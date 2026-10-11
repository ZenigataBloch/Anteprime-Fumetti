package it.example.anteprime

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { App() } } }
    }
}

@Composable
fun App(vm: VM = viewModel()) {
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
    var filter by remember { mutableStateOf("ALL") } // ALL | LOCAL | nome Source
    val shown = vm.items.filter {
        when (filter) {
            "ALL" -> true
            "LOCAL" -> it.id in vm.localIds
            else -> it.source.name == filter
        }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Anteprime Fumetti") }, actions = {
            TextButton(onClick = { vm.scan() }, enabled = !vm.loading) { Text("Aggiorna") }
        })
    }) { pad ->
        Column(Modifier.padding(pad)) {
            if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ALL" to "Tutti", "LOCAL" to "Scaricati") .plus(Source.values().map { it.name to it.label }).forEach { (k, label) ->
                    FilterChip(selected = filter == k, onClick = { filter = k }, label = { Text(label) })
                }
            }
            vm.errors.forEach { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) }
            LazyColumn {
                items(shown, key = { it.id }) { p -> PreviewRow(p, vm, onOpen) }
            }
        }
    }
}

@Composable
private fun PreviewRow(p: Preview, vm: VM, onOpen: (Preview) -> Unit) {
    val local = p.id in vm.localIds
    ListItem(
        headlineContent = { Text(p.title) },
        supportingContent = { Text(p.source.label + when { !p.isPdf -> " · solo online (Issuu)"; local -> " · salvato sul dispositivo"; else -> "" }) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    !p.isPdf -> TextButton({ onOpen(p) }) { Text("Apri") }
                    p.id in vm.busy -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    local -> {
                        TextButton({ vm.remove(p) }) { Text("Elimina") }
                        TextButton({ onOpen(p) }) { Text("Leggi") }
                    }
                    else -> TextButton({ vm.fetch(p) { onOpen(p) } }) { Text("Scarica e leggi") }
                }
            }
        }
    )
    HorizontalDivider()
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebScreen(url: String, title: String, onBack: () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text(title, maxLines = 1) }, navigationIcon = { TextButton(onClick = onBack) { Text("‹ Indietro") } })
    }) { pad ->
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = WebViewClient()
                    loadUrl(url)
                }
            },
            modifier = Modifier.padding(pad).fillMaxSize()
        )
    }
}
