package com.romcloud.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class ExternalEmulatorFilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `cles recopiees seulement quand elles changent`() {
        val from = tmp.newFile("prod.keys").apply { writeText("prod_key = 01") }
        val to = File(tmp.root, "Download/RomCloud/Switch/prod.keys")
        assertNotNull(ExternalEmulatorFiles.copyIfNewer(from, to))
        assertEquals("prod_key = 01", to.readText())
        assertNull(ExternalEmulatorFiles.copyIfNewer(from, to)) // déjà à jour
        from.writeText("prod_key = 0123")
        assertNotNull(ExternalEmulatorFiles.copyIfNewer(from, to)) // taille différente
    }

    @Test
    fun `firmware en dossier recompresse en zip pour Eden`() {
        val folder = tmp.newFolder("switch", "firmware")
        File(folder, "a.nca").writeText("A")
        File(folder, "sub").mkdirs()
        File(folder, "sub/b.nca").writeText("B")
        val zip = File(tmp.root, "out/firmware.zip")
        assertNotNull(ExternalEmulatorFiles.zipIfNewer(folder, zip))
        ZipFile(zip).use { z -> assertEquals(setOf("a.nca", "sub/b.nca"), z.entries().toList().map { it.name }.toSet()) }
        assertNull(ExternalEmulatorFiles.zipIfNewer(folder, zip)) // rien de nouveau
        assertNull(ExternalEmulatorFiles.zipIfNewer(tmp.newFolder("vide"), File(tmp.root, "vide.zip")))
    }
}
