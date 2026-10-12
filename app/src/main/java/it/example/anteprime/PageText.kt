package it.example.anteprime

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.sqrt

/** Una riga di testo di una pagina. Le coordinate sono frazioni (0..1) della larghezza/altezza della pagina. */
data class PageLine(val text: String, val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
    val cx: Float get() = (x0 + x1) / 2f
    val h: Float get() = y1 - y0
}

/**
 * Testo di ogni pagina del PDF, ricavato con l'OCR e messo in cache su disco (filesDir/text/<id>.json).
 * L'OCR usa un PdfRenderer proprio sullo stesso file, quindi non contende con il lettore.
 * La cache si salva a tratti: se si interrompe, la volta dopo si riparte dalle pagine mancanti.
 */
object PageText {
    private const val OCR_WIDTH = 2000
    private const val MAX_PIXELS = 10_000_000
    private const val SAVE_EVERY = 10

    /** Il PDF sta in filesDir/pdf/<id>.pdf, il testo in filesDir/text/<id>.json. */
    fun cacheFile(pdf: File): File =
        File(File(pdf.parentFile?.parentFile, "text"), pdf.nameWithoutExtension + ".json")

    /** Pagine già lette (indice 0-based -> righe). Vuota se non c'è cache o se il PDF è cambiato. Da chiamare fuori dal main thread. */
    fun load(pdf: File): Map<Int, List<PageLine>> {
        return try {
            val cache = cacheFile(pdf)
            if (!cache.exists()) return emptyMap()
            val root = JSONObject(cache.readText())
            if (root.getLong("len") != pdf.length()) return emptyMap()
            val pages = root.getJSONObject("pages")
            val out = HashMap<Int, List<PageLine>>()
            for (k in pages.keys()) {
                val arr = pages.getJSONArray(k)
                out[k.toInt()] = List(arr.length()) { i ->
                    val l = arr.getJSONObject(i)
                    PageLine(
                        l.getString("t"),
                        l.getDouble("a").toFloat(), l.getDouble("b").toFloat(),
                        l.getDouble("c").toFloat(), l.getDouble("d").toFloat()
                    )
                }
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun save(pdf: File, pages: Map<Int, List<PageLine>>) {
        try {
            val cache = cacheFile(pdf)
            cache.parentFile?.mkdirs()
            val p = JSONObject()
            for ((i, lines) in pages) {
                val arr = JSONArray()
                lines.forEach {
                    arr.put(
                        JSONObject().put("t", it.text)
                            .put("a", it.x0.toDouble()).put("b", it.y0.toDouble())
                            .put("c", it.x1.toDouble()).put("d", it.y1.toDouble())
                    )
                }
                p.put(i.toString(), arr)
            }
            val root = JSONObject().put("len", pdf.length()).put("pages", p)
            val tmp = File(cache.parentFile, cache.name + ".tmp")
            tmp.writeText(root.toString())
            tmp.renameTo(cache) // scrittura atomica: niente file a metà
        } catch (e: Exception) {
            // la cache è solo un'ottimizzazione
        }
    }

    /**
     * Legge con l'OCR le pagine che mancano in [done], una alla volta. [onPage] viene chiamata (sul thread del chiamante)
     * a ogni pagina completata; [onTotal] riceve il numero di pagine del PDF appena aperto.
     * Annullabile: alla cancellazione salva quanto fatto finora.
     */
    suspend fun build(
        pdf: File,
        done: Map<Int, List<PageLine>>,
        onTotal: (Int) -> Unit = {},
        onPage: (Int, List<PageLine>) -> Unit
    ) {
        val acc = HashMap<Int, List<PageLine>>(done)
        val pfd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        try {
            onTotal(renderer.pageCount)
            var sinceSave = 0
            for (i in 0 until renderer.pageCount) {
                if (i in acc) continue
                currentCoroutineContext().ensureActive()
                val lines = withContext(Dispatchers.Default) { ocrPage(renderer, i) }
                acc[i] = lines
                onPage(i, lines)
                if (++sinceSave >= SAVE_EVERY) {
                    sinceSave = 0
                    withContext(Dispatchers.IO) { save(pdf, acc) }
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                save(pdf, acc)
                renderer.close()
                pfd.close()
            }
        }
    }

    private suspend fun ocrPage(renderer: PdfRenderer, index: Int): List<PageLine> {
        val bmp = renderer.openPage(index).use { p ->
            val ratio = p.height.toFloat() / p.width
            val w = minOf(OCR_WIDTH, sqrt(MAX_PIXELS / ratio).toInt()).coerceAtLeast(1)
            val h = (w * ratio).toInt().coerceAtLeast(1)
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                it.eraseColor(android.graphics.Color.WHITE)
                p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }
        // niente recycle in caso di annullamento: ML Kit potrebbe ancora leggere la bitmap
        val raw = TextOcr.readLines(bmp)
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        bmp.recycle()
        return raw.filter { it.text.isNotBlank() }
            .map { PageLine(it.text, it.left / bw, it.top / bh, it.right / bw, it.bottom / bh) }
    }
}
