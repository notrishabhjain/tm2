package com.taskmind

import com.taskmind.core.TranscriptParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 11.4 - the Xiaomi HyperAI clipboard import format. */
class TranscriptParserTest {

    private val sample = """
        Speaker 1 00:00:00
        हाँ जी।
        Speaker 2 00:00:03
        दिल्ली रोड पे हूँ मंडी पे ठीक।
        Speaker 1 00:00:09
        theek hai kal tak invoice bhej dena
    """.trimIndent()

    @Test
    fun `speaker labels are kept and timestamps stripped`() {
        val parsed = TranscriptParser.parse(sample)
        assertTrue(parsed.diarised)
        assertEquals(3, parsed.turns.size)
        assertEquals("Speaker 1", parsed.turns[0].speaker)
        assertEquals("Speaker 2", parsed.turns[1].speaker)
        assertFalse("timestamps must not survive", parsed.rendered.contains("00:00:03"))
        assertTrue(parsed.rendered.contains("Speaker 1:"))
        assertTrue(parsed.rendered.contains("kal tak invoice bhej dena"))
    }

    @Test
    fun `a spoken time is speech not markup`() {
        // The trap: "5:30 baje milte hain" is a sentence, not a timestamp line.
        val input = """
            Speaker 1 00:00:00
            5:30 baje milte hain
            Speaker 2 00:00:05
            theek hai
        """.trimIndent()
        val parsed = TranscriptParser.parse(input)
        assertTrue("spoken time must be preserved", parsed.rendered.contains("5:30 baje milte hain"))
    }

    @Test
    fun `a bare timestamp line is dropped`() {
        val input = """
            00:00:00
            haan ji bataiye
            00:12
            kal tak bhej dunga
        """.trimIndent()
        val parsed = TranscriptParser.parse(input)
        assertFalse(parsed.rendered.contains("00:00:00"))
        assertFalse(parsed.rendered.contains("00:12"))
        assertTrue(parsed.rendered.contains("haan ji bataiye"))
        assertTrue(parsed.rendered.contains("kal tak bhej dunga"))
    }

    @Test
    fun `consecutive turns by the same speaker are merged`() {
        val input = """
            Speaker 1 00:00:00
            haan ji
            Speaker 1 00:00:02
            bataiye kya kaam hai
            Speaker 2 00:00:05
            kal tak report chahiye
        """.trimIndent()
        val parsed = TranscriptParser.parse(input)
        assertEquals(2, parsed.turns.size)
        assertEquals("haan ji bataiye kya kaam hai", parsed.turns[0].text)
    }

    @Test
    fun `inline speaker labels are handled`() {
        val input = """
            Speaker 1: haan ji bataiye
            Speaker 2: kal tak invoice bhej dijiye
        """.trimIndent()
        val parsed = TranscriptParser.parse(input)
        assertTrue(parsed.diarised)
        assertEquals(2, parsed.turns.size)
        assertEquals("kal tak invoice bhej dijiye", parsed.turns[1].text)
    }

    @Test
    fun `unparseable input is still accepted as plain text`() {
        val plain = "haan ji maine unse baat kar li hai, kal tak payment ho jayega, aur invoice bhi bhej dunga"
        val parsed = TranscriptParser.parse(plain)
        assertFalse(parsed.diarised)
        assertEquals(1, parsed.turns.size)
        assertEquals(plain, parsed.rendered)
    }

    @Test
    fun `clipboard heuristic recognises a transcript and ignores a short note`() {
        assertTrue(TranscriptParser.looksLikeTranscript(sample))
        assertFalse(TranscriptParser.looksLikeTranscript("call sharma ji"))
    }

    @Test
    fun `devanagari speaker labels are recognised`() {
        val input = "वक्ता 1 00:00:00\nहाँ जी\nवक्ता 2 00:00:04\nकल तक भेज दूँगा"
        val parsed = TranscriptParser.parse(input)
        assertTrue(parsed.diarised)
        assertEquals(2, parsed.turns.size)
    }
}
