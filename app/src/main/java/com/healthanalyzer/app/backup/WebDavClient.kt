package com.healthanalyzer.app.backup

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory

/** [legacy]: found directly in the configured URL (where backups went before the RunAnalyzer folder). */
data class RemoteFile(val name: String, val sizeBytes: Long?, val modifiedEpochMs: Long?, val legacy: Boolean = false)

class WebDavException(message: String) : IOException(message)

/**
 * Minimal WebDAV client for one backup folder (Nextcloud, ownCloud, Synology, Jianguoyun, …):
 * PROPFIND to list, PUT to upload, GET to download, MKCOL to create the folder. Pure JVM.
 *
 * Backups live in a [APP_FOLDER] folder inside the configured URL, created automatically, so the
 * URL can simply be the WebDAV root. A URL that already ends in that folder is used as it is.
 */
class WebDavClient(
    private val http: OkHttpClient,
    folderUrl: String,
    user: String,
    password: String,
) {
    private val root = withSlash(folderUrl)
    private val base = appFolderUrl(folderUrl)
    private val auth = if (user.isNotBlank() || password.isNotBlank()) Credentials.basic(user, password, Charsets.UTF_8) else null

    private fun req(url: String) = Request.Builder().url(url).apply { auth?.let { header("Authorization", it) } }

    /** Checks the login and creates the configured folder and the [APP_FOLDER] in it if they don't exist yet. */
    fun ensureFolder() {
        if (root != base) ensure(root)
        ensure(base)
    }

    private fun ensure(url: String) {
        val code = propfind(url, depth = 0).use { it.code }
        when (code) {
            207, 200 -> return
            404 -> http.newCall(req(url).method("MKCOL", null).build()).execute().use { r ->
                if (!r.isSuccessful) throw WebDavException("Couldn't create the folder $url (HTTP ${r.code}). Create it on the server, then try again.")
            }
            401, 403 -> throw WebDavException("The server rejected the username or password (HTTP $code). Some services need an app password.")
            else -> throw WebDavException("Unexpected answer from the server: HTTP $code")
        }
    }

    fun upload(name: String, file: File) {
        val body = file.asRequestBody("application/octet-stream".toMediaType())
        http.newCall(req(base + name).put(body).build()).execute().use { r ->
            if (!r.isSuccessful) throw WebDavException("Upload failed: HTTP ${r.code} ${r.message}")
        }
    }

    fun download(name: String, dest: File, legacy: Boolean = false) {
        http.newCall(req((if (legacy) root else base) + name).get().build()).execute().use { r ->
            if (!r.isSuccessful) throw WebDavException("Download failed: HTTP ${r.code} ${r.message}")
            dest.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
        }
    }

    fun delete(name: String) {
        http.newCall(req(base + name).delete().build()).execute().use { r ->
            if (!r.isSuccessful && r.code != 404) throw WebDavException("Delete failed: HTTP ${r.code} ${r.message}")
        }
    }

    /** Files directly inside the [APP_FOLDER], newest first. */
    fun list(): List<RemoteFile> = list(base)

    /** [list] plus backups left directly in the configured URL by older versions, newest first. */
    fun listWithLegacy(): List<RemoteFile> {
        val current = list()
        if (root == base) return current
        val old = runCatching { list(root) }.getOrDefault(emptyList()).map { it.copy(legacy = true) }
        return (current + old).sortedByDescending { it.modifiedEpochMs ?: 0 }
    }

    private fun list(url: String): List<RemoteFile> = propfind(url, depth = 1).use { r ->
        if (r.code == 401 || r.code == 403) throw WebDavException("The server rejected the username or password (HTTP ${r.code}).")
        if (r.code == 404) return emptyList()
        if (r.code != 207 && !r.isSuccessful) throw WebDavException("Listing failed: HTTP ${r.code}")
        parseMultistatus(r.body?.string().orEmpty())
            .sortedByDescending { it.modifiedEpochMs ?: 0 }
    }

    private fun propfind(url: String, depth: Int) = http.newCall(
        req(url)
            .header("Depth", depth.toString())
            .method("PROPFIND", PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .build(),
    ).execute()

    companion object {
        const val APP_FOLDER = "RunAnalyzer"

        private fun withSlash(url: String) = url.trim().let { if (it.endsWith("/")) it else "$it/" }

        /** The folder backups go to: [APP_FOLDER] inside [url], unless [url] already points at it. */
        fun appFolderUrl(url: String): String {
            val u = withSlash(url)
            val last = URLDecoder.decode(u.trimEnd('/').substringAfterLast('/'), "UTF-8")
            return if (last.equals(APP_FOLDER, ignoreCase = true)) u else "$u$APP_FOLDER/"
        }

        private const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""

        /** Parses a 207 Multi-Status reply into files (folders, including the listed one, are skipped). */
        internal fun parseMultistatus(xml: String): List<RemoteFile> {
            if (xml.isBlank()) return emptyList()
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val doc = factory.newDocumentBuilder().parse(xml.byteInputStream())
            val responses = doc.getElementsByTagNameNS("DAV:", "response")
            return (0 until responses.length).mapNotNull { i ->
                val r = responses.item(i) as Element
                fun first(name: String) = r.getElementsByTagNameNS("DAV:", name).item(0)?.textContent?.trim()
                if (r.getElementsByTagNameNS("DAV:", "collection").length > 0) return@mapNotNull null
                val href = first("href") ?: return@mapNotNull null
                val name = URLDecoder.decode(href.trimEnd('/').substringAfterLast('/'), "UTF-8")
                if (name.isBlank()) return@mapNotNull null
                RemoteFile(
                    name = name,
                    sizeBytes = first("getcontentlength")?.toLongOrNull(),
                    modifiedEpochMs = first("getlastmodified")?.let {
                        runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
                    },
                )
            }
        }
    }
}
