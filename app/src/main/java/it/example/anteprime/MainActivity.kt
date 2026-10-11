package it.example.anteprime

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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

@Composable
fun App(vm: VM) {
    var open by remember { mutableStateOf<Preview?>(null) }
    BackHandler(open != null) { open = null }
    val p = open
    when {
        p == null -> ListScreen(vm) { open = it }
        p.isPdf -> PdfScreen(vm.repo.file(p), p.title) { open = null }
        else -> WebScreen(p.url, p.title) { open = null }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(vm: VM, onOpen: (Preview) -> Unit) {
    var source by rememberSaveable { mutableStateOf<String?>(null) }
    var onlyLocal by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val shown = vm.items.filter {
        (source == null || it.source.name == source) && (!onlyLocal || it.id in vm.localIds)
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
                    IconButton(onClick = { vm.scan() }, enabled = !vm.loading) {
                        Icon(Icons.Default.Refresh, contentDescription = "Aggiorna")
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.Palette, contentDescription = "Tema") }
                        ThemeMenu(vm, menu) { menu = false }
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
                    FilterChip(selected = source == s.name, onClick = { source = s.name }, label = { Text(s.label) })
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
private fun ThemeMenu(vm: VM, expanded: Boolean, onDismiss: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        ThemeMode.values().forEach { m ->
            DropdownMenuItem(
                text = { Text(m.label) },
                leadingIcon = { RadioButton(selected = vm.themeMode == m, onClick = null) },
                onClick = { vm.setTheme(m) }
            )
        }
        if (Build.VERSION.SDK_INT >= 31) {
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Colori dinamici") },
                leadingIcon = { Checkbox(checked = vm.dynamicColor, onCheckedChange = null) },
                onClick = { vm.setDynamic(!vm.dynamicColor) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewCard(p: Preview, vm: VM, onOpen: (Preview) -> Unit) {
    val local = p.id in vm.localIds
    val busy = p.id in vm.busy
    Card(
        onClick = { if (!p.isPdf || local) onOpen(p) else if (!busy) vm.fetch(p) { onOpen(p) } },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            SourceBadge(p.source)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    p.source.label + when { !p.isPdf -> " · solo online"; local -> " · scaricato"; else -> "" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            when {
                !p.isPdf -> Icon(Icons.AutoMirrored.Filled.OpenInNew, "Apri", Modifier.padding(12.dp))
                busy -> CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                local -> {
                    IconButton(onClick = { vm.remove(p) }) { Icon(Icons.Default.Delete, "Elimina") }
                    IconButton(onClick = { onOpen(p) }) { Icon(Icons.AutoMirrored.Filled.MenuBook, "Leggi") }
                }
                else -> IconButton(onClick = { vm.fetch(p) { onOpen(p) } }) { Icon(Icons.Default.Download, "Scarica e leggi") }
            }
        }
    }
}

@Composable
private fun SourceBadge(s: Source) {
    val c = when (s) {
        Source.PANINI -> Color(0xFFD32F2F)
        Source.STAR -> Color(0xFF0195DB)
        Source.MANICOMIX -> Color(0xFFEF6C00)
    }
    Box(Modifier.size(40.dp).background(c, CircleShape), contentAlignment = Alignment.Center) {
        Text(s.label.first().toString(), color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebScreen(url: String, title: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } },
            actions = {
                IconButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }) {
                    Icon(Icons.Default.OpenInBrowser, "Apri nel browser")
                }
            }
        )
    }) { pad ->
        AndroidView(
            factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    setBackgroundColor(android.graphics.Color.BLACK)
                    webViewClient = WebViewClient()
                    // Come sul sito: iframe dentro una pagina con origine manicomix.it
                    val safe = url.replace("&", "&amp;").replace("\"", "&quot;")
                    val html = "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>" +
                        "<style>html,body{margin:0;height:100%;background:#000}iframe{border:0;width:100%;height:100%}</style></head>" +
                        "<body><iframe src=\"$safe\" allowfullscreen allow=\"fullscreen\"></iframe></body></html>"
                    loadDataWithBaseURL("https://www.manicomix.it/", html, "text/html", "utf-8", null)
                }
            },
            modifier = Modifier.padding(pad).fillMaxSize()
        )
    }
}
