package it.example.anteprime

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
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object Notifier {
    private const val CHANNEL = "anteprime"

    /** Controllo periodico (ogni 6 ore, solo con rete). Sopravvive al riavvio del telefono. */
    fun schedule(ctx: Context, on: Boolean) {
        val wm = WorkManager.getInstance(ctx)
        if (!on) { wm.cancelUniqueWork("scan"); return }
        val req = PeriodicWorkRequestBuilder<ScanWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork("scan", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun notifyNew(ctx: Context, fresh: List<Preview>) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Nuove anteprime", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val style = NotificationCompat.InboxStyle()
        fresh.take(5).forEach { style.addLine("${it.source.label}: ${it.title}") }
        if (fresh.size > 5) style.addLine("…e altre ${fresh.size - 5}")
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
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
        val all = mutableListOf<Preview>()
        var ok = 0
        for (f in listOf(Scraper::panini, Scraper::star, Scraper::manicomix)) {
            runCatching { f() }.onSuccess { all += it; ok++ }
        }
        if (ok == 0) return@withContext Result.retry()
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
