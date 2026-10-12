package it.example.anteprime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import java.text.Normalizer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** App di destinazione: ognuna ha la sua sintassi per grassetto/corsivo e un limite di lunghezza per messaggio. */
enum class ExportFormat(val label: String, val limit: Int) {
    WHATSAPP("WhatsApp", 60_000),
    TELEGRAM("Telegram", 4_000),
    DISCORD("Discord", 1_900), // 2000 è il limite dei messaggi, con un po' di margine
    PLAIN("Testo semplice", Int.MAX_VALUE)
}

/**
 * Trasforma le note in testo da incollare in chat, raggruppato per tag (mesi in ordine cronologico, poi gli altri tag,
 * poi le note senza tag). Una nota con più tag compare sotto ciascuno. Dentro a ogni tag le voci seguono la data di uscita
 * (se indicata) e poi l'ordine dei PDF. Se il testo supera il limite dell'app viene diviso in più messaggi,
 * ripetendo l'intestazione del tag.
 */
object Exporter {
    const val TAG_EMOJI = "\uD83C\uDFF7\uFE0F" // 🏷️
    private const val NO_TAG = "Senza tag"

    /**
     * WhatsApp e Telegram: il grassetto con * e ** dipende dal programma che riceve il testo condiviso e spesso non scatta,
     * quindi si usano i caratteri grassetto di Unicode (si vedono in grassetto ovunque, senza simboli).
     * Discord legge il markdown normalmente.
     */
    private fun ExportFormat.bold(s: String) = when (this) {
        ExportFormat.WHATSAPP, ExportFormat.TELEGRAM -> unicodeBold(s)
        ExportFormat.DISCORD -> "**${s}**"
        ExportFormat.PLAIN -> s
    }

    /** Lettere e cifre -> Mathematical Sans-Serif Bold (U+1D5D4...). Gli accenti restano come segni combinanti sopra la lettera. */
    private fun unicodeBold(s: String): String {
        val sb = StringBuilder()
        for (ch in Normalizer.normalize(s, Normalizer.Form.NFD)) {
            when (ch) {
                in 'A'..'Z' -> sb.appendCodePoint(0x1D5D4 + (ch - 'A'))
                in 'a'..'z' -> sb.appendCodePoint(0x1D5EE + (ch - 'a'))
                in '0'..'9' -> sb.appendCodePoint(0x1D7EC + (ch - '0'))
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * Emoji + testo in grassetto. L'emoji sta FUORI dai simboli di formattazione: WhatsApp (e in parte Telegram)
     * non applicano il grassetto se l'asterisco è attaccato a un'emoji ("*📚 Titolo*" resta con gli asterischi visibili).
     */
    private fun ExportFormat.emojiBold(emoji: String, s: String) = "$emoji ${bold(s)}"

    /** Titolo del tag: maiuscolo e in grassetto; su Discord diventa un'intestazione (###), quindi anche più grande. */
    private fun ExportFormat.heading(tag: String): String {
        val t = clean(tag.uppercase())
        return when (this) {
            ExportFormat.DISCORD -> "### $TAG_EMOJI $t"
            else -> emojiBold(TAG_EMOJI, t)
        }
    }

    /** Nei testi liberi toglie i simboli che altrimenti rovinerebbero la formattazione. */
    private fun ExportFormat.clean(s: String) = if (this == ExportFormat.PLAIN) s else s.replace(Regex("[*_~`]"), "")

    /** Vero per le righe che sono il titolo di un tag (serve all'anteprima per ingrandirle). */
    fun isHeading(line: String) = line.trimStart('#', ' ', '*').startsWith(TAG_EMOJI)

    private val NUM_DATE = Regex("""(\d{1,2})[/.\-](\d{1,2})[/.\-](\d{2,4})""")
    private val TEXT_DATE = Regex("""(?:(\d{1,2})\s+)?([A-Za-zàèéìòù]+)\s+(\d{4})""")

    /** Chiave per ordinare le date scritte a mano: "12/11/2026", "12 novembre 2026", "novembre 2026". */
    fun releaseKey(s: String): Int? {
        NUM_DATE.find(s)?.let { m ->
            val y = m.groupValues[3].toInt().let { if (it < 100) it + 2000 else it }
            return y * 10000 + m.groupValues[2].toInt() * 100 + m.groupValues[1].toInt()
        }
        TEXT_DATE.find(s)?.let { m ->
            val mo = Tags.monthNumber(m.groupValues[2])
            if (mo > 0) return m.groupValues[3].toInt() * 10000 + mo * 100 + (m.groupValues[1].toIntOrNull() ?: 0)
        }
        return null
    }

    private fun entry(f: ExportFormat, n: Note): String {
        val sb = StringBuilder("• ").append(f.clean(n.text.replace(Regex("""\s*\n\s*"""), " ")))
        if (n.release.isNotBlank()) sb.append("\n   📅 ").append(f.clean(n.release))
        n.preview?.let { p ->
            sb.append("\n   📖 ").append(f.clean(p.title)).append(if (n.page >= 0) " (pag. ${n.page + 1})" else "")
        }
        return sb.toString()
    }

    private data class Item(val tag: String, val entry: String)

    /** Uno o più messaggi pronti da condividere (lista vuota se non ci sono note). */
    fun build(notes: List<Note>, f: ExportFormat): List<String> {
        if (notes.isEmpty()) return emptyList()

        val byTag = linkedMapOf<String, MutableList<Note>>()
        for (n in notes) for (t in n.tags.ifEmpty { listOf(NO_TAG) }) byTag.getOrPut(t) { mutableListOf() }.add(n)
        val order = Tags.sort(byTag.keys.filter { it != NO_TAG }) + listOfNotNull(NO_TAG.takeIf { it in byTag })

        val noteOrder = compareBy<Note>(
            { releaseKey(it.release) ?: Int.MAX_VALUE },
            { it.preview?.source?.ordinal ?: Int.MAX_VALUE },
            { it.preview?.title ?: "" },
            { it.page },
            { it.text.lowercase() }
        )
        val items = order.flatMap { tag -> byTag.getValue(tag).sortedWith(noteOrder).map { Item(tag, entry(f, it)) } }

        val limit = if (f.limit == Int.MAX_VALUE) Int.MAX_VALUE else f.limit - 90 // spazio per il titolo
        val parts = mutableListOf<String>()
        val cur = StringBuilder()
        var lastTag: String? = null

        fun piece(b: Item): String {
            val sb = StringBuilder()
            if (b.tag != lastTag) {
                if (cur.isNotEmpty()) sb.append("\n")
                sb.append(f.heading(b.tag)).append("\n")
            }
            sb.append(b.entry).append("\n")
            return sb.toString()
        }

        for (item in items) {
            var p = piece(item)
            if (cur.isNotEmpty() && cur.length + p.length > limit) {
                parts += cur.toString().trimEnd()
                cur.clear(); lastTag = null
                p = piece(item) // nel nuovo messaggio l'intestazione si ripete
            }
            if (p.length > limit) p = p.take(limit) // una voce da sola oltre il limite: si taglia
            cur.append(p)
            lastTag = item.tag
        }
        if (cur.isNotEmpty()) parts += cur.toString().trimEnd()

        val n = notes.size
        return parts.mapIndexed { i, body ->
            val title = if (parts.size > 1) "Anteprime Fumetti (${i + 1}/${parts.size})"
            else "Anteprime Fumetti · $n ${if (n == 1) "nota" else "note"}"
            f.emojiBold("📚", title) + "\n\n" + body
        }
    }
}

private fun shareText(ctx: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    ctx.startActivity(Intent.createChooser(send, "Condividi note").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun copyText(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Note", text))
    Toast.makeText(ctx, "Copiato", Toast.LENGTH_SHORT).show()
}

/**
 * Anteprima del testo da condividere, con scelta dell'app di destinazione (cambia la formattazione).
 * Se il testo è troppo lungo per un messaggio, è diviso in parti da condividere una alla volta.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ExportDialog(notes: List<Note>, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var formatName by rememberSaveable { mutableStateOf(ExportFormat.WHATSAPP.name) }
    val format = ExportFormat.valueOf(formatName)
    val parts = remember(notes, format) { Exporter.build(notes, format) }
    var part by remember(format) { mutableIntStateOf(0) }
    val idx = part.coerceIn(0, (parts.size - 1).coerceAtLeast(0))
    val text = parts.getOrElse(idx) { "" }
    // nell'anteprima i titoli dei tag sono in grassetto e un po' più grandi del resto
    val styled = remember(text) {
        buildAnnotatedString {
            text.lines().forEachIndexed { i, line ->
                if (i > 0) append("\n")
                if (Exporter.isHeading(line)) withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp)) { append(line) }
                else append(line)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Condividi ${notes.size} " + if (notes.size == 1) "nota" else "note") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExportFormat.values().forEach { f ->
                        FilterChip(selected = f == format, onClick = { formatName = f.name }, label = { Text(f.label) })
                    }
                }
                if (parts.size > 1) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Troppo lungo per un solo messaggio ${format.label}: diviso in ${parts.size} parti, da inviare una alla volta.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        parts.indices.forEach { i ->
                            FilterChip(selected = i == idx, onClick = { part = i }, label = { Text("Parte ${i + 1}") })
                        }
                    }
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth()
                ) {
                    Text(styled, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp))
                }
                if (format != ExportFormat.PLAIN) {
                    Text(
                        if (format == ExportFormat.DISCORD) "I simboli di formattazione (* _) diventano grassetto e corsivo una volta inviato il messaggio."
                        else "I titoli usano caratteri grassetto speciali: si vedono in grassetto ovunque, ma nella chat la ricerca potrebbe non trovarli.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { shareText(ctx, text) }, enabled = text.isNotEmpty()) { Text("Condividi") } },
        dismissButton = {
            Row {
                TextButton(onClick = { copyText(ctx, text) }, enabled = text.isNotEmpty()) { Text("Copia") }
                TextButton(onClick = onDismiss) { Text("Chiudi") }
            }
        }
    )
}
