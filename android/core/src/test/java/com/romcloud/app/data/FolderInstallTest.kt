package com.romcloud.app.data

import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FolderInstallTest {

    private fun zipOf(file: File, name: String, data: ByteArray) {
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(data)
            zip.closeEntry()
        }
    }

    /** Archive xz en [blocks] blocs indépendants (comme xz -T du serveur). */
    private fun xzOf(file: File, data: ByteArray, blocks: Int) {
        XZOutputStream(file.outputStream(), LZMA2Options(6)).use { xz ->
            val step = (data.size + blocks - 1) / blocks
            for (start in data.indices step step) {
                xz.write(data, start, minOf(step, data.size - start))
                xz.endBlock()
            }
        }
    }

    private fun crcOf(data: ByteArray) = "%08x".format(CRC32().apply { update(data) }.value)

    private val mb = 1024L * 1024

    @Test
    fun `plus lourds d'abord, place pour les fichiers decompresses et la plus grosse archive`() {
        val files = listOf(
            FolderFile("a.dat", 100),
            FolderFile("big.pak", 10_000, format = "xz", packedSize = 6_000),
            FolderFile("mid.pak", 5_000, zipSize = 4_000),
            FolderFile("new.pak", 50, format = "zst", packedSize = 20),
        )
        assertEquals(listOf("big.pak", "mid.pak", "a.dat", "new.pak"), FolderInstall.order(files).map { it.path })
        assertEquals("xz", files[1].archiveFormat)
        assertEquals("zip", files[2].archiveFormat) // serveur d'avant le xz
        assertEquals("zst", files[3].archiveFormat)
        assertEquals(15_150L + 6_000 + FolderInstall.SPACE_MARGIN, FolderInstall.neededBytes(files))
        // Attente : place libre insuffisante pour les fichiers pas encore écrits et l'archive suivante.
        val next = files[1]
        assertTrue(FolderInstall.mustWait(next, pendingBytes = 100 * mb, free = 100 * mb))
        assertFalse(FolderInstall.mustWait(next, pendingBytes = 100 * mb, free = 200 * mb))
        assertFalse(FolderInstall.mustWait(next, pendingBytes = 100 * mb, free = -1)) // place inconnue
    }

    @Test
    fun `archive zstd decompressee, puis supprimee, abimee refusee et supprimee`() = runBlocking {
        val dir = Files.createTempDirectory("folder-install").toFile()
        try {
            val data = ByteArray(3_000_000) { (it % 251).toByte() }
            val target = File(dir, "level1.pak")
            val archive = FolderInstall.archiveFile(target, "zst")
            assertEquals("level1.pak.zst", archive.name)
            // Comme le serveur : zstd -19 --long=27 (somme de contrôle comprise).
            ZstdOutputStream(archive.outputStream(), 19).use { zst ->
                zst.setLong(27)
                zst.setChecksum(true)
                zst.write(data)
            }
            var last = 0L
            FolderInstall.unpack(archive, "zst", target, data.size.toLong(), null) { last = it }
            assertArrayEquals(data, target.readBytes())
            assertEquals(data.size.toLong(), last)
            assertFalse(archive.exists())

            val bad = File(dir, "bad.bin")
            FolderInstall.archiveFile(bad, "zst").writeBytes(Zstd.compress(data).also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() })
            assertThrows(IOException::class.java) { runBlocking { FolderInstall.unpack(FolderInstall.archiveFile(bad, "zst"), "zst", bad, data.size.toLong(), null) } }
            assertFalse(bad.exists())
            assertFalse(FolderInstall.archiveFile(bad, "zst").exists())
            assertTrue(dir.listFiles()!!.none { it.name.endsWith(".unpack") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `archive xz en plusieurs blocs decompressee, puis supprimee`() = runBlocking {
        val dir = Files.createTempDirectory("folder-install").toFile()
        try {
            val data = ByteArray(3_000_000) { (it % 251).toByte() }
            val target = File(dir, "level1.pak")
            val archive = FolderInstall.archiveFile(target, "xz")
            assertEquals("level1.pak.xz", archive.name)
            xzOf(archive, data, blocks = 4)
            var last = 0L
            FolderInstall.unpack(archive, "xz", target, data.size.toLong(), null) { last = it }
            assertArrayEquals(data, target.readBytes())
            assertEquals(data.size.toLong(), last)
            assertFalse(archive.exists())

            // Pendant le téléchargement : un seul fil, priorité très basse, remise normale à la fin.
            val low = File(dir, "low.pak")
            xzOf(FolderInstall.archiveFile(low, "xz"), data, blocks = 4)
            val before = Thread.currentThread().priority
            val downloading = java.util.concurrent.atomic.AtomicBoolean(true)
            FolderInstall.unpack(FolderInstall.archiveFile(low, "xz"), "xz", low, data.size.toLong(), null, downloading::get) {
                if (it > data.size / 2) downloading.set(false) // téléchargement terminé en cours de route
            }
            assertArrayEquals(data, low.readBytes())
            assertEquals(before, Thread.currentThread().priority)

            // Taille annoncée différente : erreur, archive supprimée (téléchargée de nouveau).
            val bad = File(dir, "bad.bin")
            xzOf(FolderInstall.archiveFile(bad, "xz"), data, blocks = 1)
            assertThrows(IOException::class.java) { runBlocking { FolderInstall.unpack(FolderInstall.archiveFile(bad, "xz"), "xz", bad, 12, null) } }
            assertFalse(bad.exists())
            assertFalse(FolderInstall.archiveFile(bad, "xz").exists())
            assertTrue(dir.listFiles()!!.none { it.name.endsWith(".unpack") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `archive zip des compressions d'avant le xz, verifiee par son CRC`() = runBlocking {
        val dir = Files.createTempDirectory("folder-install").toFile()
        try {
            val data = ByteArray(300_000) { (it % 97).toByte() }
            val target = File(dir, "old.dat")
            zipOf(FolderInstall.archiveFile(target, "zip"), "old.dat", data)
            FolderInstall.unpack(FolderInstall.archiveFile(target, "zip"), "zip", target, data.size.toLong(), crcOf(data))
            assertArrayEquals(data, target.readBytes())

            val bad = File(dir, "bad.dat")
            zipOf(FolderInstall.archiveFile(bad, "zip"), "bad.dat", data)
            assertThrows(IOException::class.java) {
                runBlocking { FolderInstall.unpack(FolderInstall.archiveFile(bad, "zip"), "zip", bad, data.size.toLong(), "00000000") }
            }
            assertFalse(FolderInstall.archiveFile(bad, "zip").exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
