package com.romcloud.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import com.romcloud.app.data.Game
import com.romcloud.app.netplay.Together
import com.romcloud.app.data.GameSystem
import com.romcloud.app.data.StorageUsage
import com.romcloud.app.ui.DownloadIndicator
import com.romcloud.app.ui.LocalStatus
import com.romcloud.app.ui.TogetherBadge
import com.romcloud.app.ui.formatSize
import com.romcloud.app.ui.pluralString
import com.romcloud.core.R

private val CardShape = RoundedCornerShape(10.dp)

/** Contour et agrandissement bien visibles à distance quand la carte a le focus. */
@Composable
private fun focusBorder() = CardDefaults.border(
    focusedBorder = Border(BorderStroke(3.dp, MaterialTheme.colorScheme.border), shape = CardShape),
)

@Composable
private fun focusScale() = CardDefaults.scale(focusedScale = 1.08f)

/** Carte d'un jeu : jaquette 3:4, indicateur de téléchargement, titre dessous. */
@Composable
fun TvGameCard(
    game: Game,
    coverUrl: String?,
    status: LocalStatus,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    /** Élément en bas à droite de la jaquette (ex. logo de la console dans les résultats de recherche). */
    badge: (@Composable () -> Unit)? = null,
    /** Jeu jouable à plusieurs avec un appareil du réseau local : icône à côté du titre. */
    together: Together? = null,
) {
    Column(modifier) {
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            shape = CardDefaults.shape(CardShape),
            border = focusBorder(),
            scale = focusScale(),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .onFocusChanged { if (it.isFocused) onFocused() },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                CoverImage(coverUrl, game.title)
                if (status !is LocalStatus.Downloaded) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)))
                }
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
                ) {
                    DownloadIndicator(status, size = 34.dp)
                }
                if (badge != null) {
                    Box(Modifier.align(Alignment.BottomEnd).padding(8.dp)) { badge() }
                }
                if (status is LocalStatus.Downloading) {
                    LinearProgressIndicator(
                        progress = { status.state.progress },
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                    )
                }
            }
        }
        Row(Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp)) {
            Text(
                game.title,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            together?.let { TogetherBadge(it, Modifier.padding(start = 6.dp, top = 1.dp), size = 20.dp) }
        }
    }
}

@Composable
private fun BoxScope.CoverImage(url: String?, title: String) {
    val placeholder = @Composable {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.Center).padding(10.dp),
        )
    }
    if (url == null) {
        placeholder()
        return
    }
    SubcomposeAsyncImage(
        model = url,
        contentDescription = title,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
        loading = { placeholder() },
        error = { placeholder() },
    )
}

/** Carte d'un système : image 16:9 (configurée sur le serveur) ou nom court en grand. */
@Composable
fun TvSystemCard(system: GameSystem, imageUrl: String?, onClick: () -> Unit, modifier: Modifier = Modifier, localSize: Long = 0) {
    Card(
        onClick = onClick,
        shape = CardDefaults.shape(CardShape),
        border = focusBorder(),
        scale = focusScale(),
        modifier = modifier,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = system.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                )
            } else {
                Text(
                    system.shortname.uppercase(),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(system.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                pluralString(R.plurals.games_count, system.gameCount, system.gameCount) + " · " + formatSize(system.totalSize),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (localSize > 0) {
                Text(
                    stringResource(R.string.storage_on_device, formatSize(localSize)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** Espace de la partition des ROMs, à côté du nom de l'application : barre et espace libre (rouge sous 10 %). */
@Composable
fun TvDiskBar(usage: StorageUsage, modifier: Modifier = Modifier) {
    if (!usage.known) return
    val color = if (usage.low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LinearProgressIndicator(
            progress = { usage.usedFraction },
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            drawStopIndicator = {},
            modifier = Modifier.width(160.dp).height(10.dp),
        )
        Text(
            stringResource(R.string.storage_free_of, formatSize(usage.free), formatSize(usage.total)),
            style = MaterialTheme.typography.titleSmall,
            color = if (usage.low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Bandeau d'information (hors ligne, autorisation manquante…). */
@Composable
fun TvBanner(text: String, modifier: Modifier = Modifier, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/**
 * Image plein écran en arrière-plan, assombrie vers la gauche et le bas pour la lisibilité.
 * [alignment] choisit la partie gardée au recadrage (le haut quand le bas est caché par les rangées).
 */
@Composable
fun Backdrop(url: String?, alignment: Alignment = Alignment.Center) {
    if (url == null) return
    val bg = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxSize()) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = alignment,
            alpha = 0.55f,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.horizontalGradient(0f to bg, 0.45f to bg.copy(alpha = 0.75f), 1f to Color.Transparent)),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.35f to Color.Transparent, 0.75f to bg.copy(alpha = 0.9f), 1f to bg)),
        )
    }
}

val PosterWidth = 150.dp
val SmallGap = 12.dp
val IconSize = Modifier.size(20.dp)
