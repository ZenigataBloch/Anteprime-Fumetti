package it.zenigata.anteprime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: VM by viewModels() // la stessa istanza che userebbe viewModel() dentro Compose

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) vm.handleIntent(intent) // avvio dal tocco su una notifica "opere seguite"
        setContent {
            AppTheme(vm.themeMode, vm.dynamicColor) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { App(vm) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.handleIntent(intent)
    }
}

/** PDF aperto nel lettore, con la pagina da cui partire (0-based). */
private data class Opened(val p: Preview, val page: Int = 0)

@Composable
fun App(vm: VM) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf<Opened?>(null) }
    var showNotes by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    // Indietro: prima chiude il PDF (si torna a note o lista), poi la schermata note
    BackHandler(open != null || showNotes || showSettings) { if (open != null) open = null else if (showNotes) showNotes = false else showSettings = false }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val wantNotif = vm.notifyEnabled || vm.follows.isNotEmpty() // anche le opere seguite mandano notifiche
    LaunchedEffect(wantNotif) {
        if (wantNotif && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // tocco su una notifica di "opere seguite": apre il PDF alla pagina trovata
    LaunchedEffect(vm.pendingOpen, vm.items) {
        val (id, page) = vm.pendingOpen ?: return@LaunchedEffect
        val p = vm.items.firstOrNull { it.id == id } ?: return@LaunchedEffect
        vm.pendingOpen = null
        if (p.id in vm.localIds) open = Opened(p, page) else vm.fetch(p) { open = Opened(p, page) }
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
        showSettings -> SettingsScreen(vm, onBack = { showSettings = false })
        else -> ListScreen(vm, onNotes = { showNotes = true }, onSettings = { showSettings = true }) {
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
fun ListScreen(vm: VM, onNotes: () -> Unit, onSettings: () -> Unit, onOpen: (Preview) -> Unit) {
    var onlyLocal by rememberSaveable { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(vm.returnToSearch) } // tornando dal PDF di un risultato si riapre l'elenco
    LaunchedEffect(Unit) { vm.returnToSearch = false }
    // pagina 0 = Tutti, poi una pagina per fonte; lo swipe cambia fonte
    val tabs: List<Source?> = remember { listOf<Source?>(null) + Source.values().toList() }
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()
    if (showSearch) WorkSearchDialog(vm) { showSearch = false }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Anteprime Fumetti",
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp)
                    )
                },
                actions = {
                    IconToggleButton(checked = onlyLocal, onCheckedChange = { onlyLocal = it }) {
                        Icon(
                            if (onlyLocal) Icons.Default.DownloadDone else Icons.Default.Download,
                            contentDescription = "Solo scaricati",
                            tint = if (onlyLocal) MaterialTheme.colorScheme.primary else LocalContentColor.current
                        )
                    }
                    IconButton(onClick = onNotes) {
                        Icon(Icons.AutoMirrored.Filled.Notes, contentDescription = "Wishlist")
                    }
                    IconButton(onClick = { vm.resetTest(); showSearch = true }) { Icon(Icons.Default.Search, contentDescription = "Cerca un'opera") }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Impostazioni") }
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
                FilterChip(
                    selected = pagerState.currentPage == 0,
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                    label = { Text("Tutti") }
                )
                Source.values().forEachIndexed { i, s ->
                    FilterChip(
                        selected = pagerState.currentPage == i + 1,
                        onClick = { scope.launch { pagerState.animateScrollToPage(i + 1) } },
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
            PullToRefreshBox(isRefreshing = vm.loading, onRefresh = { vm.scan() }, modifier = Modifier.fillMaxSize()) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.Top) { page ->
                val src = tabs[page]
                val shown = remember(vm.items, vm.localIds, src, onlyLocal) {
                    vm.items.filter { (src == null || it.source == src) && (!onlyLocal || it.id in vm.localIds) }
                }
                if (shown.isEmpty() && !vm.loading) {
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text("Nessun elemento", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    val listState = rememberLazyListState()
                    LaunchedEffect(shown.firstOrNull()?.id) { listState.scrollToItem(0) }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(shown, key = { it.id }) { p -> PreviewCard(p, vm, onOpen) }
                    }
                }
            }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewCard(p: Preview, vm: VM, onOpen: (Preview) -> Unit) {
    val local = p.id in vm.localIds
    val busy = p.id in vm.busy
    // lettura del testo per la ricerca (parte da sola a fine download)
    val ix = Indexer.states[p.id]
    val readNote = if (p.id in Indexer.running) {
        ix?.takeIf { it.total > 0 }?.let { " · lettura testo ${it.pages.size}/${it.total}" } ?: " · lettura testo…"
    } else ""
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
                        local -> " · scaricato$readNote"
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
