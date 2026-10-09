package com.romcloud.app.netplay

import com.romcloud.app.data.Game
import com.romcloud.app.data.GameSystem
import com.romcloud.app.data.Player
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Jeu à plusieurs en réseau local (netplay synchronisé) : chaque appareil émule le même jeu et seules
 * les touches circulent, image par image (adaptateur natif, audio_shim.cpp).
 * 1. Découverte : chaque appareil RomCloud ouvert annonce sa présence en diffusion UDP sur le réseau
 *    local ([Beacon], toutes les [BEACON_MS] ms, port [DISCOVERY_PORT]) ; l'hôte d'une partie y
 *    ajoute le jeu et son port TCP.
 * 2. L'invité se connecte au port de l'hôte et envoie [Join] (writeUTF) ; l'hôte accepte ou refuse
 *    ([Answer]). Accepté : la connexion passe à l'adaptateur natif (état de l'hôte copié chez
 *    l'invité, puis touches échangées).
 */
object NetplayProtocol {
    const val VERSION = 1
    const val DISCOVERY_PORT = 47321
    const val BEACON_MS = 2000L
    /** Appareil muet depuis ce délai : retiré de la liste. */
    const val PEER_TTL_MS = 7000L
    /** Images entre l'appui et son effet (environ 50 ms à 60 images/s) : marge du réseau local. */
    const val DELAY_FRAMES = 3
    /** Images de délai au plus (Internet). */
    const val MAX_DELAY_FRAMES = 15
    /** Annonce au serveur (jeu par Internet) : intervalle. */
    const val INTERNET_ANNOUNCE_MS = 10_000L

    /** Raisons d'un refus ([Answer.reason]). */
    const val REFUSED = "refused"
    const val BUSY = "busy"
    const val OTHER_GAME = "game"
    const val OTHER_CORE = "core"
    const val OTHER_VERSION = "version"

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    /**
     * Annonce d'un appareil ; [port] et [hosting] : partie qu'il propose de rejoindre ; [busy] : en
     * partie avec un autre appareil (ni hôte disponible, ni invité possible).
     */
    @Serializable
    data class Beacon(
        val app: String = APP,
        val v: Int = VERSION,
        val id: String,
        val name: String,
        val platform: String,
        val port: Int = 0,
        val hosting: HostedGame? = null,
        val busy: Boolean = false,
    )

    /**
     * Jeu d'une partie proposée : identifiant sur le serveur, fichier et cœur (les mêmes chez l'invité) ;
     * [link] : liaison entre consoles ([LinkKind.id]), l'invité y relie sa console avec son propre jeu ;
     * [session] : partie aussi proposée par Internet, rejointe par le relais du serveur avec cet identifiant.
     */
    @Serializable
    data class HostedGame(
        val gameId: Long,
        val systemId: String,
        val title: String,
        val fileName: String,
        val size: Long,
        val core: String,
        val link: String? = null,
        val session: String? = null,
    )

    /** Demande de l'invité : son jeu (même fichier) et son cœur (même nom ; taille comparée) ; [link] : liaison demandée. */
    @Serializable
    data class Join(
        val v: Int = VERSION,
        val name: String,
        val platform: String,
        val fileName: String,
        val size: Long,
        val core: String,
        val coreSize: Long,
        val link: String? = null,
        /** Images de délai souhaitées (Internet : d'après les allers-retours mesurés), 0 : celui de l'hôte. */
        val delay: Int = 0,
    )

    /** Réponse de l'hôte ; [coreSize] : taille de son cœur (différente : versions différentes). */
    @Serializable
    data class Answer(val ok: Boolean, val reason: String? = null, val delay: Int = DELAY_FRAMES, val coreSize: Long = 0)

    const val APP = "romcloud"
}

/**
 * Appareil RomCloud joignable, vu par ses annonces ; [busy] : en partie avec un autre appareil ;
 * [internet] : par Internet (profil [user], même serveur ; aller-retour [rtt] jusqu'au serveur),
 * sinon sur le réseau local ([address], [port]).
 */
data class Peer(
    val id: String,
    val name: String,
    val platform: String,
    val address: String,
    val port: Int,
    val hosting: NetplayProtocol.HostedGame?,
    val seenAt: Long,
    val busy: Boolean = false,
    val user: String? = null,
    val internet: Boolean = false,
    val rtt: Int = 0,
) {
    /** Nom affiché : appareil, précédé du profil par Internet (« alice · Pixel 8 »). */
    val displayName: String get() = if (internet && !user.isNullOrBlank()) "$user · $name" else name

    /** Libre : ni hôte d'une partie, ni en partie ; peut rejoindre une partie proposée. */
    val available: Boolean get() = hosting == null && !busy

    /** Propose-t-il ce jeu (même jeu du serveur, ou même fichier) ? */
    fun hosts(game: Game): Boolean {
        val hosted = hosting ?: return false
        return hosted.systemId == game.systemId && (hosted.gameId == game.id || (hosted.fileName == game.fileName && hosted.size == game.size))
    }
}

/** Au moins un appareil libre sur le réseau : une partie peut être proposée. */
fun List<Peer>.anyAvailable(): Boolean = any { it.available }

/**
 * Jeux et cœurs qui se jouent à plusieurs en réseau : consoles de salon et bornes d'arcade (plusieurs
 * manettes sur la même console ; les consoles portables se relient par câble ou réseau émulé, à part),
 * jeux à au moins deux joueurs, cœur du moteur intégré dont l'état se copie rapidement.
 */
object NetplayRules {
    private val SYSTEMS = setOf(
        "nes", "fds", "snes", "snesmsu1", "satellaview", "n64", "psx", "genesis", "genesismsu", "segacd", "sega32x",
        "pico", "master", "sg1000", "saturn", "tg16", "tgcd", "supergrafx", "pcfx", "neogeo", "neogeocd",
        "cps1", "cps2", "cps3", "fbneo", "mame", "atari2600", "atari5200", "atari7800", "jaguar", "coleco",
        "colecovision", "intellivision", "msx", "msx2", "3do",
    )

    /**
     * Cœurs trop lourds, dont l'état est trop gros pour être copié en cours de partie, ou qui ne
     * tiennent pas une partie synchronisée (vérifié entre deux moteurs) : parallel_n64 s'arrête net
     * après le départ ; Yabause, YabaSanshiro, Kronos et Ymir (Saturn, émulation répartie sur
     * plusieurs fils) et SMS Plus divergent à chaque contrôle.
     */
    private val EXCLUDED_CORES = setOf(
        "dolphin", "pcsx2", "play", "lrps2", "pcee2", "armsx2", "flycast", "citra", "azahar", "panda3ds",
        "ppsspp", "melonds", "melondsds", "desmume", "desmume2015",
        "parallel_n64", "yabause", "yabasanshiro", "kronos", "ymir", "smsplus",
    )

    /**
     * Options des cœurs imposées en jeu synchronisé : ce qui dépend de l'appareil et changerait la
     * partie (meilleurs scores enregistrés de FBNeo, réinjectés dans la mémoire du jeu).
     */
    val CORE_OPTIONS = mapOf("fbneo-hiscores" to "disabled")

    /** Nombre de joueurs maximal d'après le champ « joueurs » du scraping (« 1-2 », « 4 »…), 0 si inconnu. */
    fun maxPlayers(players: String?): Int =
        Regex("\\d+").findAll(players.orEmpty()).mapNotNull { it.value.toIntOrNull() }.maxOrNull() ?: 0

    fun systemAllows(system: GameSystem): Boolean =
        system.id.lowercase() in SYSTEMS || system.shortname.lowercase() in SYSTEMS

    fun coreAllows(core: String?): Boolean = !core.isNullOrEmpty() && core !in EXCLUDED_CORES

    /** Le jeu se joue-t-il à plusieurs en réseau avec cet émulateur (moteur intégré) ? */
    fun canPlayTogether(system: GameSystem, game: Game, player: Player?): Boolean =
        systemAllows(system) && maxPlayers(game.players) >= 2 && coreAllows(player?.libretroCore)
}
