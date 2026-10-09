package com.romcloud.tv

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.romcloud.app.RomCloudApp
import com.romcloud.app.data.DownloadState
import com.romcloud.app.data.Game
import com.romcloud.app.data.GameSystem
import com.romcloud.app.ui.LocalStatus
import com.romcloud.app.ui.NetplayDialog
import com.romcloud.app.ui.OfflineBadge
import com.romcloud.app.ui.SystemBadge
import com.romcloud.app.ui.SystemsViewModel
import com.romcloud.app.ui.accountState
import com.romcloud.app.ui.pluralString
import com.romcloud.app.ui.quitApp
import com.romcloud.core.R

@Composable
fun TvSystemsScreen(
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
    val usage by viewModel.usage.collectAsStateWithLifecycle()
    val peers by viewModel.peers.collectAsStateWithLifecycle()
    var showNetplay by remember { mutableStateOf(false) }
    var searchDialog by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.refreshUsage() }
    }
    // Au retour d'un système, le focus revient sur la carte d'où l'on venait.
    var lastFocused by rememberSaveable { mutableIntStateOf(0) }
    val restoreFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    val gridState = rememberLazyGridState()

    LaunchedEffect(state.systems.size, search.active) {
        if (state.systems.isEmpty() || search.active) return@LaunchedEffect
        val index = lastFocused.coerceIn(0, state.systems.lastIndex)
        gridState.scrollToItem(index)
        runCatching { restoreFocus.requestFocus() }
    }

    // Retour : efface d'abord la recherche affichée, puis demande confirmation avant de quitter.
    val activity = LocalContext.current as Activity
    var askQuit by remember { mutableStateOf(false) }
    BackHandler(enabled = !searchDialog) {
        if (search.active) viewModel.setQuery("", debounce = false) else askQuit = true
    }
    if (askQuit) {
        ConfirmDialog(
            title = stringResource(R.string.quit_app_title),
            text = stringResource(R.string.quit_app_text),
            confirm = stringResource(R.string.action_quit),
            onConfirm = { quitApp(activity) },
            onDismiss = { askQuit = false },
        )
    }

    if (showNetplay) {
        NetplayDialog(activity.application as RomCloudApp, peers, onMessage = { Toast.makeText(activity, it, Toast.LENGTH_LONG).show() }, onDismiss = { showNetplay = false })
    }

    if (searchDialog) {
        SearchDialog(
            initial = search.query,
            onSearch = {
                viewModel.setQuery(it, debounce = false)
                searchDialog = false
            },
            onDismiss = { searchDialog = false },
        )
    }

    Column(Modifier.fillMaxSize().padding(TvSafePadding), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TvDiskBar(usage)
            OfflineBadge(Modifier.padding(start = 16.dp), size = 40.dp, explainOnClick = false)
            Spacer(Modifier.weight(1f))
            if (search.active) {
                // Recherche en cours : modifier ou effacer
                Button(onClick = { searchDialog = true }) {
                    Icon(Icons.Filled.Search, null, modifier = IconSize)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.search_query, search.query))
                }
                Spacer(Modifier.width(SmallGap))
                Button(onClick = { viewModel.setQuery("") }) {
                    Icon(Icons.Filled.Close, null, modifier = IconSize)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.clear_search))
                }
            } else {
                Button(onClick = { searchDialog = true }) {
                    Icon(Icons.Filled.Search, null, modifier = IconSize)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_search))
                }
            }
            Spacer(Modifier.width(SmallGap))
            // Appareils RomCloud du réseau local : parties proposées.
            if (peers.isNotEmpty()) {
                Button(onClick = { showNetplay = true }) {
                    Icon(Icons.Filled.Groups, null, modifier = IconSize)
                    Spacer(Modifier.width(8.dp))
                    val hosted = peers.count { it.hosting != null }
                    Text(if (hosted > 0) stringResource(R.string.netplay_button_games, hosted) else stringResource(R.string.netplay_title))
                }
                Spacer(Modifier.width(SmallGap))
            }
            Button(onClick = viewModel::refresh) {
                Icon(Icons.Filled.Refresh, null, modifier = IconSize)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_refresh))
            }
            Spacer(Modifier.width(SmallGap))
            // Profil du joueur, à côté des paramètres : nom du profil une fois connecté.
            val account = accountState()
            Button(onClick = onOpenProfile) {
                Icon(
                    Icons.Filled.AccountCircle, null, modifier = IconSize,
                    tint = if (account.signedIn) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                )
                Spacer(Modifier.width(8.dp))
                Text(if (account.signedIn) account.username else stringResource(R.string.profile_button))
            }
            Spacer(Modifier.width(SmallGap))
            Button(onClick = onOpenSettings, modifier = Modifier.focusRequester(settingsFocus)) {
                Icon(Icons.Filled.Settings, null, modifier = IconSize)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_settings))
            }
        }
        if (storageWarning) {
            TvBanner(stringResource(R.string.banner_storage), error = true)
        }
        if (state.offline) {
            TvBanner(stringResource(R.string.banner_offline_full))
        }
        when {
            search.active -> TvSearchResults(viewModel, search, imageUrl, mediaUrl, onOpenGame)
            state.systems.isEmpty() && state.loading -> Message(stringResource(R.string.loading))
            state.systems.isEmpty() && state.error != null -> {
                Message(stringResource(R.string.server_unreachable_tv, state.error.orEmpty()))
                LaunchedEffect(Unit) { runCatching { settingsFocus.requestFocus() } }
            }
            state.systems.isEmpty() -> Message(stringResource(R.string.no_systems))
            else -> LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(250.dp),
                contentPadding = PaddingValues(vertical = 12.dp, horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(state.systems, key = { _, s -> s.id }) { index, system ->
                    TvSystemCard(
                        system = system,
                        imageUrl = imageUrl(system),
                        localSize = usage.systems[system.id] ?: 0,
                        onClick = { onOpenSystem(system.id) },
                        modifier = Modifier
                            .then(if (index == lastFocused) Modifier.focusRequester(restoreFocus) else Modifier)
                            .onFocusChanged { if (it.isFocused) lastFocused = index },
                    )
                }
            }
        }
    }
}

@Composable
fun Message(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Résultats de la recherche globale : cartes de jeux avec le logo de leur console. */
@Composable
private fun TvSearchResults(
    viewModel: SystemsViewModel,
    search: SystemsViewModel.SearchState,
    imageUrl: (GameSystem) -> String?,
    mediaUrl: (Game, String) -> String?,
    onOpenGame: (systemId: String, gameId: Long) -> Unit,
) {
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    // Au retour d'une fiche de jeu, le focus revient sur le résultat choisi.
    var lastFocused by rememberSaveable { mutableIntStateOf(0) }
    val restoreFocus = remember { FocusRequester() }
    val gridState = rememberLazyGridState()
    LaunchedEffect(search.results) {
        if (search.results.isEmpty()) return@LaunchedEffect
        withFrameNanos { }
        runCatching { restoreFocus.requestFocus() }
    }

    fun statusOf(game: Game): LocalStatus = when (val d = downloads[game.id]) {
        is DownloadState.Running -> LocalStatus.Downloading(d)
        is DownloadState.Failed -> LocalStatus.Error(d.message)
        null -> if (game.id in search.downloaded) LocalStatus.Downloaded else LocalStatus.Remote
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (search.offline) TvBanner(stringResource(R.string.search_offline))
        when {
            search.results.isEmpty() && search.loading -> Message(stringResource(R.string.loading))
            search.results.isEmpty() && search.error != null -> Message(stringResource(R.string.error_with, search.error.orEmpty()))
            search.results.isEmpty() -> Message(stringResource(R.string.search_no_results, search.query))
            else -> {
                Text(
                    pluralString(R.plurals.search_results, search.results.size, search.results.size, search.query),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(PosterWidth),
                    contentPadding = PaddingValues(vertical = 12.dp, horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(search.results, key = { _, g -> g.id }) { index, game ->
                        val system = viewModel.system(game.systemId)
                        TvGameCard(
                            game = game,
                            coverUrl = mediaUrl(game, "boxart"),
                            status = statusOf(game),
                            onClick = { onOpenGame(game.systemId, game.id) },
                            onLongClick = { onOpenGame(game.systemId, game.id) },
                            onFocused = { lastFocused = index },
                            modifier = if (index == lastFocused.coerceAtMost(search.results.lastIndex)) {
                                Modifier.focusRequester(restoreFocus)
                            } else Modifier,
                            badge = { SystemBadge(system, system?.let(imageUrl), height = 26.dp) },
                        )
                    }
                }
            }
        }
    }
}
