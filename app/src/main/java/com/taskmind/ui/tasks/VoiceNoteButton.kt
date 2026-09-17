package com.taskmind.ui.tasks

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.taskmind.di.AppContainer
import com.taskmind.voice.VoiceNoteCapture
import com.taskmind.voice.VoiceNoteRecorder
import kotlinx.coroutines.launch

/**
 * Hold to talk, release to file it.
 *
 * Hold-to-record rather than tap-to-start-tap-to-stop, because a note you are
 * dictating in ten seconds should not leave a recorder running if you get
 * distracted. Letting go always ends it, and sliding off the button before
 * letting go throws it away.
 *
 * The button owns the recorder and nothing else: the words go to
 * [VoiceNoteCapture], which puts them through the same pipeline a WhatsApp
 * message goes through.
 */
@Composable
fun VoiceNoteButton(onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recorder = remember { VoiceNoteRecorder(context) }

    var recording by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }

    // Leaving the screen mid-press must not leave the microphone open.
    DisposableEffect(Unit) {
        onDispose { runCatching { recorder.cancel() } }
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
                    // the background and may make one task, several, or none,
                    // and promising a number here would sometimes be wrong.
                    onMessage("Heard: \"${result.text.take(80)}\" - reading it for tasks.")
                VoiceNoteCapture.Result.NoSpeech -> onMessage("Nothing was said in that note.")
                is VoiceNoteCapture.Result.Failed -> onMessage(result.message)
            }
            working = false
        }
    }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        onMessage(
            if (granted) {
                "Microphone allowed. Hold the button to record."
            } else {
                "Voice notes need the microphone permission."
            },
        )
    }

    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    FloatingActionButton(
        onClick = { if (!granted) permission.launch(Manifest.permission.RECORD_AUDIO) },
        containerColor = if (recording) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        modifier = Modifier.pointerInput(granted, working) {
            if (!granted || working) return@pointerInput
            detectTapGestures(
                onPress = {
                    if (!recorder.start()) {
                        onMessage("The microphone is busy.")
                        return@detectTapGestures
                    }
                    recording = true
                    onMessage("Listening - let go when you are done.")

                    // Returns false when the finger slid off instead of
                    // lifting, which is the gesture people use to mean "no,
                    // forget it".
                    val released = tryAwaitRelease()
                    recording = false
                    if (released) handle(recorder.stop()) else recorder.cancel()
                },
            )
        },
    ) {
        Icon(
            Icons.Filled.Mic,
            contentDescription = when {
                working -> "Working on your note"
                recording -> "Recording, let go to finish"
                else -> "Hold to record a note"
            },
        )
    }
}
