package it.zenigata.anteprime

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: VM, onBack: () -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Impostazioni") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState())) {
            Text("Tema", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            ThemeMode.values().forEach { m ->
                ListItem(
                    headlineContent = { Text(m.label) },
                    leadingContent = { RadioButton(selected = vm.themeMode == m, onClick = null) },
                    modifier = Modifier.clickable { vm.setTheme(m) }
                )
            }
            if (Build.VERSION.SDK_INT >= 31) {
                ListItem(
                    headlineContent = { Text("Colori dinamici") },
                    trailingContent = { Switch(checked = vm.dynamicColor, onCheckedChange = null) },
                    modifier = Modifier.clickable { vm.setDynamic(!vm.dynamicColor) }
                )
            }
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Notifiche nuove anteprime") },
                trailingContent = { Switch(checked = vm.notifyEnabled, onCheckedChange = null) },
                modifier = Modifier.clickable { vm.setNotify(!vm.notifyEnabled) }
            )
            ListItem(
                headlineContent = { Text("Anteprime nella ricerca") },
                supportingContent = { Text("Per fonte, le più recenti") },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { vm.changeSearchCount(vm.searchCount - 1) }) { Icon(Icons.Default.Remove, "Meno") }
                        Text("${vm.searchCount}")
                        IconButton(onClick = { vm.changeSearchCount(vm.searchCount + 1) }) { Icon(Icons.Default.Add, "Più") }
                    }
                }
            )
            ListItem(
                headlineContent = { Text("Rivedi il tutorial") },
                modifier = Modifier.clickable { vm.showTutorial = true }
            )
            ListItem(
                headlineContent = { Text("Controlla aggiornamenti") },
                supportingContent = { Text("Versione ${BuildConfig.VERSION_NAME}") },
                modifier = Modifier.clickable { vm.checkUpdate(true) }
            )
        }
    }
}
