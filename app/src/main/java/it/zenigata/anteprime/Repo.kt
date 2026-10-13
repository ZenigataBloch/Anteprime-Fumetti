package it.zenigata.anteprime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** PDF salvati in filesDir/pdf + indice JSON per mostrarli anche offline. */
class Repo(ctx: Context) {
    private val dir = File(ctx.filesDir, "pdf").apply { mkdirs() }
    private val prefs = ctx.getSharedPreferences("index", Context.MODE_PRIVATE)

    // Repo viene creato anche dai worker (ScanWorker, opere seguite): il blocco è condiviso fra tutte le istanze,
    // così due download in parallelo non si sovrascrivono l'indice a vicenda.
    private companion object { val LOCK = Any() }

    private val seenPrefs = ctx.getSharedPreferences("seen", Context.MODE_PRIVATE)
    fun seen(): Set<String> = seenPrefs.getStringSet("ids", emptySet())!!.toSet()
    fun markSeen(ids: Collection<String>) {
        synchronized(LOCK) { seenPrefs.edit().putStringSet("ids", seen() + ids).apply() }
    }

    // ultima pagina letta di ogni PDF scaricato (0-based); si azzera quando il file viene cancellato
    private val pagePrefs = ctx.getSharedPreferences("lastpage", Context.MODE_PRIVATE)
    fun lastPage(p: Preview): Int = pagePrefs.getInt(p.id, 0)
    fun setLastPage(p: Preview, page: Int) { pagePrefs.edit().putInt(p.id, page).apply() }

    fun file(p: Preview) = File(dir, "${p.id}.pdf")
    fun localIds(): Set<String> = dir.listFiles { f -> f.extension == "pdf" }?.map { it.nameWithoutExtension }?.toSet() ?: emptySet()

    /** onProgress (pagine fatte, totali) serve solo ai documenti Issuu (Manicomix), che si scaricano pagina per pagina. */
    fun download(p: Preview, onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        val tmp = File(dir, "${p.id}.tmp")
        try {
            if (p.source == Source.MANICOMIX) {
                IssuuDownloader.download(p, tmp, onProgress)
            } else {
                Scraper.client.newCall(Scraper.request(p.url)).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    r.body!!.byteStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o, 256 * 1024) } }
                }
                // (readNBytes esiste solo da Android 13: su minSdk 24 farebbe crashare il download)
                val magic = tmp.inputStream().use { i -> ByteArray(4).also { i.read(it) }.toString(Charsets.US_ASCII) }
                if (magic != "%PDF") error("il file scaricato non è un PDF")
            }
            if (!tmp.renameTo(file(p))) error("impossibile salvare il file")
        } catch (e: Exception) {
            tmp.delete() // niente .tmp orfani se il download fallisce
            throw e
        }
        synchronized(LOCK) { saveIndex(localIndex().filter { it.id != p.id } + p) }
    }

    fun delete(p: Preview) {
        PageText.cacheFile(file(p)).delete() // testo OCR per la ricerca
        file(p).delete()
        pagePrefs.edit().remove(p.id).apply() // cancellato e riscaricato = si riparte dalla prima pagina
        synchronized(LOCK) { saveIndex(localIndex().filter { it.id != p.id }) }
    }

    fun localIndex(): List<Preview> {
        val ids = localIds()
        val arr = JSONArray(prefs.getString("items", "[]"))
        val out = ArrayList<Preview>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val p = Preview(Source.valueOf(o.getString("s")), o.getString("t"), o.getString("u"))
            if (p.id in ids) out += p
        }
        return out
    }

    private fun saveIndex(list: List<Preview>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("s", it.source.name).put("t", it.title).put("u", it.url)) }
        prefs.edit().putString("items", arr.toString()).apply()
    }
}
