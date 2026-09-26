package com.romcloud.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.romcloud.app.data.DownloadState
import com.romcloud.app.ui.theme.DownloadedGreen
import java.util.Locale

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
fun DownloadIndicator(status: LocalStatus, modifier: Modifier = Modifier) {
    Box(modifier.size(36.dp), contentAlignment = Alignment.Center) {
        when (status) {
            LocalStatus.Downloaded -> Icon(
                Icons.Filled.CheckCircle, contentDescription = "Téléchargé",
                tint = DownloadedGreen, modifier = Modifier.size(26.dp),
            )
            LocalStatus.Remote -> Icon(
                Icons.Filled.CloudDownload, contentDescription = "Non téléchargé",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.size(26.dp),
            )
            is LocalStatus.Downloading -> {
                CircularProgressIndicator(
                    progress = { status.state.progress },
                    modifier = Modifier.size(30.dp),
                    strokeWidth = 3.dp,
                )
                Text("${(status.state.progress * 100).toInt()}", fontSize = 9.sp)
            }
            is LocalStatus.Error -> Icon(
                Icons.Filled.ErrorOutline, contentDescription = "Échec : ${status.message}",
                tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(26.dp),
            )
        }
    }
}

/** Jaquette du jeu, avec repli sur le titre si aucune image n'est disponible. */
@Composable
fun Cover(url: String?, title: String, width: Dp, height: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    val placeholder = @Composable {
        Box(
            Modifier
                .size(width, height)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (width >= 80.dp) {
                Text(
                    title, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(6.dp), maxLines = 4,
                )
            } else {
                Icon(Icons.Filled.SportsEsports, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (url == null) {
        Box(modifier) { placeholder() }
        return
    }
    SubcomposeAsyncImage(
        model = url,
        contentDescription = title,
        contentScale = ContentScale.Fit,
        modifier = modifier.size(width, height).clip(shape),
        loading = { placeholder() },
        error = { placeholder() },
    )
}

@Composable
fun Banner(text: String, modifier: Modifier = Modifier, error: Boolean = false) {
    Surface(
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
