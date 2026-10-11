package it.example.anteprime

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

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
    var zoom by remember { mutableFloatStateOf(1f) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(title, maxLines = 1) },
            navigationIcon = { TextButton(onClick = onBack) { Text("‹ Indietro") } },
            actions = {
                TextButton(onClick = { zoom = (zoom - 0.5f).coerceAtLeast(1f) }) { Text("−") }
                TextButton(onClick = { zoom = (zoom + 0.5f).coerceAtMost(2.5f) }) { Text("+") }
            }
        )
    }) { pad ->
        BoxWithConstraints(Modifier.padding(pad).fillMaxSize()) {
            val baseW = maxWidth
            val px = with(LocalDensity.current) { (baseW * zoom).roundToPx() }.coerceAtMost(2400)
            Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
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
