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
    fun `fichiers regroupes telecharges en une archive, avec tous ses fichiers`() {
        val files = listOf(
            FolderFile("big.pak", 200 * mb, format = "zst", packedSize = 50 * mb),
            FolderFile("a.dat", 10, format = "zst", bundle = "1", offset = 0),
            FolderFile("b.dat", 20, format = "zst", bundle = "1", offset = 10),
            FolderFile("c.dat", 30, format = "zst", bundle = "2", offset = 0),
            FolderFile("flat.txt", 5),
        )
        // a.dat absent : l'archive 1 entière ; c.dat déjà là : archive 2 pas téléchargée.
        val (todo, members) = FolderInstall.downloads(files, listOf(files[0], files[1], files[4]), mapOf("1" to FolderBundle(12), "2" to FolderBundle(9)))
        assertEquals(listOf("big.pak", ".romcloud-bundles/1", "flat.txt"), todo.map { it.path })
        val bundle = todo[1]
        assertEquals(30L, bundle.size)
        assertEquals(12L, bundle.downloadSize)
        assertEquals("1", bundle.bundle)
        assertEquals("1.zst", FolderInstall.archiveFile(File(bundle.path), bundle.archiveFormat).name)
        assertEquals(listOf("a.dat", "b.dat"), members.getValue("1").map { it.path })
        assertThrows(IOException::class.java) { FolderInstall.downloads(files, listOf(files[3]), emptyMap()) }
        // Tout le jeu : chaque archive regroupée comptée une fois, à sa taille (12 + 9, pas 10 + 20 + 30).
        val all = FolderInstall.downloads(files, files, mapOf("1" to FolderBundle(12), "2" to FolderBundle(9))).first
        assertEquals(50 * mb + 12 + 9 + 5, all.sumOf { it.downloadSize })
    }

    @Test
    fun `archive regroupee decompressee fichier par fichier, puis supprimee, abimee refusee`() = runBlocking {
        val dir = Files.createTempDirectory("folder-bundle").toFile()
        try {
            val a = ByteArray(300_000) { (it % 97).toByte() }
            val b = ByteArray(0)
            val c = ByteArray(200_000) { (it % 13).toByte() }
            val files = listOf(
                FolderFile("data/a.dat", a.size.toLong(), crc32 = crcOf(a), bundle = "1", offset = 0),
                FolderFile("empty.txt", 0, crc32 = crcOf(b), bundle = "1", offset = a.size.toLong()),
                FolderFile("c.dat", c.size.toLong(), crc32 = crcOf(c), bundle = "1", offset = a.size.toLong()),
            )
            val archive = File(dir, ".romcloud-bundles/1.zst").apply { parentFile.mkdirs() }
            fun write(content: ByteArray) = ZstdOutputStream(archive.outputStream(), 19).use { zst ->
                zst.setLong(27)
                zst.setChecksum(true)
                zst.write(content)
            }
            write(a + b + c)
            var last = 0L
            FolderInstall.unpackBundle(archive, files.reversed().map { it to File(dir, it.path) }) { last = it }
            assertArrayEquals(a, File(dir, "data/a.dat").readBytes())
            assertTrue(File(dir, "empty.txt").isFile)
            assertArrayEquals(c, File(dir, "c.dat").readBytes())
            assertEquals((a.size + c.size).toLong(), last)
            assertFalse(archive.exists())
            assertTrue(dir.walk().none { it.name.endsWith(".unpack") })

            // Contenu qui ne correspond pas au CRC32 d'un fichier : refusé, archive supprimée.
            write(a + b + c.reversedArray())
            File(dir, "c.dat").delete()
            assertThrows(IOException::class.java) { runBlocking { FolderInstall.unpackBundle(archive, files.map { it to File(dir, it.path) }) } }
            assertFalse(File(dir, "c.dat").exists())
            assertFalse(archive.exists())
        } finally {
            dir.deleteRecursively()
        }
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
