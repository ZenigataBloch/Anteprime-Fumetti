package it.example.anteprime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** PDF salvati in filesDir/pdf + indice JSON per mostrarli anche offline. */
class Repo(ctx: Context) {
    private val dir = File(ctx.filesDir, "pdf").apply { mkdirs() }
    private val prefs = ctx.getSharedPreferences("index", Context.MODE_PRIVATE)

    private val seenPrefs = ctx.getSharedPreferences("seen", Context.MODE_PRIVATE)
    fun seen(): Set<String> = seenPrefs.getStringSet("ids", emptySet())!!.toSet()
    fun markSeen(ids: Collection<String>) { seenPrefs.edit().putStringSet("ids", seen() + ids).apply() }

    // ultima pagina letta di ogni PDF scaricato (0-based); si azzera quando il file viene cancellato
    private val pagePrefs = ctx.getSharedPreferences("lastpage", Context.MODE_PRIVATE)
    fun lastPage(p: Preview): Int = pagePrefs.getInt(p.id, 0)
    fun setLastPage(p: Preview, page: Int) { pagePrefs.edit().putInt(p.id, page).apply() }

    fun file(p: Preview) = File(dir, "${p.id}.pdf")
    fun localIds(): Set<String> = dir.listFiles { f -> f.extension == "pdf" }?.map { it.nameWithoutExtension }?.toSet() ?: emptySet()

    fun download(p: Preview) {
        Scraper.client.newCall(Scraper.request(p.url)).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val tmp = File(dir, "${p.id}.tmp")
            r.body!!.byteStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            val magic = tmp.inputStream().use { it.readNBytes(4).toString(Charsets.US_ASCII) }
            if (magic != "%PDF") { tmp.delete(); error("il file scaricato non è un PDF") }
            tmp.renameTo(file(p))
        }
        saveIndex(localIndex().filter { it.id != p.id } + p)
    }

    fun delete(p: Preview) {
        file(p).delete()
        pagePrefs.edit().remove(p.id).apply() // cancellato e riscaricato = si riparte dalla prima pagina
        saveIndex(localIndex().filter { it.id != p.id })
    }

    fun localIndex(): List<Preview> {
        val ids = localIds()
        val arr = JSONArray(prefs.getString("items", "[]"))
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            Preview(Source.valueOf(it.getString("s")), it.getString("t"), it.getString("u"))
        }.filter { it.id in ids }
    }

    private fun saveIndex(list: List<Preview>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("s", it.source.name).put("t", it.title).put("u", it.url)) }
        prefs.edit().putString("items", arr.toString()).apply()
    }
}
