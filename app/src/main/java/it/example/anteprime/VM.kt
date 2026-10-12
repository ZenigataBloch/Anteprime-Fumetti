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
import kotlinx.coroutines.joinAll
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

    // Ultima lista nota di ogni fonte: all'avvio arriva dal disco (la lista appare subito),
    // poi viene sostituita da quella scaricata. Si tocca solo dal thread principale.
    private val bySource = linkedMapOf<Source, List<Preview>>().apply {
        Source.values().forEach { put(it, PreviewCache.load(app.filesDir, it)) }
    }

    private fun merged(): List<Preview> {
        val all = bySource.values.flatten()
        return all + repo.localIndex().filter { l -> all.none { it.id == l.id } }
    }

    private val fonti = listOf<Pair<Source, () -> List<Preview>>>(
        Source.PANINI to Scraper::panini,
        Source.STAR to Scraper::star,
        Source.MANICOMIX to Scraper::manicomix
    )

    var items by mutableStateOf(merged())
    var localIds by mutableStateOf(repo.localIds())
    var busy by mutableStateOf(setOf<String>())
    var loading by mutableStateOf(false)
    var errors by mutableStateOf(listOf<String>())

    var update by mutableStateOf<Update?>(null)
    var updating by mutableStateOf(false)

    // note salvate (dal lettore PDF o scritte a mano), la più recente per prima
    private val notesRepo = NotesRepo(app)
    var notes by mutableStateOf(notesRepo.load())

    private fun saveNotes(list: List<Note>) { notes = list; notesRepo.save(list) }

    // catalogo dei tag: li crei e li cancelli tu, anche senza note. All'avvio si unisce ai tag già usati dalle note.
    private val tagPrefs = app.getSharedPreferences("tags", Context.MODE_PRIVATE)
    var tagList by mutableStateOf(Tags.sort(tagPrefs.getStringSet("all", emptySet())!! + notes.flatMap { it.tags }))
        private set
    val allTags: List<String> get() = tagList

    private fun saveTags(list: List<String>) {
        tagList = Tags.sort(list)
        tagPrefs.edit().putStringSet("all", tagList.toSet()).apply()
    }

    /** Crea un tag (se esiste già, ignorando maiuscole/minuscole, non fa nulla) e restituisce il nome canonico. */
    fun createTag(raw: String): String? {
        val t = Tags.normalize(raw)
        if (t.isBlank()) return null
        tagList.firstOrNull { it.equals(t, ignoreCase = true) }?.let { return it }
        saveTags(tagList + t)
        return t
    }

    /** Elimina il tag dal catalogo e lo toglie da tutte le note. */
    fun deleteTag(t: String) {
        saveTags(tagList - t)
        saveNotes(notes.map { if (t in it.tags) it.copy(tags = it.tags - t) else it })
    }

    /** Case editrici originali già usate nelle note: servono come scorciatoie quando ne scrivi una nuova. */
    val allOriginals: List<String>
        get() = notes.map { it.original.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }

    fun addNote(text: String, p: Preview?, page: Int, tags: List<String> = emptyList(), original: String = "", release: String = "") {
        val id = maxOf(System.currentTimeMillis(), (notes.maxOfOrNull { it.id } ?: 0L) + 1)
        saveNotes(listOf(Note(id, text, p, page, tags, original, release)) + notes)
    }

    fun editNote(n: Note, text: String, tags: List<String>, original: String, release: String) =
        saveNotes(notes.map { if (it.id == n.id) it.copy(text = text, tags = tags, original = original, release = release) else it })

    fun deleteNote(n: Note) = saveNotes(notes.filter { it.id != n.id })

    init {
        Scraper.cacheDir = app.filesDir // cache dell'archivio nerdpool
        Notifier.schedule(app, notifyEnabled)
        scan()
        checkUpdate(false)
    }

    private fun toast(msg: String) = Toast.makeText(app, msg, Toast.LENGTH_LONG).show()

    private fun publish() {
        items = merged()
        localIds = repo.localIds()
    }

    fun scan() {
        if (loading) return
        viewModelScope.launch {
            loading = true
            errors = emptyList()
            val errs = mutableListOf<String>()
            val fresh = mutableListOf<Preview>()
            // le tre fonti partono insieme e ognuna compare in lista appena è pronta
            fonti.map { (s, f) ->
                launch {
                    val r = withContext(Dispatchers.IO) {
                        runCatching { f() }.also { res ->
                            res.getOrNull()?.takeIf { it.isNotEmpty() }?.let { PreviewCache.save(app.filesDir, s, it) }
                        }
                    }
                    // da qui si è di nuovo sul thread principale
                    r.onSuccess { list ->
                        if (list.isEmpty()) {
                            errs += "${s.label}: nessun elemento trovato (struttura del sito cambiata?)"
                        } else {
                            bySource[s] = list
                            fresh += list
                        }
                    }.onFailure {
                        errs += "${s.label}: ${it.message ?: it.javaClass.simpleName}" // in lista resta la versione salvata
                    }
                    errors = errs.toList()
                    publish()
                }
            }.joinAll()
            repo.markSeen(fresh.map { it.id }) // quello che vedi in lista non viene notificato di nuovo
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
