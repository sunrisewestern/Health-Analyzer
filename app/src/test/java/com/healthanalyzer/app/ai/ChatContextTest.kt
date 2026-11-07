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
}
