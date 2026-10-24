package com.romcloud.app.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FolderInstallTest {

    private fun zipOf(file: File, name: String, data: ByteArray, stored: Boolean = false) {
        ZipOutputStream(file.outputStream()).use { zip ->
            val entry = ZipEntry(name)
            if (stored) {
                entry.method = ZipEntry.STORED
                entry.size = data.size.toLong()
                entry.compressedSize = data.size.toLong()
                entry.crc = CRC32().apply { update(data) }.value
            }
            zip.putNextEntry(entry)
            zip.write(data)
            zip.closeEntry()
        }
    }

    private fun crcOf(data: ByteArray) = "%08x".format(CRC32().apply { update(data) }.value)

    @Test
    fun `plus lourds d'abord, place pour les fichiers decompresses et les archives sur le disque`() {
        val files = listOf(
            FolderFile("a.dat", 100),
            FolderFile("big.pak", 10_000, zipSize = 6_000),
            FolderFile("mid.pak", 5_000, zipSize = 4_000),
            FolderFile("x1", 50, zipSize = 10), FolderFile("x2", 50, zipSize = 10),
            FolderFile("x3", 50, zipSize = 10), FolderFile("x4", 50, zipSize = 10),
        )
        assertEquals(listOf("big.pak", "mid.pak", "a.dat"), FolderInstall.order(files).take(3).map { it.path })
        // 15 300 décompressés + les 5 plus grosses archives (3 en téléchargement, 1 en attente, 1 en décompression).
        assertEquals(15_300L + 6_000 + 4_000 + 10 + 10 + 10, FolderInstall.neededBytes(files))
        assertEquals(100L, FolderInstall.neededBytes(listOf(FolderFile("a.dat", 100))))
    }

    @Test
    fun `archive decompressee a sa reception, verifiee, puis supprimee`() {
        val dir = Files.createTempDirectory("folder-install").toFile()
        try {
            val data = ByteArray(300_000) { (it % 97).toByte() }
            val target = File(dir, "level1.pak")
            val zip = FolderInstall.zipFile(target)
            assertEquals("level1.pak.zip", zip.name)

            zipOf(zip, "level1.pak", data)
            FolderInstall.unzip(zip, target, data.size.toLong(), crcOf(data))
            assertArrayEquals(data, target.readBytes())
            assertFalse(zip.exists())

            // Archive sans compression.
            val small = "bonjour".toByteArray()
            val txt = File(dir, "readme.txt")
            zipOf(FolderInstall.zipFile(txt), "readme.txt", small, stored = true)
            FolderInstall.unzip(FolderInstall.zipFile(txt), txt, small.size.toLong(), crcOf(small))
            assertArrayEquals(small, txt.readBytes())

            // CRC différent : erreur, archive supprimée (téléchargée de nouveau), fichier absent.
            val bad = File(dir, "bad.bin")
            zipOf(FolderInstall.zipFile(bad), "bad.bin", data)
            assertThrows(IOException::class.java) { FolderInstall.unzip(FolderInstall.zipFile(bad), bad, data.size.toLong(), "00000000") }
            assertFalse(bad.exists())
            assertFalse(FolderInstall.zipFile(bad).exists())
            assertTrue(dir.listFiles()!!.none { it.name.endsWith(".unzip") })
        } finally {
            dir.deleteRecursively()
        }
    }
}
