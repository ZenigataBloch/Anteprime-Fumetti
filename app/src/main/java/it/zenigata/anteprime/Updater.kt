package it.zenigata.anteprime

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import okhttp3.Request
import org.json.JSONObject
import java.io.File

data class Update(val version: String, val url: String, val notes: String)

/** Controlla l'ultima Release GitHub e installa l'APK allegato. */
object Updater {
    const val REPO = "ZenigataBloch/Anteprime-Fumetti"

    fun latest(): Update? {
        val req = Request.Builder().url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json").header("User-Agent", "AnteprimeApp").build()
        Scraper.client.newCall(req).execute().use { r ->
            if (r.code == 404) return null // nessuna release (o repository privato)
            if (!r.isSuccessful) error("GitHub HTTP ${r.code}")
            val j = JSONObject(r.body!!.string())
            val tag = j.getString("tag_name").removePrefix("v")
            val assets = j.getJSONArray("assets")
            var apk: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) { apk = a.getString("browser_download_url"); break }
            }
            if (apk == null || !newer(tag, BuildConfig.VERSION_NAME)) return null
            return Update(tag, apk, j.optString("body"))
        }
    }

    fun newer(a: String, b: String): Boolean {
        fun parts(v: String) = v.split('.').map { it.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0 }
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrElse(i) { 0 }) - (y.getOrElse(i) { 0 })
            if (d != 0) return d > 0
        }
        return false
    }

    fun download(ctx: Context, url: String): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val out = File(dir, "update.apk")
        Scraper.client.newCall(Request.Builder().url(url).header("User-Agent", "AnteprimeApp").build()).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            r.body!!.byteStream().use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        }
        return out
    }

    /** false = manca il permesso "installa app sconosciute": si apre la schermata dove concederlo. */
    fun install(ctx: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }
}
