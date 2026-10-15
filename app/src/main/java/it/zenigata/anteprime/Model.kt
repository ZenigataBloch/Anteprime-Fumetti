package it.zenigata.anteprime

import java.security.MessageDigest

enum class Source(val label: String) { PANINI("Panini"), STAR("Star Comics"), MANICOMIX("Manicomix"), JPOP("J-POP") }

private val HEX = "0123456789abcdef".toCharArray()
private val ISSUU_D = Regex("""[?&;]d=([^&;]+)""")
private val ISSUU_U = Regex("""[?&;]u=([^&;]+)""")

/** Primi 12 caratteri esadecimali dello SHA-1 dell'url (stesso id di prima: le cache su disco restano valide). */
private fun idOf(url: String): String {
    val d = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
    val c = CharArray(12)
    for (i in 0 until 6) {
        val b = d[i].toInt() and 0xff
        c[2 * i] = HEX[b ushr 4]
        c[2 * i + 1] = HEX[b and 15]
    }
    return String(c)
}

/** isPdf=false -> contenuto Issuu (Manicomix): solo online, non scaricabile. */
data class Preview(val source: Source, val title: String, val url: String, val isPdf: Boolean = true) {
    val id: String = idOf(url)

    /** Pagina Issuu "vera" del documento (si apre bene in Chrome Custom Tabs). */
    val webUrl: String
        get() {
            val d = ISSUU_D.find(url)?.groupValues?.get(1) ?: return url
            val u = ISSUU_U.find(url)?.groupValues?.get(1) ?: "catalogomega"
            return "https://issuu.com/$u/docs/$d"
        }
}
