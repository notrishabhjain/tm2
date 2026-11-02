package com.taskmind.email

import com.taskmind.capture.CaptureCoordinator
import com.taskmind.core.DateResolver
import com.taskmind.core.EmailFilter
import com.taskmind.core.EmailText
import com.taskmind.core.LogLevel
import com.taskmind.core.Mime
import com.taskmind.core.Stage
import com.taskmind.di.AppContainer
import com.taskmind.work.Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.time.LocalDate

/**
 * Reads new mail from the connected mailbox into captures.
 *
 * Each run: sign in, open the inbox (and the sent folder, and a "TaskMind"
 * label if there is one), take the messages that arrived since the last run,
 * and for each one that passes [EmailFilter] write a capture. The extraction
 * worker reads them from there exactly as it reads a WhatsApp message.
 *
 * What it reads stays on the phone and goes only to the AI provider, like a
 * message does. Nothing from a mail is sent to the web mirror except the task
 * it produces.
 */
class EmailSync(
    private val container: AppContainer,
    private val store: EmailStore = container.emailStore,
) {

    sealed interface Result {
        data class Done(val read: Int, val captured: Int, val skipped: Int) : Result
        data class Skipped(val why: String) : Result
        data class Failed(val message: String, val auth: Boolean, val retryable: Boolean) : Result
    }

    private data class Target(val name: String, val kind: EmailFilter.Folder)

    suspend fun run(manual: Boolean): Result = lock.withLock {
        withContext(Dispatchers.IO) { runLocked(manual) }
    }

    private suspend fun runLocked(manual: Boolean): Result {
        val state = store.current()
        val now = System.currentTimeMillis()
        if (!state.connected) return Result.Skipped("no mailbox connected")
        // A refused password would be refused again, and repeated failed
        // sign-ins can get an account locked. Wait for the user.
        if (state.authFailed && !manual) return Result.Skipped("waiting for a new password")
        if (!container.settingsRepository.current().cloudConsent) {
            val why = "Cloud processing is off, so email is not read."
            store.recordFailure(now, why, auth = false)
            return Result.Failed(why, auth = false, retryable = false)
        }
        val password = container.secretStore.emailPassword
        if (password.isBlank()) {
            val why = "No app password saved. Connect the mailbox again."
            store.recordFailure(now, why, auth = true)
            return Result.Failed(why, auth = true, retryable = false)
        }

        val client = ImapClient(state.host, state.port)
        return try {
            client.connect()
            client.login(state.address, password)
            val folders = client.list()
            val targets = buildList {
                add(Target("INBOX", EmailFilter.Folder.INBOX))
                if (state.readSent) ImapClient.sentFolder(folders)?.let { add(Target(it.name, EmailFilter.Folder.SENT)) }
                ImapClient.labelFolder(folders)?.let { add(Target(it.name, EmailFilter.Folder.LABEL)) }
            }

            var read = 0
            var captured = 0
            var skipped = 0
            val skipReasons = mutableMapOf<String, Int>()
            for (target in targets) {
                val box = client.examine(target.name)
                val mark = state.marks[target.name]?.takeIf { it.first == box.uidValidity }
                val uids = if (mark == null) {
                    // First look at this folder: the last two days, not the
                    // whole history - a mailbox of ten years is not a to-do list.
                    client.uidsSince(LocalDate.now(DateResolver.IST).minusDays(FIRST_RUN_DAYS)).takeLast(FIRST_RUN_MAX)
                } else {
                    client.uidsAfter(mark.second).take(MAX_PER_RUN)
                }
                if (uids.isEmpty()) {
                    val newest = mark?.second ?: (box.uidNext - 1).coerceAtLeast(0)
                    store.setMark(target.name, box.uidValidity, newest)
                    continue
                }
                for (batch in uids.chunked(BATCH)) {
                    for (fetched in client.fetch(batch, MAX_BYTES)) {
                        read++
                        when (val outcome = handle(fetched, target, box.uidValidity, state)) {
                            is Handled.Captured -> captured++
                            is Handled.Skipped -> {
                                skipped++
                                skipReasons[outcome.rule] = (skipReasons[outcome.rule] ?: 0) + 1
                            }
                            Handled.Duplicate -> Unit
                        }
                    }
                    // Moved per batch, so a run cut short resumes rather than repeats.
                    store.setMark(target.name, box.uidValidity, batch.max())
                }
            }
            client.logout()

            val summary = summary(read, captured, skipReasons)
            store.recordRun(System.currentTimeMillis(), summary, read, captured)
            container.logger.write(Stage.CAPTURE, LogLevel.INFO, "Email: $summary")
            if (captured > 0) Scheduler.enqueueExtraction(container.context)
            Result.Done(read, captured, skipped)
        } catch (e: ImapClient.ImapException) {
            val message = if (e.auth) authMessage(e.message.orEmpty(), state) else (e.message ?: "The mail server refused.")
            store.recordFailure(System.currentTimeMillis(), message, e.auth)
            container.logger.write(Stage.CAPTURE, LogLevel.WARN, "Email check failed", message)
            Result.Failed(message, e.auth, retryable = false)
        } catch (e: IOException) {
            val message = "Could not reach ${state.host}: ${e.message ?: e.javaClass.simpleName}"
            store.recordFailure(System.currentTimeMillis(), message, auth = false)
            container.logger.write(Stage.CAPTURE, LogLevel.WARN, "Email check failed", message)
            Result.Failed(message, auth = false, retryable = true)
        } catch (t: Throwable) {
            val message = "Email check failed: ${t.message ?: t.javaClass.simpleName}"
            store.recordFailure(System.currentTimeMillis(), message, auth = false)
            container.logger.write(Stage.CAPTURE, LogLevel.ERROR, "Email check crashed", t.toString())
            Result.Failed(message, auth = false, retryable = false)
        } finally {
            client.close()
        }
    }

    private sealed interface Handled {
        data object Captured : Handled
        data class Skipped(val rule: String) : Handled
        data object Duplicate : Handled
    }

    private suspend fun handle(
        fetched: ImapClient.Fetched,
        target: Target,
        uidValidity: Long,
        state: EmailStore.State,
    ): Handled {
        val message = Mime.parse(fetched.raw)
        val body = EmailText.body(message)
        val mine = state.mine
        val verdict = EmailFilter.evaluate(
            EmailFilter.Input(
                folder = target.kind,
                from = message.from,
                to = message.to,
                cc = message.cc,
                subject = message.subject,
                body = body,
                headers = message.headers,
                hasCalendar = message.hasCalendar,
                mine = mine,
                onlyToMe = state.onlyToMe,
            ),
        )
        if (verdict is EmailFilter.Verdict.Skip) {
            container.logger.write(
                Stage.PREFILTER,
                LogLevel.DEBUG,
                "email skipped: ${verdict.rule}",
                "from=${message.from?.email} subject=${message.subject.take(120)}",
            )
            return Handled.Skipped(verdict.rule)
        }

        val fromMe = EmailFilter.isMine(message.from?.email.orEmpty(), mine)
        val toSelf = fromMe && (message.to + message.cc).all { EmailFilter.isMine(it.email, mine) }
        val forwarded = if (EmailText.isForward(message.subject, body)) EmailText.forwardedFrom(body) else null
        // Who this is with: the sender, the recipient of mail the user sent,
        // or the original author of something the user forwarded to themselves.
        val person: Mime.Address? = when {
            toSelf && forwarded != null -> forwarded
            toSelf -> null
            fromMe -> message.to.firstOrNull()
            else -> message.from
        }
        val name = person?.display ?: if (toSelf) "Note to self" else "Unknown"
        val sent = fromMe && !toSelf
        val text = EmailText.forCapture(message.from, message.to, message.cc, message.subject, body, sent, message.attachments)
        val ref = "email:" + (message.messageId?.lowercase() ?: "${state.host}/${target.name}/$uidValidity/${fetched.uid}")
        val occurredAt = message.date?.takeIf { it > 0 } ?: fetched.internalDate ?: System.currentTimeMillis()
        val outcome = container.captureCoordinator.captureEmail(
            sourceRef = ref,
            label = "$name - Email",
            text = text,
            occurredAt = occurredAt,
            contextLabel = EmailText.contextLabel(name, person?.email, message.subject, sent),
        )
        return when (outcome) {
            is CaptureCoordinator.Outcome.Captured -> Handled.Captured
            is CaptureCoordinator.Outcome.Duplicate -> Handled.Duplicate
            is CaptureCoordinator.Outcome.Rejected -> Handled.Skipped(outcome.rule)
            CaptureCoordinator.Outcome.Unusable -> Handled.Skipped("unreadable")
        }
    }

    private fun summary(read: Int, captured: Int, reasons: Map<String, Int>): String {
        if (read == 0) return "No new email."
        val skippedText = reasons.entries.sortedByDescending { it.value }.take(3)
            .joinToString(", ") { "${it.value} ${it.key}" }
        return buildString {
            append(if (read == 1) "Read 1 new email" else "Read $read new emails")
            append(": ").append(if (captured == 1) "1 for TaskMind to look at" else "$captured for TaskMind to look at")
            if (skippedText.isNotEmpty()) append("; skipped ").append(skippedText)
            append('.')
        }
    }

    /** The server's refusal, with what to do about it for the usual cause. */
    private fun authMessage(server: String, state: EmailStore.State): String {
        val base = "Sign-in refused" + if (server.isNotBlank()) " (\"${server.take(160)}\")" else ""
        val hint = when (state.providerId) {
            "gmail" -> "Use an app password from myaccount.google.com/apppasswords, not your Google password. " +
                "A Workspace admin may also have turned app passwords off."
            else -> "Check the address and use an app password."
        }
        return "$base. $hint"
    }

    companion object {
        private val lock = Mutex()

        const val FIRST_RUN_DAYS = 2L
        const val FIRST_RUN_MAX = 15
        const val MAX_PER_RUN = 25
        const val BATCH = 10
        /** The start of each mail: words, not attachments. */
        const val MAX_BYTES = 128 * 1024

        /** Signs in and lists the folders, without reading any mail: the "Connect" button's test. */
        suspend fun test(host: String, port: Int, address: String, password: String): String? =
            withContext(Dispatchers.IO) {
                val client = ImapClient(host.trim(), port)
                try {
                    client.connect()
                    client.login(address.trim(), password.trim())
                    val folders = client.list()
                    client.examine("INBOX")
                    client.logout()
                    if (folders.isEmpty()) "Signed in, but the mailbox has no folders." else null
                } catch (e: ImapClient.ImapException) {
                    if (e.auth) "Sign-in refused: ${e.message}. Use an app password, not your normal password." else e.message
                } catch (e: IOException) {
                    "Could not reach $host:$port - ${e.message ?: e.javaClass.simpleName}"
                } finally {
                    client.close()
                }
            }

        /** "2 Nov, 10:15" for the status line. */
        fun formatWhen(at: Long): String =
            if (at <= 0) "never" else Instant.ofEpochMilli(at).atZone(DateResolver.IST)
                .format(java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm", java.util.Locale.ENGLISH))
    }
}
