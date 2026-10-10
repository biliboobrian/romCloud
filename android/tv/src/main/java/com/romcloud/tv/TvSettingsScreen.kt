@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.romcloud.tv

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.romcloud.app.AppLanguage
import com.romcloud.app.RomCloudApp
import com.romcloud.app.data.GameOrder
import com.romcloud.app.data.RetroArchSafMode
import com.romcloud.app.data.Settings
import com.romcloud.app.ui.ErrorReportSection
import com.romcloud.app.ui.appVersion
import com.romcloud.core.R
import kotlinx.coroutines.launch

@Composable
fun TvSettingsScreen(app: RomCloudApp, onSaved: () -> Unit) {
    val context = LocalContext.current
    val current = remember { app.settings.config.value }
    var url by rememberSaveable { mutableStateOf(current.serverUrl) }
    var key by rememberSaveable { mutableStateOf(current.apiKey) }
    var dir by rememberSaveable { mutableStateOf(current.romsDir) }
    var biosDir by rememberSaveable { mutableStateOf(current.biosDir) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var testing by remember { mutableStateOf(false) }
    var hasPermission by remember { mutableStateOf(app.library.hasStoragePermission()) }
    var showAdbHelp by remember { mutableStateOf(false) }
    var safMode by remember { mutableStateOf(app.settings.retroArchSafMode) }
    var quitOnExit by remember { mutableStateOf(app.settings.retroArchQuitOnExit) }
    var hideUnidentified by remember { mutableStateOf(app.settings.hideUnidentified) }
    var gameOrder by remember { mutableStateOf(app.settings.gameOrder) }
    val scope = rememberCoroutineScope()
    val firstField = remember { FocusRequester() }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { hasPermission = app.library.hasStoragePermission() }
    }
    LaunchedEffect(Unit) { runCatching { firstField.requestFocus() } }

    fun requestPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            showAdbHelp = true
            return
        }
        // Beaucoup de téléviseurs n'ont pas cet écran : repli sur la commande ADB.
        val intents = listOf(
            Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")),
            Intent(AndroidSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
        )
        for (intent in intents) {
            try {
                context.startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
                // intent suivant
            }
        }
        showAdbHelp = true
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(TvSafePadding),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.action_settings), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)

        Section(stringResource(R.string.settings_server))
        OutlinedTextField(
            value = url, onValueChange = { url = it },
            label = { androidx.compose.material3.Text(stringResource(R.string.server_address_example)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(0.7f).focusRequester(firstField),
        )
        OutlinedTextField(
            value = key, onValueChange = { key = it },
            label = { androidx.compose.material3.Text(stringResource(R.string.api_key_label)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(0.7f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(SmallGap)) {
            OutlinedButton(onClick = {
                testing = true
                testResult = null
                scope.launch {
                    testResult = try {
                        val info = app.api.testConnection(url, key)
                        true to context.getString(R.string.connected_to, info.name, info.version)
                    } catch (e: Exception) {
                        false to (e.message ?: context.getString(R.string.connection_failed))
                    }
                    testing = false
                }
            }, enabled = url.isNotBlank() && !testing) { Text(stringResource(if (testing) R.string.testing else R.string.test_connection)) }
        }
        testResult?.let { (ok, msg) ->
            Text(msg, color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }

        Section(stringResource(R.string.settings_language))
        LanguageSelector(app)

        Section(stringResource(R.string.report_section))
        ErrorReportSection(app)

        Section(stringResource(R.string.settings_display))
        FilterChip(selected = hideUnidentified, onClick = {
            hideUnidentified = !hideUnidentified
            app.settings.hideUnidentified = hideUnidentified
        }) { Text(stringResource(if (hideUnidentified) R.string.hide_unidentified_on else R.string.hide_unidentified)) }
        Text(
            stringResource(R.string.hide_unidentified_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        var autoUploadStates by remember { mutableStateOf(app.settings.autoUploadStates) }
        FilterChip(selected = autoUploadStates, onClick = {
            autoUploadStates = !autoUploadStates
            app.settings.autoUploadStates = autoUploadStates
        }) { Text(stringResource(if (autoUploadStates) R.string.settings_auto_upload_states_on else R.string.settings_auto_upload_states)) }
        Text(
            stringResource(R.string.settings_auto_upload_states_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(stringResource(R.string.order_games), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(SmallGap)) {
            GameOrder.entries.forEach { order ->
                FilterChip(selected = gameOrder == order, onClick = {
                    gameOrder = order
                    app.settings.gameOrder = order
                }) { Text(stringResource(order.label)) }
            }
        }
        Text(
            stringResource(R.string.order_games_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Section(stringResource(R.string.settings_storage))
        OutlinedTextField(
            value = dir, onValueChange = { dir = it },
            label = { androidx.compose.material3.Text(stringResource(R.string.local_folder_tv)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(0.7f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(SmallGap)) {
            OutlinedButton(onClick = { dir = Settings.defaultRomsDir() }) { Text(stringResource(R.string.default_folder)) }
        }
        OutlinedTextField(
            value = biosDir, onValueChange = { biosDir = it },
            label = { androidx.compose.material3.Text(stringResource(R.string.bios_folder)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(0.7f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(SmallGap)) {
            Settings.biosPresets().forEach { (label, path) ->
                FilterChip(selected = biosDir == path, onClick = { biosDir = path }) { Text(stringResource(label)) }
            }
        }
        Text(
            stringResource(R.string.bios_folder_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(if (hasPermission) R.string.all_files_granted else R.string.all_files_denied),
            color = if (hasPermission) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            stringResource(R.string.storage_hint_tv),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!hasPermission) {
            Button(onClick = ::requestPermission) { Text(stringResource(R.string.action_allow)) }
        }
        if (showAdbHelp && !hasPermission) {
            Text(
                stringResource(R.string.tv_adb_hint),
                style = MaterialTheme.typography.bodyMedium,
            )
            SelectionContainer {
                Text(
                    "adb connect ${stringResource(R.string.tv_ip_placeholder)}\n" +
                        "adb shell appops set --uid ${context.packageName} MANAGE_EXTERNAL_STORAGE allow",
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Section("RetroArch")
        Text(stringResource(R.string.retroarch_access), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(SmallGap)) {
            RetroArchSafMode.entries.forEach { mode ->
                FilterChip(selected = safMode == mode, onClick = {
                    safMode = mode
                    app.settings.retroArchSafMode = mode
                }) { Text(stringResource(mode.label)) }
            }
        }
        Text(
            stringResource(R.string.retroarch_access_hint_tv),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilterChip(selected = quitOnExit, onClick = {
            quitOnExit = !quitOnExit
            app.settings.retroArchQuitOnExit = quitOnExit
        }) { Text(stringResource(if (quitOnExit) R.string.quit_on_exit_on else R.string.quit_on_exit)) }
        Text(
            stringResource(R.string.quit_on_exit_hint_tv),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(
            onClick = {
                app.settings.save(url, key, dir, biosDir)
                app.repository.clearMemory()
                onSaved()
            },
            enabled = url.isNotBlank(),
            modifier = Modifier.padding(top = 12.dp),
        ) { Text(stringResource(R.string.action_save)) }
        Text(
            stringResource(R.string.settings_version, appVersion(context)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp),
    )
}

/** Langue de l'application : Système / Français / English (appliquée immédiatement). */
@Composable
private fun LanguageSelector(app: RomCloudApp) {
    val activity = LocalContext.current as Activity
    val current = remember { app.settings.language }
    val options = listOf(
        AppLanguage.SYSTEM to stringResource(R.string.language_system),
        "fr" to stringResource(R.string.language_fr),
        "en" to stringResource(R.string.language_en),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(SmallGap)) {
        options.forEach { (code, label) ->
            FilterChip(
                selected = current == code,
                onClick = {
                    if (code != current) {
                        app.settings.language = code
                        activity.recreate()
                    }
                },
            ) { Text(label) }
        }
    }
}
