package com.healthanalyzer.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class AutoBackupTest {

    @Test
    fun intervalsMapToHours() {
        assertEquals(
            listOf(0L, 6L, 12L, 24L, 72L, 168L),
            AutoBackupInterval.entries.map { it.hours },
        )
        // WorkManager's shortest period is 15 minutes; every real interval must be above it.
        assertTrue(AutoBackupInterval.entries.filter { it != AutoBackupInterval.OFF }.all { it.hours >= 1 })
    }

    @Test
    fun unknownIntervalNameMeansOff() {
        assertEquals(AutoBackupInterval.DAILY, AutoBackupInterval.fromName("DAILY"))
        assertEquals(AutoBackupInterval.OFF, AutoBackupInterval.fromName(null))
        assertEquals(AutoBackupInterval.OFF, AutoBackupInterval.fromName("HOURLY"))
    }

    @Test
    fun destinationKeysRoundTrip() {
        AutoBackupDestination.entries.forEach { assertEquals(it, AutoBackupDestination.fromKey(it.key)) }
        assertNull(AutoBackupDestination.fromKey("ftp"))
    }

    @Test
    fun fileNamesCarryKindTimeAndEncryption() {
        val at = LocalDateTime.of(2026, 3, 7, 4, 5)
        assertEquals("HealthAnalyzer-auto-data-20260307-0405.zip", AutoBackupFiles.name(BackupKind.DATA, false, at))
        assertEquals("HealthAnalyzer-auto-config-20260307-0405.habackup", AutoBackupFiles.name(BackupKind.CONFIG, true, at))
    }

    @Test
    fun parseReadsBackItsOwnNamesOnly() {
        val at = LocalDateTime.of(2026, 11, 13, 23, 59)
        BackupKind.entries.forEach { kind ->
            listOf(true, false).forEach { enc ->
                assertEquals(AutoBackupFiles.Parsed(kind, "20261113-2359"), AutoBackupFiles.parse(AutoBackupFiles.name(kind, enc, at)))
            }
        }
        assertNull(AutoBackupFiles.parse("HealthAnalyzer-data-20261113-2359.zip")) // manual backup
        assertNull(AutoBackupFiles.parse("HealthAnalyzer-auto-data-20261113-2359.zip.part"))
        assertNull(AutoBackupFiles.parse("HealthAnalyzer-auto-data-20261113-2359 (1).zip"))
        assertNull(AutoBackupFiles.parse("holiday.jpg"))
    }

    @Test
    fun pruneKeepsTheNewestOfEachKind() {
        val names = listOf(
            "HealthAnalyzer-auto-data-20261110-0800.zip",
            "HealthAnalyzer-auto-data-20261112-0800.habackup",
            "HealthAnalyzer-auto-data-20261111-0800.zip",
            "HealthAnalyzer-auto-config-20261110-0800.habackup",
            "HealthAnalyzer-auto-config-20261111-0800.habackup",
            "HealthAnalyzer-auto-config-20261112-0800.habackup",
        )
        assertEquals(
            setOf("HealthAnalyzer-auto-data-20261110-0800.zip", "HealthAnalyzer-auto-config-20261110-0800.habackup"),
            AutoBackupFiles.toPrune(names, keep = 2).toSet(),
        )
        assertTrue(AutoBackupFiles.toPrune(names, keep = 3).isEmpty())
    }

    @Test
    fun pruneNeverTouchesManualBackupsOrOtherFiles() {
        val names = listOf(
            "HealthAnalyzer-data-20200101-0000.zip",
            "HealthAnalyzer-config-20200101-0000.habackup",
            "notes.txt",
            "HealthAnalyzer-auto-data-20261110-0800.zip",
            "HealthAnalyzer-auto-data-20261111-0800.zip",
        )
        assertEquals(listOf("HealthAnalyzer-auto-data-20261110-0800.zip"), AutoBackupFiles.toPrune(names, keep = 1))
    }

    @Test
    fun pruneAlwaysKeepsAtLeastOneAndIgnoresDuplicates() {
        val newest = "HealthAnalyzer-auto-data-20261111-0800.zip"
        val names = listOf("HealthAnalyzer-auto-data-20261110-0800.zip", newest, newest)
        assertEquals(listOf("HealthAnalyzer-auto-data-20261110-0800.zip"), AutoBackupFiles.toPrune(names, keep = 0))
        assertTrue(AutoBackupFiles.toPrune(emptyList(), keep = 5).isEmpty())
    }

    @Test
    fun settingsBackupsNeedAUsablePassword() {
        val base = AutoBackupSettings(includeConfig = true)
        assertEquals(listOf(BackupKind.DATA), base.kinds)
        assertEquals(listOf(BackupKind.DATA), base.copy(password = "12345").kinds)
        assertNull(base.copy(password = "12345").effectivePassword)
        assertEquals(listOf(BackupKind.DATA, BackupKind.CONFIG), base.copy(password = "123456").kinds)
        assertEquals(listOf(BackupKind.DATA), base.copy(password = "123456", includeConfig = false).kinds)
    }
}
