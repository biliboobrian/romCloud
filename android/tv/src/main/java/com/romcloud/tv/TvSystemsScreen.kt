package com.romcloud.tv

import com.romcloud.app.data.MediaUrl
import com.romcloud.app.data.MediaSize
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed as rowItemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridItemSpan
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
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.romcloud.app.RomCloudApp
import com.romcloud.app.data.DownloadState
import com.romcloud.app.data.Game
import com.romcloud.app.data.GameSystem
import com.romcloud.app.ui.LocalStatus
import com.romcloud.app.ui.OfflineBadge
import com.romcloud.app.ui.SystemBadge
import com.romcloud.app.ui.SystemsViewModel
import com.romcloud.app.ui.accountState
import com.romcloud.app.ui.fitsLobby
import com.romcloud.app.ui.pluralString
import com.romcloud.app.ui.quitApp
import com.romcloud.core.R

@Composable
fun TvSystemsScreen(
    viewModel: SystemsViewModel,
    storageWarning: Boolean,
    imageUrl: (GameSystem) -> String?,
    mediaUrl: MediaUrl,
    onOpenSystem: (String) -> Unit,
    onOpenGame: (systemId: String, gameId: Long) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenLobby: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val usage by viewModel.usage.collectAsStateWithLifecycle()
    val peers by viewModel.peers.collectAsStateWithLifecycle()
    var showNetplay by remember { mutableStateOf(false) }
    var searchDialog by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.refreshUsage()
            viewModel.refreshLocalGames()
        }
    }
    val app = (LocalContext.current as Activity).application as RomCloudApp
    val lobbyState by app.lobbies.state.collectAsStateWithLifecycle()
    // Jeux favoris puis téléchargés, tous systèmes confondus : rangées en tête de la grille (lobby :
    // jeux à assez de joueurs ; hors ligne : favoris téléchargés seulement).
    val localGames by viewModel.localGames.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val local = localGames.filter { viewModel.system(it.systemId) != null && fitsLobby(it, lobbyState) }
    val localIds = localGames.mapTo(HashSet()) { it.id }
    val favorite = favorites.filter { viewModel.system(it.systemId) != null && fitsLobby(it, lobbyState) && (!state.offline || it.id in localIds) }
    // Au retour d'un système (ou d'un jeu d'une rangée), le focus revient sur la carte d'où l'on venait.
    var lastFocused by rememberSaveable { mutableIntStateOf(0) }
    var lastGame by rememberSaveable { mutableLongStateOf(-1L) }
    /** Rangée du dernier jeu choisi (un jeu peut être dans les deux) : favoris, sinon téléchargés. */
    var lastGameFavorite by rememberSaveable { mutableStateOf(false) }
    val restoreFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    val gridState = rememberLazyGridState()
    val rowState = rememberLazyListState()
    val favoriteRowState = rememberLazyListState()
    val headerRows = (if (favorite.isNotEmpty()) 1 else 0) + (if (local.isNotEmpty()) 1 else 0)
    val gameRestored = (if (lastGameFavorite) favorite else local).any { it.id == lastGame }

    LaunchedEffect(state.systems.size, search.active, local.isNotEmpty(), favorite.isNotEmpty()) {
        if (state.systems.isEmpty() || search.active) return@LaunchedEffect
        val gameIndex = (if (lastGameFavorite) favorite else local).indexOfFirst { it.id == lastGame }
        if (gameIndex >= 0) {
            gridState.scrollToItem(if (lastGameFavorite || favorite.isEmpty()) 0 else 1)
            (if (lastGameFavorite) favoriteRowState else rowState).scrollToItem(gameIndex)
        } else {
            val index = lastFocused.coerceIn(0, state.systems.lastIndex)
            gridState.scrollToItem(index + headerRows)
        }
        withFrameNanos { }
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

    val account by app.account.state.collectAsStateWithLifecycle()
    if (showNetplay) {
        com.romcloud.app.ui.MultiplayerDialog(
            app,
            onOpenLobby = onOpenLobby,
            onMessage = { Toast.makeText(activity, it, Toast.LENGTH_LONG).show() },
            onDismiss = { showNetplay = false },
        )
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

    Column(Modifier.fillMaxSize().padding(TvSafePadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // En-tête compact : stockage et boutons petits, pour laisser la place aux systèmes.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(HeaderGap)) {
            TvDiskBar(usage)
            OfflineBadge(size = 24.dp)
            Spacer(Modifier.weight(1f))
            if (search.active) {
                // Recherche en cours : modifier ou effacer
                HeaderButton(Icons.Filled.Search, stringResource(R.string.search_query, search.query)) { searchDialog = true }
                HeaderButton(Icons.Filled.Close, stringResource(R.string.clear_search)) { viewModel.setQuery("") }
            } else {
                HeaderButton(Icons.Filled.Search, stringResource(R.string.action_search)) { searchDialog = true }
            }
            // Multijoueurs (profil connecté) : joueurs et lobbies.
            if (account.signedIn) {
                val open = lobbyState.lobbies.size
                HeaderButton(
                    Icons.Filled.Groups,
                    if (open > 0) "${stringResource(R.string.netplay_title)} ($open)" else stringResource(R.string.netplay_title),
                ) { showNetplay = true }
            }
            HeaderButton(Icons.Filled.Refresh, stringResource(R.string.action_refresh), onClick = viewModel::refresh)
            // Profil du joueur, à côté des paramètres : nom du profil une fois connecté.
            val account = accountState()
            HeaderButton(
                Icons.Filled.AccountCircle,
                if (account.signedIn) account.username else stringResource(R.string.profile_button),
                tint = if (account.signedIn) MaterialTheme.colorScheme.primary else null,
                onClick = onOpenProfile,
            )
            HeaderButton(
                Icons.Filled.Settings, stringResource(R.string.action_settings),
                modifier = Modifier.focusRequester(settingsFocus), onClick = onOpenSettings,
            )
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
                if (favorite.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "favorite-games") {
                        TvGamesRow(
                            viewModel, stringResource(R.string.row_favorites), favorite, { it.id in localIds }, favoriteRowState, imageUrl, mediaUrl,
                            restoreGame = lastGame.takeIf { gameRestored && lastGameFavorite },
                            restoreFocus = restoreFocus,
                            onFocused = { lastGame = it.id; lastGameFavorite = true },
                            onOpenGame = onOpenGame,
                        )
                    }
                }
                if (local.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "local-games") {
                        TvGamesRow(
                            viewModel, stringResource(R.string.row_downloaded), local, { true }, rowState, imageUrl, mediaUrl,
                            restoreGame = lastGame.takeIf { gameRestored && !lastGameFavorite },
                            restoreFocus = restoreFocus,
                            onFocused = { lastGame = it.id; lastGameFavorite = false },
                            onOpenGame = onOpenGame,
                        )
                    }
                }
                // Lobby : seulement les systèmes que tous ses membres savent jouer en réseau.
                itemsIndexed(state.systems.filter { lobbyState.allowsSystem(it.id) }, key = { _, s -> s.id }) { index, system ->
                    TvSystemCard(
                        system = system,
                        imageUrl = imageUrl(system),
                        localSize = usage.systems[system.id] ?: 0,
                        onClick = { onOpenSystem(system.id) },
                        modifier = Modifier
                            .then(if (index == lastFocused && !gameRestored) Modifier.focusRequester(restoreFocus) else Modifier)
                            .onFocusChanged {
                                if (it.isFocused) {
                                    lastFocused = index
                                    lastGame = -1L
                                }
                            },
                    )
                }
            }
        }
    }
}

private val HeaderGap = 8.dp

/** Bouton de l'en-tête des systèmes : petit (icône et texte réduits, hauteur 30 dp). */
@Composable
private fun HeaderButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    TvButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        scale = ButtonDefaults.scale(focusedScale = 1.05f),
        modifier = modifier.height(30.dp),
    ) {
        Icon(icon, null, modifier = Modifier.size(16.dp), tint = tint ?: LocalContentColor.current)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
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

/**
 * Rangée de jeux en tête des systèmes (favoris, téléchargés) : cartes comme la recherche, validation
 * -> fiche du jeu ; [downloaded] : jeu présent sur l'appareil.
 */
@Composable
private fun TvGamesRow(
    viewModel: SystemsViewModel,
    title: String,
    games: List<Game>,
    downloaded: (Game) -> Boolean,
    rowState: LazyListState,
    imageUrl: (GameSystem) -> String?,
    mediaUrl: MediaUrl,
    restoreGame: Long?,
    restoreFocus: FocusRequester,
    onFocused: (Game) -> Unit,
    onOpenGame: (systemId: String, gameId: Long) -> Unit,
) {
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    Column {
        Text(
            stringResource(R.string.row_title, title, games.size),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            rowItemsIndexed(games, key = { _, g -> g.id }) { _, game ->
                val system = viewModel.system(game.systemId)
                TvGameCard(
                    game = game,
                    coverUrl = mediaUrl(game, "boxart", MediaSize.THUMB),
                    status = when (val d = downloads[game.id]) {
                        is DownloadState.Running -> LocalStatus.Downloading(d)
                        is DownloadState.Failed -> LocalStatus.Error(d.message)
                        null -> if (downloaded(game)) LocalStatus.Downloaded else LocalStatus.Remote
                    },
                    onClick = { onOpenGame(game.systemId, game.id) },
                    onLongClick = { onOpenGame(game.systemId, game.id) },
                    onFocused = { onFocused(game) },
                    modifier = Modifier
                        .width(PosterWidth)
                        .then(if (game.id == restoreGame) Modifier.focusRequester(restoreFocus) else Modifier),
                    badge = { SystemBadge(system, system?.let(imageUrl), height = 26.dp) },
                )
            }
        }
    }
}

/** Résultats de la recherche globale : cartes de jeux avec le logo de leur console. */
@Composable
private fun TvSearchResults(
    viewModel: SystemsViewModel,
    search: SystemsViewModel.SearchState,
    imageUrl: (GameSystem) -> String?,
    mediaUrl: MediaUrl,
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
                            coverUrl = mediaUrl(game, "boxart", MediaSize.THUMB),
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
