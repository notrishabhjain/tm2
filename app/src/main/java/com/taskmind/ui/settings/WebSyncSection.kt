package com.taskmind.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taskmind.di.AppContainer
import com.taskmind.sync.SupabaseApi
import com.taskmind.sync.SyncEngine
import com.taskmind.sync.SyncSecrets
import com.taskmind.sync.SyncStore
import com.taskmind.ui.components.DateFormats
import com.taskmind.ui.components.LabeledSwitch
import com.taskmind.ui.components.SectionCard
import com.taskmind.work.Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pairing this phone with the web mirror.
 *
 * Self-contained on purpose - it owns its stores and talks to Supabase
 * directly rather than going through [SettingsViewModel]. Sync is a separate
 * concern with a separate lifetime, and threading four more fields through the
 * settings view model would tangle it with the capture engine's configuration
 * for no benefit.
 *
 * Nothing here is on by default. Until someone fills this in and signs in, no
 * task has ever left the device.
 */
@Composable
fun WebSyncSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val store = remember { SyncStore(context) }
    val secrets = remember { SyncSecrets(context) }
    val state by store.state.collectAsStateWithLifecycle(initialValue = SyncStore.State())

    var url by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    // The saved values win once they exist, so reopening this screen shows
    // what is actually configured rather than empty boxes.
    val shownUrl = if (url.isBlank() && state.projectUrl.isNotBlank()) state.projectUrl else url
    val signedIn = secrets.signedIn()

    SectionCard(
        title = "Web access",
        subtitle = "Mirror your tasks to a private web page you can open on a laptop. " +
            "Off until you set it up.",
    ) {
        if (signedIn) {
            Text(
                "Connected as ${state.email.ifBlank { "your account" }}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                state.projectUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            LabeledSwitch(
                label = "Keep the web page up to date",
                checked = state.enabled,
                onCheckedChange = { on ->
                    scope.launch {
                        store.setEnabled(on)
                        if (on) Scheduler.enqueueSync(context)
                    }
                },
                description = "Pushes changes hourly, and whenever you leave the app.",
            )

            Spacer(Modifier.height(8.dp))
            if (state.lastSuccessAt > 0) {
                Text(
                    "Last sent ${DateFormats.full(state.lastSuccessAt)} IST",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.lastResult.isNotBlank()) {
                Text(
                    state.lastResult,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            val result = SyncEngine(AppContainer.get(context), store, secrets).run(force = true)
                            message = when (result) {
                                is SyncEngine.Result.Pushed ->
                                    "Sent ${result.tasks} task(s) and ${result.reviews} awaiting review."
                                is SyncEngine.Result.Failed -> result.message
                                SyncEngine.Result.Skipped -> "Nothing to do."
                            }
                            busy = false
                        }
                    },
                    enabled = !busy,
                ) {
                    Text(if (busy) "Sending..." else "Sync now")
                }
                OutlinedButton(
                    onClick = { scope.launch { store.forceFullResync(); message = "Everything will be re-sent next time." } },
                    enabled = !busy,
                ) {
                    Text("Re-send all")
                }
            }

            Spacer(Modifier.height(4.dp))
            TextButton(onClick = {
                scope.launch {
                    secrets.clear()
                    store.clear()
                    message = "Disconnected. Nothing further will be sent."
                }
            }) {
                Text("Disconnect")
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Your task titles, dates and the evidence quote are sent. Full message text and " +
                    "call transcripts are not, and there is nowhere on the server to put them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "Paste the two values from your Supabase project, then sign in with the account " +
                    "you created there. SETUP.md in the repository walks through both.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = shownUrl,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Project URL") },
                placeholder = { Text("https://xxxx.supabase.co") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Anon / publishable key") },
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Email") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        message = connect(
                            context = context,
                            store = store,
                            secrets = secrets,
                            url = shownUrl.trim().trimEnd('/'),
                            key = key.trim(),
                            email = email.trim(),
                            password = password,
                        )
                        // Never keep the password: the refresh token replaces
                        // it, and it is the one credential worth stealing.
                        password = ""
                        busy = false
                    }
                },
                enabled = !busy && shownUrl.isNotBlank() && key.isNotBlank() &&
                    email.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (busy) "Connecting..." else "Connect")
            }
        }

        message?.let {
            Spacer(Modifier.height(12.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * Signs in and, if that works, does one push immediately.
 *
 * The first sync happens here rather than being left to the schedule so the
 * result is visible while the person is still looking at the screen. A setup
 * flow that ends in "it will probably work later" is how you end up with a
 * blank web page and nothing to debug.
 */
private suspend fun connect(
    context: android.content.Context,
    store: SyncStore,
    secrets: SyncSecrets,
    url: String,
    key: String,
    email: String,
    password: String,
): String {
    val container = AppContainer.get(context)
    val api = SupabaseApi(container.httpClient)

    val ok = when (val session = withContext(Dispatchers.IO) { api.signIn(url, key, email, password) }) {
        is SupabaseApi.Outcome.Ok -> session.value
        is SupabaseApi.Outcome.Failed -> return "Could not sign in: ${session.message}"
    }

    secrets.anonKey = key
    secrets.refreshToken = ok.refreshToken
    secrets.accessToken = ok.accessToken
    secrets.accessTokenExpiresAt = ok.expiresAt
    store.setProject(url, email)
    store.setEnabled(true)

    return when (val first = SyncEngine(container, store, secrets).run(force = true)) {
        is SyncEngine.Result.Pushed -> {
            Scheduler.enqueueSync(context)
            "Connected. Sent ${first.tasks} task(s) and ${first.reviews} awaiting review."
        }
        is SyncEngine.Result.Failed ->
            "Signed in, but the first send failed: ${first.message}"
        SyncEngine.Result.Skipped -> "Connected."
    }
}
