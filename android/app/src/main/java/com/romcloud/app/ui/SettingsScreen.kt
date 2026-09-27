package com.romcloud.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.romcloud.app.AppLanguage
import com.romcloud.app.RomCloudApp
import com.romcloud.app.data.RetroArchSafMode
import com.romcloud.app.data.Settings
import com.romcloud.core.R
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(app: RomCloudApp, canGoBack: Boolean, onBack: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val current = remember { app.settings.config.value }
    var url by rememberSaveable { mutableStateOf(current.serverUrl) }
    var key by rememberSaveable { mutableStateOf(current.apiKey) }
    var dir by rememberSaveable { mutableStateOf(current.romsDir) }
    var biosDir by rememberSaveable { mutableStateOf(current.biosDir) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var hasPermission by remember { mutableStateOf(app.library.hasStoragePermission()) }
    val scope = rememberCoroutineScope()

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { hasPermission = app.library.hasStoragePermission() }
    }
    val legacyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
    }

    fun test() {
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
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.action_settings)) },
                navigationIcon = {
                    if (canGoBack) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.settings_server), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = url, onValueChange = { url = it },
                label = { Text(stringResource(R.string.server_address)) },
                placeholder = { Text("http://192.168.1.10:8080") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = key, onValueChange = { key = it },
                label = { Text(stringResource(R.string.api_key_label)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = ::test, enabled = url.isNotBlank() && !testing) {
                    if (testing) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.test_connection))
                }
            }
            testResult?.let { (ok, msg) ->
                Text(msg, color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }

            Text(stringResource(R.string.settings_display), style = MaterialTheme.typography.titleMedium)
            val fullscreen by app.settings.fullscreen.collectAsStateWithLifecycle()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.fullscreen), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.fullscreen_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = fullscreen, onCheckedChange = app.settings::setFullscreen)
            }

            Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleMedium)
            LanguageSelector(app)

            Text(stringResource(R.string.settings_emulators), style = MaterialTheme.typography.titleMedium)
            var quitOnExit by remember { mutableStateOf(app.settings.retroArchQuitOnExit) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.quit_on_exit), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.quit_on_exit_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = quitOnExit, onCheckedChange = {
                    quitOnExit = it
                    app.settings.retroArchQuitOnExit = it
                })
            }

            var safMode by remember { mutableStateOf(app.settings.retroArchSafMode) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.retroarch_access), style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RetroArchSafMode.entries.forEach { mode ->
                        FilterChip(
                            selected = safMode == mode,
                            onClick = {
                                safMode = mode
                                app.settings.retroArchSafMode = mode
                            },
                            label = { Text(stringResource(mode.label)) },
                        )
                    }
                }
                Text(
                    stringResource(R.string.retroarch_access_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(stringResource(R.string.settings_storage), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = dir, onValueChange = { dir = it },
                label = { Text(stringResource(R.string.local_folder)) },
                supportingText = { Text(stringResource(R.string.local_folder_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { dir = Settings.defaultRomsDir() }) { Text(stringResource(R.string.default_folder)) }
                TextButton(onClick = {
                    dir = context.getExternalFilesDir("roms")?.absolutePath ?: dir
                }) { Text(stringResource(R.string.private_folder)) }
            }

            OutlinedTextField(
                value = biosDir, onValueChange = { biosDir = it },
                label = { Text(stringResource(R.string.bios_folder)) },
                supportingText = { Text(stringResource(R.string.bios_folder_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Settings.biosPresets().forEach { (label, path) ->
                    FilterChip(selected = biosDir == path, onClick = { biosDir = path }, label = { Text(stringResource(label)) })
                }
            }

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(if (hasPermission) R.string.all_files_granted else R.string.all_files_denied),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (hasPermission) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                    Text(
                        stringResource(R.string.storage_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (!hasPermission) {
                        Button(onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                val intent = Intent(
                                    AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:${context.packageName}"),
                                )
                                runCatching { context.startActivity(intent) }.onFailure {
                                    context.startActivity(Intent(AndroidSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                                }
                            } else {
                                legacyPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            }
                        }) { Text(stringResource(R.string.action_allow)) }
                    }
                }
            }

            Button(
                onClick = {
                    app.settings.save(url, key, dir, biosDir)
                    app.repository.clearMemory()
                    onSaved()
                },
                enabled = url.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.action_save)) }
        }
    }
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
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (code, label) ->
            FilterChip(
                selected = current == code,
                onClick = {
                    if (code != current) {
                        app.settings.language = code
                        activity.recreate()
                    }
                },
                label = { Text(label) },
            )
        }
    }
}
