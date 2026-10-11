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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_ZOOM = 4f
private const val MAX_PX = 3200 // limite larghezza bitmap, per non esaurire la memoria

/** Lettore PDF locale basato su android.graphics.pdf.PdfRenderer (nessuna libreria esterna). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfScreen(file: File, title: String, onBack: () -> Unit) {
    val pfd = remember { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }
    val renderer = remember { PdfRenderer(pfd) }
    val mutex = remember { Mutex() } // PdfRenderer apre una sola pagina alla volta
    DisposableEffect(Unit) {
        onDispose { runBlocking { mutex.withLock { renderer.close(); pfd.close() } } }
    }
    var zoom by remember { mutableFloatStateOf(1f) }          // zoom "live" (layout)
    var renderZoom by remember { mutableFloatStateOf(1f) }    // zoom usato per rasterizzare, con debounce
    val hScroll = rememberScrollState()
    val pendingScroll = remember { floatArrayOf(Float.NaN) }  // scroll X da applicare dopo un pinch

    // rirenderizza a risoluzione piena solo quando lo zoom si ferma
    LaunchedEffect(zoom) { delay(250); renderZoom = zoom }
    // mantiene il punto sotto le dita fermo durante il pinch
    LaunchedEffect(zoom) {
        val target = pendingScroll[0]
        if (!target.isNaN()) {
            pendingScroll[0] = Float.NaN
            withFrameNanos { }
            hScroll.scrollTo(target.toInt().coerceAtLeast(0))
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(title, maxLines = 1) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } },
            actions = {
                IconButton(onClick = { zoom = (zoom - 0.5f).coerceAtLeast(1f) }) { Icon(Icons.Default.Remove, "Riduci") }
                IconButton(onClick = { zoom = (zoom + 0.5f).coerceAtMost(MAX_ZOOM) }) { Icon(Icons.Default.Add, "Ingrandisci") }
            }
        )
    }) { pad ->
        BoxWithConstraints(Modifier.padding(pad).fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
            val baseW = maxWidth
            val px = with(LocalDensity.current) { (baseW * renderZoom).roundToPx() }.coerceAtMost(MAX_PX)
            Box(
                Modifier
                    .fillMaxSize()
                    // pinch-to-zoom: intercetta solo i gesti a 2+ dita, così lo scroll a 1 dito resta alla lista
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.changes.size >= 2) {
                                    val z = event.calculateZoom()
                                    if (z != 1f) {
                                        val old = zoom
                                        val new = (old * z).coerceIn(1f, MAX_ZOOM)
                                        if (new != old) {
                                            val cx = event.calculateCentroid().x
                                            pendingScroll[0] = (hScroll.value + cx) * (new / old) - cx
                                            zoom = new
                                        }
                                    }
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                    // doppio tap: alterna 1x / 2.5x
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { zoom = if (zoom > 1.5f) 1f else 2.5f })
                    }
                    .horizontalScroll(hScroll)
            ) {
                LazyColumn(Modifier.width(baseW * zoom)) {
                    items(renderer.pageCount) { i -> PdfPage(renderer, mutex, i, px) }
                }
            }
        }
    }
}

@Composable
private fun PdfPage(renderer: PdfRenderer, mutex: Mutex, index: Int, widthPx: Int) {
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
    val b = bmp
    if (b != null) {
        Image(b.asImageBitmap(), "Pagina ${index + 1}", Modifier.fillMaxWidth().padding(bottom = 4.dp), contentScale = ContentScale.FillWidth)
    } else {
        Box(Modifier.fillMaxWidth().height(480.dp), Alignment.Center) { CircularProgressIndicator() }
    }
}
