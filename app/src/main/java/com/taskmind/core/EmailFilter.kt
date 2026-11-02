package com.taskmind.core

/**
 * Which emails are worth a model call - decided before any is made.
 *
 * Email buries a list faster than WhatsApp ever did: newsletters, receipts,
 * notifications, mailing lists. Most of it says so in its headers, which no
 * person writes by hand (List-Unsubscribe, Precedence: bulk, Auto-Submitted),
 * so those are the main test. Like [PreFilter], every skip names its rule,
 * and the bias is to read rather than skip when unsure: what is read lands in
 * the Inbox, where the user decides, not on their list.
 *
 * Pure: headers in, verdict out.
 */
object EmailFilter {

    enum class Folder { INBOX, SENT, LABEL }

    data class Input(
        val folder: Folder,
        val from: Mime.Address?,
        val to: List<Mime.Address>,
        val cc: List<Mime.Address>,
        val subject: String,
        val body: String,
        /** Only the headers this filter reads; see [HEADERS]. */
        val headers: Mime.Headers,
        val hasCalendar: Boolean,
        /** The user's own addresses: the mailbox and any extras they gave. */
        val mine: Set<String>,
        /** Skip mail where the user is not in To or Cc. */
        val onlyToMe: Boolean,
    )

    sealed interface Verdict {
        data class Read(val why: String) : Verdict
        data class Skip(val rule: String) : Verdict
    }

    /** The headers [evaluate] looks at. */
    val HEADERS = listOf(
        "List-Unsubscribe", "List-Id", "List-Post", "Precedence", "Auto-Submitted",
        "X-Autoreply", "X-Autorespond", "X-Auto-Response-Suppress", "Feedback-ID", "X-Mailchimp-Campaign",
    )

    private val AUTOMATED_LOCAL = Regex(
        "^(no[-_.]?reply|do[-_.]?not[-_.]?reply|notifications?|notify|alerts?|mailer-daemon|postmaster|" +
            "newsletters?|news|digest|updates?|marketing|promo(tions)?|offers?|deals|bounce[s]?|automated|system|" +
            "billing|receipts?|statements?|ebill|support-noreply|hello|team)([-+._].*)?$",
        RegexOption.IGNORE_CASE,
    )

    private val AUTOMATED_DOMAINS = listOf(
        "linkedin.com", "facebookmail.com", "twitter.com", "x.com", "instagram.com", "quora.com", "medium.com",
        "substack.com", "mailchimp", "sendgrid", "amazonses.com", "swiggy", "zomato", "uber.com", "olacabs.com",
        "flipkart", "myntra", "paytm", "phonepe", "netflix.com", "spotify.com", "youtube.com",
        "accounts.google.com", "docs.google.com", "calendar.google.com", "email.apple.com", "naukri.com", "indeed.com",
    )

    /**
     * Some machine mail really is a task: a document to sign, a review asked
     * for, a bill due. These pass the automated-sender rule and the model
     * decides, as with any other mail.
     */
    private val ACTIONABLE = Regex(
        "(please (sign|review|approve|complete|submit|confirm|respond)|signature (requested|required)|" +
            "requested your review|review requested|assigned (to )?you|has assigned|mentioned you|" +
            "action (required|needed)|approval (required|needed|pending)|awaiting your|" +
            "(invoice|bill|payment|premium|renewal|emi)\\s+(is\\s+)?(due|overdue|pending)|due (date|on|by)|" +
            "expir(es|ing|y)|deadline|last date)",
        RegexOption.IGNORE_CASE,
    )

    private val INVITATION_SUBJECT = Regex(
        "^(invitation|updated invitation|accepted|declined|tentatively accepted|canceled event|cancelled event)\\s*(:|-)",
        RegexOption.IGNORE_CASE,
    )

    private val PROMISE = Regex(
        "\\b(i'?ll|i will|i shall|we'?ll|we will|let me|i can|i'?m going to|will (send|share|get back|revert|check|" +
            "update|do|call|confirm|look|arrange|follow up|circle back|prepare|review|fix|book|pay|transfer|deliver))\\b|" +
            "\\b(by|before) (today|tonight|tomorrow|eod|eow|monday|tuesday|wednesday|thursday|friday|saturday|sunday|next week)\\b|" +
            "\\b(bhej|kar|de|bata|dekh|bol|call kar)\\s?(dunga|dungi|denge|deta|deti|doonga|doongi)\\b|" +
            "\\b(kal|aaj|parso) tak\\b",
        RegexOption.IGNORE_CASE,
    )

    fun evaluate(input: Input): Verdict {
        val sender = input.from?.email?.lowercase().orEmpty()
        val fromMe = isMine(sender, input.mine)
        val recipients = (input.to + input.cc).map { it.email.lowercase() }
        val toMe = recipients.any { isMine(it, input.mine) }
        val toOnlyMe = recipients.isNotEmpty() && recipients.all { isMine(it, input.mine) }

        if (input.subject.isBlank() && input.body.isBlank()) return Verdict.Skip("empty email")

        // Labelled for TaskMind by the user: read whatever it is.
        if (input.folder == Folder.LABEL) return Verdict.Read("labelled for TaskMind")

        if (input.hasCalendar || INVITATION_SUBJECT.containsMatchIn(input.subject)) {
            return Verdict.Skip("calendar invitation - your calendar has it")
        }

        // A note to yourself is a to-do by definition.
        if (fromMe && (toOnlyMe || recipients.isEmpty())) return Verdict.Read("note to self")

        if (input.folder == Folder.SENT) {
            if (!fromMe && sender.isNotEmpty()) return Verdict.Skip("sent folder, not from you")
            return if (looksLikePromise(input.body)) {
                Verdict.Read("you made a promise")
            } else {
                Verdict.Skip("sent mail without a promise")
            }
        }

        if (fromMe) return Verdict.Skip("from you")

        val automated = automatedReason(input, sender)
        if (automated != null) {
            val actionable = ACTIONABLE.containsMatchIn(input.subject) || ACTIONABLE.containsMatchIn(input.body.take(600))
            if (!actionable) return Verdict.Skip(automated)
        }

        if (input.onlyToMe && input.mine.isNotEmpty() && !toMe) return Verdict.Skip("not addressed to you")

        return Verdict.Read(if (automated != null) "automated, but asks for action" else "addressed to you")
    }

    /** Why this looks machine-sent, or null when it looks written by a person. */
    private fun automatedReason(input: Input, sender: String): String? {
        val h = input.headers
        if (h.has("List-Unsubscribe") || h.has("List-Id") || h.has("List-Post")) return "mailing list or newsletter"
        h["Precedence"]?.lowercase()?.let { if (it.contains("bulk") || it.contains("list") || it.contains("junk")) return "bulk mail" }
        h["Auto-Submitted"]?.lowercase()?.let { if (!it.startsWith("no")) return "automatic reply or notification" }
        if (h.has("X-Autoreply") || h.has("X-Autorespond")) return "automatic reply"
        if (h.has("X-Mailchimp-Campaign")) return "newsletter"
        if (Regex("^(automatic reply|auto(-| )?reply|out of (the )?office)", RegexOption.IGNORE_CASE).containsMatchIn(input.subject)) {
            return "out-of-office reply"
        }
        val local = sender.substringBefore('@')
        val domain = sender.substringAfter('@', "")
        if (local.isNotEmpty() && AUTOMATED_LOCAL.matches(local)) return "automated sender"
        if (domain.isNotEmpty() && AUTOMATED_DOMAINS.any { domain == it || domain.endsWith(".$it") || domain.contains(it) && !it.contains('.') }) {
            return "service notification"
        }
        return null
    }

    /** Whether text the user wrote promises something - the only sent mail worth reading. */
    fun looksLikePromise(text: String): Boolean = PROMISE.containsMatchIn(text)

    /**
     * Whether [address] is one of [mine]. Gmail ignores dots and anything
     * after a plus in the local part, so r.jain+work@gmail.com is rjain@gmail.com.
     */
    fun isMine(address: String, mine: Set<String>): Boolean {
        if (address.isBlank()) return false
        val key = canonical(address)
        return mine.any { canonical(it) == key }
    }

    fun canonical(address: String): String {
        val lower = address.trim().lowercase()
        val local = lower.substringBefore('@')
        val domain = lower.substringAfter('@', "")
        return if (domain == "gmail.com" || domain == "googlemail.com") {
            local.substringBefore('+').replace(".", "") + "@gmail.com"
        } else {
            local.substringBefore('+') + "@" + domain
        }
    }
}
