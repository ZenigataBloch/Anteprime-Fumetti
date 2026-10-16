package it.zenigata.anteprime

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/** Barra di ricerca della pagina principale: stesso motore delle opere seguite (vm.testFollows); i risultati escono in FollowResultsDialog. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainSearchBar(vm: VM) {
    val focus = LocalFocusManager.current
    fun go() {
        val names = KeywordSearch.keywords(vm.searchText)
        if (names.isEmpty() || vm.testRunning) return
        focus.clearFocus()
        vm.testFollows(names)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        OutlinedTextField(
            value = vm.searchText, onValueChange = { vm.searchText = it }, singleLine = true,
            label = { Text("Cerca un'opera") }, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { go() }),
            trailingIcon = {
                if (vm.testRunning) IconButton(onClick = { vm.stopTest() }) { Icon(Icons.Default.Close, "Ferma la ricerca") }
                else IconButton(enabled = vm.searchText.isNotBlank(), onClick = { go() }) { Icon(Icons.Default.Search, "Cerca") }
            }
        )
        // filtro editori: nessuno selezionato = si cercano tutti
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Source.values().forEach { src ->
                FilterChip(
                    selected = src in vm.searchSources,
                    onClick = { vm.searchSources = if (src in vm.searchSources) vm.searchSources - src else vm.searchSources + src },
                    label = { Text(src.label) }
                )
            }
        }
        if (vm.testRunning) {
            Text("${vm.testProgress.first + 1}/${vm.testProgress.second} · ${vm.testCurrent}", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else if (vm.testDone && vm.testResults.isEmpty()) {
            Text("Nessuna corrispondenza.", style = MaterialTheme.typography.bodySmall)
            vm.suggestion?.let { s -> TextButton(onClick = { vm.searchText = s; go() }) { Text("Stavi forse cercando: $s?") } }
        } else if (vm.testResults.isNotEmpty()) {
            TextButton(onClick = { vm.showFollowResults = true }) { Text("Risultati (${vm.testResults.size})") }
        }
    }
}
