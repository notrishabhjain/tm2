package com.taskmind.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.taskmind.di.AppContainer
import com.taskmind.security.AppLock
import com.taskmind.sync.SyncSecrets
import com.taskmind.ui.components.LabeledSwitch
import com.taskmind.ui.components.SectionCard

/**
 * The lock, the screenshot block, and one honest warning.
 *
 * Its own page rather than a corner of Privacy: that page is about what leaves
 * the device, this one is about somebody holding the phone. They are different
 * worries and mixing them makes both harder to reason about.
 */
@Composable
fun SecuritySection() {
    val context = LocalContext.current

    var lock by remember(context) { mutableStateOf(AppLock.enabled(context)) }
    var hide by remember(context) { mutableStateOf(AppLock.hideContent(context)) }
    val unavailable = remember(context) { AppLock.unavailableReason(context) }

    // Read once. Touching these is what opens the keystore, and the answer
    // cannot change without the app restarting.
    val plainFallback = remember(context) {
        runCatching {
            // Touching either store is what opens it, so this is also what
            // sets the flag. Both are asked, because either one falling back
            // is worth saying out loud.
            val container = AppContainer.get(context)
            container.secretStore.hasLlmKey()
            val sync = SyncSecrets(context)
            sync.signedIn()
            container.secretStore.usingPlainFallback || sync.usingPlainFallback
        }.getOrDefault(false)
    }

    SectionCard(
        title = "Lock the app",
        subtitle = "Ask for your fingerprint, face or screen lock before showing anything.",
    ) {
        LabeledSwitch(
            label = "Require unlock",
            checked = lock,
            enabled = unavailable == null,
            onCheckedChange = { on ->
                lock = on
                AppLock.setEnabled(context, on)
                if (on) AppLock.lockNow()
            },
            description = unavailable
                ?: "Asked when you open TaskMind, and again after a minute away from it.",
        )

        Spacer(Modifier.height(12.dp))
        LabeledSwitch(
            label = "Hide from screenshots and app switcher",
            checked = hide || lock,
            enabled = !lock,
            onCheckedChange = { on ->
                hide = on
                AppLock.setHideContent(context, on)
            },
            description = if (lock) {
                "Always on while the app is locked."
            } else {
                "Your task list stops appearing in the recents thumbnail, and screenshots of " +
                    "TaskMind come out blank. Some screen-recording tools will also refuse."
            },
        )

        Spacer(Modifier.height(12.dp))
        Text(
            "This protects against somebody picking up your unlocked phone. It is not disk " +
                "encryption — your tasks are already protected by the phone's own encryption and " +
                "by Android keeping apps out of each other's files.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (plainFallback) {
        SectionCard(
            title = "Your keys are not in secure storage",
            subtitle = "This needs a moment of your attention.",
        ) {
            Text(
                "TaskMind normally keeps your API keys and your web sign-in in storage locked to " +
                    "this phone's hardware keystore. On this device that store could not be opened — " +
                    "which usually means the app was restored from a backup of another phone — so it " +
                    "fell back to an ordinary file. Android still keeps that file away from other " +
                    "apps, but it is not hardware-protected.\n\n" +
                    "Reinstalling TaskMind and entering your keys again will put them back in the " +
                    "secure store.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
