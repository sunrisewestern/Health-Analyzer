package com.healthanalyzer.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerTest {

    @Test fun comparesVersionsNumerically() {
        assertTrue(compareVersions("1.10.0", "1.9.2") > 0)
        assertTrue(compareVersions("1.2.1", "1.2.0") > 0)
        assertTrue(compareVersions("1.2.0", "1.3") < 0)
        assertEquals(0, compareVersions("v1.2.0", "1.2.0-dev"))
        assertEquals(0, compareVersions("1.2", "1.2.0"))
    }

    @Test fun parsesLatestRelease() {
        val json = """
            {"tag_name":"v1.3.0","html_url":"https://github.com/x/y/releases/tag/v1.3.0","body":"## Notes\n- fix",
             "draft":false,"prerelease":false,
             "assets":[
               {"name":"HealthAnalyzer-1.3.0.apk.sha256","browser_download_url":"https://e/sha","size":91},
               {"name":"HealthAnalyzer-1.3.0.apk","browser_download_url":"https://e/apk","size":1234}
             ]}
        """.trimIndent()
        val r = parseRelease(json)!!
        assertEquals("1.3.0", r.version)
        assertEquals("https://e/apk", r.apkUrl)
        assertEquals("https://e/sha", r.sha256Url)
        assertEquals(1234L, r.apkSize)
        assertEquals("## Notes\n- fix", r.notes)
    }

    @Test fun releaseWithoutApkIsIgnored() {
        assertNull(parseRelease("""{"tag_name":"v2.0.0","assets":[]}"""))
        assertNull(parseRelease("""{"tag_name":"v2.0.0","prerelease":true,"assets":[{"name":"a.apk","browser_download_url":"u"}]}"""))
        assertNull(parseRelease("not json"))
    }

    @Test fun parsesSha256SumLine() {
        val hash = "a".repeat(64)
        assertEquals(hash, parseSha256("$hash  HealthAnalyzer-1.3.0.apk\n"))
        assertNull(parseSha256("nope"))
    }
}
