package com.healthanalyzer.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** One image (or video file) sent to the model with a turn. */
data class MediaPart(val mimeType: String, val file: File) {
    val isImage: Boolean get() = mimeType.startsWith("image/")
    fun base64(): String = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
    fun dataUrl(): String = "data:$mimeType;base64,${base64()}"
}

/**
 * A photo or video attached to a chat message. Files live in [ChatMedia.dir] and are stored
 * by name. A video is always kept as a few still frames (every API takes images); a copy of
 * the clip itself is kept too when it is small enough for Gemini to watch it directly.
 */
data class ChatAttachment(
    val kind: String,
    /** JPEG file names: the photo, or the video's frames in order. */
    val frames: List<String>,
    val video: String? = null,
    val videoMime: String? = null,
    val durationMs: Long = 0,
) {
    val isVideo: Boolean get() = kind == VIDEO

    companion object {
        const val IMAGE = "image"
        const val VIDEO = "video"
    }
}

object ChatMedia {
    const val MAX_ATTACHMENTS = 6
    private const val MAX_SIDE = 1568 // Claude's and GPT's sweet spot; larger is downscaled server-side anyway
    private const val VIDEO_FRAMES = 6
    private const val MAX_INLINE_VIDEO_BYTES = 15L * 1024 * 1024 // Gemini inline requests cap at 20 MB
    private val gson = Gson()
    private val listType = object : TypeToken<List<ChatAttachment>>() {}.type

    fun dir(context: Context): File = File(context.filesDir, "chat_media").apply { mkdirs() }

    fun encode(list: List<ChatAttachment>): String? = list.takeIf { it.isNotEmpty() }?.let { gson.toJson(it) }

    fun decode(json: String?): List<ChatAttachment> =
        if (json.isNullOrBlank()) emptyList() else runCatching { gson.fromJson<List<ChatAttachment>>(json, listType) }.getOrNull().orEmpty()

    /** Copies a picked photo or video into app storage, downscaled. Throws with a readable message. */
    suspend fun import(context: Context, uri: Uri): ChatAttachment = withContext(Dispatchers.IO) {
        val mime = context.contentResolver.getType(uri).orEmpty()
        if (mime.startsWith("video/")) importVideo(context, uri, mime) else importImage(context, uri)
    }

    private fun importImage(context: Context, uri: Uri): ChatAttachment {
        val src = ImageDecoder.createSource(context.contentResolver, uri)
        val bmp = runCatching {
            ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                val (w, h) = info.size.width to info.size.height
                val scale = MAX_SIDE.toFloat() / maxOf(w, h)
                if (scale < 1f) decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrElse { throw IllegalArgumentException("Couldn't read that image.") }
        return ChatAttachment(ChatAttachment.IMAGE, listOf(saveJpeg(context, bmp)))
    }

    private fun importVideo(context: Context, uri: Uri, mime: String): ChatAttachment {
        val r = MediaMetadataRetriever()
        try {
            runCatching { r.setDataSource(context, uri) }.getOrElse { throw IllegalArgumentException("Couldn't read that video.") }
            val durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val count = if (durationMs < 2_000) 1 else VIDEO_FRAMES
            val frames = (0 until count).mapNotNull { i ->
                // Evenly spaced, skipping the very first and last instants (often black).
                val tUs = durationMs * 1000 * (2 * i + 1) / (2 * count)
                r.getScaledFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, MAX_SIDE, MAX_SIDE)
            }.map { saveJpeg(context, it) }
            if (frames.isEmpty()) throw IllegalArgumentException("Couldn't read frames from that video.")
            val size = context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
            val video = if (size != null && size <= MAX_INLINE_VIDEO_BYTES) {
                val name = "${UUID.randomUUID()}.${mime.substringAfter('/').substringBefore(';').ifBlank { "mp4" }}"
                context.contentResolver.openInputStream(uri)?.use { input ->
                    File(dir(context), name).outputStream().use { input.copyTo(it) }
                }
                name
            } else null
            return ChatAttachment(ChatAttachment.VIDEO, frames, video, mime.takeIf { video != null }, durationMs)
        } finally {
            r.release()
        }
    }

    private fun saveJpeg(context: Context, bmp: Bitmap): String {
        val name = "${UUID.randomUUID()}.jpg"
        File(dir(context), name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bmp.recycle()
        return name
    }

    fun files(a: ChatAttachment): List<String> = a.frames + listOfNotNull(a.video)

    fun delete(context: Context, list: List<ChatAttachment>) {
        val d = dir(context)
        list.flatMap(::files).forEach { File(d, it).delete() }
    }

    fun deleteAll(context: Context) { dir(context).listFiles()?.forEach { it.delete() } }

    /**
     * What a turn sends for [list]. [nativeVideo] (Gemini) sends the clip itself when it was kept;
     * otherwise its frames. Files missing (e.g. after a restore from backup) are skipped.
     */
    fun parts(dir: File, list: List<ChatAttachment>, nativeVideo: Boolean): List<MediaPart> = list.flatMap { a ->
        if (nativeVideo && a.isVideo && a.video != null && File(dir, a.video).exists()) {
            listOf(MediaPart(a.videoMime ?: "video/mp4", File(dir, a.video)))
        } else {
            a.frames.map { File(dir, it) }.filter { it.exists() }.map { MediaPart("image/jpeg", it) }
        }
    }

    /** A line telling the model how the media was sent, e.g. for a video sent as frames. */
    fun note(dir: File, list: List<ChatAttachment>, nativeVideo: Boolean): String? = list.filter { it.isVideo }
        .filterNot { nativeVideo && it.video != null && File(dir, it.video).exists() }
        .takeIf { it.isNotEmpty() }
        ?.joinToString("\n") { v ->
            "[Attached video, ${v.durationMs / 1000} s long, sent as ${v.frames.size} still frame(s) in time order.]"
        }

    fun durationLabel(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }
}
