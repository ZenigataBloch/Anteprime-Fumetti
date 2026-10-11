package it.example.anteprime

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

private const val MAX_ZOOM = 4f
private const val MAX_PX = 3200 // limite larghezza bitmap, per non esaurire la memoria

/**
 * Lettore PDF locale basato su android.graphics.pdf.PdfRenderer (nessuna libreria esterna).
 * Zoom: pinch (ancorato al punto sotto le dita), doppio tap, pulsanti + / -.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfScreen(file: File, title: String, onBack: () -> Unit) {
    val pfd = remember { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }
    val renderer = remember { PdfRenderer(pfd) }
    val mutex = remember { Mutex() } // PdfRenderer apre una sola pagina alla volta
    DisposableEffect(Unit) {
        onDispose { runBlocking { mutex.withLock { renderer.close(); pfd.close() } } }
    }

    // altezza/larghezza di ogni pagina: servono per calcolare con precisione il punto di ancoraggio dello zoom
    val ratios by produceState<List<Float>?>(null) {
        value = withContext(Dispatchers.Default) {
            mutex.withLock {
                List(renderer.pageCount) { i -> renderer.openPage(i).use { it.height.toFloat() / it.width } }
            }
        }
    }

    var zoom by remember { mutableFloatStateOf(1f) }        // zoom "live" (layout)
    var renderZoom by remember { mutableFloatStateOf(1f) }  // zoom usato per rasterizzare, con debounce
    var hx by remember { mutableFloatStateOf(0f) }          // scorrimento orizzontale in px
    var viewW by remember { mutableFloatStateOf(0f) }
    var viewH by remember { mutableFloatStateOf(0f) }
    val listState = rememberLazyListState()
    val gap = 4.dp
    val gapPx = with(LocalDensity.current) { gap.toPx() }

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

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(title, maxLines = 1) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } },
            actions = {
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
                    // pinch: intercetta solo i gesti a 2+ dita (zoom + pan); a 1 dito scorre la lista
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.changes.size >= 2) {
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
            }
        }
    }
}

@Composable
private fun PdfPage(renderer: PdfRenderer, mutex: Mutex, index: Int, widthPx: Int, ratio: Float) {
    val bmp by produceState<Bitmap?>(null, index, widthPx) {
        value = withContext(Dispatchers.Default) {
            mutex.withLock {
                renderer.openPage(index).use { p ->
                    val h = (widthPx * p.height.toFloat() / p.width).toInt()
                    Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888).also {
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
            Image(b.asImageBitmap(), "Pagina ${index + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        } else {
            CircularProgressIndicator()
        }
    }
}
