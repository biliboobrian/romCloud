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
        EmulatorReleases.pickAsset(release(*names), EmulatorReleases.FALLBACK.getValue(packageName).asset, sdk)
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
            assertNull(pkg, EmulatorReleases.FALLBACK[pkg])
        }
    }

    @Test
    fun `applications du miroir declarees par le serveur`() {
        // Réponse de « /api/emulators/android » (emulator-mirror.js, androidPackages).
        val sources = EmulatorReleases.parseSources(
            """[
              { "package": "com.armsx3", "name": "ARMSX3", "source": "armsx3", "host": "github.com",
                "asset": "ARMSX3-.*-a\\d+-armv8\\.2-sdk\\d+\\.apk", "playStore": false },
              { "package": "dev.eden.eden_emulator", "name": "Eden", "source": "eden", "host": "git.eden-emu.dev",
                "asset": "Eden-Android-.*-standard\\.apk", "playStore": true },
              { "package": "sans.motif", "source": "x", "asset": "(" }
            ]""",
        )
        assertEquals(setOf("com.armsx3", "dev.eden.eden_emulator"), sources.keys)
        val armsx3 = sources.getValue("com.armsx3")
        assertEquals(false, armsx3.playStore)
        assertEquals("armsx3", armsx3.mirror)
        assertEquals("git.eden-emu.dev", sources.getValue("dev.eden.eden_emulator").host)
        // Une variante par version d'Android : la plus récente acceptée, jamais « legacy ».
        val names = arrayOf(
            "ARMSX3-1.6.7-a11-armv8.2-sdk30.apk", "ARMSX3-1.6.7-a13-armv8.2-sdk33.apk",
            "ARMSX3-1.6.7-a15-armv8.2-sdk35.apk", "ARMSX3-1.6.7-legacy-armv8.1-sdk30.apk",
        )
        fun pick(sdk: Int) = EmulatorReleases.pickAsset(release(*names), armsx3.asset, sdk)?.get("name")?.jsonPrimitive?.content
        assertEquals("ARMSX3-1.6.7-a15-armv8.2-sdk35.apk", pick(36))
        assertEquals("ARMSX3-1.6.7-a13-armv8.2-sdk33.apk", pick(34))
        assertEquals("ARMSX3-1.6.7-a11-armv8.2-sdk30.apk", pick(30))
        assertNull(pick(28))
        assertTrue(EmulatorReleases.parseSources("{}").isEmpty())
    }

    @Test
    fun `RomCloud PC, APK de la Release de RomCloud`() {
        assertEquals("RomCloud-PC-1.63.0.apk", pick("com.romcloud.wine", 34, "RomCloud-1.63.0.apk", "RomCloud-TV-1.63.0.apk", "RomCloud-PC-1.63.0.apk"))
        assertEquals(false, EmulatorReleases.FALLBACK.getValue("com.romcloud.wine").playStore)
    }

    @Test
    fun `Winlator Cmod, APK de GitHub hors Play Store`() {
        assertEquals("Winlator-Cmod-v13.1.1.apk", pick("com.winlator.cmod", 34, "cmod-hotfix-checklist-08-07-2025.png", "Winlator-Cmod-v13.1.1.apk"))
        assertEquals(false, EmulatorReleases.FALLBACK.getValue("com.winlator.cmod").playStore)
        assertEquals("13.1", EmulatorReleases.versionOf("cmod_v13.1"))
        assertEquals("0.0.3", EmulatorReleases.versionOf("v0.0.3"))
        assertEquals("2122.1", EmulatorReleases.versionOf("2122.1"))
    }

    @Test
    fun `site du gestionnaire de versions`() {
        assertEquals("git.eden-emu.dev", EmulatorReleases.FALLBACK.getValue("dev.eden.eden_emulator").host)
        assertEquals("github.com", EmulatorReleases.FALLBACK.getValue("com.flycast.emulator").host)
        // Sources du miroir du serveur (SOURCES de emulator-mirror.js) : Eden et ses variantes, même source.
        assertEquals("eden", EmulatorReleases.FALLBACK.getValue("dev.legacy.eden_emulator").mirror)
        assertEquals("vita3k-android", EmulatorReleases.FALLBACK.getValue("org.vita3k.emulator").mirror)
        assertTrue(EmulatorReleases.FALLBACK.values.all { it.mirror.matches(Regex("[a-z0-9-]+")) })
    }
}
