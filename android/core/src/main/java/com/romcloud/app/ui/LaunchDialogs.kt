package com.romcloud.app.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.romcloud.app.RomCloudApp
import com.romcloud.app.I18n
import com.romcloud.core.R
import com.romcloud.app.data.AppUpdater
import com.romcloud.app.data.DownloadEvent
import com.romcloud.app.data.EmulatorApk
import com.romcloud.app.data.EmulatorReleases
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
 * (lancement automatique ou proposition « Jouer »), émulateur manquant (dernière version de
 * l'émulateur, APK du serveur ou Play Store),
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
                    val pending = app.autoLaunch.remove(event.game.id)
                    if (pending != null && resumed) {
                        // Mêmes options qu'au lancement demandé (reprise, état, cœur), changement de cœur déjà confirmé.
                        app.play(
                            activity, event.system, event.game, resume = pending.resume, stream = pending.stream,
                            streamMode = pending.streamMode, state = pending.state, core = pending.core, coreSwitchConfirmed = true,
                            disk = pending.disk,
                        )?.let { snackbar.showSnackbar(it) }
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

    // Émulateur absent : proposer la dernière version publiée par l'émulateur (Release GitHub ou
    // Forgejo), à défaut l'APK du serveur RomCloud (s'il en a un), ou le Google Play Store.
    // Mise à jour d'un émulateur installé hors Play Store (installedVersion) : même dialogue, sans le Play Store.
    val missing by app.missingEmulator.collectAsStateWithLifecycle()
    missing?.let { emulator ->
        val update = emulator.installedVersion
        val scope = rememberCoroutineScope()
        val unavailable = stringResource(R.string.play_store_unavailable)
        // null = recherche en cours.
        val apk by produceState<Result<EmulatorApk?>?>(null, emulator) {
            val release = app.emulatorReleases.latest(emulator.packageName)
            value = Result.success(release ?: app.repository.apks().find { it.packageName == emulator.packageName })
        }
        val found = apk?.getOrNull()
        val release = found?.takeIf { it.source != null }
        val serverApk = found?.takeIf { it.source == null }
        AlertDialog(
            onDismissRequest = { app.missingEmulator.value = null },
            title = {
                Text(if (update != null) stringResource(R.string.update_emulator_title, emulator.playerName ?: emulator.packageName)
                else stringResource(R.string.missing_emulator_title))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (update != null) {
                        Text(stringResource(R.string.update_emulator_installed, update.ifEmpty { "?" }))
                    } else {
                        Text(
                            stringResource(
                                R.string.missing_emulator_text,
                                emulator.playerName ?: emulator.packageName,
                                emulator.packageName,
                            ),
                        )
                    }
                    when {
                        apk == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        update != null && found == null -> Text(stringResource(R.string.update_emulator_not_found), style = MaterialTheme.typography.bodySmall)
                        release != null -> Text(
                            stringResource(R.string.apk_release_available, release.label, release.versionName.orEmpty(), formatSize(release.size), release.source.orEmpty()),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        serverApk != null -> Text(
                            stringResource(R.string.apk_available, serverApk.label, serverApk.versionName.orEmpty(), formatSize(serverApk.size)),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        else -> Text(stringResource(R.string.apk_not_on_server), style = MaterialTheme.typography.bodySmall)
                    }
                    if (update != null && found?.versionName != null && found.versionName == update) {
                        Text(stringResource(R.string.update_emulator_up_to_date), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Row {
                    // Application absente du Play Store (Winlator) : seulement son APK.
                    if (update == null && EmulatorReleases.SOURCES[emulator.packageName]?.playStore != false) {
                        TextButton(onClick = {
                            app.missingEmulator.value = null
                            try {
                                app.launcher.openStore(activity, emulator.packageName)
                            } catch (e: LaunchException) {
                                scope.launch { snackbar.showSnackbar(e.message ?: unavailable) }
                            }
                        }) { Text(stringResource(R.string.action_open_play_store)) }
                    }
                    found?.let { download ->
                        TextButton(onClick = {
                            app.missingEmulator.value = null
                            app.apkInstaller.install(activity, download)
                        }) {
                            Text(stringResource(when {
                                update != null -> R.string.action_update_emulator
                                release != null -> R.string.action_download_latest
                                else -> R.string.action_install_from_server
                            }))
                        }
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
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.update_text, u.version, u.installed, formatSize(u.apk.size)))
                    UpdateChanges(u)
                }
            },
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

    // Installation d'un APK (Release de l'émulateur ou serveur) : erreurs, autorisation « sources inconnues », progression.
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
            // Jeu PC planté dans RomCloud PC (Wine ou l'application) : signalé au serveur.
            withContext(Dispatchers.IO) { com.romcloud.app.launch.Winlator.takeCompanionCrash(activity) }?.let { (title, text) ->
                app.account.reportError("romcloud-pc-crash", title, text)
                launch { snackbar.showSnackbar(I18n.get(R.string.pc_crash_reported, title)) }
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

    // Sauvegardes faites hors ligne (retour en ligne, ouverture) : envoi proposé. Ne sont pas envoyées
    // celles qui ne seraient pas gardées ou écraseraient une sauvegarde plus récente du serveur.
    val uploadPending by app.uploadPrompt.collectAsStateWithLifecycle()
    uploadPending?.let { count ->
        val focus = remember { FocusRequester() }
        if (app.account.isTv) LaunchedEffect(count) { runCatching { focus.requestFocus() } }
        AlertDialog(
            onDismissRequest = { app.uploadPrompt.value = null },
            title = { Text(stringResource(R.string.offline_upload_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(pluralString(R.plurals.offline_upload_text, count, count))
                    Text(stringResource(R.string.offline_upload_rule), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    app.uploadPrompt.value = null
                    // Envoi poursuivi même si l'écran change (portée de l'application).
                    app.appScope.launch {
                        val (sent, skipped) = app.account.uploadPending()
                        withContext(Dispatchers.Main) {
                            if (sent > 0) snackbar.showSnackbar(I18n.plural(R.plurals.offline_synced, sent, sent))
                            if (skipped > 0) snackbar.showSnackbar(I18n.plural(R.plurals.offline_upload_skipped, skipped, skipped))
                        }
                    }
                }, modifier = Modifier.focusRequester(focus)) { Text(stringResource(R.string.offline_upload_send)) }
            },
            dismissButton = {
                TextButton(onClick = { app.uploadPrompt.value = null }) { Text(stringResource(R.string.offline_upload_later)) }
            },
        )
    }

    // Reprise ou état fait avec un autre cœur que l'émulateur du système : il le remplace, après confirmation.
    val switchPrompt by app.coreSwitchPrompt.collectAsStateWithLifecycle()
    switchPrompt?.let { prompt ->
        val scope = rememberCoroutineScope()
        val core = prompt.target.libretroCore.orEmpty()
        AlertDialog(
            onDismissRequest = { app.coreSwitchPrompt.value = null },
            title = { Text(stringResource(R.string.core_switch_title)) },
            text = { Text(stringResource(R.string.core_switch_text, core, prompt.currentName ?: "—", prompt.target.name)) },
            confirmButton = {
                TextButton(onClick = {
                    app.coreSwitchPrompt.value = null
                    app.settings.setPreferredPlayer(prompt.system.id, prompt.target.uniqueId)
                    app.play(
                        activity, prompt.system, prompt.game, resume = prompt.resume, stream = prompt.stream,
                        streamMode = prompt.streamMode, state = prompt.state, core = prompt.core, coreSwitchConfirmed = true,
                        missingBios = prompt.missingBios, disk = prompt.disk,
                    )?.let { scope.launch { snackbar.showSnackbar(it) } }
                }) { Text(stringResource(R.string.core_switch_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { app.coreSwitchPrompt.value = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    // BIOS du système absents : les télécharger avant de jouer (jeu lancé à la fin, mêmes options) ou jouer quand même.
    val biosPrompt by app.biosPrompt.collectAsStateWithLifecycle()
    biosPrompt?.let { prompt ->
        val scope = rememberCoroutineScope()
        val pending = prompt.launch
        // TV : bouton principal sélectionné à la télécommande.
        val focus = remember { FocusRequester() }
        if (app.account.isTv) LaunchedEffect(prompt) { runCatching { focus.requestFocus() } }
        AlertDialog(
            onDismissRequest = { app.biosPrompt.value = null },
            title = { Text(stringResource(R.string.bios_before_play_title)) },
            text = { Text(stringResource(R.string.bios_before_play_text, prompt.missing.size, formatSize(prompt.missing.sumOf { it.size }))) },
            confirmButton = {
                TextButton(onClick = {
                    app.biosPrompt.value = null
                    app.autoLaunch[prompt.game.id] = pending
                    app.downloader.dismissError(prompt.game.id)
                    app.downloader.start(prompt.system, prompt.game, prompt.missing, includeRom = false)
                }, modifier = Modifier.focusRequester(focus)) { Text(stringResource(R.string.action_download_and_play)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    app.biosPrompt.value = null
                    app.play(
                        activity, prompt.system, prompt.game, resume = pending.resume, stream = pending.stream,
                        streamMode = pending.streamMode, state = pending.state, core = pending.core, coreSwitchConfirmed = true,
                        disk = pending.disk,
                    )?.let { scope.launch { snackbar.showSnackbar(it) } }
                }) { Text(stringResource(R.string.action_play_anyway)) }
            },
        )
    }

    // Jeu PC : accès aux fichiers de Winlator demandé une fois (sélecteur de dossier d'Android),
    // pour ajouter le lecteur des ROMs à son conteneur ; refusé : lancé avec le conseil d'avant.
    val drivePrompt by app.winlatorDrivePrompt.collectAsStateWithLifecycle()
    val driveScope = rememberCoroutineScope()
    fun launchWindowsGame(askDrive: Boolean) {
        val prompt = app.winlatorDrivePrompt.value ?: return
        app.winlatorDrivePrompt.value = null
        app.playWindowsGame(activity, prompt.system, prompt.game, askDrive)?.let { driveScope.launch { snackbar.showSnackbar(it) } }
    }
    val winlatorFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                app.settings.winlatorTree = uri.toString()
            }
        }
        launchWindowsGame(askDrive = uri != null)
    }
    drivePrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = { app.winlatorDrivePrompt.value = null },
            title = { Text(stringResource(R.string.winlator_access_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (prompt.retry) Text(stringResource(R.string.winlator_access_retry), color = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.winlator_access_text))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        winlatorFolder.launch(prompt.initialUri)
                    } catch (e: ActivityNotFoundException) {
                        launchWindowsGame(askDrive = false)
                    }
                }) { Text(stringResource(R.string.winlator_access_allow)) }
            },
            dismissButton = {
                TextButton(onClick = { launchWindowsGame(askDrive = false) }) { Text(stringResource(R.string.action_later)) }
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

/** Modifications depuis la version installée, par version, repliées sous un bouton. */
@Composable
private fun UpdateChanges(update: AppUpdater.Update) {
    if (update.changes.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) {
        Text(stringResource(R.string.update_changes, update.installed, update.version))
        Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
    }
    if (!expanded) return
    for (group in update.changes) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("v${group.version}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            for (item in group.items) Text("• $item", style = MaterialTheme.typography.bodySmall)
        }
    }
}
