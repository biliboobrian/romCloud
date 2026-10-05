package com.romcloud.app.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.romcloud.app.RomCloudApp
import com.romcloud.app.I18n
import com.romcloud.core.R
import com.romcloud.app.data.DownloadEvent
import com.romcloud.app.data.EmulatorApk
import com.romcloud.app.launch.LaunchException
import com.romcloud.app.libretro.CrashReport
import com.romcloud.app.libretro.CrashReports
import com.romcloud.app.libretro.CoreOptionsStore
import com.romcloud.app.libretro.LibretroCores
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Comportements globaux communs aux applications téléphone et TV : fins de téléchargement
 * (lancement automatique ou proposition « Jouer »), émulateur manquant (APK du serveur ou Play Store),
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
                    app.account.reportError("download:${event.game.systemId}", event.message, event.game.fileName)
                    launch { snackbar.showSnackbar(I18n.get(R.string.download_failed_game, event.game.title, event.message)) }
                }
            }
        }
    }

    // Émulateur absent : proposer l'APK du serveur RomCloud (s'il en a un) ou le Google Play Store.
    val missing by app.missingEmulator.collectAsStateWithLifecycle()
    missing?.let { emulator ->
        val scope = rememberCoroutineScope()
        val unavailable = stringResource(R.string.play_store_unavailable)
        // null = liste des APK en cours de chargement.
        val apk by produceState<Result<EmulatorApk?>?>(null, emulator) {
            value = Result.success(app.repository.apks().find { it.packageName == emulator.packageName })
        }
        val serverApk = apk?.getOrNull()
        AlertDialog(
            onDismissRequest = { app.missingEmulator.value = null },
            title = { Text(stringResource(R.string.missing_emulator_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(
                            R.string.missing_emulator_text,
                            emulator.playerName ?: emulator.packageName,
                            emulator.packageName,
                        ),
                    )
                    when {
                        apk == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        serverApk != null -> Text(
                            stringResource(R.string.apk_available, serverApk.label, serverApk.versionName.orEmpty(), formatSize(serverApk.size)),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        else -> Text(stringResource(R.string.apk_not_on_server), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        app.missingEmulator.value = null
                        try {
                            app.launcher.openStore(activity, emulator.packageName)
                        } catch (e: LaunchException) {
                            scope.launch { snackbar.showSnackbar(e.message ?: unavailable) }
                        }
                    }) { Text(stringResource(R.string.action_open_play_store)) }
                    if (serverApk != null) {
                        TextButton(onClick = {
                            app.missingEmulator.value = null
                            app.apkInstaller.install(activity, serverApk)
                        }) { Text(stringResource(R.string.action_install_from_server)) }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { app.missingEmulator.value = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    // Mise à jour de RomCloud : vérifiée au chargement de l'application, installée par ApkInstaller.
    LaunchedEffect(Unit) { app.updater.check() }
    val update by app.updater.available.collectAsStateWithLifecycle()
    update?.let { u ->
        AlertDialog(
            onDismissRequest = app.updater::dismiss,
            title = { Text(stringResource(R.string.update_title)) },
            text = { Text(stringResource(R.string.update_text, u.version, u.installed, formatSize(u.apk.size))) },
            confirmButton = {
                TextButton(onClick = {
                    app.updater.dismiss()
                    app.apkInstaller.install(activity, u.apk)
                }) { Text(stringResource(R.string.action_update)) }
            },
            dismissButton = {
                TextButton(onClick = app.updater::dismiss) { Text(stringResource(R.string.action_later)) }
            },
        )
    }

    // Installation d'un APK du serveur : erreurs, autorisation « sources inconnues », progression.
    LaunchedEffect(Unit) {
        app.apkInstaller.errors.collect { launch { snackbar.showSnackbar(it) } }
    }
    val lifecycle = activity.lifecycle
    LaunchedEffect(lifecycle) {
        // Au retour du réglage Android, l'installation en attente reprend si elle a été autorisée.
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { app.apkInstaller.resumePending(activity) }
    }
    val pendingApk by app.apkInstaller.pending.collectAsStateWithLifecycle()
    pendingApk?.let { apk ->
        AlertDialog(
            onDismissRequest = app.apkInstaller::dismissPending,
            title = { Text(stringResource(R.string.apk_permission_title)) },
            text = { Text(stringResource(R.string.apk_permission_text, apk.label)) },
            confirmButton = {
                TextButton(onClick = { app.apkInstaller.openInstallPermissionSettings(activity) }) {
                    Text(stringResource(R.string.action_open_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = app.apkInstaller::dismissPending) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
    val apkProgress by app.apkInstaller.progress.collectAsStateWithLifecycle()
    apkProgress?.let { p ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.apk_downloading_title, p.apk.label)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                    Text("${formatSize(p.bytes)} / ${formatSize(p.apk.size)}", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = app.apkInstaller::cancel) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    // Plantage de l'émulateur intégré lors de la dernière partie : rapport à partager.
    var crash by remember { mutableStateOf<CrashReport?>(null) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // Retour dans l'application (fin de partie) : temps de jeu et sauvegardes envoyés au profil.
            app.account.onAppResumed()
            // Laisse au processus de jeu le temps de se terminer après une sortie normale.
            delay(1500)
            withContext(Dispatchers.IO) { CrashReports.takePending(activity) }?.let {
                crash = it
                app.account.reportError("libretro-crash:${it.core}", "${it.game} (${it.core})", it.text.take(20000))
            }
        }
    }
    crash?.let { report ->
        val scope = rememberCoroutineScope()
        AlertDialog(
            onDismissRequest = { crash = null },
            title = { Text(stringResource(R.string.libretro_crash_title)) },
            text = { Text(stringResource(R.string.libretro_crash_text, report.game, report.core)) },
            confirmButton = {
                TextButton(onClick = {
                    crash = null
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "RomCloud — ${report.core} — ${report.game}")
                        .putExtra(Intent.EXTRA_TEXT, report.text)
                    runCatching { activity.startActivity(Intent.createChooser(send, null)) }
                }) { Text(stringResource(R.string.action_share_report)) }
            },
            dismissButton = {
                Row {
                    // Cœur ou options en cause : nouveau téléchargement du cœur, options par défaut.
                    TextButton(onClick = {
                        crash = null
                        if (report.core.isNotBlank()) LibretroCores(activity).delete(report.core)
                        if (report.systemId.isNotBlank()) CoreOptionsStore(activity).clear(report.systemId)
                        scope.launch { snackbar.showSnackbar(I18n.get(R.string.libretro_crash_reset_done, report.core)) }
                    }) { Text(stringResource(R.string.libretro_crash_reset)) }
                    TextButton(onClick = { crash = null }) { Text(stringResource(R.string.action_close)) }
                }
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
