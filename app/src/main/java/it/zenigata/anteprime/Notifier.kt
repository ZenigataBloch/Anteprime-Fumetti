package it.zenigata.anteprime

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object Notifier {
    private const val CHANNEL = "anteprime"

    /** Icona piccola delle notifiche: usala anche per le notifiche delle opere seguite. */
    val smallIcon: Int get() = R.drawable.ic_stat

    /** Controllo periodico (ogni 6 ore, solo con rete). Sopravvive al riavvio del telefono. */
    fun schedule(ctx: Context, on: Boolean) {
        val wm = WorkManager.getInstance(ctx)
        if (!on) { wm.cancelUniqueWork("scan"); return }
        val req = PeriodicWorkRequestBuilder<ScanWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork("scan", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** True se si può mostrare una notifica (permesso concesso su Android 13+). */
    fun canPost(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Crea (se serve) il canale "Nuove anteprime" e ne restituisce l'id. */
    fun ensureChannel(ctx: Context): String {
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Nuove anteprime", NotificationManager.IMPORTANCE_DEFAULT))
        }
        return CHANNEL
    }

    fun notifyNew(ctx: Context, fresh: List<Preview>) {
        if (fresh.isEmpty() || !canPost(ctx)) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val channel = ensureChannel(ctx)
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val style = NotificationCompat.InboxStyle()
        fresh.take(5).forEach { style.addLine("${it.source.label}: ${it.title}") }
        if (fresh.size > 5) style.addLine("…e altre ${fresh.size - 5}")
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(smallIcon)
            .setContentTitle(if (fresh.size == 1) "Nuova anteprima" else "${fresh.size} nuove anteprime")
            .setContentText(fresh.first().let { "${it.source.label}: ${it.title}" } + if (fresh.size > 1) " e altre" else "")
            .setStyle(style)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(1, n)
    }
}

class ScanWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // i tre siti si interrogano in parallelo: il controllo dura quanto il più lento, non la somma
        val results = coroutineScope {
            listOf(Scraper::panini, Scraper::star, Scraper::manicomix)
                .map { f -> async { runCatching { f() } } }
                .awaitAll()
        }
        if (results.none { it.isSuccess }) return@withContext Result.retry()
        val all = results.flatMap { it.getOrNull() ?: emptyList() }
        val repo = Repo(applicationContext)
        val seen = repo.seen()
        if (seen.isEmpty()) {
            repo.markSeen(all.map { it.id }) // primo avvio: nessuna notifica, solo base di partenza
        } else {
            val fresh = all.filter { it.id !in seen }
            if (fresh.isNotEmpty()) {
                Notifier.notifyNew(applicationContext, fresh)
                repo.markSeen(fresh.map { it.id })
            }
        }
        Result.success()
    }
}
