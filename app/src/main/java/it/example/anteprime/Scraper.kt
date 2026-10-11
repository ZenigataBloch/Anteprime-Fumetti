package it.example.anteprime

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
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

    // ---------- Panini: link PDF in /media/paniniFiles/A###_Panini.pdf ----------
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
        return found.map { u ->
            val n = Regex("""A(\d+)_""").find(u)?.groupValues?.get(1)
            Preview(Source.PANINI, if (n != null) "Panini Anteprima $n" else u.substringAfterLast('/'), u)
        }.sortedByDescending { Regex("""\d+""").find(it.title)?.value?.toIntOrNull() ?: 0 }
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

    fun manicomix(): List<Preview> {
        val out = linkedMapOf<String, Preview>()
        var ok = false
        var last: Throwable? = null
        for (page in listOf("https://www.manicomix.it/", "https://www.manicomix.it/preview/")) {
            try {
                val html = get(page); ok = true
                for (u in issuu(html)) {
                    val d = dOf(u) ?: continue
                    if (page.endsWith(".it/") && !d.startsWith("catalogo_manicomix")) continue
                    out.putIfAbsent(d, Preview(Source.MANICOMIX, pretty(d), u, isPdf = false))
                }
            } catch (e: Exception) { last = e }
        }
        if (!ok) throw last ?: IllegalStateException("pagine non raggiungibili")
        return out.values.toList()
    }
}
