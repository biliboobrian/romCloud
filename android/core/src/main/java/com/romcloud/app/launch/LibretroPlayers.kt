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

    /**
     * Cœurs proposés avant les autres cœurs du système, quel que soit l'ordre des modèles :
     * PlayStation 2 -> LRPS2 (pcsx2, rendu logiciel), seul à afficher une image avec LibretroDroid
     * (Play! s'arrête ou se bloque, pcee2 n'a que Vulkan).
     */
    private val PREFERRED_CORES = listOf("pcsx2")
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
            .sortedBy { (core, _) -> if (core in PREFERRED_CORES) 0 else 1 }
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
