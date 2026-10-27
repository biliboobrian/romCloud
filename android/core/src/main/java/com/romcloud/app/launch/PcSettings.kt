package com.romcloud.app.launch

import com.romcloud.app.I18n
import com.romcloud.core.R
import kotlin.math.roundToInt

/**
 * Réglages d'un jeu PC choisis dans sa fiche (enregistrés dans le profil, [com.romcloud.app.data.Account.gameSettings]) :
 * version de Proton, pilote Vulkan (Turnip inclus dans RomCloud PC ou téléchargé, pilote Qualcomm),
 * versions de DXVK, VKD3D-Proton, Box64 et FEXCore (incluses, ou téléchargées au premier usage :
 * [PcCatalog]), résolution de l'écran Windows, images par seconde au plus et qualité graphique au
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
    const val KEY_SHADERS = "shaders"
    const val KEY_CPU = "cpu"
    const val KEY_DX12 = "dx12"
    const val KEY_VKD3D = "vkd3d"
    const val KEY_BOX64 = "box64"
    const val KEY_FEXCORE = "fexcore"

    /** DirectX 12 : VKD3D-Proton, ou celui de Wine (automatique : VKD3D-Proton pour les jeux DirectX 12). */
    val DX12_MODES = listOf("vkd3d", "wine")

    /** Versions de Proton de RomCloud PC (un conteneur par version). */
    val WINE_VERSIONS = listOf("proton-9.0-arm64ec", "proton-9.0-x86_64")

    /**
     * Pilote Vulkan des puces Adreno : [id] est le nom du pilote dans RomCloud PC (dossier d'adrenotools,
     * « name » de meta.json pour un pilote téléchargé), [url] le paquet AdrenoTools à télécharger au
     * premier usage (null : inclus dans RomCloud PC) ; « System » : pilote Qualcomm de l'appareil.
     */
    data class Driver(val id: String, val label: String, val url: String? = null)

    const val SYSTEM_DRIVER = "System"

    /** Pilotes inclus dans RomCloud PC (Winlator Cmod 13.1). */
    private val BUILTIN_DRIVERS = listOf(
        Driver(Winlator.TURNIP, "Turnip 25.3.0 R3"),
        Driver("turnip25.3.0_R3_Gmem", "Turnip 25.3.0 R3 (Gmem)"),
        Driver("turnip25.1.0", "Turnip 25.1.0"),
        Driver("v805", "Qualcomm 805"),
        Driver("v762", "Qualcomm 762"),
    )

    /** Pilotes : inclus, puis téléchargés au premier usage (du plus récent au plus ancien), puis celui de l'appareil. */
    val DRIVERS: List<Driver> = BUILTIN_DRIVERS + PcCatalog.DRIVERS.filter { d -> BUILTIN_DRIVERS.none { it.id == d.id } } +
        Driver(SYSTEM_DRIVER, "Qualcomm")

    /**
     * Composant de Winlator : [type] (dxvk, vkd3d, box64, wowbox64, fexcore), [version] telle que RomCloud PC
     * l'attend (« 2.4.1 » inclus, « 3.1.1-0 » téléchargé : versionName-versionCode du paquet), [name] affiché,
     * [url] du paquet .wcp à télécharger (null : inclus dans RomCloud PC).
     */
    data class Content(val type: String, val version: String, val name: String, val url: String? = null)

    private fun builtin(type: String, vararg versions: String) = versions.map { Content(type, it, it.removeSuffix("-0")) }

    /** Composants [type] : inclus, puis téléchargés (versions déjà incluses écartées). */
    private fun contents(type: String, included: List<Content>): List<Content> =
        included + PcCatalog.CONTENTS.filter { c -> c.type == type && included.none { it.version == c.version } }

    /** Versions de DXVK : celles de RomCloud PC (Winlator Cmod 13.1), puis téléchargées. */
    val DXVK = contents("dxvk", builtin("dxvk", "1.7.1", "1.10.1", "1.10.3", "async-1.10.3", "2.2.116", "2.3.1",
        "2.3.1-arm64ec-gplasync", "2.4-gplasync", "2.4-24-gplasync", "2.4.1", "2.4.1-gplasync"))
    val DXVK_VERSIONS: List<String> get() = DXVK.map { it.version }

    /** Versions de VKD3D-Proton (DirectX 12). */
    val VKD3D = contents("vkd3d", builtin("vkd3d", "2.14.1-0", "2.13-0", "2.12-0", "2.8-0"))

    /** Box64 (Proton x86_64) et WOWBox64 (Proton arm64ec) : valeur « type:version ». */
    val BOX64 = contents("box64", builtin("box64", "0.3.7", "0.3.6", "0.3.2")) +
        contents("wowbox64", builtin("wowbox64", "0.3.7", "0.3.4"))

    /** Versions de FEXCore (Proton arm64ec). */
    val FEXCORE = contents("fexcore", builtin("fexcore", "2507", "2505"))

    /** Qualité graphique au démarrage (moteurs Unity et Unreal Engine), de la plus basse à la plus haute. */
    val QUALITIES = listOf("minimum", "verylow", "low", "medium", "high")

    /** Hauteurs de l'écran Windows (lignes), largeur au format de l'écran de l'appareil. */
    val SCREEN_HEIGHTS = listOf("360", "480", "540", "720", "900", "1080")

    /** Images par seconde au plus (limite de DXVK) : moins de travail pour le processeur graphique. */
    val FRAMERATES = listOf("30", "45", "60")

    /** Émulation du processeur plus rapide que celle par défaut (compatibilité), au risque de plantages. */
    val CPU_PRESETS = listOf("intermediate", "performance")

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
            Choice(KEY_DXVK, I18n.get(R.string.pc_setting_dxvk), listOf(auto) + DXVK.map { it.version to contentLabel(it) }),
            Choice(KEY_DX12, I18n.get(R.string.pc_setting_dx12), listOf(auto,
                "vkd3d" to I18n.get(R.string.pc_dx12_vkd3d), "wine" to I18n.get(R.string.pc_dx12_wine))),
            Choice(KEY_VKD3D, I18n.get(R.string.pc_setting_vkd3d), listOf(auto) + VKD3D.map { it.version to contentLabel(it) }),
            Choice(KEY_BOX64, I18n.get(R.string.pc_setting_box64), listOf(auto) + BOX64.map { boxValue(it) to contentLabel(it) }),
            Choice(KEY_FEXCORE, I18n.get(R.string.pc_setting_fexcore), listOf(auto) + FEXCORE.map { it.version to contentLabel(it) }),
            Choice(KEY_SCREEN, I18n.get(R.string.pc_setting_screen), listOf(auto) + SCREEN_HEIGHTS.map { it to "${it}p" }),
            Choice(KEY_FPS, I18n.get(R.string.pc_setting_fps), listOf(auto) + FRAMERATES.map { it to I18n.get(R.string.pc_fps_value, it) }),
            Choice(KEY_SHADERS, I18n.get(R.string.pc_setting_shaders), listOf(null to I18n.get(R.string.pc_shaders_normal),
                "async" to I18n.get(R.string.pc_shaders_async))),
            Choice(KEY_CPU, I18n.get(R.string.pc_setting_cpu), listOf(null to I18n.get(R.string.pc_cpu_compatibility),
                "intermediate" to I18n.get(R.string.pc_cpu_intermediate), "performance" to I18n.get(R.string.pc_cpu_performance))),
            Choice(KEY_QUALITY, I18n.get(R.string.pc_setting_quality), listOf(auto,
                "minimum" to I18n.get(R.string.pc_quality_minimum), "verylow" to I18n.get(R.string.pc_quality_very_low),
                "low" to I18n.get(R.string.pc_quality_low), "medium" to I18n.get(R.string.pc_quality_medium), "high" to I18n.get(R.string.pc_quality_high))),
        )
    }

    /** Libellé d'un composant : version, avec le Proton visé (Box64) et « (téléchargé) » s'il n'est pas inclus. */
    private fun contentLabel(c: Content): String {
        val name = when (c.type) {
            "box64" -> "Box64 ${c.name} (Proton x86_64)"
            "wowbox64" -> "WOWBox64 ${c.name} (Proton arm64ec)"
            else -> c.name
        }
        return if (c.url != null) I18n.get(R.string.pc_driver_download, name) else name
    }

    private fun boxValue(c: Content) = "${c.type}:${c.version}"

    /**
     * Réglages de RomCloud PC (section « Extra Data » du raccourci) d'après les réglages [chosen] du jeu,
     * ajoutés à ceux de l'appareil [base] : Proton (« wineVersion »), pilote (« graphicsDriverConfig » et,
     * pour un pilote téléchargé, « adrenotoolsDriverUrl »), DXVK (« dxwrapperConfig »), résolution
     * (« screenSize », au format de celle de l'appareil), images par seconde (« romcloudFramerate »,
     * et dans « dxwrapperConfig » si DXVK est choisi, pour Winlator Cmod) et qualité
     * (« romcloudQuality »), shaders en arrière-plan (« romcloudShaders ») et émulation du processeur
     * (« romcloudCpu »), DirectX 12 (« romcloudDx12 »), composants téléchargés (« romcloudDxvkUrl »,
     * « romcloudVkd3d », « romcloudBox64 », « romcloudFexcore » et leurs adresses), appliqués par RomCloud PC. Valeur inconnue (version retirée de RomCloud) : ignorée.
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
        chosen[KEY_DXVK]?.let { v -> DXVK.find { it.version == v } }?.let {
            result["dxwrapper"] = "dxvk"
            result["dxwrapperConfig"] = "version=${it.version},framerate=${fps ?: 0},maxDeviceMemory=0,async=0,asyncCache=0,vkd3dVersion=2.12-0,vkd3dLevel=12_1"
            it.url?.let { url -> result["romcloudDxvkUrl"] = url }
        }
        chosen[KEY_VKD3D]?.let { v -> VKD3D.find { it.version == v } }?.let {
            result["romcloudVkd3d"] = it.version
            it.url?.let { url -> result["romcloudVkd3dUrl"] = url }
        }
        chosen[KEY_BOX64]?.let { v -> BOX64.find { boxValue(it) == v } }?.let {
            result["romcloudBox64"] = boxValue(it)
            it.url?.let { url -> result["romcloudBox64Url"] = url }
        }
        chosen[KEY_FEXCORE]?.let { v -> FEXCORE.find { it.version == v } }?.let {
            result["romcloudFexcore"] = it.version
            it.url?.let { url -> result["romcloudFexcoreUrl"] = url }
        }
        fps?.let { result["romcloudFramerate"] = it }
        chosen[KEY_SHADERS]?.takeIf { it == "async" }?.let { result["romcloudShaders"] = it }
        chosen[KEY_CPU]?.takeIf { it in CPU_PRESETS }?.let { result["romcloudCpu"] = it }
        chosen[KEY_DX12]?.takeIf { it in DX12_MODES }?.let { result["romcloudDx12"] = it }
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
