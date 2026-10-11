package it.example.anteprime

import java.security.MessageDigest

enum class Source(val label: String) { PANINI("Panini"), STAR("Star Comics"), MANICOMIX("Manicomix") }

/** isPdf=false -> contenuto Issuu (Manicomix): solo online, non scaricabile. */
data class Preview(val source: Source, val title: String, val url: String, val isPdf: Boolean = true) {
    val id: String = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(12)

    /** Pagina Issuu "vera" del documento (si apre bene in Chrome Custom Tabs). */
    val webUrl: String
        get() {
            val d = Regex("""[?&;]d=([^&;]+)""").find(url)?.groupValues?.get(1) ?: return url
            val u = Regex("""[?&;]u=([^&;]+)""").find(url)?.groupValues?.get(1) ?: "catalogomega"
            return "https://issuu.com/$u/docs/$d"
        }
}
