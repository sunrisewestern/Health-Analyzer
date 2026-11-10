package com.healthanalyzer.app.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** A published GitHub release that carries an APK. */
data class ReleaseInfo(
    val version: String,
    val tag: String,
    val notes: String,
    val htmlUrl: String,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    val sha256Url: String?,
)

/** Checks GitHub releases for a newer build and installs it. */
object UpdateManager {
    const val REPO = "sunrisewestern/Health-Analyzer"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases"

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** The newest non-draft, non-prerelease release with an .apk asset. */
    suspend fun latestRelease(): ReleaseInfo = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "HealthAnalyzer-Android")
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.code == 403 || resp.code == 429) throw IOException("GitHub rate limit reached – try again later.")
            if (!resp.isSuccessful) throw IOException("GitHub returned HTTP ${resp.code}")
            parseRelease(body) ?: throw IOException("The latest release has no APK attached.")
        }
    }

    /** Downloads the APK into the cache (verifying its .sha256 when published) and returns the file. */
    suspend fun download(context: Context, info: ReleaseInfo, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { if (it.name != info.apkName) it.delete() }
            val target = File(dir, info.apkName)
            val expected = info.sha256Url?.let { runCatching { fetchSha256(it) }.getOrNull() }
            if (target.exists() && expected != null && sha256(target) == expected) {
                onProgress(1f)
                return@withContext target
            }
            val partial = File(dir, info.apkName + ".part")
            val req = Request.Builder().url(info.apkUrl).header("User-Agent", "HealthAnalyzer-Android").build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Download failed: HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("Download failed: empty response")
                val total = body.contentLength().takeIf { it > 0 } ?: info.apkSize
                body.byteStream().use { input ->
                    partial.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastReported = -1
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastReported) { lastReported = pct; onProgress(done.toFloat() / total) }
                            }
                        }
                    }
                }
            }
            if (expected != null && sha256(partial) != expected) {
                partial.delete()
                throw IOException("The downloaded file is corrupted (checksum mismatch). Try again.")
            }
            target.delete()
            if (!partial.renameTo(target)) throw IOException("Couldn't save the update.")
            target
        }

    /** True when the user still has to allow this app to install packages. */
    fun needsInstallPermission(context: Context): Boolean =
        !context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Hands the APK to the system installer. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun openReleasePage(context: Context, info: ReleaseInfo?) {
        val url = info?.htmlUrl ?: RELEASES_PAGE
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
        }
    }

    private fun fetchSha256(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", "HealthAnalyzer-Android").build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            parseSha256(resp.body?.string().orEmpty()) ?: throw IOException("Bad checksum file")
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Parses a GitHub `releases/latest` response; null when there's no APK asset. */
internal fun parseRelease(json: String): ReleaseInfo? {
    val o = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return null
    fun str(name: String) = o.get(name)?.takeIf { it.isJsonPrimitive }?.asString
    if (o.get("draft")?.asBoolean == true || o.get("prerelease")?.asBoolean == true) return null
    val tag = str("tag_name") ?: return null
    val assets = o.get("assets")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
        .orEmpty()
    val apk = assets.firstOrNull { it.get("name")?.asString?.endsWith(".apk", ignoreCase = true) == true } ?: return null
    val apkName = apk.get("name").asString
    val sha = assets.firstOrNull { it.get("name")?.asString == "$apkName.sha256" }
    return ReleaseInfo(
        version = tag.removePrefix("v").removePrefix("V"),
        tag = tag,
        notes = str("body").orEmpty().trim(),
        htmlUrl = str("html_url") ?: UpdateManager.RELEASES_PAGE,
        apkName = apkName,
        apkUrl = apk.get("browser_download_url")?.asString ?: return null,
        apkSize = apk.get("size")?.asLong ?: 0L,
        sha256Url = sha?.get("browser_download_url")?.asString,
    )
}

/** First hex token of a `sha256sum` line ("<hash>  <file>"). */
internal fun parseSha256(text: String): String? =
    text.trim().split(Regex("\\s+")).firstOrNull()?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }

/**
 * Compares dotted versions numerically ("1.10.0" > "1.9.2"), ignoring a leading "v" and
 * any suffix such as "-dev" or "-beta". Missing parts count as 0.
 */
internal fun compareVersions(a: String, b: String): Int {
    fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V")
        .substringBefore('-').substringBefore('+')
        .split('.').map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
    val pa = parts(a)
    val pb = parts(b)
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val c = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
        if (c != 0) return c
    }
    return 0
}
