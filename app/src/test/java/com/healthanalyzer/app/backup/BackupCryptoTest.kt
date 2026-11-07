package com.healthanalyzer.app.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class BackupCryptoTest {

    private fun roundTrip(size: Int) {
        val dir = createTempDir()
        val plain = File(dir, "p").apply { writeBytes(Random(size).nextBytes(size)) }
        val enc = File(dir, "e")
        val dec = File(dir, "d")
        BackupCrypto.encrypt(plain, enc, "s3cret!".toCharArray())
        assertTrue(BackupCrypto.isEncrypted(enc))
        assertFalse(BackupCrypto.isEncrypted(plain))
        BackupCrypto.decrypt(enc, dec, "s3cret!".toCharArray())
        assertArrayEquals(plain.readBytes(), dec.readBytes())
        dir.deleteRecursively()
    }

    @Test fun emptyFile() = roundTrip(0)
    @Test fun small() = roundTrip(1234)
    @Test fun exactlyOneChunk() = roundTrip(1 shl 20)
    @Test fun severalChunks() = roundTrip((1 shl 20) * 2 + 17)

    @Test(expected = WrongPasswordException::class)
    fun wrongPasswordFails() {
        val dir = createTempDir()
        val plain = File(dir, "p").apply { writeText("api keys") }
        val enc = File(dir, "e")
        BackupCrypto.encrypt(plain, enc, "right".toCharArray())
        BackupCrypto.decrypt(enc, File(dir, "d"), "wrong".toCharArray())
    }

    @Test(expected = WrongPasswordException::class)
    fun truncatedFileFails() {
        val dir = createTempDir()
        val plain = File(dir, "p").apply { writeBytes(Random(1).nextBytes((1 shl 20) * 2)) }
        val enc = File(dir, "e")
        BackupCrypto.encrypt(plain, enc, "pw".toCharArray())
        // Drop the whole last chunk: the one before isn't flagged as last, so this must fail.
        val bytes = enc.readBytes()
        val lastChunkLen = 4 + 12 + (1 shl 20) + 16
        File(dir, "t").writeBytes(bytes.copyOf(bytes.size - lastChunkLen))
        BackupCrypto.decrypt(File(dir, "t"), File(dir, "d2"), "pw".toCharArray()).let { }
    }

    @Test(expected = WrongPasswordException::class)
    fun cutMidChunkFails() {
        val dir = createTempDir()
        val plain = File(dir, "p").apply { writeBytes(Random(2).nextBytes(5000)) }
        val enc = File(dir, "e")
        BackupCrypto.encrypt(plain, enc, "pw".toCharArray())
        val bytes = enc.readBytes()
        File(dir, "t").writeBytes(bytes.copyOf(bytes.size - 100))
        BackupCrypto.decrypt(File(dir, "t"), File(dir, "d"), "pw".toCharArray())
    }

    @Test
    fun parsesWebDavListing() {
        val xml = """<?xml version="1.0"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response><d:href>/dav/HealthAnalyzer/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
              <d:response><d:href>/dav/HealthAnalyzer/HealthAnalyzer-config-20261107-1600.habackup</d:href><d:propstat><d:prop><d:resourcetype/>
                <d:getcontentlength>2048</d:getcontentlength><d:getlastmodified>Sat, 07 Nov 2026 21:00:00 GMT</d:getlastmodified></d:prop></d:propstat></d:response>
            </d:multistatus>"""
        val files = WebDavClient.parseMultistatus(xml)
        assertEquals(1, files.size)
        assertEquals("HealthAnalyzer-config-20261107-1600.habackup", files[0].name)
        assertEquals(2048L, files[0].sizeBytes)
        assertEquals(1794085200000L, files[0].modifiedEpochMs)
    }
}
