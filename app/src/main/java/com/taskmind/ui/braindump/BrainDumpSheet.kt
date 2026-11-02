package com.taskmind.ui.braindump

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.taskmind.ui.AppViewModels
import com.taskmind.ui.components.DateFormats

/**
 * Everything on your mind, in one go.
 *
 * Speak (Android's own dictation, so Hindi and English both work and nothing
 * is recorded by TaskMind) or paste a list from anywhere. "Sort it out" splits
 * it into tasks with dates and areas; every item is shown with a tick box
 * before anything is added.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BrainDumpSheet(
    initialText: String = "",
    onDismiss: () -> Unit,
    onAdded: (Int) -> Unit,
) {
    val vm: BrainDumpViewModel = viewModel(factory = AppViewModels.factory)
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.reset(initialText) }
    // Cleared on the way out, so the next opening never sees the last one's
    // "added" and closes itself.
    DisposableEffect(Unit) { onDispose { vm.reset() } }
    LaunchedEffect(state.added) { state.added?.let(onAdded) }

    var dictationFailed by remember { mutableStateOf(false) }
    val dictate = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(vm::appendSpoken)
        }
    }
    val dictationIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say everything on your mind")
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Text("Brain dump", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            val items = state.items
            if (items == null) {
                Text(
                    "Say or paste everything on your mind - one thing or twenty, any order. " +
                        "You see each task before it is added.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = state.text,
                    onValueChange = vm::setText,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                    placeholder = {
                        Text("pay electricity bill by friday, call Rahul about the rack quote, buy Diwali gifts for the team")
                    },
                )
                if (dictationFailed) {
                    Text(
                        "This phone has no dictation app. Type or paste instead.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { runCatching { dictate.launch(dictationIntent) }.onFailure { dictationFailed = true } },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Mic, contentDescription = null)
                        Text("  Speak")
                    }
                    Button(
                        onClick = vm::sortItOut,
                        enabled = state.text.isNotBlank() && !state.busy,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (state.busy) "Sorting…" else "Sort it out") }
                }
            } else {
                state.note?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (items.isEmpty()) {
                    Text("No tasks found in that. Add a little more and try again.", style = MaterialTheme.typography.bodyMedium)
                }
                items.forEachIndexed { i, item ->
                    val on = i !in state.skipped
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { vm.toggle(i) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = on, onCheckedChange = { vm.toggle(i) })
                            Column(Modifier.weight(1f)) {
                                Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val meta = listOfNotNull(item.dueAt?.let { DateFormats.due(it) }, item.notes)
                                if (meta.isNotEmpty()) {
                                    Text(
                                        meta.joinToString("  ·  "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        if (on) {
                            FlowRow(
                                Modifier.padding(start = 48.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                FilterChip(selected = item.today, onClick = { vm.toggleToday(i) }, label = { Text("Today") })
                                state.areas.forEach { area ->
                                    FilterChip(
                                        selected = item.area == area,
                                        onClick = { vm.setArea(i, if (item.area == area) null else area) },
                                        label = { Text(area) },
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                val count = items.size - state.skipped.size
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = vm::backToText, modifier = Modifier.weight(1f)) { Text("Back to text") }
                    Button(
                        onClick = vm::add,
                        enabled = count > 0 && !state.busy,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (count == 1) "Add 1 task" else "Add $count tasks") }
                }
            }
        }
    }
}
