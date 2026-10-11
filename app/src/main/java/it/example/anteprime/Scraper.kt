package it.example.anteprime

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object Scraper {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    fun request(url: String): Request = Request.Builder().url(url).header("User-Agent", UA).header("Accept-Language", "it-IT,it;q=0.9").build()

    private fun get(url: String): String = client.newCall(request(url)).execute().use { r ->
        if (!r.isSuccessful) error("HTTP ${r.code} su $url")
        r.body!!.string()
    }

    private val MESI = listOf(
        "gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno",
        "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre"
    )

    // ---------- Panini: link PDF in /media/paniniFiles/A###_Panini.pdf (oppure A###-Panini.pdf) ----------
    fun panini(): List<Preview> {
        val found = linkedSetOf("https://www.panini.it/media/paniniFiles/A421_Panini.pdf") // seed noto
        var ok = false
        var last: Throwable? = null
        for (page in listOf(
            "https://www.panini.it/shp_ita_it/anteprima.html",
            "https://www.panini.it/shp_ita_it/anteprima/anteprima-mese-scorso.html"
        )) {
            try {
                val html = get(page); ok = true
                Regex("""/media/paniniFiles/[\w\-]+\.pdf""").findAll(html)
                    .forEach { found += "https://www.panini.it" + it.value }
            } catch (e: Exception) { last = e }
        }
        if (!ok) throw last ?: IllegalStateException("pagine non raggiungibili")

        val out = linkedMapOf<Int, Preview>()
        val others = mutableListOf<Preview>()
        for (u in found) {
            val n = Regex("""A(\d+)[_-]""").find(u.substringAfterLast('/'))?.groupValues?.get(1)?.toIntOrNull()
            if (n != null) out.putIfAbsent(n, Preview(Source.PANINI, "Panini Anteprima $n", u))
            else others += Preview(Source.PANINI, u.substringAfterLast('/'), u)
        }
        // archivio da nerdpool: aggiunge solo i numeri non già trovati sul sito Panini
        runCatching { paniniArchive(out.keys) }.getOrNull()?.forEach { (n, p) -> out.putIfAbsent(n, p) }

        return (out.values + others)
            .sortedByDescending { Regex("""\d+""").find(it.title)?.value?.toIntOrNull() ?: 0 }
    }

    // ---------- Archivio Panini da nerdpool.it ----------
    private const val NERDPOOL_TAG = "https://nerdpool.it/tag/anteprima-panini/"

    private fun paniniArchive(known: Set<Int>, maxPages: Int = 3): List<Pair<Int, Preview>> {
        // 1) elenco articoli: numero anteprima -> URL articolo
        val articles = linkedMapOf<Int, String>()
        for (p in 1..maxPages) {
            val url = if (p == 1) NERDPOOL_TAG else "${NERDPOOL_TAG}page/$p/"
            val html = try { get(url) } catch (e: Exception) { break } // pagina inesistente -> fine
            Regex("""https://nerdpool\.it/anteprima-panini-(\d+)-[\w\-]+/""").findAll(html)
                .forEach { articles.putIfAbsent(it.groupValues[1].toInt(), it.value) }
        }
        val todo = articles.filterKeys { it !in known }
        if (todo.isEmpty()) return emptyList()

        // 2) da ogni articolo estrae il link al PDF completo (in parallelo)
        val pool = Executors.newFixedThreadPool(6)
        try {
            val tasks = todo.map { (n, articleUrl) ->
                Callable<Pair<Int, Preview>?> {
                    runCatching {
                        val html = get(articleUrl)
                        val pdf = Regex(
                            """https?://www\.panini\.it/media/paniniFiles/A$n[_-]Panini\.pdf""",
                            RegexOption.IGNORE_CASE
                        ).find(html)?.value ?: return@runCatching null
                        val m = Regex("""annunci-di-([a-z]+)-(\d{4})""").find(articleUrl)
                        val suffix = m?.let { " · " + it.groupValues[1].replaceFirstChar { c -> c.uppercase() } + " " + it.groupValues[2] } ?: ""
                        n to Preview(Source.PANINI, "Panini Anteprima $n$suffix", pdf)
                    }.getOrNull()
                }
            }
            return pool.invokeAll(tasks).mapNotNull { runCatching { it.get() }.getOrNull() }
        } finally {
            pool.shutdown()
        }
    }

    // ---------- Star Comics: /ftp/pdf/Anteprima/Anteprima_Mese_Anno_WEB.pdf ----------
    fun star(): List<Preview> {
        val base = "https://www.starcomics.com/news/novit-presentate-in-anteprima"
        val doc = Jsoup.parse(get(base), base)
        return doc.select("a[href*=/ftp/pdf/Anteprima/]")
            .filter { it.absUrl("href").endsWith(".pdf", true) }
            .map { a ->
                val u = a.absUrl("href")
                Preview(Source.STAR, a.attr("title").ifBlank { u.substringAfterLast('/') }, u)
            }.distinctBy { it.url }
    }

    // ---------- Manicomix: embed Issuu (nessun PDF diretto) ----------
    private val issuuRe = Regex("""(?:https?:)?//e\.issuu\.com/(?:anonymous-)?embed\.html\?[^"'\s<>()\]\\]+""")
    private fun unescape(s: String): String {
        var t = s.replace("\\/", "/").replace("\\u0026", "&")
        repeat(3) { t = t.replace("&amp;", "&").replace("&#038;", "&").replace("&#38;", "&") }
        return t
    }

    private fun issuu(html: String): List<String> =
        issuuRe.findAll(unescape(html))
            .map { if (it.value.startsWith("//")) "https:${it.value}" else it.value }
            .filter { !it.contains("pageNumber=") }.toList()

    private fun dOf(u: String) = Regex("""[?&;]d=([^&;]+)""").find(u)?.groupValues?.get(1)

    private fun pretty(d: String) = d.replace(Regex("""_[0-9a-f]{12,}$"""), "")
        .replace('_', ' ').replace('-', ' ').split(' ').filter { it.isNotEmpty() }
        .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    /** "Catalogo Manicomix Agosto 2026" -> anno*12+mese; null se il titolo non ha quel formato. */
    private fun monthKey(title: String): Int? {
        val m = Regex("""^catalogo\s+manicomix\s+([a-zà-ù]+)\s+(\d{4})\b""", RegexOption.IGNORE_CASE)
            .find(title.trim()) ?: return null
        val mi = MESI.indexOf(m.groupValues[1].lowercase())
        if (mi < 0) return null
        return m.groupValues[2].toInt() * 12 + mi
    }

    private data class IssuuDoc(val slug: String, val title: String)

    /** Profilo https://issuu.com/catalogomega: tiene solo i titoli "Catalogo Manicomix [Mese] [Anno]". */
    private fun issuuCatalogs(maxPages: Int = 5): List<IssuuDoc> {
        val docs = linkedMapOf<String, IssuuDoc>()
        for (p in 1..maxPages) {
            val url = if (p == 1) "https://issuu.com/catalogomega" else "https://issuu.com/catalogomega/$p?ps=24"
            val doc = try { Jsoup.parse(get(url), url) } catch (e: Exception) { if (p == 1) throw e else break }
            val links = doc.select("a[href*=/catalogomega/docs/]")
            if (links.isEmpty()) break
            for (a in links) {
                val slug = a.absUrl("href").substringBefore('?').substringAfter("/docs/").trim('/')
                val title = a.text().trim().ifBlank { a.attr("title").trim() }
                if (slug.isEmpty() || title.isEmpty() || monthKey(title) == null) continue
                docs.putIfAbsent(slug, IssuuDoc(slug, title))
            }
        }
        return docs.values.toList()
    }

    fun manicomix(): List<Preview> {
        val out = linkedMapOf<String, Preview>()
        val seenMonths = mutableSetOf<Int>()
        var ok = false
        var last: Throwable? = null
        for (page in listOf("https://www.manicomix.it/", "https://www.manicomix.it/preview/")) {
            try {
                val html = get(page); ok = true
                for (u in issuu(html)) {
                    val d = dOf(u) ?: continue
                    if (page.endsWith(".it/") && !d.startsWith("catalogo_manicomix")) continue
                    val title = pretty(d)
                    if (out.putIfAbsent(d, Preview(Source.MANICOMIX, title, u, isPdf = false)) == null) {
                        monthKey(title)?.let { seenMonths += it }
                    }
                }
            } catch (e: Exception) { last = e }
        }
        // cataloghi aggiuntivi dal profilo Issuu (solo quelli non già trovati)
        try {
            for (d in issuuCatalogs()) {
                val k = monthKey(d.title)!!
                if (d.slug in out || k in seenMonths) continue
                out[d.slug] = Preview(
                    Source.MANICOMIX, d.title,
                    "https://e.issuu.com/embed.html?d=${d.slug}&u=catalogomega", isPdf = false
                )
                seenMonths += k
            }
            ok = true
        } catch (e: Exception) { last = e }
        if (!ok) throw last ?: IllegalStateException("pagine non raggiungibili")
        // cataloghi mensili dal più recente; il resto in coda, nell'ordine originale
        return out.values.sortedByDescending { monthKey(it.title) ?: -1 }
    }
}
