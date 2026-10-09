package com.romcloud.app.netplay

import com.romcloud.app.data.Game
import com.romcloud.app.data.GameSystem
import com.romcloud.app.data.Player

/** Façon de jouer à plusieurs : même jeu synchronisé ([NETPLAY]) ou consoles reliées ([LINK]). */
enum class Together { NETPLAY, LINK }

/**
 * Liaison propre à un cœur : chaque appareil émule sa console, avec son propre jeu (Pokémon Rouge
 * relié à Pokémon Bleu), et le cœur échange lui-même ses données avec l'autre console.
 * [id] : identifiant annoncé ([NetplayProtocol.HostedGame.link]) ; [core] : cœur utilisé pour la liaison ;
 * [packets] : paquets du cœur transmis par l'adaptateur natif (interface netpacket de libretro), sinon
 * connexion ouverte par le cœur lui-même vers l'adresse de l'hôte ; [multi] : plus de deux consoles.
 */
enum class LinkKind(val id: String, val core: String, val packets: Boolean, val multi: Boolean) {
    /** Câble Game Link (Game Boy, Game Boy Color) : Gambatte, serveur chez l'hôte. */
    GAME_LINK("gb", "gambatte", packets = false, multi = false),
    /** Câble et adaptateur sans fil de la Game Boy Advance : gpSP (netpacket). */
    GBA_LINK("gba", "gpsp", packets = true, multi = false),
    /** Réseau ad hoc de la PSP : PPSSPP, serveur ad hoc intégré chez l'hôte. */
    PSP_ADHOC("psp", "ppsspp", packets = false, multi = true),
}

/**
 * Liaisons entre consoles portables (câble, adaptateur sans fil, ad hoc), à part du jeu synchronisé
 * de [NetplayRules] : elles passent par le cœur de la liaison, quel que soit l'émulateur choisi
 * (sauvegarde reprise de celui-ci, voir LibretroActivity).
 */
object LinkRules {
    private val SYSTEMS = mapOf(
        "gb" to LinkKind.GAME_LINK, "gbc" to LinkKind.GAME_LINK, "gbcolor" to LinkKind.GAME_LINK,
        "gba" to LinkKind.GBA_LINK,
        "psp" to LinkKind.PSP_ADHOC,
    )

    /** Port du câble Game Link de Gambatte (sa valeur par défaut). */
    const val GAMBATTE_PORT = "56400"

    /** Décalage des ports des jeux PSP (valeur par défaut de PPSSPP hors libretro). */
    const val PSP_PORT_OFFSET = "10000"

    fun kind(system: GameSystem): LinkKind? = SYSTEMS[system.id.lowercase()] ?: SYSTEMS[system.shortname.lowercase()]

    fun kind(id: String?): LinkKind? = LinkKind.entries.find { it.id == id }

    /** Jeu à plusieurs d'une console qui se relie à une autre. */
    fun canLink(system: GameSystem, game: Game): Boolean =
        kind(system) != null && NetplayRules.maxPlayers(game.players) >= 2

    /**
     * Options du cœur pour la liaison : [host] (serveur) ou invité relié à [hostAddress] ; [deviceId] :
     * adresse MAC propre à l'appareil (PSP : deux consoles de même adresse ne se voient pas) ;
     * [ownAddress] : adresse de cet appareil sur le réseau local (hôte de la PSP).
     */
    fun options(kind: LinkKind, host: Boolean, hostAddress: String, deviceId: String, ownAddress: String? = null): Map<String, String> = when (kind) {
        LinkKind.GAME_LINK -> buildMap {
            put("gambatte_gb_link_mode", if (host) "Network Server" else "Network Client")
            put("gambatte_gb_link_network_port", GAMBATTE_PORT)
            if (!host) ipDigits(hostAddress)?.forEachIndexed { i, digit -> put("gambatte_gb_link_network_server_ip_${i + 1}", digit.toString()) }
        }
        LinkKind.GBA_LINK -> emptyMap()  // câble choisi par gpSP d'après le jeu (option « Automatic »)
        LinkKind.PSP_ADHOC -> buildMap {
            put("ppsspp_enable_wlan", "enabled")
            // Ports des jeux décalés (PSP : souvent sous 1024, interdits aux applications Android) ;
            // le même décalage sur toutes les consoles reliées (port de l'autre calculé avec).
            put("ppsspp_port_offset", PSP_PORT_OFFSET)
            put("ppsspp_enable_builtin_pro_ad_hoc_server", if (host) "enabled" else "disabled")
            // Hôte : relié à son propre serveur par son adresse sur le réseau local, pas par « localhost » :
            // le serveur annonce chaque console aux autres avec l'adresse de sa connexion (127.0.0.1
            // sinon, injoignable pour les invités).
            val server = if (host) ownAddress?.let(::ipDigits) else ipDigits(hostAddress)
            if (server == null) {
                put("ppsspp_change_pro_ad_hoc_server_address", "localhost")
            } else {
                put("ppsspp_change_pro_ad_hoc_server_address", "IP address")
                server.forEachIndexed { i, digit -> put("ppsspp_pro_ad_hoc_server_address%02d".format(i + 1), digit.toString()) }
            }
            mac(deviceId).forEachIndexed { i, digit -> put("ppsspp_change_mac_address%02d".format(i + 1), digit.toString()) }
        }
    }

    /** Adresse IPv4 en 12 chiffres, comme les options des cœurs (« 192.168.1.5 » -> « 192168001005 »). */
    fun ipDigits(address: String): String? {
        val parts = address.trim().split('.')
        if (parts.size != 4) return null
        val numbers = parts.map { it.toIntOrNull()?.takeIf { n -> n in 0..255 } ?: return null }
        return numbers.joinToString("") { it.toString().padStart(3, '0') }
    }

    /** Adresse MAC (12 chiffres hexadécimaux) tirée de l'identifiant de l'appareil, administrée localement. */
    fun mac(deviceId: String): String {
        var hash = 1469598103934665603L
        for (c in deviceId) hash = (hash xor c.code.toLong()) * 1099511628211L
        val bytes = (0 until 6).map { ((hash ushr (it * 8)) and 0xff).toInt() }.toMutableList()
        bytes[0] = (bytes[0] and 0xfc) or 0x02
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

/** Façon de jouer à plusieurs avec ce jeu et cet émulateur, ou null. */
fun NetplayRules.together(system: GameSystem, game: Game, player: Player?): Together? = when {
    canPlayTogether(system, game, player) -> Together.NETPLAY
    LinkRules.canLink(system, game) -> Together.LINK
    else -> null
}
