package com.romcloud.app.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import com.romcloud.app.data.DownloadState
import com.romcloud.app.data.Game
import com.romcloud.app.data.GameSystem
import com.romcloud.app.data.StorageUsage
import com.romcloud.core.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemsScreen(
    viewModel: SystemsViewModel,
    storageWarning: Boolean,
    imageUrl: (GameSystem) -> String?,
    mediaUrl: (Game, String) -> String?,
    onOpenSystem: (String) -> Unit,
    onOpenGame: (systemId: String, gameId: Long) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    // Champ ouvert si une recherche est en cours (ex. au retour de la fiche d'un jeu).
    var searching by rememberSaveable { mutableStateOf(search.active) }
    val searchFocus = remember { FocusRequester() }

    // Retour : ferme d'abord la recherche, puis demande confirmation avant de quitter l'application.
    val activity = LocalContext.current as Activity
    var askQuit by remember { mutableStateOf(false) }
    BackHandler {
        if (searching) {
            viewModel.setQuery("")
            searching = false
        } else {
            askQuit = true
        }
    }
    if (askQuit) {
        AlertDialog(
            onDismissRequest = { askQuit = false },
            title = { Text(stringResource(R.string.quit_app_title)) },
            text = { Text(stringResource(R.string.quit_app_text)) },
            confirmButton = { TextButton(onClick = { quitApp(activity) }) { Text(stringResource(R.string.action_quit)) } },
            dismissButton = { TextButton(onClick = { askQuit = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    // Au retour dans l'application (ex. après une partie), re-vérifie les jeux présents.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.refreshSearchLocal()
            viewModel.refreshUsage()
        }
    }
    val usage by viewModel.usage.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        TextField(
                            value = search.query,
                            onValueChange = viewModel::setQuery,
                            placeholder = { Text(stringResource(R.string.search_all_hint)) },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
                        )
                        LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("RomCloud")
                            DiskBar(usage)
                        }
                    }
                },
                actions = {
                    OfflineBadge(Modifier.padding(horizontal = 4.dp))
                    IconButton(onClick = {
                        if (searching) viewModel.setQuery("")
                        searching = !searching
                    }) {
                        Icon(
                            if (searching) Icons.Filled.Close else Icons.Filled.Search,
                            stringResource(if (searching) R.string.clear_search else R.string.search_all_title),
                        )
                    }
                    if (!searching) {
                        IconButton(onClick = viewModel::refresh) { Icon(Icons.Filled.Refresh, stringResource(R.string.action_refresh)) }
                        CastButton()
                        // Profil du joueur (coloré une fois connecté), à côté des paramètres.
                        IconButton(onClick = onOpenProfile) {
                            Icon(
                                Icons.Filled.AccountCircle,
                                stringResource(R.string.profile_button),
                                tint = if (isSignedIn()) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                            )
                        }
                        IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, stringResource(R.string.action_settings)) }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (storageWarning) {
                Banner(stringResource(R.string.banner_storage), error = true)
            }
            if (search.active) {
                SearchResults(viewModel, search, imageUrl, mediaUrl, onOpenGame)
                return@Column
            }
            if (state.offline) {
                Banner(stringResource(R.string.banner_offline_full))
            }
            PullToRefreshBox(
                isRefreshing = state.loading && state.systems.isNotEmpty(),
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    state.systems.isEmpty() && state.loading -> Centered(stringResource(R.string.loading))
                    state.systems.isEmpty() && state.error != null -> Centered(
                        stringResource(R.string.server_unreachable, state.error.orEmpty()),
                        action = stringResource(R.string.action_settings), onAction = onOpenSettings,
                    )
                    state.systems.isEmpty() -> Centered(stringResource(R.string.no_systems))
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(160.dp),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(state.systems, key = { it.id }) { system ->
                            SystemCard(system, imageUrl(system), usage.systems[system.id] ?: 0) { onOpenSystem(system.id) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SystemCard(system: GameSystem, imageUrl: String?, localSize: Long, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        imageUrl?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = system.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(8.dp),
            )
        }
        Column(Modifier.padding(16.dp)) {
            Text(
                system.shortname.uppercase(), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                system.name, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, minLines = 2, maxLines = 2,
            )
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

/** Espace de la partition des ROMs : barre de la place occupée et espace libre (rouge sous 10 %). */
@Composable
private fun DiskBar(usage: StorageUsage) {
    if (!usage.known) return
    val color = if (usage.low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LinearProgressIndicator(
            progress = { usage.usedFraction },
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            drawStopIndicator = {},
            modifier = Modifier.width(72.dp).height(6.dp),
        )
        Text(
            stringResource(R.string.storage_free, formatSize(usage.free)),
            style = MaterialTheme.typography.labelSmall,
            color = if (usage.low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Résultats de la recherche globale : cartes de jeux avec le logo de leur console. */
@Composable
private fun SearchResults(
    viewModel: SystemsViewModel,
    search: SystemsViewModel.SearchState,
    imageUrl: (GameSystem) -> String?,
    mediaUrl: (Game, String) -> String?,
    onOpenGame: (systemId: String, gameId: Long) -> Unit,
) {
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    if (search.offline) Banner(stringResource(R.string.search_offline))
    if (search.loading) LinearProgressIndicator(Modifier.fillMaxWidth())

    fun statusOf(game: Game): LocalStatus = when (val d = downloads[game.id]) {
        is DownloadState.Running -> LocalStatus.Downloading(d)
        is DownloadState.Failed -> LocalStatus.Error(d.message)
        null -> if (game.id in search.downloaded) LocalStatus.Downloaded else LocalStatus.Remote
    }

    when {
        search.results.isEmpty() && search.loading -> Centered(stringResource(R.string.loading))
        search.results.isEmpty() && search.error != null ->
            Centered(stringResource(R.string.error_with, search.error.orEmpty()), stringResource(R.string.action_retry)) {
                viewModel.setQuery(search.query, debounce = false)
            }
        search.results.isEmpty() -> Centered(stringResource(R.string.search_no_results, search.query))
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(116.dp),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    pluralString(R.plurals.search_results, search.results.size, search.results.size, search.query),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            items(search.results, key = { it.id }) { game ->
                val system = viewModel.system(game.systemId)
                GameCard(
                    game = game,
                    coverUrl = mediaUrl(game, "boxart"),
                    status = statusOf(game),
                    onClick = { onOpenGame(game.systemId, game.id) },
                    onDetails = { onOpenGame(game.systemId, game.id) },
                    badge = { SystemBadge(system, system?.let(imageUrl)) },
                )
            }
        }
    }
}

@Composable
fun Centered(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (action != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}
