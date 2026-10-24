package com.romcloud.app.ui

import com.romcloud.app.data.Game

import androidx.annotation.PluralsRes
import android.widget.Toast
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.launch

// Éléments communs aux applications téléphone et TV.

val DownloadedGreen = Color(0xFF2EB872)
val OfflineAmber = Color(0xFFF0B429)

/**
 * Pastille « hors ligne » de la barre du haut, affichée en mode hors ligne : seuls les jeux
 * téléchargés sont proposés, le serveur n'est plus interrogé. Un appui (ou OK sur la télécommande)
 * sonde le serveur : joignable, l'application repasse en ligne (les sauvegardes partent alors).
 */
@Composable
fun OfflineBadge(modifier: Modifier = Modifier, size: Dp = 32.dp) {
    val context = LocalContext.current
    val app = context.applicationContext as RomCloudApp
    val online by app.connectivity.online.collectAsStateWithLifecycle()
    if (online) return
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val description = stringResource(R.string.offline_badge)
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(OfflineAmber.copy(alpha = if (focused) 0.4f else 0.16f))
            .then(if (focused) Modifier.border(2.dp, OfflineAmber, CircleShape) else Modifier)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, enabled = !checking) {
                checking = true
                scope.launch {
                    val reachable = app.connectivity.ping()
                    checking = false
                    if (!reachable) Toast.makeText(context, I18n.get(R.string.offline_still), Toast.LENGTH_SHORT).show()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (checking) {
            CircularProgressIndicator(color = OfflineAmber, strokeWidth = 2.dp, modifier = Modifier.size(size * 0.5f))
        } else {
            Icon(Icons.Filled.CloudOff, contentDescription = description, tint = OfflineAmber, modifier = Modifier.size(size * 0.6f))
        }
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

/**
 * Barre du téléchargement et, collée dessous, celle de la décompression (jeu PC compressé : archives
 * décompressées une à une pendant le téléchargement ; jeu jouable une fois cette barre pleine).
 */
@Composable
fun DownloadBars(state: DownloadState.Running, modifier: Modifier = Modifier, height: Dp = 4.dp) {
    Column(modifier) {
        LinearProgressIndicator(
            progress = { state.progress },
            modifier = Modifier.fillMaxWidth().height(height),
            drawStopIndicator = {},
            gapSize = 0.dp,
        )
        if (state.unpackTotal > 0) {
            val color = MaterialTheme.colorScheme.tertiary
            LinearProgressIndicator(
                progress = { state.unpackProgress },
                modifier = Modifier.fillMaxWidth().height(height * 0.75f),
                color = color,
                trackColor = color.copy(alpha = 0.25f),
                drawStopIndicator = {},
                gapSize = 0.dp,
            )
        }
    }
}

/** Progression écrite sous les barres : octets reçus, puis décompressés (jeu PC compressé). */
@Composable
fun downloadProgressText(state: DownloadState.Running): String = when {
    state.unpacking -> stringResource(R.string.unpacking_playable) + " " +
        stringResource(R.string.unpacking_progress, formatSize(state.unpackBytes), formatSize(state.unpackTotal))
    state.unpackTotal > 0 -> stringResource(R.string.downloading_progress, formatSize(state.bytes), formatSize(state.total)) + " · " +
        stringResource(R.string.unpacking_progress, formatSize(state.unpackBytes), formatSize(state.unpackTotal))
    else -> stringResource(R.string.downloading_progress, formatSize(state.bytes), formatSize(state.total))
}

/** Taille du jeu ; jeu PC compressé : taille installée et taille à télécharger (« 12 Go (zip : 8 Go) »). */
fun gameSizeLabel(game: Game): String =
    if (game.zipped) I18n.get(R.string.size_flat_zip, formatSize(game.fullSize), formatSize(game.packedSize ?: game.fullSize))
    else formatSize(game.fullSize)

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
