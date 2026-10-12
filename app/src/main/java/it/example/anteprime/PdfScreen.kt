package it.example.anteprime

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val MAX_ZOOM = 4f
private const val MAX_PX = 3200 // limite larghezza bitmap, per non esaurire la memoria
private const val MAX_PIXELS = 10_000_000 // limite ai pixel totali di una pagina (~40 MB): conta anche l'altezza, non solo la larghezza
private const val OCR_WIDTH = 2000 // larghezza a cui si rasterizza la pagina per riconoscere il testo selezionato
private val BAR_ZONE = 28.dp // fascia a destra riservata alla scrollbar quando è visibile
private val THUMB_H = 56.dp

private data class NoteDraft(val text: String, val page: Int)

/**
 * Lettore PDF locale basato su android.graphics.pdf.PdfRenderer (nessuna libreria esterna per la visualizzazione).
 * Zoom: pinch (ancorato al punto sotto le dita), doppio tap, pulsanti + / -.
 * Scrollbar: compare mentre si scorre, si trascina per saltare di pagina (con indicatore "pag. X / N").
 * Note: pulsante matita = nota scritta a mano; evidenziatore = si trascina un rettangolo sul testo e
 * il testo viene riconosciuto (OCR) e proposto come nota, da correggere e taggare prima di salvare (tag creati e cancellati da te).
 * onPageChanged: chiamato a ogni cambio di pagina in cima (serve a ricordare dove si era arrivati).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfScreen(
    file: File,
    title: String,
    startPage: Int = 0,
    allTags: List<String> = emptyList(),
    onCreateTag: (String) -> Unit = {},
    onDeleteTag: (String) -> Unit = {},
    onAddNote: (text: String, page: Int, tags: List<String>, release: String) -> Unit,
    onPageChanged: (page: Int) -> Unit = {},
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pfd = remember { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }
    val renderer = remember { PdfRenderer(pfd) }
    val mutex = remember { Mutex() } // PdfRenderer apre una sola pagina alla volta
    DisposableEffect(Unit) {
        // chiusura fuori dal thread principale: se un rendering è in corso, "indietro" non si blocca ad aspettarlo
        onDispose { CoroutineScope(Dispatchers.IO).launch { mutex.withLock { renderer.close(); pfd.close() } } }
    }

    // altezza/larghezza di ogni pagina: servono per calcolare con precisione il punto di ancoraggio dello zoom.
    // Aprire tutte le pagine una per una è lento sui PDF lunghi, quindi il risultato è in cache su disco.
    val ratios by produceState<List<Float>?>(null) {
        val cache = File(ctx.cacheDir, "ratios_${file.name}.txt")
        value = withContext(Dispatchers.Default) {
            val n = mutex.withLock { renderer.pageCount }
            readRatios(cache, file.length(), n) ?: mutex.withLock {
                List(n) { i -> renderer.openPage(i).use { it.height.toFloat() / it.width } }
            }.also { writeRatios(cache, file.length(), it) }
        }
    }

    var zoom by remember { mutableFloatStateOf(1f) }        // zoom "live" (layout)
    var renderZoom by remember { mutableFloatStateOf(1f) }  // zoom usato per rasterizzare, con debounce
    var hx by remember { mutableFloatStateOf(0f) }          // scorrimento orizzontale in px
    var viewW by remember { mutableFloatStateOf(0f) }
    var viewH by remember { mutableFloatStateOf(0f) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startPage.coerceAtLeast(0))
    val gap = 4.dp
    val gapPx = with(LocalDensity.current) { gap.toPx() }
    val barZonePx = with(LocalDensity.current) { BAR_ZONE.toPx() }
    var barVisible by remember { mutableStateOf(false) }    // scrollbar visibile (e quindi afferrabile)

    // ricorda la pagina in cima: salva solo quando cambia (SharedPreferences.apply è leggero)
    val pageCallback by rememberUpdatedState(onPageChanged)
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { pageCallback(it) }
    }

    // --- note ---
    var selectMode by remember { mutableStateOf(false) }     // evidenziatore attivo
    var selRect by remember { mutableStateOf<Rect?>(null) }  // rettangolo in corso di selezione (px del viewport)
    var ocrBusy by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<NoteDraft?>(null) }

    // rirenderizza a risoluzione piena solo quando lo zoom si ferma
    LaunchedEffect(zoom) { delay(250); renderZoom = zoom }

    /**
     * Cambia lo zoom in modo che il punto che era sotto (fromX, fromY) finisca sotto (toX, toY).
     * Coordinate in px relative al viewport. Se from == to è uno zoom puro; se differiscono, zoom + pan.
     */
    fun applyZoom(target: Float, fromX: Float, fromY: Float, toX: Float, toY: Float) {
        val old = zoom
        val new = target.coerceIn(1f, MAX_ZOOM)
        val r = ratios
        val wOld = viewW * old
        val wNew = viewW * new

        // --- verticale: la pagina e la frazione di pagina sotto il dito restano sotto il dito ---
        if (r != null && r.isNotEmpty() && viewW > 0f) {
            val idx0 = listState.firstVisibleItemIndex.coerceIn(0, r.lastIndex)
            var top0 = 0f
            for (j in 0 until idx0) top0 += wOld * r[j] + gapPx
            val y = top0 + listState.firstVisibleItemScrollOffset + fromY

            var k = 0
            var kTop = 0f
            while (k < r.lastIndex && kTop + wOld * r[k] + gapPx <= y) { kTop += wOld * r[k] + gapPx; k++ }
            val f = ((y - kTop) / (wOld * r[k])).coerceIn(0f, 1f)

            var newTop = 0f
            for (j in 0 until k) newTop += wNew * r[j] + gapPx
            val a = (newTop + f * wNew * r[k] - toY).coerceAtLeast(0f)

            var i = 0
            var t = 0f
            while (i < r.lastIndex && t + wNew * r[i] + gapPx <= a) { t += wNew * r[i] + gapPx; i++ }
            listState.requestScrollToItem(i, (a - t).roundToInt().coerceAtLeast(0))
        }

        // --- orizzontale ---
        hx = ((hx + fromX) * (new / old) - toX).coerceIn(0f, viewW * (new - 1f))
        zoom = new
    }

    /**
     * Riconosce il testo sotto il rettangolo selezionato (px del viewport): trova la pagina,
     * la rasterizza, ritaglia la zona e la passa all'OCR. Il risultato diventa una bozza di nota.
     */
    fun recognize(sel: Rect) {
        val r = ratios ?: return
        if (r.isEmpty() || viewW <= 0f) { selRect = null; return }
        val w = viewW * zoom

        // posizione del rettangolo nel "contenuto" (tutte le pagine una sotto l'altra, già zoomate)
        val idx0 = listState.firstVisibleItemIndex.coerceIn(0, r.lastIndex)
        var top0 = 0f
        for (j in 0 until idx0) top0 += w * r[j] + gapPx
        val y0 = top0 + listState.firstVisibleItemScrollOffset
        val centerY = y0 + sel.center.y
        var k = 0
        var kTop = 0f
        while (k < r.lastIndex && kTop + w * r[k] + gapPx <= centerY) { kTop += w * r[k] + gapPx; k++ }
        val pageH = w * r[k]

        // frazioni di pagina (0..1) occupate dalla selezione
        val fx0 = ((hx + sel.left) / w).coerceIn(0f, 1f)
        val fx1 = ((hx + sel.right) / w).coerceIn(0f, 1f)
        val fy0 = ((y0 + sel.top - kTop) / pageH).coerceIn(0f, 1f)
        val fy1 = ((y0 + sel.bottom - kTop) / pageH).coerceIn(0f, 1f)
        if (fx1 - fx0 < 0.01f || fy1 - fy0 < 0.003f) { selRect = null; return } // tocco o selezione minuscola

        ocrBusy = true
        val page = k
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    val full = mutex.withLock {
                        renderer.openPage(page).use { p ->
                            val h = (OCR_WIDTH * p.height.toFloat() / p.width).toInt()
                            Bitmap.createBitmap(OCR_WIDTH, h, Bitmap.Config.ARGB_8888).also {
                                it.eraseColor(android.graphics.Color.WHITE)
                                p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        }
                    }
                    val cx = (fx0 * full.width).toInt().coerceIn(0, full.width - 1)
                    val cy = (fy0 * full.height).toInt().coerceIn(0, full.height - 1)
                    val cw = ((fx1 - fx0) * full.width).toInt().coerceIn(1, full.width - cx)
                    val ch = ((fy1 - fy0) * full.height).toInt().coerceIn(1, full.height - cy)
                    val crop = Bitmap.createBitmap(full, cx, cy, cw, ch)
                    if (crop !== full) full.recycle()
                    TextOcr.read(crop)
                }
            }
            result.onSuccess { raw ->
                // a capo -> spazio; "paro-\nla" -> "parola"
                val clean = raw
                    .replace(Regex("""(?<=\p{L})-\s*\n\s*(?=\p{Ll})"""), "")
                    .replace(Regex("""\s*\n\s*"""), " ")
                    .trim()
                if (clean.isBlank()) {
                    Toast.makeText(ctx, "Nessun testo riconosciuto: riprova con una selezione più ampia", Toast.LENGTH_SHORT).show()
                } else {
                    draft = NoteDraft(clean, page)
                }
            }.onFailure {
                Toast.makeText(ctx, "Riconoscimento non riuscito: ${it.message}", Toast.LENGTH_LONG).show()
            }
            ocrBusy = false
            selRect = null
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(title, maxLines = 1) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } },
            actions = {
                IconButton(onClick = { draft = NoteDraft("", listState.firstVisibleItemIndex) }) { Icon(Icons.Default.Edit, "Nuova nota") }
                IconToggleButton(checked = selectMode, onCheckedChange = { selectMode = it; selRect = null }) {
                    Icon(
                        HighlighterIcon,
                        contentDescription = "Evidenzia testo",
                        tint = if (selectMode) MaterialTheme.colorScheme.primary else LocalContentColor.current
                    )
                }
                IconButton(onClick = { applyZoom(zoom - 0.5f, viewW / 2, viewH / 2, viewW / 2, viewH / 2) }) { Icon(Icons.Default.Remove, "Riduci") }
                IconButton(onClick = { applyZoom(zoom + 0.5f, viewW / 2, viewH / 2, viewW / 2, viewH / 2) }) { Icon(Icons.Default.Add, "Ingrandisci") }
            }
        )
    }) { pad ->
        BoxWithConstraints(Modifier.padding(pad).fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
            val vw = constraints.maxWidth.toFloat()
            val vh = constraints.maxHeight.toFloat()
            SideEffect { viewW = vw; viewH = vh }
            val density = LocalDensity.current
            val layoutW = with(density) { (vw * zoom).toDp() }
            val px = (vw * renderZoom).roundToInt().coerceAtMost(MAX_PX)

            Box(
                Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    // pinch: intercetta i gesti a 2+ dita (zoom + pan); a 1 dito scorre la lista,
                    // tranne con l'evidenziatore attivo: allora a 1 dito si disegna il rettangolo di selezione
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            // un tocco sulla scrollbar (quando è visibile) è della scrollbar
                            if (barVisible && down.position.x > viewW - barZonePx) return@awaitEachGesture
                            val selecting = selectMode && !ocrBusy
                            var multiTouch = false
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (selecting && event.changes.size == 1) {
                                    val c = event.changes[0].position
                                    selRect = Rect(
                                        minOf(down.position.x, c.x), minOf(down.position.y, c.y),
                                        maxOf(down.position.x, c.x), maxOf(down.position.y, c.y)
                                    )
                                    event.changes[0].consume()
                                } else if (event.changes.size >= 2) {
                                    multiTouch = true
                                    val z = event.calculateZoom()
                                    val c0 = event.calculateCentroid(useCurrent = false)
                                    val c1 = event.calculateCentroid(useCurrent = true)
                                    if (c0.isSpecified && c1.isSpecified) applyZoom(zoom * z, c0.x, c0.y, c1.x, c1.y)
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                } else if (zoom > 1f && event.changes.size == 1) {
                                    // pan orizzontale a 1 dito (il verticale lo gestisce la lista)
                                    val d = event.changes[0].positionChange()
                                    if (abs(d.x) > abs(d.y)) hx = (hx - d.x).coerceIn(0f, viewW * (zoom - 1f))
                                }
                            } while (event.changes.any { it.pressed })
                            // fine del gesto: con l'evidenziatore parte il riconoscimento del testo
                            val sel = selRect
                            if (selecting && !multiTouch && sel != null) recognize(sel) else selRect = null
                        }
                    }
                    // doppio tap: alterna 1x / 2.5x sul punto toccato
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { o ->
                            applyZoom(if (zoom > 1.5f) 1f else 2.5f, o.x, o.y, o.x, o.y)
                        })
                    }
            ) {
                val r = ratios
                if (r == null) {
                    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                } else {
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(gap),
                        modifier = Modifier
                            .offset { IntOffset(-hx.roundToInt(), 0) }
                            .fillMaxHeight()
                            .wrapContentWidth(Alignment.Start, unbounded = true)
                            .width(layoutW)
                    ) {
                        items(r.size) { i -> PdfPage(renderer, mutex, i, px, r[i]) }
                    }
                }

                // rettangolo di selezione (evidenziatore)
                Canvas(Modifier.fillMaxSize()) {
                    selRect?.let {
                        drawRect(Color(0x66FFEB3B), it.topLeft, it.size)
                        drawRect(Color(0xFFFFC107), it.topLeft, it.size, style = Stroke(width = 2.dp.toPx()))
                    }
                }
                if (selectMode && !ocrBusy) {
                    Surface(
                        color = MaterialTheme.colorScheme.inverseSurface,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
                    ) {
                        Text(
                            "Trascina per evidenziare il testo da salvare",
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
                if (ocrBusy) CircularProgressIndicator(Modifier.align(Alignment.Center))

                // scrollbar (sopra a tutto il resto)
                val rr = ratios
                if (rr != null) PdfScrollbar(listState, rr, vw, vh, zoom, gapPx, barVisible) { barVisible = it }
            }
        }
    }

    draft?.let { d ->
        NoteDialog(
            title = "Nota · pag. ${d.page + 1}",
            initial = d.text,
            allTags = allTags,
            onCreateTag = onCreateTag,
            onDeleteTag = onDeleteTag,
            onDismiss = { draft = null; selectMode = false },
            onSave = { text, tags, release ->
                onAddNote(text, d.page, tags, release)
                draft = null
                selectMode = false
                Toast.makeText(ctx, "Nota salvata", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

/**
 * Scrollbar trascinabile. Le altezze di tutte le pagine sono note (ratios), quindi la posizione del pollice
 * e il salto sono esatti anche con lo zoom. Compare mentre si scorre e sparisce dopo 1,5 s;
 * mentre è visibile si può afferrare: mostra "pag. X / N" e salta direttamente al punto scelto.
 */
@Composable
private fun PdfScrollbar(
    listState: LazyListState,
    ratios: List<Float>,
    viewW: Float,
    viewH: Float,
    zoom: Float,
    gapPx: Float,
    visible: Boolean,
    onVisible: (Boolean) -> Unit
) {
    val density = LocalDensity.current
    val thumbH = with(density) { THUMB_H.toPx() }
    var dragging by remember { mutableStateOf(false) }
    val scrolling = listState.isScrollInProgress
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) onVisible(true) else { delay(1500); onVisible(false) }
    }
    val alpha by animateFloatAsState(if (visible) 1f else 0f, label = "scrollbar")

    // inizio (px) di ogni pagina nel contenuto già zoomato
    val w = viewW * zoom
    val starts = remember(ratios, w, gapPx) {
        val a = FloatArray(ratios.size)
        var y = 0f
        for (i in ratios.indices) { a[i] = y; y += w * ratios[i] + gapPx }
        a
    }
    val maxScroll = if (starts.isEmpty()) 0f else starts.last() + w * ratios.last() - viewH
    val startsNow by rememberUpdatedState(starts)
    val maxNow by rememberUpdatedState(maxScroll)
    val viewHNow by rememberUpdatedState(viewH)

    if (starts.isEmpty() || maxScroll <= 1f || viewH <= thumbH) return

    val first = listState.firstVisibleItemIndex.coerceIn(0, starts.lastIndex)
    val scrollY = starts[first] + listState.firstVisibleItemScrollOffset
    val frac = (scrollY / maxScroll).coerceIn(0f, 1f)
    val thumbTop = frac * (viewH - thumbH)
    val page = if (frac >= 0.999f) starts.size else indexAt(starts, scrollY + viewH / 2) + 1

    val jumpTo = { y: Float ->
        val f = ((y - thumbH / 2) / (viewHNow - thumbH)).coerceIn(0f, 1f)
        val target = f * maxNow
        val st = startsNow
        val i = indexAt(st, target)
        listState.requestScrollToItem(i, (target - st[i]).roundToInt().coerceAtLeast(0))
    }

    Box(Modifier.fillMaxSize()) {
        if (dragging) {
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.align(Alignment.TopEnd).offset {
                    IntOffset(-48.dp.roundToPx(), (thumbTop + thumbH / 2 - 14.dp.toPx()).roundToInt())
                }
            ) {
                Text(
                    "pag. $page / ${starts.size}",
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .width(BAR_ZONE)
                .fillMaxHeight()
                .then(
                    // il tocco è gestito solo quando la scrollbar si vede: altrimenti il bordo destro resta della pagina
                    if (visible) Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            dragging = true
                            jumpTo(down.position.y)
                            down.consume()
                            do {
                                val ev = awaitPointerEvent()
                                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (c.pressed) { jumpTo(c.position.y); c.consume() }
                            } while (ev.changes.any { it.pressed })
                            dragging = false
                        }
                    } else Modifier
                )
        ) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, thumbTop.roundToInt()) }
                    .padding(end = 4.dp)
                    .width(8.dp)
                    .height(THUMB_H)
                    .alpha(alpha)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.8f), RoundedCornerShape(4.dp))
            )
        }
    }
}

/** Indice dell'ultima pagina che inizia a y o prima (ricerca binaria su starts, che è crescente). */
private fun indexAt(starts: FloatArray, y: Float): Int {
    var lo = 0
    var hi = starts.lastIndex
    while (lo < hi) {
        val mid = (lo + hi + 1) / 2
        if (starts[mid] <= y) lo = mid else hi = mid - 1
    }
    return lo
}

// cache su disco delle proporzioni delle pagine: prima riga = dimensione del PDF (per accorgersi di un file diverso)
private fun readRatios(f: File, len: Long, n: Int): List<Float>? = runCatching {
    val lines = f.readLines()
    if (lines.size != 2 || lines[0].toLong() != len) return@runCatching null
    lines[1].split(',').map { it.toFloat() }.takeIf { it.size == n }
}.getOrNull()

private fun writeRatios(f: File, len: Long, r: List<Float>) {
    runCatching { f.writeText("$len\n${r.joinToString(",")}") }
}

@Composable
private fun PdfPage(renderer: PdfRenderer, mutex: Mutex, index: Int, widthPx: Int, ratio: Float) {
    // oltre al limite di larghezza, un tetto ai pixel totali: pagine molto alte non fanno esplodere la memoria
    val w = minOf(widthPx, sqrt(MAX_PIXELS / ratio).toInt()).coerceAtLeast(1)
    val bmp by produceState<Bitmap?>(null, index, w) {
        value = withContext(Dispatchers.Default) {
            mutex.withLock {
                // se nel frattempo la pagina è uscita dallo schermo (scrollbar, scorrimento veloce) non si renderizza
                ensureActive()
                renderer.openPage(index).use { p ->
                    val h = (w * p.height.toFloat() / p.width).toInt()
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(android.graphics.Color.WHITE)
                        p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    }
    // l'altezza è fissata dalle proporzioni della pagina: non cambia quando la bitmap viene caricata
    Box(Modifier.fillMaxWidth().aspectRatio(1f / ratio), Alignment.Center) {
        val b = bmp
        if (b != null) {
            val img = remember(b) { b.asImageBitmap() }
            Image(img, "Pagina ${index + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        } else {
            CircularProgressIndicator()
        }
    }
}
