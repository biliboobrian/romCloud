package com.romcloud.app.data

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Fichiers du dossier des BIOS que les émulateurs externes importent eux-mêmes, avec le sélecteur
 * de fichiers d'Android : recopiés dans Téléchargements/RomCloud/<système>, toujours proposé par le
 * sélecteur (le dossier des BIOS, souvent dans Android/media, y est difficile à atteindre ou masqué).
 * Switch (Eden, Citron…) : clés, et firmware en .zip, seul format accepté par Eden (un firmware
 * envoyé en dossier de fichiers .nca est recompressé).
 * Pilote graphique Turnip (puces Adreno) des systèmes dont les émulateurs en chargent un (Eden,
 * Dolphin, Vita3K, aPS3e), à installer depuis leur gestionnaire de pilotes ; retiré si l'option
 * des pilotes est désactivée.
 */
object ExternalEmulatorFiles {

    /** Système -> dossier public (Téléchargements/RomCloud/<dossier>). */
    private val FOLDERS = mapOf("switch" to "Switch", "gc" to "GameCube", "wii" to "Wii", "vita" to "PS Vita", "ps3" to "PS3")

    /** Pilote dans le dossier des BIOS (chemin du serveur) et nom de sa copie. */
    private const val DRIVER_PATH = "drivers/turnip.zip"
    internal const val DRIVER_NAME = "Turnip (Adreno).zip"

    private fun folderKey(system: GameSystem) = listOf(system.id, system.shortname).firstOrNull { it in FOLDERS }

    /** Dossier affiché à l'utilisateur pour un système, ou null s'il n'y a rien à recopier. */
    fun folderName(system: GameSystem): String? = folderKey(system)?.let { "RomCloud/${FOLDERS.getValue(it)}" }

    private fun isSwitch(system: GameSystem) = folderKey(system) == "switch"

    /** Dossier public de destination (Téléchargements/RomCloud/<système>). */
    fun targetDir(system: GameSystem): File? = folderName(system)?.let {
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), it)
    }

    /**
     * Recopie les fichiers du système présents dans [biosDir] (s'ils ont changé), pilote graphique
     * compris si [drivers] ; renvoie les fichiers disponibles dans le dossier public. Sans accès au
     * stockage : liste vide.
     */
    fun export(context: Context, system: GameSystem, biosDir: File, drivers: Boolean): List<File> {
        val target = targetDir(system) ?: return emptyList()
        val written = mutableListOf<File>()
        runCatching {
            val driver = File(biosDir, DRIVER_PATH)
            val copy = File(target, DRIVER_NAME)
            if (drivers && driver.isFile) copyIfNewer(driver, copy)?.let(written::add)
            else if (!drivers) copy.delete()
        }
        val source = File(biosDir, "switch")
        if (isSwitch(system) && source.isDirectory) runCatching {
            for (name in listOf("prod.keys", "title.keys")) {
                val from = File(source, name)
                if (from.isFile) copyIfNewer(from, File(target, name))?.let(written::add)
            }
            val zip = File(source, "firmware.zip")
            val folder = File(source, "firmware")
            when {
                zip.isFile -> copyIfNewer(zip, File(target, "firmware.zip"))?.let(written::add)
                folder.isDirectory -> zipIfNewer(folder, File(target, "firmware.zip"))?.let(written::add)
            }
        }
        // Visibles aussitôt dans le sélecteur (Téléchargements, fichiers récents).
        if (written.isNotEmpty()) MediaScannerConnection.scanFile(context, written.map { it.absolutePath }.toTypedArray(), null, null)
        return target.listFiles { f -> f.isFile && !f.name.endsWith(".tmp") }.orEmpty().sortedBy { it.name }
    }

    /** Copie [from] sur [to] s'il est plus récent ou de taille différente ; renvoie [to] si copié. */
    internal fun copyIfNewer(from: File, to: File): File? {
        if (to.isFile && to.length() == from.length() && to.lastModified() >= from.lastModified()) return null
        to.parentFile?.mkdirs()
        val tmp = File(to.path + ".tmp")
        from.copyTo(tmp, overwrite = true)
        if (!tmp.renameTo(to)) {
            tmp.copyTo(to, overwrite = true)
            tmp.delete()
        }
        return to
    }

    /** Archive .zip des fichiers de [folder] (sous-dossiers compris) si l'un d'eux est plus récent ; renvoie [zip] si écrit. */
    internal fun zipIfNewer(folder: File, zip: File): File? {
        val files = folder.walkTopDown().filter { it.isFile }.toList()
        if (files.isEmpty()) return null
        if (zip.isFile && files.all { it.lastModified() <= zip.lastModified() }) return null
        zip.parentFile?.mkdirs()
        val tmp = File(zip.path + ".tmp")
        ZipOutputStream(tmp.outputStream().buffered()).use { out ->
            for (file in files) {
                out.putNextEntry(ZipEntry(file.relativeTo(folder).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        zip.delete()
        if (!tmp.renameTo(zip)) {
            tmp.copyTo(zip, overwrite = true)
            tmp.delete()
        }
        return zip
    }
}
