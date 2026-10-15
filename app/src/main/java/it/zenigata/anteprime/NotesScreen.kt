package it.zenigata.anteprime

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
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
 * I tag disponibili sono solo quelli del catalogo (allTags): li crei col campo "Nuovo tag"
 * o da "Gestisci", dove puoi anche cancellarli. Nessun tag viene assegnato in automatico: formato/colore riconosciuti
 * ([suggestedTags]) e mesi citati nel testo ("novembre 2026") compaiono solo come suggerimento, che si applica toccandolo.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun NoteDialog(
    title: String,
    initial: String,
    initialTags: List<String> = emptyList(),
    initialRelease: String = "",
    suggestedTags: List<String> = emptyList(),
    allTags: List<String>,
    onCreateTag: (String) -> Unit,
    onDeleteTag: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (text: String, tags: List<String>, release: String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    var release by remember { mutableStateOf(initialRelease) }
    var selected by remember { mutableStateOf(initialTags.toSet()) }
    var newTag by remember { mutableStateOf("") }
    var managing by remember { mutableStateOf(false) }
    // suggerimenti: tag riconosciuti dalla scheda (formato, colore) + mesi citati nel testo (si aggiornano mentre scrivi),
    // esclusi quelli già selezionati. Non vengono mai applicati da soli.
    val suggestions = (suggestedTags + remember(text) { Tags.fromText(text) })
        .distinctBy { it.lowercase() }
        .filter { s -> selected.none { it.equals(s, ignoreCase = true) } }

    fun addTag() {
        val t = Tags.normalize(newTag)
        if (t.isBlank()) return
        val name = allTags.firstOrNull { it.equals(t, ignoreCase = true) } ?: t // niente doppioni "natale"/"Natale"
        onCreateTag(name)
        selected = selected + name
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
                Spacer(Modifier.height(8.dp))
                // facoltativa: si vede nell'export e ne ordina le voci
                OutlinedTextField(
                    value = release,
                    onValueChange = { release = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Data di uscita (es. 12/11/2026)") },
                    singleLine = true
                )
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Tag", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { managing = true }) { Text("Gestisci") }
                }
                if (allTags.isEmpty()) {
                    Text(
                        "Nessun tag: creane uno qui sotto.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        allTags.forEach { t ->
                            FilterChip(
                                selected = t in selected,
                                onClick = { selected = if (t in selected) selected - t else selected + t },
                                label = { Text(t) }
                            )
                        }
                    }
                }
                if (suggestions.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Suggeriti",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        suggestions.forEach { s ->
                            SuggestionChip(
                                onClick = {
                                    val name = allTags.firstOrNull { it.equals(s, ignoreCase = true) } ?: s
                                    onCreateTag(name) // se non esiste ancora lo crea, solo ora che lo accetti
                                    selected = selected + name
                                },
                                label = { Text("+ $s") }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                NewTagField(newTag, { newTag = it }, ::addTag)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(text.trim(), Tags.sort(selected.filter { it in allTags }), release.trim()) }, // ignora tag cancellati nel frattempo
                enabled = text.isNotBlank()
            ) { Text("Salva") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )

    if (managing) TagsDialog(allTags, onCreateTag, onDeleteTag) { managing = false }
}

@Composable
private fun NewTagField(value: String, onChange: (String) -> Unit, onAdd: () -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Nuovo tag") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onAdd() }),
        trailingIcon = {
            IconButton(onClick = onAdd, enabled = value.isNotBlank()) { Icon(Icons.Default.Add, "Aggiungi tag") }
        }
    )
}

/** Crea e cancella i tag. Cancellare un tag lo toglie da tutte le note (chiede conferma). */
@Composable
fun TagsDialog(tags: List<String>, onCreate: (String) -> Unit, onDelete: (String) -> Unit, onDismiss: () -> Unit) {
    var newTag by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<String?>(null) }

    fun add() {
        val t = Tags.normalize(newTag)
        if (t.isBlank()) return
        onCreate(tags.firstOrNull { it.equals(t, ignoreCase = true) } ?: t)
        newTag = ""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Gestisci tag") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                NewTagField(newTag, { newTag = it }, ::add)
                Spacer(Modifier.height(8.dp))
                if (tags.isEmpty()) {
                    Text(
                        "Nessun tag. Creane uno qui sopra.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    tags.forEach { t ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(t, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            IconButton(onClick = { deleting = t }) { Icon(Icons.Default.Delete, "Elimina tag $t") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Chiudi") } }
    )

    deleting?.let { t ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Eliminare il tag \"$t\"?") },
            text = { Text("Verrà tolto da tutte le note che lo usano. Le note non vengono cancellate.") },
            confirmButton = { TextButton(onClick = { onDelete(t); deleting = null }) { Text("Elimina") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Annulla") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(vm: VM, onBack: () -> Unit, onOpen: (Note) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Note?>(null) }
    var deleting by remember { mutableStateOf<Note?>(null) }
    var managingTags by remember { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) } // selezione per l'export (tieni premuto su una nota)
    var exporting by remember { mutableStateOf<List<Note>?>(null) }
    val selecting = selectedIds.isNotEmpty()
    BackHandler(selecting) { selectedIds = emptySet() } // "indietro" esce prima dalla selezione

    val tags = vm.allTags
    val active = filter?.takeIf { it in tags } // se l'ultima nota con quel tag sparisce, il filtro decade
    val shown = if (active == null) vm.notes else vm.notes.filter { active in it.tags }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(if (selecting) "${selectedIds.size} selezionate" else "Wishlist") },
                navigationIcon = {
                    if (selecting) IconButton(onClick = { selectedIds = emptySet() }) { Icon(Icons.Default.Close, "Annulla selezione") }
                    else IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") }
                },
                actions = {
                    if (selecting) {
                        IconButton(onClick = { selectedIds = shown.map { it.id }.toSet() }) { Icon(Icons.Default.SelectAll, "Seleziona tutte") }
                        IconButton(onClick = { exporting = vm.notes.filter { it.id in selectedIds } }) {
                            Icon(Icons.Default.Share, "Condividi selezionate")
                        }
                    } else if (vm.wishTab == 0) {
                        // senza selezione si condivide quello che si vede (tutte le note, o quelle del tag filtrato)
                        if (shown.isNotEmpty()) IconButton(onClick = { exporting = shown }) { Icon(Icons.Default.Share, "Condividi tutte") }
                        IconButton(onClick = { managingTags = true }) { Icon(Icons.AutoMirrored.Filled.Label, "Gestisci tag") }
                        IconButton(onClick = { adding = true }) { Icon(Icons.Default.Add, "Nuova nota") }
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad)) {
            TabRow(selectedTabIndex = vm.wishTab) {
                Tab(selected = vm.wishTab == 0, onClick = { vm.wishTab = 0 }, text = { Text("Note") })
                Tab(selected = vm.wishTab == 1, onClick = { vm.resetTest(); vm.wishTab = 1 }, text = { Text("Opere seguite (${vm.follows.size})") })
            }
            if (vm.wishTab == 1) FollowsTab(vm) else Column(Modifier.weight(1f)) {
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
            } else if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                    Text("Nessuna nota con questo tag.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(shown, key = { it.id }) { n ->
                        NoteCard(
                            n, onOpen,
                            selecting = selecting,
                            checked = n.id in selectedIds,
                            onToggle = { selectedIds = if (n.id in selectedIds) selectedIds - n.id else selectedIds + n.id },
                            onEdit = { editing = n },
                            onDelete = { deleting = n }
                        )
                    }
                }
            }
        }
        }
    }

    if (adding) {
        NoteDialog(
            "Nuova nota", "", allTags = tags,
            onCreateTag = { vm.createTag(it) }, onDeleteTag = { vm.deleteTag(it) },
            onDismiss = { adding = false }
        ) { text, t, r ->
            vm.addNote(text, null, -1, t, r); adding = false
        }
    }
    editing?.let { n ->
        NoteDialog(
            "Modifica nota", n.text, initialTags = n.tags,
            initialRelease = n.release, allTags = tags,
            onCreateTag = { vm.createTag(it) }, onDeleteTag = { vm.deleteTag(it) },
            onDismiss = { editing = null }
        ) { text, t, r ->
            vm.editNote(n, text, t, r); editing = null
        }
    }
    exporting?.let { ExportDialog(it) { exporting = null } }
    if (managingTags) TagsDialog(tags, { vm.createTag(it) }, { vm.deleteTag(it) }) { managingTags = false }
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(
    n: Note,
    onOpen: (Note) -> Unit,
    selecting: Boolean,
    checked: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (checked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = { if (selecting) onToggle() else if (n.preview != null) onOpen(n) },
            onLongClick = onToggle // tieni premuto: inizia la selezione (o aggiunge alla selezione)
        )
    ) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selecting) {
                Checkbox(checked = checked, onCheckedChange = null)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(n.text, style = MaterialTheme.typography.bodyLarge)
                if (n.release.isNotBlank()) {
                    Text("uscita ${n.release}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
            if (!selecting) {
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Modifica") }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Elimina") }
            }
        }
    }
}

/** Scheda "Opere seguite" della Wishlist: aggiungi/rimuovi i nomi e prova la ricerca sulle ultime anteprime. */
@Composable
private fun FollowsTab(vm: VM) {
    var text by remember { mutableStateOf("") }
    if (vm.showFollowResults && vm.testResults.isNotEmpty()) FollowResultsDialog(vm)
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                "Quando esce una nuova anteprima la scarico in background e ti avviso se contiene questi nomi.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    label = { Text("Nome o serie") }, modifier = Modifier.weight(1f)
                )
                IconButton(enabled = text.isNotBlank(), onClick = { vm.addFollow(text); text = "" }) {
                    Icon(Icons.Default.Add, contentDescription = "Aggiungi")
                }
            }
        }
        items(vm.follows, key = { it }) { n ->
            Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(n, modifier = Modifier.weight(1f).clickable { if (!vm.testRunning) vm.testFollows(listOf(n)) })
                IconButton(onClick = { vm.removeFollow(n) }) { Icon(Icons.Default.Close, contentDescription = "Rimuovi $n") }
            }
        }
        if (vm.testRunning) {
            item { TextButton(onClick = { vm.stopTest() }) { Text("Ferma la ricerca") } }
        }
        if (vm.testRunning) {
            item {
                Text("${vm.testProgress.first + 1}/${vm.testProgress.second} · ${vm.testCurrent}", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        } else if (vm.testDone && vm.testResults.isEmpty()) {
            item { Text("Nessuna corrispondenza.", style = MaterialTheme.typography.bodySmall) }
        }
        if (!vm.testRunning && vm.testResults.isNotEmpty()) {
            item { TextButton(onClick = { vm.showFollowResults = true }) { Text("Risultati (${vm.testResults.size})") } }
        }
    }
}

/** Risultati della ricerca sopra "Opere seguite": il tocco apre il PDF alla pagina; con BACK dal PDF si ritorna a questa lista. */
@Composable
private fun FollowResultsDialog(vm: VM) {
    AlertDialog(
        onDismissRequest = { vm.showFollowResults = false },
        title = { Text("Risultati") },
        confirmButton = { TextButton(onClick = { vm.showFollowResults = false }) { Text("Chiudi") } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                vm.testResults.forEach { (p, hits) ->
                    Text(p.title, style = MaterialTheme.typography.titleSmall)
                    hits.distinctBy { it.keyword to it.page }.sortedBy { it.page }.take(8).forEach { h ->
                        Text(
                            "p. ${h.page + 1} · ${h.keyword}: ${h.text.take(60)}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().clickable { vm.pendingOpen = p.id to h.page } // la finestra resta aperta (flag nel VM)
                        )
                    }
                }
            }
        }
    )
}
