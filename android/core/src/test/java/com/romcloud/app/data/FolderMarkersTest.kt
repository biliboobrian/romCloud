package com.romcloud.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FolderMarkersTest {

    @Test
    fun `marqueur d'un jeu PC sans la liste des fichiers`() {
        val dir = Files.createTempDirectory("romcloud-marker").toFile()
        try {
            assertNull(FolderMarkers.read(dir))
            // Version 1.89 : fichiers dans le marqueur, rangés à part à la première lecture.
            val files = (0 until 5000).associate { "data/file$it.dat" to listOf(it.toLong(), 1_700_000_000_000L) }
            val entries = files.entries.joinToString(",") { (path, v) -> "\"$path\":[${v[0]},${v[1]}]" }
            File(dir, FolderMarkers.MARKER).writeText("""{"size":9,"stamp":"9-1","version":"1.0","files":{$entries}}""")
            assertEquals(FolderMarker(size = 9, stamp = "9-1", version = "1.0"), FolderMarkers.read(dir))
            assertTrue(File(dir, FolderMarkers.MARKER).length() < 100)
            assertEquals(files, FolderMarkers.files(dir))
            // Écrit par cette version, et anciennes versions (taille seule).
            FolderMarkers.write(dir, FolderMarker(size = 3, files = mapOf("a" to listOf(3L, 0L))))
            assertEquals(FolderMarker(size = 3), FolderMarkers.read(dir))
            assertEquals(mapOf("a" to listOf(3L, 0L)), FolderMarkers.files(dir))
            File(dir, FolderMarkers.MARKER).writeText("42")
            assertEquals(FolderMarker(size = 42), FolderMarkers.read(dir))
        } finally {
            dir.deleteRecursively()
        }
    }
}
