package com.romcloud.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmulatorReleasesTest {

    private fun release(vararg names: String) = Json.parseToJsonElement(
        """{ "tag_name": "v1", "assets": [${names.joinToString { """{ "name": "$it", "size": 1 }""" }}] }""",
    ).jsonObject

    private fun pick(packageName: String, sdk: Int, vararg names: String) =
        EmulatorReleases.pickAsset(release(*names), EmulatorReleases.SOURCES.getValue(packageName).asset, sdk)
            ?.get("name")?.jsonPrimitive?.content

    @Test
    fun `variantes d'Eden selon le paquet`() {
        val eden = arrayOf(
            "Eden-Android-v0.2.1-chromeos.apk", "Eden-Android-v0.2.1-legacy.apk",
            "Eden-Android-v0.2.1-optimized.apk", "Eden-Android-v0.2.1-standard.apk",
        )
        assertEquals("Eden-Android-v0.2.1-standard.apk", pick("dev.eden.eden_emulator", 34, *eden))
        assertEquals("Eden-Android-v0.2.1-legacy.apk", pick("dev.legacy.eden_emulator", 34, *eden))
        assertEquals("Eden-Android-v0.2.1-optimized.apk", pick("com.miHoYo.Yuanshen", 34, *eden))
    }

    @Test
    fun `APK de Flycast et APK absent`() {
        val flycast = arrayOf("flycast-2.5.apk", "flycast-win64-2.5.zip")
        assertEquals("flycast-2.5.apk", pick("com.flycast.emulator", 34, *flycast))
        assertNull(pick("com.flycast.emulator", 34, "flycast-win64-2.5.zip"))
    }

    @Test
    fun `pas d'emulateur externe des consoles de LibretroDroid`() {
        // 3DS, DS, Game Boy : cœurs Citra, melonDS, mGBA… du moteur intégré.
        for (pkg in listOf("org.azahar_emu.azahar", "io.github.lime3ds.android", "me.magnum.melonds", "com.sky.SkyEmu")) {
            assertNull(pkg, EmulatorReleases.SOURCES[pkg])
        }
    }

    @Test
    fun `variante d'ARMSX2 selon la version d'Android`() {
        val armsx2 = arrayOf(
            "ARMSX2-2.8.2-a11-armv8.2-sdk30.apk", "ARMSX2-2.8.2-a13-armv8.2-sdk33.apk",
            "ARMSX2-2.8.2-a15-armv8.2-sdk35.apk", "ARMSX2-2.8.2-legacy-armv8.0-sdk26.apk",
        )
        assertEquals("ARMSX2-2.8.2-a15-armv8.2-sdk35.apk", pick("com.armsx2", 36, *armsx2))
        assertEquals("ARMSX2-2.8.2-a13-armv8.2-sdk33.apk", pick("com.armsx2", 34, *armsx2))
        assertEquals("ARMSX2-2.8.2-legacy-armv8.0-sdk26.apk", pick("com.armsx2", 28, *armsx2))
        assertNull(pick("com.armsx2", 24, *armsx2))
    }

    @Test
    fun `RomCloud PC, APK de la Release de RomCloud`() {
        assertEquals("RomCloud-PC-1.63.0.apk", pick("com.romcloud.wine", 34, "RomCloud-1.63.0.apk", "RomCloud-TV-1.63.0.apk", "RomCloud-PC-1.63.0.apk"))
        assertEquals(false, EmulatorReleases.SOURCES.getValue("com.romcloud.wine").playStore)
    }

    @Test
    fun `Winlator Cmod, APK de GitHub hors Play Store`() {
        assertEquals("Winlator-Cmod-v13.1.1.apk", pick("com.winlator.cmod", 34, "cmod-hotfix-checklist-08-07-2025.png", "Winlator-Cmod-v13.1.1.apk"))
        assertEquals(false, EmulatorReleases.SOURCES.getValue("com.winlator.cmod").playStore)
        assertEquals("13.1", EmulatorReleases.versionOf("cmod_v13.1"))
        assertEquals("0.0.3", EmulatorReleases.versionOf("v0.0.3"))
        assertEquals("2122.1", EmulatorReleases.versionOf("2122.1"))
    }

    @Test
    fun `site du gestionnaire de versions`() {
        assertEquals("git.eden-emu.dev", EmulatorReleases.SOURCES.getValue("dev.eden.eden_emulator").host)
        assertEquals("github.com", EmulatorReleases.SOURCES.getValue("com.flycast.emulator").host)
        // Sources du miroir du serveur (SOURCES de emulator-mirror.js) : Eden et ses variantes, même source.
        assertEquals("eden", EmulatorReleases.SOURCES.getValue("dev.legacy.eden_emulator").mirror)
        assertEquals("vita3k-android", EmulatorReleases.SOURCES.getValue("org.vita3k.emulator").mirror)
        assertTrue(EmulatorReleases.SOURCES.values.all { it.mirror.matches(Regex("[a-z0-9-]+")) })
    }
}
