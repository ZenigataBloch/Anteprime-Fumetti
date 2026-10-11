package it.example.anteprime

import android.app.Application
import android.content.Context
import android.widget.Toast
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

class VM(private val app: Application) : AndroidViewModel(app) {
    val repo = Repo(app)
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var themeMode by mutableStateOf(runCatching { ThemeMode.valueOf(prefs.getString("theme", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM))
    var dynamicColor by mutableStateOf(prefs.getBoolean("dynamic", true))
    var notifyEnabled by mutableStateOf(prefs.getBoolean("notify", true))
    fun setTheme(m: ThemeMode) { themeMode = m; prefs.edit().putString("theme", m.name).apply() }
    fun setDynamic(b: Boolean) { dynamicColor = b; prefs.edit().putBoolean("dynamic", b).apply() }
    fun setNotify(b: Boolean) { notifyEnabled = b; prefs.edit().putBoolean("notify", b).apply(); Notifier.schedule(app, b) }

    var items by mutableStateOf(repo.localIndex())
    var localIds by mutableStateOf(repo.localIds())
    var busy by mutableStateOf(setOf<String>())
    var loading by mutableStateOf(false)
    var errors by mutableStateOf(listOf<String>())

    var update by mutableStateOf<Update?>(null)
    var updating by mutableStateOf(false)

    init {
        Notifier.schedule(app, notifyEnabled)
        scan()
        checkUpdate(false)
    }

    private fun toast(msg: String) = Toast.makeText(app, msg, Toast.LENGTH_LONG).show()

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
                r.onSuccess { if (it.isEmpty()) errs += "$n: nessun elemento trovato (struttura del sito cambiata?)"; all += it }
                    .onFailure { errs += "$n: ${it.message ?: it.javaClass.simpleName}" }
            }
            val local = repo.localIndex()
            items = all + local.filter { l -> all.none { it.id == l.id } }
            localIds = repo.localIds()
            errors = errs
            repo.markSeen(all.map { it.id }) // quello che vedi in lista non viene notificato di nuovo
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

    fun checkUpdate(manual: Boolean) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { Updater.latest() } }
                .onSuccess { u ->
                    if (u != null) update = u
                    else if (manual) toast("Sei già alla versione ${BuildConfig.VERSION_NAME}")
                }
                .onFailure { if (manual) toast("Controllo non riuscito: ${it.message}") }
        }
    }

    fun installUpdate() {
        val u = update ?: return
        if (updating) return
        viewModelScope.launch {
            updating = true
            runCatching { withContext(Dispatchers.IO) { Updater.download(app, u.url) } }
                .onSuccess { f ->
                    if (Updater.install(app, f)) update = null
                    else toast("Consenti l'installazione da questa app, poi torna qui e riprova")
                }
                .onFailure { toast("Download non riuscito: ${it.message}") }
            updating = false
        }
    }
}
