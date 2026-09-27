@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.romcloud.tv

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.romcloud.app.data.DownloadState
import com.romcloud.app.data.Game
import com.romcloud.app.ui.CarouselRow
import com.romcloud.app.ui.DownloadedGreen
import com.romcloud.app.ui.GameFilter
import com.romcloud.app.ui.GamesViewModel
import com.romcloud.app.ui.LocalStatus
import com.romcloud.app.ui.RowLabels
import com.romcloud.app.ui.carouselRows
import com.romcloud.app.ui.formatSize
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
    val activity = LocalContext.current as Activity

    var focusedGameId by rememberSaveable { mutableStateOf<Long?>(null) }
    var focusedRow by rememberSaveable { mutableStateOf<String?>(null) }
    var askDownload by remember { mutableStateOf<Game?>(null) }
    var askCancel by remember { mutableStateOf<Game?>(null) }
    var searching by remember { mutableStateOf(false) }

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
            LocalStatus.Downloaded -> viewModel.play(activity, game)?.let(onMessage)
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
        Backdrop(featured?.let { mediaUrl(it, "screenshot") ?: mediaUrl(it, "boxart") })

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
                    modifier = Modifier.weight(1f),
                )
                GameFilter.entries.forEach { f ->
                    FilterChip(selected = state.filter == f, onClick = { viewModel.setFilter(f) }) { Text(stringResource(f.label)) }
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
                status = featured?.let(::statusOf),
                modifier = Modifier.padding(start = 48.dp, end = 48.dp, top = 8.dp).height(190.dp),
            )

            when {
                state.games.isEmpty() && state.loading -> Message(stringResource(R.string.loading))
                state.games.isEmpty() && state.error != null -> Message(stringResource(R.string.error_with, state.error.orEmpty()))
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
                )
            }
        }
    }

    askDownload?.let { game ->
        DownloadDialog(
            game = game,
            error = (statusOf(game) as? LocalStatus.Error)?.message,
            onDownload = { launchAfter ->
                if (launchAfter) autoLaunch += game.id else autoLaunch -= game.id
                viewModel.download(game)
                askDownload = null
            },
            onDetails = {
                askDownload = null
                onOpenGame(game.id)
            },
            onDismiss = { askDownload = null },
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

/** Zone du haut : informations du jeu sélectionné. */
@Composable
private fun GameHero(game: Game?, status: LocalStatus?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(0.6f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (game == null) return@Column
        Text(
            game.title,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            listOfNotNull(game.year, game.genre, game.players?.let { stringResource(R.string.players_count, it) }, formatSize(game.size)).joinToString("  ·  "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (status) {
            LocalStatus.Downloaded -> Text(stringResource(R.string.tv_ready), color = DownloadedGreen, style = MaterialTheme.typography.labelLarge)
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
            Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
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

    LazyColumn(
        state = columnState,
        contentPadding = PaddingValues(bottom = 48.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(rows, key = { _, row -> row.title }) { _, row ->
            Column(Modifier.padding(top = 14.dp)) {
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
                            onClick = { onClick(game) },
                            onLongClick = { onDetails(game) },
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
