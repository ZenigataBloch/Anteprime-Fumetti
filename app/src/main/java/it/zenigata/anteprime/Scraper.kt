package it.zenigata.anteprime

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.File
import java.net.URLDecoder
import java.util.Calendar
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.abs

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

    /**
     * True solo se l'URL è davvero un PDF: si leggono i primi 4 byte e devono essere "%PDF".
     * (Controllare solo codice e Content-Type non basta: alcuni server rispondono "ok" anche per file inesistenti.)
     */
    private fun pdfExists(url: String): Boolean {
        gate.acquire()
        try {
            val req = request(url).newBuilder().header("Range", "bytes=0-3").build()
            return pageClient.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use false
                val head = ByteArray(4)
                var got = 0
                r.body!!.byteStream().use { i ->
                    while (got < 4) {
                        val n = i.read(head, got, 4 - got)
                        if (n < 0) break
                        got += n
                    }
                }
                got == 4 && String(head, Charsets.US_ASCII) == "%PDF"
            }
        } catch (e: Exception) {
            return false
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
    private val ISSUE_NUM_RE = Regex("""^A(\d+)(?=[_.\-])""", RegexOption.IGNORE_CASE)
    private val NUM_RE = Regex("""\d+""")
    private val ARTICLE_RE = Regex("""https://nerdpool\.it/anteprima-panini-(\d+)-[\w\-]+/""")

    // ---------- Panini: link PDF in /media/paniniFiles/ (A###.pdf, A###_Panini.pdf o A###-Panini.pdf: il nome è cambiato nel tempo) ----------
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

        // i PDF seguono un nome prevedibile: si prova direttamente il numero successivo al più recente,
        // così una nuova anteprima compare anche prima che Panini o nerdpool aggiornino le loro pagine
        out.keys.maxOrNull()?.let { top ->
            listOf(top + 1)
                .flatMap { n -> listOf("A$n.pdf", "A${n}_Panini.pdf", "A$n-Panini.pdf").map { f -> n to "https://www.panini.it/media/paniniFiles/$f" } }
                .parMap { (n, u) -> if (pdfExists(u)) n to u else null }
                .mapNotNull { it.getOrNull() }
                .forEach { (n, u) -> out.putIfAbsent(n, Preview(Source.PANINI, "Panini Anteprima $n", u)) }
        }

        withMonths(out)
        return (out.values + others)
            .sortedByDescending { NUM_RE.find(it.title)?.value?.toIntOrNull() ?: 0 }
    }

    private val TITLE_MONTH_RE = Regex(""" · ([A-Za-zà-ù]+) (\d{4})$""")

    private fun monthIdx(title: String): Int? {
        val m = TITLE_MONTH_RE.find(title) ?: return null
        val mi = MESI.indexOf(m.groupValues[1].lowercase())
        return if (mi < 0) null else m.groupValues[2].toInt() * 12 + mi
    }

    /** Ai numeri senza mese/anno lo ricava dal numero noto più vicino (una anteprima al mese). */
    private fun withMonths(out: MutableMap<Int, Preview>) {
        val known = out.mapNotNull { (n, p) -> monthIdx(p.title)?.let { n to it } }
        if (known.isEmpty()) return
        for ((n, p) in out.entries.toList()) {
            if (monthIdx(p.title) != null) continue
            val (kn, kk) = known.minByOrNull { abs(it.first - n) }!!
            val k = kk + (n - kn)
            out[n] = p.copy(title = p.title + " · " + MESI[k % 12].replaceFirstChar { it.uppercase() } + " " + k / 12)
        }
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
            """https?://www\.panini\.it/media/paniniFiles/A$n(?:[_-][\w\-]*)?\.pdf""",
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

    // ---------- MangaComicsMarket: fonte / fallback per Star Comics e J-POP ----------
    // Pagina cataloghi: https://www.mangacomicsmarket.it/page/cataloghi
    // Nome nel catalogo: "ANTEPRIMA [NUMERO] [EDITORE]" (es. ANTEPRIMA 422 STAR COMICS)
    //                    "DIRECT JPOP [NUMERO] [MESE]"   (es. DIRECT JPOP 145 NOVEMBRE)
    // File:  .../wysiwyg/Star%20Comics%20Gennaio.pdf  e  .../wysiwyg/Anteprima%20421%20jpop.pdf
    private const val MCM_CATALOGHI = "https://www.mangacomicsmarket.it/page/cataloghi"
    private const val MCM_PDF_BASE = "https://engine.mangacomicsmarket.it/media/uploads/wysiwyg/"
    private val MCM_PDF_RE = Regex("""https?://engine\.mangacomicsmarket\.it/media/uploads/wysiwyg/[^"'\s<>()\\]+?\.pdf""", RegexOption.IGNORE_CASE)
    private val MCM_STAR_TEXT_RE = Regex("""ANTEPRIMA\s+(\d+)\s+STAR\s+COMICS""", RegexOption.IGNORE_CASE)
    private val MCM_JPOP_TEXT_RE = Regex("""DIRECT\s+JPOP\s+(\d+)\s+([A-Za-zà-ù]+)""", RegexOption.IGNORE_CASE)
    private val MCM_STAR_FILE_RE = Regex("""star\s+comics\s+([a-zà-ù]+)""", RegexOption.IGNORE_CASE)
    private val MCM_JPOP_FILE_RE = Regex("""anteprima\s+(\d+)\s+jpop""", RegexOption.IGNORE_CASE)
    private const val JPOP_SEED = 421 // ultimo numero noto del file "Anteprima N jpop.pdf"

    private fun nowIdx(): Int = Calendar.getInstance().let { it.get(Calendar.YEAR) * 12 + it.get(Calendar.MONTH) }

    /** Il nome del file ha solo il mese: si sceglie l'occorrenza più vicina (da 3 mesi fa a 8 mesi avanti). */
    private fun nearestMonth(mi: Int): Int {
        val now = nowIdx()
        var k = (now / 12) * 12 + mi
        if (k < now - 3) k += 12
        if (k > now + 8) k -= 12
        return k
    }

    private fun monthLabel(k: Int) = MESI[k % 12].replaceFirstChar { it.uppercase() } + " " + k / 12

    /** Tutti i PDF linkati dalla pagina cataloghi: (url, testo del link). Può essere vuoto se la pagina li carica via JavaScript. */
    private fun mcmLinks(): List<Pair<String, String>> {
        val html = get(MCM_CATALOGHI)
        val out = linkedMapOf<String, String>()
        Jsoup.parse(html, MCM_CATALOGHI).select("a[href]").forEach { a ->
            val u = a.absUrl("href")
            if (u.endsWith(".pdf", true) && u.contains("mangacomicsmarket.it/media/uploads/wysiwyg/", true)) {
                out.putIfAbsent(u, a.text())
            }
        }
        MCM_PDF_RE.findAll(unescape(html)).forEach { out.putIfAbsent(it.value, "") }
        return out.toList()
    }

    private fun fileName(u: String): String = runCatching { URLDecoder.decode(u.substringAfterLast('/'), "UTF-8") }.getOrDefault(u)

    /** Star Comics da MangaComicsMarket: prima i link in pagina, poi si prova il nome prevedibile "Star Comics [Mese].pdf". */
    private fun mcmStar(): List<Preview> {
        val found = linkedMapOf<Int, Preview>() // chiave: anno*12+mese (o -numero se il mese non è noto)
        for ((u, text) in runCatching { mcmLinks() }.getOrDefault(emptyList())) {
            val name = fileName(u)
            val num = MCM_STAR_TEXT_RE.find(text)?.groupValues?.get(1)
            val mi = MCM_STAR_FILE_RE.find(name)?.groupValues?.get(1)?.let { MESI.indexOf(it.lowercase()) }?.takeIf { it >= 0 }
            if (mi == null && num == null) continue
            val numPart = num?.let { " $it" }.orEmpty()
            if (mi != null) {
                val k = nearestMonth(mi)
                found.putIfAbsent(k, Preview(Source.STAR, "Star Comics Anteprima$numPart · ${monthLabel(k)}", u))
            } else {
                found.putIfAbsent(-num!!.toInt(), Preview(Source.STAR, "Star Comics Anteprima$numPart", u))
            }
        }
        // il sito potrebbe non elencare i link: si prova il nome del file per i mesi vicini
        val now = nowIdx()
        ((now - 1)..(now + 2)).filter { it !in found }
            .parMap { k ->
                val u = MCM_PDF_BASE + "Star%20Comics%20" + MESI[k % 12].replaceFirstChar { it.uppercase() } + ".pdf"
                if (pdfExists(u)) k to u else null
            }
            .mapNotNull { it.getOrNull() }
            .forEach { (k, u) -> found.putIfAbsent(k, Preview(Source.STAR, "Star Comics Anteprima · ${monthLabel(k)}", u)) }
        return found.entries.sortedByDescending { it.key }.map { it.value }
    }

    /** J-POP da MangaComicsMarket: "DIRECT JPOP [N] [MESE]" in pagina, altrimenti si prova "Anteprima [N] jpop.pdf". */
    private fun mcmJpop(): List<Preview> {
        val found = linkedMapOf<String, Preview>() // chiave: nome file
        var top = JPOP_SEED
        for ((u, text) in runCatching { mcmLinks() }.getOrDefault(emptyList())) {
            val name = fileName(u)
            val fileNum = MCM_JPOP_FILE_RE.find(name)?.groupValues?.get(1)?.toIntOrNull()
            val t = MCM_JPOP_TEXT_RE.find(text)
            if (fileNum == null && t == null) continue
            if (fileNum != null && fileNum > top) top = fileNum
            val title = if (t != null) {
                "J-POP Direct ${t.groupValues[1]} · ${t.groupValues[2].lowercase().replaceFirstChar { it.uppercase() }}"
            } else "J-POP Anteprima $fileNum"
            found.putIfAbsent(name.lowercase(), Preview(Source.JPOP, title, u))
        }
        // numeri successivi all'ultimo noto (il file segue il numero dell'Anteprima, non quello di Direct)
        (top..(top + 2)).filter { n -> found.keys.none { it.contains("anteprima $n jpop") } }
            .parMap { n ->
                val u = MCM_PDF_BASE + "Anteprima%20$n%20jpop.pdf"
                if (pdfExists(u)) n to u else null
            }
            .mapNotNull { it.getOrNull() }
            .forEach { (n, u) -> found.putIfAbsent(fileName(u).lowercase(), Preview(Source.JPOP, "J-POP Anteprima $n", u)) }
        return found.values.toList()
    }

    /** J-POP: categoria a parte, PDF scaricabili da MangaComicsMarket (più recente per primo). */
    fun jpop(): List<Preview> {
        val list = mcmJpop().sortedByDescending {
            MCM_JPOP_FILE_RE.find(fileName(it.url))?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }
        if (list.isEmpty()) throw IllegalStateException("nessuna anteprima J-POP trovata")
        return list
    }

    // ---------- Star Comics: /ftp/pdf/Anteprima/Anteprima_Mese_Anno_WEB.pdf (+ fallback MangaComicsMarket) ----------
    private val STAR_FILE_MONTH_RE = Regex("""([A-Za-zà-ù]+)[_\s\-]+(\d{4})""")

    /** anno*12+mese di un'anteprima Star Comics (dal titolo "· Mese Anno" o dal nome file); null se non riconoscibile. */
    private fun starKey(p: Preview): Int? {
        monthIdx(p.title)?.let { return it }
        for (s in listOf(p.title, fileName(p.url))) {
            for (m in STAR_FILE_MONTH_RE.findAll(s)) {
                val mi = MESI.indexOf(m.groupValues[1].lowercase())
                if (mi >= 0) return m.groupValues[2].toInt() * 12 + mi
            }
        }
        return null
    }

    private fun starSite(): List<Preview> {
        val base = "https://www.starcomics.com/news/novit-presentate-in-anteprima"
        val doc = Jsoup.parse(get(base), base)
        return doc.select("a[href*=/ftp/pdf/Anteprima/]")
            .filter { it.absUrl("href").endsWith(".pdf", true) }
            .map { a ->
                val u = a.absUrl("href")
                Preview(Source.STAR, a.attr("title").ifBlank { u.substringAfterLast('/') }, u)
            }.distinctBy { it.url }
    }

    fun star(): List<Preview> {
        val mcmJob = io.submit(Callable { runCatching { mcmStar() }.getOrDefault(emptyList()) })
        val site = runCatching { starSite() }
        val mcm = mcmJob.get()
        val base = site.getOrDefault(emptyList())
        // sito Star Comics non raggiungibile e nessun fallback: l'errore originale arriva all'app
        if (site.isFailure && mcm.isEmpty()) throw site.exceptionOrNull()!!
        // dal fallback si aggiungono solo i mesi che il sito Star Comics non ha già
        val have = base.mapNotNull { starKey(it) }.toSet()
        val extra = mcm.filter { p -> starKey(p)?.let { it !in have } ?: true }
        return extra + base
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
