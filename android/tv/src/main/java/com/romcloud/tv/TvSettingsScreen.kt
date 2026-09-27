@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.romcloud.tv

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
import com.romcloud.app.RomCloudApp
import com.romcloud.app.data.RetroArchSafMode
import com.romcloud.app.data.Settings
import kotlinx.coroutines.launch

@Composable
fun TvSettingsScreen(app: RomCloudApp, onSaved: () -> Unit) {
    val context = LocalContext.current
    val current = remember { app.settings.config.value }
    var url by rememberSaveable { mutableStateOf(current.serverUrl) }
    var key by rememberSaveable { mutableStateOf(current.apiKey) }
    var dir by rememberSaveable { mutableStateOf(current.romsDir) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var testing by remember { mutableStateOf(false) }
    var hasPermission by remember { mutableStateOf(app.library.hasStoragePermission()) }
    var showAdbHelp by remember { mutableStateOf(false) }
    var safMode by remember { mutableStateOf(app.settings.retroArchSafMode) }
    var quitOnExit by remember { mutableStateOf(app.settings.retroArchQuitOnExit) }
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
        Text("Paramètres", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)

        Section("Serveur RomCloud")
        OutlinedTextField(
            value = url, onValueChange = { url = it },
            label = { androidx.compose.material3.Text("Adresse du serveur (ex. 192.168.1.10:8080)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(0.7f).focusRequester(firstField),
        )
        OutlinedTextField(
            value = key, onValueChange = { key = it },
            label = { androidx.compose.material3.Text("Clé d’API (API_KEY du serveur)") },
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
                        true to "Connecté à ${info.name} ${info.version}"
                    } catch (e: Exception) {
                        false to (e.message ?: "Connexion impossible")
                    }
                    testing = false
                }
            }, enabled = url.isNotBlank() && !testing) { Text(if (testing) "Test en cours…" else "Tester la connexion") }
        }
        testResult?.let { (ok, msg) ->
            Text(msg, color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }

        Section("Stockage des ROMs")
        OutlinedTextField(
            value = dir, onValueChange = { dir = it },
            label = { androidx.compose.material3.Text("Dossier local (un sous-dossier par système)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(0.7f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(SmallGap)) {
            OutlinedButton(onClick = { dir = Settings.defaultRomsDir() }) { Text("Dossier par défaut") }
        }
        Text(
            if (hasPermission) "✓ Accès à tous les fichiers autorisé" else "Accès aux fichiers non autorisé",
            color = if (hasPermission) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            "Les émulateurs doivent pouvoir lire les ROMs : elles sont enregistrées dans un dossier partagé, " +
                "ce qui nécessite l’autorisation « Accès à tous les fichiers ».",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!hasPermission) {
            Button(onClick = ::requestPermission) { Text("Autoriser") }
        }
        if (showAdbHelp && !hasPermission) {
            Text(
                "Ce téléviseur ne propose pas l’écran d’autorisation. Depuis un ordinateur connecté au même réseau " +
                    "(débogage ADB activé dans les Options pour les développeurs du téléviseur), exécutez :",
                style = MaterialTheme.typography.bodyMedium,
            )
            SelectionContainer {
                Text(
                    "adb connect <ip-du-téléviseur>\nadb shell appops set --uid ${context.packageName} MANAGE_EXTERNAL_STORAGE allow",
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Section("RetroArch")
        Text("Accès aux ROMs pour RetroArch", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(SmallGap)) {
            RetroArchSafMode.entries.forEach { mode ->
                FilterChip(selected = safMode == mode, onClick = {
                    safMode = mode
                    app.settings.retroArchSafMode = mode
                }) { Text(mode.label) }
            }
        }
        Text(
            "Automatique : chemin « saf:// » si RetroArch vient du Play Store (ajoutez alors le dossier des ROMs " +
                "dans RetroArch via « Charger du contenu »), chemin classique sinon.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilterChip(selected = quitOnExit, onClick = {
            quitOnExit = !quitOnExit
            app.settings.retroArchQuitOnExit = quitOnExit
        }) { Text(if (quitOnExit) "✓ Fermer RetroArch en quittant le jeu" else "Fermer RetroArch en quittant le jeu") }
        Text(
            "Recommandé : évite l’écran noir au lancement suivant. Sauvegardez avant de quitter une partie.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(
            onClick = {
                app.settings.save(url, key, dir)
                app.repository.clearMemory()
                onSaved()
            },
            enabled = url.isNotBlank(),
            modifier = Modifier.padding(top = 12.dp),
        ) { Text("Enregistrer") }
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
