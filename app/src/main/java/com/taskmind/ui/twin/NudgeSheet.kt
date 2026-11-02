package com.taskmind.ui.twin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.taskmind.contacts.ContactLookup
import com.taskmind.data.repo.TwinBrain

/**
 * A nudge, ready to send - by the user. TaskMind opens WhatsApp or the mail
 * app with the words typed in; pressing send is theirs.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NudgeSheet(
    draft: TwinBrain.Draft,
    onTextChange: (String) -> Unit,
    /** Called with how it went out: "WhatsApp", "email", "shared", "copied". */
    onSent: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Text("Nudge ${draft.person}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                if (draft.usedModel) "Drafted for you. Change anything, then send it yourself."
                else "A starting point. Change anything, then send it yourself.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = draft.text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (draft.email != null) {
                    Button(onClick = {
                        if (ContactLookup.email(context, draft.email, draft.subject, draft.text)) onSent("email")
                    }) { Text("Reply by email") }
                } else {
                    Button(onClick = {
                        if (ContactLookup.whatsappWithText(context, draft.phone, draft.text)) onSent("WhatsApp")
                    }) { Text(if (draft.phone != null) "Open in WhatsApp" else "WhatsApp…") }
                }
                OutlinedButton(onClick = {
                    ContactLookup.share(context, draft.text)
                    onSent("shared")
                }) { Text("Share…") }
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(draft.text))
                    onSent("copied")
                }) { Text("Copy") }
            }
            if (draft.email == null && draft.phone == null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "No saved number for ${draft.person}, so WhatsApp will ask which chat.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
