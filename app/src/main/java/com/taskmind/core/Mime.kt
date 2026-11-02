package com.taskmind.core

import java.nio.charset.Charset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale

/**
 * Reads an email as it arrives over IMAP: headers, addresses, and the text a
 * person would read.
 *
 * Deliberately small. It understands what real mail uses - folded headers,
 * encoded words, multipart trees, quoted-printable and base64, the common
 * charsets, HTML-only mail - and nothing else. What it cannot read it leaves
 * out rather than guesses at: a message with no readable text is simply a
 * message with an empty body, which the filter then skips.
 *
 * The raw bytes are held as an ISO-8859-1 string, which maps every byte to
 * one char and back again losslessly. Structure (headers, boundaries) is found
 * in that string; each part's content is turned back into bytes and decoded
 * with the part's own charset.
 *
 * Pure: no Android, no network, so every case is a unit test.
 */
object Mime {

    data class Address(val name: String?, val email: String) {
        /** What to call them: their name when the mail gives one, else the address. */
        val display: String get() = name?.takeIf { it.isNotBlank() } ?: email
    }

    class Headers(private val entries: List<Pair<String, String>>) {
        /** The first value of [name], decoded, or null. Case-insensitive. */
        operator fun get(name: String): String? = entries.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
        fun all(name: String): List<String> = entries.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }
        fun has(name: String): Boolean = entries.any { it.first.equals(name, ignoreCase = true) }
        val size: Int get() = entries.size
    }

    data class Message(
        val headers: Headers,
        val from: Address?,
        val to: List<Address>,
        val cc: List<Address>,
        val replyTo: List<Address>,
        val subject: String,
        /** From the Date header; null when it is missing or unreadable. */
        val date: Long?,
        val messageId: String?,
        val inReplyTo: String?,
        val references: List<String>,
        /** The plain-text body, when the mail has one. */
        val plain: String?,
        /** The HTML body, decoded but not converted, when the mail has one. */
        val html: String?,
        /** A meeting invitation (text/calendar) is attached. */
        val hasCalendar: Boolean,
        val attachments: List<String>,
    ) {
        /** The best readable text: plain when present, else the HTML as text. */
        val text: String get() = plain?.takeIf { it.isNotBlank() } ?: html?.let { htmlToText(it) }.orEmpty()
    }

    /** Longest message read. Past this the tail is ignored: it is attachments, not words. */
    const val MAX_BYTES = 256 * 1024
    private const val MAX_DEPTH = 6

    fun parse(raw: ByteArray): Message = parse(String(raw, 0, minOf(raw.size, MAX_BYTES), Charsets.ISO_8859_1))

    /** [raw] must be bytes-as-chars (ISO-8859-1), as from [parse] with a ByteArray. */
    fun parse(raw: String): Message {
        val (headerBlock, body) = splitHeaders(raw)
        val headers = parseHeaders(headerBlock)
        val collected = Collected()
        walk(headers, body, collected, 0)
        return Message(
            headers = headers,
            from = addresses(headers["From"]).firstOrNull(),
            to = addresses(headers["To"]),
            cc = addresses(headers["Cc"]),
            replyTo = addresses(headers["Reply-To"]),
            subject = headers["Subject"]?.trim().orEmpty(),
            date = headers["Date"]?.let(::parseDate),
            messageId = headers["Message-ID"]?.let(::angleId),
            inReplyTo = headers["In-Reply-To"]?.let(::angleId),
            references = headers["References"]?.let { refs ->
                Regex("<[^>]+>").findAll(refs).map { it.value.trim('<', '>') }.toList()
            }.orEmpty(),
            plain = collected.plain,
            html = collected.html,
            hasCalendar = collected.calendar,
            attachments = collected.attachments,
        )
    }

    // ------------------------------------------------------------- headers

    /** Splits at the first empty line. A message with no body is all headers. */
    private fun splitHeaders(raw: String): Pair<String, String> {
        val crlf = raw.indexOf("\r\n\r\n")
        val lf = raw.indexOf("\n\n")
        return when {
            crlf >= 0 && (lf < 0 || crlf <= lf) -> raw.substring(0, crlf) to raw.substring(crlf + 4)
            lf >= 0 -> raw.substring(0, lf) to raw.substring(lf + 2)
            else -> raw to ""
        }
    }

    /** Unfolds continuation lines and decodes every value. */
    fun parseHeaders(block: String): Headers {
        val entries = mutableListOf<Pair<String, String>>()
        var name: String? = null
        val value = StringBuilder()
        fun flush() {
            val n = name ?: return
            entries += n to decodeHeader(value.toString().trim())
            name = null
            value.setLength(0)
        }
        for (line in block.split('\n')) {
            val l = line.trimEnd('\r')
            if (l.isEmpty()) continue
            if ((l[0] == ' ' || l[0] == '\t') && name != null) {
                value.append(' ').append(l.trim())
                continue
            }
            val colon = l.indexOf(':')
            if (colon <= 0) continue
            flush()
            name = l.substring(0, colon).trim()
            value.append(l.substring(colon + 1))
        }
        flush()
        return Headers(entries)
    }

    private val ENCODED_WORD = Regex("=\\?([^?\\s]+)\\?([bBqQ])\\?([^?]*)\\?=")
    private val BETWEEN_WORDS = Regex("(\\?=)\\s+(=\\?)")

    /**
     * RFC 2047 encoded words, and raw UTF-8 in headers (RFC 6532). Whitespace
     * between two encoded words is not part of the text, so it is dropped
     * before decoding.
     */
    fun decodeHeader(value: String): String {
        val utf8 = fromLatin1Bytes(value)
        if (!utf8.contains("=?")) return utf8
        val joined = utf8.replace(BETWEEN_WORDS, "$1$2")
        return ENCODED_WORD.replace(joined) { m ->
            val charset = charset(m.groupValues[1])
            val payload = m.groupValues[3]
            runCatching {
                val bytes = if (m.groupValues[2].equals("B", ignoreCase = true)) {
                    Base64.getMimeDecoder().decode(payload)
                } else {
                    decodeQuotedPrintable(payload.replace('_', ' '), header = true)
                }
                String(bytes, charset)
            }.getOrDefault(m.value)
        }
    }

    /** Header text that is really UTF-8 bytes read as Latin-1, turned back into text. */
    private fun fromLatin1Bytes(value: String): String {
        if (value.none { it.code in 0x80..0xFF }) return value
        val bytes = value.toByteArray(Charsets.ISO_8859_1)
        val decoded = String(bytes, Charsets.UTF_8)
        return if (decoded.contains('�')) value else decoded
    }

    // ----------------------------------------------------------- addresses

    /** "Rahul <r@x.com>, \"Sharma, Priya\" <p@y.com>, bob@z.com" as addresses. */
    fun addresses(value: String?): List<Address> {
        if (value.isNullOrBlank()) return emptyList()
        return splitAddressList(value).mapNotNull(::address)
    }

    private fun splitAddressList(value: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var angle = 0
        var paren = 0
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == '\\' && quoted && i + 1 < value.length -> {
                    current.append(c).append(value[i + 1])
                    i += 2
                    continue
                }
                c == '"' -> quoted = !quoted
                !quoted && c == '<' -> angle++
                !quoted && c == '>' -> angle = (angle - 1).coerceAtLeast(0)
                !quoted && c == '(' -> paren++
                !quoted && c == ')' -> paren = (paren - 1).coerceAtLeast(0)
            }
            // A group ("Team: a@x, b@y;") is flattened: its name is dropped.
            if (!quoted && angle == 0 && paren == 0 && (c == ',' || c == ';')) {
                out += current.toString()
                current.setLength(0)
            } else if (!quoted && angle == 0 && paren == 0 && c == ':' && !current.contains('@')) {
                current.setLength(0)
            } else {
                current.append(c)
            }
            i++
        }
        out += current.toString()
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun address(token: String): Address? {
        val open = token.lastIndexOf('<')
        val close = token.lastIndexOf('>')
        if (open >= 0 && close > open) {
            val email = token.substring(open + 1, close).trim()
            if (!email.contains('@')) return null
            val name = token.substring(0, open).trim().trim('"').replace("\\\"", "\"").trim().takeIf { it.isNotEmpty() }
            return Address(name, email)
        }
        // "bob@z.com (Bob Smith)" - the comment is the name.
        val comment = Regex("\\(([^)]*)\\)").find(token)?.groupValues?.get(1)?.trim()
        val email = token.replace(Regex("\\([^)]*\\)"), "").trim().trim('"')
        if (!email.contains('@') || email.contains(' ')) return null
        return Address(comment?.takeIf { it.isNotEmpty() }, email)
    }

    private fun angleId(value: String): String? {
        val m = Regex("<([^>]+)>").find(value)
        return (m?.groupValues?.get(1) ?: value).trim().takeIf { it.isNotEmpty() }
    }

    // ---------------------------------------------------------------- dates

    private val RFC_DATE = DateTimeFormatter.ofPattern("d MMM yyyy H:mm[:ss] Z", Locale.ENGLISH)

    private val ZONE_NAMES = mapOf(
        "UT" to "+0000", "UTC" to "+0000", "GMT" to "+0000", "Z" to "+0000",
        "EST" to "-0500", "EDT" to "-0400", "CST" to "-0600", "CDT" to "-0500",
        "MST" to "-0700", "MDT" to "-0600", "PST" to "-0800", "PDT" to "-0700",
        "IST" to "+0530",
    )

    /** "Mon, 2 Nov 2026 10:15:30 +0530 (IST)" to epoch millis; null when unreadable. */
    fun parseDate(value: String): Long? {
        var text = value.replace(Regex("\\([^)]*\\)"), " ").trim()
        // The weekday is optional and adds nothing; drop it.
        text = text.replace(Regex("^[A-Za-z]{3},\\s*"), "")
        text = text.replace(Regex("\\s+"), " ")
        val parts = text.split(' ').toMutableList()
        if (parts.size >= 5) {
            ZONE_NAMES[parts.last().uppercase()]?.let { parts[parts.size - 1] = it }
            if (parts.last().length == 5 && (parts.last()[0] == '+' || parts.last()[0] == '-')) {
                text = parts.take(5).joinToString(" ")
            } else {
                return null
            }
        } else if (parts.size == 4) {
            text = (parts + "+0000").joinToString(" ")
        } else {
            return null
        }
        return runCatching { ZonedDateTime.parse(text, RFC_DATE).toInstant().toEpochMilli() }.getOrNull()
    }

    // ------------------------------------------------------------ the tree

    private class Collected {
        var plain: String? = null
        var html: String? = null
        var calendar = false
        val attachments = mutableListOf<String>()
    }

    private data class ContentType(val type: String, val params: Map<String, String>) {
        val charset: String? get() = params["charset"]
        val boundary: String? get() = params["boundary"]
        val isMultipart: Boolean get() = type.startsWith("multipart/")
    }

    private fun contentType(value: String?): ContentType {
        if (value.isNullOrBlank()) return ContentType("text/plain", emptyMap())
        val pieces = splitParams(value)
        val type = pieces.first().trim().lowercase().ifEmpty { "text/plain" }
        return ContentType(type, params(pieces.drop(1)))
    }

    /** Splits "a; b=1; c=\"x;y\"" on semicolons outside quotes. */
    private fun splitParams(value: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        for (c in value) {
            if (c == '"') quoted = !quoted
            if (c == ';' && !quoted) {
                out += current.toString()
                current.setLength(0)
            } else {
                current.append(c)
            }
        }
        out += current.toString()
        return out
    }

    private fun params(pieces: List<String>): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (piece in pieces) {
            val eq = piece.indexOf('=')
            if (eq <= 0) continue
            var key = piece.substring(0, eq).trim().lowercase()
            var value = piece.substring(eq + 1).trim().trim('"')
            // RFC 2231: filename*=utf-8''%E0%A4%... and continuations name*0=.
            if (key.endsWith("*")) {
                key = key.trimEnd('*')
                val quote = value.indexOf("''")
                if (quote >= 0) {
                    val cs = charset(value.substring(0, quote))
                    value = runCatching { String(percentDecode(value.substring(quote + 2)), cs) }.getOrDefault(value)
                }
            }
            key = key.substringBefore('*')
            out[key] = (out[key] ?: "") + value
        }
        return out
    }

    private fun percentDecode(text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%' && i + 2 < text.length && isHex(text[i + 1]) && isHex(text[i + 2])) {
                out.write(text.substring(i + 1, i + 3).toInt(16))
                i += 3
            } else {
                out.write(c.code and 0xFF)
                i++
            }
        }
        return out.toByteArray()
    }

    private fun walk(headers: Headers, body: String, into: Collected, depth: Int) {
        if (depth > MAX_DEPTH) return
        val type = contentType(headers["Content-Type"])
        val disposition = headers["Content-Disposition"]?.lowercase().orEmpty()
        val filename = params(splitParams(headers["Content-Disposition"].orEmpty()).drop(1))["filename"]
            ?: type.params["name"]

        when {
            type.isMultipart -> {
                val boundary = type.boundary ?: return
                // Alternatives and mixed parts alike: every readable part is
                // collected, and [Message.text] prefers the plain one.
                splitMultipart(body, boundary).forEach { (h, b) -> walk(h, b, into, depth + 1) }
            }
            type.type == "message/rfc822" -> {
                // A forwarded message sent as an attachment: read it as text.
                val inner = parse(body)
                val text = inner.text.trim()
                if (text.isNotEmpty()) {
                    val block = buildString {
                        append("---------- Forwarded message ----------\n")
                        inner.from?.let { append("From: ").append(it.display).append(" <").append(it.email).append(">\n") }
                        if (inner.subject.isNotBlank()) append("Subject: ").append(inner.subject).append('\n')
                        append('\n').append(text)
                    }
                    into.plain = listOfNotNull(into.plain?.takeIf { it.isNotBlank() }, block).joinToString("\n\n")
                }
            }
            type.type == "text/calendar" -> into.calendar = true
            disposition.startsWith("attachment") -> filename?.let { into.attachments += decodeHeader(it) }
            type.type == "text/plain" -> {
                val text = decodeBody(body, headers["Content-Transfer-Encoding"], type.charset)
                if (into.plain == null) into.plain = text else if (text.isNotBlank()) into.plain += "\n\n" + text
            }
            type.type == "text/html" -> {
                val text = decodeBody(body, headers["Content-Transfer-Encoding"], type.charset)
                if (into.html == null) into.html = text
            }
            else -> filename?.let { into.attachments += decodeHeader(it) }
        }
    }

    /** The parts between "--boundary" lines, each split into headers and body. */
    private fun splitMultipart(body: String, boundary: String): List<Pair<Headers, String>> {
        val delimiter = "--$boundary"
        val parts = mutableListOf<Pair<Headers, String>>()
        var index = findDelimiter(body, delimiter, 0)
        if (index < 0) return parts
        while (index >= 0) {
            val afterDelimiter = index + delimiter.length
            if (body.startsWith("--", afterDelimiter)) break
            val lineEnd = body.indexOf('\n', afterDelimiter).takeIf { it >= 0 } ?: break
            val next = findDelimiter(body, delimiter, lineEnd + 1)
            val content = if (next < 0) body.substring(lineEnd + 1) else body.substring(lineEnd + 1, next)
            // The line break before the next delimiter belongs to the delimiter.
            val trimmed = content.removeSuffix("\n").removeSuffix("\r")
            val (h, b) = splitHeaders(trimmed)
            // A part with no headers at all starts with its blank line.
            val headers = if (trimmed.startsWith("\r\n") || trimmed.startsWith("\n")) Headers(emptyList()) else parseHeaders(h)
            val partBody = if (headers.size == 0 && (trimmed.startsWith("\r\n") || trimmed.startsWith("\n"))) {
                trimmed.removePrefix("\r\n").removePrefix("\n")
            } else {
                b
            }
            parts += headers to partBody
            if (next < 0) break
            index = next
        }
        return parts
    }

    /**
     * The next "--boundary" that starts a line and is not merely the start of
     * a longer boundary (a nested part's "--abc123" is not "--abc").
     */
    private fun findDelimiter(body: String, delimiter: String, from: Int): Int {
        var at = body.indexOf(delimiter, from)
        while (at >= 0) {
            val startsLine = at == 0 || body[at - 1] == '\n'
            val end = at + delimiter.length
            val ends = end >= body.length || body[end] in "-\r\n \t"
            if (startsLine && ends) return at
            at = body.indexOf(delimiter, at + 1)
        }
        return -1
    }

    private fun decodeBody(body: String, encoding: String?, charsetName: String?): String {
        val bytes = when (encoding?.trim()?.lowercase()) {
            "base64" -> runCatching {
                Base64.getMimeDecoder().decode(body.filter { !it.isWhitespace() }.let(::padBase64))
            }.getOrDefault(ByteArray(0))
            "quoted-printable" -> decodeQuotedPrintable(body, header = false)
            else -> body.toByteArray(Charsets.ISO_8859_1)
        }
        val cs = charsetName?.let(::charset) ?: guessCharset(bytes)
        return String(bytes, cs).replace("\r\n", "\n").replace('\r', '\n')
    }

    /**
     * A body cut short by [MAX_BYTES] is still mostly readable. Missing padding
     * is fine to the decoder; a lone trailing character is not, so it goes.
     */
    private fun padBase64(text: String): String = if (text.length % 4 == 1) text.dropLast(1) else text

    private fun guessCharset(bytes: ByteArray): Charset {
        if (bytes.none { it < 0 }) return Charsets.US_ASCII
        val asUtf8 = String(bytes, Charsets.UTF_8)
        return if (asUtf8.contains('�')) Charset.forName("windows-1252") else Charsets.UTF_8
    }

    fun charset(name: String): Charset {
        val clean = name.trim().trim('"').lowercase()
        val alias = when (clean) {
            "utf8" -> "utf-8"
            "latin1", "latin-1" -> "iso-8859-1"
            "cp1252" -> "windows-1252"
            "ascii", "us-ascii", "ansi_x3.4-1968" -> "us-ascii"
            else -> clean
        }
        return runCatching { Charset.forName(alias) }.getOrDefault(Charsets.UTF_8)
    }

    fun decodeQuotedPrintable(text: String, header: Boolean): ByteArray {
        val out = java.io.ByteArrayOutputStream(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '=') {
                when {
                    // A soft line break joins two lines of one paragraph.
                    !header && i + 1 < text.length && text[i + 1] == '\n' -> { i += 2; continue }
                    !header && i + 2 < text.length && text[i + 1] == '\r' && text[i + 2] == '\n' -> { i += 3; continue }
                    i + 2 < text.length && isHex(text[i + 1]) && isHex(text[i + 2]) -> {
                        out.write(text.substring(i + 1, i + 3).toInt(16))
                        i += 3
                        continue
                    }
                }
            }
            out.write(c.code and 0xFF)
            i++
        }
        return out.toByteArray()
    }

    private fun isHex(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    // ----------------------------------------------------------------- HTML

    private val DROP_BLOCKS = Regex(
        "<(head|style|script|title)[^>]*>.*?</\\1\\s*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val COMMENTS = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val BREAKS = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val BLOCK_ENDS = Regex("</(p|div|tr|h[1-6]|table|blockquote|ul|ol)\\s*>", RegexOption.IGNORE_CASE)
    private val LIST_ITEM = Regex("<li[^>]*>", RegexOption.IGNORE_CASE)
    private val CELL = Regex("</t[dh]\\s*>", RegexOption.IGNORE_CASE)
    private val TAG = Regex("<[^>]+>")
    private val NUMERIC_ENTITY = Regex("&#(x?[0-9a-fA-F]+);")
    private val NAMED_ENTITY = Regex("&([a-zA-Z]+);")

    private val ENTITIES = mapOf(
        "nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“",
        "ndash" to "–", "mdash" to "—", "hellip" to "…", "bull" to "•",
        "copy" to "©", "reg" to "®", "trade" to "™", "euro" to "€",
        "pound" to "£", "middot" to "·", "zwnj" to "", "zwj" to "", "shy" to "",
    )

    /**
     * Markers mail clients put at the start of the quoted earlier message in a
     * reply. Cutting there keeps the reply and drops the history it quotes.
     */
    private val QUOTE_MARKERS = listOf(
        "class=\"gmail_quote",
        "id=\"divRplyFwdMsg\"",
        "id=\"appendonsend\"",
        "class=\"yahoo_quoted",
        "id=\"mail-editor-reference-message-container\"",
        "<blockquote type=\"cite\"",
    )

    /** HTML to readable text. [cutQuotes] drops a reply's quoted history first. */
    fun htmlToText(html: String, cutQuotes: Boolean = false): String {
        var text = html
        if (cutQuotes) {
            val at = QUOTE_MARKERS.mapNotNull { marker ->
                val i = text.indexOf(marker, ignoreCase = true)
                if (i < 0) null else text.lastIndexOf('<', i).takeIf { it >= 0 } ?: i
            }.minOrNull()
            if (at != null && at > 0) text = text.substring(0, at)
        }
        text = COMMENTS.replace(text, "")
        text = DROP_BLOCKS.replace(text, "")
        text = BREAKS.replace(text, "\n")
        text = LIST_ITEM.replace(text, "\n- ")
        text = CELL.replace(text, " ")
        text = BLOCK_ENDS.replace(text, "\n")
        text = TAG.replace(text, "")
        text = NUMERIC_ENTITY.replace(text) { m ->
            val raw = m.groupValues[1]
            val code = if (raw.startsWith("x") || raw.startsWith("X")) raw.drop(1).toIntOrNull(16) else raw.toIntOrNull()
            code?.takeIf { it in 1..0x10FFFF }?.let { String(Character.toChars(it)) } ?: ""
        }
        text = NAMED_ENTITY.replace(text) { m -> ENTITIES[m.groupValues[1].lowercase()] ?: m.value }
        return tidy(text)
    }

    /** Collapses the whitespace HTML and quoted-printable leave behind. */
    fun tidy(text: String): String =
        text.replace(' ', ' ')
            .lines()
            .joinToString("\n") { it.replace(Regex("[ \\t]+"), " ").trim() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
}
