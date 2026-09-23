package com.taskmind.security

import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Stands between the app and its content when the lock is on.
 *
 * It renders a blank card rather than the task list underneath, because a
 * prompt drawn over readable content is not a lock - a screenshot taken while
 * it is up would still show everything behind it.
 *
 * The prompt is raised automatically, and again when the app comes back to the
 * foreground after the grace window. The button is for the case where somebody
 * dismissed it and is looking at a screen with nothing on it.
 */
@Composable
fun LockGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    // androidx.biometric needs a FragmentActivity - it raises the prompt
    // through a headless fragment. MainActivity is one for exactly this
    // reason; anything else simply does not lock.
    val activity = context as? FragmentActivity

    // Read once per composition pass rather than per frame: this is a
    // SharedPreferences hit and it is on the path of every recomposition.
    var locked by remember { mutableStateOf(AppLock.locked(context)) }
    var prompting by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> AppLock.markBackgrounded()
                Lifecycle.Event.ON_START -> {
                    AppLock.markForegrounded()
                    locked = AppLock.locked(context)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (!locked || activity == null) {
        content()
        return
    }

    LaunchedEffect(locked) {
        if (locked && !prompting) {
            prompting = true
            failure = null
        }
    }

    if (prompting) {
        LaunchedEffect(Unit) {
            val executor = ContextCompat.getMainExecutor(activity)
            val prompt = BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        AppLock.markUnlocked()
                        locked = false
                        prompting = false
                    }

                    /**
                     * An error is the user cancelling, or the system refusing.
                     * Either way the app stays locked - this callback must
                     * never be the thing that lets someone in.
                     */
                    override fun onAuthenticationError(code: Int, message: CharSequence) {
                        prompting = false
                        failure = message.toString()
                    }
                },
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock TaskMind")
                .setSubtitle("Your tasks and the messages behind them are hidden until you unlock.")
                .setAllowedAuthenticators(AppLock.ALLOWED)
                .build()
            runCatching { prompt.authenticate(info) }
                .onFailure {
                    prompting = false
                    failure = "This phone could not show the unlock prompt."
                }
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Outlined.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text("TaskMind is locked", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                failure ?: "Unlock with your fingerprint, face or screen lock.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = { prompting = true }, enabled = !prompting) { Text("Unlock") }
        }
    }
}
