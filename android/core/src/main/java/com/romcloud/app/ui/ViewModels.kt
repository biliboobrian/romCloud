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
import com.romcloud.app.data.Player
import com.romcloud.app.launch.CloseEmulatorPrompt
import com.romcloud.app.launch.LaunchException
import com.romcloud.app.launch.MissingEmulatorException
import com.romcloud.app.launch.RetroArchInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lance un jeu déjà téléchargé ; renvoie un message d'erreur ou null.
 * Si l'émulateur n'est pas installé, la proposition d'installation est affichée à la place ;
 * s'il doit être fermé d'abord (Android 14+), l'avertissement correspondant est affiché
 * (le lancement reprend avec [emulatorClosed] = true).
 */
fun RomCloudApp.play(activity: Activity, system: GameSystem, game: Game, emulatorClosed: Boolean = false): String? {
    val file = library.fileFor(system, game)
    val player = launcher.selectedPlayer(system, game.fileName)
    if (!emulatorClosed) {
        launcher.closeWarningPackage(player)?.let { pkg ->
            closeEmulatorPrompt.value = CloseEmulatorPrompt(system, game, pkg, player?.name ?: pkg)
            return null
        }
    }
    return try {
        launcher.launch(activity, system, file, player)
        null
    } catch (e: MissingEmulatorException) {
        missingEmulator.value = e.emulator
        null
    } catch (e: LaunchException) {
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

    val downloads = app.downloader.states
    private var searchJob: Job? = null

    init {
        refresh()
        viewModelScope.launch {
            app.downloader.events.collect { if (it is DownloadEvent.Completed) refreshSearchLocal() }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            _state.value = try {
                val loaded = app.repository.systems()
                UiState(loading = false, systems = loaded.data, offline = loaded.offline, error = loaded.error)
            } catch (e: Exception) {
                _state.value.copy(loading = false, error = e.message ?: I18n.get(R.string.err_connection))
            }
            if (_search.value.active) runSearch(_search.value.query, debounce = false)
        }
    }

    fun system(id: String): GameSystem? = _state.value.systems.find { it.id == id }

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
                SearchState(
                    query = query,
                    results = loaded.data,
                    downloaded = downloadedAmong(loaded.data),
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
        val grid: Boolean = false,
        /** BIOS du système sur le serveur et ceux absents de l'appareil. */
        val bios: List<BiosFile> = emptyList(),
        val missingBios: List<BiosFile> = emptyList(),
    ) {
        val visibleGames: List<Game>
            get() = games.filter { g ->
                (query.isBlank() || g.title.contains(query, true) || g.fileName.contains(query, true)) &&
                    when (filter) {
                        GameFilter.ALL -> true
                        GameFilter.DOWNLOADED -> g.id in downloaded
                        GameFilter.REMOTE -> g.id !in downloaded
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
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            try {
                val system = app.repository.system(systemId)
                val loaded = app.repository.games(systemId)
                val bios = system?.let { app.repository.bios(it) }.orEmpty()
                _state.update {
                    it.copy(
                        loading = false, system = system, games = loaded.data,
                        offline = loaded.offline, error = loaded.error, bios = bios,
                    )
                }
                refreshLocal()
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
            val (ids, missing) = withContext(Dispatchers.IO) {
                app.library.downloadedIds(system, s.games) to app.library.missingBios(s.bios)
            }
            _state.update { it.copy(downloaded = ids, missingBios = missing) }
        }
    }

    fun setQuery(q: String) = _state.update { it.copy(query = q) }
    fun setFilter(f: GameFilter) = _state.update { it.copy(filter = f) }

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

    fun play(activity: Activity, game: Game): String? {
        val system = _state.value.system ?: return I18n.get(R.string.err_system_not_found)
        return app.play(activity, system, game)
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
    )

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
            val file = app.library.fileFor(system, game)
            _state.update {
                it.copy(
                    downloaded = downloaded,
                    players = players,
                    selectedPlayer = selected,
                    localPath = file.absolutePath,
                    launchCommand = selected?.let { p -> app.launcher.describe(p, file) },
                    retroArchInfo = selected?.let(app.launcher::retroArchInfo),
                    missingBios = missingBios,
                )
            }
        }
    }

    /** Ouvre la fiche Play Store de l'émulateur ; renvoie un message d'erreur ou null. */
    fun installPlayer(activity: Activity, player: Player): String? {
        val pkg = app.launcher.packageOf(player) ?: return I18n.get(R.string.err_unknown_package)
        return try {
            app.launcher.openStore(activity, pkg)
            null
        } catch (e: LaunchException) {
            e.message
        }
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

    fun play(activity: Activity): String? {
        val s = _state.value
        val system = s.system ?: return I18n.get(R.string.err_system_not_found)
        val game = s.game ?: return I18n.get(R.string.game_not_found)
        return app.play(activity, system, game)
    }
}
