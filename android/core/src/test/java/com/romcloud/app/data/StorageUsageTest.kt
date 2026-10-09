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
}
