package com.romcloud.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.romcloud.app.RomCloudApp
import com.romcloud.app.I18n
import com.romcloud.core.R
import com.romcloud.app.data.DownloadEvent
import com.romcloud.app.launch.LaunchException
import kotlinx.coroutines.launch

/**
 * Comportements globaux communs aux applications téléphone et TV : fins de téléchargement
 * (lancement automatique ou proposition « Jouer »), émulateur manquant (Play Store),
 * émulateur à fermer avant le lancement (Android 14+), autorisation des notifications.
 */
@Composable
fun LaunchDialogs(app: RomCloudApp, activity: ComponentActivity, snackbar: SnackbarHostState) {
    // Notifications Android 13+ (progression des téléchargements).
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Fin de téléchargement : lancement automatique si demandé, sinon proposition « Jouer ».
    LaunchedEffect(Unit) {
        app.downloader.events.collect { event ->
            when (event) {
                is DownloadEvent.Completed -> {
                    val resumed = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                    if (app.autoLaunch.remove(event.game.id) && resumed) {
                        app.play(activity, event.system, event.game)?.let { snackbar.showSnackbar(it) }
                    } else if (!event.romIncluded) {
                        launch { snackbar.showSnackbar(I18n.get(R.string.bios_downloaded_snackbar, event.system.name)) }
                    } else {
                        launch {
                            val result = snackbar.showSnackbar(
                                I18n.get(R.string.downloaded_snackbar, event.game.title),
                                actionLabel = I18n.get(R.string.action_play),
                                duration = SnackbarDuration.Long,
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                app.play(activity, event.system, event.game)?.let { snackbar.showSnackbar(it) }
                            }
                        }
                    }
                }
                is DownloadEvent.Failed -> {
                    app.autoLaunch.remove(event.game.id)
                    launch { snackbar.showSnackbar(I18n.get(R.string.download_failed_game, event.game.title, event.message)) }
                }
            }
        }
    }

    // Émulateur absent : proposer de l'installer depuis le Google Play Store.
    val missing by app.missingEmulator.collectAsStateWithLifecycle()
    missing?.let { emulator ->
        val scope = rememberCoroutineScope()
        val unavailable = stringResource(R.string.play_store_unavailable)
        AlertDialog(
            onDismissRequest = { app.missingEmulator.value = null },
            title = { Text(stringResource(R.string.missing_emulator_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.missing_emulator_text,
                        emulator.playerName ?: emulator.packageName,
                        emulator.packageName,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    app.missingEmulator.value = null
                    try {
                        app.launcher.openStore(activity, emulator.packageName)
                    } catch (e: LaunchException) {
                        scope.launch { snackbar.showSnackbar(e.message ?: unavailable) }
                    }
                }) { Text(stringResource(R.string.action_open_play_store)) }
            },
            dismissButton = {
                TextButton(onClick = { app.missingEmulator.value = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    // Android 14+ : l'émulateur (RetroArch…) doit être fermé à la main avant le lancement.
    val closePrompt by app.closeEmulatorPrompt.collectAsStateWithLifecycle()
    closePrompt?.let { prompt ->
        val scope = rememberCoroutineScope()
        val settingsUnavailable = stringResource(R.string.settings_unavailable)
        var dontShowAgain by remember(prompt) { mutableStateOf(false) }
        fun dismiss() {
            if (dontShowAgain) app.settings.setCloseWarningDismissed(prompt.packageName, true)
            app.closeEmulatorPrompt.value = null
        }
        AlertDialog(
            onDismissRequest = { app.closeEmulatorPrompt.value = null },
            title = { Text(stringResource(R.string.close_emulator_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.close_emulator_text, prompt.playerName))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = dontShowAgain, onCheckedChange = { dontShowAgain = it })
                        Text(stringResource(R.string.dont_show_again))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    dismiss()
                    app.play(activity, prompt.system, prompt.game, emulatorClosed = true)
                        ?.let { scope.launch { snackbar.showSnackbar(it) } }
                }) { Text(stringResource(R.string.action_launch)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    try {
                        app.launcher.openAppSettings(activity, prompt.packageName)
                    } catch (e: LaunchException) {
                        scope.launch { snackbar.showSnackbar(e.message ?: settingsUnavailable) }
                    }
                }) { Text(stringResource(R.string.action_force_stop)) }
            },
        )
    }
}
