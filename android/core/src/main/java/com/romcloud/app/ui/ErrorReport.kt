package com.romcloud.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.romcloud.app.RomCloudApp
import com.romcloud.core.R
import kotlinx.coroutines.launch

/**
 * « Signaler un problème » (paramètres du téléphone et de la TV) : description et journal de
 * l'application envoyés au serveur (journal des erreurs de l'administration) ; gardé hors ligne.
 */
@Composable
fun ErrorReportSection(app: RomCloudApp) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.report_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { open = true }) { Text(stringResource(R.string.report_button)) }
    }
    if (open) ErrorReportDialog(app) { open = false }
}

@Composable
private fun ErrorReportDialog(app: RomCloudApp, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var description by remember { mutableStateOf("") }
    var withLogs by remember { mutableStateOf(true) }
    var sending by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text(stringResource(R.string.report_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.report_description)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    enabled = !sending,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = withLogs, onCheckedChange = { withLogs = it }, enabled = !sending)
                    Text(stringResource(R.string.report_with_logs))
                }
                if (sending) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = !sending, onClick = {
                sending = true
                scope.launch {
                    val sent = app.account.sendReport(description, withLogs)
                    sending = false
                    Toast.makeText(context, if (sent) R.string.report_sent else R.string.report_queued, Toast.LENGTH_LONG).show()
                    onDismiss()
                }
            }) { Text(stringResource(R.string.report_send)) }
        },
        dismissButton = { TextButton(enabled = !sending, onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
