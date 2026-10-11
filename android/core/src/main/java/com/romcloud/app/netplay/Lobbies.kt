package com.romcloud.app.netplay

import com.romcloud.app.data.Account
import com.romcloud.app.data.ApiException
import com.romcloud.app.data.Game
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/*
 * Multijoueurs : joueurs connectés à un profil (pastille présent / absent) et lobbies du serveur
 * (server/src/lobbies.js). Un lobby a un hôte, qui choisit le jeu et lance la partie, et des invités
 * qui la rejoignent ensuite avec le mécanisme habituel (réseau local ou relais du serveur).
 */

/** Joueur connecté : [active] : présent (activité récente ou en partie), sinon absent. */
@Serializable
data class LobbyPlayer(
    val id: String,
    val name: String = "",
    val user: String? = null,
    val platform: String = "",
    val busy: Boolean = false,
    val active: Boolean = false,
) {
    val displayName: String get() = if (user.isNullOrBlank()) name else "$user · $name"
}

/** Jeu d'un lobby ; [players] : nombre de joueurs maximal du jeu. */
@Serializable
data class LobbyGame(
    val gameId: Long = 0,
    val systemId: String = "",
    val title: String = "",
    val fileName: String = "",
    val size: Long = 0,
    val players: Int = 0,
) {
    fun matches(game: Game, systemId: String): Boolean =
        this.systemId == systemId && (gameId == game.id || (fileName == game.fileName && size == game.size))
}

/** Membre d'un lobby : [status] « waiting » (en attente) ou « playing » (en jeu). */
@Serializable
data class LobbyMember(
    val id: String,
    val name: String = "",
    val user: String? = null,
    val platform: String = "",
    val status: String = "waiting",
    val host: Boolean = false,
) {
    val displayName: String get() = if (user.isNullOrBlank()) name else "$user · $name"
    val playing: Boolean get() = status == "playing"
}

/** Lobby : [modes] : façons de jouer en réseau possibles (netplay, link) ; [started] : partie lancée par l'hôte. */
@Serializable
data class Lobby(
    val id: String,
    val host: String,
    val game: LobbyGame? = null,
    val mode: String? = null,
    val modes: List<String> = emptyList(),
    val started: Boolean = false,
    val members: List<LobbyMember> = emptyList(),
    /** Systèmes jouables en réseau par tous les membres (émulateur intégré) ; null : aucune restriction. */
    val systems: List<String>? = null,
)

@Serializable
data class LobbyList(val players: List<LobbyPlayer> = emptyList(), val lobbies: List<Lobby> = emptyList())

/** Lobbies vus par cet appareil ([me]) ; [mine] : lobby dont il est membre. */
data class LobbyState(val players: List<LobbyPlayer> = emptyList(), val lobbies: List<Lobby> = emptyList(), val me: String = "") {
    val mine: Lobby? get() = lobbies.firstOrNull { l -> l.members.any { it.id == me } }
    val isHost: Boolean get() = mine?.host == me
    /** Invité d'un lobby : il reste sur la page du lobby tant qu'il ne l'a pas quitté. */
    val locked: Boolean get() = mine != null && !isHost
    /** Joueurs minimum des jeux affichés dans un lobby (2 au moins) ; 0 hors d'un lobby. */
    val minPlayers: Int get() = mine?.let { maxOf(2, it.members.size) } ?: 0
    fun lobbiesOf(game: Game, systemId: String): List<Lobby> = lobbies.filter { it.game?.matches(game, systemId) == true }
    /** Système jouable en réseau par tous les membres du lobby (toujours vrai hors d'un lobby). */
    fun allowsSystem(systemId: String): Boolean = mine?.systems?.contains(systemId) ?: true
}

/**
 * Lobbies relus sur le serveur toutes les 3 s tant qu'un écran de l'application est affiché (profil
 * connecté) ; actions de cet appareil ; [closed] : lobby dissous ou quitté (retour à l'accueil).
 */
class Lobbies(
    private val account: Account,
    private val presence: PlayPresence,
    /** Systèmes que cet appareil sait jouer en réseau avec LibretroDroid (envoyés au lobby). */
    private val capableSystems: suspend () -> List<String>?,
) {
    private val _state = MutableStateFlow(LobbyState(me = presence.lan.deviceId))
    val state: StateFlow<LobbyState> = _state.asStateFlow()
    private val _closed = MutableSharedFlow<Boolean>(extraBufferCapacity = 4)
    /** Lobby fermé : true s'il a été dissous par l'hôte (false : quitté par cet appareil). */
    val closed: SharedFlow<Boolean> = _closed.asSharedFlow()
    private var job: Job? = null
    @Volatile private var leaving = false
    /** Lancement de la partie (statut « en jeu ») : de retour en attente au retour dans l'application. */
    @Volatile private var playingSince = 0L

    private val me get() = presence.lan.deviceId

    @Synchronized
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                refresh()
                delay(POLL_MS)
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    suspend fun refresh() {
        if (!account.state.value.signedIn) {
            _state.value = LobbyState(me = me)
            return
        }
        val list = runCatching { account.lobbies() }.getOrNull() ?: return
        val before = _state.value.mine
        val next = LobbyState(list.players, list.lobbies, me)
        _state.value = next
        if (before != null && next.mine == null) {
            _closed.tryEmit(!leaving)
            leaving = false
        }
        // Retour dans l'application après la partie : de nouveau en attente.
        val self = next.mine?.members?.find { it.id == me }
        if (self?.playing == true && System.currentTimeMillis() - playingSince > PLAYING_GRACE_MS) {
            runCatching { account.lobbyStatus(next.mine!!.id, me, "waiting") }
            refreshSoon()
        }
    }

    private suspend fun refreshSoon() {
        delay(200)
        refresh()
    }

    /** Requête au serveur ; appareil pas encore annoncé (409) : annonce, puis nouvel essai. */
    private suspend fun <T> send(block: suspend () -> T): T = try {
        block()
    } catch (e: ApiException) {
        if (e.code != 409) throw e
        presence.internet.announceNow()
        delay(1500)
        block()
    }

    suspend fun create(game: LobbyGame? = null, modes: List<String> = emptyList()) {
        val systems = capableSystems()
        send { account.createLobby(me, game, modes, systems) }
        refresh()
    }

    suspend fun join(id: String) {
        if (_state.value.lobbies.find { it.id == id }?.members?.any { it.id == me } != true) {
            val systems = capableSystems()
            send { account.joinLobby(id, me, systems) }
        }
        refresh()
    }

    suspend fun leave() {
        val lobby = _state.value.mine ?: return
        leaving = true
        runCatching { send { account.leaveLobby(lobby.id, me) } }
        refresh()
    }

    suspend fun dissolve() {
        val lobby = _state.value.mine ?: return
        leaving = true
        runCatching { send { account.dissolveLobby(lobby.id, me) } }
        refresh()
    }

    /** Hôte : jeu du lobby et façons de jouer possibles. */
    suspend fun setGame(game: LobbyGame, modes: List<String>) {
        val lobby = _state.value.mine ?: return
        send { account.updateLobby(lobby.id, me, game, modes, null) }
        refresh()
    }

    /** Hôte : façon de jouer choisie. */
    suspend fun setMode(mode: String) {
        val lobby = _state.value.mine ?: return
        send { account.updateLobby(lobby.id, me, null, null, mode) }
        refresh()
    }

    /** Partie lancée par cet appareil : « en jeu » pour les autres. */
    suspend fun markPlaying() {
        val lobby = _state.value.mine ?: return
        playingSince = System.currentTimeMillis()
        send { account.lobbyStatus(lobby.id, me, "playing") }
        refresh()
    }

    private companion object {
        const val POLL_MS = 3_000L
        /** Le jeu met quelques secondes à s'ouvrir : pas de retour « en attente » avant ce délai. */
        const val PLAYING_GRACE_MS = 20_000L
    }
}
