package it.example.anteprime

import android.annotation.SuppressLint
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Opere seguite: scrivi un nome, e quando esce un'anteprima nuova l'app la scarica in background,
 * ne legge il testo (OCR) e, se trova il nome, manda una notifica che apre il PDF alla pagina trovata.
 * Usa Indexer (una sola lettura OCR alla volta) e KeywordSearch (gli stessi risultati della lente).
 */
object Follows {
    const val EXTRA_ID = "follow_open_id"
    const val EXTRA_PAGE = "follow_open_page"   // 0-based, come Hit.page
    private const val CHANNEL = "follows"
    private const val WORK = "follow-check"
    private const val MAX_TRIES = 3

    private fun prefs(c: Context) = c.getSharedPreferences("follows", Context.MODE_PRIVATE)

    fun list(c: Context): List<String> =
        prefs(c).getString("list", "")!!.split('\n').filter { it.isNotBlank() }

    private fun save(c: Context, l: List<String>) {
        prefs(c).edit().putString("list", l.joinToString("\n")).apply()
        schedule(c)
    }

    /** "X-Men, Absolute Batman" aggiunge due voci; i doppioni (senza badare a maiuscole/accenti) vengono ignorati. */
    fun add(c: Context, raw: String) {
        val cur = list(c).toMutableList()
        for (k in KeywordSearch.keywords(raw)) {
            if (cur.none { KeywordSearch.norm(it) == KeywordSearch.norm(k) }) cur.add(k)
        }
        save(c, cur)
    }

    fun remove(c: Context, name: String) = save(c, list(c) - name)

    /** Controllo ogni 12 ore (solo Wi-Fi e batteria non scarica): scarica soltanto le anteprime mai viste prima. */
    fun schedule(c: Context) {
        val wm = WorkManager.getInstance(c)
        if (list(c).isEmpty()) { wm.cancelUniqueWork(WORK); return }
        val req = PeriodicWorkRequestBuilder<FollowWorker>(12, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            ).build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    // anteprime già controllate (o presenti alla prima esecuzione: quelle si cercano con la lente)
    internal fun hasBaseline(c: Context) = prefs(c).contains("checked")
    internal fun checked(c: Context): Set<String> = prefs(c).getStringSet("checked", emptySet())!!
    internal fun markChecked(c: Context, ids: Collection<String>) {
        prefs(c).edit().putStringSet("checked", checked(c) + ids).apply()
    }

    /** Un errore su un'anteprima: dopo MAX_TRIES tentativi smette di riprovarci. */
    internal fun fail(c: Context, id: String) {
        val n = prefs(c).getInt("try_$id", 0) + 1
        prefs(c).edit().putInt("try_$id", n).apply()
        if (n >= MAX_TRIES) markChecked(c, listOf(id))
    }

    @SuppressLint("MissingPermission")
    internal fun notify(c: Context, p: Preview, hits: List<Hit>) {
        val nm = NotificationManagerCompat.from(c)
        if (!nm.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 26) {
            c.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Opere seguite", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val first = hits.minByOrNull { it.page } ?: return
        val open = (c.packageManager.getLaunchIntentForPackage(c.packageName) ?: return).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_ID, p.id)
            putExtra(EXTRA_PAGE, first.page)
        }
        val pi = PendingIntent.getActivity(
            c, p.id.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val lines = hits.distinctBy { it.keyword to it.page }.sortedBy { it.page }.take(6)
            .map { "p. ${it.page + 1} · ${it.keyword}: ${it.text.take(60)}" }
        val names = hits.map { it.keyword }.distinct().joinToString(", ")
        val style = NotificationCompat.InboxStyle().setBigContentTitle("Trovato: $names")
        lines.forEach { style.addLine(it) }
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(c.applicationInfo.icon) // sostituiscilo con l'icona che usa già Notifier
            .setContentTitle("Trovato: $names")
            .setContentText("${p.title} · pagina ${first.page + 1}")
            .setStyle(style)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(p.id.hashCode(), n)
    }
}

class FollowWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        val follows = Follows.list(app)
        if (follows.isEmpty()) return Result.success()

        Scraper.cacheDir = app.filesDir
        val repo = Repo(app as Application)
        val sources = listOf<Pair<Source, () -> List<Preview>>>(
            Source.PANINI to Scraper::panini,
            Source.STAR to Scraper::star,
            Source.MANICOMIX to Scraper::manicomix
        )

        val found = ArrayList<Pair<Source, Preview>>()
        for ((s, f) in sources) {
            val list = withContext(Dispatchers.IO) { runCatching { f() }.getOrNull() } ?: continue
            list.forEach { found += s to it }
        }
        if (found.isEmpty()) return Result.retry()

        // prima esecuzione: quello che c'è già non si scarica (si cerca con la lente); da qui in poi solo le novità
        if (!Follows.hasBaseline(app)) {
            Follows.markChecked(app, found.map { it.second.id })
            return Result.success()
        }
        val seen = Follows.checked(app)
        val todo = found.filter { it.second.id !in seen }
        if (todo.isEmpty()) return Result.success()

        // mentre un PDF viene letto (OCR), il successivo è già in download
        val ready = Channel<Triple<Source, Preview, File>>(1)
        coroutineScope {
            launch(Dispatchers.IO) {
                try {
                    for ((s, p) in todo) {
                        try {
                            if (p.id !in repo.localIds()) repo.download(p) { _, _ -> }
                            ready.send(Triple(s, p, repo.file(p)))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Follows.fail(app, p.id)
                        }
                    }
                } finally {
                    ready.close()
                }
            }
            for ((s, p, file) in ready) {
                try {
                    val hits = Indexer.exclusive {
                        val done = PageText.load(file)
                        val pages = HashMap<Int, List<PageLine>>(done)
                        PageText.build(
                            file, done,
                            onTotal = { },
                            onPage = { i, lines -> synchronized(pages) { pages[i] = lines } }
                        )
                        KeywordSearch.search(pages, follows, s)
                    }
                    if (hits.isNotEmpty()) Follows.notify(app, p, hits)
                    Follows.markChecked(app, listOf(p.id))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Follows.fail(app, p.id)
                }
            }
        }
        return Result.success()
    }
}

/** Finestra "Opere seguite": scrivi un nome (o più, separati da virgola) e premi +. */
@Composable
fun FollowsDialog(vm: VM, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Opere seguite") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Chiudi") } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Quando esce una nuova anteprima la scarico in background e ti avviso se contiene questi nomi.",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = text, onValueChange = { text = it }, singleLine = true,
                        label = { Text("Nome o serie") }, modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        enabled = text.isNotBlank(),
                        onClick = { vm.addFollow(text); text = "" }
                    ) { Icon(Icons.Default.Add, contentDescription = "Aggiungi") }
                }
                vm.follows.forEach { n ->
                    Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(n, modifier = Modifier.weight(1f))
                        IconButton(onClick = { vm.removeFollow(n) }) {
                            Icon(Icons.Default.Close, contentDescription = "Rimuovi $n")
                        }
                    }
                }
                if (vm.follows.isNotEmpty()) {
                    TextButton(onClick = { if (vm.testRunning) vm.stopTest() else vm.testFollows() }) {
                        Text(if (vm.testRunning) "Ferma la prova" else "Prova su tutte le anteprime")
                    }
                }
                if (vm.testRunning) {
                    Text(
                        "${vm.testProgress.first + 1}/${vm.testProgress.second} · ${vm.testCurrent}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else if (vm.testDone && vm.testResults.isEmpty()) {
                    Text("Nessuna corrispondenza.", style = MaterialTheme.typography.bodySmall)
                }
                vm.testResults.forEach { (p, hits) ->
                    Text(p.title, style = MaterialTheme.typography.titleSmall)
                    hits.distinctBy { it.keyword to it.page }.sortedBy { it.page }.take(8).forEach { h ->
                        Text(
                            "p. ${h.page + 1} · ${h.keyword}: ${h.text.take(60)}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().clickable { vm.pendingOpen = p.id to h.page; onDismiss() }
                        )
                    }
                }
            }
        }
    )
}
