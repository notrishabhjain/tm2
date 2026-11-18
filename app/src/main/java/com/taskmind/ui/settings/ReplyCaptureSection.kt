package com.taskmind.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.taskmind.capture.ReplyCaptureService
import com.taskmind.data.memory.MemoryStore
import com.taskmind.di.AppContainer
import com.taskmind.ui.components.SectionCard
import com.taskmind.ui.components.StatusPill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Turning on the reply service (v1.35). Android only lets the user switch an
 * accessibility service on, in its own settings, so this card explains and
 * opens the right page, then shows whether it is on and what it has kept.
 */
@Composable
fun ReplyCaptureSection() {
    val context = LocalContext.current
    val container = remember { AppContainer.get(context) }
    var on by remember { mutableStateOf(replyServiceOn(context)) }
    var kept by remember { mutableStateOf(0) }

    // Coming back from Android's settings: notice the change without a restart.
    LaunchedEffect(Unit) {
        while (true) {
            on = replyServiceOn(context)
            kept = withContext(Dispatchers.IO) {
                runCatching { container.memoryStore.countFrom(MemoryStore.SOURCE_REPLY) }.getOrDefault(0)
            }
            delay(2_000)
        }
    }

    SectionCard(
        title = "Your WhatsApp replies",
        subtitle = "Notifications only show what others send you. Turn this on and TaskMind also keeps what you send, " +
            "so it knows both sides of a chat.",
    ) {
        StatusPill(if (on) "On - $kept repl${if (kept == 1) "y" else "ies"} kept" else "Off", on)
        Spacer(Modifier.height(8.dp))
        Text(
            "What it does: reads WhatsApp and WhatsApp Business only. When you press send, it keeps that message and the " +
                "chat's name, in the memory on this phone. It reads no other app and sends nothing anywhere. Your replies then " +
                "sit next to their messages when TaskMind reads a chat (\"Can you send the PO?\" - \"Sure, by 5\"), and in your " +
                "chat search, person pages and the style of your drafts.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "To turn it on: tap the button, open Installed apps (or Downloaded apps), pick " +
                "\"TaskMind: keep my WhatsApp replies\" and switch it on. If Android says the setting is restricted, open " +
                "App info for TaskMind, tap the three dots at the top, choose Allow restricted settings, and try again.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }) { Text(if (on) "Open accessibility settings (to turn it off)" else "Open accessibility settings") }
    }
}

/** Whether the user has switched the reply service on in Android's settings. */
fun replyServiceOn(context: Context): Boolean {
    val wanted = ComponentName(context, ReplyCaptureService::class.java)
    val enabled = runCatching {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    }.getOrNull().orEmpty()
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == wanted }
}
