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

    /** Raisons d'un refus ([Answer.reason]). */
    const val REFUSED = "refused"
    const val BUSY = "busy"
    const val OTHER_GAME = "game"
    const val OTHER_CORE = "core"
    const val OTHER_VERSION = "version"

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    /** Annonce d'un appareil ; [port] et [hosting] : partie qu'il propose de rejoindre. */
    @Serializable
    data class Beacon(
        val app: String = APP,
        val v: Int = VERSION,
        val id: String,
        val name: String,
        val platform: String,
        val port: Int = 0,
        val hosting: HostedGame? = null,
    )

    /** Jeu d'une partie proposée : identifiant sur le serveur, fichier et cœur (les mêmes chez l'invité). */
    @Serializable
    data class HostedGame(
        val gameId: Long,
        val systemId: String,
        val title: String,
        val fileName: String,
        val size: Long,
        val core: String,
    )

    /** Demande de l'invité : son jeu (même fichier) et son cœur (même nom ; taille comparée). */
    @Serializable
    data class Join(
        val v: Int = VERSION,
        val name: String,
        val platform: String,
        val fileName: String,
        val size: Long,
        val core: String,
        val coreSize: Long,
    )

    /** Réponse de l'hôte ; [coreSize] : taille de son cœur (différente : versions différentes). */
    @Serializable
    data class Answer(val ok: Boolean, val reason: String? = null, val delay: Int = DELAY_FRAMES, val coreSize: Long = 0)

    const val APP = "romcloud"
}

/** Appareil RomCloud du réseau local, vu par ses annonces. */
data class Peer(
    val id: String,
    val name: String,
    val platform: String,
    val address: String,
    val port: Int,
    val hosting: NetplayProtocol.HostedGame?,
    val seenAt: Long,
)

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

    /** Cœurs trop lourds, ou dont l'état est trop gros pour être copié en cours de partie. */
    private val EXCLUDED_CORES = setOf(
        "dolphin", "pcsx2", "play", "lrps2", "pcee2", "armsx2", "flycast", "citra", "azahar", "panda3ds",
        "ppsspp", "melonds", "melondsds", "desmume", "desmume2015",
    )

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
