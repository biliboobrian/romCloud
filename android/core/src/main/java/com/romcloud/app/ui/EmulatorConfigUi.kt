package com.romcloud.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.romcloud.app.I18n
import com.romcloud.app.data.EmulatorConfig
import com.romcloud.app.data.EmulatorConfigFiles
import com.romcloud.app.data.IniText
import com.romcloud.app.data.Settings
import com.romcloud.core.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Émulateur externe dont RomCloud sait modifier les réglages : paquet, nom affiché, réglages. */
data class EmulatorConfigTarget(
    val pkg: String,
    val name: String,
    val schema: EmulatorConfig.Schema,
    /** Fichier de l'application Windows -> chemin dans le dossier de l'émulateur Android. */
    val files: Map<String, String>,
)

/** Réglages lus : valeur actuelle de chaque champ (null : absent du fichier, valeur de l'émulateur). */
private class Loaded(val tree: Uri, val initial: List<String?>, val missing: Boolean)

/** Liste des réglages d'emulator-config.json (assets), lue une fois. */
private var configCache: EmulatorConfig? = null

/** Réglages de l'émulateur externe du paquet [pkg] ([name] : nom affiché), null s'il n'en a pas. */
fun emulatorConfigTarget(context: Context, pkg: String, name: String): EmulatorConfigTarget? {
    val config = configCache ?: runCatching {
        EmulatorConfig.parse(context.assets.open(EmulatorConfig.ASSET).bufferedReader().use { it.readText() })
    }.getOrNull()?.also { configCache = it } ?: return null
    return config.forPackage(pkg)?.let { EmulatorConfigTarget(pkg, name, it, config.files) }
}

/**
 * « Configurer {émulateur} » ([button] : bouton de l'écran, téléphone ou TV) : accès aux fichiers de
 * l'émulateur demandé une fois (sélecteur de dossier d'Android, sur son fournisseur de documents), puis
 * fenêtre de ses principaux réglages, enregistrés dans ses fichiers ; seuls les réglages changés sont
 * écrits.
 */
@Composable
fun EmulatorConfigAction(
    target: EmulatorConfigTarget,
    settings: Settings,
    onMessage: (String) -> Unit,
    button: @Composable (onClick: () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var askAccess by remember { mutableStateOf<Uri?>(null) }
    var retry by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf<Loaded?>(null) }

    fun load(tree: Uri) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val files = EmulatorConfigFiles(context, tree)
                    val texts = target.schema.fileNames.associateWith { name -> target.files[name]?.let(files::read) }
                    val values = texts.mapValues { (_, text) -> text?.let(IniText::parse) }
                    Loaded(
                        tree,
                        target.schema.fields.map { f -> EmulatorConfig.valueOf(f, values[f.file]?.get("${f.section}/${f.key}")) },
                        missing = texts.values.any { it == null },
                    ) to texts.values.all { it == null }
                }
            }
            result.onSuccess { (l, none) ->
                if (none) onMessage(I18n.get(R.string.emulator_config_no_file, target.name)) else loaded = l
            }.onFailure { onMessage(it.message ?: it.toString()) }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val authority = EmulatorConfigFiles.authority(context, target.pkg)
        if (uri == null || authority == null) return@rememberLauncherForActivityResult
        if (!EmulatorConfigFiles.isRoot(context, uri, authority)) {
            retry = true
            askAccess = EmulatorConfigFiles.initialUri(context, authority)
            return@rememberLauncherForActivityResult
        }
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            settings.setEmulatorConfigTree(target.pkg, uri.toString())
        }
        load(uri)
    }

    button {
        val authority = EmulatorConfigFiles.authority(context, target.pkg)
        val tree = settings.emulatorConfigTree(target.pkg)?.toUri()
        when {
            authority == null -> onMessage(I18n.get(R.string.emulator_config_no_provider, target.name))
            tree != null && tree.authority == authority && EmulatorConfigFiles.hasAccess(context, tree) -> load(tree)
            else -> {
                retry = false
                askAccess = EmulatorConfigFiles.initialUri(context, authority)
                    ?: return@button onMessage(I18n.get(R.string.emulator_config_no_provider, target.name))
            }
        }
    }

    askAccess?.let { initial ->
        AlertDialog(
            onDismissRequest = { askAccess = null },
            title = { Text(I18n.get(R.string.emulator_config_access_title, target.name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (retry) Text(I18n.get(R.string.emulator_config_access_retry, target.name), color = MaterialTheme.colorScheme.error)
                    Text(I18n.get(R.string.emulator_config_access_text, target.name))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    askAccess = null
                    try {
                        picker.launch(initial)
                    } catch (e: ActivityNotFoundException) {
                        onMessage(I18n.get(R.string.emulator_config_no_provider, target.name))
                    }
                }) { Text(I18n.get(R.string.winlator_access_allow)) }
            },
            dismissButton = { TextButton(onClick = { askAccess = null }) { Text(I18n.get(R.string.action_later)) } },
        )
    }

    loaded?.let { l ->
        EmulatorConfigDialog(target, l, onDismiss = { loaded = null }) { changes ->
            scope.launch {
                val error = withContext(Dispatchers.IO) {
                    runCatching {
                        val files = EmulatorConfigFiles(context, l.tree)
                        changes.groupBy { it.first.file }.forEach { (name, list) ->
                            val path = target.files[name] ?: return@forEach
                            val before = files.read(path) ?: return@forEach
                            val after = list.fold(before) { text, (f, value) -> IniText.setValue(text, f.section, f.key, value, target.schema.style) }
                            if (after != before) files.write(path, after)
                        }
                    }.exceptionOrNull()
                }
                if (error == null) {
                    loaded = null
                    onMessage(I18n.get(R.string.emulator_config_saved, target.name))
                } else {
                    onMessage(error.message ?: error.toString())
                }
            }
        }
    }
}

@Composable
private fun EmulatorConfigDialog(
    target: EmulatorConfigTarget,
    loaded: Loaded,
    onDismiss: () -> Unit,
    onSave: (List<Pair<EmulatorConfig.Field, String>>) -> Unit,
) {
    val language = remember { I18n.language() }
    val fields = target.schema.fields
    // Valeur choisie par champ (index) ; absente : celle du fichier.
    val chosen = remember { mutableStateMapOf<Int, String>() }
    var picking by remember { mutableStateOf<Int?>(null) }
    val unset = I18n.get(R.string.emulator_config_default)

    fun shown(i: Int): String {
        val f = fields[i]
        val v = chosen[i] ?: loaded.initial[i] ?: return unset
        return when (f.type) {
            EmulatorConfig.Type.BOOL -> I18n.get(if (v == f.on) R.string.emulator_config_on else R.string.emulator_config_off)
            EmulatorConfig.Type.SELECT -> f.options.firstOrNull { it.value == v }?.label?.get(language) ?: I18n.get(R.string.emulator_config_other, v)
            EmulatorConfig.Type.NUMBER -> f.unit?.let { "$v $it" } ?: v
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(I18n.get(R.string.emulator_config_title, target.name)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(target.schema.note.get(language).replace("{name}", target.name), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (loaded.missing) {
                    Text(I18n.get(R.string.emulator_config_no_file, target.name), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                var index = 0
                target.schema.groups.forEach { group ->
                    Text(group.title.get(language), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    group.fields.forEach { f ->
                        val i = index++
                        TextButton(onClick = { picking = i }, modifier = Modifier.fillMaxWidth().focusHighlight(MaterialTheme.shapes.small)) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(f.label.get(language), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                Text(shown(i), style = MaterialTheme.typography.bodySmall, color = if (i in chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                f.hint?.let { Text(it.get(language), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val changes = chosen.entries.filter { (i, v) -> v != loaded.initial[i] }.sortedBy { it.key }.map { (i, v) -> fields[i] to v }
                if (changes.isEmpty()) onDismiss() else onSave(changes)
            }) { Text(I18n.get(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(I18n.get(R.string.action_cancel)) } },
    )

    picking?.let { i ->
        val f = fields[i]
        val current = chosen[i] ?: loaded.initial[i]
        when (f.type) {
            EmulatorConfig.Type.NUMBER -> NumberDialog(f, current, language, onDismiss = { picking = null }) { value ->
                chosen[i] = value
                picking = null
            }
            else -> {
                val options = if (f.type == EmulatorConfig.Type.BOOL) {
                    listOf(f.on to I18n.get(R.string.emulator_config_on), f.off to I18n.get(R.string.emulator_config_off))
                } else {
                    f.options.map { it.value to it.label.get(language) }
                }
                AlertDialog(
                    onDismissRequest = { picking = null },
                    title = { Text(f.label.get(language)) },
                    text = {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            options.forEach { (value, label) ->
                                TextButton(onClick = {
                                    chosen[i] = value
                                    picking = null
                                }, modifier = Modifier.fillMaxWidth().focusHighlight(MaterialTheme.shapes.small)) {
                                    Text(
                                        (if (value == current) "✓ " else "") + label,
                                        modifier = Modifier.fillMaxWidth(),
                                        color = if (value == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { picking = null }) { Text(I18n.get(R.string.action_cancel)) } },
                )
            }
        }
    }
}

@Composable
private fun NumberDialog(field: EmulatorConfig.Field, current: String?, language: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var text by remember { mutableStateOf(current.orEmpty()) }
    val number = text.trim().toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(field.label.get(language)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(5) },
                singleLine = true,
                label = { Text("${field.min} – ${field.max}${field.unit?.let { " $it" }.orEmpty()}") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            TextButton(enabled = number != null, onClick = { number?.let { onPick(it.coerceIn(field.min, field.max).toString()) } }) {
                Text(I18n.get(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(I18n.get(R.string.action_cancel)) } },
    )
}
