package it.example.anteprime

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

object Scraper {
    // Client condiviso (usato anche per scaricare i PDF): timeout di lettura lunghi.
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    // Per le pagine HTML: stesso pool di connessioni, ma un sito lento non blocca più l'intero caricamento.
    private val pageClient: OkHttpClient = client.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS).build()

    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    fun request(url: String): Request = Request.Builder().url(url).header("User-Agent", UA).header("Accept-Language", "it-IT,it;q=0.9").build()

    // ---------- parallelismo ----------
    // Pool "illimitato" (thread daemon) + semaforo sulle sole richieste HTTP: niente deadlock con task annidati.
    private val io = Executors.newCachedThreadPool { r -> Thread(r, "scraper").apply { isDaemon = true } }
    private val gate = Semaphore(8)

    /** Esegue f su ogni elemento in parallelo; il risultato resta nello stesso ordine della lista. */
    private fun <A, B> List<A>.parMap(f: (A) -> B): List<Result<B>> =
        io.invokeAll(map { a -> Callable { runCatching { f(a) } } }).map { it.get() }

    private fun get(url: String): String {
        gate.acquire()
        try {
            return pageClient.newCall(request(url)).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code} su $url")
                r.body!!.string()
            }
        } finally {
            gate.release()
        }
    }

    /** Cartella per la cache dell'archivio nerdpool. Impostala all'avvio: Scraper.cacheDir = filesDir (senza, niente cache su disco). */
    @Volatile var cacheDir: File? = null

    private val MESI = listOf(
        "gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno",
        "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre"
    )

    private val PDF_LINK_RE = Regex("""/media/paniniFiles/[\w\-]+\.pdf""")
    private val ISSUE_NUM_RE = Regex("""A(\d+)[_-]""")
    private val NUM_RE = Regex("""\d+""")
    private val ARTICLE_RE = Regex("""https://nerdpool\.it/anteprima-panini-(\d+)-[\w\-]+/""")

    // ---------- Panini: link PDF in /media/paniniFiles/A###_Panini.pdf (oppure A###-Panini.pdf) ----------
    fun panini(): List<Preview> {
        // l'elenco articoli di nerdpool parte subito, in parallelo con le pagine Panini
        val articlesJob = io.submit(Callable { runCatching { nerdpoolArticles() }.getOrDefault(emptyMap()) })

        val pages = listOf(
            "https://www.panini.it/shp_ita_it/anteprima.html",
            "https://www.panini.it/shp_ita_it/anteprima/anteprima-mese-scorso.html"
        )
        val results = pages.parMap { get(it) }
        val htmls = results.mapNotNull { it.getOrNull() }
        if (htmls.isEmpty()) {
            throw results.mapNotNull { it.exceptionOrNull() }.lastOrNull()
                ?: IllegalStateException("pagine non raggiungibili")
        }

        val found = linkedSetOf("https://www.panini.it/media/paniniFiles/A421_Panini.pdf") // seed noto
        for (html in htmls) {
            PDF_LINK_RE.findAll(html).forEach { found += "https://www.panini.it" + it.value }
        }

        val out = linkedMapOf<Int, Preview>()
        val others = mutableListOf<Preview>()
        for (u in found) {
            val n = ISSUE_NUM_RE.find(u.substringAfterLast('/'))?.groupValues?.get(1)?.toIntOrNull()
            if (n != null) out.putIfAbsent(n, Preview(Source.PANINI, "Panini Anteprima $n", u))
            else others += Preview(Source.PANINI, u.substringAfterLast('/'), u)
        }
        // archivio da nerdpool: aggiunge solo i numeri non già trovati sul sito Panini
        val articles = runCatching { articlesJob.get() }.getOrDefault(emptyMap())
        runCatching { paniniArchive(articles, out.keys.toSet()) }.getOrNull()
            ?.forEach { (n, p) -> out.putIfAbsent(n, p) }

        return (out.values + others)
            .sortedByDescending { NUM_RE.find(it.title)?.value?.toIntOrNull() ?: 0 }
    }

    // ---------- Archivio Panini da nerdpool.it ----------
    private const val NERDPOOL_TAG = "https://nerdpool.it/tag/anteprima-panini/"

    /** Numero anteprima -> URL articolo. Le pagine del tag si scaricano tutte insieme. */
    private fun nerdpoolArticles(maxPages: Int = 3): Map<Int, String> {
        val urls = (1..maxPages).map { p -> if (p == 1) NERDPOOL_TAG else "${NERDPOOL_TAG}page/$p/" }
        val articles = linkedMapOf<Int, String>()
        // i risultati sono in ordine di pagina; una pagina inesistente (errore) si ignora
        for (res in urls.parMap { get(it) }) {
            val html = res.getOrNull() ?: continue
            ARTICLE_RE.findAll(html).forEach { articles.putIfAbsent(it.groupValues[1].toInt(), it.value) }
        }
        return articles
    }

    private fun paniniArchive(articles: Map<Int, String>, known: Set<Int>): List<Pair<Int, Preview>> {
        val cached = loadArchiveCache()
        val result = mutableListOf<Pair<Int, Preview>>()
        val todo = linkedMapOf<Int, String>()
        for ((n, articleUrl) in articles) {
            if (n in known) continue
            val c = cached[n]
            if (c != null) result += n to c else todo[n] = articleUrl // già visto: niente richiesta di rete
        }
        if (todo.isEmpty()) return result

        // solo gli articoli nuovi vengono scaricati (in parallelo)
        val fresh = todo.entries.toList()
            .parMap { (n, articleUrl) -> fetchArticle(n, articleUrl) }
            .mapNotNull { it.getOrNull() }
        if (fresh.isNotEmpty()) {
            result += fresh
            saveArchiveCache(cached + fresh.toMap())
        }
        return result
    }

    /** Dall'articolo estrae il link al PDF completo. */
    private fun fetchArticle(n: Int, articleUrl: String): Pair<Int, Preview>? {
        val html = get(articleUrl)
        val pdf = Regex(
            """https?://www\.panini\.it/media/paniniFiles/A$n[_-]Panini\.pdf""",
            RegexOption.IGNORE_CASE
        ).find(html)?.value ?: return null
        val m = Regex("""annunci-di-([a-z]+)-(\d{4})""").find(articleUrl)
        val suffix = m?.let { " · " + it.groupValues[1].replaceFirstChar { c -> c.uppercase() } + " " + it.groupValues[2] } ?: ""
        return n to Preview(Source.PANINI, "Panini Anteprima $n$suffix", pdf)
    }

    // Cache su disco (una riga per anteprima: numero \t titolo \t url). I PDF vecchi non cambiano mai.
    private fun cacheFile(): File? = cacheDir?.let { File(it, "nerdpool_archive.tsv") }

    private fun loadArchiveCache(): Map<Int, Preview> = runCatching {
        cacheFile()?.takeIf { it.exists() }?.readLines().orEmpty().mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 3) null else f[0].toInt() to Preview(Source.PANINI, f[1], f[2])
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun saveArchiveCache(m: Map<Int, Preview>) {
        runCatching {
            cacheFile()?.writeText(m.entries.joinToString("\n") { (n, p) -> "$n\t${p.title}\t${p.url}" })
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

    /**
     * Profilo https://issuu.com/catalogomega: tiene solo i titoli "Catalogo Manicomix [Mese] [Anno]".
     * Le pagine del profilo si scaricano e si analizzano tutte insieme; si ferma alla prima senza risultati.
     */
    private fun issuuCatalogs(maxPages: Int = 5): List<IssuuDoc> {
        val urls = (1..maxPages).map { p -> if (p == 1) "https://issuu.com/catalogomega" else "https://issuu.com/catalogomega/$p?ps=24" }
        val pages = urls.parMap { url -> Jsoup.parse(get(url), url) }
        val docs = linkedMapOf<String, IssuuDoc>()
        for ((i, res) in pages.withIndex()) {
            if (i == 0 && res.isFailure) throw res.exceptionOrNull()!!
            val doc = res.getOrNull() ?: break
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
        val sitePages = listOf("https://www.manicomix.it/", "https://www.manicomix.it/preview/")
        // il profilo Issuu parte subito, in parallelo con le due pagine del sito
        val issuuJob = io.submit(Callable { runCatching { issuuCatalogs() } })
        val siteResults = sitePages.parMap { get(it) }

        val out = linkedMapOf<String, Preview>()
        val seenMonths = mutableSetOf<Int>()
        var ok = false
        var last: Throwable? = null
        for ((page, res) in sitePages.zip(siteResults)) {
            val html = res.getOrNull()
            if (html == null) { last = res.exceptionOrNull(); continue }
            ok = true
            for (u in issuu(html)) {
                val d = dOf(u) ?: continue
                if (page.endsWith(".it/") && !d.startsWith("catalogo_manicomix")) continue
                val title = pretty(d)
                if (out.putIfAbsent(d, Preview(Source.MANICOMIX, title, u, isPdf = false)) == null) {
                    monthKey(title)?.let { seenMonths += it }
                }
            }
        }
        // cataloghi aggiuntivi dal profilo Issuu (solo quelli non già trovati)
        val issuuRes = issuuJob.get()
        val issuuDocs = issuuRes.getOrNull()
        if (issuuDocs != null) {
            for (d in issuuDocs) {
                val k = monthKey(d.title)!!
                if (d.slug in out || k in seenMonths) continue
                out[d.slug] = Preview(
                    Source.MANICOMIX, d.title,
                    "https://e.issuu.com/embed.html?d=${d.slug}&u=catalogomega", isPdf = false
                )
                seenMonths += k
            }
            ok = true
        } else {
            last = issuuRes.exceptionOrNull()
        }
        if (!ok) throw last ?: IllegalStateException("pagine non raggiungibili")
        // cataloghi mensili dal più recente; il resto in coda, nell'ordine originale
        return out.values.sortedByDescending { monthKey(it.title) ?: -1 }
    }
}
