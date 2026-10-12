package it.example.anteprime

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Dialog per scrivere o correggere il testo di una nota (usato anche dal lettore PDF). */
@Composable
fun NoteDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text.trim()) }, enabled = text.isNotBlank()) { Text("Salva") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(vm: VM, onBack: () -> Unit, onOpen: (Note) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Note?>(null) }
    var deleting by remember { mutableStateOf<Note?>(null) }

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
                    items(vm.notes, key = { it.id }) { n ->
                        NoteCard(n, onOpen, onEdit = { editing = n }, onDelete = { deleting = n })
                    }
                }
            }
        }
    }

    if (adding) {
        NoteDialog("Nuova nota", "", onDismiss = { adding = false }) { vm.addNote(it, null, -1); adding = false }
    }
    editing?.let { n ->
        NoteDialog("Modifica nota", n.text, onDismiss = { editing = null }) { vm.editNote(n, it); editing = null }
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

@OptIn(ExperimentalMaterial3Api::class)
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
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Modifica") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Elimina") }
        }
    }
}
