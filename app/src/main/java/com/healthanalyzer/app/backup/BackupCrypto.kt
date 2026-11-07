package com.healthanalyzer.app.backup

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class WrongPasswordException : IOException("Wrong password, or the backup file is damaged")

/**
 * Password encryption for backup files. Format:
 * `HABK1` · salt (16) · chunks of [length (4) · IV (12) · AES-256-GCM ciphertext+tag].
 * Each chunk holds up to 1 MiB of plaintext, so memory use stays flat for large backups. The chunk
 * index and a last-chunk flag are authenticated (GCM AAD), so reordered or truncated files fail.
 * The key comes from PBKDF2-HMAC-SHA256 with [ITERATIONS] rounds. Pure JVM.
 */
object BackupCrypto {
    private val MAGIC = "HABK1".toByteArray(Charsets.US_ASCII)
    private const val ITERATIONS = 210_000
    private const val CHUNK = 1 shl 20
    private val random = SecureRandom()

    fun isEncrypted(file: File): Boolean = file.inputStream().use { input ->
        val head = ByteArray(MAGIC.size)
        input.read(head) == MAGIC.size && head.contentEquals(MAGIC)
    }

    fun encrypt(plain: File, out: File, password: CharArray) {
        val salt = ByteArray(16).also(random::nextBytes)
        val key = key(password, salt)
        plain.inputStream().buffered().use { input ->
            DataOutputStream(out.outputStream().buffered()).use { output ->
                output.write(MAGIC)
                output.write(salt)
                val buf = ByteArray(CHUNK)
                var index = 0L
                var n = readFully(input, buf)
                while (true) {
                    val next = ByteArray(CHUNK)
                    val m = if (n == CHUNK) readFully(input, next) else 0
                    val last = m <= 0
                    val iv = ByteArray(12).also(random::nextBytes)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
                    cipher.updateAAD(aad(index, last))
                    val ct = cipher.doFinal(buf, 0, maxOf(n, 0))
                    output.writeInt(ct.size)
                    output.write(iv)
                    output.write(ct)
                    if (last) break
                    System.arraycopy(next, 0, buf, 0, m)
                    n = m
                    index++
                }
            }
        }
    }

    fun decrypt(encrypted: File, out: File, password: CharArray) {
        DataInputStream(encrypted.inputStream().buffered()).use { input ->
            val head = ByteArray(MAGIC.size).also { input.readFully(it) }
            if (!head.contentEquals(MAGIC)) throw IOException("Not an encrypted HealthAnalyzer backup")
            val salt = ByteArray(16).also { input.readFully(it) }
            val key = key(password, salt)
            out.outputStream().buffered().use { output ->
                var index = 0L
                while (true) {
                    val len = try { input.readInt() } catch (e: EOFException) { throw WrongPasswordException() }
                    if (len < 16 || len > CHUNK + 16) throw WrongPasswordException()
                    val iv = ByteArray(12)
                    val ct = ByteArray(len)
                    try { input.readFully(iv); input.readFully(ct) } catch (e: EOFException) { throw WrongPasswordException() }
                    val last = input.available() == 0 && peekEnd(input)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                    cipher.updateAAD(aad(index, last))
                    val pt = try { cipher.doFinal(ct) } catch (e: AEADBadTagException) { throw WrongPasswordException() }
                    output.write(pt)
                    if (last) break
                    index++
                }
            }
        }
    }

    private fun peekEnd(input: DataInputStream): Boolean {
        input.mark(1)
        val b = input.read()
        if (b == -1) return true
        input.reset()
        return false
    }

    private fun aad(index: Long, last: Boolean) = ByteBuffer.allocate(9).putLong(index).put(if (last) 1 else 0).array()

    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }

    private fun readFully(input: java.io.InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val r = input.read(buf, total, buf.size - total)
            if (r < 0) break
            total += r
        }
        return total
    }
}
