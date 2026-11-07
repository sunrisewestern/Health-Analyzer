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

data class RemoteFile(val name: String, val sizeBytes: Long?, val modifiedEpochMs: Long?)

class WebDavException(message: String) : IOException(message)

/**
 * Minimal WebDAV client for one backup folder (Nextcloud, ownCloud, Synology, Jianguoyun, …):
 * PROPFIND to list, PUT to upload, GET to download, MKCOL to create the folder. Pure JVM.
 */
class WebDavClient(
    private val http: OkHttpClient,
    folderUrl: String,
    user: String,
    password: String,
) {
    private val base = folderUrl.trim().let { if (it.endsWith("/")) it else "$it/" }
    private val auth = if (user.isNotBlank() || password.isNotBlank()) Credentials.basic(user, password, Charsets.UTF_8) else null

    private fun req(url: String) = Request.Builder().url(url).apply { auth?.let { header("Authorization", it) } }

    /** Checks the login and creates the folder if it doesn't exist yet. */
    fun ensureFolder() {
        val code = propfind(base, depth = 0).use { it.code }
        when (code) {
            207, 200 -> return
            404 -> http.newCall(req(base).method("MKCOL", null).build()).execute().use { r ->
                if (!r.isSuccessful) throw WebDavException("Couldn't create the folder (HTTP ${r.code}). Create it on the server, then try again.")
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

    fun download(name: String, dest: File) {
        http.newCall(req(base + name).get().build()).execute().use { r ->
            if (!r.isSuccessful) throw WebDavException("Download failed: HTTP ${r.code} ${r.message}")
            dest.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
        }
    }

    /** Files directly inside the folder, newest first. */
    fun list(): List<RemoteFile> = propfind(base, depth = 1).use { r ->
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
