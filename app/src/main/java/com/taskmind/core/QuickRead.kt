package com.taskmind.core

/**
 * The phone's own first read of a message: a clear ask or a clear promise,
 * spotted in a few milliseconds with no model call, so the task and its
 * notification arrive while the message is still on the screen.
 *
 * The model reads the same message seconds later and has the last word: it
 * rewrites the task in better words, or takes it back when it is not one. So
 * this is tuned to be right when it speaks rather than to catch everything.
 * A message it passes over is still read by the model a moment later; one it
 * gets wrong costs a notification that disappears again.
 *
 * Rules, not a model: English, Hinglish and Hindi requests ("can you send the
 * PO by 5", "kal tak bhej dena", "भेज दो") and promises ("I'll send it
 * tomorrow", "bhej dunga"), with the date read by [QuickAdd]. Pure Kotlin,
 * tested on the JVM.
 */
object QuickRead {

    /** What tasks made this way carry as their origin until the model has read them. */
    const val ORIGIN = "device"

    enum class Kind {
        /** The sender asks the user to do something. */
        ASK,

        /** The sender promises the user something: a task to wait on. */
        PROMISE,
    }

    data class Result(
        val kind: Kind,
        val title: String,
        /** The sentence it came from, exactly as it appears in the message. */
        val evidence: String,
        val dueAt: Long?,
        val urgent: Boolean,
        val confidence: Double,
    )

    /**
     * Reads [text] from [sender]. [myNames] are what people call the user; in
     * a group ([isGroup]) only a message that names the user, or the whole
     * group, can be an ask - and a promise only when it names the user.
     * [now] is when the message arrived: dates resolve against it.
     */
    fun read(
        text: String,
        sender: String,
        myNames: Collection<String>,
        isGroup: Boolean,
        now: Long,
    ): Result? {
        val trimmed = text.trim()
        if (trimmed.length < MIN_CHARS || trimmed.length > MAX_CHARS) return null
        val whole = normalise(trimmed)
        if (WHOLE_MESSAGE_SKIP.containsMatchIn(whole)) return null

        val mention = Mention.scan(trimmed, myNames)
        if (mention.namesSomeoneElse) return null
        if (isGroup && !mention.forMe) return null

        return segments(trimmed)
            .mapNotNull { segment -> readSegment(segment, sender, myNames, isGroup, mention.mentionsMe, now) }
            .maxByOrNull { it.confidence }
    }

    // ---------------------------------------------------------------- reading

    private fun readSegment(
        segment: String,
        sender: String,
        myNames: Collection<String>,
        isGroup: Boolean,
        namesMe: Boolean,
        now: Long,
    ): Result? {
        if (segment.length < EvidenceMatcher.MIN_EVIDENCE_CHARS) return null
        val lower = normalise(segment)
        if (SKIP.containsMatchIn(lower)) return null
        // "Thanks for the deck, please also share the excel" still asks for
        // something; a bare "thanks, got it" does not.
        val asks = (POLITE.containsMatchIn(lower) && VERBS.containsMatchIn(lower)) || REQUEST_FORMS.containsMatchIn(lower)
        if (PLEASANTRIES.containsMatchIn(lower) && !asks) return null

        val dated = QuickAdd.parse(segment, now)
        val urgent = URGENT.containsMatchIn(lower)
        val pressing = dated.dueAt != null || urgent

        // A promise first: "I'll send it" contains a verb a request would also
        // match, and it is the sender's job, not the user's.
        promise(segment, lower, sender, isGroup, namesMe, pressing, now)?.let { (title, confidence) ->
            return Result(Kind.PROMISE, title, segment, dated.dueAt, urgent, confidence)
        }

        val confidence = askConfidence(lower, myNames, isGroup, pressing) ?: return null
        val title = askTitle(segment, sender, myNames, now) ?: return null
        return Result(Kind.ASK, title, segment, dated.dueAt, urgent, confidence)
    }

    /** How sure an ask is, or null when this sentence is not one. */
    private fun askConfidence(lower: String, myNames: Collection<String>, isGroup: Boolean, pressing: Boolean): Double? {
        val polite = POLITE.containsMatchIn(lower)
        val verb = VERBS.containsMatchIn(lower)
        val hinglish = REQUEST_FORMS.containsMatchIn(lower)
        val imperative = startsWithImperative(lower, myNames)
        return when {
            hinglish && pressing -> 0.88
            hinglish -> if (isGroup) 0.82 else 0.84
            polite && verb && pressing -> 0.88
            polite && verb -> 0.85
            imperative && pressing -> 0.85
            imperative && !isGroup && wordCount(lower) >= 3 -> 0.82
            else -> null
        }
    }

    /** A promise's title and confidence, or null when the sentence is not one. */
    private fun promise(
        segment: String,
        lower: String,
        sender: String,
        isGroup: Boolean,
        namesMe: Boolean,
        pressing: Boolean,
        now: Long,
    ): Pair<String, Double>? {
        if (isGroup && !namesMe) return null
        val who = firstName(sender)

        PROMISE_EN.find(segment)?.let { m ->
            // "I'll <verb> <what>": the words after the promise are the task.
            val rest = segment.substring(m.range.last + 1).trim()
            val action = cleanTail(QuickAdd.parse(rest, now).let { if (it.dueAt != null) it.title else rest })
            val first = action.split(' ').firstOrNull()?.lowercase() ?: return null
            if (first !in VERB_WORDS || wordCount(action) < 2) return null
            val title = if (who != null) "$who to $action" else "Waiting on: $action"
            return clip(title) to if (pressing) 0.86 else 0.82
        }

        if (PROMISE_HI.containsMatchIn(lower)) {
            if (!pressing && isGroup) return null
            val words = cleanTail(QuickAdd.parse(segment, now).let { if (it.dueAt != null) it.title else segment })
                .replace(TAK, " ").replace(SPACES, " ").trim()
                .let { stripFront(it, emptyList()) }
            if (wordCount(words) < 2) return null
            val title = if (who != null) "Waiting on $who: $words" else "Waiting on: $words"
            return clip(title) to if (pressing) 0.84 else 0.80
        }
        return null
    }

    // ------------------------------------------------------------------ titles

    /**
     * The ask as a task: greetings, the user's name and "can you please"
     * taken off the front, the date words out, "me" turned into the sender.
     * "Rishabh, can you send me the PO by 5?" from Priya -> "Send Priya the PO".
     */
    private fun askTitle(segment: String, sender: String, myNames: Collection<String>, now: Long): String? {
        var title = stripFront(askingClause(segment), myNames)
        val who = firstName(sender)
        if (who != null) {
            title = ME_AFTER_VERB.replace(title) { m ->
                if (m.groupValues[1].lowercase() in VERB_WORDS) "${m.groupValues[1]} $who" else m.value
            }
        }
        val dated = QuickAdd.parse(title, now)
        if (dated.dueAt != null) title = dated.title.replace(TAK, " ")
        title = cleanTail(title.replace(SPACES, " ").trim())
        if (wordCount(title) < 2) return null
        title = title.replaceFirstChar { it.uppercase() }
        if (who != null && !title.lowercase().contains(who.lowercase())) title = "$title ($who)"
        return clip(title)
    }

    /** Takes greetings, names and request words off the front, as many as there are. */
    private fun stripFront(segment: String, myNames: Collection<String>): String {
        val names = myNames.map { it.trim() }.filter { it.length >= 2 }
        val ownName = if (names.isEmpty()) null else Regex(
            "^(?:@?(?:" + names.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + "))" +
                "(?:\\s+(?:bhai|ji|sir|bro|da|ma'?am|madam))?[\\s,:;!.\\-]+",
            RegexOption.IGNORE_CASE,
        )
        var out = segment.trim()
        while (true) {
            val before = out
            out = out.replace(LEADING_NOISE, "")
            if (ownName != null) out = out.replace(ownName, "")
            out = out.replace(LEADING_MODAL, "")
            out = out.trim()
            if (out == before) return out
        }
    }

    /**
     * The clause that does the asking: "Thanks for the deck, please also
     * share the excel" -> "please also share the excel". Only the title is
     * cut; the evidence stays the whole sentence, exactly as written.
     */
    private fun askingClause(segment: String): String {
        val lower = segment.lowercase()
        // Index arithmetic below assumes lowercasing kept every character in
        // place, which is true of everything but a few exotic letters.
        if (lower.length != segment.length) return segment
        val at = listOfNotNull(POLITE.find(lower)?.range?.first, REQUEST_FORMS.find(lower)?.range?.first).minOrNull()
            ?: return segment
        if (at == 0) return segment
        val cut = CLAUSE_BREAK.findAll(lower.substring(0, at)).lastOrNull() ?: return segment
        return segment.substring(cut.range.last + 1).trim().ifEmpty { segment }
    }

    private fun startsWithImperative(lower: String, myNames: Collection<String>): Boolean {
        val front = stripFront(lower, myNames).lowercase()
        val first = front.split(' ', ',').firstOrNull { it.isNotBlank() } ?: return false
        return first in STRONG_VERBS
    }

    private fun cleanTail(text: String): String =
        text.trim().replace(TRAILING, "").trim()

    private fun clip(title: String): String {
        if (title.length <= MAX_TITLE) return title
        val cut = title.take(MAX_TITLE).substringBeforeLast(' ')
        return cut.trimEnd(',', ';', ':', '-', ' ') + "…"
    }

    private fun firstName(sender: String): String? {
        val name = sender.trim()
        if (name.isEmpty() || name.equals("unknown", ignoreCase = true)) return null
        // A number is no name to put in a title.
        if (name.count { it.isDigit() } >= 6) return null
        return name.split(' ').first().takeIf { it.isNotBlank() }
    }

    // -------------------------------------------------------------- plumbing

    /** The message's sentences and lines, each exactly as written. */
    private fun segments(text: String): List<String> =
        text.split(SENTENCE).map { it.trim() }.filter { it.isNotEmpty() }

    private fun normalise(s: String): String =
        s.lowercase().replace('’', '\'').replace('‘', '\'').replace(SPACES, " ")

    private fun wordCount(s: String): Int = s.trim().split(SPACES).count { it.any(Char::isLetterOrDigit) }

    /** A phrase list as one regex with word edges that hold in Devanagari too. */
    private fun phrases(vararg items: String): Regex = Regex(
        "(?<![\\p{L}\\p{M}\\p{N}])(?:" +
            items.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } +
            ")(?![\\p{L}\\p{M}\\p{N}])",
    )

    private const val MIN_CHARS = 8
    private const val MAX_CHARS = 400
    private const val MAX_TITLE = 90

    private val SPACES = Regex("\\s+")
    private val CLAUSE_BREAK = Regex("[,;:]\\s*|\\s+-\\s+|\\s(?:but|and|so|also|par|aur)\\s")
    private val SENTENCE = Regex("(?<=[.!?।])\\s+|\\n+")
    private val TAK = Regex("(?<![\\p{L}\\p{M}\\p{N}])tak(?![\\p{L}\\p{M}\\p{N}])", RegexOption.IGNORE_CASE)
    private val TRAILING = Regex(
        "[\\s,;:.!?\\-]*(?:(?<![\\p{L}])(?:please|pls|plz))?[^\\p{L}\\p{M}\\p{N})\\]]*$",
        RegexOption.IGNORE_CASE,
    )
    private val ME_AFTER_VERB = Regex("^(\\p{L}+)\\s+me\\b", RegexOption.IGNORE_CASE)

    private val LEADING_NOISE = Regex(
        "^(?:hi+|hello|hey+|dear|yo|ok(?:ay)?|so|and|bhai|bro|sir|madam|ma'?am|ji|boss|team|guys|all|everyone|" +
            "folks|haan|han|ha|theek hai|thik hai|acha|accha|achha|main|mai|hum|@\\S+)(?:[\\s,:;!.\\-]+|$)",
        RegexOption.IGNORE_CASE,
    )

    private val LEADING_MODAL = Regex(
        "^(?:please|pls|plz+|kindly|can you|could you|would you|will you|can u|could u|would u|will u|" +
            "can't you|cant you|i need you to|need you to|i want you to|want you to|make sure (?:you |to )?|" +
            "don't forget to|dont forget to|do not forget to|remember to|request you to|just|also)(?![\\p{L}\\p{N}])[\\s,]*",
        RegexOption.IGNORE_CASE,
    )

    /** Asks of the user that carry no verb of their own. */
    private val POLITE = phrases(
        "please", "pls", "plz", "plzz", "plss", "kindly", "can you", "could you", "would you", "will you",
        "can u", "could u", "would u", "will u", "can't you", "need you to", "want you to", "make sure",
        "don't forget", "dont forget", "do not forget", "remember to", "request you", "requesting you",
        "let me know", "zaroor", "zarur", "yaad se", "yaad rakhna", "bhoolna mat", "bhool mat",
        "कृपया", "ज़रूर", "जरूर", "याद से",
    )

    /** Hinglish and Hindi request forms: the verb and the asking in one. */
    private val REQUEST_FORMS = phrases(
        "bhej do", "bhej dena", "bhej de", "bhejo", "bhejna", "bhejiye", "bhej dijiye",
        "kar do", "kar dena", "kar de", "kar lena", "kar lo", "kariye", "kar dijiye", "karo na",
        "de do", "de dena", "de dijiye", "bata do", "bata dena", "batao", "bataiye", "bata dijiye",
        "dekh lo", "dekh lena", "dekh lijiye", "le aao", "le aana", "le lena", "leke aana",
        "mil lo", "mil lena", "yaad dila dena", "yaad dilana", "call karo", "phone karo", "baat karo",
        "भेज दो", "भेज देना", "भेजो", "भेजना", "भेजिए", "कर दो", "कर देना", "कीजिए", "दे दो", "दे देना",
        "बता दो", "बता देना", "बताओ", "बताइए", "देख लो", "देख लेना", "ले आना", "ले आओ", "मिल लो",
    )

    /** Verbs a task starts with; an ask needs one. */
    private val VERB_WORDS: Set<String> = setOf(
        "send", "share", "forward", "mail", "email", "call", "ring", "phone", "ping", "text", "message",
        "reply", "respond", "revert", "confirm", "check", "review", "verify", "approve", "sign", "pay",
        "transfer", "deposit", "recharge", "book", "schedule", "reschedule", "cancel", "arrange", "organise",
        "organize", "prepare", "draft", "write", "update", "fix", "complete", "finish", "submit", "file",
        "upload", "download", "print", "scan", "renew", "order", "buy", "purchase", "get", "collect", "pick",
        "bring", "drop", "deliver", "return", "meet", "join", "attend", "visit", "remind", "follow", "inform",
        "tell", "ask", "let", "look", "find", "set", "connect", "help", "coordinate", "close", "clear",
        "settle", "process", "raise", "create", "make", "do", "keep", "give", "take", "come", "ensure",
    )

    /** Verbs specific enough to make a bare imperative an ask ("Call the vendor"). */
    private val STRONG_VERBS: Set<String> = setOf(
        "send", "share", "forward", "mail", "email", "call", "ping", "text", "reply", "revert", "confirm",
        "check", "review", "verify", "approve", "sign", "pay", "transfer", "deposit", "recharge", "book",
        "schedule", "reschedule", "cancel", "arrange", "prepare", "draft", "update", "fix", "complete",
        "finish", "submit", "upload", "print", "scan", "renew", "order", "buy", "collect", "pick", "bring",
        "deliver", "return", "remind", "inform", "tell", "ask", "coordinate", "settle", "raise", "close",
    )

    private val VERBS = Regex(
        "(?<![\\p{L}\\p{N}])(?:" + VERB_WORDS.sortedByDescending { it.length }.joinToString("|") + ")(?![\\p{L}\\p{N}])",
    )

    /**
     * "I'll ..." - the sender's own promise, with the task after it. Matched
     * on the message as written (both apostrophes), so the words after it can
     * be cut out of the original.
     */
    private val PROMISE_EN = Regex(
        "(?<![\\p{L}\\p{N}])(?:i['’]ll|i will|i shall|i['’]m going to|i am going to|we['’]ll|we will|will)\\s+(?:be\\s+)?",
        RegexOption.IGNORE_CASE,
    )

    /** Hinglish and Hindi promises: "bhej dunga", "kar deta hu", "भेज दूंगा". */
    private val PROMISE_HI = phrases(
        "kar dunga", "kar dungi", "kar denge", "kar deta hu", "kar deti hu", "kar deta hoon", "kar deti hoon",
        "bhej dunga", "bhej dungi", "bhej denge", "bhej deta hu", "bhej deti hu", "bhejta hu", "bhejti hu",
        "bhejta hoon", "bhejti hoon", "bhejunga", "bhejungi", "de dunga", "de dungi", "de denge",
        "bata dunga", "bata dungi", "bata denge", "batata hu", "batati hu", "call karta hu", "call karti hu",
        "call karunga", "call karungi", "dekh lunga", "dekh lungi", "dekh leta hu", "karunga", "karungi",
        "भेज दूंगा", "भेज दूँगा", "भेज दूंगी", "कर दूंगा", "कर दूँगा", "कर दूंगी", "बता दूंगा", "बता दूँगा",
        "भेजता हूँ", "भेजता हूं", "करता हूँ", "करता हूं", "करूंगा", "करूँगा",
    )

    private val URGENT = phrases(
        "urgent", "urgently", "asap", "immediately", "right away", "right now", "abhi", "turant", "jaldi",
        "fatafat", "eod", "end of day", "today itself", "aaj hi", "जल्दी", "तुरंत", "अभी",
    )

    /** Sentences that look like asks and are not: notices, offers, thanks, chasers, refusals. */
    private val SKIP = phrases(
        "please find", "pls find", "kindly find", "please note", "pls note", "kindly note", "please ignore",
        "pls ignore", "kindly ignore", "please be informed", "please be advised", "if you need",
        "if you have any", "in case you need", "feel free", "no need", "don't need", "dont need",
        "not needed", "no worries", "don't worry",
        "dont worry", "koi baat nahi", "rehne do", "mat karo", "mat bhejo", "mat karna", "mat bhejna",
        "nahi chahiye", "did you", "have you", "has it", "is it done", "was it", "ho gaya", "hua kya",
        "kiya kya", "kar diya", "bhej diya", "already",
    )

    /** Pleasantries: not an ask on their own, but they often come with one. */
    private val PLEASANTRIES = phrases(
        "thank", "thanks", "thx", "thank you", "congrats", "congratulations", "happy birthday",
        "good morning", "good night", "good evening", "welcome", "dhanyavaad", "dhanyawad", "shukriya",
        "धन्यवाद",
    )

    /** Whole messages left to the model: links, forwards, promotions, codes. */
    private val WHOLE_MESSAGE_SKIP = Regex(
        "https?://|www\\.|forwarded|fwd:|unsubscribe|cashback|% off|\\botp\\b|lottery|\\bsale\\b",
    )
}
