package it.example.anteprime

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: VM = viewModel()
            AppTheme(vm.themeMode, vm.dynamicColor) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { App(vm) }
            }
        }
    }
}

/** PDF aperto nel lettore, con la pagina da cui partire (0-based). */
private data class Opened(val p: Preview, val page: Int = 0)

@Composable
fun App(vm: VM) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf<Opened?>(null) }
    var showNotes by rememberSaveable { mutableStateOf(false) }
    // Indietro: prima chiude il PDF (si torna a note o lista), poi la schermata note
    BackHandler(open != null || showNotes) { if (open != null) open = null else showNotes = false }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(vm.notifyEnabled) {
        if (vm.notifyEnabled && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val o = open
    when {
        o != null -> PdfScreen(
            file = vm.repo.file(o.p),
            title = o.p.title,
            source = o.p.source,
            startPage = o.page,
            allTags = vm.allTags,
            onCreateTag = { vm.createTag(it) },
            onDeleteTag = { vm.deleteTag(it) },
            onAddNote = { text, page, tags, release -> vm.addNote(text, o.p, page, tags, release) },
            onPageChanged = { vm.repo.setLastPage(o.p, it) }, // ricorda l'ultima pagina letta
            onBack = { open = null }
        )
        showNotes -> NotesScreen(vm, onBack = { showNotes = false }) { n ->
            // tocca una nota: apre il PDF alla sua pagina (lo scarica se non è già sul telefono)
            val p = n.preview
            if (p != null) {
                val page = n.page.coerceAtLeast(0)
                if (p.id in vm.localIds) open = Opened(p, page) else vm.fetch(p) { open = Opened(p, page) }
            }
        }
        else -> ListScreen(vm, onNotes = { showNotes = true }) {
            // dalla lista si riparte dall'ultima pagina letta; dalle note, dalla pagina della nota
            open = Opened(it, vm.repo.lastPage(it))
        }
    }

    vm.update?.let { u ->
        AlertDialog(
            onDismissRequest = { vm.update = null },
            title = { Text("Aggiornamento disponibile") },
            text = { Text("Versione ${u.version}" + if (u.notes.isNotBlank()) "\n\n" + u.notes.take(400) else "") },
            confirmButton = {
                TextButton(onClick = { vm.installUpdate() }, enabled = !vm.updating) {
                    Text(if (vm.updating) "Download…" else "Scarica e installa")
                }
            },
            dismissButton = { TextButton(onClick = { vm.update = null }) { Text("Più tardi") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(vm: VM, onNotes: () -> Unit, onOpen: (Preview) -> Unit) {
    var source by rememberSaveable { mutableStateOf<String?>(null) }
    var onlyLocal by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val shown = remember(vm.items, vm.localIds, source, onlyLocal) {
        vm.items.filter { (source == null || it.source.name == source) && (!onlyLocal || it.id in vm.localIds) }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Anteprime Fumetti", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconToggleButton(checked = onlyLocal, onCheckedChange = { onlyLocal = it }) {
                        Icon(
                            if (onlyLocal) Icons.Default.DownloadDone else Icons.Default.Download,
                            contentDescription = "Solo scaricati",
                            tint = if (onlyLocal) MaterialTheme.colorScheme.primary else LocalContentColor.current
                        )
                    }
                    IconButton(onClick = onNotes) {
                        Icon(Icons.AutoMirrored.Filled.Notes, contentDescription = "Note")
                    }
                    IconButton(onClick = { vm.scan() }, enabled = !vm.loading) {
                        Icon(Icons.Default.Refresh, contentDescription = "Aggiorna")
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Impostazioni") }
                        SettingsMenu(vm, menu) { menu = false }
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad)) {
            if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(selected = source == null, onClick = { source = null }, label = { Text("Tutti") })
                Source.values().forEach { s ->
                    FilterChip(
                        selected = source == s.name,
                        onClick = { source = s.name },
                        label = { Text(s.label) },
                        leadingIcon = {
                            Image(
                                painter = painterResource(s.iconRes()),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(20.dp).clip(RoundedCornerShape(4.dp))
                            )
                        }
                    )
                }
            }
            vm.errors.forEach {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
                ) {
                    Text(it, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp))
                }
            }
            if (shown.isEmpty() && !vm.loading) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("Nessun elemento", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(shown, key = { it.id }) { p -> PreviewCard(p, vm, onOpen) }
                }
            }
        }
    }
}

@Composable
private fun SettingsMenu(vm: VM, expanded: Boolean, onDismiss: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        ThemeMode.values().forEach { m ->
            DropdownMenuItem(
                text = { Text(m.label) },
                leadingIcon = { RadioButton(selected = vm.themeMode == m, onClick = null) },
                onClick = { vm.setTheme(m) }
            )
        }
        if (Build.VERSION.SDK_INT >= 31) {
            DropdownMenuItem(
                text = { Text("Colori dinamici") },
                leadingIcon = { Checkbox(checked = vm.dynamicColor, onCheckedChange = null) },
                onClick = { vm.setDynamic(!vm.dynamicColor) }
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Notifiche nuove anteprime") },
            leadingIcon = { Checkbox(checked = vm.notifyEnabled, onCheckedChange = null) },
            onClick = { vm.setNotify(!vm.notifyEnabled) }
        )
        DropdownMenuItem(
            text = { Text("Controlla aggiornamenti (v${BuildConfig.VERSION_NAME})") },
            onClick = { onDismiss(); vm.checkUpdate(true) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewCard(p: Preview, vm: VM, onOpen: (Preview) -> Unit) {
    val local = p.id in vm.localIds
    val busy = p.id in vm.busy
    // Manicomix (catalogo da centinaia di pagine): si scarica soltanto, senza aprirlo da solo a fine download
    val download = { if (!busy) vm.fetch(p) { if (p.source != Source.MANICOMIX) onOpen(p) } }
    Card(
        onClick = { if (local) onOpen(p) else download() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            SourceBadge(p.source)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    p.source.label + when {
                        local -> " · scaricato"
                        busy -> vm.progress[p.id]?.let { " · scaricamento ${it.first}/${it.second}" } ?: " · scaricamento…"
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            when {
                busy -> {
                    val pr = vm.progress[p.id]
                    if (pr != null && pr.second > 0) {
                        CircularProgressIndicator(
                            progress = { pr.first / pr.second.toFloat() },
                            modifier = Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp
                        )
                    } else {
                        CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                    }
                }
                local -> {
                    IconButton(onClick = { vm.remove(p) }) { Icon(Icons.Default.Delete, "Elimina") }
                    IconButton(onClick = { onOpen(p) }) { Icon(Icons.AutoMirrored.Filled.MenuBook, "Leggi") }
                }
                else -> IconButton(onClick = download) {
                    Icon(Icons.Default.Download, if (p.source == Source.MANICOMIX) "Scarica per leggerlo offline" else "Scarica e leggi")
                }
            }
        }
    }
}

private fun Source.iconRes(): Int = when (this) {
    Source.PANINI -> R.drawable.ic_panini
    Source.MANICOMIX -> R.drawable.ic_manicomix
    Source.STAR -> R.drawable.ic_starcomics
}

@Composable
private fun SourceBadge(s: Source) {
    Image(
        painter = painterResource(s.iconRes()),
        contentDescription = s.label,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
    )
}
