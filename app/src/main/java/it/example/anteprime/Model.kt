package it.example.anteprime

import java.security.MessageDigest

enum class Source(val label: String) { PANINI("Panini"), STAR("Star Comics"), MANICOMIX("Manicomix") }

/** isPdf=false -> contenuto Issuu (Manicomix): solo online, non scaricabile. */
data class Preview(val source: Source, val title: String, val url: String, val isPdf: Boolean = true) {
    val id: String = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(12)
}
