package com.taskmind.ui.tasks

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.taskmind.di.AppContainer
import com.taskmind.voice.VoiceNoteCapture
import com.taskmind.voice.VoiceNoteRecorder
import kotlinx.coroutines.launch

/**
 * Hold to talk, release to file it.
 *
 * TWO BUGS THIS IS THE FIX FOR
 *
 * The first version was inert on a real device, for two independent reasons,
 * and both are worth naming because both are easy to write again.
 *
 * 1. The permission was read as a plain value during composition. Granting it
 *    through the system dialog changed nothing this composable observed, so it
 *    never recomposed, so the gesture handler kept seeing the stale "denied"
 *    and returned immediately. Granting the permission had no effect until the
 *    app was restarted. It is state now, re-read when the screen resumes -
 *    which also covers the permission being revoked in system settings while
 *    the app is open.
 *
 * 2. It was a FloatingActionButton with an onClick. That installs a clickable
 *    inside the button, and an inner clickable wins the gesture against a
 *    pointerInput attached from outside - so detectTapGestures never ran at
 *    all. This is a Surface with no onClick, and the gesture detector is the
 *    only thing handling pointers.
 *
 * Hold-to-record rather than tap-to-start-tap-to-stop, because a note you are
 * dictating in ten seconds should not leave a recorder running if you get
 * distracted. Letting go always ends it, and sliding off the button before
 * letting go throws it away.
 */
@Composable
fun VoiceNoteButton(onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recorder = remember { VoiceNoteRecorder(context) }

    var recording by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }

    fun micGranted() = hasMicPermission(context)

    // State, not a value read during composition - see the note above.
    var granted by remember { mutableStateOf(micGranted()) }

    // Re-checked on resume so granting the permission in system settings, or
    // revoking it there, is noticed without restarting the app.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = micGranted()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching { recorder.cancel() }
        }
    }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { result ->
        granted = result
        onMessage(
            if (result) {
                "Microphone allowed. Hold the button and talk."
            } else {
                "Voice notes need the microphone permission."
            },
        )
    }

    fun handle(file: java.io.File?) {
        if (file == null) {
            onMessage("Hold the button a moment longer.")
            return
        }
        working = true
        scope.launch {
            val container = AppContainer.get(context)
            when (val result = VoiceNoteCapture.process(container, file)) {
                is VoiceNoteCapture.Result.Queued ->
                    // What it heard, not what it created: extraction runs in
                    // the background and may make one task, several or none,
                    // and promising a number here would sometimes be wrong.
                    onMessage("Heard: \"${result.text.take(80)}\" - reading it for tasks.")
                VoiceNoteCapture.Result.NoSpeech -> onMessage("Nothing was said in that note.")
                is VoiceNoteCapture.Result.Failed -> onMessage(result.message)
            }
            working = false
        }
    }

    // Grows while recording. The snackbar says what is happening, but the
    // thing under your finger should say it too.
    val scale by animateFloatAsState(if (recording) 1.15f else 1f, label = "mic")

    Surface(
        shape = CircleShape,
        color = when {
            recording -> MaterialTheme.colorScheme.errorContainer
            working -> MaterialTheme.colorScheme.surfaceVariant
            else -> MaterialTheme.colorScheme.secondaryContainer
        },
        shadowElevation = 6.dp,
        modifier = Modifier
            .size(56.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .semantics {
                contentDescription = when {
                    working -> "Working on your note"
                    recording -> "Recording, let go to finish"
                    granted -> "Hold to record a note"
                    else -> "Tap to allow the microphone"
                }
            }
            .pointerInput(granted, working) {
                detectTapGestures(
                    // Without the permission the button is an ordinary tap
                    // that asks for it. Handled here rather than by a
                    // clickable, so there is still exactly one thing
                    // consuming pointer events.
                    onTap = {
                        if (!granted) permission.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onPress = {
                        if (!granted || working) return@detectTapGestures
                        if (!recorder.start()) {
                            onMessage("The microphone could not be opened.")
                            return@detectTapGestures
                        }
                        recording = true
                        onMessage("Listening - let go when you are done.")

                        // False when the finger slid off instead of lifting,
                        // which is the gesture people use for "forget it".
                        val released = tryAwaitRelease()
                        recording = false
                        if (released) handle(recorder.stop()) else recorder.cancel()
                    },
                )
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Mic, contentDescription = null)
        }
    }
}

/** One spelling for the permission read, used on first composition and on resume. */
private fun hasMicPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
