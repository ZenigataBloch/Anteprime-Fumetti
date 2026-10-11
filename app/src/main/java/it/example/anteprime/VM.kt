package it.example.anteprime

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VM(app: Application) : AndroidViewModel(app) {
    val repo = Repo(app)
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var themeMode by mutableStateOf(runCatching { ThemeMode.valueOf(prefs.getString("theme", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM))
    var dynamicColor by mutableStateOf(prefs.getBoolean("dynamic", true))
    fun setTheme(m: ThemeMode) { themeMode = m; prefs.edit().putString("theme", m.name).apply() }
    fun setDynamic(b: Boolean) { dynamicColor = b; prefs.edit().putBoolean("dynamic", b).apply() }
    var items by mutableStateOf(repo.localIndex())
    var localIds by mutableStateOf(repo.localIds())
    var busy by mutableStateOf(setOf<String>())
    var loading by mutableStateOf(false)
    var errors by mutableStateOf(listOf<String>())

    init { scan() }

    fun scan() {
        if (loading) return
        viewModelScope.launch {
            loading = true
            val errs = mutableListOf<String>()
            val all = mutableListOf<Preview>()
            val results = withContext(Dispatchers.IO) {
                listOf("Panini" to Scraper::panini, "Star Comics" to Scraper::star, "Manicomix" to Scraper::manicomix)
                    .map { (n, f) -> async { n to runCatching { f() } } }.awaitAll()
            }
            results.forEach { (n, r) ->
                r.onSuccess { if (it.isEmpty()) errs += "$n: nessun elemento trovato (struttura del sito cambiata?)"; all += it }.onFailure { errs += "$n: ${it.message ?: it.javaClass.simpleName}" }
            }
            val local = repo.localIndex()
            items = all + local.filter { l -> all.none { it.id == l.id } }
            localIds = repo.localIds()
            errors = errs
            loading = false
        }
    }

    fun fetch(p: Preview, onReady: () -> Unit) {
        if (p.id in busy) return
        viewModelScope.launch {
            busy += p.id
            runCatching { withContext(Dispatchers.IO) { repo.download(p) } }
                .onSuccess { localIds = repo.localIds(); onReady() }
                .onFailure { errors = errors + "Download ${p.title}: ${it.message}" }
            busy -= p.id
        }
    }

    fun remove(p: Preview) { repo.delete(p); localIds = repo.localIds() }
}
