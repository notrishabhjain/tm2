package com.taskmind.tagging

import com.taskmind.core.SourceType

/**
 * The tags a task gets without anyone typing them.
 *
 * DERIVED, NOT STORED
 *
 * Every tag here is computed from fields the task already carries -
 * `sourceType`, `sourceApp`, `sourceLabel`, the title and the evidence quote.
 * Nothing is written to the database and there is no migration, which means
 * these appear on every task you already have, not just ones captured from
 * now on. Change a rule below and yesterday's tasks re-tag themselves.
 *
 * It also means the capture-to-task engine needs no changes at all: this reads
 * what the funnel already recorded and says nothing back to it.
 *
 * WHY KEYWORDS AND NOT THE MODEL
 *
 * The topic tags could have been another field in the extraction prompt. They
 * are rules instead because rules are free, instant, explainable when a tag
 * looks wrong, and - the deciding one - they work backwards over every task
 * already in the database. A model-assigned category would only ever exist on
 * tasks captured after the change.
 *
 * All of this is pure Kotlin on purpose: it is the one part of this feature a
 * machine without an Android toolchain can actually test.
 */
object AutoTagger {

    enum class Kind {
        /** How it arrived: a call, a message, typed in. */
        SOURCE,

        /** Which app it came through. */
        APP,

        /** Who said it. */
        PERSON,

        /** Which group chat, when it was one. */
        GROUP,

        /** What sort of thing it is: a payment, a meeting, a document. */
        TOPIC,
    }

    /**
     * [value] is what gets displayed and matched; [kind] only decides ordering
     * and colour, so a tag stays a plain string everywhere else.
     */
    data class Tag(val kind: Kind, val value: String) {
        /** Lower-cased, for matching a search term against it. */
        val key: String get() = value.lowercase()
    }

    /**
     * Tags for one task, most specific first.
     *
     * Person and group lead because "what did Sharma ji ask for" is the
     * question this is actually for; the source and app are context.
     */
    fun tags(
        sourceType: SourceType,
        sourceApp: String?,
        sourceLabel: String?,
        title: String,
        evidence: String? = null,
    ): List<Tag> {
        val parsed = parseLabel(sourceType, sourceLabel)
        val out = mutableListOf<Tag>()

        parsed.person?.let { out += Tag(Kind.PERSON, it) }
        parsed.group?.let { out += Tag(Kind.GROUP, it) }

        appName(sourceApp, parsed.app)?.let { out += Tag(Kind.APP, it) }
        sourceTag(sourceType)?.let { out += Tag(Kind.SOURCE, it) }
        out += topics(title, evidence).map { Tag(Kind.TOPIC, it) }

        // Deduped on the value alone, not on kind-and-value. A group named
        // after the person who runs it, or a sender whose name matches the
        // app, would otherwise draw the same word twice on one row. Ordering
        // puts the most useful kind first, so keeping the first occurrence
        // keeps the right one.
        return out.distinctBy { it.key }
    }

    /** Flattened to the strings a search box compares against. */
    fun keys(
        sourceType: SourceType,
        sourceApp: String?,
        sourceLabel: String?,
        title: String,
        evidence: String? = null,
    ): List<String> = tags(sourceType, sourceApp, sourceLabel, title, evidence).map { it.key }

    // -- where it came from --------------------------------------------------

    private fun sourceTag(type: SourceType): String? = when (type) {
        SourceType.CALL -> "call"
        SourceType.NOTIFICATION -> "message"
        SourceType.CLIPBOARD -> "pasted"
        SourceType.MANUAL -> "manual"
        // A candidate the user approved by hand. Worth being able to find:
        // these are the ones the app was unsure about.
        SourceType.REVIEW -> "approved"
    }

    data class Parsed(val person: String?, val group: String?, val app: String?)

    /**
     * Pulls the person, group and app back out of the label capture wrote.
     *
     * Two shapes exist, both built in `CaptureCoordinator`:
     *
     *   "Sharma Ji in Project Alpha - WhatsApp"   (a notification)
     *   "Call with Amit - 14:32"                  (a call)
     *
     * Parsing a string that another part of the app formatted is not lovely,
     * but the alternative is a schema change to the frozen task table to store
     * three fields that are already in there. This is the cheaper trade, and
     * a label that does not match either shape simply yields no tags rather
     * than a wrong one.
     */
    fun parseLabel(type: SourceType, label: String?): Parsed {
        val text = label?.trim().orEmpty()
        if (text.isEmpty()) return Parsed(null, null, null)

        if (type == SourceType.CALL || text.startsWith(CALL_PREFIX, ignoreCase = true)) {
            // "Call with Amit - 14:32" - the tail is a clock time, not an app.
            val who = text.removePrefix(CALL_PREFIX).removePrefix(CALL_PREFIX.lowercase())
                .substringBeforeLast(" - ")
                .trim()
            return Parsed(person = who.takeIf { it.isUsableName() }, group = null, app = null)
        }

        // Split on the LAST " - ": a person's name may well contain one, the
        // app suffix is always last.
        val app = text.substringAfterLast(" - ", missingDelimiterValue = "").trim()
        val head = if (app.isEmpty()) text else text.substringBeforeLast(" - ").trim()

        val person = head.substringBefore(GROUP_JOINER).trim()
        val group = if (head.contains(GROUP_JOINER)) {
            head.substringAfter(GROUP_JOINER).trim()
        } else {
            null
        }

        return Parsed(
            person = person.takeIf { it.isUsableName() },
            group = group?.takeIf { it.isUsableName() },
            app = app.takeIf { it.isNotBlank() },
        )
    }

    /**
     * "Unknown" is what capture writes when it could not resolve a sender, and
     * a bare phone number is not a name worth grouping by. Neither makes a
     * useful tag, and a tag you cannot act on is clutter.
     */
    private fun String.isUsableName(): Boolean {
        val trimmed = trim()
        if (trimmed.length < 2 || trimmed.length > 40) return false
        if (trimmed.equals("unknown", ignoreCase = true)) return false
        if (trimmed.equals("unknown number", ignoreCase = true)) return false
        // Digits, spaces, + and - only: a phone number.
        if (trimmed.all { it.isDigit() || it == '+' || it == '-' || it == ' ' || it == '(' || it == ')' }) return false
        return true
    }

    /**
     * A readable app name.
     *
     * The label's own suffix is preferred because it is the name Android gave
     * the app on this device. The package map is the fallback for captures
     * whose label lost it, and it normalises the handful that matter.
     */
    private fun appName(packageName: String?, fromLabel: String?): String? {
        packageName?.let { pkg ->
            PACKAGES.entries.firstOrNull { pkg.startsWith(it.key) }?.let { return it.value }
        }
        return fromLabel?.takeIf { it.isNotBlank() && it.length <= 24 && !it.contains('.') }
    }

    // -- what kind of thing it is -------------------------------------------

    /**
     * At most two, most specific first.
     *
     * A task that matches four topics has been tagged by a rule that is too
     * loose, and four chips on a row is not organisation, it is noise.
     */
    private fun topics(title: String, evidence: String?): List<String> {
        val haystack = (title + " " + evidence.orEmpty()).lowercase()
        return TOPICS.filter { (_, words) -> words.any { haystack.contains(it) } }
            .map { it.first }
            .take(2)
    }

    private const val CALL_PREFIX = "Call with "
    private const val GROUP_JOINER = " in "

    /** Longest prefixes first so a more specific package wins. */
    private val PACKAGES: Map<String, String> = linkedMapOf(
        "com.whatsapp.w4b" to "WhatsApp Business",
        "com.whatsapp" to "WhatsApp",
        "org.telegram" to "Telegram",
        "com.google.android.apps.messaging" to "Messages",
        "com.android.mms" to "Messages",
        "com.google.android.gm" to "Gmail",
        "com.microsoft.teams" to "Teams",
        "com.Slack" to "Slack",
        "com.slack" to "Slack",
        "com.instagram" to "Instagram",
        "com.linkedin" to "LinkedIn",
        "com.facebook.orca" to "Messenger",
    )

    /**
     * Ordered by specificity: a message about an invoice is better filed under
     * payment than under document, so payment is tested first.
     *
     * Hindi appears in both scripts because the transcripts do. Whisper returns
     * Devanagari for spoken Hindi while typed messages are usually romanised,
     * and a rule that only knows one of them misses half the real traffic.
     * Substring matching is deliberate - "payment", "payments" and "prepayment"
     * should all land on the same tag.
     */
    private val TOPICS: List<Pair<String, List<String>>> = listOf(
        "payment" to listOf(
            "payment", "pay ", "paid", "invoice", "bill", "amount", "rupee", "rupaye",
            "₹", " rs", "rs.", "lakh", "crore", "advance", "transfer", "neft", "rtgs",
            "upi", "cheque", "check payment", "paisa", "paise", "पैसा", "पैसे", "भुगतान",
            "रुपये", "बिल", "एडवांस",
        ),
        "document" to listOf(
            "document", "invoice copy", "quotation", "quote", "agreement", "contract",
            "proposal", "report", "pdf", "attach", "paperwork", "kagaz", "कागज",
            "दस्तावेज", "रिपोर्ट",
        ),
        "meeting" to listOf(
            "meeting", "meet ", "milna", "milte", "milenge", "appointment", "visit",
            "discuss", "schedule", "aana hai", "मीटिंग", "मिलना", "मिलेंगे", "आना",
        ),
        "call-back" to listOf(
            "call back", "callback", "call karna", "phone karna", "ring ", "dial",
            "call me", "baat karna", "कॉल", "फोन", "बात",
        ),
        "send" to listOf(
            "send", "bhej", "share ", "forward", "courier", "dispatch", "upload",
            "mail it", "email it", "भेज", "शेयर",
        ),
        "follow-up" to listOf(
            "follow up", "followup", "follow-up", "remind", "confirm", "status",
            "update on", "check with", "poochna", "pata karna", "फॉलो", "याद", "पूछना",
        ),
    )
}
