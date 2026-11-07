package com.healthanalyzer.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParseModelIdsTest {
    private fun error(body: String): String =
        runCatching { parseModelIds(body) }.exceptionOrNull()?.message.orEmpty()

    @Test fun openAiShape() {
        assertEquals(listOf("a", "b"), parseModelIds("""{"object":"list","data":[{"id":"b"},{"id":"a"}]}"""))
    }

    @Test fun newApiShapeWithModels() {
        val body = """{"success":true,"object":"list","data":[{"id":"gpt-4o","object":"model","owned_by":"openai"}]}"""
        assertEquals(listOf("gpt-4o"), parseModelIds(body))
    }

    @Test fun newApiNullDataExplainsInsteadOfCrashing() {
        val msg = error("""{"success":true,"data":null,"object":"list"}""")
        assertTrue(msg, msg.contains("no model list"))
    }

    @Test fun newApiFailureMessageIsShown() {
        assertEquals("get user group failed", error("""{"success":false,"message":"get user group failed"}"""))
    }

    @Test fun errorObject() {
        assertEquals("bad key", error("""{"error":{"message":"bad key","type":"auth"}}"""))
    }

    @Test fun bareArrayAndStrings() {
        assertEquals(listOf("x", "y"), parseModelIds("""["y","x","x"]"""))
        assertEquals(listOf("m1"), parseModelIds("""{"models":[{"name":"m1"}]}"""))
    }

    @Test fun htmlPageGivesBaseUrlHint() {
        assertTrue(error("<html>login</html>").contains("/v1"))
    }
}
