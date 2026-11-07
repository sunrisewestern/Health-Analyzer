package com.healthanalyzer.app.data

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GarminPersonalApiTest {
    private val now = 1_000_000L

    @Test fun tokenFromOAuthStyleJson() {
        val t = GarminPersonalApi.parseToken("""{"access_token":"abc","token_type":"Bearer","expires_in":86400}""", now)
        assertEquals("abc", t.value)
        assertEquals(now + 86_400_000, t.expiresAtMs)
    }

    @Test fun tokenFromCamelCaseOrBareString() {
        assertEquals("xyz", GarminPersonalApi.parseToken("""{"accessToken":"xyz"}""", now).value)
        assertEquals("raw.jwt.value", GarminPersonalApi.parseToken("raw.jwt.value", now).value)
        assertEquals(now + 24 * 3600_000L, GarminPersonalApi.parseToken("raw.jwt.value", now).expiresAtMs)
    }

    @Test fun unexpectedTokenBodyIsReported() {
        val e = runCatching { GarminPersonalApi.parseToken("""{"message":"nope"}""", now) }.exceptionOrNull()
        assertTrue(e is GarminApiException && e.message!!.contains("nope"))
    }

    @Test fun activitiesListPicksRunsOnly() {
        val page = JsonParser.parseString(
            """[
              {"activityId":11,"activityName":"Morning Run","activityType":{"typeId":1,"typeKey":"running"},"beginTimestamp":1793567942000,"distance":6893.7},
              {"activityId":12,"activityType":{"typeKey":"treadmill_running"},"distance":4800},
              {"activityId":13,"activityType":{"typeKey":"trail_running"}},
              {"activityId":14,"activityType":{"typeKey":"cycling"}},
              {"activityName":"no id"}
            ]""",
        )
        val all = GarminPersonalApi.parseActivities(page)
        assertEquals(listOf(11L, 12L, 13L, 14L), all.map { it.id })
        assertEquals(listOf(11L, 12L, 13L), all.filter { it.isRun }.map { it.id })
        assertEquals("Morning Run", all.first().name)
        assertEquals(1793567942000L, all.first().startEpochMs)
    }

}
