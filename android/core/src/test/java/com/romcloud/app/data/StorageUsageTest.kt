package com.romcloud.app.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StorageUsageTest {

    @Test
    fun `espace de la partition`() {
        val usage = StorageUsage(free = 25, total = 100)
        assertEquals(0.75f, usage.usedFraction, 0.001f)
        assertEquals(false, usage.low)
        assertEquals(true, StorageUsage(free = 9, total = 100).low)
        assertEquals(false, StorageUsage().known)
        assertEquals(0f, StorageUsage().usedFraction, 0f)
    }

    @Test
    fun `taille d'un dossier, sous-dossiers compris`() {
        val dir = Files.createTempDirectory("romcloud-usage").toFile()
        try {
            File(dir, "a.chd").writeBytes(ByteArray(100))
            File(dir, "sous").mkdirs()
            File(dir, "sous/b.chd").writeBytes(ByteArray(23))
            assertEquals(123L, dirSize(dir))
            assertEquals(0L, dirSize(File(dir, "absent")))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `page du stockage, taille d'un fichier ou d'un dossier, totaux par catégorie`() {
        val dir = Files.createTempDirectory("romcloud-storage").toFile()
        try {
            File(dir, "a.srm").writeBytes(ByteArray(10))
            File(dir, "x.history").mkdirs()
            File(dir, "x.history/1.state").writeBytes(ByteArray(5))
            assertEquals(15L, StorageScanner.sizeOf(dir))
            assertEquals(10L, StorageScanner.sizeOf(File(dir, "a.srm")))
            assertEquals(0L, StorageScanner.sizeOf(File(dir, "absent")))
        } finally {
            dir.deleteRecursively()
        }
        val system = GameSystem(id = "snes", name = "SNES", shortname = "snes", folder = "snes")
        val details = StorageDetails(
            listOf(
                SystemStorage(system, mapOf(StorageKind.GAMES to 100, StorageKind.SAVES to 5)),
                SystemStorage(system.copy(id = "nes"), mapOf(StorageKind.GAMES to 20, StorageKind.BIOS to 1)),
            ),
        )
        assertEquals(120L, details.sum(StorageKind.GAMES))
        assertEquals(0L, details.sum(StorageKind.DRIVERS))
        assertEquals(105L, details.systems[0].total)
    }
}
