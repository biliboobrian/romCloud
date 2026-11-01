package com.romcloud.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ExternalEmulatorFilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun system(id: String, folder: String = id) = GameSystem(id = id, name = id, shortname = id, folder = folder, players = emptyList())

    @Test
    fun `emplacement des fichiers du serveur dans le dossier RomCloud`() {
        val folders = Folders(tmp.root)
        val switch = system("switch", folder = "nswitch")
        fun at(file: BiosFile) = folders.file(switch, file).relativeTo(tmp.root).invariantSeparatorsPath
        assertEquals("system/switch/keys/prod.keys", at(BiosFile(1, "switch/prod.keys", 1, area = "system", devicePath = "switch/keys/prod.keys")))
        assertEquals("bios/nswitch/dc/dc_boot.bin", at(BiosFile(2, "dc/dc_boot.bin", 1, area = "bios", devicePath = "dc/dc_boot.bin")))
        // Serveur sans zone : BIOS, pilotes dans leur dossier ; chemins sûrs seulement.
        assertEquals("drivers/turnip.zip", at(BiosFile(3, "drivers/turnip.zip", 1)))
        assertTrue(BiosFile(3, "drivers/turnip.zip", 1).isDriver)
        assertEquals("bios/nswitch/x/scph5501.bin", at(BiosFile(4, "../../x/scph5501.bin", 1)))
    }

    @Test
    fun `fichiers proposes aux emulateurs externes, pilote retire si l'option est coupee`() {
        val folders = Folders(tmp.root)
        File(tmp.root, "system/switch/keys").mkdirs()
        File(tmp.root, "system/switch/keys/prod.keys").writeText("prod")
        File(tmp.root, "drivers").mkdirs()
        File(tmp.root, "drivers/turnip.zip").writeText("zip")
        assertEquals(
            listOf("drivers/turnip.zip", "system/switch/keys/prod.keys"),
            ExternalEmulatorFiles.available(folders, system("switch"), drivers = true),
        )
        assertEquals(listOf("drivers/turnip.zip"), ExternalEmulatorFiles.available(folders, system("gc"), drivers = true))
        assertEquals(emptyList<String>(), ExternalEmulatorFiles.available(folders, system("snes"), drivers = true))
        assertEquals(listOf("system/switch/keys/prod.keys"), ExternalEmulatorFiles.available(folders, system("switch"), drivers = false))
        assertFalse(File(tmp.root, "drivers/turnip.zip").exists())
    }

    @Test
    fun `deplacement d'un dossier, fichiers deja presents gardes`() {
        File(tmp.root, "a/sub").mkdirs()
        File(tmp.root, "a/sub/f.bin").writeText("neuf")
        File(tmp.root, "a/g.bin").writeText("g")
        File(tmp.root, "b/sub").mkdirs()
        File(tmp.root, "b/sub/f.bin").writeText("gardé")
        assertTrue(Folders.moveTree(File(tmp.root, "a"), File(tmp.root, "b")))
        assertFalse(File(tmp.root, "a").exists())
        assertEquals("gardé", File(tmp.root, "b/sub/f.bin").readText())
        assertEquals("g", File(tmp.root, "b/g.bin").readText())
        assertTrue(Folders.moveTree(File(tmp.root, "absent"), File(tmp.root, "c")))
    }
}
