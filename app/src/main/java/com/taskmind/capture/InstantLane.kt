package com.taskmind.capture

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.core.content.getSystemService
import com.taskmind.core.CaptureState
import com.taskmind.core.LogLevel
import com.taskmind.core.Stage
import com.taskmind.diagnostics.NetworkState
import com.taskmind.di.AppContainer
import com.taskmind.work.Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads captures the moment they arrive, inside the running app.
 *
 * Every capture used to wait for WorkManager, and WorkManager runs when
 * Android lets it: on HyperOS, in the background, that was often minutes
 * later - and a chat's quiet-minute timer, the recording search after a call
 * and the transcription all went through the same queue, one wait after
 * another. The notification listener and the call watcher keep this process
 * alive anyway, so the work runs here, now. WorkManager stays as the safety
 * net: for a process that dies half-way, and for a phone that is offline,
 * since its network constraint waits for exactly the moment this lane cannot
 * see coming.
 *
 * Three lanes, because they wait on different things. Messages are quick and
 * must never queue behind a ten-minute call; call audio is slow; and the
 * search for a recording is mostly waiting for a dialer to finish writing.
 * Each lane runs one pass at a time - a kick while a pass runs queues exactly
 * one more - so WorkManager and the lane never read the same capture twice.
 */
class InstantLane(private val container: AppContainer) {

    private val context: Context get() = container.context

    private val messageLock = Mutex()
    private val audioLock = Mutex()
    private val messagesQueued = AtomicBoolean(false)
    private val audioQueued = AtomicBoolean(false)

    /** Calls whose recording is being searched for right now, by call record id. */
    private val searching = ConcurrentHashMap.newKeySet<String>()

    private val timerGuard = Any()
    private var timer: Job? = null
    private var timerAt = Long.MAX_VALUE

    private val started = AtomicBoolean(false)

    /** What a pass over a queue did. [retry] is true when something failed and will be tried again. */
    data class Pass(val read: Int, val retry: Boolean)

    /**
     * Starts watching the network, once per process: captures that arrived
     * offline are read the moment a usable network is back, not when
     * WorkManager next gets round to it. Registering also fires straight away
     * when a network is up, which reads whatever an earlier run left behind.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        val manager = context.getSystemService<ConnectivityManager>() ?: return
        runCatching {
            manager.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        kick("network available")
                        kickAudio("network available")
                    }

                    override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                        if (blocked) return
                        kick("network unblocked")
                        kickAudio("network unblocked")
                    }
                },
            )
        }.onFailure {
            container.logger.post(Stage.WORKER, LogLevel.WARN, "network watch not registered", it.toString())
        }
    }

    // ------------------------------------------------------------- messages

    /** Reads the message queue now. Cheap to call often: kicks while a pass is waiting coalesce. */
    fun kick(reason: String) {
        if (!messagesQueued.compareAndSet(false, true)) return
        container.applicationScope.launch(Dispatchers.IO) {
            try {
                messageLock.withLock {
                    messagesQueued.set(false)
                    if (!NetworkState.isUsable(context)) {
                        // WorkManager's request (queued alongside every kick)
                        // runs when the network is back, and so does the
                        // callback registered in start().
                        container.logger.write(Stage.WORKER, LogLevel.DEBUG, "instant read waiting for a network", reason)
                        return@withLock
                    }
                    passMessages()
                }
            } catch (t: Throwable) {
                messagesQueued.set(false)
                container.logger.write(Stage.WORKER, LogLevel.ERROR, "instant read failed", t.toString())
            }
        }
    }

    /** One pass for WorkManager's ExtractionWorker, after any pass already running. */
    suspend fun drainMessages(): Pass = messageLock.withLock { passMessages() }

    /**
     * Reads what is due, batch by batch, then sets the timer for whatever is
     * waiting (a chat still talking, a retry backing off). Callers hold
     * [messageLock].
     *
     * A batch that changed nothing ends the pass: captures that are parked
     * stay due on purpose (no key yet, a provider cooldown), and re-reading
     * them in a loop would spin without doing anything.
     */
    private suspend fun passMessages(): Pass {
        val dao = container.database.rawCaptureDao()
        val pipeline = container.extractionPipeline
        dao.releaseHeld()
        pipeline.releaseBlockedIfConfigChanged()

        var read = 0
        var retry = false
        var rounds = 0
        while (rounds++ < MAX_ROUNDS) {
            val batch = dao.dueForState(CaptureState.PENDING_EXTRACTION, System.currentTimeMillis(), BATCH)
            if (batch.isEmpty()) break
            var progressed = 0
            for (capture in batch) {
                // A message read earlier in this batch as part of its chat's
                // burst is already done; the batch was fetched before that.
                val fresh = dao.byId(capture.id) ?: continue
                if (fresh.state != CaptureState.PENDING_EXTRACTION) continue
                when (pipeline.process(fresh)) {
                    is ExtractionPipeline.Outcome.Retry -> retry = true
                    is ExtractionPipeline.Outcome.Deferred -> progressed++
                    is ExtractionPipeline.Outcome.Parked -> Unit
                    else -> {
                        progressed++
                        read++
                    }
                }
            }
            if (progressed == 0 || batch.size < BATCH) break
        }

        val next = dao.nextDueAfter(CaptureState.PENDING_EXTRACTION, System.currentTimeMillis())
        if (next != null) wakeAt(next)
        return Pass(read, retry)
    }

    /**
     * Comes back at [at] - the moment a chat has been quiet long enough, or a
     * retry falls due. One timer: an earlier time replaces a later one, and
     * the pass it starts sets the next. WorkManager gets the same request in
     * case this process is gone by then.
     */
    private fun wakeAt(at: Long) {
        val delayMillis = (at - System.currentTimeMillis()).coerceAtLeast(MIN_WAKE_MILLIS)
        synchronized(timerGuard) {
            val current = timer
            if (current != null && current.isActive && timerAt <= at) return
            current?.cancel()
            timerAt = at
            timer = container.applicationScope.launch {
                delay(delayMillis)
                synchronized(timerGuard) { timerAt = Long.MAX_VALUE }
                kick("due")
            }
        }
        if (delayMillis <= MAX_IN_PROCESS_WAIT_MILLIS) {
            runCatching { Scheduler.enqueueExtractionAfter(context, delayMillis) }
        }
    }

    // ------------------------------------------------------------ call audio

    /** Transcribes recordings that are waiting, now. */
    fun kickAudio(reason: String) {
        if (!audioQueued.compareAndSet(false, true)) return
        container.applicationScope.launch(Dispatchers.IO) {
            try {
                audioLock.withLock {
                    audioQueued.set(false)
                    if (!NetworkState.isUsable(context)) {
                        container.logger.write(Stage.WORKER, LogLevel.DEBUG, "transcription waiting for a network", reason)
                        return@withLock
                    }
                    passAudio()
                }
            } catch (t: Throwable) {
                audioQueued.set(false)
                container.logger.write(Stage.WORKER, LogLevel.ERROR, "instant transcription failed", t.toString())
            }
        }
    }

    /** One pass for WorkManager's TranscriptionWorker, after any pass already running. */
    suspend fun drainAudio(): Pass = audioLock.withLock { passAudio() }

    private suspend fun passAudio(): Pass {
        val dao = container.database.rawCaptureDao()
        reconcileAgainstCutoff()
        var read = 0
        var retry = false
        var rounds = 0
        while (rounds++ < MAX_ROUNDS) {
            val batch = dao.dueForState(CaptureState.PENDING_TRANSCRIPTION, System.currentTimeMillis(), AUDIO_BATCH)
            if (batch.isEmpty()) break
            var progressed = 0
            for (capture in batch) {
                when (container.transcriptionPipeline.transcribe(capture)) {
                    is TranscriptionPipeline.Outcome.Transcribed -> {
                        progressed++
                        read++
                        // Straight on to reading it: a call is not a chat, so
                        // there is no quiet period to wait out.
                        Scheduler.enqueueExtraction(context)
                    }
                    is TranscriptionPipeline.Outcome.Retry -> {
                        progressed++
                        retry = true
                    }
                    // Discovery has not found the recording yet. Left alone on
                    // purpose, and not counted: a batch of nothing but waiting
                    // rows must not loop.
                    TranscriptionPipeline.Outcome.Waiting -> Unit
                    else -> progressed++
                }
            }
            if (progressed == 0 || batch.size < AUDIO_BATCH) break
        }
        return Pass(read, retry)
    }

    /**
     * Brings the audio queue into line with the cutoff, in that order.
     *
     * Old call captures are retired: an upgrade inherits whatever the previous
     * build queued, all of it recorded before the app drew its line. What
     * survives that and is still waiting to be hand-picked is released -
     * transcription is automatic, so nothing waits for a tap any more.
     */
    private suspend fun reconcileAgainstCutoff() {
        val dao = container.database.rawCaptureDao()
        val cutoff = container.settingsRepository.current().recordingCutoffMillis
        if (cutoff > 0) {
            val retired = runCatching { dao.retireCallCapturesBefore(cutoff) }.getOrDefault(0)
            if (retired > 0) {
                container.logger.write(
                    Stage.CALL,
                    LogLevel.INFO,
                    "retired $retired old recording(s) from the queue",
                    "They were recorded before TaskMind started watching, so they are not " +
                        "transcribed. New calls are handled automatically.",
                )
            }
        }
        if (dao.byState(CaptureState.AWAITING_SELECTION, 1).isNotEmpty()) {
            dao.requeueRecordings(CaptureState.AWAITING_SELECTION, CaptureState.PENDING_TRANSCRIPTION)
            container.logger.write(
                Stage.CALL,
                LogLevel.INFO,
                "queued recordings that were waiting to be picked",
                "Transcription is automatic now, so nothing waits for a tap.",
            )
        }
    }

    // ---------------------------------------------------- finding recordings

    /** Sweeps the call log and starts looking for each new call's recording, now. */
    fun kickDiscovery(reason: String) {
        container.applicationScope.launch(Dispatchers.IO) {
            runCatching { passDiscovery(reason, wait = false) }
                .onFailure { container.logger.write(Stage.WORKER, LogLevel.ERROR, "recording search failed", it.toString()) }
        }
    }

    /**
     * One search per call, side by side. A dialer can take a while to finish
     * writing a long call, and that wait must not hold up the next call that
     * ends - so there is no lock here, only a note of which calls are already
     * being looked for. [wait] keeps WorkManager's worker alive until the
     * searches it started are over.
     */
    suspend fun passDiscovery(reason: String, wait: Boolean): Int {
        val pipeline = container.callPipeline
        pipeline.sweepCallLog(reason)
        val jobs = pipeline.pendingDiscovery()
            .filter { searching.add(it.id) }
            .map { record ->
                container.applicationScope.launch(Dispatchers.IO) {
                    try {
                        if (pipeline.discoverRecording(record.id)) Scheduler.enqueueTranscription(context)
                    } finally {
                        searching.remove(record.id)
                    }
                }
            }
        if (wait) jobs.joinAll()
        return jobs.size
    }

    private companion object {
        const val BATCH = 20
        const val AUDIO_BATCH = 3
        const val MAX_ROUNDS = 25

        /** Never sooner than this, so a capture due "now" cannot spin the timer. */
        const val MIN_WAKE_MILLIS = 1_000L

        /** Beyond this, a retry is WorkManager's to schedule; it survives a reboot. */
        const val MAX_IN_PROCESS_WAIT_MILLIS = 6 * 60 * 60 * 1000L
    }
}
