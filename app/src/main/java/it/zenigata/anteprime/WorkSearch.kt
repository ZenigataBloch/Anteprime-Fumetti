package it.zenigata.anteprime

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Lente della lista: cerca un'opera nelle ultime anteprime di ogni fonte e, se vuoi, la aggiunge alle opere seguite. */
@Composable
fun WorkSearchDialog(vm: VM, onDismiss: () -> Unit) {
    val names = KeywordSearch.keywords(vm.searchText)
    val already = names.isNotEmpty() && names.all { k -> vm.follows.any { KeywordSearch.norm(it) == KeywordSearch.norm(k) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cerca un'opera") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Chiudi") } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Cerco nelle ultime ${vm.searchCount} anteprime di ogni fonte (si regola dalle impostazioni).",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = vm.searchText, onValueChange = { vm.searchText = it }, singleLine = true,
                        label = { Text("Nome o serie") }, modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        enabled = vm.testRunning || names.isNotEmpty(),
                        onClick = { if (vm.testRunning) vm.stopTest() else vm.testFollows(names) }
                    ) { Icon(Icons.Default.Search, contentDescription = if (vm.testRunning) "Ferma" else "Cerca") }
                }
                TextButton(enabled = names.isNotEmpty() && !already, onClick = { vm.addFollow(vm.searchText) }) {
                    Text(if (already) "Già nelle opere seguite" else "Aggiungi alle opere seguite")
                }
                if (vm.testRunning) {
                    Text("${vm.testProgress.first + 1}/${vm.testProgress.second} \u00b7 ${vm.testCurrent}", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else if (vm.testDone && vm.testResults.isEmpty()) {
                    Text("Nessuna corrispondenza.", style = MaterialTheme.typography.bodySmall)
                }
                vm.testResults.forEach { (p, hits) ->
                    Text(p.title, style = MaterialTheme.typography.titleSmall)
                    hits.distinctBy { it.keyword to it.page }.sortedBy { it.page }.take(8).forEach { h ->
                        Text(
                            "p. ${h.page + 1} \u00b7 ${h.keyword}: ${h.text.take(60)}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().clickable {
                                vm.returnToSearch = true; vm.pendingOpen = p.id to h.page; onDismiss()
                            }
                        )
                    }
                }
            }
        }
    )
}
