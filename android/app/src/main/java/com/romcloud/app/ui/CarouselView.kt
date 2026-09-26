package com.romcloud.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import com.romcloud.app.data.Game

/** Une rangée du carrousel : un titre et ses jeux. */
data class CarouselRow(val title: String, val games: List<Game>)

private const val RECENT_COUNT = 20
private const val OTHER_GENRE = "Autres"

/**
 * Regroupe les jeux (déjà filtrés) en rangées façon Netflix :
 * téléchargés, ajoutés récemment, puis une rangée par genre (le plus fourni d'abord).
 */
fun carouselRows(games: List<Game>, downloaded: Set<Long>): List<CarouselRow> {
    val rows = mutableListOf<CarouselRow>()
    games.filter { it.id in downloaded }.takeIf { it.isNotEmpty() }?.let {
        rows += CarouselRow("Téléchargés", it)
    }
    // Rangée « récents » seulement si elle apporte quelque chose (dates connues, pas toute la liste).
    if (games.size > RECENT_COUNT && games.any { it.addedAt.isNotEmpty() }) {
        rows += CarouselRow("Ajoutés récemment", games.sortedByDescending { it.addedAt }.take(RECENT_COUNT))
    }
    val byGenre = games.groupBy { it.mainGenre ?: OTHER_GENRE }
    byGenre.entries
        .sortedWith(compareBy<Map.Entry<String, List<Game>>> { it.key == OTHER_GENRE }.thenByDescending { it.value.size })
        .forEach { (genre, list) ->
            // Sans aucun genre scrapé, une seule rangée « Tous les jeux ».
            val title = if (byGenre.size == 1 && genre == OTHER_GENRE) "Tous les jeux" else genre
            rows += CarouselRow(title, list)
        }
    return rows
}

/** Jeu mis en avant : de préférence un jeu téléchargé avec capture, sinon le plus récent illustré. */
fun pickFeatured(games: List<Game>, downloaded: Set<Long>): Game? =
    games.filter { it.hasScreenshot && it.id in downloaded }.maxByOrNull { it.addedAt }
        ?: games.filter { it.hasScreenshot }.maxByOrNull { it.addedAt }
        ?: games.filter { it.hasBoxart }.maxByOrNull { it.addedAt }
        ?: games.firstOrNull()

@Composable
fun GamesCarousel(
    games: List<Game>,
    downloaded: Set<Long>,
    mediaUrl: (Game, String) -> String?,
    statusOf: (Game) -> LocalStatus,
    onClick: (Game) -> Unit,
    onDetails: (Game) -> Unit,
) {
    val rows = remember(games, downloaded) { carouselRows(games, downloaded) }
    val featured = remember(games, downloaded) { pickFeatured(games, downloaded) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        featured?.let { game ->
            item(key = "hero") {
                HeroBanner(
                    game = game,
                    backgroundUrl = mediaUrl(game, "screenshot") ?: mediaUrl(game, "boxart"),
                    coverUrl = mediaUrl(game, "boxart"),
                    status = statusOf(game),
                    onPrimary = { onClick(game) },
                    onDetails = { onDetails(game) },
                )
            }
        }
        items(rows, key = { "row:" + it.title }) { row ->
            Column(Modifier.padding(top = 16.dp)) {
                Text(
                    "${row.title}  ·  ${row.games.size}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(row.games, key = { it.id }) { game ->
                        GameCard(
                            game = game,
                            coverUrl = mediaUrl(game, "boxart"),
                            status = statusOf(game),
                            onClick = { onClick(game) },
                            onDetails = { onDetails(game) },
                            modifier = Modifier.width(118.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Grande bannière en tête du carrousel. */
@Composable
private fun HeroBanner(
    game: Game,
    backgroundUrl: String?,
    coverUrl: String?,
    status: LocalStatus,
    onPrimary: () -> Unit,
    onDetails: () -> Unit,
) {
    val background = MaterialTheme.colorScheme.background
    Box(
        Modifier
            .fillMaxWidth()
            .height(300.dp),
    ) {
        if (backgroundUrl != null) {
            AsyncImage(
                model = backgroundUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Dégradé vers le fond pour la lisibilité du texte et la transition avec les rangées.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to background.copy(alpha = 0.15f),
                        0.55f to background.copy(alpha = 0.7f),
                        1f to background,
                    ),
                ),
        )
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (coverUrl != null) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = game.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .width(96.dp)
                        .aspectRatio(3f / 4f)
                        .clip(RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    game.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                val meta = listOfNotNull(game.year, game.mainGenre, formatSize(game.size)).joinToString(" · ")
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    when (status) {
                        LocalStatus.Downloaded -> Button(onClick = onPrimary) {
                            Icon(Icons.Filled.PlayArrow, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Jouer")
                        }
                        is LocalStatus.Downloading -> OutlinedButton(onClick = onPrimary) {
                            Text("${(status.state.progress * 100).toInt()} %")
                        }
                        else -> Button(onClick = onPrimary) {
                            Icon(Icons.Filled.CloudDownload, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Télécharger")
                        }
                    }
                    OutlinedButton(onClick = onDetails) {
                        Icon(Icons.Filled.Info, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Infos")
                    }
                }
            }
        }
    }
}

/**
 * Carte d'un jeu : jaquette, indicateur de téléchargement en surimpression et bouton « i »
 * vers la fiche (description, choix de l'émulateur…).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GameCard(
    game: Game,
    coverUrl: String?,
    status: LocalStatus,
    onClick: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = onDetails),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val placeholder = @Composable {
                Text(
                    game.title, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp),
                )
            }
            if (coverUrl == null) {
                placeholder()
            } else {
                SubcomposeAsyncImage(
                    model = coverUrl,
                    contentDescription = game.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    loading = { placeholder() },
                    error = { placeholder() },
                )
            }
            // Jeu non téléchargé : jaquette légèrement estompée.
            if (status !is LocalStatus.Downloaded) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))
            }
            OverlayCircle(Modifier.align(Alignment.TopEnd)) { DownloadIndicator(status) }
            OverlayCircle(Modifier.align(Alignment.TopStart)) {
                IconButton(onClick = onDetails, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Info, "Détails et émulateur", modifier = Modifier.size(22.dp))
                }
            }
            if (status is LocalStatus.Downloading) {
                LinearProgressIndicator(
                    progress = { status.state.progress },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                )
            }
        }
        Text(
            game.title,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
        )
        Text(
            listOfNotNull(game.year, formatSize(game.size)).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

@Composable
private fun OverlayCircle(modifier: Modifier = Modifier, size: Dp = 36.dp, content: @Composable () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        modifier = modifier.padding(6.dp).size(size),
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}
