package com.taskmind.vector

import java.io.Reader

/**
 * A small pull reader for JSON, for files too large to load as a tree.
 *
 * The tokenizer file is 17 MB, most of it one array of a quarter of a million
 * pieces. Parsed into a tree it costs ten times that on a phone; read as a
 * stream it costs what the pieces themselves do.
 *
 * Only what the tokenizer loader needs: walk objects and arrays, read strings
 * and numbers, skip anything else. Pure Kotlin.
 */
class JsonPull(private val reader: Reader) {

    enum class Token { BEGIN_OBJECT, END_OBJECT, BEGIN_ARRAY, END_ARRAY, NAME, STRING, NUMBER, TRUE, FALSE, NULL, END }

    private val buffer = CharArray(1 shl 16)
    private var length = 0
    private var pos = 0
    private var peeked: Token? = null
    private var text: String = ""

    /** Containers open now; true for an object. Lets a name be told from a string value. */
    private val stack = ArrayList<Boolean>()
    private val expectName = ArrayList<Boolean>()

    private fun fill(): Boolean {
        if (pos < length) return true
        length = reader.read(buffer)
        pos = 0
        return length > 0
    }

    private fun next(): Char {
        if (!fill()) throw IllegalStateException("JSON ends early")
        return buffer[pos++]
    }

    private fun skipSpace(): Char? {
        while (fill()) {
            val c = buffer[pos]
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == ',' || c == ':') pos++ else return c
        }
        return null
    }

    fun peek(): Token {
        peeked?.let { return it }
        val c = skipSpace() ?: return Token.END.also { peeked = it }
        val token = when (c) {
            '{' -> { pos++; stack += true; expectName += true; Token.BEGIN_OBJECT }
            '}' -> { pos++; stack.removeAt(stack.size - 1); expectName.removeAt(expectName.size - 1); afterValue(); Token.END_OBJECT }
            '[' -> { pos++; stack += false; expectName += false; Token.BEGIN_ARRAY }
            ']' -> { pos++; stack.removeAt(stack.size - 1); expectName.removeAt(expectName.size - 1); afterValue(); Token.END_ARRAY }
            '"' -> {
                pos++
                text = readString()
                if (stack.isNotEmpty() && stack.last() && expectName.last()) {
                    expectName[expectName.size - 1] = false
                    Token.NAME
                } else {
                    afterValue(); Token.STRING
                }
            }
            't' -> { word("true"); afterValue(); Token.TRUE }
            'f' -> { word("false"); afterValue(); Token.FALSE }
            'n' -> { word("null"); afterValue(); Token.NULL }
            else -> { text = readNumber(); afterValue(); Token.NUMBER }
        }
        peeked = token
        return token
    }

    /** After a value inside an object, the next string is a name again. */
    private fun afterValue() {
        if (stack.isNotEmpty() && stack.last()) expectName[expectName.size - 1] = true
    }

    private fun take(expected: Token): String {
        val t = peek()
        if (t != expected) throw IllegalStateException("expected $expected, found $t")
        peeked = null
        return text
    }

    fun beginObject() { take(Token.BEGIN_OBJECT) }
    fun endObject() { take(Token.END_OBJECT) }
    fun beginArray() { take(Token.BEGIN_ARRAY) }
    fun endArray() { take(Token.END_ARRAY) }
    fun nextName(): String = take(Token.NAME)
    fun nextString(): String = take(Token.STRING)
    fun nextDouble(): Double = take(Token.NUMBER).toDouble()
    fun nextInt(): Int = take(Token.NUMBER).toDouble().toInt()
    fun hasNext(): Boolean = peek().let { it != Token.END_OBJECT && it != Token.END_ARRAY && it != Token.END }

    /** Skips the next value, whatever it is, containers included. */
    fun skipValue() {
        var depth = 0
        do {
            when (peek()) {
                Token.BEGIN_OBJECT, Token.BEGIN_ARRAY -> depth++
                Token.END_OBJECT, Token.END_ARRAY -> depth--
                Token.END -> throw IllegalStateException("JSON ends early")
                else -> Unit
            }
            peeked = null
        } while (depth > 0)
    }

    private fun word(w: String) {
        for (ch in w) if (next() != ch) throw IllegalStateException("bad literal")
    }

    private fun readNumber(): String {
        val sb = StringBuilder()
        while (fill()) {
            val c = buffer[pos]
            if (c.isDigit() || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') { sb.append(c); pos++ } else break
        }
        if (sb.isEmpty()) throw IllegalStateException("unexpected character")
        return sb.toString()
    }

    private fun readString(): String {
        val sb = StringBuilder()
        while (true) {
            val c = next()
            when (c) {
                '"' -> return sb.toString()
                '\\' -> when (val e = next()) {
                    '"', '\\', '/' -> sb.append(e)
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        var v = 0
                        repeat(4) { v = v * 16 + Character.digit(next(), 16) }
                        sb.append(v.toChar())
                    }
                    else -> throw IllegalStateException("bad escape")
                }
                else -> sb.append(c)
            }
        }
    }
}
