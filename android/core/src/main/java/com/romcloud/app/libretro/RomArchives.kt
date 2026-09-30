package com.romcloud.app.libretro

import java.io.File
import java.util.zip.ZipInputStream

/**
 * ROMs compressées en .zip : RetroArch les décompresse avant de les passer au cœur, sauf pour
 * les cœurs qui lisent eux-mêmes les archives (arcade, DOS). LibretroDroid ne le fait pas :
 * on reproduit ce comportement.
 */
internal object RomArchives {

    /** Cœurs qui attendent l'archive telle quelle (jeux d'arcade = ensembles de fichiers .zip). */
    private val ARCHIVE_CORES = listOf("fbneo", "fbalpha", "mame", "dosbox", "neocd")

    /** Fichier principal d'un jeu à plusieurs fichiers (liste de disques, image CD). */
    private val MAIN_EXTENSIONS = listOf("m3u", "cue", "gdi", "ccd", "chd", "iso")

    fun needsExtraction(core: String, rom: File) =
        rom.extension.equals("zip", ignoreCase = true) && ARCHIVE_CORES.none { core.startsWith(it) }

    /** Décompresse [zip] dans [dir] (vidé au préalable) et renvoie le fichier à charger. */
    fun extract(zip: File, dir: File): File {
        dir.deleteRecursively()
        dir.mkdirs()
        val root = dir.canonicalPath + File.separator
        val files = mutableListOf<File>()
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            generateSequence { input.nextEntry }.filterNot { it.isDirectory }.forEach { entry ->
                val out = File(dir, entry.name)
                // Ignore les chemins qui sortiraient du dossier (« ../ »).
                if (!out.canonicalPath.startsWith(root)) return@forEach
                out.parentFile?.mkdirs()
                out.outputStream().use { input.copyTo(it) }
                files += out
            }
        }
        return MAIN_EXTENSIONS.firstNotNullOfOrNull { ext -> files.find { it.extension.equals(ext, ignoreCase = true) } }
            ?: files.maxByOrNull { it.length() }
            ?: zip
    }
}
