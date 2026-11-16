package com.taskmind.vector

import java.io.Reader
import java.text.BreakIterator
import java.util.Base64

/**
 * The tokenizer of multilingual-e5-small: SentencePiece's unigram model, as
 * Hugging Face's tokenizer runs it, written out so it runs on the phone with
 * no native library.
 *
 * Four steps, in the order the tokenizer file names them:
 *
 *  1. Normalise with SentencePiece's precompiled table (full-width letters to
 *     plain, ligatures apart, and so on), one grapheme at a time.
 *  2. Collapse runs of spaces to one.
 *  3. Mark word starts with "▁" and split before each.
 *  4. For each word, the most probable split into the model's pieces
 *     (Viterbi over the piece scores); characters no piece covers become one
 *     unknown token per run.
 *
 * Checked token for token against Hugging Face's own tokenizer on English,
 * Hinglish, Devanagari, emoji and full-width text before it shipped.
 */
class SpmTokenizer(
    pieces: List<String>,
    private val scores: DoubleArray,
    private val unkId: Int,
    charsmap: ByteArray?,
) {
    private val ids = HashMap<String, Int>(pieces.size * 2)
    private val maxPieceLength: Int
    private val unkScore: Double
    private val trie: IntArray
    private val normalized: ByteArray

    init {
        var longest = 1
        pieces.forEachIndexed { i, p ->
            if (!ids.containsKey(p)) ids[p] = i
            longest = maxOf(longest, p.codePointCount(0, p.length))
        }
        maxPieceLength = longest
        unkScore = (scores.minOrNull() ?: 0.0) - UNK_PENALTY
        if (charsmap != null && charsmap.size >= 4) {
            val trieBytes = (charsmap[0].toInt() and 0xFF) or ((charsmap[1].toInt() and 0xFF) shl 8) or
                ((charsmap[2].toInt() and 0xFF) shl 16) or ((charsmap[3].toInt() and 0xFF) shl 24)
            trie = IntArray(trieBytes / 4) { i ->
                val o = 4 + 4 * i
                (charsmap[o].toInt() and 0xFF) or ((charsmap[o + 1].toInt() and 0xFF) shl 8) or
                    ((charsmap[o + 2].toInt() and 0xFF) shl 16) or ((charsmap[o + 3].toInt() and 0xFF) shl 24)
            }
            normalized = charsmap.copyOfRange(4 + trieBytes, charsmap.size)
        } else {
            trie = IntArray(0)
            normalized = ByteArray(0)
        }
    }

    /** Token ids for [text], with the start and end markers, at most [maxTokens] in all. */
    fun encode(text: String, maxTokens: Int = 512): IntArray {
        val out = ArrayList<Int>()
        out += BOS
        for (word in preTokenize(normalize(text))) {
            for (id in viterbi(word)) {
                if (out.size >= maxTokens - 1) break
                out += id
            }
        }
        out += EOS
        return out.toIntArray()
    }

    // ------------------------------------------------------ normalisation

    internal fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        if (trie.isEmpty()) {
            sb.append(text)
        } else {
            val it = BreakIterator.getCharacterInstance()
            it.setText(text)
            var start = it.first()
            var end = it.next()
            while (end != BreakIterator.DONE) {
                val grapheme = text.substring(start, end)
                val whole = grapheme.toByteArray(Charsets.UTF_8)
                val replaced = if (whole.size < 6) transform(whole) else null
                if (replaced != null) {
                    sb.append(replaced)
                } else {
                    var i = 0
                    while (i < grapheme.length) {
                        val cp = grapheme.codePointAt(i)
                        val ch = String(Character.toChars(cp))
                        sb.append(transform(ch.toByteArray(Charsets.UTF_8)) ?: ch)
                        i += Character.charCount(cp)
                    }
                }
                start = end
                end = it.next()
            }
        }
        // " {2,}" -> " "
        return MULTI_SPACE.replace(sb, " ")
    }

    /** The table's replacement for the shortest prefix of [key] it has one for, or null. */
    private fun transform(key: ByteArray): String? {
        var nodePos = 0
        var unit = trie[nodePos]
        nodePos = nodePos xor offset(unit)
        for (b in key) {
            val c = b.toInt() and 0xFF
            if (c == 0) break
            nodePos = nodePos xor c
            if (nodePos < 0 || nodePos >= trie.size) return null
            unit = trie[nodePos]
            if (label(unit) != c) return null
            nodePos = nodePos xor offset(unit)
            if (hasLeaf(unit)) {
                val at = value(trie[nodePos])
                var stop = at
                while (stop < normalized.size && normalized[stop].toInt() != 0) stop++
                return String(normalized, at, stop - at, Charsets.UTF_8)
            }
        }
        return null
    }

    private fun hasLeaf(unit: Int) = (unit ushr 8) and 1 == 1
    private fun value(unit: Int) = unit and 0x7FFFFFFF
    private fun label(unit: Int) = unit and (0x80000000.toInt() or 0xFF)
    private fun offset(unit: Int) = (unit ushr 10) shl ((unit and (1 shl 9)) ushr 6)

    // ---------------------------------------------------- word splitting

    /** Spaces become "▁", one is put in front, and each "▁" starts a new word. */
    internal fun preTokenize(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        var s = text.replace(' ', META)
        if (!s.startsWith(META)) s = META + s
        val words = ArrayList<String>()
        var start = 0
        for (i in 1 until s.length) {
            if (s[i] == META) {
                words += s.substring(start, i)
                start = i
            }
        }
        words += s.substring(start)
        return words
    }

    // ------------------------------------------------------------ viterbi

    internal fun viterbi(word: String): List<Int> {
        val cps = word.codePoints().toArray()
        val n = cps.size
        if (n == 0) return emptyList()
        val best = DoubleArray(n + 1)
        val from = IntArray(n + 1) { -1 }
        val id = IntArray(n + 1) { -1 }
        from[0] = 0
        for (s in 0 until n) {
            if (from[s] < 0) continue
            val base = best[s]
            var single = false
            val sb = StringBuilder()
            for (len in 1..minOf(maxPieceLength, n - s)) {
                sb.appendCodePoint(cps[s + len - 1])
                val pid = ids[sb.toString()] ?: continue
                val e = s + len
                val cand = scores[pid] + base
                if (from[e] < 0 || cand > best[e]) {
                    best[e] = cand
                    from[e] = s
                    id[e] = pid
                }
                if (len == 1) single = true
            }
            if (!single) {
                val e = s + 1
                val cand = unkScore + base
                if (from[e] < 0 || cand > best[e]) {
                    best[e] = cand
                    from[e] = s
                    id[e] = unkId
                }
            }
        }
        // Back from the end; a run of unknowns is one token.
        val out = ArrayList<Int>()
        var e = n
        var inUnk = false
        while (e > 0) {
            val pid = id[e]
            if (pid == unkId) {
                if (!inUnk) out += unkId
                inUnk = true
            } else {
                out += pid
                inUnk = false
            }
            e = from[e]
        }
        out.reverse()
        return out
    }

    companion object {
        const val BOS = 0
        const val EOS = 2
        const val PAD = 1
        private const val META = '▁'
        private const val UNK_PENALTY = 10.0
        private val MULTI_SPACE = Regex(" {2,}")

        /** Reads a Hugging Face tokenizer.json of a unigram model, streaming. */
        fun read(reader: Reader): SpmTokenizer {
            val json = JsonPull(reader)
            var charsmap: ByteArray? = null
            val pieces = ArrayList<String>(260_000)
            val scores = ArrayList<Double>(260_000)
            var unk = 3

            fun normalizer() {
                // {"type":"Sequence","normalizers":[{"type":"Precompiled","precompiled_charsmap":"..."}, ...]}
                if (json.peek() != JsonPull.Token.BEGIN_OBJECT) { json.skipValue(); return }
                json.beginObject()
                while (json.hasNext()) {
                    when (json.nextName()) {
                        "precompiled_charsmap" -> charsmap = Base64.getDecoder().decode(json.nextString())
                        "normalizers" -> {
                            json.beginArray()
                            while (json.hasNext()) normalizer()
                            json.endArray()
                        }
                        else -> json.skipValue()
                    }
                }
                json.endObject()
            }

            json.beginObject()
            while (json.hasNext()) {
                when (json.nextName()) {
                    "normalizer" -> normalizer()
                    "model" -> {
                        json.beginObject()
                        while (json.hasNext()) {
                            when (json.nextName()) {
                                "type" -> {
                                    val type = json.nextString()
                                    require(type == "Unigram") { "Not a unigram tokenizer: $type" }
                                }
                                "unk_id" -> unk = json.nextInt()
                                "vocab" -> {
                                    json.beginArray()
                                    while (json.hasNext()) {
                                        json.beginArray()
                                        pieces += json.nextString()
                                        scores += json.nextDouble()
                                        json.endArray()
                                    }
                                    json.endArray()
                                }
                                else -> json.skipValue()
                            }
                        }
                        json.endObject()
                    }
                    else -> json.skipValue()
                }
            }
            require(pieces.isNotEmpty()) { "The tokenizer file has no vocabulary." }
            return SpmTokenizer(pieces, scores.toDoubleArray(), unk, charsmap)
        }
    }
}
