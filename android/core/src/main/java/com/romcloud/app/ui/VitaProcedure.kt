package com.romcloud.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import com.romcloud.core.R

/** Jeu PS Vita lancé avec Vita3K : identifiant (« PCSB00061 »), format du fichier, licence zRIF saisie (.pkg). */
data class VitaInfo(val titleId: String?, val format: String, val license: String)

/**
 * Procédure Vita3K selon le jeu : le jeu s'installe d'abord dans Vita3K (son menu, avec la licence
 * pour un .pkg), puis « Jouer » le démarre directement par son identifiant.
 */
@Composable
fun VitaProcedure(info: VitaInfo, filePath: String?, onSaveLicense: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.vita_title), style = MaterialTheme.typography.titleMedium)
            val pkg = info.format == "pkg"
            val archive = info.format == "vpk" || info.format == "zip"
            val steps = buildList {
                add(stringResource(R.string.vita_step_firmware))
                when {
                    pkg -> add(stringResource(R.string.vita_step_install_pkg))
                    archive -> add(stringResource(R.string.vita_step_install_archive))
                    else -> add(stringResource(R.string.vita_step_format, info.format))
                }
                add(
                    info.titleId?.let { stringResource(R.string.vita_step_play, it) }
                        ?: stringResource(R.string.vita_step_no_title_id),
                )
            }
            steps.forEachIndexed { i, step -> Text("${i + 1}. $step", style = MaterialTheme.typography.bodyMedium) }
            filePath?.let {
                Text(stringResource(R.string.vita_file, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (pkg) {
                var zrif by remember(info.license) { mutableStateOf(info.license) }
                OutlinedTextField(
                    value = zrif,
                    onValueChange = { zrif = it },
                    label = { Text(stringResource(R.string.vita_license)) },
                    placeholder = { Text("KO5ifR1dQ+eHBl…") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onSaveLicense(zrif) }, enabled = zrif != info.license) { Text(stringResource(R.string.vita_license_save)) }
                    TextButton(onClick = { copy(context, zrif) }, enabled = zrif.isNotBlank()) { Text(stringResource(R.string.vita_license_copy)) }
                }
                Text(stringResource(R.string.vita_license_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Licence copiée, à coller dans Vita3K au moment d'installer le .pkg. */
private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("zRIF", text))
    Toast.makeText(context, R.string.vita_license_copied, Toast.LENGTH_SHORT).show()
}
