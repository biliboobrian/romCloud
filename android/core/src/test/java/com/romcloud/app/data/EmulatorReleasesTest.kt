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
    fun `variante d'Azahar et APK absent`() {
        val azahar = arrayOf("azahar-android-googleplay-2126.2.apk", "azahar-android-vanilla-2126.2.apk", "azahar-2126.2-windows.zip")
        assertEquals("azahar-android-vanilla-2126.2.apk", pick("org.azahar_emu.azahar", 34, *azahar))
        assertEquals("azahar-android-googleplay-2126.2.apk", pick("io.github.lime3ds.android", 34, *azahar))
        assertNull(pick("org.azahar_emu.azahar", 34, "azahar-2126.2-windows.zip"))
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
    fun `site du gestionnaire de versions`() {
        assertEquals("git.eden-emu.dev", EmulatorReleases.SOURCES.getValue("dev.eden.eden_emulator").host)
        assertEquals("github.com", EmulatorReleases.SOURCES.getValue("com.flycast.emulator").host)
        assertTrue(EmulatorReleases.SOURCES.values.all { it.api.startsWith("https://") })
    }
}
