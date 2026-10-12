package it.example.anteprime

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/** Formato riconosciuto nella riga della scheda, es. sigla "C." -> "Cartonato" (label null se la sigla non è nel dizionario). */
data class FormatInfo(val sigla: String, val label: String?) {
    fun display(): String = label ?: sigla
}

/** Un risultato: la keyword cercata, la pagina (0-based), il testo trovato e il formato (solo se trovato in una scheda). */
data class Hit(val keyword: String, val page: Int, val text: String, val format: FormatInfo?, val inCard: Boolean)

object FormatDict {
    /** Sigla (senza punto) -> significato. Da confermare e ampliare. */
    val map: Map<String, String> = mapOf(
        "C" to "Cartonato",
        "B" to "Brossurato",
        "S" to "Spillato"
    )
}

/**
 * Ricerca nel testo OCR delle pagine.
 *
 * Una "scheda" è il riquadro azzurro di Anteprima Panini. Si individua dalla riga del formato
 * ("Novembre • 20,5x31, C., 208 pp., col. • Euro 50,00"): le righe incolonnate sopra (Autori) e sotto
 * (ISBN, Contiene) appartengono alla stessa scheda. La sigla si cerca SOLO in quella riga, mai nel resto della pagina.
 * Se i riquadri non vengono associati bene, i valori da ritoccare sono le costanti qui sotto.
 */
object KeywordSearch {
    private const val CX_TOL = 0.06f   // tolleranza (frazione di larghezza pagina) sul centro delle righe di una stessa scheda
    private const val GAP_MAX = 2.0f   // distanza massima tra due righe della scheda, in altezze di riga
    private const val MAX_UP = 6
    private const val MAX_DOWN = 12

    // dimensioni seguite da ", <sigla>." : "20,5x31, C." / "18,3x27,7, B."
    private val FORMAT_RE = Regex("""[xX×]\s*\d+(?:[.,]\d+)?\s*,\s*([A-Za-z]{1,2})\s*\.""")
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

    private class Card(val members: List<Int>, val lines: List<PageLine>, val format: FormatInfo?)

    /** Schede di una pagina; [s] è ordinata per y0. */
    private fun cards(s: List<PageLine>): List<Card> {
        val out = ArrayList<Card>()
        for (fi in s.indices) {
            val m = FORMAT_RE.find(s[fi].text) ?: continue
            val letter = m.groupValues[1].uppercase(Locale.ROOT)
            val fmt = FormatInfo("$letter.", FormatDict.map[letter])
            val f = s[fi]
            val lh = maxOf(f.h, 0.004f)
            // solo le righe incolonnate con quella del formato (due schede affiancate restano separate)
            val col = s.indices.filter { abs(s[it].cx - f.cx) <= CX_TOL }
            val p = col.indexOf(fi)
            val members = ArrayList<Int>()
            members.add(fi)

            var prev = fi
            var n = 0
            for (q in p - 1 downTo 0) {
                val j = col[q]
                if (n >= MAX_UP || s[prev].y0 - s[j].y1 > GAP_MAX * lh) break
                if (FORMAT_RE.containsMatchIn(s[j].text)) break
                members.add(j)
                prev = j
                n++
                if (norm(s[j].text).startsWith("autori")) break
            }

            prev = fi
            n = 0
            for (q in p + 1 until col.size) {
                val j = col[q]
                if (n >= MAX_DOWN || s[j].y0 - s[prev].y1 > GAP_MAX * lh) break
                if (FORMAT_RE.containsMatchIn(s[j].text) || norm(s[j].text).startsWith("autori")) break
                members.add(j)
                prev = j
                n++
            }
            val sorted = members.sorted()
            out.add(Card(sorted, sorted.map { s[it] }, fmt))
        }
        return out
    }

    private fun cardHits(kw: String, re: Regex, page: Int, c: Card): List<Hit> {
        val ci = c.lines.indexOfFirst { norm(it.text).startsWith("contiene") }
        val items = if (ci >= 0) {
            c.lines.drop(ci).joinToString(" ") { it.text.trim() }
                .replace(CONTIENE_RE, "")
                .split(ITEM_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
        } else emptyList()
        val found = items.filter { re.containsMatchIn(norm(it)) }
        if (found.isNotEmpty()) return found.map { Hit(kw, page, it, c.format, true) }
        // la parola può comparire altrove nella scheda (es. tra gli autori)
        val line = c.lines.firstOrNull { re.containsMatchIn(norm(it.text)) } ?: return emptyList()
        return listOf(Hit(kw, page, line.text.trim(), c.format, true))
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

    fun search(pages: Map<Int, List<PageLine>>, keywords: List<String>): List<Hit> {
        val rx = keywords.mapNotNull { k -> regexFor(k)?.let { k to it } }
        if (rx.isEmpty()) return emptyList()
        val hits = ArrayList<Hit>()
        for (page in pages.keys.sorted()) {
            val s = pages.getValue(page).sortedBy { it.y0 }
            val cs = cards(s)
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
