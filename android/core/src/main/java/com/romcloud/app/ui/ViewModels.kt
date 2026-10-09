package com.romcloud.app.ui

import android.app.Activity
import androidx.annotation.StringRes
import com.romcloud.app.I18n
import com.romcloud.core.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.romcloud.app.RomCloudApp
import com.romcloud.app.data.BiosFile
import com.romcloud.app.data.DownloadEvent
import com.romcloud.app.data.Game
import com.romcloud.app.data.GameSystem
import com.romcloud.app.data.OnlineSave
import com.romcloud.app.data.Player
import com.romcloud.app.data.StorageUsage
import com.romcloud.app.data.StreamReceiver
import com.romcloud.app.stream.StreamMode
import com.romcloud.app.data.sameSaveCore
import com.romcloud.app.launch.CloseEmulatorPrompt
import com.romcloud.app.launch.LaunchException
import com.romcloud.app.launch.MissingEmulator
import com.romcloud.app.launch.MissingEmulatorException
import com.romcloud.app.launch.RetroArchInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lance un jeu déjà téléchargé ; renvoie un message d'erreur ou null.
 * Si l'émulateur n'est pas installé, la proposition d'installation est affichée à la place ;
 * s'il doit être fermé d'abord (Android 14+), l'avertissement correspondant est affiché
 * (le lancement reprend avec [emulatorClosed] = true).
 */
fun RomCloudApp.play(
    activity: Activity,
    system: GameSystem,
    game: Game,
    emulatorClosed: Boolean = false,
    resume: Boolean = false,
    stream: StreamReceiver? = null,
    streamMode: StreamMode = StreamMode.NATIVE,
): String? {
    val player = launcher.selectedPlayer(system, game.fileName)
    val file = library.launchFile(system, game, player)
    if (!emulatorClosed) {
        launcher.closeWarningPackage(player)?.let { pkg ->
            closeEmulatorPrompt.value = CloseEmulatorPrompt(system, game, pkg, player?.name ?: pkg)
            return null
        }
    }
    return try {
        launcher.launch(activity, system, file, player, resume, game.id, stream, streamMode)
        // Émulateur externe : temps de jeu compté jusqu'au retour dans l'application (l'émulateur
        // intégré compte lui-même le temps de la partie affichée).
        if (player?.libretroCore == null) account.startExternalSession(game.id)
        null
    } catch (e: MissingEmulatorException) {
        missingEmulator.value = e.emulator
        null
    } catch (e: LaunchException) {
        account.reportError("launch:${system.id}", e.message ?: "launch", game.fileName)
        e.message
    }
}

// ---------------------------------------------------------------------------

class SystemsViewModel(private val app: RomCloudApp) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val systems: List<GameSystem> = emptyList(),
        val offline: Boolean = false,
        val error: String? = null,
    )

    /** Recherche globale (tous les systèmes). */
    data class SearchState(
        val query: String = "",
        val loading: Boolean = false,
        val results: List<Game> = emptyList(),
        val downloaded: Set<Long> = emptySet(),
        val offline: Boolean = false,
        val error: String? = null,
    ) {
        val active: Boolean get() = query.isNotBlank()
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()

    private val _usage = MutableStateFlow(StorageUsage())

    /** Place occupée par système sur l'appareil et espace de la partition des ROMs. */
    val usage: StateFlow<StorageUsage> = _usage.asStateFlow()

    val downloads = app.downloader.states
    private var searchJob: Job? = null

    init {
        refresh()
        viewModelScope.launch {
            app.downloader.events.collect {
                if (it is DownloadEvent.Completed) {
                    refreshSearchLocal()
                    refreshUsage()
                }
            }
        }
        viewModelScope.launch {
            app.connectivity.online.drop(1).collect { online ->
                if (online) {
                    refresh() // listes complètes relues sur le serveur
                } else {
                    // Sans attendre une nouvelle requête : seuls les jeux de l'appareil restent proposés.
                    val systems = withLocalGames(_state.value.systems)
                    _state.update { it.copy(systems = systems, offline = true) }
                    _search.update { s -> if (s.active) s.copy(results = s.results.filter { it.id in s.downloaded }, offline = true) else s }
                }
            }
        }
    }

    /** Hors ligne : seuls les systèmes dont des jeux sont sur l'appareil sont affichés. */
    private suspend fun withLocalGames(systems: List<GameSystem>): List<GameSystem> {
        val ids = withContext(Dispatchers.IO) { app.library.systemsWithGames(systems) }
        return systems.filter { it.id in ids }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            _state.value = try {
                val loaded = app.repository.systems()
                val systems = if (loaded.offline) withLocalGames(loaded.data) else loaded.data
                UiState(loading = false, systems = systems, offline = loaded.offline, error = loaded.error)
            } catch (e: Exception) {
                _state.value.copy(loading = false, error = e.message ?: I18n.get(R.string.err_connection))
            }
            refreshUsage()
            if (_search.value.active) runSearch(_search.value.query, debounce = false)
        }
    }

    fun system(id: String): GameSystem? = _state.value.systems.find { it.id == id }

    /** Recalcule la place occupée (affichage de l'écran, fin de téléchargement, jeu supprimé). */
    fun refreshUsage() {
        val systems = _state.value.systems
        viewModelScope.launch {
            _usage.value = withContext(Dispatchers.IO) { app.library.usage(systems) }
        }
    }

    /** Met à jour la recherche ; la requête part après une courte pause de saisie. */
    fun setQuery(query: String, debounce: Boolean = true) {
        _search.update { it.copy(query = query) }
        if (query.isBlank()) {
            searchJob?.cancel()
            _search.value = SearchState()
            return
        }
        runSearch(query, debounce)
    }

    private fun runSearch(query: String, debounce: Boolean) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (debounce) delay(350)
            _search.update { it.copy(loading = true) }
            _search.value = try {
                val loaded = app.repository.search(query.trim())
                val downloaded = downloadedAmong(loaded.data)
                SearchState(
                    query = query,
                    // Hors ligne : seuls les jeux présents sur l'appareil peuvent être lancés.
                    results = if (loaded.offline) loaded.data.filter { it.id in downloaded } else loaded.data,
                    downloaded = downloaded,
                    offline = loaded.offline,
                    error = loaded.error,
                )
            } catch (e: Exception) {
                _search.value.copy(loading = false, error = e.message ?: I18n.get(R.string.err_connection))
            }
        }
    }

    /** Recalcule les jeux présents sur l'appareil (retour dans l'app, fin de téléchargement). */
    fun refreshSearchLocal() {
        val current = _search.value
        if (!current.active) return
        viewModelScope.launch {
            val ids = downloadedAmong(current.results)
            _search.update { it.copy(downloaded = ids) }
        }
    }

    private suspend fun downloadedAmong(games: List<Game>): Set<Long> = withContext(Dispatchers.IO) {
        val systems = _state.value.systems.associateBy { it.id }
        games.filter { g -> systems[g.systemId]?.let { app.library.isDownloaded(it, g) } == true }.map { it.id }.toSet()
    }
}

// ---------------------------------------------------------------------------

enum class GameFilter(@StringRes val label: Int) {
    ALL(R.string.filter_all),
    DOWNLOADED(R.string.filter_downloaded),
    REMOTE(R.string.filter_remote),
}

class GamesViewModel(private val app: RomCloudApp, private val systemId: String) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val system: GameSystem? = null,
        val games: List<Game> = emptyList(),
        val downloaded: Set<Long> = emptySet(),
        val offline: Boolean = false,
        val error: String? = null,
        val query: String = "",
        val filter: GameFilter = GameFilter.ALL,
        /** Recherche avancée (genre, décennie, joueurs…) et valeurs proposées d'après les jeux. */
        val criteria: GameCriteria = GameCriteria(),
        val facets: GameFacets = GameFacets(),
        val grid: Boolean = false,
        /** BIOS du système sur le serveur et ceux absents de l'appareil. */
        val bios: List<BiosFile> = emptyList(),
        val missingBios: List<BiosFile> = emptyList(),
        /** Jeux téléchargés avec une partie sauvegardée dans l'émulateur intégré (« Reprendre »). */
        val resumable: Set<Long> = emptySet(),
    ) {
        val visibleGames: List<Game>
            get() = games.filter { g ->
                (query.isBlank() || g.title.contains(query, true) || g.fileName.contains(query, true)) &&
                    criteria.matches(g) &&
                    when {
                        offline -> g.id in downloaded // hors ligne : jeux de l'appareil seulement
                        filter == GameFilter.ALL -> true
                        filter == GameFilter.DOWNLOADED -> g.id in downloaded
                        else -> g.id !in downloaded
                    }
            }
    }

    private val _state = MutableStateFlow(UiState(grid = app.settings.gamesAsGrid))
    val state: StateFlow<UiState> = _state.asStateFlow()

    val downloads = app.downloader.states

    init {
        refresh()
        viewModelScope.launch {
            app.downloader.events.collect { if (it is DownloadEvent.Completed && it.system.id == systemId) refreshLocal() }
        }
        viewModelScope.launch {
            app.connectivity.online.drop(1).collect { online ->
                if (online) refresh() else _state.update { it.copy(offline = true) }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            try {
                val system = app.repository.system(systemId)
                val loaded = app.repository.games(systemId)
                val bios = system?.let { app.repository.bios(it) }.orEmpty()
                // Jeux de l'appareil connus avant d'afficher la liste : la rangée « Téléchargés »
                // est en tête dès le premier affichage (pas insérée au-dessus de la liste déjà affichée).
                val local = system?.let { localState(it, loaded.data, bios) }
                _state.update {
                    it.copy(
                        loading = false, system = system, games = loaded.data, facets = GameFacets.of(loaded.data),
                        offline = loaded.offline, error = loaded.error, bios = bios,
                        downloaded = local?.downloaded ?: it.downloaded,
                        missingBios = local?.missingBios ?: it.missingBios,
                        resumable = local?.resumable ?: it.resumable,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: I18n.get(R.string.err_connection)) }
            }
        }
    }

    /** Recalcule quels jeux sont présents sur l'appareil (après un téléchargement, au retour dans l'app…). */
    fun refreshLocal() {
        val s = _state.value
        val system = s.system ?: return
        viewModelScope.launch {
            val local = localState(system, s.games, s.bios)
            _state.update { it.copy(downloaded = local.downloaded, missingBios = local.missingBios, resumable = local.resumable) }
        }
    }

    private class LocalState(val downloaded: Set<Long>, val missingBios: List<BiosFile>, val resumable: Set<Long>)

    /** Jeux présents sur l'appareil, BIOS manquants et parties à reprendre (lecture des dossiers). */
    private suspend fun localState(system: GameSystem, games: List<Game>, bios: List<BiosFile>): LocalState =
        withContext(Dispatchers.IO) {
            val ids = app.library.downloadedIds(system, games)
            val resumable = games.filter { it.id in ids }.filter { game ->
                val player = app.launcher.selectedPlayer(system, game.fileName)
                app.launcher.canResume(app.library.launchFile(system, game, player), player)
            }.mapTo(mutableSetOf()) { it.id }
            LocalState(ids, app.library.missingBios(bios), resumable)
        }

    fun setQuery(q: String) = _state.update { it.copy(query = q) }
    fun setFilter(f: GameFilter) = _state.update { it.copy(filter = f) }
    fun setCriteria(c: GameCriteria) = _state.update { it.copy(criteria = c) }

    fun toggleView() {
        val grid = !_state.value.grid
        app.settings.gamesAsGrid = grid
        _state.update { it.copy(grid = grid) }
    }

    /** Télécharge le jeu et, si [withBios], les BIOS du système absents de l'appareil. */
    fun download(game: Game, withBios: Boolean = true) {
        val system = _state.value.system ?: return
        app.downloader.dismissError(game.id)
        app.downloader.start(system, game, if (withBios) _state.value.missingBios else emptyList())
    }

    /** Télécharge seulement les BIOS manquants du système (le jeu est déjà sur l'appareil). */
    fun downloadBios(game: Game) {
        val system = _state.value.system ?: return
        app.downloader.dismissError(game.id)
        app.downloader.start(system, game, _state.value.missingBios, includeRom = false)
    }

    fun cancel(game: Game) = app.downloader.cancel(game.id)

    /**
     * Guide RetroArch à montrer quand l'utilisateur confirme le téléchargement de ce jeu :
     * seulement si l'émulateur sélectionné est RetroArch et que le guide n'a pas été masqué.
     */
    fun retroArchHelpFor(game: Game): RetroArchInfo? {
        val system = _state.value.system ?: return null
        val player = app.launcher.selectedPlayer(system, game.fileName) ?: return null
        return app.launcher.retroArchInfo(player)?.takeUnless { app.settings.isRetroArchHelpDismissed(it.packageName) }
    }

    fun setRetroArchHelpDismissed(packageName: String, dismissed: Boolean) =
        app.settings.setRetroArchHelpDismissed(packageName, dismissed)

    /** [resume] : reprend la partie sauvegardée dans l'émulateur intégré. */
    fun play(activity: Activity, game: Game, resume: Boolean = false): String? {
        val system = _state.value.system ?: return I18n.get(R.string.err_system_not_found)
        return app.play(activity, system, game, resume = resume)
    }
}

// ---------------------------------------------------------------------------

class GameDetailViewModel(
    private val app: RomCloudApp,
    private val systemId: String,
    private val gameId: Long,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val system: GameSystem? = null,
        val game: Game? = null,
        val downloaded: Boolean = false,
        val players: List<Pair<Player, Boolean>> = emptyList(), // (émulateur, installé ?)
        val selectedPlayer: Player? = null,
        val localPath: String? = null,
        val launchCommand: String? = null,
        val retroArchInfo: RetroArchInfo? = null,
        val bios: List<BiosFile> = emptyList(),
        val missingBios: List<BiosFile> = emptyList(),
        /** Partie sauvegardée dans l'émulateur intégré : « Reprendre » avant « Jouer ». */
        val canResume: Boolean = false,
        /** État de sauvegarde en ligne du profil (émulateur intégré), récupéré au lancement s'il est plus récent. */
        val onlineSave: OnlineSave? = null,
        /** TV du profil allumées avec RomCloud ouvert : « Jouer sur la TV » (émulateur intégré). */
        val tvs: List<StreamReceiver> = emptyList(),
    ) {
        val canStream: Boolean get() = downloaded && selectedPlayer?.libretroCore != null && tvs.isNotEmpty()
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val downloads = app.downloader.states

    init {
        viewModelScope.launch {
            val system = app.repository.system(systemId)
            val game = app.repository.game(systemId, gameId)
            val bios = system?.let { app.repository.bios(it) }.orEmpty()
            _state.value = UiState(loading = false, system = system, game = game, bios = bios)
            refreshLocal()
        }
        viewModelScope.launch {
            app.downloader.events.collect { if (it is DownloadEvent.Completed && it.system.id == systemId) refreshLocal() }
        }
        // TV du profil prêtes à recevoir (allumées, RomCloud ouvert) : relues régulièrement (téléphone).
        if (!app.account.isTv) viewModelScope.launch {
            while (true) {
                if (app.account.state.value.signedIn) {
                    val tvs = app.account.streamReceivers()
                    _state.update { it.copy(tvs = tvs) }
                }
                delay(TV_POLL_MS)
            }
        }
    }

    fun refreshLocal() {
        val s = _state.value
        val system = s.system ?: return
        val game = s.game ?: return
        viewModelScope.launch {
            val (downloaded, players) = withContext(Dispatchers.IO) {
                app.library.isDownloaded(system, game) to
                    app.launcher.compatiblePlayers(system, game.fileName).map { it to app.launcher.isInstalled(it) }
            }
            val missingBios = withContext(Dispatchers.IO) { app.library.missingBios(s.bios) }
            val selected = app.launcher.selectedPlayer(system, game.fileName)
            val file = withContext(Dispatchers.IO) { app.library.launchFile(system, game, selected) }
            val localResume = withContext(Dispatchers.IO) { downloaded && app.launcher.canResume(file, selected) }
            val online = selected?.libretroCore?.let { core -> app.account.saves(game.id).find { sameSaveCore(it.core, core) && it.kind == "state" } }
            val canResume = localResume || (downloaded && online != null)
            _state.update {
                it.copy(
                    downloaded = downloaded,
                    players = players,
                    selectedPlayer = selected,
                    localPath = file.absolutePath,
                    launchCommand = selected?.let { p -> app.launcher.describe(p, file) },
                    retroArchInfo = selected?.let(app.launcher::retroArchInfo),
                    missingBios = missingBios,
                    canResume = canResume,
                    onlineSave = online,
                )
            }
        }
    }

    /**
     * Propose d'installer l'émulateur (APK du serveur RomCloud s'il existe, ou Play Store) ;
     * renvoie un message d'erreur ou null.
     */
    fun installPlayer(player: Player): String? {
        val pkg = app.launcher.packageOf(player) ?: return I18n.get(R.string.err_unknown_package)
        app.missingEmulator.value = MissingEmulator(pkg, player.name)
        return null
    }

    fun selectPlayer(player: Player) {
        app.settings.setPreferredPlayer(systemId, player.uniqueId)
        _state.update { it.copy(selectedPlayer = player) }
        refreshLocal()
    }

    /** Télécharge le jeu et les BIOS manquants du système. */
    fun download() {
        val s = _state.value
        val system = s.system ?: return
        val game = s.game ?: return
        app.downloader.dismissError(game.id)
        app.downloader.start(system, game, s.missingBios)
    }

    /** Télécharge seulement les BIOS manquants (jeu déjà présent sur l'appareil). */
    fun downloadBios() {
        val s = _state.value
        val system = s.system ?: return
        val game = s.game ?: return
        app.downloader.dismissError(game.id)
        app.downloader.start(system, game, s.missingBios, includeRom = false)
    }

    fun cancel() = app.downloader.cancel(gameId)

    fun deleteLocal() {
        val s = _state.value
        val system = s.system ?: return
        val game = s.game ?: return
        viewModelScope.launch(Dispatchers.IO) {
            app.library.delete(system, game)
            refreshLocal()
        }
    }

    /** [resume] : reprend la partie sauvegardée dans l'émulateur intégré ; [tv] : diffusée sur cette TV, image [mode]. */
    fun play(activity: Activity, resume: Boolean = false, tv: StreamReceiver? = null, mode: StreamMode = StreamMode.NATIVE): String? {
        val s = _state.value
        val system = s.system ?: return I18n.get(R.string.err_system_not_found)
        val game = s.game ?: return I18n.get(R.string.game_not_found)
        return app.play(activity, system, game, resume = resume, stream = tv, streamMode = mode)
    }

    private companion object {
        const val TV_POLL_MS = 15_000L
    }
}
