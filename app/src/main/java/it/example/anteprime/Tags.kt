package it.example.anteprime

import java.util.Calendar

/** Tag delle note: libero, ma "Novembre 2026" e simili vengono riconosciuti come mesi (suggeriti e ordinati per data). */
object Tags {
    private val MESI = listOf(
        "gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno",
        "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre"
    )
    private val MONTH_RE = Regex("""\b(${MESI.joinToString("|")})\s+(\d{4})\b""", RegexOption.IGNORE_CASE)

    /** Tag-mese trovati nel testo, es. "esce a novembre 2026" -> ["Novembre 2026"]. */
    fun fromText(s: String): List<String> =
        MONTH_RE.findAll(s)
            .map { canonicalMonth(it.groupValues[1], it.groupValues[2]) }
            .distinct().toList()

    private fun canonicalMonth(m: String, y: String) = m.lowercase().replaceFirstChar { it.uppercase() } + " " + y

    /** Pulisce un tag scritto a mano: spazi normalizzati, e "novembre 2026" diventa "Novembre 2026". */
    fun normalize(raw: String): String {
        val t = raw.trim().replace(Regex("""\s+"""), " ").take(30)
        val m = MONTH_RE.matchEntire(t) ?: return t
        return canonicalMonth(m.groupValues[1], m.groupValues[2])
    }

    /** Mese corrente e successivi, come tag pronti da proporre (le anteprime riguardano le uscite future). */
    fun upcomingMonths(count: Int = 3): List<String> {
        val c = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
        return List(count) {
            canonicalMonth(MESI[c.get(Calendar.MONTH)], c.get(Calendar.YEAR).toString()).also { c.add(Calendar.MONTH, 1) }
        }
    }

    /** Prima i mesi in ordine cronologico, poi gli altri tag in ordine alfabetico. */
    fun sort(tags: Collection<String>): List<String> =
        tags.distinct().sortedWith(compareBy<String>({ monthKey(it) ?: Int.MAX_VALUE }, { it.lowercase() }))

    private fun monthKey(tag: String): Int? {
        val m = MONTH_RE.matchEntire(tag.trim()) ?: return null
        return m.groupValues[2].toInt() * 12 + MESI.indexOf(m.groupValues[1].lowercase())
    }
}
