package com.taskmind.ui.transparency

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskmind.BuildConfig
import com.taskmind.core.Backoff
import com.taskmind.core.EvidenceMatcher
import com.taskmind.core.PreFilter
import com.taskmind.data.settings.Settings
import com.taskmind.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One row of the explanation: what the app does, and the value it is doing it
 * with right now.
 *
 * Every number in here is read from live settings rather than written into the
 * prose, so the screen cannot drift out of date the way documentation does.
 */
data class Rule(
    val title: String,
    val explanation: String,
    val value: String? = null,
)

data class Stage(
    val name: String,
    val summary: String,
    val rules: List<Rule>,
)

data class HowItWorksUiState(
    val stages: List<Stage> = emptyList(),
    val version: String = "",
    val llmModel: String = "",
    val asrModel: String = "",
    val hasLlmKey: Boolean = false,
    val hasAsrKey: Boolean = false,
    val storedCaptures: Int = 0,
    val storedTasks: Int = 0,
    val storedModelCalls: Int = 0,
)

class HowItWorksViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(HowItWorksUiState())
    val state: StateFlow<HowItWorksUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val s = container.settingsRepository.current()
            _state.value = HowItWorksUiState(
                stages = buildStages(s),
                version = "${BuildConfig.APP_VERSION_NAME} (${BuildConfig.APP_VERSION_CODE})",
                llmModel = s.llmModel,
                asrModel = s.asrModel,
                hasLlmKey = container.secretStore.hasLlmKey(),
                hasAsrKey = container.secretStore.hasAsrKey(),
                storedTasks = container.taskRepository.totalTasks(),
                storedCaptures = container.database.rawCaptureDao().total(),
                storedModelCalls = container.database.inferenceCallDao().total(),
            )
        }
    }

    private fun buildStages(s: Settings): List<Stage> = listOf(
        Stage(
            name = "1. Capture",
            summary = "A message arrives, or a call ends. TaskMind writes down what happened and nothing " +
                "else — no task is created at this point.",
            rules = listOf(
                Rule(
                    "Which apps are read",
                    "Only apps on your allow-list are looked at. Everything else is ignored before any " +
                        "text is examined.",
                    "${s.allowedPackages.size} apps allowed",
                ),
                Rule(
                    "Which text is used",
                    "Android bundles unread messages together, so the expanded notification grows every " +
                        "time another message arrives. TaskMind takes only the single latest message from " +
                        "the messaging bundle. Using the bundle would create a fresh task per message in it.",
                ),
                Rule(
                    "Written before anything else",
                    "The record is saved synchronously, inside the notification callback, before any " +
                        "filtering that needs disk. The listener process can be killed at any moment; if " +
                        "the row is not already there, the message is gone.",
                ),
                Rule(
                    "Calls are not recorded by this app",
                    "Android does not permit third-party call recording. TaskMind reads the files your " +
                        "own dialer writes. Three independent triggers notice a call ended, because on " +
                        "HyperOS any one of them can be silently blocked.",
                ),
                Rule(
                    "Recordings must settle first",
                    "A recording is only uploaded once its size is unchanged across two reads a second " +
                        "apart, otherwise half a file gets transcribed.",
                    "retried at 3s, 6s, 10s, 20s",
                ),
            ),
        ),
        Stage(
            name = "2. Pre-filter",
            summary = "A free, deterministic pass that throws out the large majority of notifications " +
                "before any money is spent. It never uses a model, and every rejection is logged with the " +
                "rule that fired.",
            rules = listOf(
                Rule("One-time codes", "A 4–8 digit number next to words like OTP, code, verify, or \"kisi ko na bataye\"."),
                Rule("Money and deliveries", "debited, credited, a/c, avl bal, txn, delivered, out for delivery, order #, shipped."),
                Rule("Automated senders", "Indian DLT header format (XX-YYYYYY), or names containing noreply, alert, info, update."),
                Rule("Noise", "Group summaries, ongoing notifications, media controls, and system apps."),
                Rule("Too short to mean anything", "Text below the minimum length after trimming.", "${PreFilter.MIN_TEXT_LENGTH} characters"),
                Rule(
                    "Already seen",
                    "Android re-delivers notifications. A fingerprint of sender plus text suppresses a " +
                        "repeat so it is not paid for twice.",
                    "7 days",
                ),
                Rule(
                    "The bias",
                    "The filter only rejects on a rule it can name. It never rejects because it is unsure — " +
                        "a missed commitment costs more than a wasted model call.",
                ),
            ),
        ),
        Stage(
            name = "3. The model",
            summary = "What survives the filter is sent to the provider you configured, using prompts you " +
                "can read and rewrite. Every request and reply is kept so you can check it.",
            rules = listOf(
                Rule("Extraction model", "Reads one message, or one transcript, and answers in JSON.", s.llmModel),
                Rule("Transcription model", "Turns call audio into text.", "${s.asrProvider.name.lowercase()} · ${s.asrModel}"),
                Rule(
                    "Second opinion",
                    "A separate call re-reads each candidate against the source and drops what is not " +
                        "really there. Costs one extra call per batch.",
                    if (s.verifyPass) "on" else "off",
                ),
                Rule(
                    "Temperature",
                    "Fixed at zero. The same message should produce the same answer twice.",
                    "0",
                ),
                Rule(
                    "Unparseable replies are discarded",
                    "If the reply is not valid JSON in the expected shape, it is thrown away whole rather " +
                        "than picked over for usable fragments.",
                ),
            ),
        ),
        Stage(
            name = "4. Grounding",
            summary = "The check that makes invented tasks structurally difficult: the model must quote " +
                "the words that justify each task, and TaskMind looks for that quote in the original.",
            rules = listOf(
                Rule(
                    "How the quote is matched",
                    "Case, punctuation, spacing and digit grouping are normalised, and Devanagari is " +
                        "handled the same as Latin. If the quote is not found outright, a sliding window " +
                        "scores how much of it appears; below the threshold the task is dropped in code, " +
                        "not by the model.",
                ),
                Rule("Messages must match closely", "The text is exact, so the model has no excuse.", "${(s.notificationTolerance * 100).toInt()}%"),
                Rule("Pasted transcripts", "Slightly looser: they have been through someone else's transcription.", "${(s.clipboardTolerance * 100).toInt()}%"),
                Rule("Call transcripts", "Loosest, because speech recognition itself makes mistakes.", "${(s.callTolerance * 100).toInt()}%"),
                Rule("Minimum quote length", "A quote shorter than this proves nothing and is rejected.", "${EvidenceMatcher.MIN_EVIDENCE_CHARS} characters"),
                Rule("Manual tasks skip this", "You typed it, so there is nothing to verify it against."),
            ),
        ),
        Stage(
            name = "5. The decision",
            summary = "One function creates every task in the app — from messages, calls, pasted " +
                "transcripts, the review inbox and your own typing alike. There is no second path.",
            rules = listOf(
                Rule("Created without asking", "The model's confidence is at or above this.", "${(s.autoCreateThreshold * 100).toInt()}%"),
                Rule("Sent to the review inbox", "Between the two thresholds: real enough to keep, not certain enough to assert."),
                Rule("Discarded", "Below this, it is logged and dropped.", "${(s.reviewThreshold * 100).toInt()}%"),
                Rule(
                    "Missing confidence counts as uncertain",
                    "If the model does not give a number, the task goes to review rather than to your list.",
                ),
                Rule(
                    "Duplicates",
                    "The database refuses a second task with the same source and the same normalised " +
                        "title, so a re-delivered message cannot produce a second copy. Politeness words " +
                        "(bhai, please, zara, ji) are stripped before comparing.",
                ),
                Rule(
                    "Dates",
                    "Resolved in India Standard Time against when the message arrived, not when it was " +
                        "processed. A date with no time means 18:00. A date that lands before the message " +
                        "was sent is dropped and kept in the notes rather than silently shifted.",
                ),
            ),
        ),
        Stage(
            name = "6. When things fail",
            summary = "Nothing captured is thrown away because a step failed. Everything parks and retries.",
            rules = listOf(
                Rule("Retry schedule", "Backing off, then it stops and waits for you.", "30s, 2m, 10m, 1h, 6h — ${Backoff.MAX_ATTEMPTS} tries"),
                Rule(
                    "Configuration errors do not retry",
                    "A wrong model name or a rejected key is not going to fix itself, so it is surfaced " +
                        "instead of retried. It also does not consume the retry budget, so the capture is " +
                        "still fresh once you fix it.",
                ),
                Rule("Daily model calls", "Reached, and everything waits for tomorrow rather than being dropped.", "${s.maxLlmCallsPerDay}"),
                Rule("Per app, per day", "Stops one chatty group chat spending the whole budget.", "${s.maxLlmCallsPerPackagePerDay}"),
                Rule("Daily transcription", "Call audio is the expensive upload.", "${s.maxAsrMinutesPerDay} minutes"),
                Rule("Call audio on Wi-Fi only", "", if (s.wifiOnlyAsr) "on" else "off"),
            ),
        ),
        Stage(
            name = "7. What is kept",
            summary = "What lives on the phone, for how long, and what leaves it.",
            rules = listOf(
                Rule(
                    "What leaves the device",
                    "The text of watched messages and your call recordings, to the provider you " +
                        "configured, and nowhere else. Nothing at all until you add a key.",
                ),
                Rule("Message text, transcripts and recordings", "Deleted on this schedule.", "${s.retentionDays} days"),
                Rule(
                    "Deleting them never deletes your tasks",
                    "The quote and the source label are copied onto each task, so a task still says who " +
                        "asked and in what words after the original is gone.",
                ),
                Rule("Recordings after transcription", "", if (s.deleteRecordingsAfterTranscription) "deleted immediately" else "kept until the retention window"),
                Rule("API keys", "Stored encrypted, excluded from backups, and never written to any log."),
                Rule(
                    "Model calls",
                    "The last hundred requests and replies are kept so you can audit them. They contain " +
                        "your message text, and \"Erase all captured content\" clears them.",
                ),
            ),
        ),
        Stage(
            name = "8. Staying alive",
            summary = "The unglamorous part. Android and HyperOS both try hard to stop background work.",
            rules = listOf(
                Rule(
                    "Three ways to notice a call ended",
                    "The call log, the telephony callback, and a system broadcast. Any one of them can be " +
                        "silently blocked, so all three run, plus a sweep on incoming notifications and a " +
                        "watchdog alarm.",
                    "watchdog every ~15 min",
                ),
                Rule(
                    "Xiaomi Autostart",
                    "A separate Xiaomi permission. With it off, background services are killed no matter " +
                        "what Android's own battery settings say, and call detection stops with no error.",
                ),
                Rule(
                    "Notifications from this app",
                    "Silent by default. The only ones that make a sound are a task being captured and a " +
                        "reminder firing — nothing for processing, syncing, or a failure that will be retried.",
                ),
                Rule(
                    "Database recovery never deletes",
                    "There is no destructive migration path. Schema changes are migrated, and tasks are " +
                        "exported before anything risky is attempted.",
                ),
            ),
        ),
    )
}
