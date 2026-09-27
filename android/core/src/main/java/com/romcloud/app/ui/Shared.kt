package com.romcloud.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.romcloud.app.data.DownloadState
import java.util.Locale

// Éléments communs aux applications téléphone et TV.

val DownloadedGreen = Color(0xFF2EB872)

fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 o"
    val units = listOf("o", "Ko", "Mo", "Go", "To")
    var value = bytes.toDouble()
    var i = 0
    while (value >= 1024 && i < units.lastIndex) {
        value /= 1024
        i++
    }
    return if (i == 0) "$bytes o" else String.format(Locale.FRANCE, "%.1f %s", value, units[i])
}

/** Statut local d'un jeu, affiché à droite de chaque ligne de la liste. */
sealed interface LocalStatus {
    data object Remote : LocalStatus
    data object Downloaded : LocalStatus
    data class Downloading(val state: DownloadState.Running) : LocalStatus
    data class Error(val message: String) : LocalStatus
}

@Composable
fun DownloadIndicator(status: LocalStatus, modifier: Modifier = Modifier, size: Dp = 36.dp) {
    val icon = size * 0.72f
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        when (status) {
            LocalStatus.Downloaded -> Icon(
                Icons.Filled.CheckCircle, contentDescription = "Téléchargé",
                tint = DownloadedGreen, modifier = Modifier.size(icon),
            )
            LocalStatus.Remote -> Icon(
                Icons.Filled.CloudDownload, contentDescription = "Non téléchargé",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.size(icon),
            )
            is LocalStatus.Downloading -> {
                CircularProgressIndicator(
                    progress = { status.state.progress },
                    modifier = Modifier.size(size * 0.83f),
                    strokeWidth = 3.dp,
                )
                Text("${(status.state.progress * 100).toInt()}", fontSize = 9.sp)
            }
            is LocalStatus.Error -> Icon(
                Icons.Filled.ErrorOutline, contentDescription = "Échec : ${status.message}",
                tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(icon),
            )
        }
    }
}
