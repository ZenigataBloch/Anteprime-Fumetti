package it.example.anteprime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** preview = anteprima di provenienza (null = nota libera); page = pagina (0-based) oppure -1. */
data class Note(val id: Long, val text: String, val preview: Preview?, val page: Int)

/** Note salvate in SharedPreferences come JSON (la più recente per prima). */
class NotesRepo(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("notes", Context.MODE_PRIVATE)

    fun load(): List<Note> = runCatching {
        val arr = JSONArray(prefs.getString("items", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Note(
                id = o.getLong("i"),
                text = o.getString("x"),
                preview = if (o.has("u")) Preview(Source.valueOf(o.getString("s")), o.getString("t"), o.getString("u")) else null,
                page = o.optInt("p", -1)
            )
        }
    }.getOrDefault(emptyList())

    fun save(list: List<Note>) {
        val arr = JSONArray()
        list.forEach { n ->
            val o = JSONObject().put("i", n.id).put("x", n.text).put("p", n.page)
            n.preview?.let { o.put("s", it.source.name).put("t", it.title).put("u", it.url) }
            arr.put(o)
        }
        prefs.edit().putString("items", arr.toString()).apply()
    }
}
