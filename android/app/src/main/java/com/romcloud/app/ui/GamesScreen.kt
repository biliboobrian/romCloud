package com.romcloud.app.ui

import android.app.Activity
import android.content.res.Configuration
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.romcloud.app.data.DownloadState
import com.romcloud.app.data.Game
import com.romcloud.core.R
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamesScreen(
    viewModel: GamesViewModel,
    mediaUrl: (Game, String) -> String?,
    snackbar: SnackbarHostState,
    autoLaunch: MutableSet<Long>,
    onBack: () -> Unit,
    onOpenGame: (Long) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val activity = LocalContext.current as Activity
    val scope = rememberCoroutineScope()
    var searching by rememberSaveable { mutableStateOf(false) }
    var askDownload by remember { mutableStateOf<Game?>(null) }
    var askCancel by remember { mutableStateOf<Game?>(null) }
    // Paysage : les filtres passent dans la barre du haut pour libérer une ligne.
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val filters = @Composable {
        GameFilter.entries.forEach { f ->
            FilterChip(
                selected = state.filter == f,
                onClick = { viewModel.setFilter(f) },
                label = { Text(stringResource(f.label)) },
            )
        }
    }

    // Au retour dans l'application (ex. après une partie), re-vérifie les fichiers locaux.
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
            LocalStatus.Downloaded -> viewModel.play(activity, game)?.let { scope.launch { snackbar.showSnackbar(it) } }
            is LocalStatus.Downloading -> askCancel = game
            else -> askDownload = game
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
                },
                title = {
                    if (searching) {
                        TextField(
                            value = state.query,
                            onValueChange = viewModel::setQuery,
                            placeholder = { Text(stringResource(R.string.search_hint)) },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Column {
                            Text(state.system?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                pluralString(R.plurals.games_count, state.games.size, state.games.size) + " · " +
                                    stringResource(R.string.downloaded_count, state.downloaded.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    if (landscape) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(end = 8.dp)) {
                            filters()
                        }
                    }
                    IconButton(onClick = viewModel::toggleView) {
                        if (state.grid) Icon(Icons.AutoMirrored.Filled.ViewList, stringResource(R.string.view_list))
                        else Icon(Icons.Filled.ViewCarousel, stringResource(R.string.view_carousel))
                    }
                    IconButton(onClick = {
                        if (searching) viewModel.setQuery("")
                        searching = !searching
                    }) {
                        Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, stringResource(R.string.action_search))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.offline) Banner(stringResource(R.string.banner_offline))
            if (!landscape) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    filters()
                }
            }
            PullToRefreshBox(
                isRefreshing = state.loading && state.games.isNotEmpty(),
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                val games = state.visibleGames
                when {
                    state.games.isEmpty() && state.loading -> Centered(stringResource(R.string.loading))
                    state.games.isEmpty() && state.error != null ->
                        Centered(stringResource(R.string.error_with, state.error.orEmpty()), stringResource(R.string.action_retry), viewModel::refresh)
                    games.isEmpty() -> Centered(stringResource(R.string.no_games))
                    // Carrousel façon Netflix ; pendant une recherche, résultats en grille.
                    state.grid && state.query.isBlank() -> GamesCarousel(
                        games = games,
                        downloaded = state.downloaded,
                        mediaUrl = mediaUrl,
                        statusOf = ::statusOf,
                        onClick = ::onClick,
                        onDetails = { onOpenGame(it.id) },
                    )
                    state.grid -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(116.dp),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(games, key = { it.id }) { game ->
                            GameCard(
                                game = game,
                                coverUrl = mediaUrl(game, "boxart"),
                                status = statusOf(game),
                                onClick = { onClick(game) },
                                onDetails = { onOpenGame(game.id) },
                            )
                        }
                    }
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(games, key = { it.id }) { game ->
                            GameRow(
                                game = game,
                                coverUrl = mediaUrl(game, "boxart"),
                                status = statusOf(game),
                                onClick = { onClick(game) },
                                onDetails = { onOpenGame(game.id) },
                            )
                            HorizontalDivider(Modifier.padding(start = 84.dp), thickness = 0.5.dp)
                        }
                    }
                }
            }
        }
    }

    askDownload?.let { game ->
        var launchAfter by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { askDownload = null },
            title = { Text(stringResource(R.string.download_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.download_text, game.title))
                    Spacer(Modifier.padding(4.dp))
                    Text("${game.fileName} · ${formatSize(game.size)}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    (statusOf(game) as? LocalStatus.Error)?.let {
                        Text(stringResource(R.string.download_last_error, it.message), color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = launchAfter, onCheckedChange = { launchAfter = it })
                        Text(stringResource(R.string.download_launch_after))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (launchAfter) autoLaunch += game.id else autoLaunch -= game.id
                    viewModel.download(game)
                    askDownload = null
                }) { Text(stringResource(R.string.action_download)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { askDownload = null; onOpenGame(game.id) }) { Text(stringResource(R.string.action_details)) }
                    TextButton(onClick = { askDownload = null }) { Text(stringResource(R.string.action_cancel)) }
                }
            },
        )
    }

    askCancel?.let { game ->
        AlertDialog(
            onDismissRequest = { askCancel = null },
            title = { Text(stringResource(R.string.downloading_title)) },
            text = { Text(stringResource(R.string.downloading_text, game.title)) },
            confirmButton = {
                TextButton(onClick = { viewModel.cancel(game); autoLaunch -= game.id; askCancel = null }) {
                    Text(stringResource(R.string.cancel_download))
                }
            },
            dismissButton = { TextButton(onClick = { askCancel = null }) { Text(stringResource(R.string.action_continue)) } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GameRow(
    game: Game,
    coverUrl: String?,
    status: LocalStatus,
    onClick: () -> Unit,
    onDetails: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onDetails)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(coverUrl, game.title, 52.dp, 68.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(game.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(game.year, game.genre, formatSize(game.size)).joinToString(" · ")
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (status is LocalStatus.Downloading) {
                Text(
                    stringResource(R.string.bytes_progress, formatSize(status.state.bytes), formatSize(status.state.total)),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        DownloadIndicator(status)
        IconButton(onClick = onDetails) {
            Icon(Icons.Filled.Info, stringResource(R.string.action_details), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        }
    }
}
