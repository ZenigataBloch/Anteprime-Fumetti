package it.example.anteprime

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Scarica un documento Issuu (Manicomix) come PDF.
 * 1) dalla pagina https://issuu.com/<utente>/docs/<nome> legge revisionId, publicationId e numero di pagine;
 * 2) scarica ogni pagina da https://image.isu.pub/<revisionId>-<publicationId>/jpg/page_<n>.jpg (circa 2115x2990);
 * 3) le pagine più larghe di MAX_W vengono ridotte e ricompresse (il PDF resta di dimensioni ragionevoli);
 * 4) scrive un PDF minimale con i JPEG incorporati (nessuna libreria esterna).
 * Se Issuu cambia la struttura della pagina o degli indirizzi, il download fallisce con un messaggio chiaro.
 */
object IssuuDownloader {
    private const val MAX_W = 1600   // larghezza massima delle pagine nel PDF, in pixel
    private const val QUALITY = 80   // qualità JPEG quando si ricomprime
    private const val PARALLEL = 4   // pagine scaricate insieme

    private class Doc(val rev: String, val pub: String, val pages: Int)

    // nella pagina i dati sono in JSON con virgolette precedute da una o più barre rovesce
    private val DOC_RE = Regex("""pageCount\\*"\s*:\s*(\d+)\s*,\s*\\*"publicationId\\*"\s*:\s*\\*"([0-9a-f]+)\\*"\s*,\s*\\*"revisionId\\*"\s*:\s*\\*"(\d+)""")
    private val REV_RE = Regex("""revisionId\\*"\s*:\s*\\*"(\d+)""")
    private val PUB_RE = Regex("""publicationId\\*"\s*:\s*\\*"([0-9a-f]+)""")
    private val COUNT_RE = Regex("""pageCount\\*"\s*:\s*(\d+)""")

    private fun docInfo(p: Preview): Doc {
        val html = Scraper.client.newCall(Scraper.request(p.webUrl)).execute().use { r ->
            if (!r.isSuccessful) error("Issuu HTTP ${r.code}")
            r.body!!.string()
        }
        DOC_RE.find(html)?.let { return Doc(it.groupValues[3], it.groupValues[2], it.groupValues[1].toInt()) }
        val rev = REV_RE.find(html)?.groupValues?.get(1)
        val pub = PUB_RE.find(html)?.groupValues?.get(1)
        val n = COUNT_RE.find(html)?.groupValues?.get(1)?.toIntOrNull()
        if (rev == null || pub == null || n == null || n <= 0) error("Issuu: struttura della pagina cambiata, dati del documento non trovati")
        return Doc(rev, pub, n)
    }

    private fun fetchPage(d: Doc, n: Int): ByteArray {
        val url = "https://image.isu.pub/${d.rev}-${d.pub}/jpg/page_$n.jpg"
        var last: Exception? = null
        repeat(3) {
            try {
                Scraper.client.newCall(Scraper.request(url)).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    return r.body!!.bytes()
                }
            } catch (e: Exception) {
                last = e
            }
        }
        throw IllegalStateException("pagina $n: ${last?.message}")
    }

    private fun <T> Future<T>.result(): T =
        try { get() } catch (e: ExecutionException) { throw (e.cause ?: e) }

    private class Page(val jpg: ByteArray, val w: Int, val h: Int, val comps: Int)

    /** Larghezza, altezza e numero di componenti di un JPEG, leggendo l'intestazione (SOF). */
    private fun jpegInfo(b: ByteArray): IntArray? {
        if (b.size < 4 || (b[0].toInt() and 0xFF) != 0xFF || (b[1].toInt() and 0xFF) != 0xD8) return null
        var i = 2
        while (i + 9 < b.size) {
            if ((b[i].toInt() and 0xFF) != 0xFF) { i++; continue }
            val m = b[i + 1].toInt() and 0xFF
            if (m == 0xFF) { i++; continue }
            if (m == 0xD8 || m == 0x01 || m in 0xD0..0xD7) { i += 2; continue }
            val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) {
                val h = ((b[i + 5].toInt() and 0xFF) shl 8) or (b[i + 6].toInt() and 0xFF)
                val w = ((b[i + 7].toInt() and 0xFF) shl 8) or (b[i + 8].toInt() and 0xFF)
                return intArrayOf(w, h, b[i + 9].toInt() and 0xFF)
            }
            i += 2 + len
        }
        return null
    }

    /** Pagina pronta per il PDF: il JPEG originale se è già abbastanza piccolo, altrimenti ridotto e ricompresso. */
    private fun prepare(raw: ByteArray): Page {
        val info = jpegInfo(raw)
        if (info != null && (info[2] == 1 || info[2] == 3) && info[0] <= MAX_W) return Page(raw, info[0], info[1], info[2])
        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: error("immagine non leggibile")
        val w = minOf(MAX_W, bmp.width)
        val h = (bmp.height.toLong() * w / bmp.width).toInt().coerceAtLeast(1)
        val scaled = if (w == bmp.width) bmp else Bitmap.createScaledBitmap(bmp, w, h, true)
        val bos = ByteArrayOutputStream(256 * 1024)
        scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, bos)
        if (scaled !== bmp) scaled.recycle()
        bmp.recycle()
        return Page(bos.toByteArray(), w, h, 3)
    }

    /** Scrive il PDF in [out]; [onProgress] riceve (pagine fatte, pagine totali). Blocca il thread: chiamare fuori dal main. */
    fun download(p: Preview, out: File, onProgress: (Int, Int) -> Unit) {
        val d = docInfo(p)
        val pool = Executors.newFixedThreadPool(PARALLEL)
        try {
            PdfWriter(out).use { pdf ->
                var n = 1
                while (n <= d.pages) {
                    val batch = (n until minOf(n + PARALLEL, d.pages + 1)).toList()
                    // i download di un gruppo di pagine corrono in parallelo; ricompressione e scrittura una pagina alla volta
                    val raws = pool.invokeAll(batch.map { k -> Callable { fetchPage(d, k) } }).map { it.result() }
                    for (raw in raws) {
                        val pg = prepare(raw)
                        pdf.addJpeg(pg.jpg, pg.w, pg.h, pg.comps)
                    }
                    n += batch.size
                    onProgress(n - 1, d.pages)
                }
                pdf.finish()
            }
        } finally {
            pool.shutdownNow()
        }
    }

    /** Scrittore di PDF minimale: una pagina per immagine JPEG (DCTDecode), larga 600 pt. */
    private class PdfWriter(file: File) : Closeable {
        private val out = BufferedOutputStream(FileOutputStream(file), 64 * 1024)
        private var pos = 0L
        private val off = HashMap<Int, Long>() // numero oggetto -> posizione nel file
        private val kids = ArrayList<Int>()
        private var next = 3 // 1 = catalogo, 2 = elenco pagine (scritto alla fine)

        private fun w(s: String) { w(s.toByteArray(Charsets.ISO_8859_1)) }
        private fun w(b: ByteArray) { out.write(b); pos += b.size }
        private fun obj(n: Int) { off[n] = pos; w("$n 0 obj\n") }

        init {
            w("%PDF-1.4\n")
            w(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))
            obj(1)
            w("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        }

        fun addJpeg(jpg: ByteArray, wpx: Int, hpx: Int, comps: Int) {
            val img = next++
            val cont = next++
            val page = next++
            val cs = if (comps == 1) "/DeviceGray" else "/DeviceRGB"
            obj(img)
            w("<< /Type /XObject /Subtype /Image /Width $wpx /Height $hpx /ColorSpace $cs /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpg.size} >>\nstream\n")
            w(jpg)
            w("\nendstream\nendobj\n")
            val pw = 600
            val ph = String.format(Locale.US, "%.2f", pw.toDouble() * hpx / wpx)
            val content = "q $pw 0 0 $ph 0 0 cm /Im0 Do Q"
            obj(cont)
            w("<< /Length ${content.length} >>\nstream\n$content\nendstream\nendobj\n")
            obj(page)
            w("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $pw $ph] /Resources << /XObject << /Im0 $img 0 R >> >> /Contents $cont 0 R >>\nendobj\n")
            kids.add(page)
        }

        fun finish() {
            obj(2)
            w("<< /Type /Pages /Count ${kids.size} /Kids [${kids.joinToString(" ") { "$it 0 R" }}] >>\nendobj\n")
            val xref = pos
            w("xref\n0 $next\n")
            w("0000000000 65535 f \n")
            for (i in 1 until next) w(String.format(Locale.US, "%010d 00000 n \n", off.getValue(i)))
            w("trailer\n<< /Size $next /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
            out.flush()
        }

        override fun close() = out.close()
    }
}
