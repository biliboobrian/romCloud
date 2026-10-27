package com.romcloud.app.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PcSettingsTest {

    private val base = mapOf("screenSize" to "1600x720", "graphicsDriverConfig" to "version=${Winlator.TURNIP};adrenotoolsTurnip=1")

    @Test
    fun sansReglagesRienNeChange() {
        assertEquals(base, PcSettings.apply(base, emptyMap()))
    }

    @Test
    fun reglagesAppliques() {
        val chosen = mapOf(
            PcSettings.KEY_WINE to "proton-9.0-x86_64",
            PcSettings.KEY_DRIVER to "Turnip - Aug 25, 2026 - 1eb29b2",
            PcSettings.KEY_DXVK to "2.4.1",
            PcSettings.KEY_QUALITY to "low",
        )
        val result = PcSettings.apply(base, chosen)
        assertEquals("1600x720", result["screenSize"])
        assertEquals("proton-9.0-x86_64", result["wineVersion"])
        assertEquals("version=Turnip - Aug 25, 2026 - 1eb29b2;blacklistedExtensions=;maxDeviceMemory=0;adrenotoolsTurnip=1;frameSync=Normal", result["graphicsDriverConfig"])
        assertEquals(PcSettings.DRIVERS.first { it.id == chosen[PcSettings.KEY_DRIVER] }.url, result["adrenotoolsDriverUrl"])
        assertEquals("dxvk", result["dxwrapper"])
        assertEquals("version=2.4.1,framerate=0,maxDeviceMemory=0,async=0,asyncCache=0,vkd3dVersion=2.12-0,vkd3dLevel=12_1", result["dxwrapperConfig"])
        assertEquals("low", result["romcloudQuality"])
    }

    @Test
    fun piloteQualcommEtPiloteInclus() {
        val qualcomm = PcSettings.apply(base, mapOf(PcSettings.KEY_DRIVER to PcSettings.SYSTEM_DRIVER))
        assertEquals("version=System;blacklistedExtensions=;maxDeviceMemory=0;adrenotoolsTurnip=0;frameSync=Normal", qualcomm["graphicsDriverConfig"])
        assertFalse(qualcomm.containsKey("adrenotoolsDriverUrl"))
        assertFalse(PcSettings.apply(base, mapOf(PcSettings.KEY_DRIVER to "turnip25.1.0")).containsKey("adrenotoolsDriverUrl"))
    }

    @Test
    fun resolutionAuFormatDeLAppareil() {
        assertEquals("2400x1080", PcSettings.apply(base, mapOf(PcSettings.KEY_SCREEN to "1080"))["screenSize"])
        assertEquals("1066x480", PcSettings.apply(base, mapOf(PcSettings.KEY_SCREEN to "480"))["screenSize"])
        assertEquals("960x540", PcSettings.apply(emptyMap(), mapOf(PcSettings.KEY_SCREEN to "540"))["screenSize"])
    }

    @Test
    fun imagesParSeconde() {
        assertEquals("30", PcSettings.apply(base, mapOf(PcSettings.KEY_FPS to "30"))["romcloudFramerate"])
        val withDxvk = PcSettings.apply(base, mapOf(PcSettings.KEY_FPS to "45", PcSettings.KEY_DXVK to "2.4.1"))
        assertEquals("version=2.4.1,framerate=45,maxDeviceMemory=0,async=0,asyncCache=0,vkd3dVersion=2.12-0,vkd3dLevel=12_1", withDxvk["dxwrapperConfig"])
        assertEquals("640x360", PcSettings.apply(mapOf("screenSize" to "1280x720"), mapOf(PcSettings.KEY_SCREEN to "360"))["screenSize"])
    }

    @Test
    fun shadersEtProcesseur() {
        val result = PcSettings.apply(base, mapOf(PcSettings.KEY_SHADERS to "async", PcSettings.KEY_CPU to "performance"))
        assertEquals("async", result["romcloudShaders"])
        assertEquals("performance", result["romcloudCpu"])
    }

    @Test
    fun composantsTelechargesOuInclus() {
        // DXVK téléchargé : version du paquet (versionName-versionCode) et son adresse.
        val dxvk = PcSettings.DXVK.first { it.url != null }
        val withDxvk = PcSettings.apply(base, mapOf(PcSettings.KEY_DXVK to dxvk.version))
        assertEquals("version=${dxvk.version},framerate=0,maxDeviceMemory=0,async=0,asyncCache=0,vkd3dVersion=2.12-0,vkd3dLevel=12_1", withDxvk["dxwrapperConfig"])
        assertEquals(dxvk.url, withDxvk["romcloudDxvkUrl"])
        // DXVK inclus : pas d'adresse.
        assertFalse(PcSettings.apply(base, mapOf(PcSettings.KEY_DXVK to "2.4.1")).containsKey("romcloudDxvkUrl"))
        val vkd3d = PcSettings.VKD3D.first { it.url != null }
        val withVkd3d = PcSettings.apply(base, mapOf(PcSettings.KEY_VKD3D to vkd3d.version))
        assertEquals(vkd3d.version, withVkd3d["romcloudVkd3d"])
        assertEquals(vkd3d.url, withVkd3d["romcloudVkd3dUrl"])
        assertEquals("2.14.1-0", PcSettings.apply(base, mapOf(PcSettings.KEY_VKD3D to "2.14.1-0"))["romcloudVkd3d"])
        val box = PcSettings.BOX64.first { it.type == "wowbox64" && it.url != null }
        val withBox = PcSettings.apply(base, mapOf(PcSettings.KEY_BOX64 to "wowbox64:${box.version}"))
        assertEquals("wowbox64:${box.version}", withBox["romcloudBox64"])
        assertEquals(box.url, withBox["romcloudBox64Url"])
        assertEquals("box64:0.3.7", PcSettings.apply(base, mapOf(PcSettings.KEY_BOX64 to "box64:0.3.7"))["romcloudBox64"])
        val fex = PcSettings.FEXCORE.first { it.url != null }
        assertEquals(fex.url, PcSettings.apply(base, mapOf(PcSettings.KEY_FEXCORE to fex.version))["romcloudFexcoreUrl"])
        // Versions incluses jamais doublées par un paquet téléchargé, identifiants de pilotes uniques.
        for (list in listOf(PcSettings.DXVK, PcSettings.VKD3D, PcSettings.BOX64, PcSettings.FEXCORE)) {
            assertEquals(list.size, list.map { "${it.type}:${it.version}" }.toSet().size)
        }
        assertEquals(PcSettings.DRIVERS.size, PcSettings.DRIVERS.map { it.id }.toSet().size)
    }

    @Test
    fun directX12() {
        assertEquals("vkd3d", PcSettings.apply(base, mapOf(PcSettings.KEY_DX12 to "vkd3d"))["romcloudDx12"])
        assertEquals("wine", PcSettings.apply(base, mapOf(PcSettings.KEY_DX12 to "wine"))["romcloudDx12"])
    }

    @Test
    fun valeursInconnuesIgnorees() {
        val chosen = mapOf(PcSettings.KEY_WINE to "proton-1.0", PcSettings.KEY_DRIVER to "inconnu", PcSettings.KEY_DXVK to "9.9",
            PcSettings.KEY_QUALITY to "ultra", PcSettings.KEY_SCREEN to "4320", PcSettings.KEY_FPS to "1000",
            PcSettings.KEY_SHADERS to "x", PcSettings.KEY_CPU to "turbo", PcSettings.KEY_DX12 to "dx13",
            PcSettings.KEY_VKD3D to "9.9", PcSettings.KEY_BOX64 to "box64:9.9", PcSettings.KEY_FEXCORE to "1")
        assertEquals(base, PcSettings.apply(base, chosen))
    }
}
