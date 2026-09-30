package com.romcloud.app.launch

import com.romcloud.app.data.GameSystem
import com.romcloud.app.data.Player

/**
 * Émulateur intégré LibretroDroid : pour chaque cœur proposé par les modèles RetroArch d'un
 * système, un émulateur « LibretroDroid - <cœur> » est ajouté en tête de liste (choix par défaut).
 * Le cœur est téléchargé depuis le buildbot libretro au premier lancement (voir LibretroCores).
 */
object LibretroPlayers {

    const val ID_PREFIX = "libretrodroid."
    private val VALID_CORE = Regex("[A-Za-z0-9_]+")

    /**
     * Nom du cœur demandé par un modèle RetroArch (extra LIBRETRO) : « snes9x »,
     * « /data/data/…/cores/snes9x_libretro_android.so » -> « snes9x » ; null sinon.
     */
    fun coreOf(player: Player): String? =
        AmStartParser.stringExtra(player.amStartArguments, "LIBRETRO")
            ?.substringAfterLast('/')
            ?.removeSuffix(".so")
            ?.removeSuffix("_android")
            ?.removeSuffix("_libretro")
            ?.takeIf { it.isNotEmpty() }

    /** Ajoute en tête les émulateurs LibretroDroid (un par cœur, dans l'ordre des modèles RetroArch). */
    fun addTo(system: GameSystem): GameSystem {
        if (system.players.any { it.libretroCore != null }) return system
        val added = system.players
            .mapNotNull { p -> coreOf(p)?.takeIf { VALID_CORE.matches(it) }?.let { it to p } }
            .distinctBy { it.first }
            .map { (core, template) ->
                Player(
                    name = "LibretroDroid - $core",
                    uniqueId = "$ID_PREFIX$core",
                    acceptedFilenameRegex = template.acceptedFilenameRegex,
                    amStartArguments = "",
                    libretroCore = core,
                )
            }
        return if (added.isEmpty()) system else system.copy(players = added + system.players)
    }
}
