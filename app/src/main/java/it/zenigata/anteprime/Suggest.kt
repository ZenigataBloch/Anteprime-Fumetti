package it.zenigata.anteprime

import java.text.Normalizer
import kotlin.math.abs

/**
 * "Stavi forse cercando": propone nomi alternativi usando le parole che compaiono davvero nei PDF già letti.
 * - refusi: parole del vocabolario a distanza 1-2 (con scambio di lettere vicine);
 * - scomposizione: una parola attaccata che si spezza in due parole del vocabolario (xmen -> x-men / x men).
 * Le proposte sono solo candidati: VM.findSuggestion le prova con la ricerca vera e tiene quella con più risultati.
 */
object Suggest {
    private val marks = Regex("\\p{Mn}+")
    private val sep = Regex("[^\\p{L}\\p{N}]+")

    /** Parole minuscole, senza accenti e senza punteggiatura. */
    fun words(s: String): List<String> =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(marks, "").split(sep).filter { it.isNotEmpty() }

    /** Aggiunge a [into] le parole delle pagine con la loro frequenza. */
    fun vocab(pages: Collection<List<PageLine>>, into: HashMap<String, Int> = HashMap()): HashMap<String, Int> {
        for (lines in pages) for (l in lines) for (w in words(l.text)) into[w] = (into[w] ?: 0) + 1
        return into
    }

    /** Distanza di Damerau-Levenshtein (scambi inclusi); oltre [max] restituisce max + 1 senza finire il calcolo. */
    private fun dist(a: String, b: String, max: Int): Int {
        if (abs(a.length - b.length) > max) return max + 1
        var prev2 = IntArray(b.length + 1)
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = i
            for (j in 1..b.length) {
                val c = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + c)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, prev2[j - 2] + 1)
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > max) return max + 1
            val t = prev2; prev2 = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    private fun alternatives(t: String, v: Map<String, Int>): List<String> {
        if (t in v) return listOf(t)
        val out = ArrayList<String>()
        // scomposizione in due parole presenti nei PDF
        if (t.length >= 2) {
            (1 until t.length)
                .filter { t.substring(0, it) in v && t.substring(it) in v }
                .sortedByDescending { minOf(v[t.substring(0, it)]!!, v[t.substring(it)]!!) }
                .take(2)
                .forEach { i -> out += t.substring(0, i) + "-" + t.substring(i); out += t.substring(0, i) + " " + t.substring(i) }
        }
        // refusi: parole simili, prima le più vicine, poi le più frequenti
        val k = if (t.length <= 4) 1 else 2
        v.entries.asSequence()
            .filter { it.key.length >= 3 && abs(it.key.length - t.length) <= k }
            .map { Triple(it.key, dist(t, it.key, k), it.value) }
            .filter { it.second <= k }
            .sortedWith(compareBy({ it.second }, { -it.third }))
            .take(3)
            .forEach { out += it.first }
        return out
    }

    /** Frasi alternative per [query] (parola per parola; le parole già presenti nei PDF restano com'è). */
    fun candidates(query: String, v: Map<String, Int>): List<String> {
        val toks = words(query)
        if (toks.isEmpty() || v.isEmpty()) return emptyList()
        var phrases = listOf("")
        for (t in toks) {
            val alts = alternatives(t, v).ifEmpty { listOf(t) }
            phrases = phrases.flatMap { p -> alts.map { if (p.isEmpty()) it else "$p $it" } }.take(30)
        }
        val orig = toks.joinToString(" ")
        return phrases.filter { it != orig }
    }
}
