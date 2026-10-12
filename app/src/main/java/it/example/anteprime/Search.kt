package it.example.anteprime

import it.example.anteprime.KeywordSearch.norm
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * Informazioni lette nella scheda: formato (Brossurato/Cartonato/...), colore (Bianco e nero / A colori)
 * ed extra (es. "con sovracoperta"). Sono solo suggerimenti: nulla viene taggato in automatico.
 */
data class CardInfo(
    val format: String?,                         // nome normalizzato; null se non riconosciuto
    val colors: List<String> = emptyList(),
    val extras: List<String> = emptyList(),
    val rawSigla: String? = null                 // sigla non in dizionario, mostrata com'è
) {
    /** Tag che il dialog della nota può proporre: formato e colore (gli extra restano solo etichetta). */
    val tags: List<String> get() = listOfNotNull(format) + colors

    fun display(): String = (listOfNotNull(format ?: rawSigla) + colors + extras).joinToString(" · ")
}

/** Un risultato: la keyword cercata, la pagina (0-based), il testo trovato e le info di scheda (solo se trovato in una scheda). */
data class Hit(val keyword: String, val page: Int, val text: String, val info: CardInfo?, val inCard: Boolean)

object FormatDict {
    /** Sigla (senza punto) -> significato. B è confermata per Star; C e S sono ipotesi da confermare. */
    val map: Map<String, String> = mapOf(
        "C" to "Cartonato",
        "B" to "Brossurato",
        "S" to "Spillato"
    )
}

// ---------------------------------------------------------------------------------------------
// Riconoscimento di formato / colore nel testo (già normalizzato con norm())
// ---------------------------------------------------------------------------------------------

private val BN_RE = Regex("""(?<![a-z0-9])(?:b n|bn|bianco e nero|bianco nero)(?![a-z0-9])""")
private val COL_RE = Regex("""(?<![a-z0-9])(?:col|colore|colori)(?![a-z0-9])""")

/** "b/n" -> Bianco e nero; "col." / "a colori" / "colore" -> A colori; "b/n + colore" -> entrambi. */
internal fun colorsIn(n: String): List<String> {
    val out = ArrayList<String>()
    if (BN_RE.containsMatchIn(n)) out.add("Bianco e nero")
    if (COL_RE.containsMatchIn(n)) out.add("A colori")
    return out
}

/** Formato scritto a parole ("Cartonato", "Bross.") */
internal fun formatIn(n: String): String? = when {
    "cartonat" in n -> "Cartonato"
    "bross" in n -> "Brossurato"
    "spillat" in n -> "Spillato"
    else -> null
}

/** "con sovr.", "con sovracc.", "sovraccoperta" -> "con sovracoperta" */
internal fun extrasIn(n: String): List<String> = if ("sovr" in n) listOf("con sovracoperta") else emptyList()

// ---------------------------------------------------------------------------------------------
// Parser per editore
// ---------------------------------------------------------------------------------------------

/** Una scheda trovata in una pagina: indici delle righe, le righe, info riconosciute e (se noto) il titolo. */
internal class Card(val members: List<Int>, val lines: List<PageLine>, val info: CardInfo?, val title: String? = null)

internal interface CardParser {
    /** Schede di una pagina; [s] è ordinata per y0. */
    fun cards(s: List<PageLine>): List<Card>
}

/** Righe con lo stesso bordo sinistro dell'ancora (testo allineato a sinistra). */
private fun alignedLeft(s: List<PageLine>, fi: Int, tol: Float): List<Int> =
    s.indices.filter { abs(s[it].x0 - s[fi].x0) <= tol }

/**
 * Raccoglie le righe della scheda intorno alla riga ancora [fi], dentro la colonna [col] (indici ordinati per y).
 * Verso l'alto si ferma a un'altra ancora o quando [stopUp] riconosce la fine della scheda precedente;
 * verso il basso si ferma a un'altra ancora, oppure dopo la riga per cui [endDown] è vero.
 * [isAnchor] lavora sul testo grezzo, [stopUp] e [endDown] sul testo normalizzato.
 */
private fun gather(
    s: List<PageLine>, col: List<Int>, fi: Int,
    maxUp: Int, maxDown: Int, gapMax: Float,
    isAnchor: (String) -> Boolean,
    stopUp: (String) -> Boolean,
    endDown: (String) -> Boolean
): List<Int> {
    val lh = maxOf(s[fi].h, 0.004f)
    val p = col.indexOf(fi)
    val members = ArrayList<Int>()
    members.add(fi)

    var prev = fi
    var n = 0
    for (q in p - 1 downTo 0) {
        val j = col[q]
        if (n >= maxUp || s[prev].y0 - s[j].y1 > gapMax * lh) break
        if (isAnchor(s[j].text) || stopUp(norm(s[j].text))) break
        members.add(j)
        prev = j
        n++
    }

    prev = fi
    n = 0
    for (q in p + 1 until col.size) {
        val j = col[q]
        if (n >= maxDown || s[j].y0 - s[prev].y1 > gapMax * lh) break
        if (isAnchor(s[j].text)) break
        members.add(j)
        prev = j
        n++
        if (endDown(norm(s[j].text))) break
    }
    return members.sorted()
}

/**
 * Panini: il riquadro azzurro si individua dalla riga del formato
 * ("Novembre • 20,5x31, C., 208 pp., col. • Euro 50,00"). Righe incolonnate sopra (Autori) e sotto (ISBN, Contiene)
 * appartengono alla stessa scheda. Testo centrato: si allinea sul centro. Se i riquadri non vengono associati bene,
 * i valori da ritoccare sono le costanti qui sotto.
 */
internal object PaniniParser : CardParser {
    private const val CX_TOL = 0.06f   // tolleranza (frazione di larghezza pagina) sul centro delle righe di una stessa scheda
    private const val GAP_MAX = 2.0f   // distanza massima tra due righe della scheda, in altezze di riga
    private const val MAX_UP = 6
    private const val MAX_DOWN = 12

    // dimensioni seguite da ", <sigla>." : "20,5x31, C." / "18,3x27,7, B."
    private val FORMAT_RE = Regex("""[xX×]\s*\d+(?:[.,]\d+)?\s*,\s*([A-Za-z]{1,2})\s*\.""")

    override fun cards(s: List<PageLine>): List<Card> {
        val out = ArrayList<Card>()
        for (fi in s.indices) {
            val m = FORMAT_RE.find(s[fi].text) ?: continue
            val letter = m.groupValues[1].uppercase(Locale.ROOT)
            val n = norm(s[fi].text)
            val info = CardInfo(FormatDict.map[letter], colorsIn(n), extrasIn(n), "$letter.")
            val f = s[fi]
            val lh = maxOf(f.h, 0.004f)
            // solo le righe incolonnate con quella del formato (due schede affiancate restano separate)
            val col = s.indices.filter { abs(s[it].cx - f.cx) <= CX_TOL }
            val p = col.indexOf(fi)
            val members = ArrayList<Int>()
            members.add(fi)

            var prev = fi
            var k = 0
            for (q in p - 1 downTo 0) {
                val j = col[q]
                if (k >= MAX_UP || s[prev].y0 - s[j].y1 > GAP_MAX * lh) break
                if (FORMAT_RE.containsMatchIn(s[j].text)) break
                members.add(j)
                prev = j
                k++
                if (norm(s[j].text).startsWith("autori")) break
            }

            prev = fi
            k = 0
            for (q in p + 1 until col.size) {
                val j = col[q]
                if (k >= MAX_DOWN || s[j].y0 - s[prev].y1 > GAP_MAX * lh) break
                if (FORMAT_RE.containsMatchIn(s[j].text) || norm(s[j].text).startsWith("autori")) break
                members.add(j)
                prev = j
                k++
            }
            val sorted = members.sorted()
            out.add(Card(sorted, sorted.map { s[it] }, info))
        }
        return out
    }
}

/**
 * Star Comics: riquadro giallo con
 *   "11,5x17,5, B, b/n, con sovr., 208 pp"   (dimensioni, sigla, colore, extra, pagine)
 *   "€ 7,90 | 6 ottobre 2026"
 *   "isbn: 9788822669544"
 * Testo allineato a sinistra. La riga del formato è l'ancora; sotto ci sono prezzo/data e ISBN.
 * Da ritoccare su una pagina intera (titolo e descrizione sopra il riquadro).
 */
internal object StarParser : CardParser {
    private const val LEFT_TOL = 0.03f
    private const val GAP_MAX = 2.5f
    private const val MAX_UP = 6
    private const val MAX_DOWN = 3

    // dimensioni seguite da ", <sigla>," : "11,5x17,5, B," (la virgola o il punto dopo la sigla)
    private val FORMAT_RE = Regex("""[xX×]\s*\d+(?:[.,]\d+)?\s*,\s*([A-Za-z]{1,2})\s*[,.]""")

    override fun cards(s: List<PageLine>): List<Card> {
        val out = ArrayList<Card>()
        for (fi in s.indices) {
            val m = FORMAT_RE.find(s[fi].text) ?: continue
            val letter = m.groupValues[1].uppercase(Locale.ROOT)
            val n = norm(s[fi].text)
            val info = CardInfo(FormatDict.map[letter], colorsIn(n), extrasIn(n), "$letter.")
            val members = gather(
                s, alignedLeft(s, fi, LEFT_TOL), fi, MAX_UP, MAX_DOWN, GAP_MAX,
                isAnchor = { FORMAT_RE.containsMatchIn(it) },
                stopUp = { it.startsWith("isbn") },   // l'ISBN è l'ultima riga: sopra c'è la scheda precedente
                endDown = { it.startsWith("isbn") }
            )
            out.add(Card(members, members.map { s[it] }, info))
        }
        return out
    }
}

/**
 * J-POP (dentro Manicomix): scheda a etichette con trattino, allineata a sinistra:
 *   TITOLO / DI AUTORI / PIANO DELL'OPERA - ... / FORMATO - 17X25 - CARTONATO / PAGINE - 120, COLORE / ISBN - ... / PREZZO - ...
 * Ancora = riga "FORMATO - ...". Il formato è scritto a parole; il colore si legge dalla riga "PAGINE".
 */
internal object JPopParser : CardParser {
    private const val LEFT_TOL = 0.03f
    private const val GAP_MAX = 2.5f
    private const val MAX_UP = 8
    private const val MAX_DOWN = 5

    private val ANCHOR = Regex("""^\s*formato\s*[-–—−‒]""", RegexOption.IGNORE_CASE)

    override fun cards(s: List<PageLine>): List<Card> {
        val out = ArrayList<Card>()
        for (fi in s.indices) {
            if (!ANCHOR.containsMatchIn(s[fi].text)) continue
            val members = gather(
                s, alignedLeft(s, fi, LEFT_TOL), fi, MAX_UP, MAX_DOWN, GAP_MAX,
                isAnchor = { ANCHOR.containsMatchIn(it) },
                stopUp = { it.startsWith("prezzo") },  // PREZZO è l'ultima riga: sopra c'è la scheda precedente
                endDown = { it.startsWith("prezzo") }
            )
            val lines = members.map { s[it] }
            val fmt = norm(s[fi].text)
            val pagine = lines.map { norm(it.text) }.firstOrNull { it.startsWith("pagine") } ?: ""
            val info = CardInfo(formatIn(fmt), colorsIn(pagine), extrasIn(fmt))
            // titolo = righe prima di "DI <autori>" / "PIANO DELL'OPERA" (esclusa l'etichetta NOVITÀ)
            val head = lines.takeWhile {
                val t = norm(it.text)
                !(t.startsWith("di ") || t.startsWith("piano") || t.startsWith("periodicita") || t.startsWith("formato"))
            }
            val title = head.filter { !norm(it.text).startsWith("novita") }
                .joinToString(" ") { it.text.trim() }
                .ifBlank { null }
            out.add(Card(members, lines, info, title))
        }
        return out
    }
}

/**
 * Jundo (dentro Manicomix): una pagina per titolo. Titolo nella barra in alto (accanto a "NUOVA USCITA"),
 * poi tabella Generi / Uscita / Autori / Informazioni Aggiuntive (Pagine, Formato, Copertina, Prezzo, ISBN).
 * La scheda è l'intera pagina. Copertina "Flessibile" = Brossurato, "Rigida" = Cartonato (ipotesi).
 */
internal object JundoParser : CardParser {
    override fun cards(s: List<PageLine>): List<Card> {
        if (s.isEmpty()) return emptyList()
        val ns = s.map { norm(it.text) }
        val hasCover = ns.any { it.startsWith("copertina") }
        val hasTable = ns.any { it.startsWith("generi") } && ns.any { it.startsWith("autori") }
        if (!hasCover && !hasTable) return emptyList()

        var format: String? = null
        var colors: List<String> = emptyList()
        var extras: List<String> = emptyList()
        for (n in ns) {
            if (n.startsWith("copertina")) {
                format = when {
                    "flessibil" in n -> "Brossurato"
                    "rigid" in n || "cartonat" in n -> "Cartonato"
                    else -> formatIn(n)
                }
                extras = extrasIn(n)
            } else if (n.startsWith("pagine")) {
                colors = colorsIn(n)
            }
        }
        // titolo = la prima riga in alto che non è l'etichetta "NUOVA USCITA"
        val ti = ns.indices.firstOrNull {
            val n = ns[it]
            n.length >= 3 && !n.startsWith("nuova") && !n.startsWith("nuovo")
        }
        val title = ti?.let { s[it].text.trim() }
        return listOf(Card(s.indices.toList(), s, CardInfo(format, colors, extras), title))
    }
}

/** Manicomix = più editori nello stesso catalogo: prima Jundo (pagina intera), altrimenti J-POP. */
internal object ManicomixParser : CardParser {
    override fun cards(s: List<PageLine>): List<Card> = JundoParser.cards(s).ifEmpty { JPopParser.cards(s) }
}

private fun parserFor(source: Source): CardParser = when (source) {
    Source.PANINI -> PaniniParser
    Source.STAR -> StarParser
    Source.MANICOMIX -> ManicomixParser
}

// ---------------------------------------------------------------------------------------------
// Ricerca
// ---------------------------------------------------------------------------------------------

/**
 * Ricerca nel testo OCR delle pagine. Il riconoscimento delle schede dipende dall'editore (vedi i parser sopra);
 * il matching delle keyword è uguale per tutti.
 */
object KeywordSearch {
    private val ACCENTS = Regex("""\p{Mn}+""")
    private val NON_ALNUM = Regex("""[^a-z0-9]+""")
    private val CONTIENE_RE = Regex("""^\s*contiene\s*:?\s*""", RegexOption.IGNORE_CASE)
    // le voci di "Contiene:" finiscono con il numero: "... (2026) #2, The Peril ... #2"
    private val ITEM_SPLIT = Regex("""(?<=#\d{1,3}(?:/\d{1,3})?)\s*,\s*""")

    /** Minuscolo, senza accenti, tutto ciò che non è lettera o cifra diventa uno spazio ("X-Men", "X‑Men" -> "x men"). */
    fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(ACCENTS, "")
            .lowercase(Locale.ROOT)
            .replace(NON_ALNUM, " ")
            .trim()

    /** "X-Men, Absolute Batman" -> ["X-Men", "Absolute Batman"]. */
    fun keywords(query: String): List<String> =
        query.split(',', ';', '\n').map { it.trim() }.filter { norm(it).isNotEmpty() }.distinct().take(8)

    /** Parole intere; tra una parola e l'altra spazi facoltativi, così "X-Men", "X Men" e "XMEN" sono equivalenti. */
    private fun regexFor(keyword: String): Regex? {
        val tokens = norm(keyword).split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        return Regex("(?<![a-z0-9])" + tokens.joinToString("\\s*") { Regex.escape(it) } + "(?![a-z0-9])")
    }

    private fun cardHits(kw: String, re: Regex, page: Int, c: Card): List<Hit> {
        // Panini: le voci di "Contiene:"
        val ci = c.lines.indexOfFirst { norm(it.text).startsWith("contiene") }
        val items = if (ci >= 0) {
            c.lines.drop(ci).joinToString(" ") { it.text.trim() }
                .replace(CONTIENE_RE, "")
                .split(ITEM_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
        } else emptyList()
        val found = items.filter { re.containsMatchIn(norm(it)) }
        if (found.isNotEmpty()) return found.map { Hit(kw, page, it, c.info, true) }

        // la parola può comparire altrove nella scheda (autori, titolo, trama): se la scheda ha un titolo, il risultato è il titolo
        val line = c.lines.firstOrNull { re.containsMatchIn(norm(it.text)) }
        if (line == null) {
            // parola spezzata su due righe (es. titolo a capo)
            val joined = norm(c.lines.joinToString(" ") { it.text })
            if (!re.containsMatchIn(joined)) return emptyList()
            return listOf(Hit(kw, page, c.title ?: c.lines.first().text.trim(), c.info, true))
        }
        return listOf(Hit(kw, page, c.title ?: line.text.trim(), c.info, true))
    }

    /** Un solo risultato "nel testo" per pagina, e solo se la parola non è già stata trovata in una scheda. */
    private fun bodyHit(kw: String, re: Regex, page: Int, s: List<PageLine>, inCard: Set<Int>): Hit? {
        val body = s.indices.filter { it !in inCard }
        for ((n, i) in body.withIndex()) {
            val one = s[i].text.trim()
            if (re.containsMatchIn(norm(one))) return Hit(kw, page, one, null, false)
            val next = body.getOrNull(n + 1) ?: continue
            val two = one + " " + s[next].text.trim() // parola spezzata su due righe
            if (re.containsMatchIn(norm(two))) return Hit(kw, page, two, null, false)
        }
        return null
    }

    fun search(pages: Map<Int, List<PageLine>>, keywords: List<String>, source: Source): List<Hit> {
        val rx = keywords.mapNotNull { k -> regexFor(k)?.let { k to it } }
        if (rx.isEmpty()) return emptyList()
        val parser = parserFor(source)
        val hits = ArrayList<Hit>()
        for (page in pages.keys.sorted()) {
            val s = pages.getValue(page).sortedBy { it.y0 }
            val cs = parser.cards(s)
            val inCard = HashSet<Int>()
            cs.forEach { inCard.addAll(it.members) }
            for ((kw, re) in rx) {
                val before = hits.size
                for (c in cs) hits.addAll(cardHits(kw, re, page, c))
                if (hits.size == before) bodyHit(kw, re, page, s, inCard)?.let { hits.add(it) }
            }
        }
        return hits
    }
}
