package com.romcloud.app.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.romcloud.app.RomCloudApp
import com.romcloud.app.data.RetroArchSafMode
import com.romcloud.app.data.Settings
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(app: RomCloudApp, canGoBack: Boolean, onBack: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val current = remember { app.settings.config.value }
    var url by rememberSaveable { mutableStateOf(current.serverUrl) }
    var key by rememberSaveable { mutableStateOf(current.apiKey) }
    var dir by rememberSaveable { mutableStateOf(current.romsDir) }
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
                true to "Connecté à ${info.name} ${info.version}"
            } catch (e: Exception) {
                false to (e.message ?: "Connexion impossible")
            }
            testing = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Paramètres") },
                navigationIcon = {
                    if (canGoBack) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
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
            Text("Serveur RomCloud", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = url, onValueChange = { url = it },
                label = { Text("Adresse du serveur") },
                placeholder = { Text("http://192.168.1.10:8080") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = key, onValueChange = { key = it },
                label = { Text("Clé d’API (API_KEY du serveur)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = ::test, enabled = url.isNotBlank() && !testing) {
                    if (testing) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
                    Text("Tester la connexion")
                }
            }
            testResult?.let { (ok, msg) ->
                Text(msg, color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }

            Text("Affichage", style = MaterialTheme.typography.titleMedium)
            val fullscreen by app.settings.fullscreen.collectAsStateWithLifecycle()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Plein écran", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Masque la barre d’état et les boutons de navigation. Glissez depuis le bord de l’écran pour les afficher.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = fullscreen, onCheckedChange = app.settings::setFullscreen)
            }

            Text("Émulateurs", style = MaterialTheme.typography.titleMedium)
            var quitOnExit by remember { mutableStateOf(app.settings.retroArchQuitOnExit) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Fermer RetroArch en quittant le jeu", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Recommandé : évite l’écran noir au lancement suivant. Quitter RetroArch en cours de partie " +
                            "(bouton Accueil, applications récentes) ferme alors le jeu — sauvegardez avant.",
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
                Text("Accès aux ROMs pour RetroArch", style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RetroArchSafMode.entries.forEach { mode ->
                        FilterChip(
                            selected = safMode == mode,
                            onClick = {
                                safMode = mode
                                app.settings.retroArchSafMode = mode
                            },
                            label = { Text(mode.label) },
                        )
                    }
                }
                Text(
                    "La version Play Store de RetroArch ne peut lire le dossier des ROMs qu’en SAF : dans RetroArch, " +
                        "« Charger du contenu », ajoutez le dossier des ROMs de RomCloud (ci-dessous) et autorisez l’accès. " +
                        "Automatique : SAF si RetroArch vient du Play Store, chemin classique sinon.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text("Stockage des ROMs", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = dir, onValueChange = { dir = it },
                label = { Text("Dossier local") },
                supportingText = { Text("Un sous-dossier par système y est créé (ex. …/snes/).") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { dir = Settings.defaultRomsDir() }) { Text("Dossier par défaut") }
                TextButton(onClick = {
                    dir = context.getExternalFilesDir("roms")?.absolutePath ?: dir
                }) { Text("Dossier privé de l’app") }
            }

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (hasPermission) "✓ Accès à tous les fichiers autorisé" else "Accès aux fichiers non autorisé",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (hasPermission) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                    Text(
                        "Les émulateurs (RetroArch, etc.) doivent pouvoir lire les ROMs : elles sont donc enregistrées dans " +
                            "un dossier partagé, ce qui nécessite l’autorisation « Accès à tous les fichiers ». " +
                            "Le dossier privé de l’app ne la nécessite pas, mais la plupart des émulateurs ne pourront pas le lire.",
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
                        }) { Text("Autoriser") }
                    }
                }
            }

            Button(
                onClick = {
                    app.settings.save(url, key, dir)
                    app.repository.clearMemory()
                    onSaved()
                },
                enabled = url.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Enregistrer") }
        }
    }
}
