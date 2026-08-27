package com.taskmind.ui.onboarding

import android.Manifest
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taskmind.diagnostics.PermissionState
import com.taskmind.ui.components.StatusPill

/**
 * Spec 18 - onboarding. A screen per step, live granted state on each, and
 * every step skippable: an app that refuses to start until seven permissions
 * are granted gets uninstalled at step three.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onFinished: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(state.finished) { if (state.finished) onFinished() }

    val runtimePermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.refresh() }

    fun launch(intent: Intent?) {
        if (intent == null) return
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        viewModel.refresh()
    }

    val steps = state.steps
    val index = steps.indexOf(state.step).coerceAtLeast(0)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.step.title) },
                actions = { TextButton(onClick = viewModel::finish) { Text("Skip setup") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LinearProgressIndicator(
                progress = { (index + 1f) / steps.size },
                modifier = Modifier.fillMaxWidth(),
            )

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            ) {
                when (state.step) {
                    OnboardingStep.WELCOME -> WelcomeStep(state, viewModel)
                    OnboardingStep.PROVIDERS -> ProvidersStep(onOpenSettings)
                    OnboardingStep.NOTIFICATION_ACCESS -> PermissionStep(
                        item = state.permissions.firstOrNull { it.key == "notification_listener" },
                        body = "TaskMind reads the messages you choose to watch and looks for commitments in " +
                            "them. Android calls this notification access, and it is granted in system settings.",
                        onFix = ::launch,
                    )
                    OnboardingStep.APPS -> AppsStep(onOpenSettings)
                    OnboardingStep.BATTERY -> PermissionStep(
                        item = state.permissions.firstOrNull { it.key == "battery" },
                        body = "Android's battery saver stops the background work that finds your calls. " +
                            "TaskMind needs to be exempt to work while the phone is idle.",
                        onFix = ::launch,
                    )
                    OnboardingStep.AUTOSTART -> AutostartStep(onFix = { launch(PermissionState.autostartIntent(context)) })
                    OnboardingStep.CALLS -> CallsStep(state, viewModel, ::launch) {
                        runtimePermissions.launch(
                            arrayOf(
                                Manifest.permission.READ_CALL_LOG,
                                Manifest.permission.READ_PHONE_STATE,
                                Manifest.permission.READ_CONTACTS,
                                Manifest.permission.POST_NOTIFICATIONS,
                            ),
                        )
                    }
                    OnboardingStep.DONE -> DoneStep()
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (index > 0) {
                    OutlinedButton(onClick = viewModel::back, modifier = Modifier.weight(1f)) { Text("Back") }
                }
                if (state.step == OnboardingStep.DONE) {
                    Button(onClick = viewModel::finish, modifier = Modifier.weight(1f)) { Text("Start using TaskMind") }
                } else {
                    Button(
                        onClick = viewModel::next,
                        modifier = Modifier.weight(1f),
                        enabled = state.step != OnboardingStep.WELCOME || state.consent,
                    ) {
                        Text("Next")
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomeStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    Text("TaskMind finds your commitments", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(16.dp))
    Text(
        "It watches the messaging apps you choose and the call recordings your phone already makes.\n\n" +
            "When someone asks you for something, it becomes a task - with the exact words that created it.\n\n" +
            "You never have to type a task again, though you still can.",
        style = MaterialTheme.typography.bodyLarge,
    )
    Spacer(Modifier.height(24.dp))
    Text("Before you start", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    Text(
        "TaskMind sends the text of watched messages and your call recordings to the AI provider you " +
            "configure, so it can find tasks in them. Nothing is sent anywhere else, and nothing is sent " +
            "until you add a key. Free API tiers commonly reserve the right to train on what you submit - " +
            "check your provider's terms.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = state.consent, onCheckedChange = viewModel::setConsent)
        Text(
            "Send my message text and call audio to the cloud provider I configure.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    if (!state.consent) {
        Text(
            "Capture stays off until this is ticked. You can still use TaskMind as a plain task list.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProvidersStep(onOpenSettings: () -> Unit) {
    Text("Choose your AI providers", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(12.dp))
    Text(
        "TaskMind needs two things: a language model to read messages, and a speech engine to " +
            "transcribe calls. They are usually different vendors - the best Hindi speech engine and the " +
            "best extraction model rarely come from the same company.\n\n" +
            "Groq has a generous free tier for both. Sarvam AI is purpose-built for Indian languages and " +
            "is materially better on Hindi phone audio.\n\n" +
            "You can skip this. Messages and calls will still be captured and kept, and they will all be " +
            "processed the moment you add a key.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(20.dp))
    Button(onClick = onOpenSettings) { Text("Set up providers") }
}

@Composable
private fun PermissionStep(
    item: PermissionState.Item?,
    body: String,
    onFix: (Intent?) -> Unit,
) {
    Text(body, style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(20.dp))
    if (item != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(if (item.granted) "Granted" else "Not granted yet", item.granted)
        }
        Spacer(Modifier.height(16.dp))
        if (!item.granted) {
            Button(onClick = { onFix(item.fixIntent) }) { Text("Open system settings") }
        }
    }
}

@Composable
private fun AppsStep(onOpenSettings: () -> Unit) {
    Text(
        "TaskMind only reads apps you allow. WhatsApp and your SMS app are on the list by default; " +
            "everything else is off.\n\n" +
            "Once notification access is on, every app that posts a notification appears in settings and " +
            "you decide which ones are worth reading. An allow-list keeps the cost predictable.",
        style = MaterialTheme.typography.bodyLarge,
    )
    Spacer(Modifier.height(20.dp))
    Button(onClick = onOpenSettings) { Text("Choose apps") }
}

@Composable
private fun AutostartStep(onFix: () -> Unit) {
    Text("Xiaomi Autostart", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(12.dp))
    Text(
        "This one matters more than it sounds. Autostart is a Xiaomi setting separate from Android's own " +
            "battery controls. With it off, TaskMind is killed in the background and call detection stops - " +
            "silently, with no error anywhere.",
        style = MaterialTheme.typography.bodyLarge,
    )
    Spacer(Modifier.height(16.dp))
    Text(PermissionState.AUTOSTART_INSTRUCTIONS, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(20.dp))
    Button(onClick = onFix) { Text("Open Autostart settings") }
}

@Composable
private fun CallsStep(
    state: OnboardingUiState,
    viewModel: OnboardingViewModel,
    onFix: (Intent?) -> Unit,
    onRequestRuntime: () -> Unit,
) {
    Text("Call capture", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(12.dp))
    Text(
        "TaskMind cannot record calls. Android does not allow it. What it can do is read the recordings " +
            "your own phone app makes - so call capture only works if call recording is turned on in your " +
            "dialer.\n\n" +
            "Worth saying plainly: the other person on a call has not agreed to their voice being sent to a " +
            "transcription service. That is your call to make.",
        style = MaterialTheme.typography.bodyLarge,
    )
    Spacer(Modifier.height(20.dp))

    Button(onClick = onRequestRuntime) { Text("Grant call permissions") }
    Spacer(Modifier.height(12.dp))

    val allFiles = state.permissions.firstOrNull { it.key == "all_files" }
    if (allFiles != null && !allFiles.granted) {
        Text(
            "TaskMind also needs All Files Access to find recordings, because the dialer writes them " +
                "outside its own folder.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { onFix(allFiles.fixIntent) }) { Text("Grant All Files Access") }
        Spacer(Modifier.height(12.dp))
    }

    OutlinedButton(onClick = viewModel::checkCallRecording, enabled = !state.checkingRecordings) {
        Text(if (state.checkingRecordings) "Looking..." else "Check if call recording is on")
    }
    when (state.callRecordingSeen) {
        true -> {
            Spacer(Modifier.height(8.dp))
            Text(
                "Found recordings on this device. Call capture should work.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        false -> {
            Spacer(Modifier.height(8.dp))
            Text(
                "No recordings found anywhere TaskMind knows to look. Either call recording is off in your " +
                    "phone app, or your phone saves them somewhere unusual - you can point TaskMind at the " +
                    "folder in Settings. Until then, call capture will do nothing.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        null -> Unit
    }
}

@Composable
private fun DoneStep() {
    Text("That's it", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(16.dp))
    Text(
        "TaskMind is now watching. Anything it finds appears in your list with the words that created it.\n\n" +
            "If something ever seems not to work, open Status from the task list menu. It shows every " +
            "permission, both providers, today's usage, and the last thing that happened - and the activity " +
            "log behind it records every step of every capture.",
        style = MaterialTheme.typography.bodyLarge,
    )
}
