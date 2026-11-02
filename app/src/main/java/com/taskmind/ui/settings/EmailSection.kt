package com.taskmind.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taskmind.data.settings.Settings
import com.taskmind.di.AppContainer
import com.taskmind.email.EmailStore
import com.taskmind.email.EmailSync
import com.taskmind.ui.components.LabeledSwitch
import com.taskmind.ui.components.SectionCard
import com.taskmind.work.Scheduler
import kotlinx.coroutines.launch

/**
 * Email, two ways: a mail app's notifications (no setup, any provider) and
 * the mailbox itself over IMAP with an app password (full text, sent mail,
 * works with notifications off).
 *
 * Self-contained like [WebSyncSection]: it owns its store and writes the two
 * settings it needs directly.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmailSection(settings: Settings) {
    val context = LocalContext.current
    val container = remember { AppContainer.get(context) }
    val scope = rememberCoroutineScope()
    val store = container.emailStore
    val state by store.state.collectAsStateWithLifecycle(initialValue = EmailStore.State())

    var providerId by remember { mutableStateOf("gmail") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("993") }
    var address by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var extra by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val provider = EmailStore.PROVIDERS.firstOrNull { it.id == providerId } ?: EmailStore.PROVIDERS.first()
    val shownHost = host.ifBlank { provider.host }

    SectionCard(
        title = "Email",
        subtitle = "Asks and promises in your email reach the Inbox, like messages do. " +
            "Newsletters, receipts and notifications are skipped before anything is sent to the AI.",
    ) {
        LabeledSwitch(
            label = "Read mail app notifications",
            checked = settings.captureEmailApps,
            onCheckedChange = { on -> scope.launch { container.settingsRepository.setCaptureEmailApps(on) } },
            description = "Gmail, Outlook and other mail apps: the start of each new mail as it arrives. " +
                "Works with any account, including work mail in the Outlook app. No password needed.",
        )

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Text("Read your mailbox directly", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "Each new mail in full, even with notifications off, and the promises you make in mail you " +
                "send. TaskMind can only read: it cannot send, delete or mark anything as read.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        if (state.connected) {
            Text("Connected: ${state.address}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "${state.host} · checked ${EmailSync.formatWhen(state.lastCheckAt)} · " +
                    "${state.readTotal} read, ${state.capturedTotal} sent to the Inbox so far",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.lastError.isNotBlank()) {
                Text(state.lastError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            } else if (state.lastResult.isNotBlank()) {
                Text(state.lastResult, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            LabeledSwitch(
                label = "Also read what I send",
                checked = state.readSent,
                onCheckedChange = { on -> scope.launch { store.setReadSent(on) } },
                description = "Only sent mail that promises something (\"I'll send it by Friday\") is read, " +
                    "so your own commitments land in the Inbox too.",
            )
            LabeledSwitch(
                label = "Only mail addressed to me",
                checked = state.onlyToMe,
                onCheckedChange = { on -> scope.launch { store.setOnlyToMe(on) } },
                description = "Skips mail where you are not in To or Cc - lists and group aliases.",
            )
            Spacer(Modifier.height(4.dp))
            Text("Check every", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EmailStore.INTERVALS.forEach { hours ->
                    FilterChip(
                        selected = state.intervalHours == hours,
                        onClick = {
                            scope.launch {
                                store.setInterval(hours)
                                Scheduler.ensureEmailWork(context, true, hours)
                            }
                        },
                        label = { Text(if (hours == 24) "Day" else if (hours == 1) "Hour" else "$hours hours") },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = extra ?: state.extraAddresses,
                onValueChange = { extra = it },
                label = { Text("Your other addresses (optional)") },
                placeholder = { Text("work@company.com, alias@gmail.com") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            )
            if (extra != null && extra != state.extraAddresses) {
                TextButton(onClick = {
                    val value = extra.orEmpty()
                    scope.launch {
                        store.setExtraAddresses(value)
                        extra = null
                        message = "Saved. Mail to these counts as mail to you."
                    }
                }) { Text("Save addresses") }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            message = when (val r = EmailSync(container).run(manual = true)) {
                                is EmailSync.Result.Done -> store.current().lastResult
                                is EmailSync.Result.Failed -> r.message
                                is EmailSync.Result.Skipped -> "Nothing to do: ${r.why}."
                            }
                            busy = false
                        }
                    },
                    enabled = !busy,
                ) { Text(if (busy) "Checking…" else "Check now") }
                OutlinedButton(onClick = {
                    scope.launch {
                        store.disconnect()
                        container.secretStore.emailPassword = ""
                        container.settingsRepository.setEmailConnectedAddress("")
                        Scheduler.ensureEmailWork(context, false, state.intervalHours)
                        message = "Disconnected. The app password is deleted from this phone."
                    }
                }) { Text("Disconnect") }
            }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EmailStore.PROVIDERS.forEach { p ->
                    FilterChip(
                        selected = providerId == p.id,
                        onClick = {
                            providerId = p.id
                            host = ""
                        },
                        label = { Text(p.label) },
                    )
                }
            }
            Text(provider.help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            provider.passwordUrl?.let { url ->
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }) { Text("Create an app password") }
            }
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text("Email address") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("App password") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = shownHost,
                    onValueChange = { host = it },
                    label = { Text("IMAP server") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text("Port") },
                    modifier = Modifier.weight(0.4f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        val p = port.toIntOrNull() ?: 993
                        val problem = EmailSync.test(shownHost, p, address, password)
                        if (problem == null) {
                            container.secretStore.emailPassword = password
                            store.connect(providerId, address, shownHost, p)
                            container.settingsRepository.setEmailConnectedAddress(address)
                            val interval = store.current().intervalHours
                            Scheduler.ensureEmailWork(context, true, interval)
                            Scheduler.enqueueEmailNow(context)
                            password = ""
                            message = "Connected. Reading the last two days of mail now."
                        } else {
                            message = problem
                        }
                        busy = false
                    }
                },
                enabled = !busy && address.contains('@') && password.isNotBlank() && shownHost.isNotBlank(),
            ) { Text(if (busy) "Connecting…" else "Connect") }
        }

        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "Tip: in Gmail, give any mail the label \"TaskMind\" and it is read whatever it is. Work mail in " +
                "Outlook or Microsoft 365 can be forwarded to the mailbox connected here; the forwarded " +
                "sender is the one TaskMind files it under.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
