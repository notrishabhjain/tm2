# TaskMind

An Android app that captures your work commitments automatically and turns them
into tasks, without you ever typing one.

It watches what you are committed to, wherever it was said:

- **Messages** — WhatsApp, SMS and whatever else you allow. *"beta woh 25000 ka
  payment kal tak kar dena"* becomes a task due tomorrow.
- **Calls** — the recordings your own phone app already makes get transcribed,
  and commitments spoken during the call become tasks.
- **Email** — mail apps' notifications (Gmail, Outlook, any account), or your
  mailbox read directly with an app password: the asks in mail sent to you and
  the promises in mail you send. Newsletters and receipts are skipped first.
- **Your own voice, screenshots and meetings** — say everything on your mind,
  share a screenshot, and get a prompt before and after each meeting.

Everything lands in one **Inbox** for a yes or no, and feeds a full task manager:
Today, areas, matters, routines. Hindi, English and Hinglish are the normal
input, not an edge case.

The **Twin** tab is the part that acts. Ask it anything ("what do I owe Sharma
ji?"), have it plan your day around your meetings or spread the week, and let it
draft the nudge to someone who owes you - which you send yourself. It proposes;
you apply with a tap; every change it (or a message) makes can be undone.

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

TaskMind can update itself, so you only have to do the above once. It checks
this repository's GitHub releases daily on Wi-Fi; **Health → Check for updates**
then **Download and install** fetches a new version, checked against the
release's checksum before it installs.

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

**Prompts** shows the exact instructions sent to the model. Every one is
editable and takes effect on the next capture, with a reset on each. Each one
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
real state, both providers, today's usage, how many captures are waiting and
in which state, and the last few things that happened. It also says when Data
Saver stops TaskMind using mobile data in the background, which looks exactly
like "tasks arrive late".

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
- **Nothing captured is lost.** No key, no network, a provider error, failed
  transcription — every failure parks the capture and retries it. Add a key a
  week later and the whole backlog drains.
- **It stays quiet.** The only notifications are a task being captured and a
  reminder firing. No progress, no syncing, no retry chatter.

## How fast it is

A task is meant to arrive while the message is still on screen.

- **Instant first read.** A plain ask or promise — "can you send the PO by 5",
  "kal tak bhej dena", "I'll send it tomorrow" — is read by rules on the phone
  and becomes an Inbox task with its notification the moment the message
  arrives, with no model call. The model reads the same message a few seconds
  later and has the last word: it rewrites the task in better words, or takes
  it back. Switch it off in **Settings → You and your chats → How many tasks**.
- **Read straight away.** Every capture is read inside the running app the
  moment it is written, rather than when Android next runs background work.
  WorkManager is only the safety net, for a closed app or a phone that is
  offline; captures waiting for the network are read the moment it is back.
- **A chat is read once it pauses for 8 seconds**, so lines sent in a row are
  read together.
- **Calls:** the recording is looked for over three and a half minutes after
  the call ends, and its audio is transcribed four pieces at a time.
- **No daily limits** and no Wi-Fi-only rule; mobile data works for everything.

Every threshold here is tunable in **Settings → Accuracy**, with a reset button.

---

## Costs

There are no daily caps: they held real captures back until midnight, which
is the opposite of what a second mind is for. Health counts today's model calls
and transcription minutes, and **Model calls** shows each request with its
token count, so spending stays visible.

What keeps the bill small is the free deterministic filter in front of
everything: it rejects OTPs, bank and delivery alerts, promotional senders,
group summaries, and anything from an app you haven't allowed — which is the
large majority of notifications.

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
