package com.taskmind.core

/**
 * Spec 10.3 - reject before any inference.
 *
 * Cheap, deterministic, free. It rejects the large majority of notifications
 * and is the main defence against a cloud-first design turning into a runaway
 * bill.
 *
 * Aimed at HIGH RECALL of real tasks: reject only on a matched rule, never on
 * general uncertainty. Every rejection names the rule that fired so a false
 * reject is diagnosable from the activity log alone.
 */
object PreFilter {

    const val MIN_TEXT_LENGTH = 8

    /** Everything the filter needs, with no Android types, so it is testable. */
    data class Input(
        val packageName: String,
        val senderKey: String,
        val text: String,
        val isGroupSummary: Boolean = false,
        /**
         * True when the text came from a single MessagingStyle entry with its
         * own sender, rather than from the notification's summary fields.
         */
        val hasResolvedMessage: Boolean = false,
        val isOngoing: Boolean = false,
        val isMediaStyle: Boolean = false,
        val isAllowListed: Boolean = true,
        val fingerprintSeen: Boolean = false,
        val ownPackageName: String = "com.taskmind",
        /** A group chat rather than a one-to-one conversation. */
        val isGroup: Boolean = false,
        /** What to do about groups. Ignored entirely when [isGroup] is false. */
        val groupPolicy: GroupPolicy = GroupPolicy.EVERYTHING,
        /** From [Mention]: is there positive reason to think this concerns the user. */
        val mention: Mention.Result = Mention.Result(false, false, false),
    )

    /**
     * How much of a group chat is worth reading.
     *
     * The rest of this filter rejects only what is definitely not a task. This
     * one rejects things that MIGHT be, and that is a deliberate exception:
     * in a work group most messages are two other people talking, and a task
     * created from one of those costs a deletion every time, every day. The
     * cost of the opposite mistake is bounded by the per-group override: a
     * group where everything really does concern the user is set back to
     * EVERYTHING and behaves exactly as it always has.
     */
    enum class GroupPolicy(val label: String, val explanation: String) {
        EVERYTHING(
            "Read every message",
            "How TaskMind behaved before. Accurate, and noisy in busy groups.",
        ),
        ADDRESSED_TO_ME(
            "Only when I'm involved",
            "Your name, an @mention of you, or a message to the whole group. " +
                "Colleagues asking each other for updates are skipped.",
        ),
        NEVER(
            "Ignore group chats",
            "One-to-one messages and calls are still read.",
        ),
        ;

        companion object {
            /** A stored name that no longer matches an entry falls back rather than crashing. */
            fun byName(name: String?): GroupPolicy? = entries.firstOrNull { it.name == name }
        }
    }

    sealed interface Verdict {
        data object Pass : Verdict
        data class Reject(val rule: String, val detail: String? = null) : Verdict
    }

    private val SYSTEM_PACKAGES = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.miui.securitycenter",
        "com.miui.powerkeeper",
        "com.google.android.gms",
    )

    private val OTP_NUMBER = Regex("\\b\\d{4,8}\\b")
    private val OTP_WORDS = Regex(
        "otp|code|verification|verify|password|pin|do not share|kisi ko na bat",
        RegexOption.IGNORE_CASE,
    )
    private val TRANSACTIONAL = Regex(
        "debited|credited|a/c|avl bal|txn|delivered|out for delivery|order #|your order|has been shipped",
        RegexOption.IGNORE_CASE,
    )
    private val DLT_HEADER = Regex("^[A-Z]{2}-[A-Z0-9]{6}$")
    private val AUTOMATED_SENDER = Regex("noreply|no-reply|alerts?|info|update", RegexOption.IGNORE_CASE)

    /**
     * Explicit advertising markers only.
     *
     * A Telegram post ("Transform your backyard with our cozy FIRE PITS ...
     * #ad InsideAd") reached the model on the device and cost a request. The
     * temptation is to add "buy now", "% off" and similar, but those appear in
     * genuine work messages - "unko 10% off de dena" is a real commitment - and
     * the filter's bias is to reject only on a rule it can name. Every pattern
     * here is something a colleague would not write to another person.
     */
    private val PROMOTIONAL = Regex(
        "#ad\\b|#sponsored|#promo\\b|#advertisement|sponsored (?:post|by)|promoted by|" +
            "unsubscribe|t&c apply|terms (?:&|and) conditions apply|limited time offer|" +
            "offer ends|sale ends|shop now",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The subset of rules that depend on nothing but the package name.
     *
     * Split out so the caller can apply them before it does the work of pulling
     * text out of the notification: an app nobody asked to watch should not
     * cost a text extraction or a log line.
     */
    fun evaluatePackage(
        packageName: String,
        isAllowListed: Boolean,
        ownPackageName: String = "com.taskmind",
    ): Verdict {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return Verdict.Reject("empty package")
        if (pkg == ownPackageName) return Verdict.Reject("own package")
        if (pkg in SYSTEM_PACKAGES || pkg.startsWith("com.android.") || pkg.startsWith("com.miui.")) {
            return Verdict.Reject("system package", pkg)
        }
        if (!isAllowListed) return Verdict.Reject("package not on allow-list", pkg)
        return Verdict.Pass
    }

    fun evaluate(input: Input): Verdict {
        val packageVerdict = evaluatePackage(
            packageName = input.packageName,
            isAllowListed = input.isAllowListed,
            ownPackageName = input.ownPackageName,
        )
        if (packageVerdict is Verdict.Reject) return packageVerdict

        // A group summary is normally the rolled-up "3 new messages" banner that
        // sits alongside the real per-conversation notifications, and taking it
        // would create one task per bundled message.
        //
        // But not always. On the device a plain "Please call Rahul tomorrow at
        // 4 PM" arrived ONLY as a summary, three times, and was thrown away
        // three times - precisely the kind of commitment this app exists to
        // catch. When the summary carries a single resolved MessagingStyle
        // entry it is not a roll-up: it names one sender and one message, and
        // it produces the same sourceRef as the individual notification would,
        // so the existing duplicate check absorbs whichever arrives second.
        if (input.isGroupSummary && !input.hasResolvedMessage) {
            return Verdict.Reject("group summary")
        }
        if (input.isOngoing) return Verdict.Reject("ongoing event")
        if (input.isMediaStyle) return Verdict.Reject("media notification")

        val text = input.text.trim()
        if (text.isEmpty()) return Verdict.Reject("empty text")
        if (text.length < MIN_TEXT_LENGTH) return Verdict.Reject("text shorter than $MIN_TEXT_LENGTH chars")

        if (OTP_NUMBER.containsMatchIn(text) && OTP_WORDS.containsMatchIn(text)) {
            return Verdict.Reject("otp/verification pattern")
        }
        if (TRANSACTIONAL.containsMatchIn(text)) {
            val hit = TRANSACTIONAL.find(text)?.value
            return Verdict.Reject("transactional pattern", hit)
        }
        if (PROMOTIONAL.containsMatchIn(text)) {
            val hit = PROMOTIONAL.find(text)?.value
            return Verdict.Reject("promotional content", hit)
        }

        val sender = input.senderKey.trim()
        if (sender.isNotEmpty()) {
            if (DLT_HEADER.matches(sender)) return Verdict.Reject("DLT sender header", sender)
            if (AUTOMATED_SENDER.containsMatchIn(sender)) return Verdict.Reject("automated sender name", sender)
        }

        if (input.fingerprintSeen) return Verdict.Reject("fingerprint seen within 7 days")

        val groupVerdict = evaluateGroup(input)
        if (groupVerdict is Verdict.Reject) return groupVerdict

        return Verdict.Pass
    }

    /**
     * Which policy actually applies to one conversation.
     *
     * Pure, and separated from [evaluate], because the interesting part is the
     * order of the exceptions rather than the rule itself:
     *
     *  - a one-to-one chat is never filtered by group rules
     *  - a group the user marked "always watch" beats everything
     *  - a group they marked "ignore" comes next
     *  - and if they have not told the app what they are called, the whole
     *    feature stands down. "Only when I'm involved" with no names to look
     *    for would reject every group message on the phone, silently. That is
     *    worse than the noise it was meant to fix.
     */
    fun resolveGroupPolicy(
        isGroup: Boolean,
        groupName: String?,
        chosen: GroupPolicy,
        ownNames: Set<String>,
        alwaysWatch: Set<String>,
        neverWatch: Set<String>,
    ): GroupPolicy {
        if (!isGroup) return GroupPolicy.EVERYTHING
        val name = groupName?.trim().orEmpty()
        if (name.isNotEmpty()) {
            if (alwaysWatch.any { it.equals(name, ignoreCase = true) }) return GroupPolicy.EVERYTHING
            if (neverWatch.any { it.equals(name, ignoreCase = true) }) return GroupPolicy.NEVER
        }
        if (chosen == GroupPolicy.ADDRESSED_TO_ME && ownNames.none { it.isNotBlank() }) {
            return GroupPolicy.EVERYTHING
        }
        return chosen
    }

    /**
     * The group rule, last, so a message it would have let through has already
     * been judged on everything else and the log line names the real reason.
     *
     * It runs only for group conversations. A one-to-one message is by
     * definition addressed to the user and is never touched here.
     */
    fun evaluateGroup(input: Input): Verdict {
        if (!input.isGroup) return Verdict.Pass
        return when (input.groupPolicy) {
            GroupPolicy.EVERYTHING -> Verdict.Pass
            GroupPolicy.NEVER -> Verdict.Reject("group chat ignored by policy")
            GroupPolicy.ADDRESSED_TO_ME ->
                if (input.mention.forMe) {
                    Verdict.Pass
                } else {
                    Verdict.Reject(
                        "group message not addressed to you",
                        if (input.mention.namesSomeoneElse) "names someone else" else "no mention of you",
                    )
                }
        }
    }

    /**
     * Spec 10.3 default allow-list. The settings screen adds to this from the
     * set of packages that have actually posted a notification since install.
     */
    val DEFAULT_ALLOWED_PACKAGES: Set<String> = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "com.google.android.apps.messaging",
        "com.android.mms",
        "com.samsung.android.messaging",
        "org.telegram.messenger",
        "org.thoughtcrime.securesms",
        "com.miui.smsextra",
    )
}
