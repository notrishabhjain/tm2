package com.taskmind.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Random
import java.util.zip.Deflater
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The backup reader against the platform's own AES-GCM: a file is built here
 * the way WhatsApp lays one out, encrypted by the JDK, and must come back
 * byte for byte.
 */
class WaBackupTest {

    private val rootKey = ByteArray(32) { (it * 7 + 3).toByte() }
    private val iv = ByteArray(16) { (0xA0 + it).toByte() }

    private fun database(size: Int): ByteArray {
        val body = ByteArray(size)
        Random(42).nextBytes(body)
        // Half compressible, half not, so the inflater works across buffers.
        for (i in 0 until size / 2) body[i] = (i % 61).toByte()
        val magic = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        System.arraycopy(magic, 0, body, 0, magic.size)
        return body
    }

    private fun zlib(data: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        return out.toByteArray()
    }

    /** Field 1 varint, field 3 { field 1: iv }, field 4 some text - as the real header orders them. */
    private fun header(): ByteArray {
        val inner = byteArrayOf(0x0A, 16) + iv
        return byteArrayOf(0x08, 0x01) + byteArrayOf(0x1A, inner.size.toByte()) + inner +
            byteArrayOf(0x22, 3) + "2.2".toByteArray()
    }

    private fun backup(plain: ByteArray, key: ByteArray = rootKey, featureByte: Boolean = true, md5: Boolean = true): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(WaBackup.deriveKey(key), "AES"), GCMParameterSpec(128, iv))
        val sealed = cipher.doFinal(zlib(plain))
        val head = header()
        val out = ByteArrayOutputStream()
        out.write(head.size)
        if (featureByte) out.write(1)
        out.write(head)
        out.write(sealed)
        if (md5) out.write(MessageDigest.getInstance("MD5").digest(out.toByteArray()))
        return out.toByteArray()
    }

    private fun open(file: ByteArray, key: ByteArray = rootKey): ByteArray {
        val out = ByteArrayOutputStream()
        WaBackup.decrypt(ByteArrayInputStream(file), key, out)
        return out.toByteArray()
    }

    @Test
    fun `a backup sealed by the platform's AES-GCM opens byte for byte`() {
        val db = database(700_000)
        assertArrayEquals(db, open(backup(db)))
    }

    @Test
    fun `it opens with or without the feature byte and the trailing checksum`() {
        val db = database(50_000)
        assertArrayEquals(db, open(backup(db, featureByte = false)))
        assertArrayEquals(db, open(backup(db, md5 = false)))
    }

    @Test
    fun `the wrong key is reported as the wrong key`() {
        val file = backup(database(50_000))
        assertThrows(WaBackup.BadKey::class.java) { open(file, ByteArray(32) { 9 }) }
    }

    @Test
    fun `a file that is not a backup is reported as such`() {
        assertThrows(WaBackup.BadFile::class.java) { open("hello, this is a text file".toByteArray()) }
        assertThrows(WaBackup.BadFile::class.java) { open(ByteArray(0)) }
    }

    @Test
    fun `a backup cut short is reported, not returned`() {
        val file = backup(database(300_000))
        assertThrows(WaBackup.BadFile::class.java) { open(file.copyOf(file.size / 2)) }
    }

    @Test
    fun `the key is read however it is grouped`() {
        val hex = rootKey.joinToString("") { "%02x".format(it) }
        assertArrayEquals(rootKey, WaBackup.parseKey(hex))
        assertArrayEquals(rootKey, WaBackup.parseKey(hex.uppercase().chunked(4).joinToString(" ")))
        assertNull(WaBackup.parseKey(hex.dropLast(1)))
        assertNull(WaBackup.parseKey(hex.dropLast(1) + "z"))
        assertNotNull(WaBackup.parseKey(hex.chunked(8).joinToString("-")))
    }

    @Test
    fun `the counter wraps in its last four bytes only`() {
        val counter = ByteArray(16) { 0x11 }
        for (k in 12..15) counter[k] = 0xFF.toByte()
        WaBackup.inc32(counter)
        assertEquals(List(12) { 0x11.toByte() } + List(4) { 0.toByte() }, counter.toList())
    }

    @Test
    fun `a month in a question becomes that month's span, last year's when it is still ahead`() {
        val zone = DateResolver.IST
        val now = java.time.LocalDate.of(2026, 11, 16).atStartOfDay(zone).toInstant().toEpochMilli()
        fun day(y: Int, m: Int, d: Int) = java.time.LocalDate.of(y, m, d).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(day(2026, 8, 1) to day(2026, 9, 1), Recall.monthRange("what did we agree with the vendor in August", now))
        assertEquals(day(2025, 12, 1) to day(2026, 1, 1), Recall.monthRange("anything from Dec?", now))
        assertNull(Recall.monthRange("what does Priya owe me", now))
    }
}
