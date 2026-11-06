package com.romcloud.app.launch

import com.romcloud.app.data.GameSystem
import com.romcloud.app.data.Player

/**
 * Émulateur intégré LibretroDroid : pour chaque cœur libretro des modèles d'un système (extra
 * LIBRETRO des modèles Daijishou), un émulateur « LibretroDroid - <cœur> » est ajouté en tête de
 * liste (choix par défaut). Le cœur est téléchargé depuis le buildbot libretro au premier lancement
 * (voir LibretroCores). Les modèles de cœur eux-mêmes sont retirés : seul le moteur intégré lance
 * les cœurs libretro. Console émulée par le moteur intégré ([CoreRanking.covers] : NES, PlayStation,
 * DS, 3DS, GameCube…) : ses émulateurs externes (DuckStation, melonDS, Azahar, Dolphin…) sont retirés
 * aussi ; ils restent proposés pour les autres (Switch, PS3, Vita, Wii U, Naomi…).
 */
object LibretroPlayers {

    const val ID_PREFIX = "libretrodroid."
    private val VALID_CORE = Regex("[A-Za-z0-9_]+")

    /**
     * Nom du cœur libretro demandé par un modèle (extra LIBRETRO) : « snes9x »,
     * « /data/data/…/cores/snes9x_libretro_android.so » -> « snes9x » ; null sinon.
     */
    fun coreOf(player: Player): String? =
        AmStartParser.stringExtra(player.amStartArguments, "LIBRETRO")
            ?.substringAfterLast('/')
            ?.removeSuffix(".so")
            ?.removeSuffix("_android")
            ?.removeSuffix("_libretro")
            ?.takeIf { it.isNotEmpty() }

    /** Ajoute en tête les émulateurs LibretroDroid (un par cœur, du plus abouti au moins abouti : CoreRanking). */
    fun addTo(system: GameSystem): GameSystem {
        if (system.players.any { it.libretroCore != null }) return system
        val others = system.players.filter { coreOf(it) == null }
        val added = system.players
            .mapNotNull { p -> coreOf(p)?.takeIf { VALID_CORE.matches(it) }?.let { it to p } }
            .distinctBy { it.first }
            .let { cores -> CoreRanking.sort(system.shortname.ifEmpty { system.id }, cores) { it.first } }
            .map { (core, template) ->
                Player(
                    name = "LibretroDroid - $core",
                    uniqueId = "$ID_PREFIX$core",
                    acceptedFilenameRegex = template.acceptedFilenameRegex,
                    amStartArguments = "",
                    libretroCore = core,
                )
            }
        if (added.isEmpty()) return system
        val key = system.shortname.ifEmpty { system.id }
        val covered = CoreRanking.covers(key, added.mapNotNull { it.libretroCore })
        return system.copy(players = if (covered) added else added + others)
    }
}
