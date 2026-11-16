package com.taskmind.core

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Opens WhatsApp's own end-to-end encrypted backup (msgstore.db.crypt15) with
 * the 64-digit key the user copied out of WhatsApp.
 *
 * WhatsApp has no "export every chat", and exporting hundreds of chats one by
 * one is not something anyone will do. The backup already holds all of them,
 * and it sits in a folder this app can read. With the key, it is one step.
 *
 * THE FORMAT (not published by WhatsApp; this follows what the open-source
 * decryption tools have established, and is written to fail clearly rather
 * than guess when a file does not match):
 *
 *   1 byte    length N of the header
 *   1 byte    0x01, present on message-store backups
 *   N bytes   a protobuf header; field 3 holds a message whose field 1 is the
 *             16-byte IV
 *   ...       the database, zlib-compressed, then AES-256-GCM encrypted
 *   16 bytes  the GCM tag (and, on most files, 16 more: an MD5 of the file)
 *
 * The AES key is HKDF-SHA256 of the 32-byte root key (the 64 digits), with a
 * zero salt and the label "backup encryption".
 *
 * WHY NOT Cipher("AES/GCM")
 *
 * Android's GCM holds the whole ciphertext in memory until the tag is checked,
 * and a message store is routinely over a gigabyte. So the stream is decrypted
 * here as what GCM is underneath - AES in counter mode from the block after J0
 * - a buffer at a time, straight into the inflater. Integrity still has two
 * guards: zlib's own checksum, and the SQLite header the result must start
 * with. A wrong key fails on the first buffer.
 *
 * Pure JVM: tested against the platform's real AES-GCM.
 */
object WaBackup {

    class BadKey(message: String) : IOException(message)
    class BadFile(message: String) : IOException(message)

    const val KEY_HEX_LENGTH = 64
    private const val BLOCK = 16
    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    /** The key as typed: digits and letters a-f, in any grouping. Null unless it is 64 of them. */
    fun parseKey(typed: String): ByteArray? {
        val hex = typed.filter { it.isLetterOrDigit() }.lowercase()
        if (hex.length != KEY_HEX_LENGTH || hex.any { it !in "0123456789abcdef" }) return null
        return ByteArray(32) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
    }

    /**
     * Decrypts and decompresses [input] into [output], which then holds a
     * SQLite database. Returns the number of bytes written. [progress] is
     * given the encrypted bytes read so far.
     */
    fun decrypt(input: InputStream, rootKey: ByteArray, output: OutputStream, progress: (Long) -> Unit = {}): Long {
        val iv = readHeader(input)
        val key = deriveKey(rootKey)
        val aes = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }
        val counter = j0(aes, iv)
        inc32(counter)

        val inflater = Inflater()
        val encrypted = ByteArray(BUFFER)
        val stream = ByteArray(BUFFER)
        val plain = ByteArray(BUFFER)
        var read = 0L
        var written = 0L
        var checkedMagic = false
        try {
            while (!inflater.finished()) {
                val n = readFully(input, encrypted)
                if (n <= 0) throw BadFile("The backup file ends before the database does. It may still be being written; try again in a minute.")
                read += n
                keystream(aes, counter, stream, n)
                for (i in 0 until n) encrypted[i] = (encrypted[i].toInt() xor stream[i].toInt()).toByte()
                inflater.setInput(encrypted, 0, n)
                while (!inflater.needsInput() && !inflater.finished()) {
                    val out = try {
                        inflater.inflate(plain)
                    } catch (e: DataFormatException) {
                        // Garbage where zlib should be: on the first buffer that is the key.
                        if (written == 0L) throw BadKey("That key does not open this backup.")
                        throw BadFile("The backup is damaged part-way through.")
                    }
                    if (out == 0 && inflater.needsDictionary()) throw BadKey("That key does not open this backup.")
                    if (out > 0) {
                        if (!checkedMagic) {
                            if (out < SQLITE_MAGIC.size || !SQLITE_MAGIC.indices.all { plain[it] == SQLITE_MAGIC[it] }) {
                                throw BadKey("That key does not open this backup.")
                            }
                            checkedMagic = true
                        }
                        output.write(plain, 0, out)
                        written += out
                    }
                }
                progress(read)
            }
        } finally {
            inflater.end()
        }
        if (!checkedMagic) throw BadFile("The backup held no database.")
        return written
    }

    // ------------------------------------------------------------- the parts

    /** Reads the header off the front of the stream and returns the 16-byte IV. */
    internal fun readHeader(input: InputStream): ByteArray {
        val size = input.read()
        if (size <= 0) throw BadFile("This is not a WhatsApp backup file.")
        var first = input.read()
        if (first < 0) throw BadFile("This is not a WhatsApp backup file.")
        val header = ByteArray(size)
        var offset = 0
        if (first != 0x01) {
            // No feature byte: what was read is the header's own first byte.
            header[0] = first.toByte()
            offset = 1
        }
        while (offset < size) {
            val n = input.read(header, offset, size - offset)
            if (n < 0) throw BadFile("This is not a WhatsApp backup file.")
            offset += n
        }
        val ivHolder = protoField(header, 3)
            ?: throw BadFile(
                "This backup is not end-to-end encrypted with a 64-digit key. In WhatsApp, turn on " +
                    "end-to-end encrypted backup and choose the 64-digit key, then back up again.",
            )
        val iv = protoField(ivHolder, 1)
        if (iv == null || iv.size != BLOCK) throw BadFile("This backup's header is in a form TaskMind does not know yet.")
        return iv
    }

    /** The bytes of the first length-delimited field numbered [number], or null. */
    internal fun protoField(message: ByteArray, number: Int): ByteArray? {
        var i = 0
        fun varint(): Long {
            var shift = 0
            var value = 0L
            while (true) {
                if (i >= message.size || shift > 63) throw BadFile("This backup's header cannot be read.")
                val b = message[i++].toInt() and 0xFF
                value = value or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return value
                shift += 7
            }
        }
        while (i < message.size) {
            val tag = varint()
            val field = (tag ushr 3).toInt()
            when ((tag and 7).toInt()) {
                0 -> varint()
                1 -> i += 8
                5 -> i += 4
                2 -> {
                    val length = varint()
                    if (length < 0 || length > message.size - i) throw BadFile("This backup's header cannot be read.")
                    if (field == number) return message.copyOfRange(i, i + length.toInt())
                    i += length.toInt()
                }
                else -> throw BadFile("This backup's header cannot be read.")
            }
        }
        return null
    }

    /** HKDF-SHA256, zero salt, label "backup encryption", 32 bytes. */
    internal fun deriveKey(rootKey: ByteArray): ByteArray {
        fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                parts.forEach { update(it) }
                doFinal()
            }
        val extracted = hmac(ByteArray(32), rootKey)
        return hmac(extracted, "backup encryption".toByteArray(Charsets.US_ASCII), byteArrayOf(1))
    }

    /** GCM's first counter block for an IV that is not 96 bits: GHASH(IV, then its length in bits). */
    internal fun j0(aes: Cipher, iv: ByteArray): ByteArray {
        val h = aes.doFinal(ByteArray(BLOCK))
        val lengths = ByteArray(BLOCK)
        val bits = iv.size.toLong() * 8
        for (k in 0 until 8) lengths[15 - k] = (bits ushr (8 * k)).toByte()
        var y = ByteArray(BLOCK)
        for (block in listOf(iv, lengths)) {
            for (k in 0 until BLOCK) y[k] = (y[k].toInt() xor block[k].toInt()).toByte()
            y = gfMultiply(y, h)
        }
        return y
    }

    /** Multiplication in GF(2^128) as GCM defines it. Bit by bit: it runs twice per file. */
    private fun gfMultiply(x: ByteArray, y: ByteArray): ByteArray {
        val z = ByteArray(BLOCK)
        val v = y.copyOf()
        for (bit in 0 until 128) {
            if ((x[bit / 8].toInt() shr (7 - bit % 8)) and 1 == 1) {
                for (k in 0 until BLOCK) z[k] = (z[k].toInt() xor v[k].toInt()).toByte()
            }
            val carry = v[15].toInt() and 1
            for (k in 15 downTo 1) {
                v[k] = (((v[k].toInt() and 0xFF) ushr 1) or ((v[k - 1].toInt() and 1) shl 7)).toByte()
            }
            v[0] = ((v[0].toInt() and 0xFF) ushr 1).toByte()
            if (carry == 1) v[0] = (v[0].toInt() xor 0xE1).toByte()
        }
        return z
    }

    /** GCM's counter step: the last four bytes, wrapping, the rest untouched. */
    internal fun inc32(counter: ByteArray) {
        for (k in 15 downTo 12) {
            counter[k] = (counter[k] + 1).toByte()
            if (counter[k].toInt() != 0) return
        }
    }

    /** Fills [out] with [length] bytes of keystream and leaves [counter] at the next block. */
    private fun keystream(aes: Cipher, counter: ByteArray, out: ByteArray, length: Int) {
        val blocks = (length + BLOCK - 1) / BLOCK
        val counters = ByteArray(blocks * BLOCK)
        for (b in 0 until blocks) {
            System.arraycopy(counter, 0, counters, b * BLOCK, BLOCK)
            inc32(counter)
        }
        val stream = aes.doFinal(counters)
        System.arraycopy(stream, 0, out, 0, length)
    }

    /** Reads until [buffer] is full or the stream ends; a short read mid-file would break the block count. */
    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = try {
                input.read(buffer, total, buffer.size - total)
            } catch (e: EOFException) {
                -1
            }
            if (n < 0) break
            total += n
        }
        return total
    }

    /** A multiple of the block size, so every buffer but the last ends on a block. */
    private const val BUFFER = 64 * 1024
}
