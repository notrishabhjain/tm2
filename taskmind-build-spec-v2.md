# TaskMind — Build Specification v2 (cloud-first, single-app, one-shot ready)

> This document is the **sole source of truth**. It assumes no prior context and supersedes
> any earlier TaskMind spec. Every architectural question is already decided — where you see
> a decision you disagree with, implement it anyway and note the objection at the end. Open
> questions are what kill a one-shot build.
>
> Section 21 (Known failure modes) describes bugs that actually happened in a prior
> implementation. Treat them as hard requirements, not advice.

---

## 1. What TaskMind is

One Android app that captures work commitments automatically and turns them into tasks,
without the user ever typing one.

It watches two sources:

1. **Messaging notifications** (WhatsApp, SMS, and similar) — "beta woh 25000 ka payment kal
   tak kar dena" becomes a task due tomorrow.
2. **Phone call recordings** written by the device's own dialer — the audio is transcribed by
   a cloud ASR provider, and commitments spoken during the call become tasks.

Both feed a **full-featured task manager inside the same app**. There is no companion app, no
web dashboard, no second install. The task list is the product; anything the app cannot show
in that list does not exist as far as the user is concerned.

The user is an Indian professional. Conversations are in **Hindi, English, and Hinglish**
(Hindi written in Latin script, freely code-switched). This is the normal input, not an edge
case. Any component that assumes English will fail in production.

---

## 2. Locked decisions

These were previously open. They are now closed. Do not re-litigate them mid-build.

| Question | Decision | Why |
|---|---|---|
| Stack | **Pure Kotlin, single module** | Capture, background services and OS integration must be native anyway. A JS layer would buy only the UI and cost a bridge, a headless context, Expo config plugins, and a schema duplicated in two languages. |
| UI | **Jetpack Compose, Material 3** | |
| Database | **Room + KSP** | Compile-time verified queries. Failure mode #1 (tasks written to the wrong table) is a schema-discipline bug; Room makes the schema singular and inspectable. |
| DI | **None. A hand-written `AppContainer` singleton.** | Hilt codegen failures in CI are a first-build tax with no local machine to debug on. |
| Background work | **WorkManager** for retryable jobs, **foreground services** only for the two cases in §17 | |
| Networking | **OkHttp + kotlinx.serialization** | |
| Settings / secrets | **DataStore (Preferences)** for settings; **EncryptedSharedPreferences** for API keys | |
| Inference | **Cloud only in v1.** No llama.cpp, no whisper.cpp, no ONNX, no on-device model. | Local models were the single largest source of complexity and the reason the foreground-service budget was ever at risk. Deferred to v2 behind an interface. |
| LLM provider | **Any OpenAI-compatible `/v1/chat/completions` endpoint**, user-configured (base URL + key + model) | |
| ASR provider | **Two adapters:** OpenAI-compatible `/v1/audio/transcriptions` (multipart), and **Sarvam AI** (`api.sarvam.ai`, `saarika:v2.5`). User picks. | Sarvam is materially better on Hindi phone audio; it is not OpenAI-shaped, so it needs its own adapter. |
| Xiaomi HyperAI transcript | **Manual clipboard import only.** Never the primary path. | See §11.3. It cannot be automated safely. |
| Google Tasks sync | **Cut from v1** | OAuth on a sideloaded app needs credentials bound to the signing SHA-1. Not worth the first-run risk. |
| In-app Hugging Face model browsing | **Cut from v1** | Follows from cloud-only. |
| Home-screen widget | **Cut from v1** | |
| Drag-to-reorder | **Cut from v1.** Manual sort order field exists in the schema; UI is Phase 8. | |
| Threshold tuning | **A settings screen.** No remote config, no server. | |
| Distribution | **Sideload, with a self-update channel** (§19) | `MANAGE_EXTERNAL_STORAGE` + notification access makes Play Store approval unrealistic. |

---

## 3. Target environment

| | |
|---|---|
| Device | Redmi Turbo 5, 8 GB RAM |
| OS | Xiaomi HyperOS (India), Android 15 |
| Build | `compileSdk 35`, `targetSdk 35`, `minSdk 29`, `arm64-v8a` only |
| Locale | India (`Asia/Kolkata`); Hindi + English + Hinglish |
| Java | 17 |

**All builds happen in GitHub Actions.** There is no local development machine. This has three
consequences that shape the whole build:

- Every version must be **pinned** in `gradle/libs.versions.toml`. No dynamic versions, no
  `+` ranges. A resolution surprise costs a full CI cycle to discover.
- Debugging happens through the **in-app activity log** (§15), which must therefore be built
  in Phase 1 and written to by every stage. Assume no `adb logcat`.
- The build must produce a **signed release APK** from a keystore in repository secrets, so
  that self-update (§19) works — an unsigned or debug-signed APK cannot update a
  release-signed install.

### 3.1 CI workflow (build this in Phase 1)

`.github/workflows/build.yml`:

- Trigger: push to `main`, and `workflow_dispatch`.
- JDK 17, Gradle cache enabled.
- Decode `KEYSTORE_BASE64` secret to a file; read `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
  `KEY_PASSWORD` from secrets.
- `./gradlew assembleRelease`
- Upload the APK as a workflow artifact **and** attach it to a GitHub Release tagged
  `v${versionName}` when the run is a tag push.
- A second job runs `./gradlew testDebugUnitTest` and must pass before assembling.

Unit tests are not optional. The intake funnel (§7) and the evidence matcher (§13) are pure
functions with no Android dependency, and they are where correctness lives. They must be
testable and tested in CI without a device.

---

## 4. Non-negotiable principles

Ordered. When they conflict, the earlier one wins.

1. **Never silently lose a capture.** Every failure path leaves the input recoverable and
   retried later. A dropped commitment is the one unforgivable bug.
2. **Precision beats recall.** A wrong task costs the user's attention and their trust in the
   whole list. A missed task costs one commitment. When uncertain, ask rather than assert.
3. **Never fabricate.** No task may exist that was not literally stated in the source text.
   §13 enforces this mechanically.
4. **Silent by default.** Background work produces no notifications. The user is notified only
   when a task is created, or when they must act. An app that buzzes constantly gets
   uninstalled — this happened.
5. **Everything is visible.** Every capture attempt and its outcome is written to an activity
   log the user can read. When something does not work, the log must say why.
6. **The app works before it is configured.** With no API key, no permissions, and no network,
   the task manager is fully usable for manual tasks and the status screen explains exactly
   which capability is unavailable and why.

---

## 5. Architecture

```
┌──────────────────────────────────────────────────────────────┐
│ CAPTURE  (Kotlin, must survive process death)                │
│   NotificationListenerService  →  message text               │
│   Call-end triggers ×3         →  recording file path        │
│   Clipboard import screen      →  pasted transcript          │
│   Manual entry                 →  user-typed task            │
│                                                              │
│   Every one of these writes a RawCapture row and NOTHING     │
│   else. Capture never writes to the task table.              │
└──────────────────────────┬───────────────────────────────────┘
                           ▼
┌──────────────────────────────────────────────────────────────┐
│ TRANSCRIBE  (calls only)  — CaptureWorker                    │
│   cloud ASR  →  park & retry on failure                      │
└──────────────────────────┬───────────────────────────────────┘
                           ▼
┌──────────────────────────────────────────────────────────────┐
│ EXTRACT  — the single most quality-critical stage            │
│   cheap pre-filter → LLM (JSON mode) → verify pass           │
└──────────────────────────┬───────────────────────────────────┘
                           ▼
┌──────────────────────────────────────────────────────────────┐
│ INTAKE FUNNEL — ONE function. All sources. No exceptions.    │
│   validate → evidence check → normalise → confidence gate    │
│   → dedup → persist                                          │
│                                                              │
│   IntakeFunnel.submit() is the ONLY code in the app that     │
│   inserts into the tasks table.                              │
└──────────────────────────┬───────────────────────────────────┘
                           ▼
              TASK LIST  +  REVIEW INBOX (uncertain items)
```

**The intake funnel is the most important architectural rule in this document.** In the prior
implementation the call path bypassed it and wrote to a different table; calls were processed
correctly for weeks and produced *zero visible tasks*, because each path looked fine in
isolation. Enforce it structurally: the task DAO's insert method is `internal` and lives in a
package that only `IntakeFunnel` can reach, and there is a unit test asserting that manual
entry, notification, call and review-accept all converge on the same function.

---

## 6. Data model

Room entities. Everything is local; there is no server.

### `Task` — the primary object

```
id: String (UUID)
title: String
titleKey: String              // normalised, for dedup — see §7.4
notes: String?
dueAt: Long?                  // epoch millis, null = no date
priority: Priority            // URGENT | HIGH | MEDIUM | LOW
status: Status                // ACTIVE | COMPLETED | ARCHIVED | DELETED
projectId: String?
tags: List<String>            // TypeConverter to JSON
recurrenceRule: String?       // see §16
reminderAt: Long?
parentTaskId: String?         // sub-tasks
sortOrder: Int

sourceType: SourceType        // NOTIFICATION | CALL | CLIPBOARD | MANUAL | REVIEW
sourceRef: String?            // see §6.1 — NULL only for MANUAL
sourceLabel: String?          // "Sharma Ji · WhatsApp" / "Call with +9198… · 14:32"
sourceApp: String?            // package name
evidence: String?             // the verbatim quote that justified this task
confidence: Double?
inferenceOrigin: String?      // "cloud:gpt-4o-mini" / "manual"
rawCaptureId: String?         // FK, nullable — survives retention purge as NULL

completedAt: Long?
createdAt: Long
updatedAt: Long
```

**Unique index on `(sourceType, sourceRef, titleKey)`.** SQLite treats NULLs as distinct in a
unique index, so manual tasks (`sourceRef = NULL`) may legitimately repeat while automated
ones cannot. Dedup is enforced by the database, not by trusting callers to check first. Insert
with `OnConflictStrategy.IGNORE` and treat a zero row-count return as "already had it" — log
it, do not surface it.

### 6.1 `sourceRef` — exact definition per source

This was undefined before and it is the linchpin of dedup. It is now:

| sourceType | sourceRef |
|---|---|
| `NOTIFICATION` | `"n:" + sha256(packageName + "\u0000" + senderKey + "\u0000" + normalizeForHash(messageText)).take(40)` |
| `CALL` | `"c:" + callLogId` if the call-log row was found; otherwise `"c:" + startTimeMillis + ":" + phoneNumberDigitsOnly` |
| `CLIPBOARD` | `"p:" + sha256(normalizeForHash(fullTranscript)).take(40)` |
| `REVIEW` | inherited unchanged from the RawCapture the review item came from |
| `MANUAL` | `null` |

`normalizeForHash` = NFKC → lowercase → strip all non-alphanumeric (Unicode-aware, so
Devanagari letters survive) → collapse whitespace.

`senderKey` = the resolved sender identity (§10.2), lowercased and whitespace-collapsed.

### 6.2 Supporting entities

- **`RawCapture`** — `id`, `sourceType`, `sourceRef`, `sourceApp`, `sourceLabel`,
  `rawText` (message text or transcript, nullable while awaiting ASR), `audioPath`,
  `capturedAt`, `occurredAt` (when the message arrived / the call took place — this, not
  `capturedAt`, is what relative dates resolve against), `state`, `attemptCount`,
  `lastError`, `nextAttemptAt`.
  `state` ∈ `PENDING_TRANSCRIPTION | PENDING_EXTRACTION | BUDGET_HELD | DONE | REJECTED | FAILED_PERMANENT`.
- **`ReviewItem`** — proposed task fields + source text + model reasoning + confidence +
  `state` (`PENDING | ACCEPTED | DISMISSED`) + `rawCaptureId`.
- **`CallRecord`** — caller name, number, direction, startTime, durationSeconds (nullable),
  recordingPath, transcript, state, `rawCaptureId`.
- **`Fingerprint`** — `hash`, `seenAt`. Cheap pre-inference reject only (§10.3). 7-day TTL.
  **This is not the dedup mechanism** — the unique index is. Fingerprints exist purely to
  avoid spending money re-inferring an Android notification re-delivery.
- **`ActivityLog`** — `timestamp`, `stage`, `level`, `message`, `detail`. Keep newest 500,
  trim on insert. Exportable as text from the settings screen.
- **`Project`**, **`Tag`**.

### 6.3 Retention

Transcripts and raw message text are purged on a user-configurable schedule (7 / 30 / 90 days,
default 30). **Purging must never delete tasks derived from the raw capture.** That is exactly
why `sourceLabel` and `evidence` are denormalised onto `Task`: after a purge, the task still
shows who said it and the words that created it, and only the tap-through to full context goes
away. Set `rawCaptureId = NULL` on purge; the FK is `ON DELETE SET NULL`.

Audio files are deleted on the same schedule, or immediately after successful transcription if
the user enables "delete recordings after transcription".

---

## 7. The intake funnel

One function. Signature:

```kotlin
suspend fun IntakeFunnel.submit(candidate: TaskCandidate): IntakeResult
```

`TaskCandidate` carries: title, evidence, priority, dueAt, notes, confidence, sourceType,
sourceRef, sourceLabel, sourceApp, rawCaptureId, inferenceOrigin, and the source text against
which evidence is checked.

Steps, in order:

1. **Validate.** Title non-blank after trim, ≤ 200 chars (truncate at 60 for display, store
   full). Priority in enum. Confidence in `0.0..1.0` or null. Reject with a logged reason
   otherwise.
2. **Evidence check** (§13). Only for `sourceType != MANUAL`. Failure → drop, log at WARN with
   the evidence string and the source, never surface to the user.
3. **Normalise.** Trim, collapse whitespace, strip a trailing full stop. Resolve and sanity-
   check `dueAt` (§14). Compute `titleKey` (§7.4).
4. **Confidence gate** (§14.2). Routes to task / review inbox / discard.
5. **Dedup.** Insert with `IGNORE`; row count 0 means duplicate.
6. **Persist.** Then, and only then, mark the originating `RawCapture` as `DONE`.
   **Persist first, mark second** — failure mode #5.
7. **Notify** if and only if a task was actually created (§17.3).
8. **Log** the outcome to `ActivityLog` regardless of which branch was taken.

### 7.4 `titleKey` normalisation

Lowercase → NFKC → strip leading politeness tokens (`please`, `pls`, `plz`, `kindly`, `zara`,
`thoda`, `ek baar`, `bhai`, `beta`, `sir`, `ji`) repeatedly until none match → strip all
punctuation → collapse whitespace → trim. Unit-test this with at least 15 Hinglish pairs that
must and must not collapse.

---

## 8. Provider configuration

Two independent configurations, because the best Hindi ASR and the best extraction LLM are
usually different vendors.

### 8.1 LLM (extraction and verification)

```
llmBaseUrl   : String   // default "https://api.openai.com/v1"
llmApiKey    : String
llmModel     : String   // default "gpt-4o-mini"
```

Called as OpenAI-compatible `POST {base}/chat/completions` with
`response_format: {"type": "json_object"}`, `temperature: 0`. If the provider rejects
`response_format`, retry once without it and fall back to lenient extraction of the first
balanced `{...}` block — but a response that then fails schema validation is **discarded, not
parsed leniently field-by-field**.

Known-good presets offered in the settings UI (base URL prefilled, user supplies key):
OpenAI, Groq (`https://api.groq.com/openai/v1`), OpenRouter
(`https://openrouter.ai/api/v1`), and "Custom".

### 8.2 ASR (call transcription)

```
asrProvider  : enum { OPENAI_COMPATIBLE, SARVAM }
asrBaseUrl   : String
asrApiKey    : String
asrModel     : String
asrLanguage  : String   // default "hi" — Sarvam handles Hinglish code-switching within "hi"
```

- **`OPENAI_COMPATIBLE`** — `POST {base}/audio/transcriptions`, multipart with `file`,
  `model`, `language`. Presets: OpenAI (`whisper-1`), Groq
  (`whisper-large-v3`, generous free tier, fast).
- **`SARVAM`** — `POST https://api.sarvam.ai/speech-to-text`, header
  `api-subscription-key: {key}`, multipart `file` + `model=saarika:v2.5` +
  `language_code=hi-IN`. Purpose-built for Indian languages and materially better than
  Whisper on Hindi phone audio.

### 8.3 Connection test

The setup screen has a **Test connection** button per provider. LLM test sends a two-token
prompt and asserts a JSON reply. ASR test sends a bundled 2-second silent WAV and asserts a
2xx. Both write their result to the activity log. Without this, a wrong base URL presents
identically to a broken pipeline, and diagnosing it costs a CI cycle.

### 8.4 Behaviour with no key

Captures are still recorded and parked in `PENDING_EXTRACTION`. The status screen shows
"Extraction unavailable — no API key configured" with a button to settings. When a key is
added, a WorkManager job drains the backlog oldest-first. **Nothing is discarded for lack of a
key.**

---

## 9. Cost and rate control

Cloud-first means every notification is potentially money. Without a ceiling this is a
runaway.

- **Pre-filter before every LLM call** (§10.3). It is deterministic, free, and rejects the
  large majority of notifications.
- **Daily budgets**, settings-tunable: `maxLlmCallsPerDay` (default 300),
  `maxAsrMinutesPerDay` (default 60). On exhaustion, captures move to `BUDGET_HELD` and are
  retried after the next local midnight. Never discarded.
- **Per-package cap**: at most 60 LLM calls/day attributable to any one package, to contain a
  single chatty group.
- **Wi-Fi-only ASR** toggle, default on. Call audio is the expensive upload.
- The status screen shows today's usage against both budgets.
- Retries use exponential backoff (30 s, 2 m, 10 m, 1 h, 6 h) capped at 5 attempts, then
  `FAILED_PERMANENT` with the error visible in the log. HTTP 429 and 5xx retry; 400 and 401
  do not (they are configuration errors — surface them on the status screen instead).

---

## 10. Notification capture

`NotificationListenerService`. Requires notification access granted in system settings; it is
not a runtime permission.

Extract per notification: package name, title, text, `EXTRA_BIG_TEXT` / `EXTRA_TEXT_LINES`,
`EXTRA_CONVERSATION_TITLE`, `EXTRA_IS_GROUP_CONVERSATION`, `EXTRA_MESSAGES` (the
`MessagingStyle` bundle), post time, notification key.

### 10.1 Which text to use — this one bites

Android bundles unread messages: the expanded text field accumulates *all* unread messages in
a conversation and changes every time a new one arrives. Fingerprinting it creates a fresh
task per bundled message.

**Resolution order for the message text:**

1. The **last entry** of `EXTRA_MESSAGES` (`MessagingStyle`), which is a single message with
   its own sender — this is the correct source when present, and WhatsApp provides it.
2. `EXTRA_TEXT_LINES`, last line only.
3. `EXTRA_TEXT`.
4. `EXTRA_BIG_TEXT` — last resort only, and if used, take only the final line.

Use that single latest message for **both** the fingerprint and the model input.

### 10.2 Sender and group resolution

Rules, in order:

- If `EXTRA_MESSAGES` is present, `senderKey` = the last message's `Person.name`; group flag =
  `EXTRA_IS_GROUP_CONVERSATION`.
- Else if `EXTRA_CONVERSATION_TITLE` is present and differs from `EXTRA_TITLE`, treat
  `EXTRA_CONVERSATION_TITLE` as the group name and `EXTRA_TITLE` as the sender.
- Else if `EXTRA_TITLE` matches `^(.+?)\s*[@:]\s*(.+)$`, group 1 is the sender and group 2 the
  group name.
- Else `senderKey` = `EXTRA_TITLE`, group = false.

For SMS, `senderKey` is the address; if it matches a contact via `READ_CONTACTS`, substitute
the display name (this is the only use of that permission — if you do not implement it, remove
the permission from the manifest).

Group chats are **not** excluded. They are passed to the LLM with the group name in context,
and Test 3 in the extraction prompt handles the "someone else is the doer" case.

### 10.3 Pre-filter — reject before any inference

Cheap, deterministic, saves battery and money. Reject:

- Own package, and `android`/system packages.
- `FLAG_GROUP_SUMMARY`, `FLAG_ONGOING_EVENT`, media-session and media-control notifications.
- Notifications from packages not on the allow-list. **Ship an allow-list**, default:
  WhatsApp (`com.whatsapp`, `com.whatsapp.w4b`), the default SMS app, Telegram, Signal,
  Google Messages. The settings screen lists every package that has posted a notification
  since install and lets the user toggle each. An allow-list is safer and cheaper than a
  deny-list here, and it makes cost predictable.
- Text matching OTP/verification patterns: `\b\d{4,8}\b` co-occurring with any of
  `otp|code|verification|verify|password|pin|do not share|kisi ko na bat`.
- Text matching transactional patterns: `debited|credited|a/c|avl bal|txn|delivered|out for delivery|order #|your order|has been shipped`.
- Sender names matching `^[A-Z]{2}-[A-Z0-9]{6}$` (Indian DLT header format) or containing
  `noreply|no-reply|alerts?|info|update`.
- Empty or whitespace-only text after trim; text shorter than 8 characters.
- Fingerprint already seen within 7 days.

Aim the filter at **high recall of real tasks** — reject only on a matched rule, never on
general uncertainty. Every rejection writes one activity-log line with the rule that fired, so
a false reject is diagnosable.

### 10.4 Durability

- Write the `RawCapture` row **synchronously inside `onNotificationPosted`**, before any
  filtering that requires I/O, then enqueue a WorkManager job. The listener process can die at
  any moment; the row must already exist.
- On `onListenerConnected`, call `getActiveNotifications()` and replay anything whose
  fingerprint is unseen.
- On `onListenerDisconnected`, call `requestRebind`.
- Every entry point wrapped in a top-level try/catch. **An uncaught exception kills the
  notification listener**, which kills call detection with it.

---

## 11. Call capture

### 11.1 The app must not record calls

Android does not permit third-party call-audio capture. The app **consumes recordings written
by the device's own dialer**. If the user has not enabled call recording in their phone app,
call capture cannot work — detect this during onboarding (§18) and say so plainly rather than
failing silently.

### 11.2 Detecting call end — three independent triggers

On HyperOS any single mechanism can be silently blocked. Implement all three plus two recovery
paths.

| Trigger | Mechanism | Fails when |
|---|---|---|
| Call-log observer | `ContentObserver` on `CallLog.Calls.CONTENT_URI`, registered **inside the notification listener process** | `READ_CALL_LOG` not granted |
| Telephony callback | `TelephonyCallback.CallStateListener` in a foreground service | The service is killed |
| Static receiver | `PHONE_STATE` broadcast receiver | MIUI **Autostart** is off |

Recovery paths: a sweep triggered by incoming notification traffic, and a periodic
`AlarmManager` watchdog (`setAndAllowWhileIdle`, ~15 min in Doze).

**Why the listener process matters:** a `NotificationListenerService` is system-bound and is
therefore the only context that can reliably start a foreground service from the background on
HyperOS. Host the observer and the recovery sweep there.

Process only completed incoming/outgoing calls. Ignore missed and rejected calls.

**Duration filtering — failure mode #4.** A query filtering `duration >= 15` is *false* for
NULL in SQL, so every call with unknown duration was silently skipped. Write it as
`(duration IS NULL OR duration >= :minDuration)` and unit-test the NULL case. Apply this rule
to **every** numeric filter in the codebase.

### 11.3 Finding the recording file

Requires `MANAGE_EXTERNAL_STORAGE` (All Files Access), granted from a system settings screen.
Detect denial explicitly with `Environment.isExternalStorageManager()` — discovery fails
silently without it.

Search, in order: a user-nominated directory (SAF-picked, persisted), then the known-paths
list below (each scanned one subdirectory deep), then a `MediaStore.Audio` query filtered on
path keywords (`call`, `record`, `phone`, `voice`, `dialer`, `rec`).

```
/storage/emulated/0/MIUI/sound_recorder/call_rec
/storage/emulated/0/MIUI/sounds/Call
/storage/emulated/0/Recordings/Call recordings
/storage/emulated/0/Recordings/Call Recording
/storage/emulated/0/Sound Recorder
/storage/emulated/0/Music/Recordings/Call Recordings
/storage/emulated/0/Record/PhoneRecord
/storage/emulated/0/Record/Call
/storage/emulated/0/Recordings/Record/Call
/storage/emulated/0/Recordings/Call
/storage/emulated/0/Recordings/CallRecordings
/storage/emulated/0/Documents/Call Recordings
/storage/emulated/0/Recorder/CallRecord
/storage/emulated/0/CallRecording
/storage/emulated/0/PhoneCallRecordings
/storage/emulated/0/Sounds
```

Extensions: `m4a`, `amr`, `3gp`, `mp3`, `wav`, `aac`, `opus`, `ogg`.

Match a file to a call by: `lastModified` within `[callStart - 60s, callEnd + 180s]`, and
prefer a filename containing the phone number's last 6 digits when one exists. Take the newest
match.

**Timing.** The recorder is still flushing when the call-end trigger fires. Retry discovery at
3 s, 6 s, 10 s, 20 s, and if still nothing keep a "call pending" marker alive for ~5 minutes so
later sweeps retry. Additionally require the file size to be **stable across two reads 1 s
apart** before uploading, or you will transcribe a half-written file.

**Do not pre-check** — failure mode #7. A sweep that asks "is there a recording?" before
starting the worker that has its own retry loop will always answer "no" immediately after a
call, and skip. Start the worker; let it do the looking.

**Mark processed only after the result is persisted** — failure mode #5.

### 11.4 Xiaomi HyperAI transcript — manual import only

The HyperOS Recorder can transcribe a call with Xiaomi's cloud AI and its Hindi quality is
good. **It cannot be automated.** The flow is: open Recorder → tap the recording → *Show text*
→ pick Hindi → wait → ⋮ → *Copy*. It is user-initiated by design, with no share action, no
export, and no documented readable transcript file.

Driving that UI with an `AccessibilityService` is technically possible and is the wrong choice:
it breaks on every Recorder update, it requires a permission that grants the app the ability to
read every screen the user sees, and a silent breakage looks identical to a pipeline bug. Not
in v1. Not in v2 either, unless the cloud path proves inadequate.

What to build instead — an **Import transcript** screen:

- On open, read the clipboard and prefill the text area if it looks like a transcript.
- Parse the speaker-diarised format:
  ```
  Speaker 1 00:00:00
  हाँ जी।
  Speaker 2 00:00:03
  दिल्ली रोड पे हूँ मंडी पे ठीक।
  ```
- Strip the timestamps (noise for extraction), **keep the speaker labels** — "send me the
  report" means something different depending on who said it. Merge consecutive turns by the
  same speaker.
- **Careful:** a line like `5:30 baje milte hain` is *speech*, not markup. Only treat a time as
  markup when the line matches `^\s*(Speaker\s*\d+|वक्ता\s*\d+)?\s*\d{1,2}:\d{2}(:\d{2})?\s*$`
  — i.e. the time is alone on its line, optionally preceded by a speaker label.
- Let the user attach the transcript to a specific recent call from the call list, so the
  extracted tasks get correct provenance and `occurredAt`.
- Unparseable input is still accepted as plain text and extracted, with a note in the log.

---

## 12. Transcription

1. Decode to **16 kHz mono PCM WAV** before sending (`MediaCodec` + `MediaExtractor`; do not
   ship FFmpeg).
2. If the decoded audio exceeds **the provider's limit** (assume 25 MB / ~25 minutes for
   OpenAI-compatible; Sarvam's sync endpoint caps around 30 s per request, so always chunk for
   Sarvam), split into chunks of at most 25 s of speech, **cutting at the quietest 100 ms
   window** within ±3 s of each nominal boundary so words are not sliced. Join transcripts with
   a single space.
3. Checkpoint per chunk in `RawCapture.attemptCount` / a chunk-index column, so a killed
   process resumes rather than restarting.
4. On failure after backoff, the capture stays `PENDING_TRANSCRIPTION` with the recording path
   intact. **Never discard the call.**
5. Speaker labels are unavailable from these ASR providers. Do not fabricate them. The call
   extraction prompt is written to cope with an unlabelled transcript.

---

## 13. Grounding — the evidence matcher

§14's prompts require every task to carry an `evidence` field containing the exact source
words. **A task whose evidence cannot be located in the source is dropped in code, not by the
model.** This is the single strongest anti-hallucination device available: the model cannot
invent a task without also inventing a quote, and the quote is mechanically checkable.

The prior spec never defined the comparison, and a literal `contains` check is a trap — models
normalise whitespace, swap Devanagari for Latin, drop a *matra*, or tidy punctuation. A strict
matcher turns a precision-first design into a recall-zero one.

```
fun normalizeForMatch(s: String): String =
    s.normalize(NFKC)
     .lowercase()
     .replace(ZERO_WIDTH_AND_CONTROL, "")
     .replace(Regex("[^\\p{L}\\p{N}]+"), " ")   // Unicode-aware: Devanagari survives
     .trim()

fun evidenceMatches(evidence: String, source: String, tolerance: Double): Boolean {
    val e = normalizeForMatch(evidence)
    val s = normalizeForMatch(source)
    if (e.isEmpty() || e.length < 8) return false
    if (s.contains(e)) return true

    val eTokens = e.split(" ")
    val sTokens = s.split(" ")
    if (eTokens.size < 2) return false
    val window = (eTokens.size * 1.6).toInt().coerceAtMost(sTokens.size)
    // slide a window over the source; score = |intersection| / |eTokens| (containment)
    var best = 0.0
    for (i in 0..(sTokens.size - window).coerceAtLeast(0)) {
        val slice = sTokens.subList(i, (i + window).coerceAtMost(sTokens.size)).toSet()
        val hit = eTokens.count { it in slice }.toDouble() / eTokens.size
        if (hit > best) best = hit
    }
    return best >= tolerance
}
```

Tolerance by source:

| Source | Tolerance |
|---|---|
| `NOTIFICATION` | **0.90** — text is exact, the model has no excuse |
| `CLIPBOARD` | **0.85** |
| `CALL` (ASR output) | **0.75** — the transcript itself is noisy |

Both thresholds are settings-tunable. Every failed match logs the evidence string, the source,
and the best score achieved — this is the diagnostic you will need most.

---

## 14. Extraction

### 14.1 Date and time handling — previously undefined

- The timezone is **`Asia/Kolkata`**, always. Not the device default.
- Relative expressions resolve against `RawCapture.occurredAt`, **not** the current time. A
  notification processed the next morning after a backlog drain must still treat "kal" as the
  day after the message arrived.
- The prompt is given `occurredAt` formatted as `EEEE d MMMM yyyy, HH:mm` in IST.
- A returned `dueDate` that fails ISO-8601 parsing → treat as null, do not guess.
- A returned `dueDate` **more than 12 hours before `occurredAt`** → the model resolved it
  wrong. Set `dueAt = null`, keep the task, and append the raw string to `notes` as
  `"stated deadline: <raw>"`. Never silently shift it forward a year.
- A date with no time → 18:00 IST.
- A `dueDate` more than 2 years in the future → treat as null.

### 14.2 Confidence gating

| Confidence | Action |
|---|---|
| ≥ 0.75 | Create the task automatically |
| 0.40 – 0.75 | Send to the **Review Inbox** — one tap to accept or dismiss |
| < 0.40 | Discard, log only |
| missing / unparseable | Treat as **uncertain**, never as certain → Review Inbox |

Both thresholds are settings-tunable with a "reset to defaults" button. Accepting a review item
calls `IntakeFunnel.submit()` with `sourceType = REVIEW` — it does **not** insert directly.

### 14.3 Notification extraction prompt

```
You decide whether ONE incoming message creates a task for the user of a personal task
manager. Your verdict is final and goes straight onto their list, so a wrong task costs
them more than a missed one.

Messages are in Hindi, English, or Hinglish (Hindi in Latin script). Read them as a native
speaker of Indian English would.

THREE TESTS — all must pass for isTask=true:
1. A specific person is asking or expecting THE USER to do something, or the user has
   committed to do something. Automated senders, systems and broadcasts never assign tasks.
2. The action is concrete: a verb and an object, something tickable.
   "Send the invoice" passes. "We should catch up sometime" does not.
3. The user could reasonably be the one to act. In a group chat where the request names a
   specific OTHER person as the doer, return false. If the group request is ambiguous about
   who should act, return true with confidence at most 0.7.

NEVER a task, regardless of wording: OTPs and verification codes, payment or bank
confirmations, delivery and order status, promotions and offers, news, social-media
activity, app or system alerts.

You are given the date and time the message arrived. Resolve every relative expression
against THAT moment — "kal", "parso", "aaj shaam", "tomorrow", "by Friday", "5 baje".
"kal" as a deadline means the following day. If a date is given with no time, use 18:00.
All times are India Standard Time.

GROUNDING — the strictest rule here:
- The "evidence" field must quote the source message EXACTLY, word for word. Do not
  paraphrase, translate, or tidy it. Software checks it against the original and discards
  the task if it does not match.
- If you cannot supply exact evidence, the task does not exist. Return isTask=false.
- Never infer unstated details. No invented amounts, names, dates or recipients.

Respond with ONLY this JSON, no markdown:
{
  "reasoning": "<1-2 sentences: who wants what from whom, and which tests pass or fail>",
  "isTask": true|false,
  "evidence": "<exact quote from the message, or null if isTask is false>",
  "title": "<imperative, <=60 chars, naming the concrete specifics, in English; null if not a task>",
  "priority": "URGENT|HIGH|MEDIUM|LOW",
  "dueDate": "<ISO 8601 date-time with +05:30 offset, or null if none stated>",
  "notes": "<amounts, references, context worth keeping; null if none>",
  "confidence": <0.0-1.0, how certain you are this is a real task for this user>
}

Priority: URGENT = explicit urgency or a deadline within ~24h (urgent/ASAP/abhi/aaj/turant).
HIGH = deadline 1-3 days, or clearly important (kal tak/by tomorrow). MEDIUM = a real task
with no stated urgency. LOW = optional (jab time mile).

EXAMPLES

[Mon 7 July 2025, 2:00 PM] WhatsApp from "Sharma Ji": "beta woh 25000 ka payment kal tak kar dena warna late fee lagegi"
{"reasoning":"Sharma Ji directly asks the user to pay 25000 by tomorrow. Personal, concrete, aimed at the user. All three pass.","isTask":true,"evidence":"woh 25000 ka payment kal tak kar dena","title":"Pay ₹25,000 to Sharma Ji","priority":"HIGH","dueDate":"2025-07-08T18:00:00+05:30","notes":"Late fee applies if missed","confidence":0.95}

[Mon 7 July 2025, 2:00 PM] WhatsApp group "College Friends" from "Amit": "bhai Rohit tu hi book kar le tickets, tera card pe offer hai"
{"reasoning":"Amit names Rohit as the one to book. Test 3 fails — a specific other person is the doer.","isTask":false,"evidence":null,"title":null,"priority":"LOW","dueDate":null,"notes":null,"confidence":0.9}

[Mon 7 July 2025, 2:00 PM] SMS from "HDFCBK": "Rs.4,500 debited from a/c XX1234 for UPI txn. Avl bal: Rs.52,310"
{"reasoning":"Automated bank confirmation. No person, no request. Test 1 fails.","isTask":false,"evidence":null,"title":null,"priority":"LOW","dueDate":null,"notes":null,"confidence":0.99}

[Mon 7 July 2025, 2:00 PM] WhatsApp from "Priya": "haan sab theek! chalo phir baat karte hain, bye"
{"reasoning":"Small talk closing a chat. No action requested or committed. Test 2 fails.","isTask":false,"evidence":null,"title":null,"priority":"LOW","dueDate":null,"notes":null,"confidence":0.97}
```

Empty output is a valid and expected answer. Most messages are not tasks.

### 14.4 Call transcript extraction prompt

```
You extract commitments from a phone-call transcript for a personal task manager used by an
Indian professional. The transcript may be Hindi, English or Hinglish and WILL contain
speech-recognition errors — read for intended meaning, but never invent content.

You are given the date and time the call took place. Resolve every relative expression
against THAT date, not today. All times are India Standard Time.

The transcript MAY label speakers. If it does, use those labels to decide WHO committed to
what. If it does not, decide from context, and lower your confidence accordingly — an
unlabelled transcript rarely supports confidence above 0.8.

PRECISION RULES — accuracy matters far more than completeness:
- Extract only commitments that were ACTUALLY SPOKEN. Never infer, embellish or complete a
  half-finished thought.
- If a section is garbled, skip it. A garbled section is not a licence to guess.
- Merge near-duplicate commitments into one task.
- Titles must name concrete specifics from the call — names, amounts, documents. Never a bare
  "Follow up".
- Small talk, opinions and general discussion are not tasks. A task needs someone asking for,
  or agreeing to, a specific action.

GROUNDING:
- Every task carries "evidence": the exact transcript words that justify it, copied verbatim.
  Software checks this against the transcript and drops any task whose evidence is not found.
- No evidence means no task.

Return ONLY this JSON, no markdown:
{
  "reasoning": "<list each commitment found, who made it, its deadline; or state there are none>",
  "summary": "<2-3 sentences on what was discussed>",
  "topics": ["<short phrase>", ...],
  "tasks": [
    {
      "title": "<imperative, <=60 chars, quoting specifics>",
      "evidence": "<exact transcript quote>",
      "priority": "URGENT|HIGH|MEDIUM|LOW",
      "dueDate": "<ISO 8601 with +05:30, resolved from the call date, or null>",
      "assignedToMe": <true if the user must act, false if the other party committed>,
      "notes": "<names, amounts, references; null if none>",
      "confidence": <0.0-1.0>
    }
  ]
}

Priority: URGENT = within 24h of the call, or urgent/ASAP/abhi/aaj tak. HIGH = 2-3 days, or
kal tak/important. MEDIUM = no stated urgency. LOW = optional, "jab time mile".

Common Hindi/Hinglish action phrases: "bhej dena", "bhej do", "kar dena", "dekh lena",
"bata dena", "call karna", "confirm karo", "meeting rakhna", "payment karna", "forward karna".

If there are no action items, return "tasks": []. That is a correct and common answer.
```

Tasks with `assignedToMe: false` are still created, tagged `waiting-on`, so the user can chase
the other party. That is a feature, not a leak.

### 14.5 Verify pass

A second LLM call, run on every batch of candidates before the funnel. Skip it only if
`settings.verifyPass` is off (default on) or the daily budget is short.

```
You are a strict reviewer of tasks extracted from a source text. You receive the source and
a list of candidate tasks. For each candidate, judge it against the source:

- "keep"  — clearly stated in the source, and the title and date are accurate
- "fix"   — the commitment is real but the title or dueDate is wrong; supply corrections
- "drop"  — not actually stated, a duplicate, or ordinary conversation misread as a task

Check each candidate's "evidence" against the source. If those words do not appear, the
verdict is "drop" regardless of how plausible the task sounds.

Be strict. When in doubt, drop. A wrong task costs more than a missed one.

Return ONLY JSON:
{"verdicts":[{"index":0,"verdict":"keep|fix|drop","title":<corrected or null>,"dueDate":<corrected or null>,"reason":"<short phrase>"}]}
```

A `fix` verdict that changes the title does **not** invalidate the evidence — the evidence
check in §13 runs against the original source either way.

---

## 15. Activity log and self-test

The activity log is the primary debugging instrument, because there is no local machine.

Every stage writes at least one line: capture received, pre-filter verdict + rule, ASR
started/finished + duration + provider, LLM call + model + token count, evidence check result
+ score, funnel verdict, dedup hit, task created. Levels: DEBUG / INFO / WARN / ERROR, with a
level filter in the UI and a "share as text file" action.

**Self-test** (settings → Diagnostics) must exercise the **production path end to end**, not a
parallel one. Failure mode #8: the prior self-test checked only discovery and transcription,
reported success, and pointed debugging away from the broken extraction stage for weeks.

The self-test:
1. Reports every permission's actual current state.
2. Runs the ASR connection test on a bundled sample.
3. Injects a **synthetic notification** through the real `handleNotification()` entry point
   with a known Hinglish task message, and asserts a task appears with the expected title.
4. Injects a **synthetic transcript** through the real call extraction path and asserts the
   same.
5. Deletes both synthetic tasks afterwards and reports each step's outcome and timing.

---

## 16. Task manager — v1 scope

The task list is the product. It must stand on its own against a paid app.

**Core** — create, edit, delete, complete, reopen; four priorities with visual weight; due date
**and time**, plus "no date"; notes; archive (distinct from delete, recoverable); sub-tasks;
projects and tags with filtering.

**Views** — Today / Upcoming / Overdue / Completed / Archived / All; sort by due date, priority
or creation; search across title and notes; grouping by project or date. **Every view has an
empty state** — a list with no empty state ships as a blank rectangle.

**Behaviour** — recurring tasks (`DAILY|WEEKLY|MONTHLY|CUSTOM:<n><unit>`; on completion, the
next instance is created from the rule); reminders via `AlarmManager` + notification; snooze
(+1h, +3h, tomorrow 9am, custom); bulk multi-select for complete/delete/re-prioritise; swipe
to complete and to archive; **undo for every destructive action** via snackbar.

**Provenance — the differentiator** — every auto-created task shows contact/app/call and
timestamp; tap through to the originating message text or transcript excerpt; the evidence
quote is displayed on the task detail screen; the engine that produced it is shown; the
**Review Inbox** shows the source text with one-tap accept/dismiss and a visible confidence
figure.

**Data** — export and import as JSON and CSV; local backup and restore to a SAF-picked file;
"Erase all captured content" that clears raw captures, transcripts, audio and logs **but keeps
tasks**.

**Polish** — dark and light themes following system; full accessibility (content descriptions,
4.5:1 contrast, 200% font scaling without clipping); Devanagari and Latin render correctly
everywhere, including in notification titles and export files.

---

## 17. Background reliability

Expect more effort here than in features.

### 17.1 Foreground services — Android 15

- Exactly **two** foreground services. `ResidencyService` uses type **`specialUse`** (exempt
  from the daily budget) and hosts the telephony callback and the watchdog. `WorkerService`
  uses type **`dataSync`** for bounded upload/inference work and stops the moment work
  completes, never on a fixed timer.
- `dataSync` has a **cumulative ~6 h/day budget**. Exceeding it throws
  `ForegroundServiceDidNotStopInTimeException` and **kills the app**.
- Implement **both** `onTimeout(startId)` (API 34) and `onTimeout(startId, fgsType)` (API 35).
  Android 15 calls the two-arg form for `dataSync`; implementing only the one-arg version means
  the handler never fires and the app crashes. **This happened** — failure mode #2, and the
  crash killed the notification listener, which killed call detection.
- Track cumulative `dataSync` seconds per rolling day in DataStore and defer non-urgent work
  below a 30-minute reserve.
- Prefer WorkManager with constraints (charging, unmetered) for anything that can wait.

### 17.2 MIUI / HyperOS

- **Autostart** is a separate Xiaomi permission. With it off, static broadcast receivers never
  fire and background services are killed regardless of Android battery settings. Detect
  `Build.MANUFACTURER` and deep-link the user to it during onboarding:
  `ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")`,
  wrapped in try/catch with a fallback to app details plus written instructions.
- Battery optimisation must be disabled for the app
  (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`).
- Background service starts are blocked from ordinary contexts. Components hosted in the
  system-bound notification listener are the reliable path.

### 17.3 Notifications the app itself posts

**Silent by default** — failure mode #3, where two services posted a progress notification on
every short-timer invocation and the app buzzed all day.

- Foreground-service notifications use `FOREGROUND_SERVICE_DEFERRED` so short runs never draw,
  on a low-importance channel.
- The only notifications with sound: **a task was created** (grouped, at most one summary per
  5 minutes) and **a reminder fired**.
- No notification for "processing", "syncing", "checking", or any failure that will be retried.
  Failures live in the activity log and on the status screen.
- Never start a service on a timer just to poll.

### 17.4 General

- Watchdog alarm via `setAndAllowWhileIdle` ~15 min. It **reschedules itself first**, before
  doing any work, so a crash in its body cannot break the chain.
- Restart everything on `BOOT_COMPLETED` and on `MY_PACKAGE_REPLACED`.
- Re-check every permission on app start and on every watchdog cycle; a revocation must appear
  on the status screen within one cycle, not silently disable a feature.
- **Never delete user data during recovery** — failure mode #6. If Room reports corruption, the
  migration/recovery path must first attempt to export all tasks to a timestamped JSON file in
  app storage, and must never call `fallbackToDestructiveMigration()`. Write real migrations.

### 17.5 Permissions

```
BIND_NOTIFICATION_LISTENER_SERVICE   (special, via system settings)
MANAGE_EXTERNAL_STORAGE              (special, via system settings)
REQUEST_INSTALL_PACKAGES             (for self-update — previously missing)
READ_CALL_LOG, READ_PHONE_STATE, READ_CONTACTS, POST_NOTIFICATIONS   (runtime)
FOREGROUND_SERVICE
FOREGROUND_SERVICE_DATA_SYNC
FOREGROUND_SERVICE_SPECIAL_USE
RECEIVE_BOOT_COMPLETED
WAKE_LOCK
SCHEDULE_EXACT_ALARM  (reminders; request USE_EXACT_ALARM fallback)
INTERNET, ACCESS_NETWORK_STATE       (normal)
```

---

## 18. Onboarding

Not a footnote. With zero permissions the app does nothing, so this is a first-class flow with
a screen per step, each showing live granted/not-granted state and each skippable.

1. **What this app does**, in three lines, plus the privacy statement (§20). An explicit
   checkbox: *"Send my message text and call audio to the cloud provider I configure."*
   Capture stays disabled until this is ticked.
2. **AI provider setup** — LLM and ASR configuration with presets and Test connection (§8.3).
   Skippable; capture still records and parks.
3. **Notification access** — deep link to
   `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`, then verify on resume.
4. **Choose which apps to watch** — the allow-list, with WhatsApp and SMS pre-ticked.
5. **Battery optimisation** — request exemption.
6. **Xiaomi Autostart** — shown only on Xiaomi devices, with the deep link and a screenshot-
   grade written description of what to tap.
7. **Call capture (optional)** — explains that the app cannot record calls itself, checks
   whether call recording appears to be enabled by looking for any file in the known paths,
   and says plainly if none is found. Then requests `READ_CALL_LOG`, `READ_PHONE_STATE`,
   `READ_CONTACTS` and All Files Access.
8. **Done** → the **Status screen**, which is permanently reachable from the task list and
   shows: every permission's state, both provider connection states, today's budget usage,
   the count of parked captures by state, the last 5 activity-log lines, and buttons for
   Self-test and Log export.

---

## 19. Self-update

Required, because no store will do it.

- A daily `WorkManager` job (unmetered, not urgent) fetches an update manifest from a URL set
  in settings — default a raw GitHub URL for the repo:

  ```json
  {
    "versionCode": 12,
    "versionName": "1.2.0",
    "apkUrl": "https://github.com/<owner>/<repo>/releases/download/v1.2.0/taskmind.apk",
    "sha256": "…",
    "releaseNotes": "…",
    "mandatory": false
  }
  ```

- If `versionCode` exceeds the installed one, show an in-app banner (no notification unless
  `mandatory`). On tap: download to app cache with progress, **verify the SHA-256 before
  doing anything with the file**, then install via `PackageInstaller` (fall back to an
  `ACTION_VIEW` intent with a `FileProvider` URI).
- The APK must be signed with the **same keystore** as the installed build or the update
  silently fails. This is why §3.1 signs in CI from a fixed secret.
- Never delete the downloaded APK before the install session reports a result.

---

## 20. Privacy

Message text and call audio leave the device and go to a third-party API. Say so, plainly,
once, on the onboarding screen and again in settings:

> TaskMind sends the text of watched messages and your call recordings to the AI provider you
> configure, so it can find tasks in them. Nothing is sent anywhere else, and nothing is sent
> until you add a key. Free API tiers commonly reserve the right to train on what you submit —
> check your provider's terms.

Also worth surfacing once: the other party on a call has not consented to cloud transcription.
State it in the call-capture onboarding step and move on; it is the user's call to make.

---

## 21. Known failure modes — design against these from day one

Every one of these actually happened.

1. **Tasks written to the wrong table.** The call path wrote extracted tasks to a sync outbox
   instead of the task table. With sync disabled, they sat there forever. Calls were processed
   correctly and produced zero visible tasks for weeks. → **One intake funnel, always** (§5, §7).
2. **`onTimeout` signature mismatch.** Only the one-arg overload was implemented, so Android 15
   never called it and killed the app on the dataSync budget. The crash killed the notification
   listener, which killed call detection. → **Implement both overloads** (§17.1).
3. **Notification spam.** Two background services posted a progress notification on every
   invocation while being started on short timers. → **Silent by default;
   `FOREGROUND_SERVICE_DEFERRED`; never poll on a timer** (§17.3).
4. **NULL excluded by a numeric filter.** `duration >= 15` is false for NULL in SQL, so every
   call with unknown duration was silently skipped. → **Handle NULL explicitly in every numeric
   filter** (§11.2).
5. **Marked processed before persisting.** A transient failure then erased the recording from
   the search path permanently. → **Persist first, mark second** (§7, §11.3).
6. **Corruption recovery deleted user data.** The DB reset path was written when tasks lived in
   the cloud and the local file was disposable. → **Salvage before any destructive recovery;
   no destructive migrations** (§17.4).
7. **Racy pre-check defeated a retry loop.** A sweep checked "is there a recording?" before
   starting the service that had its own retry loop; right after a call the file is still
   being written. → **Do not pre-check what the callee already handles better** (§11.3).
8. **Diagnostic that did not test the real path.** The self-test exercised only discovery and
   transcription, reported success, and pointed debugging away from the broken extraction stage
   for a long time. → **A self-test must run the production path** (§15).

---

## 22. Build order — phase gates

Build in this order. **Commit after each phase, and each phase must compile and pass its tests
before the next begins.** Do not build features before the pipeline that feeds them works.

| Phase | Contents | Gate |
|---|---|---|
| 1 | Project skeleton, `libs.versions.toml`, CI workflow, Room schema, `AppContainer`, ActivityLog, Status screen shell | CI produces a signed APK |
| 2 | Task manager: CRUD, all views, filters, search, archive, undo, empty states, manual entry | Usable as a plain task app on-device |
| 3 | `IntakeFunnel` + evidence matcher + titleKey + dedup index | Unit tests green, including the "all sources converge" test |
| 4 | Provider config, settings screen, connection tests, budget accounting | Test connection succeeds against a real key |
| 5 | Notification capture → pre-filter → LLM → funnel → task | A WhatsApp request creates a task on the real device |
| 6 | Call capture: three triggers, discovery, ASR, extraction, CallRecord UI | Make a call, hang up, touch nothing, task appears |
| 7 | Background hardening: watchdog, boot, onTimeout overloads, MIUI onboarding, self-test | 24h with no crash and no stray notification |
| 8 | Clipboard import, recurring tasks, reminders, export/import, self-update, drag-reorder | |

**Test on the real device from Phase 5 onward.** An emulator cannot reproduce MIUI Autostart,
the foreground-service budget, or the dialer's recording behaviour — which is where nearly all
the difficulty lives.

---

## 23. Definition of done

- Make a call, hang up, touch nothing: a task appears within a few minutes, correctly
  attributed to the caller.
- Receive a WhatsApp message containing a request with the app force-stopped: a task appears.
- No network for an hour, then reconnect: every parked capture drains and produces its tasks.
- No API key configured: the app is fully usable for manual tasks, captures accumulate, and
  adding a key later drains the backlog.
- 24 hours of ordinary use: no notification other than task confirmations and reminders.
- 7-day soak: no crash, no unbounded table growth, attributable battery ≤ 4 %/day.
- Every task in the list can be traced to the exact words that created it.
- **No task exists that was not said.**

---

## 24. Explicitly out of scope for v1

On-device LLM or ASR of any kind · Hugging Face model browsing · Google Tasks or any cloud sync
· home-screen widget · AccessibilityService automation of the Xiaomi Recorder · iOS · multi-
device · a backend server of any kind · Play Store distribution.

Structure the extraction and transcription layers behind `TaskExtractor` and `Transcriber`
interfaces so a local implementation can be added in v2 without touching the funnel.
