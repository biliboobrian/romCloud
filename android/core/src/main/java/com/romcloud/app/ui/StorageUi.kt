package com.romcloud.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.romcloud.app.data.StorageDetails
import com.romcloud.app.data.StorageKind
import com.romcloud.app.data.SystemStorage
import com.romcloud.core.R

private val OtherColor = Color(0xFF5B6170)

/**
 * Contenu de la page du stockage : barre de la partition (données de RomCloud par catégorie, autres
 * fichiers, espace libre), une barre multicolore par système avec sa corbeille, légende en bas.
 * [onMessage] : texte à afficher après une suppression.
 */
@Composable
fun StorageContent(viewModel: StorageViewModel, state: StorageViewModel.UiState, onMessage: (String) -> Unit, modifier: Modifier = Modifier) {
    val details = state.details
    var clearing by remember { mutableStateOf<SystemStorage?>(null) }
    val context = LocalContext.current
    Column(modifier.widthIn(max = 900.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (state.loading) {
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            return@Column
        }
        if (details.known) {
            val romcloud = StorageKind.entries.sumOf { details.sum(it) }
            val other = (details.total - details.free - romcloud).coerceAtLeast(0)
            StorageBar(
                StorageKind.entries.map { details.sum(it) to Color(it.color) } + (other to OtherColor),
                scale = details.total,
                height = 20.dp,
            )
            Text(
                stringResource(R.string.storage_summary, formatSize(romcloud), formatSize(details.free), formatSize(details.total)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (details.systems.isEmpty()) {
            Text(stringResource(R.string.storage_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val max = details.systems.maxOfOrNull { it.total } ?: 0
        for (row in details.systems) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.width(140.dp)) {
                    Text(row.system.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(formatSize(row.total), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StorageBar(StorageKind.entries.map { row.size(it) to Color(it.color) }, scale = max, height = 12.dp, modifier = Modifier.weight(1f))
                IconButton(onClick = { clearing = row }, enabled = !state.busy, modifier = Modifier.focusHighlight(CircleShape)) {
                    Icon(Icons.Filled.Delete, stringResource(R.string.storage_clear), tint = MaterialTheme.colorScheme.error)
                }
            }
        }
        HorizontalDivider()
        StorageLegend(details)
    }
    clearing?.let { row ->
        ClearSystemDialog(row, onDismiss = { clearing = null }) { kinds ->
            clearing = null
            viewModel.clear(row, kinds) { freed, uninstall ->
                onMessage(context.getString(R.string.storage_cleared, formatSize(freed), row.system.name))
                // Android demande la confirmation de chaque désinstallation.
                for (pkg in uninstall) {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
        }
    }
}

/** Barre multicolore : segments ([taille, couleur]) rapportés à [scale] octets, sur le fond de la piste. */
@Composable
private fun StorageBar(segments: List<Pair<Long, Color>>, scale: Long, height: Dp, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (scale <= 0) return@Row
        for ((size, color) in segments) {
            if (size <= 0) continue
            val fraction = (size.toDouble() / scale).toFloat().coerceIn(0.002f, 1f)
            Box(Modifier.fillMaxHeight().weight(fraction).background(color))
        }
        val rest = 1f - segments.sumOf { it.first.coerceAtLeast(0) }.toDouble().div(scale).toFloat()
        if (rest > 0.0005f) Spacer(Modifier.weight(rest))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StorageLegend(details: StorageDetails) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (kind in StorageKind.entries) LegendItem(Color(kind.color), stringResource(kind.label), formatSize(details.sum(kind)))
        if (details.known) {
            LegendItem(OtherColor, stringResource(R.string.storage_other), null)
            LegendItem(MaterialTheme.colorScheme.surfaceVariant, stringResource(R.string.storage_free_label), formatSize(details.free))
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String, size: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Text(label, style = MaterialTheme.typography.bodySmall)
        if (size != null) Text(size, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Choix des catégories à effacer (sauvegardes décochées par défaut). */
@Composable
private fun ClearSystemDialog(row: SystemStorage, onDismiss: () -> Unit, onConfirm: (Set<StorageKind>) -> Unit) {
    val kinds = StorageKind.entries.filter { row.size(it) > 0 }
    var chosen by remember { mutableStateOf(kinds.filter { it != StorageKind.SAVES }.toSet()) }
    val pm = LocalContext.current.packageManager
    val names = { packages: List<String> ->
        packages.joinToString { pkg -> runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Delete, null) },
        title = { Text(stringResource(R.string.storage_clear_title, row.system.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.storage_clear_text))
                for (kind in kinds) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = kind in chosen, onCheckedChange = { on -> chosen = if (on) chosen + kind else chosen - kind })
                        Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(Color(kind.color)))
                        Text("  ${stringResource(kind.label)}", Modifier.weight(1f))
                        Text(formatSize(row.size(kind)), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (StorageKind.EMULATORS in chosen && row.apps.isNotEmpty()) {
                    Text(stringResource(R.string.storage_clear_apps, names(row.apps)), style = MaterialTheme.typography.bodySmall)
                }
                if (StorageKind.EMULATORS in chosen && row.sharedApps.isNotEmpty()) {
                    Text(stringResource(R.string.storage_clear_shared_apps, names(row.sharedApps)), style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    stringResource(R.string.storage_clear_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(chosen) }, enabled = chosen.isNotEmpty()) {
                Text(stringResource(R.string.storage_clear_button), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
