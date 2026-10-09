package com.romcloud.app.ui

import androidx.compose.foundation.border
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import com.romcloud.app.data.Game
import com.romcloud.core.R

@Composable
fun GamesCarousel(
    games: List<Game>,
    downloaded: Set<Long>,
    mediaUrl: (Game, String) -> String?,
    statusOf: (Game) -> LocalStatus,
    onClick: (Game) -> Unit,
    onDetails: (Game) -> Unit,
    /** Partie sauvegardée dans l'émulateur intégré : « Reprendre » sur la bannière. */
    canResume: (Game) -> Boolean = { false },
    onResume: (Game) -> Unit = {},
    /** Hauteur de la barre du haut dessinée par-dessus : l'image de la bannière passe dessous. */
    topInset: Dp = 0.dp,
    /** Paysage : bannière réduite pour remonter les rangées. */
    compact: Boolean = false,
) {
    val labels = RowLabels(
        downloaded = stringResource(R.string.row_downloaded),
        recent = stringResource(R.string.row_recent),
        other = stringResource(R.string.row_other),
        all = stringResource(R.string.row_all),
    )
    val rows = remember(games, downloaded, labels) { carouselRows(games, downloaded, labels) }
    // Jeu choisi dans les rangées (appui sur sa carte), sinon le jeu mis en avant par défaut.
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    val defaultFeatured = remember(games, downloaded) { pickFeatured(games, downloaded) }
    val featured = games.find { it.id == selectedId } ?: defaultFeatured

    // Bannière fixe en haut : seules les rangées défilent dessous.
    Column(Modifier.fillMaxSize()) {
        featured?.let { game ->
            HeroBanner(
                game = game,
                backgroundUrl = mediaUrl(game, "screenshot") ?: mediaUrl(game, "boxart"),
                coverUrl = mediaUrl(game, "boxart"),
                status = statusOf(game),
                canResume = canResume(game),
                onPrimary = { onClick(game) },
                onResume = { onResume(game) },
                onDetails = { onDetails(game) },
                topInset = topInset,
                compact = compact,
            )
        }
        // Rangée ajoutée en tête (premier jeu téléchargé du système) : la liste, gardée par Compose
        // sur la rangée qui était en haut, est remontée tant que l'utilisateur n'a pas fait défiler.
        val listState = rememberLazyListState()
        LaunchedEffect(rows.firstOrNull()?.title) {
            if (listState.firstVisibleItemIndex <= 1) listState.scrollToItem(0)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
            items(rows, key = { "row:" + it.title }) { row ->
                Column(Modifier.padding(top = if (compact) 8.dp else 16.dp)) {
                    Text(
                        stringResource(R.string.row_title, row.title, row.games.size),
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
                                // Appui : jeu affiché dans la bannière (jouer / télécharger depuis la bannière) ;
                                // appui long ou bouton « i » : fiche du jeu.
                                onClick = { selectedId = game.id },
                                onDetails = { onDetails(game) },
                                selected = game.id == featured?.id,
                                modifier = Modifier.width(118.dp),
                            )
                        }
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
    canResume: Boolean,
    onPrimary: () -> Unit,
    onResume: () -> Unit,
    onDetails: () -> Unit,
    topInset: Dp,
    compact: Boolean,
) {
    val background = MaterialTheme.colorScheme.background
    // Hauteur de la bannière = celle de son contenu : la jaquette suit directement les filtres,
    // l'image de fond s'étend derrière.
    Box(Modifier.fillMaxWidth()) {
        if (backgroundUrl != null) {
            AsyncImage(
                model = backgroundUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                // Bannière basse : on garde le haut de l'image plutôt que son centre.
                alignment = if (compact) Alignment.TopCenter else Alignment.Center,
                modifier = Modifier.matchParentSize(),
            )
        }
        // Dégradé vers le fond pour la lisibilité du texte et la transition avec les rangées ;
        // assombri en haut quand la barre du haut est dessinée par-dessus l'image.
        val stops = if (topInset > 0.dp) {
            arrayOf(
                0f to background.copy(alpha = 0.6f),
                0.3f to background.copy(alpha = 0.2f),
                0.6f to background.copy(alpha = 0.7f),
                1f to background,
            )
        } else {
            arrayOf(0f to background.copy(alpha = 0.15f), 0.55f to background.copy(alpha = 0.7f), 1f to background)
        }
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(*stops)))
        Row(
            Modifier.padding(
                start = 16.dp,
                end = 16.dp,
                // Barre du haut dessinée par-dessus (paysage) : la jaquette commence juste dessous.
                top = topInset + 4.dp,
                bottom = if (compact) 10.dp else 16.dp,
            ),
            verticalAlignment = Alignment.Top,
        ) {
            if (coverUrl != null) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = game.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .width(if (compact) 72.dp else 96.dp)
                        .aspectRatio(3f / 4f)
                        .clip(RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    game.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis,
                )
                val meta = listOfNotNull(game.year, game.mainGenre, partsSummary(game), formatSize(game.fullSize)).joinToString(" · ")
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PlaytimeLabel(gamePlaytime(game.id))
                if (!compact) game.description?.let { HeroDescription(it, maxLines = 3) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Partie sauvegardée : « Reprendre » d'abord, puis « Jouer » (comme la fiche du jeu).
                    val resumable = canResume && status == LocalStatus.Downloaded
                    if (resumable) {
                        Button(onClick = onResume) {
                            Icon(Icons.Filled.PlayArrow, null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.action_resume_game))
                        }
                    }
                    when (status) {
                        LocalStatus.Downloaded -> if (resumable) {
                            OutlinedButton(onClick = onPrimary) {
                                Icon(Icons.Filled.Refresh, null)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.action_play))
                            }
                        } else {
                            Button(onClick = onPrimary) {
                                Icon(Icons.Filled.PlayArrow, null)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.action_play))
                            }
                        }
                        is LocalStatus.Downloading -> OutlinedButton(onClick = onPrimary) {
                            Text(stringResource(R.string.percent, (status.state.progress * 100).toInt()))
                        }
                        else -> Button(onClick = onPrimary) {
                            Icon(Icons.Filled.CloudDownload, null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.action_download))
                        }
                    }
                    // Trois boutons : « Infos » réduit à son icône pour tenir sur la ligne.
                    if (resumable) {
                        OutlinedIconButton(onClick = onDetails) { Icon(Icons.Filled.Info, stringResource(R.string.action_info)) }
                    } else {
                        OutlinedButton(onClick = onDetails) {
                            Icon(Icons.Filled.Info, null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.action_info))
                        }
                    }
                }
            }
            // Paysage : bannière basse mais large, la description occupe une colonne à droite.
            if (compact) {
                game.description?.let {
                    Spacer(Modifier.width(16.dp))
                    HeroDescription(it, maxLines = 5, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun HeroDescription(text: String, maxLines: Int, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
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
    /** Élément en bas à droite de la jaquette (ex. logo de la console dans les résultats de recherche). */
    badge: (@Composable () -> Unit)? = null,
    /** Jeu affiché dans la bannière du carrousel : jaquette encadrée. */
    selected: Boolean = false,
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
                .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)) else Modifier)
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
                    Icon(Icons.Filled.Info, stringResource(R.string.details_and_emulator), modifier = Modifier.size(22.dp))
                }
            }
            if (badge != null) {
                Box(Modifier.align(Alignment.BottomEnd).padding(6.dp)) { badge() }
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
            listOfNotNull(game.year, formatSize(game.fullSize)).joinToString(" · "),
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
