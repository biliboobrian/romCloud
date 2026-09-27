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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.romcloud.app.RomCloudApp
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
                    } else {
                        launch {
                            val result = snackbar.showSnackbar(
                                "« ${event.game.title} » est téléchargé",
                                actionLabel = "Jouer",
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
                    launch { snackbar.showSnackbar("Échec du téléchargement de « ${event.game.title} » : ${event.message}") }
                }
            }
        }
    }

    // Émulateur absent : proposer de l'installer depuis le Google Play Store.
    val missing by app.missingEmulator.collectAsStateWithLifecycle()
    missing?.let { emulator ->
        val scope = rememberCoroutineScope()
        AlertDialog(
            onDismissRequest = { app.missingEmulator.value = null },
            title = { Text("Émulateur non installé") },
            text = {
                Text(
                    "Pour lancer ce jeu, installez l’émulateur « ${emulator.playerName ?: emulator.packageName} » " +
                        "(${emulator.packageName}).\n\nVoulez-vous ouvrir sa page sur le Google Play Store ?\n\n" +
                        "Vous pouvez aussi choisir un autre émulateur dans la fiche du jeu.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    app.missingEmulator.value = null
                    try {
                        app.launcher.openStore(activity, emulator.packageName)
                    } catch (e: LaunchException) {
                        scope.launch { snackbar.showSnackbar(e.message ?: "Play Store indisponible") }
                    }
                }) { Text("Ouvrir le Play Store") }
            },
            dismissButton = { TextButton(onClick = { app.missingEmulator.value = null }) { Text("Annuler") } },
        )
    }

    // Android 14+ : l'émulateur (RetroArch…) doit être fermé à la main avant le lancement.
    val closePrompt by app.closeEmulatorPrompt.collectAsStateWithLifecycle()
    closePrompt?.let { prompt ->
        val scope = rememberCoroutineScope()
        var dontShowAgain by remember(prompt) { mutableStateOf(false) }
        fun dismiss() {
            if (dontShowAgain) app.settings.setCloseWarningDismissed(prompt.packageName, true)
            app.closeEmulatorPrompt.value = null
        }
        AlertDialog(
            onDismissRequest = { app.closeEmulatorPrompt.value = null },
            title = { Text("Fermez d’abord l’émulateur") },
            text = {
                Column {
                    Text(
                        "Si « ${prompt.playerName} » est encore ouvert en arrière-plan, le jeu restera sur un écran noir. " +
                            "Depuis Android 14, RomCloud ne peut plus le fermer lui-même.\n\n" +
                            "Choisissez « Forcer l’arrêt » ci-dessous, puis « Forcer l’arrêt » dans la page qui s’ouvre, " +
                            "revenez ici et choisissez « Lancer ». Si l’émulateur est déjà fermé, choisissez directement « Lancer ».",
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = dontShowAgain, onCheckedChange = { dontShowAgain = it })
                        Text("Ne plus afficher pour cet émulateur")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    dismiss()
                    app.play(activity, prompt.system, prompt.game, emulatorClosed = true)
                        ?.let { scope.launch { snackbar.showSnackbar(it) } }
                }) { Text("Lancer") }
            },
            dismissButton = {
                TextButton(onClick = {
                    try {
                        app.launcher.openAppSettings(activity, prompt.packageName)
                    } catch (e: LaunchException) {
                        scope.launch { snackbar.showSnackbar(e.message ?: "Paramètres indisponibles") }
                    }
                }) { Text("Forcer l’arrêt") }
            },
        )
    }
}
