package com.healthanalyzer.app.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatContextTest {
    private fun m(role: String, text: String, error: String? = null) =
        ChatMessageEntity(chatId = 1, role = role, text = text, createdEpochMs = 0, error = error)

    @Test
    fun dropsFailedRepliesAndMergesSameSide() {
        val turns = ChatContext.turns(
            listOf(
                m("assistant", "orphan greeting"),
                m("user", "q1"),
                m("assistant", "", error = "HTTP 500"),
                m("user", "q1 again"),
                m("assistant", "a1"),
                m("user", "q2"),
            ),
        )
        assertEquals(listOf("user", "assistant", "user"), turns.map { it.role })
        assertEquals("q1\n\nq1 again", turns[0].text)
        assertEquals("q2", turns.last().text)
    }

    @Test
    fun attachesMediaAndDescribesVideoFrames() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        listOf("p.jpg", "f1.jpg", "f2.jpg", "v.mp4").forEach { java.io.File(dir, it).writeText("x") }
        val photo = ChatAttachment(ChatAttachment.IMAGE, listOf("p.jpg"))
        val video = ChatAttachment(ChatAttachment.VIDEO, listOf("f1.jpg", "f2.jpg"), "v.mp4", "video/mp4", 42_000)
        val msgs = listOf(
            m("user", "").copy(attachments = ChatMedia.encode(listOf(photo))),
            m("user", "how is my form?").copy(attachments = ChatMedia.encode(listOf(video))),
        )

        val asFrames = ChatContext.turns(msgs, dir, nativeVideo = false).single()
        assertEquals(listOf("p.jpg", "f1.jpg", "f2.jpg"), asFrames.media.map { it.file.name })
        assertEquals(true, asFrames.text.contains("42 s long, sent as 2 still frame"))
        assertEquals(true, asFrames.text.endsWith("how is my form?"))

        val native = ChatContext.turns(msgs, dir, nativeVideo = true).single()
        assertEquals(listOf("image/jpeg", "video/mp4"), native.media.map { it.mimeType })
        assertEquals("how is my form?", native.text)

        // Without a media dir (or with files gone), a photo-only message is dropped, not sent empty.
        assertEquals("how is my form?", ChatContext.turns(msgs).single().text)
        dir.deleteRecursively()
    }
}
