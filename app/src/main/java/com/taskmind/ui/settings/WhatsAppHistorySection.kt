package com.taskmind.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taskmind.capture.WhatsAppBackupImport
import com.taskmind.core.WaBackup
import com.taskmind.data.memory.MemoryStore
import com.taskmind.di.AppContainer
import com.taskmind.ui.components.SectionCard
import com.taskmind.ui.components.DateFormats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Your WhatsApp history": every chat, both sides, read once from WhatsApp's
 * own backup into the long-term memory.
 *
 * Self-contained like [EmailSection]. The steps are on the card because the
 * key lives four screens deep in WhatsApp and nobody finds it unprompted.
 */
@Composable
fun WhatsAppHistorySection() {
    val context = LocalContext.current
    val container = remember { AppContainer.get(context) }
    val importer = container.whatsAppBackupImport
    val state by importer.state.collectAsStateWithLifecycle()

    var key by rememberSaveable { mutableStateOf("") }
    var hasSavedKey by remember { mutableStateOf(false) }
    var showSteps by rememberSaveable { mutableStateOf(false) }
    var stats by remember { mutableStateOf(MemoryStore.Stats(0, 0, null, null)) }
    var found by remember { mutableStateOf<String?>(null) }

    // Counts and the backup's whereabouts, read off the main thread, and
    // again whenever an import finishes.
    LaunchedEffect(state is WhatsAppBackupImport.State.Working) {
        withContext(Dispatchers.IO) {
            stats = runCatching { container.memoryStore.stats() }.getOrDefault(stats)
            hasSavedKey = container.secretStore.waBackupKey.isNotBlank()
            found = runCatching { importer.findBackup() }.getOrNull()?.let { file ->
                "Backup found, last written ${DateFormats.full(file.lastModified())}"
            }
        }
    }

    val typed = key.count { it.isLetterOrDigit() }
    val working = state is WhatsAppBackupImport.State.Working

    fun start(uri: android.net.Uri? = null) {
        val use = key.ifBlank { container.secretStore.waBackupKey }
        container.applicationScope.launch { importer.run(use, uri) }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) start(uri)
    }

    SectionCard(
        title = "Your WhatsApp history",
        subtitle = "Every chat at once, both sides, read from WhatsApp's own backup. It gives the Twin your " +
            "real history to answer from. It stays on this phone and creates no tasks.",
    ) {
        if (stats.messages > 0) {
            Text(
                "In memory: ${stats.messages} messages from ${stats.chats} chats" +
                    (stats.oldest?.let { ", going back to ${DateFormats.full(it)}" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
        }

        TextButton(onClick = { showSteps = !showSteps }) {
            Text(if (showSteps) "Hide the steps" else "How to get the key from WhatsApp")
        }
        if (showSteps) {
            Text(
                "1. Open WhatsApp, then Settings, Chats, Chat backup.\n" +
                    "2. Tap End-to-end encrypted backup, then Turn on. (If it is already on with a password, " +
                    "turn it off first, then on again.)\n" +
                    "3. Choose \"Use 64-digit encryption key instead\", then Generate your 64-digit key.\n" +
                    "4. Write the key down or screenshot it. WhatsApp cannot show it again, and you need " +
                    "it to restore your own chats too.\n" +
                    "5. Finish, then tap Back up and wait for it to complete.\n" +
                    "6. Come back here, type the key, and tap Read my chats.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }

        Text(
            found ?: "No end-to-end encrypted backup found yet. After backing up in WhatsApp it appears here; " +
                "you can also choose the file yourself.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = key,
            onValueChange = { key = it.take(120) },
            label = { Text(if (hasSavedKey) "64-digit key (saved; type to replace)" else "64-digit key") },
            supportingText = { Text("$typed of ${WaBackup.KEY_HEX_LENGTH}. Spaces are fine.") },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            enabled = !working,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        val ready = !working && (typed == WaBackup.KEY_HEX_LENGTH || (key.isBlank() && hasSavedKey))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { start() }, enabled = ready) {
                Text(if (stats.messages > 0) "Read again" else "Read my chats")
            }
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = ready) { Text("Choose the file") }
        }

        when (val s = state) {
            is WhatsAppBackupImport.State.Working -> {
                Spacer(Modifier.height(12.dp))
                Text(s.step, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                val fraction = s.fraction
                if (fraction != null) {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(
                    "A large backup takes a few minutes. You can leave this screen; keep TaskMind open.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is WhatsAppBackupImport.State.Done -> {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Done: ${s.read} messages read from ${s.chats} chats, ${s.added} of them new.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Ask the Twin about any of them, with \"Search my messages when I ask\" switched on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is WhatsAppBackupImport.State.Failed -> {
                Spacer(Modifier.height(12.dp))
                Text(s.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            WhatsAppBackupImport.State.Idle -> Unit
        }
    }
}
