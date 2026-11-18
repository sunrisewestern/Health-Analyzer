package com.healthanalyzer.app.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class WebDavClientTest {
    @Test fun appendsAppFolderToRoot() {
        assertEquals("https://dav.jianguoyun.com/dav/RunAnalyzer/", WebDavClient.appFolderUrl("https://dav.jianguoyun.com/dav/"))
        assertEquals("https://dav.jianguoyun.com/dav/RunAnalyzer/", WebDavClient.appFolderUrl(" https://dav.jianguoyun.com/dav "))
    }

    @Test fun keepsUrlThatAlreadyPointsAtAppFolder() {
        assertEquals("https://x.org/files/me/RunAnalyzer/", WebDavClient.appFolderUrl("https://x.org/files/me/RunAnalyzer"))
        assertEquals("https://x.org/files/me/runanalyzer/", WebDavClient.appFolderUrl("https://x.org/files/me/runanalyzer/"))
    }

    @Test fun nestsInsideAnOlderBackupFolder() {
        assertEquals("https://x.org/dav/HealthAnalyzer/RunAnalyzer/", WebDavClient.appFolderUrl("https://x.org/dav/HealthAnalyzer/"))
    }
}
