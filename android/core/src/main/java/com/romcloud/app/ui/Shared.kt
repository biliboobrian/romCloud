package com.romcloud.app.ui

import androidx.annotation.PluralsRes
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.romcloud.app.I18n
import com.romcloud.app.RomCloudApp
import com.romcloud.app.data.DownloadState
import com.romcloud.app.data.GameSystem
import com.romcloud.core.R
import java.util.Locale

// Éléments communs aux applications téléphone et TV.

val DownloadedGreen = Color(0xFF2EB872)
val OfflineAmber = Color(0xFFF0B429)

/**
 * Pastille « hors ligne » de la barre du haut, affichée tant que le serveur est injoignable : seuls
 * les jeux téléchargés sont proposés, les sauvegardes partent au retour de la connexion.
 * [explainOnClick] : un appui affiche l'explication (téléphone ; pas sur TV, hors du parcours au D-pad).
 */
@Composable
fun OfflineBadge(modifier: Modifier = Modifier, size: Dp = 32.dp, explainOnClick: Boolean = true) {
    val context = LocalContext.current
    val app = context.applicationContext as RomCloudApp
    val online by app.connectivity.online.collectAsStateWithLifecycle()
    if (online) return
    val description = stringResource(R.string.offline_badge)
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(OfflineAmber.copy(alpha = 0.16f))
            .then(if (explainOnClick) Modifier.clickable { Toast.makeText(context, description, Toast.LENGTH_LONG).show() } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.CloudOff, contentDescription = description, tint = OfflineAmber, modifier = Modifier.size(size * 0.6f))
    }
}

/**
 * Logo de la console d'un jeu, en surimpression sur sa carte (résultats de recherche) :
 * image du système configurée sur le serveur, sinon son nom court.
 */
@Composable
fun SystemBadge(system: GameSystem?, imageUrl: String?, modifier: Modifier = Modifier, height: Dp = 22.dp) {
    if (system == null) return
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = system.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.height(height).widthIn(max = height * 3),
            )
        } else {
            Text(
                system.shortname.uppercase(),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (height.value * 0.55f).sp,
                maxLines = 1,
            )
        }
    }
}

/** Pluriel traduit (ex. « 1 game » / « 3 games ») dans la langue de l'écran. */
@Composable
fun pluralString(@PluralsRes id: Int, count: Int, vararg args: Any): String =
    LocalContext.current.resources.getQuantityString(id, count, *args)

fun formatSize(bytes: Long): String {
    val units = I18n.get(R.string.size_units).split('|')
    if (bytes <= 0) return "0 ${units[0]}"
    var value = bytes.toDouble()
    var i = 0
    while (value >= 1024 && i < units.lastIndex) {
        value /= 1024
        i++
    }
    return if (i == 0) "$bytes ${units[0]}" else String.format(Locale.getDefault(), "%.1f %s", value, units[i])
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
                Icons.Filled.CheckCircle, contentDescription = stringResource(R.string.cd_downloaded),
                tint = DownloadedGreen, modifier = Modifier.size(icon),
            )
            LocalStatus.Remote -> Icon(
                Icons.Filled.CloudDownload, contentDescription = stringResource(R.string.cd_not_downloaded),
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
                Icons.Filled.ErrorOutline, contentDescription = stringResource(R.string.cd_failed, status.message),
                tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(icon),
            )
        }
    }
}
