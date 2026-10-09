@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.romcloud.tv

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.romcloud.app.data.DownloadState
import com.romcloud.app.data.Game
import com.romcloud.app.launch.RetroArchInfo
import com.romcloud.app.ui.CarouselRow
import com.romcloud.app.ui.DownloadedGreen
import com.romcloud.app.ui.GameCriteria
import com.romcloud.app.ui.GameFilter
import com.romcloud.app.ui.GamesViewModel
import com.romcloud.app.ui.LocalStatus
import com.romcloud.app.ui.MultiplayerBadge
import com.romcloud.app.ui.OfflineBadge
import com.romcloud.app.ui.PlaytimeLabel
import com.romcloud.app.ui.RetroArchHelpDialog
import com.romcloud.app.ui.RowLabels
import com.romcloud.app.ui.carouselRows
import com.romcloud.app.ui.criteriaSections
import com.romcloud.app.ui.formatSize
import com.romcloud.app.ui.gamePlaytime
import com.romcloud.app.ui.partsSummary
import com.romcloud.app.ui.pickFeatured
import com.romcloud.core.R

@Composable
fun TvGamesScreen(
    viewModel: GamesViewModel,
    mediaUrl: (Game, String) -> String?,
    autoLaunch: MutableSet<Long>,
    onMessage: (String) -> Unit,
    onOpenGame: (Long) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val peers by viewModel.peers.collectAsStateWithLifecycle()
    val activity = LocalContext.current as Activity

    var focusedGameId by rememberSaveable { mutableStateOf<Long?>(null) }
    var focusedRow by rememberSaveable { mutableStateOf<String?>(null) }
    var askDownload by remember { mutableStateOf<Game?>(null) }
    var askCancel by remember { mutableStateOf<Game?>(null) }
    var askBios by remember { mutableStateOf<Game?>(null) }
    // Guide RetroArch ouvert après confirmation d'un téléchargement (jeu, guide, lancer ensuite ?)
    var postDownloadHelp by remember { mutableStateOf<Triple<Game, RetroArchInfo, Boolean>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var showCriteria by remember { mutableStateOf(false) }

    // Au retour d'une partie, re-vérifie les fichiers présents.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.refreshLocal() }
    }

    fun statusOf(game: Game): LocalStatus = when (val d = downloads[game.id]) {
        is DownloadState.Running -> LocalStatus.Downloading(d)
        is DownloadState.Failed -> LocalStatus.Error(d.message)
        null -> if (game.id in state.downloaded) LocalStatus.Downloaded else LocalStatus.Remote
    }

    fun onClick(game: Game) {
        when (statusOf(game)) {
            LocalStatus.Downloaded ->
                if (state.missingBios.isNotEmpty()) askBios = game else viewModel.play(activity, game)?.let(onMessage)
            is LocalStatus.Downloading -> askCancel = game
            else -> askDownload = game
        }
    }

    val games = state.visibleGames
    val labels = RowLabels(
        downloaded = stringResource(R.string.row_downloaded),
        recent = stringResource(R.string.row_recent),
        other = stringResource(R.string.row_other),
        all = stringResource(R.string.row_all),
    )
    val resultsTitle = stringResource(R.string.row_results, state.query)
    val rows = remember(games, state.downloaded, state.query, labels) {
        if (state.query.isBlank()) carouselRows(games, state.downloaded, labels)
        else listOf(CarouselRow(resultsTitle, games))
    }
    val featured = games.find { it.id == focusedGameId } ?: remember(games, state.downloaded) {
        pickFeatured(games, state.downloaded)
    }

    Box(Modifier.fillMaxSize()) {
        Backdrop(featured?.let { mediaUrl(it, "screenshot") ?: mediaUrl(it, "boxart") }, alignment = Alignment.TopCenter)

        Column(Modifier.fillMaxSize()) {
            // Barre du haut : nom du système à gauche, filtres et actions à droite.
            Row(
                Modifier.padding(start = 48.dp, end = 48.dp, top = 27.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SmallGap),
            ) {
                Text(
                    state.system?.name.orEmpty(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                OfflineBadge(size = 40.dp, explainOnClick = false)
                Spacer(Modifier.weight(1f))
                // Hors ligne : seuls les jeux de l'appareil sont listés, les filtres n'ont pas lieu d'être.
                if (!state.offline) GameFilter.entries.forEach { f ->
                    FilterChip(selected = state.filter == f, onClick = { viewModel.setFilter(f) }) { Text(stringResource(f.label)) }
                }
                if (!state.facets.isEmpty) {
                    val count = state.criteria.count
                    FilterChip(
                        selected = count > 0,
                        onClick = { showCriteria = true },
                        leadingIcon = { Icon(Icons.Filled.FilterList, null, modifier = IconSize) },
                    ) {
                        Text(if (count > 0) stringResource(R.string.criteria_count, count) else stringResource(R.string.criteria_title))
                    }
                }
                FilterChip(
                    selected = state.query.isNotBlank(),
                    onClick = { searching = true },
                    leadingIcon = { Icon(Icons.Filled.Search, null, modifier = IconSize) },
                ) {
                    Text(
                        if (state.query.isBlank()) stringResource(R.string.action_search)
                        else stringResource(R.string.search_query, state.query),
                    )
                }
                FilterChip(
                    selected = false,
                    onClick = viewModel::refresh,
                    leadingIcon = { Icon(Icons.Filled.Refresh, null, modifier = IconSize) },
                ) { Text(stringResource(R.string.action_refresh)) }
            }

            GameHero(
                game = featured,
                coverUrl = featured?.let { mediaUrl(it, "boxart") },
                status = featured?.let(::statusOf),
                resumable = featured?.let { it.id in state.resumable } == true,
                together = featured != null && peers.isNotEmpty() && viewModel.canPlayTogether(featured),
                modifier = Modifier.padding(start = 48.dp, end = 48.dp).height(150.dp),
            )

            when {
                state.games.isEmpty() && state.loading -> Message(stringResource(R.string.loading))
                state.games.isEmpty() && state.error != null -> Message(stringResource(R.string.error_with, state.error.orEmpty()))
                games.isEmpty() && state.criteria.count > 0 -> Message(stringResource(R.string.criteria_none))
                games.isEmpty() -> Message(stringResource(R.string.no_games))
                else -> GameRows(
                    rows = rows,
                    mediaUrl = mediaUrl,
                    statusOf = ::statusOf,
                    focusedRow = focusedRow,
                    focusedGameId = focusedGameId,
                    onFocused = { row, game ->
                        focusedRow = row
                        focusedGameId = game.id
                    },
                    onClick = ::onClick,
                    onDetails = { onOpenGame(it.id) },
                    // Appui long sur un jeu avec une partie sauvegardée : reprise directe.
                    onResume = { game ->
                        if (game.id in state.resumable && statusOf(game) == LocalStatus.Downloaded) {
                            viewModel.play(activity, game, resume = true)?.let(onMessage)
                            true
                        } else {
                            false
                        }
                    },
                )
            }
        }
    }

    askDownload?.let { game ->
        DownloadDialog(
            game = game,
            error = (statusOf(game) as? LocalStatus.Error)?.message,
            missingBios = state.missingBios,
            onDownload = { launchAfter, withBios ->
                val help = viewModel.retroArchHelpFor(game)
                // Avec le guide ouvert, le lancement attend sa fermeture (voir plus bas).
                if (launchAfter && help == null) autoLaunch += game.id else autoLaunch -= game.id
                viewModel.download(game, withBios)
                askDownload = null
                if (help != null) postDownloadHelp = Triple(game, help, launchAfter)
            },
            onDetails = {
                askDownload = null
                onOpenGame(game.id)
            },
            onDismiss = { askDownload = null },
        )
    }
    postDownloadHelp?.let { (game, info, launchAfter) ->
        RetroArchHelpDialog(
            info = info,
            onDismiss = {
                postDownloadHelp = null
                if (launchAfter) {
                    // Déjà téléchargé : on lance ; sinon lancement automatique à la fin du téléchargement.
                    if (statusOf(game) == LocalStatus.Downloaded) viewModel.play(activity, game)?.let(onMessage)
                    else autoLaunch += game.id
                }
            },
            onMessage = onMessage,
            onDontShowAgainChange = { viewModel.setRetroArchHelpDismissed(info.packageName, it) },
        )
    }

    askBios?.let { game ->
        MissingBiosDialog(
            missingBios = state.missingBios,
            onDownloadAndPlay = {
                askBios = null
                autoLaunch += game.id
                viewModel.downloadBios(game)
            },
            onPlay = {
                askBios = null
                viewModel.play(activity, game)?.let(onMessage)
            },
            onDismiss = { askBios = null },
        )
    }

    askCancel?.let { game ->
        CancelDownloadDialog(
            game = game,
            onConfirm = {
                viewModel.cancel(game)
                autoLaunch -= game.id
                askCancel = null
            },
            onDismiss = { askCancel = null },
        )
    }
    if (showCriteria) {
        CriteriaDialog(
            sections = criteriaSections(state.facets, state.criteria),
            criteria = state.criteria,
            onChange = viewModel::setCriteria,
            onDismiss = { showCriteria = false },
        )
    }
    if (searching) {
        SearchDialog(
            initial = state.query,
            onSearch = {
                viewModel.setQuery(it)
                searching = false
            },
            onDismiss = { searching = false },
        )
    }
}

/** Zone du haut : jaquette et informations du jeu sélectionné (description à côté de la jaquette). */
@Composable
private fun GameHero(game: Game?, coverUrl: String?, status: LocalStatus?, resumable: Boolean, together: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        if (game == null) return@Row
        if (coverUrl != null) {
            AsyncImage(
                model = coverUrl,
                contentDescription = game.title,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxHeight().aspectRatio(3f / 4f).clip(RoundedCornerShape(8.dp)),
            )
        }
        GameHeroText(game, status, resumable, together)
    }
}

@Composable
private fun GameHeroText(game: Game, status: LocalStatus?, resumable: Boolean, together: Boolean) {
    Column(Modifier.fillMaxWidth(0.7f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                game.title,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            // Jouable à plusieurs avec un appareil du réseau local.
            if (together) MultiplayerBadge(size = 32.dp)
        }
        Text(
            listOfNotNull(game.year, game.genre, game.players?.let { stringResource(R.string.players_count, it) }, partsSummary(game), formatSize(game.fullSize)).joinToString("  ·  "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PlaytimeLabel(gamePlaytime(game.id), color = MaterialTheme.colorScheme.primary)
        when (status) {
            LocalStatus.Downloaded -> Text(
                stringResource(if (resumable) R.string.tv_resume_hint else R.string.tv_ready),
                color = DownloadedGreen,
                style = MaterialTheme.typography.labelLarge,
            )
            is LocalStatus.Downloading -> Text(
                stringResource(R.string.tv_downloading_percent, (status.state.progress * 100).toInt()),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
            )
            is LocalStatus.Error -> Text(stringResource(R.string.download_failed, status.message), color = MaterialTheme.colorScheme.error)
            else -> Text(
                stringResource(R.string.tv_to_download),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        game.description?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Rangées horizontales ; le focus est restauré sur la dernière carte au retour d'une fiche. */
@Composable
private fun GameRows(
    rows: List<CarouselRow>,
    mediaUrl: (Game, String) -> String?,
    statusOf: (Game) -> LocalStatus,
    focusedRow: String?,
    focusedGameId: Long?,
    onFocused: (String, Game) -> Unit,
    onClick: (Game) -> Unit,
    onDetails: (Game) -> Unit,
    /** Appui long : reprend la partie sauvegardée ; faux si le jeu n'en a pas (fiche du jeu à la place). */
    onResume: (Game) -> Boolean,
) {
    // Positions de défilement (colonne et rangées) sauvegardées par Compose avec l'écran :
    // au retour, on redonne seulement le focus à la carte qui l'avait.
    val restore = remember { FocusRequester() }
    val target = rows.find { it.title == focusedRow && it.games.any { g -> g.id == focusedGameId } }
        ?.let { it.title to focusedGameId }
        ?: rows.firstOrNull()?.let { it.title to it.games.firstOrNull()?.id }
    val columnState = rememberLazyListState()
    var restored by remember { mutableStateOf(false) }

    LaunchedEffect(rows) {
        if (restored || rows.isEmpty()) return@LaunchedEffect
        withFrameNanos { } // attend que les cartes visibles soient composées
        runCatching { restore.requestFocus() }
        restored = true
    }

    // Rangée ajoutée en tête (premier jeu téléchargé du système) : liste remontée tant que
    // l'utilisateur n'a pas fait défiler (Compose la garde sinon sur l'ancienne première rangée).
    LaunchedEffect(rows.firstOrNull()?.title) {
        if (columnState.firstVisibleItemIndex <= 1) columnState.scrollToItem(0)
    }
    LazyColumn(
        state = columnState,
        contentPadding = PaddingValues(bottom = 48.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(rows, key = { _, row -> row.title }) { _, row ->
            Column(Modifier.padding(top = 8.dp)) {
                Text(
                    stringResource(R.string.row_title, row.title, row.games.size),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 48.dp, vertical = 6.dp),
                )
                val rowState = rememberLazyListState()
                LazyRow(
                    state = rowState,
                    contentPadding = PaddingValues(horizontal = 48.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    itemsIndexed(row.games, key = { _, g -> g.id }) { _, game ->
                        val isTarget = row.title == target?.first && game.id == target.second
                        TvGameCard(
                            game = game,
                            coverUrl = mediaUrl(game, "boxart"),
                            status = statusOf(game),
                            // Validation : fiche du jeu (jouer / télécharger depuis la fiche).
                            onClick = { onDetails(game) },
                            onLongClick = { if (!onResume(game)) onDetails(game) },
                            onFocused = { onFocused(row.title, game) },
                            modifier = Modifier
                                .width(PosterWidth)
                                .then(if (isTarget) Modifier.focusRequester(restore) else Modifier),
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
