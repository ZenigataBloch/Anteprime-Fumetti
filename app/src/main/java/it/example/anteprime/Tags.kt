package it.example.anteprime

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
            .map { it.groupValues[1].lowercase().replaceFirstChar { c -> c.uppercase() } + " " + it.groupValues[2] }
            .distinct().toList()

    /** Prima i mesi in ordine cronologico, poi gli altri tag in ordine alfabetico. */
    fun sort(tags: Collection<String>): List<String> =
        tags.distinct().sortedWith(compareBy<String>({ monthKey(it) ?: Int.MAX_VALUE }, { it.lowercase() }))

    private fun monthKey(tag: String): Int? {
        val m = MONTH_RE.matchEntire(tag.trim()) ?: return null
        return m.groupValues[2].toInt() * 12 + MESI.indexOf(m.groupValues[1].lowercase())
    }
}
