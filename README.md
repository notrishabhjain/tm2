# TaskMind

An Android app that captures your work commitments automatically and turns them
into tasks, without you ever typing one.

It watches two things:

- **Messages** — WhatsApp, SMS and whatever else you allow. *"beta woh 25000 ka
  payment kal tak kar dena"* becomes a task due tomorrow.
- **Calls** — the recordings your own phone app already makes get transcribed,
  and commitments spoken during the call become tasks.

Both feed a full task manager inside the same app. Hindi, English and Hinglish
are the normal input, not an edge case.

---

## Getting the app on your phone

You do not need a computer, Android Studio, or anything installed. GitHub builds
the APK for you.

1. Open **[Releases](../../releases)** on your phone.
2. Under the newest release, tap **`taskmind.apk`** to download it.
3. Open the downloaded file. Android will ask whether to allow installing from
   this source — say yes.
4. Open TaskMind and follow the setup screens.

Every push to `main` produces a new release automatically. If you'd rather grab a
build from a branch, open the **Actions** tab, click the newest run, and download
the `taskmind-apk-…` artifact at the bottom.

### After you install

TaskMind can update itself, so you only have to do the above once. In
**Settings → Updates**, set the manifest URL to:

```
https://github.com/<your-username>/<your-repo>/releases/latest/download/update.json
```

It then checks daily on Wi-Fi and offers the new version in the app.

---

## Setting it up

The setup flow walks through this, but here is what actually matters, in order
of how much it matters.

**1. An AI provider key.** TaskMind sends message text and call audio to a cloud
provider you choose. Without a key it still captures everything and holds it —
nothing is lost — but no tasks are created until you add one.

- **Extraction (reading messages):** any OpenAI-compatible endpoint. Groq has a
  generous free tier and is fast. OpenAI and OpenRouter are preset too.
- **Transcription (calls):** **Sarvam AI** is purpose-built for Indian languages
  and is materially better than Whisper on Hindi phone audio. Groq's
  `whisper-large-v3` is the free alternative.

There is a **Test connection** button for each. Use it — a wrong base URL looks
exactly like a broken app otherwise.

**2. Notification access.** Without this, message capture does nothing at all.
It's granted in system settings, not by a normal permission prompt.

**3. Xiaomi Autostart.** On a Redmi or Xiaomi phone this one is easy to miss and
breaks everything quietly. With Autostart off, Android kills TaskMind in the
background and call detection stops with no error anywhere. The setup screen
deep-links you to it.

**4. Battery optimisation off**, for the same reason.

**5. Call recording.** TaskMind **cannot record calls** — Android does not permit
it. It reads what your phone's own dialer writes. If call recording is off in
your dialer, call capture will do nothing, and the setup screen checks for this
and tells you plainly. It also needs All Files Access, because the dialer saves
recordings outside any app's own folder.

---

## Looking inside

An app that reads your messages and decides what becomes a task is asking for a
lot of trust. Four screens are the argument for it, all under **Settings**.

**How TaskMind decides** lists every rule the app applies — which apps get read,
each pre-filter pattern, how closely a quote has to match, where the confidence
thresholds sit, what the retry schedule is, what is stored and for how long —
with the value each one is using right now, read from live settings rather than
written into the text.

**Prompts** shows the exact instructions sent to the model. All three are
editable and take effect on the next capture, with a reset on each. Each one
says what it is for and what breaks if you change it carelessly.

**Model calls** keeps the last hundred requests: the system prompt, your data as
it was actually sent, and the unedited reply. This is how you check the privacy
statement against the bytes, and it is usually where a failure explains itself —
a provider refusing a model you never selected reads as a mystery in a log line
and as an obvious answer here.

**Activity log** records every decision for every capture: which pre-filter rule
fired, what score the quote check got, what the funnel decided.

## When something doesn't work

There is no `adb logcat` on a phone, so the app carries its own diagnostics.

**Status** (task list menu → Status and diagnostics) shows every permission's
real state, both providers, today's usage against your budgets, how many
captures are waiting and in which state, and the last few things that happened.

**Self-test** on that screen pushes a synthetic Hinglish message through the
*real* capture pipeline end to end, asserts a task came out, and deletes it. If
it passes, capture works; if it fails, it names the stage.

**Activity log** records every step of every capture: which pre-filter rule
rejected a message, what score the evidence check got, what the funnel decided,
whether it was a duplicate. Filter it by level and stage, and share it as text.

**Model calls** is usually faster when extraction produces nothing: it shows
whether the request went out and what the provider said back.

A note on picking models: TaskMind sends one prompt and expects one JSON object.
It has no use for a routing or agentic model's tool-calling — and those models
call other models underneath, so they fail with errors naming models you never
chose. A plain instruction-following model is faster and more predictable here.
Settings warns you if the model name looks like a router.

---

## What it does about being wrong

A wrong task costs more than a missed one, so:

- **Nothing is invented.** Every automatically created task carries the exact
  words that produced it, and those words are checked against the original
  source before the task exists. A quote that can't be found means no task.
- **Uncertain items wait.** Anything the model isn't confident about goes to a
  **Review inbox** with the source text and a confidence figure, for one tap to
  accept or dismiss. It doesn't guess into your list.
- **Nothing captured is lost.** No key, no network, spent budget, failed
  transcription — every failure parks the capture and retries it. Add a key a
  week later and the whole backlog drains.
- **It stays quiet.** The only notifications are a task being captured and a
  reminder firing. No progress, no syncing, no retry chatter.

Every threshold here is tunable in **Settings → Accuracy**, with a reset button.

---

## Costs

Cloud calls cost money, so there are ceilings, all in **Settings → Daily limits**:
model calls per day (300), calls per app per day (60), transcription minutes per
day (60), and Wi-Fi-only upload for call audio. Anything over a limit waits for
tomorrow rather than being dropped.

Before any of that, a free deterministic filter rejects OTPs, bank and delivery
alerts, promotional senders, group summaries, and anything from an app you
haven't allowed — which is the large majority of notifications.

---

## Privacy

> TaskMind sends the text of watched messages and your call recordings to the AI
> provider you configure, so it can find tasks in them. Nothing is sent anywhere
> else, and nothing is sent until you add a key. Free API tiers commonly reserve
> the right to train on what you submit — check your provider's terms.

Also worth saying once: the other person on a call has not agreed to their voice
being sent to a transcription service. That is your call to make.

API keys are stored encrypted and are excluded from backups. Message text,
transcripts and recordings are deleted on a schedule you set (7, 30 or 90 days) —
and deleting them never deletes the tasks that came from them, because the quote
and the source are kept on each task.

---

## For developers

Pure Kotlin, single module, Jetpack Compose with Material 3, Room, WorkManager,
OkHttp. No DI framework — a hand-written `AppContainer`. No on-device models.

The rule that matters: **`IntakeFunnel.submit()` is the only code that creates a
task.** Notification, call, clipboard, manual entry and review-accept all
converge there, and `ArchitectureTest` reads the source tree and fails the build
if a second path appears.

```
./gradlew testDebugUnitTest    # the funnel, evidence matcher, dedup, dates, filters
./gradlew assembleRelease      # signed APK
```

Signing is described in [`ci/README.md`](ci/README.md). The full specification
this was built from is in [`taskmind-build-spec-v2.md`](taskmind-build-spec-v2.md).
