package it.example.anteprime

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Dialog per scrivere o correggere una nota (usato anche dal lettore PDF), con i tag.
 * - initialTags: tag già selezionati all'apertura (es. il mese ricavato dal titolo del PDF)
 * - knownTags: tag già usati in altre note, riproposti come chip
 * Oltre a questi compaiono i prossimi mesi, i mesi citati nel testo ("novembre 2026") e i tag creati qui.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoteDialog(
    title: String,
    initial: String,
    initialTags: List<String> = emptyList(),
    knownTags: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onSave: (text: String, tags: List<String>) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    var selected by remember { mutableStateOf(initialTags.toSet()) }
    var created by remember { mutableStateOf(listOf<String>()) }
    var newTag by remember { mutableStateOf("") }
    val months = remember { Tags.upcomingMonths() }
    val typed = remember(text) { Tags.fromText(text) }
    val chips = Tags.sort(knownTags + initialTags + months + typed + created + selected)

    fun addTag() {
        val t = Tags.normalize(newTag)
        if (t.isBlank()) return
        val existing = chips.firstOrNull { it.equals(t, ignoreCase = true) } ?: t // niente doppioni "natale"/"Natale"
        if (existing !in chips) created = created + existing
        selected = selected + existing
        newTag = ""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
                Spacer(Modifier.height(12.dp))
                Text("Tag", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    chips.forEach { t ->
                        FilterChip(
                            selected = t in selected,
                            onClick = { selected = if (t in selected) selected - t else selected + t },
                            label = { Text(t) }
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = newTag,
                    onValueChange = { newTag = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Nuovo tag") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addTag() }),
                    trailingIcon = {
                        IconButton(onClick = { addTag() }, enabled = newTag.isNotBlank()) {
                            Icon(Icons.Default.Add, "Aggiungi tag")
                        }
                    }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim(), Tags.sort(selected)) }, enabled = text.isNotBlank()) { Text("Salva") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(vm: VM, onBack: () -> Unit, onOpen: (Note) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Note?>(null) }
    var deleting by remember { mutableStateOf<Note?>(null) }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }

    val tags = vm.allTags
    val active = filter?.takeIf { it in tags } // se l'ultima nota con quel tag sparisce, il filtro decade
    val shown = if (active == null) vm.notes else vm.notes.filter { active in it.tags }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Note") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } },
                actions = { IconButton(onClick = { adding = true }) { Icon(Icons.Default.Add, "Nuova nota") } }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad)) {
            if (vm.busy.isNotEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth()) // PDF in download
            if (tags.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(selected = active == null, onClick = { filter = null }, label = { Text("Tutte") })
                    tags.forEach { t ->
                        FilterChip(
                            selected = active == t,
                            onClick = { filter = if (active == t) null else t },
                            label = { Text(t) }
                        )
                    }
                }
            }
            if (vm.notes.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                    Text(
                        "Nessuna nota.\nApri un PDF, tocca l'evidenziatore e trascina sul testo per salvarlo qui.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(shown, key = { it.id }) { n ->
                        NoteCard(n, onOpen, onEdit = { editing = n }, onDelete = { deleting = n })
                    }
                }
            }
        }
    }

    if (adding) {
        NoteDialog("Nuova nota", "", knownTags = tags, onDismiss = { adding = false }) { text, t ->
            vm.addNote(text, null, -1, t); adding = false
        }
    }
    editing?.let { n ->
        NoteDialog("Modifica nota", n.text, initialTags = n.tags, knownTags = tags, onDismiss = { editing = null }) { text, t ->
            vm.editNote(n, text, t); editing = null
        }
    }
    deleting?.let { n ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Eliminare la nota?") },
            text = { Text(n.text, maxLines = 4) },
            confirmButton = { TextButton(onClick = { vm.deleteNote(n); deleting = null }) { Text("Elimina") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Annulla") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun NoteCard(n: Note, onOpen: (Note) -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(
        onClick = { if (n.preview != null) onOpen(n) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(n.text, style = MaterialTheme.typography.bodyLarge)
                n.preview?.let { p ->
                    Text(
                        p.source.label + " · " + p.title + if (n.page >= 0) " · pag. ${n.page + 1}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (n.tags.isNotEmpty()) {
                    FlowRow(
                        Modifier.padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Tags.sort(n.tags).forEach { t ->
                            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                                Text(
                                    t,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Modifica") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Elimina") }
        }
    }
}
