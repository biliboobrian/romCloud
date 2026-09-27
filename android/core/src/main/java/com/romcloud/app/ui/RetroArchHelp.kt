package com.romcloud.app.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.romcloud.app.RomCloudApp
import com.romcloud.app.launch.LaunchException
import com.romcloud.app.launch.RetroArchInfo
import com.romcloud.core.R

/** Guide de configuration de RetroArch, adapté au modèle choisi et à l'installation détectée. */
@Composable
fun RetroArchHelpDialog(
    info: RetroArchInfo,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
    /** Si fourni (ouverture automatique après un téléchargement), affiche la case « ne plus afficher ». */
    onDontShowAgainChange: ((Boolean) -> Unit)? = null,
) {
    val activity = LocalContext.current as Activity
    val launcher = (activity.application as RomCloudApp).launcher
    val actionFailed = stringResource(R.string.action_failed)

    fun run(action: () -> Unit) = try {
        action()
    } catch (e: LaunchException) {
        onMessage(e.message ?: actionFailed)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.configure_retroarch)) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val origin = stringResource(
                    when {
                        !info.installed -> R.string.ra_not_installed
                        info.fromPlayStore -> R.string.ra_play_store
                        else -> R.string.ra_other_source
                    },
                )
                Text(
                    "${info.packageName} — $origin",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HelpStep(1, stringResource(R.string.ra_step1)) {
                    if (info.core != null) {
                        Text(stringResource(R.string.ra_core_intro))
                        Code(info.core)
                        Text(stringResource(R.string.ra_core_how))
                    } else {
                        Text(stringResource(R.string.ra_core_none))
                    }
                }

                HelpStep(2, stringResource(R.string.ra_step2)) {
                    if (info.usesSaf) {
                        Text(stringResource(R.string.ra_saf_how))
                        Code(info.romsDir)
                        Text(stringResource(R.string.ra_saf_note))
                    } else {
                        Text(stringResource(R.string.ra_path_how))
                        Code(info.romsDir)
                    }
                    Text(
                        stringResource(R.string.ra_mode_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HelpStep(3, stringResource(R.string.ra_step3)) {
                    Text(stringResource(if (info.mustCloseManually) R.string.ra_close_manual else R.string.ra_close_auto))
                    Text(stringResource(if (info.quitOnExit) R.string.ra_quit_on else R.string.ra_quit_off))
                }

                HelpStep(4, stringResource(R.string.ra_step4)) {
                    Text(stringResource(R.string.ra_log_how))
                    info.configFile?.let {
                        Text(stringResource(R.string.ra_config_file), style = MaterialTheme.typography.bodySmall)
                        Code(it)
                    }
                }

                if (onDontShowAgainChange != null) {
                    var dontShowAgain by remember { mutableStateOf(false) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = dontShowAgain, onCheckedChange = {
                            dontShowAgain = it
                            onDontShowAgainChange(it)
                        })
                        Text(stringResource(R.string.ra_dont_show_again))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        dismissButton = {
            if (info.installed) {
                Row {
                    TextButton(onClick = { run { launcher.openApp(activity, info.packageName) } }) { Text(stringResource(R.string.ra_open)) }
                    TextButton(onClick = { run { launcher.openAppSettings(activity, info.packageName) } }) { Text(stringResource(R.string.action_force_stop)) }
                }
            }
        },
    )
}

@Composable
private fun HelpStep(number: Int, title: String, content: @Composable () -> Unit) {
    Row {
        Text(
            "$number",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(24.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun Code(text: String) {
    SelectionContainer {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 2.dp),
        )
    }
}
