package com.romcloud.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EmulatorConfigTest {

    /** Fichier commun avec l'application Windows (copié dans les assets au build). */
    private val config = EmulatorConfig.parse(File("../../desktop/src/renderer/emulator-config/${EmulatorConfig.ASSET}").readText())

    @Test
    fun `valeur d'Eden remplacee avec son default`() {
        val text = "[Renderer]\nbackend\\default=true\nbackend=1\nresolution_setup\\default=true\nresolution_setup=3\n\n[System]\nlanguage_index=1\n"
        var out = IniText.setValue(text, "Renderer", "resolution_setup", "6", IniText.Style.EDEN)
        assertEquals("[Renderer]\nbackend\\default=true\nbackend=1\nresolution_setup\\default=false\nresolution_setup=6\n\n[System]\nlanguage_index=1\n", out)
        // Clé absente : ajoutée à la fin de la section, avant la ligne vide ; section absente : à la fin.
        out = IniText.setValue(out, "Renderer", "use_vsync", "2", IniText.Style.EDEN)
        assertTrue(out.contains("resolution_setup=6\nuse_vsync\\default=false\nuse_vsync=2\n\n[System]"))
        out = IniText.setValue(out, "Audio", "volume", "80", IniText.Style.EDEN)
        assertTrue(out.endsWith("language_index=1\n\n[Audio]\nvolume\\default=false\nvolume=80\n"))
        assertEquals("6", IniText.parse(out)["Renderer/resolution_setup"])
        assertNull(IniText.parse(out)["Renderer/backend\\default"])
    }

    @Test
    fun `valeur de Dolphin avec espaces et fins de ligne Windows`() {
        val text = "[Settings]\r\nInternalResolution = 1\r\nMSAA = 0x00000001\r\n[Hardware]\r\nVSync = False\r\n"
        var out = IniText.setValue(text, "Settings", "InternalResolution", "3", IniText.Style.SPACED)
        out = IniText.setValue(out, "hardware", "VSync", "True", IniText.Style.SPACED)
        assertEquals("[Settings]\r\nInternalResolution = 3\r\nMSAA = 0x00000001\r\n[Hardware]\r\nVSync = True\r\n", out)
        assertEquals("[Display]\nFullscreen = True\n", IniText.setValue("", "Display", "Fullscreen", "True", IniText.Style.SPACED))
    }

    @Test
    fun `reglages d'Android et paquets des emulateurs`() {
        val eden = config.forPackage("dev.eden.eden_emulator")!!
        assertEquals(IniText.Style.EDEN, eden.style)
        assertEquals(eden, config.forPackage("dev.eden.eden_emulator.nightly"))
        assertEquals(eden, config.forPackage("dev.legacy.eden_emulator"))
        assertNull(config.forPackage("dev.eden.eden_emulatorx"))
        assertNull(config.forPackage("org.ppsspp.ppsspp"))
        assertEquals("config/config.ini", config.files["qt-config.ini"])
        // Android : Vulkan seulement, pas de mode plein écran de Windows.
        val keys = eden.fields.map { it.key }
        assertTrue("resolution_setup" in keys && "language_index" in keys && "fullscreen_mode" !in keys)
        assertEquals(listOf("1"), eden.fields.first { it.key == "backend" }.options.map { it.value })
        assertTrue(eden.note.fr.contains("{name}"))

        val dolphin = config.forPackage("org.dolphinemu.dolphinemu")!!
        assertEquals(IniText.Style.SPACED, dolphin.style)
        assertEquals(listOf("Dolphin.ini", "GFX.ini"), dolphin.fileNames)
        assertEquals(listOf("Vulkan", "OGL"), dolphin.fields.first { it.key == "GFXBackend" }.options.map { it.value })
        assertTrue(dolphin.fields.none { it.key == "Fullscreen" || it.key == "ConfirmStop" })
        // Valeur du fichier ramenée à celle d'une option (« 0x00000004 » : 4).
        val msaa = dolphin.fields.first { it.key == "MSAA" }
        assertEquals("4", EmulatorConfig.valueOf(msaa, "0x00000004"))
        assertEquals("True", EmulatorConfig.valueOf(dolphin.fields.first { it.key == "VSync" }, "true"))
        assertNull(EmulatorConfig.valueOf(msaa, null))
    }
}
