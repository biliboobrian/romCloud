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
    fun valeursInconnuesIgnorees() {
        val chosen = mapOf(PcSettings.KEY_WINE to "proton-1.0", PcSettings.KEY_DRIVER to "inconnu", PcSettings.KEY_DXVK to "9.9", PcSettings.KEY_QUALITY to "ultra")
        assertEquals(base, PcSettings.apply(base, chosen))
    }
}
