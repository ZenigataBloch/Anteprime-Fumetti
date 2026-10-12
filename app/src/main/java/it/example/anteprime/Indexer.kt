package it.example.anteprime

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Lettura OCR dei PDF per la ricerca, indipendente dal lettore: parte da sola a fine download (vedi VM.fetch)
 * e prosegue anche se il lettore è chiuso. Un solo job per PDF; lo stato è osservabile da Compose.
 *
 * Le letture avviate in automatico fanno la coda (una alla volta, per non appesantire il telefono);
 * quella richiesta dall'utente (lente nel lettore, priority = true) parte subito.
 * La cache su disco (PageText) si salva ogni 10 pagine: se il processo viene chiuso, si riparte da lì.
 * Tutto gira sul thread principale; il lavoro pesante è dentro PageText.build.
 */
object Indexer {
    /** [total] = pagine del PDF (0 finché non è noto), [pages] = pagine già lette. */
    class TextState(val total: Int, val pages: Map<Int, List<PageLine>>)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gate = Semaphore(1)
    private val jobs = HashMap<String, Job>()

    /** Esegue [block] nella stessa coda delle letture automatiche (una sola OCR alla volta in tutta l'app). Usato da FollowWorker. */
    suspend fun <T> exclusive(block: suspend () -> T): T = gate.withPermit { block() }

    /** id del PDF -> testo letto finora. */
    var states by mutableStateOf(mapOf<String, TextState>())
        private set

    /** id dei PDF con una lettura in corso o in coda. */
    var running by mutableStateOf(setOf<String>())
        private set

    private fun update(id: String, f: (TextState?) -> TextState) {
        states = states + (id to f(states[id]))
    }

    /** Carica dalla cache su disco il testo già letto, se non è già in memoria. */
    suspend fun ensureLoaded(file: File) {
        val id = file.nameWithoutExtension
        if (states[id] != null) return
        val loaded = withContext(Dispatchers.IO) { PageText.load(file) }
        if (states[id] == null) update(id) { TextState(0, loaded) }
    }

    /** Avvia la lettura delle pagine mancanti (non fa nulla se è già in corso). Da chiamare dal thread principale. */
    fun start(file: File, priority: Boolean = false, onError: (String) -> Unit = {}) {
        val id = file.nameWithoutExtension
        if (jobs[id]?.isActive == true) return
        running = running + id
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val me = coroutineContext[Job]
            try {
                val done = withContext(Dispatchers.IO) { PageText.load(file) }
                update(id) { TextState(it?.total ?: 0, done) }
                val work: suspend () -> Unit = {
                    PageText.build(
                        file, done,
                        onTotal = { t -> update(id) { s -> TextState(t, s?.pages ?: done) } },
                        onPage = { i, lines -> update(id) { s -> TextState(s?.total ?: 0, (s?.pages ?: done) + (i to lines)) } }
                    )
                }
                if (priority) work() else gate.withPermit { work() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e.message ?: e.javaClass.simpleName)
            } finally {
                if (jobs[id] === me) {
                    jobs.remove(id)
                    running = running - id
                }
            }
        }
        jobs[id] = job
        job.start()
    }

    /** Interrompe la lettura (il testo letto finora resta in cache). */
    fun stop(id: String) {
        jobs.remove(id)?.cancel()
        running = running - id
    }

    /** PDF cancellato: ferma la lettura e butta lo stato in memoria. */
    fun forget(id: String) {
        stop(id)
        states = states - id
    }
}
