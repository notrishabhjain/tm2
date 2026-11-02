package com.taskmind.email

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Just enough IMAP to read new mail: sign in, find the folders, list the
 * messages that arrived since last time, and fetch the start of each.
 *
 * Read-only by construction. Folders are opened with EXAMINE, never SELECT,
 * and bodies are fetched with BODY.PEEK, so TaskMind cannot mark a mail read,
 * move it or delete it - there is no command here that could.
 *
 * Written by hand rather than pulled in as a library: the protocol subset is
 * small, it runs on plain sockets, and so it is tested on the JVM against a
 * scripted server instead of only on a phone.
 */
class ImapClient(
    private val host: String,
    private val port: Int,
    /** Implicit TLS (port 993). Off only for the tests' local server. */
    private val tls: Boolean = true,
    private val timeoutMillis: Int = 30_000,
) : Closeable {

    /** A failure the user can act on. [auth] means the address or password was refused. */
    class ImapException(message: String, val auth: Boolean = false) : IOException(message)

    data class Folder(val name: String, val attributes: Set<String>) {
        fun has(attribute: String): Boolean = attributes.any { it.equals(attribute, ignoreCase = true) }
    }

    data class Mailbox(val exists: Int, val uidValidity: Long, val uidNext: Long)

    data class Fetched(val uid: Long, val internalDate: Long?, val raw: ByteArray)

    private class Response(val text: String, val literals: List<ByteArray>)

    private var socket: Socket? = null
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private var tag = 0

    fun connect() {
        val plain = Socket()
        plain.connect(InetSocketAddress(host, port), timeoutMillis)
        plain.soTimeout = timeoutMillis
        val connected: Socket = if (tls) {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = factory.createSocket(plain, host, port, true) as SSLSocket
            ssl.startHandshake()
            // An SSLSocket checks the certificate chain but not that it names
            // this host. Without this a valid certificate for any other site
            // would be accepted.
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) {
                runCatching { ssl.close() }
                throw ImapException("The server's certificate is not for $host.")
            }
            ssl
        } else {
            plain
        }
        socket = connected
        input = BufferedInputStream(connected.getInputStream())
        output = connected.getOutputStream()
        val greeting = readResponse()
        if (!greeting.text.startsWith("* OK", ignoreCase = true) && !greeting.text.startsWith("* PREAUTH", ignoreCase = true)) {
            throw ImapException("The mail server did not say hello: ${greeting.text.take(120)}")
        }
    }

    /** Signs in. A refusal throws with [ImapException.auth] set and the server's own words. */
    fun login(user: String, password: String) {
        val t = nextTag()
        write("$t LOGIN ")
        writeAString(user, t)
        write(" ")
        writeAString(password, t)
        write("\r\n")
        awaitTagged(t, auth = true)
    }

    fun list(): List<Folder> = command("LIST \"\" \"*\"").mapNotNull(::parseList)

    fun examine(folder: String): Mailbox {
        var exists = 0
        var validity = 0L
        var next = 0L
        command("EXAMINE ${quote(folder)}").forEach { r ->
            EXISTS.find(r.text)?.let { exists = it.groupValues[1].toInt() }
            UIDVALIDITY.find(r.text)?.let { validity = it.groupValues[1].toLong() }
            UIDNEXT.find(r.text)?.let { next = it.groupValues[1].toLong() }
        }
        return Mailbox(exists, validity, next)
    }

    /** Messages that arrived on or after [day] (the server's date, which is fine for "the last two days"). */
    fun uidsSince(day: LocalDate): List<Long> = search("SINCE ${IMAP_DATE.format(day)}")

    /** Messages with a UID above [uid]. "n:*" always includes the newest, so it is filtered. */
    fun uidsAfter(uid: Long): List<Long> = search("UID ${uid + 1}:*").filter { it > uid }

    /** The first [maxBytes] of each message, unread flags untouched. */
    fun fetch(uids: List<Long>, maxBytes: Int): List<Fetched> {
        if (uids.isEmpty()) return emptyList()
        return command("UID FETCH ${uids.joinToString(",")} (UID INTERNALDATE BODY.PEEK[]<0.$maxBytes>)")
            .mapNotNull(::parseFetch)
            .sortedBy { it.uid }
    }

    fun logout() {
        runCatching { command("LOGOUT") }
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
    }

    // ------------------------------------------------------------ protocol

    private fun nextTag(): String = "T${++tag}"

    private fun write(text: String) {
        output.write(text.toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    /**
     * Writes a string argument: quoted when it is plain ASCII, a literal
     * otherwise, waiting for the server's go-ahead before sending it.
     */
    private fun writeAString(value: String, t: String) {
        if (value.all { it.code in 0x20..0x7E }) {
            write(quote(value))
            return
        }
        val bytes = value.toByteArray(Charsets.UTF_8)
        write("{${bytes.size}}\r\n")
        val reply = readResponse()
        if (!reply.text.startsWith("+")) {
            throw ImapException(clean(reply.text.removePrefix("$t ").removePrefix("NO").removePrefix("BAD")), auth = true)
        }
        output.write(bytes)
        output.flush()
    }

    private fun command(command: String): List<Response> {
        val t = nextTag()
        write("$t $command\r\n")
        return awaitTagged(t, auth = false)
    }

    private fun awaitTagged(t: String, auth: Boolean): List<Response> {
        val untagged = mutableListOf<Response>()
        while (true) {
            val r = readResponse()
            if (r.text.startsWith("$t ")) {
                val status = r.text.substring(t.length + 1)
                return when {
                    status.startsWith("OK", ignoreCase = true) -> untagged
                    status.startsWith("NO", ignoreCase = true) -> throw ImapException(clean(status.drop(2)), auth = auth)
                    else -> throw ImapException("The mail server refused: ${clean(status)}", auth = auth)
                }
            }
            untagged += r
        }
    }

    /** One response, with any literals it carries lifted out and marked in the text. */
    private fun readResponse(): Response {
        val text = StringBuilder()
        val literals = mutableListOf<ByteArray>()
        while (true) {
            val line = readLine()
            val m = LITERAL.find(line)
            if (m == null) {
                text.append(line)
                return Response(text.toString(), literals)
            }
            text.append(line, 0, m.range.first)
            val size = m.groupValues[1].toLong()
            if (size > MAX_LITERAL) throw ImapException("The mail server sent more than expected.")
            literals += readExactly(size.toInt())
            text.append(LIT_OPEN).append(literals.size - 1).append(LIT_CLOSE)
        }
    }

    private fun readLine(): String {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) throw ImapException("The mail server closed the connection.")
            if (b == '\n'.code) break
            buf.write(b)
            if (buf.size() > MAX_LINE) throw ImapException("The mail server sent a line too long to read.")
        }
        val bytes = buf.toByteArray()
        val length = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
        return String(bytes, 0, length, Charsets.ISO_8859_1)
    }

    private fun readExactly(size: Int): ByteArray {
        val out = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = input.read(out, read, size - read)
            if (n < 0) throw ImapException("The mail server closed the connection mid-message.")
            read += n
        }
        return out
    }

    private fun search(criteria: String): List<Long> =
        command("UID SEARCH $criteria").flatMap { r ->
            if (r.text.startsWith("* SEARCH", ignoreCase = true)) {
                r.text.substring(8).trim().split(' ').mapNotNull { it.trim().toLongOrNull() }
            } else {
                emptyList()
            }
        }.distinct().sorted()

    private fun parseList(r: Response): Folder? {
        val m = LIST.find(r.text) ?: return null
        val attributes = m.groupValues[1].split(' ').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val rawName = m.groupValues[3].trim()
        val name = when {
            rawName.startsWith("\"") -> unquote(rawName.removeSurrounding("\""))
            rawName.startsWith(LIT_OPEN) -> {
                val index = rawName.removePrefix(LIT_OPEN).substringBefore(LIT_CLOSE).toIntOrNull() ?: return null
                String(r.literals.getOrNull(index) ?: return null, Charsets.ISO_8859_1)
            }
            else -> rawName
        }
        return Folder(name, attributes)
    }

    private fun parseFetch(r: Response): Fetched? {
        if (!FETCH.containsMatchIn(r.text)) return null
        val uid = UID.find(r.text)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val date = INTERNALDATE.find(r.text)?.groupValues?.get(1)?.let(::parseInternalDate)
        val body = BODY.find(r.text) ?: return null
        val raw = when {
            body.groupValues[1].isNotEmpty() -> r.literals.getOrNull(body.groupValues[1].toInt()) ?: return null
            body.groupValues[2].isNotEmpty() -> unquote(body.groupValues[2]).toByteArray(Charsets.ISO_8859_1)
            else -> ByteArray(0)
        }
        return Fetched(uid, date, raw)
    }

    /** The server's message without the bracketed codes, e.g. "Invalid credentials (Failure)". */
    private fun clean(status: String): String =
        status.trim().replace(Regex("^\\[[^]]*]\\s*"), "").trim().ifEmpty { status.trim() }

    companion object {
        private const val LIT_OPEN = "\u0001"
        private const val LIT_CLOSE = "\u0002"
        private const val MAX_LITERAL = 8L * 1024 * 1024
        private const val MAX_LINE = 1024 * 1024

        private val LITERAL = Regex("\\{(\\d+)\\+?}$")
        private val EXISTS = Regex("^\\* (\\d+) EXISTS", RegexOption.IGNORE_CASE)
        private val UIDVALIDITY = Regex("\\[UIDVALIDITY (\\d+)]", RegexOption.IGNORE_CASE)
        private val UIDNEXT = Regex("\\[UIDNEXT (\\d+)]", RegexOption.IGNORE_CASE)
        private val LIST = Regex("^\\* (?:LIST|XLIST) \\(([^)]*)\\) (NIL|\"(?:[^\"\\\\]|\\\\.)*\") (.+)$", RegexOption.IGNORE_CASE)
        private val FETCH = Regex("^\\* \\d+ FETCH", RegexOption.IGNORE_CASE)
        private val UID = Regex("\\bUID (\\d+)", RegexOption.IGNORE_CASE)
        private val INTERNALDATE = Regex("INTERNALDATE \"([^\"]+)\"", RegexOption.IGNORE_CASE)
        private val BODY = Regex(
            "BODY\\[](?:<\\d+>)?\\s*(?:$LIT_OPEN(\\d+)$LIT_CLOSE|\"((?:[^\"\\\\]|\\\\.)*)\"|NIL)",
            RegexOption.IGNORE_CASE,
        )

        val IMAP_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH)
        private val INTERNAL = DateTimeFormatter.ofPattern("d-MMM-yyyy HH:mm:ss Z", Locale.ENGLISH)

        fun quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        fun unquote(value: String): String = value.replace("\\\"", "\"").replace("\\\\", "\\")

        /** "02-Nov-2026 10:15:30 +0530" (day may be space-padded) to epoch millis. */
        fun parseInternalDate(value: String): Long? =
            runCatching { ZonedDateTime.parse(value.trim(), INTERNAL).toInstant().toEpochMilli() }.getOrNull()

        /**
         * The folder sent mail goes to: the one the server marks \Sent, else
         * the usual names. Null when there is none to be found.
         */
        fun sentFolder(folders: List<Folder>): Folder? =
            folders.firstOrNull { it.has("\\Sent") }
                ?: folders.firstOrNull { f ->
                    val leaf = f.name.substringAfterLast('/').substringAfterLast('.').lowercase()
                    leaf in setOf("sent", "sent items", "sent mail", "sent messages", "sent-mail", "gesendet", "envoyés")
                }

        /** A folder or Gmail label named "TaskMind": whatever is in it is read. */
        fun labelFolder(folders: List<Folder>): Folder? =
            folders.firstOrNull { f ->
                val leaf = f.name.substringAfterLast('/').substringAfterLast('.')
                leaf.equals("TaskMind", ignoreCase = true) && !f.has("\\Noselect")
            }
    }
}
