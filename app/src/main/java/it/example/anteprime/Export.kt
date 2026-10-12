package it.example.anteprime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** App di destinazione: ognuna ha la sua sintassi per grassetto/corsivo e un limite di lunghezza per messaggio. */
enum class ExportFormat(val label: String, val limit: Int) {
    WHATSAPP("WhatsApp", 60_000),
    TELEGRAM("Telegram", 4_000),
    DISCORD("Discord", 1_900), // 2000 è il limite dei messaggi, con un po' di margine
    PLAIN("Testo semplice", Int.MAX_VALUE)
}

/**
 * Trasforma le note in testo da incollare in chat, raggruppato così:
 * editore italiano (Panini, Star...) > casa editrice originale (Marvel, DC...) > voci in ordine di data di uscita.
 * Le note libere (non nate da un PDF) finiscono in "Altre note". Se il testo supera il limite dell'app
 * viene diviso in più messaggi, ripetendo le intestazioni.
 */
object Exporter {
    private const val NO_ORIGINAL = "Casa editrice non indicata"
    private const val FREE_NOTES = "Altre note"

    private fun ExportFormat.bold(s: String) = when (this) {
        ExportFormat.WHATSAPP -> "*${s}*"
        ExportFormat.TELEGRAM, ExportFormat.DISCORD -> "**${s}**"
        ExportFormat.PLAIN -> s
    }

    private fun ExportFormat.italic(s: String) = when (this) {
        ExportFormat.WHATSAPP -> "_${s}_"
        ExportFormat.TELEGRAM -> "__${s}__"
        ExportFormat.DISCORD -> "*${s}*"
        ExportFormat.PLAIN -> s
    }

    /** Nei testi liberi toglie i simboli che altrimenti rovinerebbero la formattazione. */
    private fun ExportFormat.clean(s: String) = if (this == ExportFormat.PLAIN) s else s.replace(Regex("[*_~`]"), "")

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
        if (n.tags.isNotEmpty()) sb.append("\n   🏷 ").append(f.clean(Tags.sort(n.tags).joinToString(", ")))
        return sb.toString()
    }

    private data class Item(val h1: String, val h2: String?, val entry: String)

    /** Uno o più messaggi pronti da condividere (lista vuota se non ci sono note). */
    fun build(notes: List<Note>, f: ExportFormat): List<String> {
        if (notes.isEmpty()) return emptyList()

        // ordine: editore (come nell'app), poi casa editrice originale (senza per ultima), poi data di uscita
        val items = mutableListOf<Item>()
        val sources: List<Source?> = Source.values().toList() + null
        for (s in sources) {
            val group = notes.filter { it.preview?.source == s }
            if (group.isEmpty()) continue
            val h1 = s?.label ?: FREE_NOTES
            val byOriginal = group.groupBy { it.original.trim().lowercase() }
            val orderedKeys = byOriginal.keys.sortedWith(compareBy<String>({ it.isEmpty() }, { it }))
            for (k in orderedKeys) {
                val list = byOriginal.getValue(k)
                val name = list.first().original.trim().ifEmpty { NO_ORIGINAL }
                val h2 = if (s == null) null else name // le note libere non hanno un editore: niente sotto-intestazione
                list.sortedWith(compareBy<Note>({ releaseKey(it.release) ?: Int.MAX_VALUE }, { it.text.lowercase() }))
                    .forEach { items += Item(h1, h2, entry(f, it)) }
            }
        }

        val limit = if (f.limit == Int.MAX_VALUE) Int.MAX_VALUE else f.limit - 90 // spazio per il titolo
        val parts = mutableListOf<String>()
        val cur = StringBuilder()
        var lastH1: String? = null
        var lastH2: String? = null

        fun piece(b: Item): String {
            val sb = StringBuilder()
            val newH1 = b.h1 != lastH1
            if (newH1) {
                if (cur.isNotEmpty()) sb.append("\n")
                sb.append(f.bold(f.clean(b.h1.uppercase()))).append("\n")
            }
            if (b.h2 != null && (newH1 || b.h2 != lastH2)) sb.append("▸ ").append(f.italic(f.clean(b.h2))).append("\n")
            sb.append(b.entry).append("\n")
            return sb.toString()
        }

        for (item in items) {
            var p = piece(item)
            if (cur.isNotEmpty() && cur.length + p.length > limit) {
                parts += cur.toString().trimEnd()
                cur.clear(); lastH1 = null; lastH2 = null
                p = piece(item) // nel nuovo messaggio le intestazioni si ripetono
            }
            if (p.length > limit) p = p.take(limit) // una voce da sola oltre il limite: si taglia
            cur.append(p)
            lastH1 = item.h1; lastH2 = item.h2
        }
        if (cur.isNotEmpty()) parts += cur.toString().trimEnd()

        val n = notes.size
        return parts.mapIndexed { i, body ->
            val title = if (parts.size > 1) "📚 Anteprime Fumetti (${i + 1}/${parts.size})"
            else "📚 Anteprime Fumetti · $n ${if (n == 1) "nota" else "note"}"
            f.bold(title) + "\n\n" + body
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
                    Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp))
                }
                if (format != ExportFormat.PLAIN) {
                    Text(
                        "I simboli di formattazione (* _) diventano grassetto e corsivo una volta inviato il messaggio.",
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
