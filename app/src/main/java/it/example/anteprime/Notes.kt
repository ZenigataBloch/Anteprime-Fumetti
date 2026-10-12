package it.example.anteprime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** release = data di uscita (testo libera e facoltativa, es. "12/11/2026").
 *  preview = anteprima di provenienza (null = nota libera); page = pagina (0-based) oppure -1; tags = categorie (mesi o custom). */
data class Note(val id: Long, val text: String, val preview: Preview?, val page: Int, val tags: List<String> = emptyList(),
    val release: String = "")

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
                page = o.optInt("p", -1),
                tags = o.optJSONArray("g")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                release = o.optString("r", "")
            )
        }
    }.getOrDefault(emptyList())

    fun save(list: List<Note>) {
        val arr = JSONArray()
        list.forEach { n ->
            val o = JSONObject().put("i", n.id).put("x", n.text).put("p", n.page).put("g", JSONArray(n.tags))
            if (n.release.isNotBlank()) o.put("r", n.release)
            n.preview?.let { o.put("s", it.source.name).put("t", it.title).put("u", it.url) }
            arr.put(o)
        }
        prefs.edit().putString("items", arr.toString()).apply()
    }
}
