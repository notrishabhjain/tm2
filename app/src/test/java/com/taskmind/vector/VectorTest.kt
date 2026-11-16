package com.taskmind.vector

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader
import kotlin.math.sqrt

class VectorTest {

    private fun unit(vararg xs: Float): FloatArray {
        val n = sqrt(xs.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(xs.size) { xs[it] / n }
    }

    @Test
    fun `packing keeps a vector within a percent`() {
        val v = unit(0.3f, -0.1f, 0.05f, 0.9f, -0.2f, 0f)
        val back = Vectors.unpack(Vectors.pack(v))
        v.indices.forEach { assertEquals(v[it], back[it], 0.01f) }
    }

    @Test
    fun `the index returns the closest first`() {
        val rows = listOf(
            10L to Vectors.pack(unit(1f, 0f, 0f)),
            11L to Vectors.pack(unit(0f, 1f, 0f)),
            12L to Vectors.pack(unit(0.9f, 0.1f, 0f)),
            13L to Vectors.pack(unit(0f, 0f, 1f)),
        )
        val index = Vectors.Index.of(rows, 3)
        val top = index.top(unit(1f, 0.05f, 0f), 2)
        assertEquals(listOf(10L, 12L), top.map { it.first })
        assertTrue(top[0].second > 0.99f)
        assertEquals(4, index.top(unit(1f, 0f, 0f), 10).size)
    }

    @Test
    fun `only matches close to the best are kept`() {
        val scored = listOf(1L to 0.88f, 2L to 0.86f, 3L to 0.83f, 4L to 0.81f)
        assertEquals(listOf(1L, 2L), Vectors.keep(scored).map { it.first })
        assertTrue(Vectors.keep(listOf(1L to 0.78f)).isEmpty())
    }

    @Test
    fun `chats are cut at a pause, by length, and never mixed`() {
        val min = 60_000L
        fun m(id: Long, chat: String, at: Long, body: String) =
            Chunker.Message(id, chat, false, if (id % 2 == 0L) "You" else chat, at, body)
        val chunks = Chunker.chunk(
            listOf(
                m(1, "Priya", 0, "invoice sent?"),
                m(2, "Priya", 2 * min, "yes, this morning"),
                m(3, "Rahul", 3 * min, "lunch?"),
                m(4, "Priya", 90 * min, "thanks!"),
                m(5, "Priya", 91 * min, "   "),
            ),
        )
        assertEquals(3, chunks.size)
        val first = chunks.first { it.chat == "Priya" && it.firstId == 1L }
        assertEquals("Priya: invoice sent?\nYou: yes, this morning", first.text)
        assertEquals(2L, first.lastId)
        assertTrue(chunks.any { it.chat == "Rahul" && it.text == "Rahul: lunch?" })
        val long = Chunker.chunk((1L..40L).map { m(it, "Big", it * 1000, "x".repeat(100)) })
        assertTrue(long.all { it.text.length <= Chunker.MAX_CHARS + 120 })
        assertEquals(40L, long.sumOf { it.lastId - it.firstId + 1 })
    }

    @Test
    fun `the pull reader walks nested json and skips what is not asked for`() {
        val json = JsonPull(StringReader("""{"a":[1,{"b":"x\"yé"}],"model":{"type":"Unigram","vocab":[["▁hi",-1.5],["x",-2]]},"z":null}"""))
        json.beginObject()
        assertEquals("a", json.nextName()); json.skipValue()
        assertEquals("model", json.nextName()); json.beginObject()
        assertEquals("type", json.nextName()); assertEquals("Unigram", json.nextString())
        assertEquals("vocab", json.nextName()); json.beginArray()
        json.beginArray(); assertEquals("▁hi", json.nextString()); assertEquals(-1.5, json.nextDouble(), 0.0); json.endArray()
        json.beginArray(); assertEquals("x", json.nextString()); assertEquals(-2.0, json.nextDouble(), 0.0); json.endArray()
        json.endArray(); json.endObject()
        assertEquals("z", json.nextName()); json.skipValue()
        json.endObject()
    }

    @Test
    fun `the unigram search takes the likeliest split and fuses unknowns`() {
        // 0..3 are the special tokens, as in the real file.
        val pieces = listOf("<s>", "<pad>", "</s>", "<unk>", "▁", "▁pay", "▁payment", "ment", "▁ab", "hi", "a", "b")
        val scores = doubleArrayOf(0.0, 0.0, 0.0, 0.0, -2.0, -3.0, -4.0, -3.5, -5.0, -4.0, -6.0, -6.0)
        val tok = SpmTokenizer(pieces, scores, 3, null)
        // "▁payment" alone (-4) beats "▁pay" + "ment" (-6.5).
        assertArrayEquals(intArrayOf(0, 6, 2), tok.encode("payment"))
        // Two unknown characters in a row become one unknown token.
        assertArrayEquals(intArrayOf(0, 4, 3, 2), tok.encode("ज़ज़"))
        // Words split at spaces; runs of spaces collapse.
        assertEquals(listOf("▁ab", "▁hi"), tok.preTokenize(tok.normalize("ab    hi")))
        assertArrayEquals(intArrayOf(0, 8, 4, 9, 2), tok.encode("ab hi"))
    }
}
