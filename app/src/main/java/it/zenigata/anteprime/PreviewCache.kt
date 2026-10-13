package it.zenigata.anteprime

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Cache su disco dell'ultima lista di anteprime di ogni fonte.
 * All'avvio si mostra subito questa; intanto si aggiorna dalla rete e si risalva.
 * Chiamare load/save fuori dal thread principale (Dispatchers.IO).
 */
object PreviewCache {
    private fun file(dir: File, source: Source) = File(dir, "lista_$source.json")

    fun load(dir: File, source: Source): List<Preview> = runCatching {
        val arr = JSONArray(file(dir, source).readText())
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Preview(source, o.getString("t"), o.getString("u"), isPdf = o.optBoolean("p", true))
        }
    }.getOrDefault(emptyList())

    fun save(dir: File, source: Source, list: List<Preview>) {
        runCatching {
            val arr = JSONArray()
            list.forEach { arr.put(JSONObject().put("t", it.title).put("u", it.url).put("p", it.isPdf)) }
            val tmp = File(dir, "lista_$source.tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(file(dir, source)) // scrittura atomica: niente file a metà
        }
    }
}
