package com.healthanalyzer.app.ai

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class CodexAuthTest {
    private fun jwt(payload: String) = listOf("{}", payload, "sig").joinToString(".") {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray())
    }

    @Test fun readsAccountEmailPlanAndExpiryFromTokens() {
        val id = jwt("""{"email":"a@b.c","https://api.openai.com/auth":{"chatgpt_account_id":"acct-1","chatgpt_plan_type":"plus"}}""")
        val access = jwt("""{"exp":2000000000}""")
        val resp = JsonObject().apply {
            addProperty("access_token", access); addProperty("refresh_token", "r1"); addProperty("id_token", id)
        }
        val t = CodexAuth.Tokens.fromTokenResponse(resp)
        assertEquals("acct-1", t.accountId)
        assertEquals("a@b.c", t.email)
        assertEquals("plus", t.plan)
        assertEquals(2000000000L, t.expiresAtSec)
        assertEquals(t, CodexAuth.Tokens.fromJson(t.toJson()))
    }

    @Test fun refreshKeepsOldRefreshTokenAndClaimsWhenOmitted() {
        val old = CodexAuth.Tokens("a", "r-old", "", "acct-1", 1, "a@b.c", "plus")
        val t = CodexAuth.Tokens.fromTokenResponse(JsonObject().apply { addProperty("access_token", jwt("""{"exp":5}""")) }, old)
        assertEquals("r-old", t.refreshToken)
        assertEquals("acct-1", t.accountId)
        assertTrue(t.expiresSoon(nowSec = 0))
    }

    @Test fun codexModelsUseSlug() {
        assertEquals(listOf("gpt-a", "gpt-b"), parseModelIds("""{"models":[{"slug":"gpt-b"},{"slug":"gpt-a"}]}"""))
    }
}
