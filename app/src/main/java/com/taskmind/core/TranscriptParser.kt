package com.taskmind.core

/**
 * Spec 11.4 - parsing a speaker-diarised transcript pasted from the Xiaomi
 * HyperOS Recorder.
 *
 * Input looks like:
 *
 *     Speaker 1 00:00:00
 *     haan ji.
 *     Speaker 2 00:00:03
 *     dilli road pe hoon mandi pe theek.
 *
 * Timestamps are noise for extraction and are stripped. Speaker labels are
 * KEPT: "send me the report" means something different depending on who said
 * it, and the call prompt is written to use the labels when they exist.
 *
 * The trap: `5:30 baje milte hain` is speech, not markup. A time is only markup
 * when it is alone on its line, optionally preceded by a speaker label.
 */
object TranscriptParser {

    /** A time alone on its line, optionally preceded by a speaker label. */
    private val MARKUP_LINE = Regex(
        "^\\s*(?:(Speaker\\s*\\d+|\\u0935\\u0915\\u094D\\u0924\\u093E\\s*\\d+)\\s*[:\\-]?)?\\s*\\d{1,2}:\\d{2}(?::\\d{2})?\\s*$",
        RegexOption.IGNORE_CASE,
    )

    /** A speaker label with no timestamp: "Speaker 2:" or "Speaker 2". */
    private val SPEAKER_ONLY_LINE = Regex(
        "^\\s*(Speaker\\s*\\d+|\\u0935\\u0915\\u094D\\u0924\\u093E\\s*\\d+)\\s*[:\\-]?\\s*$",
        RegexOption.IGNORE_CASE,
    )

    /** A speaker label followed on the same line by speech. */
    private val SPEAKER_INLINE = Regex(
        "^\\s*(Speaker\\s*\\d+|\\u0935\\u0915\\u094D\\u0924\\u093E\\s*\\d+)\\s*(?:\\d{1,2}:\\d{2}(?::\\d{2})?)?\\s*[:\\-]\\s*(.+)$",
        RegexOption.IGNORE_CASE,
    )

    data class Turn(val speaker: String?, val text: String)

    data class Parsed(
        val turns: List<Turn>,
        val diarised: Boolean,
        /** Ready to hand to the extraction prompt. */
        val rendered: String,
    )

    fun parse(raw: String): Parsed {
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val turns = mutableListOf<Turn>()
        var current: String? = null
        var sawLabel = false

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            val speakerOnly = SPEAKER_ONLY_LINE.find(trimmed)
            if (speakerOnly != null) {
                current = tidySpeaker(speakerOnly.groupValues[1])
                sawLabel = true
                continue
            }

            if (MARKUP_LINE.matches(trimmed)) {
                val label = MARKUP_LINE.find(trimmed)?.groupValues?.getOrNull(1)
                if (!label.isNullOrBlank()) {
                    current = tidySpeaker(label)
                    sawLabel = true
                }
                // A bare timestamp line carries no speech and no speaker: drop it.
                continue
            }

            val inline = SPEAKER_INLINE.find(trimmed)
            if (inline != null) {
                current = tidySpeaker(inline.groupValues[1])
                sawLabel = true
                append(turns, current, inline.groupValues[2].trim())
                continue
            }

            append(turns, current, trimmed)
        }

        val merged = mergeConsecutive(turns)
        return Parsed(
            turns = merged,
            diarised = sawLabel,
            rendered = render(merged),
        )
    }

    private fun append(turns: MutableList<Turn>, speaker: String?, text: String) {
        if (text.isBlank()) return
        turns.add(Turn(speaker, text))
    }

    private fun tidySpeaker(raw: String): String =
        raw.trim().replace(Regex("\\s+"), " ")

    /** Consecutive turns by the same speaker are one turn. */
    fun mergeConsecutive(turns: List<Turn>): List<Turn> {
        val out = mutableListOf<Turn>()
        for (t in turns) {
            val last = out.lastOrNull()
            if (last != null && last.speaker == t.speaker) {
                out[out.size - 1] = last.copy(text = (last.text + " " + t.text).trim())
            } else {
                out.add(t)
            }
        }
        return out
    }

    fun render(turns: List<Turn>): String = turns.joinToString("\n") { t ->
        if (t.speaker.isNullOrBlank()) t.text else "${t.speaker}: ${t.text}"
    }

    /**
     * Does this clipboard content look like a transcript worth prefilling the
     * import screen with? Deliberately generous - the user can always clear it.
     */
    fun looksLikeTranscript(raw: String): Boolean {
        if (raw.length < 40) return false
        val lines = raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 2) return raw.length > 200
        val markupCount = lines.count { MARKUP_LINE.matches(it) || SPEAKER_ONLY_LINE.matches(it) || SPEAKER_INLINE.matches(it) }
        return markupCount >= 2 || raw.length > 200
    }
}
