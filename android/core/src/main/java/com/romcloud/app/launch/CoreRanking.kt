package com.romcloud.app.launch

/**
 * Ordre des cœurs libretro de chaque console : du plus abouti, qui fait tourner le plus de jeux,
 * au moins abouti. Même table que desktop-tauri/src-tauri/src/cores.rs (application Windows). Les cœurs
 * absents de la table gardent l'ordre des modèles du serveur, après ceux de la table. Un cœur absent
 * d'une seule plateforme (Azahar sous Android, ARMSX2 sous Windows) est placé après les autres : le
 * cœur par défaut, puis le 2e, le 3e… sont les mêmes sous Windows et Android (états compatibles).
 * PlayStation 2 : LRPS2 (pcsx2) seul à afficher une image avec LibretroDroid (rendu logiciel).
 * Arcade : le nombre de jeux dépend surtout de la version du romset (MAME 0.78 -> mame2003_plus).
 */
internal object CoreRanking {

    private val RANKING: Map<String, List<String>> = mapOf(
        "nes" to listOf("mesen", "nestopia", "fceumm", "bnes", "quicknes"),
        "fds" to listOf("mesen", "nestopia", "fceumm", "bnes", "quicknes"),
        "snes" to listOf("bsnes", "snes9x", "mesen-s", "bsnes_hd_beta", "higan_sfc", "higan_sfc_balanced", "bsnes_mercury_accuracy", "bsnes_mercury_balanced", "bsnes2014_accuracy", "bsnes2014_balanced", "mednafen_supafaust", "snes9x2010", "bsnes_mercury_performance", "bsnes2014_performance", "mednafen_snes", "bsnes_cplusplus98", "snes9x2005_plus", "snes9x2005", "snes9x2002"),
        "gb" to listOf("sameboy", "gambatte", "mgba", "mesen-s", "gearboy", "tgbdual", "vbam", "DoubleCherryGB", "skyemu"),
        "gbc" to listOf("sameboy", "gambatte", "mgba", "mesen-s", "gearboy", "tgbdual", "vbam", "DoubleCherryGB", "skyemu"),
        "gba" to listOf("mgba", "vbam", "mednafen_gba", "vba_next", "gpsp", "skyemu", "noods", "meteor"),
        "n64" to listOf("mupen64plus_next_gles3", "mupen64plus_next", "mupen64plus_next_gles2", "parallel_n64"),
        "nds" to listOf("melondsds", "desmume", "melonds", "desmume2015", "noods", "skyemu"),
        "ndsi" to listOf("melondsds", "melonds", "desmume", "desmume2015"),
        "3ds" to listOf("citra", "panda3ds", "azahar"),
        "gc" to listOf("dolphin"),
        "wii" to listOf("dolphin"),
        "virtualboy" to listOf("mednafen_vb"),
        "master" to listOf("genesis_plus_gx", "gearsystem", "smsplus", "picodrive", "genesis_plus_gx_wide"),
        "gamegear" to listOf("genesis_plus_gx", "gearsystem", "smsplus", "picodrive", "genesis_plus_gx_wide"),
        "sg1000" to listOf("genesis_plus_gx", "gearsystem", "smsplus", "picodrive"),
        "genesis" to listOf("genesis_plus_gx", "picodrive", "genesis_plus_gx_wide"),
        "segacd" to listOf("genesis_plus_gx", "picodrive", "genesis_plus_gx_wide"),
        "sega32x" to listOf("picodrive"),
        "saturn" to listOf("mednafen_saturn", "ymir", "yabasanshiro", "yabause", "kronos"),
        "dreamcast" to listOf("flycast"),
        "psx" to listOf("mednafen_psx_hw", "swanstation", "mednafen_psx", "pcsx_rearmed", "duckstation", "goosestation"),
        "ps2" to listOf("pcsx2", "pcee2", "play", "armsx2"),
        "psp" to listOf("ppsspp"),
        "tg16" to listOf("mednafen_pce", "mednafen_pce_fast", "mednafen_supergrafx"),
        "tgcd" to listOf("mednafen_pce", "mednafen_pce_fast", "mednafen_supergrafx"),
        "supergrafx" to listOf("mednafen_supergrafx", "mednafen_pce"),
        "ngp" to listOf("mednafen_ngp", "race"),
        "ngpc" to listOf("mednafen_ngp", "race"),
        "ws" to listOf("mednafen_wswan"),
        "wsc" to listOf("mednafen_wswan"),
        "atari2600" to listOf("stella", "stella2014"),
        "atari7800" to listOf("prosystem"),
        "lynx" to listOf("handy", "mednafen_lynx"),
        "cpc" to listOf("cap32", "crocods"),
        "gx4000" to listOf("cap32"),
        "neogeo" to listOf("fbneo", "geolith", "fbalpha2012"),
        "fbneo" to listOf("fbneo", "fbalpha2012"),
        "mame" to listOf("mame", "mamearcade", "mame2010", "mame2003_plus", "mame2003", "mame2003_midway", "mame2000"),
    )

    /** Cœurs absents du buildbot libretro pour Android (vérifié en octobre 2026) : proposés en dernier. */
    private val UNAVAILABLE = setOf("bnes", "duckstation", "goosestation", "azahar", "mame", "mupen64plus_next")

    /** Cœur téléchargeable pour l'émulateur intégré (présent sur le buildbot libretro pour Android). */
    fun isAvailable(core: String): Boolean = core !in UNAVAILABLE

    /** Cœurs [cores] du système [systemId] triés selon la table ; ordre conservé pour les autres. */
    fun <T> sort(systemId: String, cores: List<T>, coreOf: (T) -> String): List<T> {
        val order = RANKING[systemId.lowercase()].orEmpty()
        return cores.sortedBy { item ->
            val core = coreOf(item)
            if (core in UNAVAILABLE) order.size + 1 else order.indexOf(core).let { if (it < 0) order.size else it }
        }
    }
}
