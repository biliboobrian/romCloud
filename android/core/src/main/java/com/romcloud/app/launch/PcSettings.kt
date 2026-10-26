package com.romcloud.app.launch

import com.romcloud.app.I18n
import com.romcloud.core.R
import kotlin.math.roundToInt

/**
 * Réglages d'un jeu PC choisis dans sa fiche (enregistrés dans le profil, [com.romcloud.app.data.Account.gameSettings]) :
 * version de Proton, pilote Vulkan (Turnip inclus dans RomCloud PC ou téléchargé, pilote Qualcomm),
 * version de DXVK, résolution de l'écran Windows, images par seconde au plus et qualité graphique au
 * démarrage (appliquée par RomCloud PC selon le moteur du jeu : Unity, Unreal Engine). Réglage absent : choix automatique de
 * RomCloud (comportement par défaut).
 */
object PcSettings {

    const val KEY_WINE = "wineVersion"
    const val KEY_DRIVER = "driver"
    const val KEY_DXVK = "dxvk"
    const val KEY_QUALITY = "quality"
    const val KEY_SCREEN = "screen"
    const val KEY_FPS = "fps"

    /** Versions de Proton de RomCloud PC (un conteneur par version). */
    val WINE_VERSIONS = listOf("proton-9.0-arm64ec", "proton-9.0-x86_64")

    /**
     * Pilote Vulkan des puces Adreno : [id] est le nom du pilote dans RomCloud PC (dossier d'adrenotools,
     * « name » de meta.json pour un pilote téléchargé), [url] le paquet AdrenoTools à télécharger au
     * premier usage (null : inclus dans RomCloud PC) ; « System » : pilote Qualcomm de l'appareil.
     */
    data class Driver(val id: String, val label: String, val url: String? = null)

    const val SYSTEM_DRIVER = "System"

    private const val TURNIP_RELEASES = "https://github.com/tiagosouzacandido/AdrenoToolsTurnipDrivers/releases/download"

    val DRIVERS = listOf(
        Driver(Winlator.TURNIP, "Turnip 25.3.0 R3"),
        Driver("turnip25.3.0_R3_Gmem", "Turnip 25.3.0 R3 (Gmem)"),
        Driver("turnip25.1.0", "Turnip 25.1.0"),
        Driver("Turnip - Apr 13, 2026 - 41fecb5", "Turnip 26.1.0", "$TURNIP_RELEASES/20260413/turnip_20260413_26.1.0-devel_vk1.4.348_41fecb5.zip"),
        Driver("Turnip - Jul 13, 2026 - 079d247", "Turnip 26.2.0", "$TURNIP_RELEASES/20260713/turnip_20260713_26.2.0-devel_vk1.4.354_079d247.zip"),
        Driver("Turnip - Aug 25, 2026 - 1eb29b2", "Turnip 26.3.0", "$TURNIP_RELEASES/20260825/turnip_20260825_26.3.0-devel_vk1.4.359_1eb29b2.zip"),
        Driver(SYSTEM_DRIVER, "Qualcomm"),
    )

    /** Versions de DXVK de RomCloud PC (Winlator Cmod 13.1). */
    val DXVK_VERSIONS = listOf(
        "1.7.1", "1.10.1", "1.10.3", "async-1.10.3", "2.2.116", "2.3.1", "2.3.1-arm64ec-gplasync",
        "2.4-gplasync", "2.4-24-gplasync", "2.4.1", "2.4.1-gplasync",
    )

    /** Qualité graphique au démarrage (moteurs Unity et Unreal Engine), de la plus basse à la plus haute. */
    val QUALITIES = listOf("minimum", "verylow", "low", "medium", "high")

    /** Hauteurs de l'écran Windows (lignes), largeur au format de l'écran de l'appareil. */
    val SCREEN_HEIGHTS = listOf("360", "480", "540", "720", "900", "1080")

    /** Images par seconde au plus (limite de DXVK) : moins de travail pour le processeur graphique. */
    val FRAMERATES = listOf("30", "45", "60")

    /** Liste d'un réglage dans la fiche du jeu : clé, titre, choix (valeur null : automatique, libellé). */
    data class Choice(val key: String, val title: String, val options: List<Pair<String?, String>>) {
        /** Libellé de la valeur [value] (automatique si elle est absente ou inconnue). */
        fun labelOf(value: String?): String = options.find { it.first == value }?.second ?: options.first().second
    }

    /** Listes de la fiche d'un jeu PC ; pilote Vulkan seulement sur Adreno ([adreno]). */
    fun choices(adreno: Boolean): List<Choice> {
        val auto = null to I18n.get(R.string.pc_setting_auto)
        return listOfNotNull(
            Choice(KEY_WINE, I18n.get(R.string.pc_setting_wine), listOf(auto,
                WINE_VERSIONS[0] to I18n.get(R.string.pc_wine_arm64ec), WINE_VERSIONS[1] to I18n.get(R.string.pc_wine_x86_64))),
            if (adreno) Choice(KEY_DRIVER, I18n.get(R.string.pc_setting_driver), listOf(auto) + DRIVERS.map { driver ->
                driver.id to when {
                    driver.id == SYSTEM_DRIVER -> I18n.get(R.string.pc_driver_qualcomm)
                    driver.url != null -> I18n.get(R.string.pc_driver_download, driver.label)
                    else -> driver.label
                }
            }) else null,
            Choice(KEY_DXVK, I18n.get(R.string.pc_setting_dxvk), listOf(auto) + DXVK_VERSIONS.map { it to it }),
            Choice(KEY_SCREEN, I18n.get(R.string.pc_setting_screen), listOf(auto) + SCREEN_HEIGHTS.map { it to "${it}p" }),
            Choice(KEY_FPS, I18n.get(R.string.pc_setting_fps), listOf(auto) + FRAMERATES.map { it to I18n.get(R.string.pc_fps_value, it) }),
            Choice(KEY_QUALITY, I18n.get(R.string.pc_setting_quality), listOf(auto,
                "minimum" to I18n.get(R.string.pc_quality_minimum), "verylow" to I18n.get(R.string.pc_quality_very_low),
                "low" to I18n.get(R.string.pc_quality_low), "medium" to I18n.get(R.string.pc_quality_medium), "high" to I18n.get(R.string.pc_quality_high))),
        )
    }

    /**
     * Réglages de RomCloud PC (section « Extra Data » du raccourci) d'après les réglages [chosen] du jeu,
     * ajoutés à ceux de l'appareil [base] : Proton (« wineVersion »), pilote (« graphicsDriverConfig » et,
     * pour un pilote téléchargé, « adrenotoolsDriverUrl »), DXVK (« dxwrapperConfig »), résolution
     * (« screenSize », au format de celle de l'appareil), images par seconde (« romcloudFramerate »,
     * et dans « dxwrapperConfig » si DXVK est choisi, pour Winlator Cmod) et qualité
     * (« romcloudQuality »). Valeur inconnue (version retirée de RomCloud) : ignorée.
     */
    fun apply(base: Map<String, String>, chosen: Map<String, String>): Map<String, String> {
        val result = LinkedHashMap(base)
        chosen[KEY_WINE]?.takeIf { it in WINE_VERSIONS }?.let { result[KEY_WINE] = it }
        chosen[KEY_DRIVER]?.let { id -> DRIVERS.find { it.id == id } }?.let { driver ->
            val turnip = driver.id != SYSTEM_DRIVER
            result["graphicsDriverConfig"] = "version=${driver.id};blacklistedExtensions=;maxDeviceMemory=0;adrenotoolsTurnip=${if (turnip) 1 else 0};frameSync=Normal"
            driver.url?.let { result["adrenotoolsDriverUrl"] = it }
        }
        val fps = chosen[KEY_FPS]?.takeIf { it in FRAMERATES }
        chosen[KEY_DXVK]?.takeIf { it in DXVK_VERSIONS }?.let {
            result["dxwrapper"] = "dxvk"
            result["dxwrapperConfig"] = "version=$it,framerate=${fps ?: 0},maxDeviceMemory=0,async=0,asyncCache=0,vkd3dVersion=2.12-0,vkd3dLevel=12_1"
        }
        fps?.let { result["romcloudFramerate"] = it }
        chosen[KEY_SCREEN]?.takeIf { it in SCREEN_HEIGHTS }?.let { result["screenSize"] = screenSize(base["screenSize"], it.toInt()) }
        chosen[KEY_QUALITY]?.takeIf { it in QUALITIES }?.let { result["romcloudQuality"] = it }
        return result
    }

    /** Résolution de [height] lignes au format de [device] (« 1600x720 », 16:9 sinon), largeur paire. */
    private fun screenSize(device: String?, height: Int): String {
        val size = device?.split('x')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 && it[1] > 0 }
        val ratio = size?.let { it[0].toDouble() / it[1] } ?: (16.0 / 9)
        return "${(height * ratio).roundToInt() / 2 * 2}x$height"
    }
}
