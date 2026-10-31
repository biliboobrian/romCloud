package com.romcloud.app.ui

import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import com.romcloud.app.data.MediaUrl
import com.romcloud.app.data.MediaSize
import android.app.Activity
import android.content.res.Configuration
import android.widget.Toast
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import com.romcloud.app.RomCloudApp
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
    mediaUrl: MediaUrl,
    onOpenSystem: (String) -> Unit,
    onOpenGame: (systemId: String, gameId: Long) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenLobby: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    // Champ ouvert si une recherche est en cours (ex. au retour de la fiche d'un jeu).
    var searching by rememberSaveable { mutableStateOf(search.active) }
    val searchFocus = remember { FocusRequester() }

    // Appareils RomCloud du réseau local : icône « jeu à plusieurs » et liste des parties proposées.
    val peers by viewModel.peers.collectAsStateWithLifecycle()
    var showNetplay by remember { mutableStateOf(false) }

    // Retour : ferme d'abord la recherche, puis demande confirmation avant de quitter l'application.
    val activity = LocalContext.current as Activity
    var askQuit by remember { mutableStateOf(false) }
    val app = activity.application as RomCloudApp
    val account by app.account.state.collectAsStateWithLifecycle()
    val lobbyState by app.lobbies.state.collectAsStateWithLifecycle()
    if (showNetplay) {
        MultiplayerDialog(
            app,
            onOpenLobby = onOpenLobby,
            onMessage = { Toast.makeText(activity, it, Toast.LENGTH_LONG).show() },
            onDismiss = { showNetplay = false },
        )
    }
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
            viewModel.refreshLocalGames()
        }
    }
    val usage by viewModel.usage.collectAsStateWithLifecycle()
    val localGames by viewModel.localGames.collectAsStateWithLifecycle()

    // Boutons de la barre du haut, rangés différemment en portrait (deux lignes) et en paysage.
    val netplayButton: @Composable () -> Unit = {
        if (account.signedIn && !searching) {
            IconButton(onClick = { showNetplay = true }) {
                val open = lobbyState.lobbies.size
                BadgedBox(badge = { if (open > 0) Badge { Text("$open") } }) {
                    Icon(Icons.Filled.Groups, stringResource(R.string.netplay_title), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
    val searchButton: @Composable () -> Unit = {
        IconButton(onClick = {
            if (searching) viewModel.setQuery("")
            searching = !searching
        }) {
            Icon(
                if (searching) Icons.Filled.Close else Icons.Filled.Search,
                stringResource(if (searching) R.string.clear_search else R.string.search_all_title),
            )
        }
    }
    val refreshButton: @Composable () -> Unit = {
        IconButton(onClick = viewModel::refresh) { Icon(Icons.Filled.Refresh, stringResource(R.string.action_refresh)) }
    }
    // Profil du joueur (coloré une fois connecté), à côté des paramètres.
    val profileButton: @Composable () -> Unit = {
        IconButton(onClick = onOpenProfile) {
            Icon(
                Icons.Filled.AccountCircle,
                stringResource(R.string.profile_button),
                tint = if (isSignedIn()) MaterialTheme.colorScheme.primary else LocalContentColor.current,
            )
        }
    }
    val settingsButton: @Composable () -> Unit = {
        IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, stringResource(R.string.action_settings)) }
    }
    val portrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT

    // Fond en dégradé violet (barre du haut transparente en paysage).
    Scaffold(
        modifier = Modifier.background(SystemsGradient),
        containerColor = Color.Transparent,
        // Textes posés sur le dégradé (jeux téléchargés, résultats de recherche) : en blanc, sinon noirs.
        contentColor = Color.White,
        topBar = {
            if (portrait && !searching) {
                // Portrait : barre de l'espace, jeu à plusieurs, profil et paramètres ; puis espace
                // restant, recherche, actualisation et diffusion ; fond gris foncé.
                Surface(color = HeaderGray, contentColor = Color.White) {
                    Column(Modifier.fillMaxWidth().windowInsetsPadding(topBarInsets).padding(start = 16.dp, end = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { DiskTrack(usage, Modifier.fillMaxWidth(0.6f)) }
                            netplayButton()
                            profileButton()
                            settingsButton()
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { DiskFree(usage, Color.White.copy(alpha = 0.7f)) }
                            OfflineBadge(Modifier.padding(horizontal = 4.dp))
                            searchButton()
                            refreshButton()
                            CastButton()
                        }
                    }
                }
            } else {
                TopAppBar(
                    windowInsets = topBarInsets,
                    colors = if (portrait) TopAppBarDefaults.topAppBarColors(
                        containerColor = HeaderGray,
                        titleContentColor = Color.White,
                        actionIconContentColor = Color.White,
                    ) else TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
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
                            DiskBar(usage)
                        }
                    },
                    actions = {
                        OfflineBadge(Modifier.padding(horizontal = 4.dp))
                        netplayButton()
                        searchButton()
                        if (!searching) {
                            refreshButton()
                            CastButton()
                            profileButton()
                            settingsButton()
                        }
                    },
                )
            }
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
                // Fin du défilement : la rangée de cartes la plus proche alignée en haut, visible en entier.
                val gridState = rememberLazyGridState()
                when {
                    state.systems.isEmpty() && state.loading -> Centered(stringResource(R.string.loading))
                    state.systems.isEmpty() && state.error != null -> Centered(
                        stringResource(R.string.server_unreachable, state.error.orEmpty()),
                        action = stringResource(R.string.action_settings), onAction = onOpenSettings,
                    )
                    state.systems.isEmpty() -> Centered(stringResource(R.string.no_systems))
                    else -> LazyVerticalGrid(
                        state = gridState,
                        flingBehavior = rememberSnapFlingBehavior(gridState, SnapPosition.Start),
                        columns = GridCells.Adaptive(160.dp),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        // Jeux téléchargés, tous systèmes confondus (lobby : jeux à assez de joueurs).
                        val local = localGames.filter { viewModel.system(it.systemId) != null && fitsLobby(it, lobbyState) }
                        if (local.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "local-games") {
                                LocalGamesRow(viewModel, local, imageUrl, mediaUrl, onOpenGame)
                            }
                        }
                        // Lobby : seulement les systèmes que tous ses membres savent jouer en réseau.
                        items(state.systems.filter { lobbyState.allowsSystem(it.id) }, key = { it.id }) { system ->
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
    // Carte sur la couleur du système (réglages du serveur), textes adaptés à cette couleur.
    SystemBackground(system.backgroundColor) {
        Card(
            Modifier.fillMaxWidth().clickable(onClick = onClick),
            colors = CardDefaults.cardColors(containerColor = system.backgroundColor ?: MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            // Toutes les cartes de même taille : zone de l'image toujours là (nom court sans image).
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    // Fond de l'image un peu plus clair que la carte, comme sous Windows.
                    .background(Color.White.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center,
            ) {
                if (imageUrl != null) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = system.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                    )
                } else {
                    Text(
                        system.shortname.uppercase(),
                        maxLines = 1,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Column(Modifier.padding(16.dp)) {
                // Nombre de lignes fixé pour chaque texte : cartes de même hauteur.
                Text(
                    system.shortname.uppercase(), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    system.name, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    pluralString(R.plurals.games_count, system.gameCount, system.gameCount) + " · " + formatSize(system.totalSize),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Ligne toujours réservée (vide sans jeu sur l'appareil) : cartes de même hauteur.
                Text(
                    if (localSize > 0) stringResource(R.string.storage_on_device, formatSize(localSize)) else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Espace de la partition des ROMs : barre de la place occupée et espace libre (rouge sous 10 %). */
@Composable
private fun DiskBar(usage: StorageUsage) {
    if (!usage.known) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DiskTrack(usage, Modifier.width(72.dp))
        DiskFree(usage)
    }
}

/** Barre de l'espace occupé (rouge s'il en reste peu). */
@Composable
private fun DiskTrack(usage: StorageUsage, modifier: Modifier = Modifier) {
    if (!usage.known) return
    LinearProgressIndicator(
        progress = { usage.usedFraction },
        color = if (usage.low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
        drawStopIndicator = {},
        modifier = modifier.height(6.dp),
    )
}

/** Espace restant (rouge s'il en reste peu). */
@Composable
private fun DiskFree(usage: StorageUsage, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    if (!usage.known) return
    Text(
        stringResource(R.string.storage_free, formatSize(usage.free)),
        style = MaterialTheme.typography.labelSmall,
        color = if (usage.low) MaterialTheme.colorScheme.error else color,
    )
}

/** Fond de la barre du haut en portrait. */
private val HeaderGray = Color(0xFF2B2B2E)

/** Résultats de la recherche globale : cartes de jeux avec le logo de leur console. */
@Composable
private fun SearchResults(
    viewModel: SystemsViewModel,
    search: SystemsViewModel.SearchState,
    imageUrl: (GameSystem) -> String?,
    mediaUrl: MediaUrl,
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

    val gridState = rememberLazyGridState()
    when {
        search.results.isEmpty() && search.loading -> Centered(stringResource(R.string.loading))
        search.results.isEmpty() && search.error != null ->
            Centered(stringResource(R.string.error_with, search.error.orEmpty()), stringResource(R.string.action_retry)) {
                viewModel.setQuery(search.query, debounce = false)
            }
        search.results.isEmpty() -> Centered(stringResource(R.string.search_no_results, search.query))
        else -> LazyVerticalGrid(
            state = gridState,
            flingBehavior = rememberSnapFlingBehavior(gridState, SnapPosition.Start),
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
                    coverUrl = mediaUrl(game, "boxart", MediaSize.THUMB),
                    status = statusOf(game),
                    onClick = { onOpenGame(game.systemId, game.id) },
                    onDetails = { onOpenGame(game.systemId, game.id) },
                    badge = { SystemBadge(system, system?.let(imageUrl)) },
                )
            }
        }
    }
}

/** Rangée des jeux téléchargés, en tête des systèmes : cartes comme la recherche, appui -> fiche du jeu. */
@Composable
private fun LocalGamesRow(
    viewModel: SystemsViewModel,
    games: List<Game>,
    imageUrl: (GameSystem) -> String?,
    mediaUrl: MediaUrl,
    onOpenGame: (systemId: String, gameId: Long) -> Unit,
) {
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    Column {
        Text(
            stringResource(R.string.row_title, stringResource(R.string.row_downloaded), games.size),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        // Rangée de bord à bord (marges de la grille compensées).
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.layoutWidth(16.dp),
        ) {
            rowItems(games, key = { it.id }) { game ->
                val system = viewModel.system(game.systemId)
                GameCard(
                    game = game,
                    coverUrl = mediaUrl(game, "boxart", MediaSize.THUMB),
                    status = when (val d = downloads[game.id]) {
                        is DownloadState.Running -> LocalStatus.Downloading(d)
                        is DownloadState.Failed -> LocalStatus.Error(d.message)
                        null -> LocalStatus.Downloaded
                    },
                    onClick = { onOpenGame(game.systemId, game.id) },
                    onDetails = { onOpenGame(game.systemId, game.id) },
                    badge = { SystemBadge(system, system?.let(imageUrl)) },
                    modifier = Modifier.width(118.dp),
                )
            }
        }
    }
}

/** Élargit l'élément de [margin] de chaque côté (rangée de bord à bord dans une grille avec marges). */
private fun Modifier.layoutWidth(margin: androidx.compose.ui.unit.Dp) = layout { measurable, constraints ->
    val extra = margin.roundToPx() * 2
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(placeable.width - extra, placeable.height) { placeable.place(-extra / 2, 0) }
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
