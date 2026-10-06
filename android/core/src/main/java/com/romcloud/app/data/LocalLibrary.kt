package com.romcloud.app.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Emplacement des ROMs sur l'appareil : <dossier ROMs>/<dossier du système>/<fichier>.
 * Un jeu est considéré comme téléchargé si le fichier existe avec la taille attendue.
 */
class LocalLibrary(private val context: Context, private val settings: Settings) {

    private fun root() = File(settings.config.value.romsDir)

    fun systemDir(system: GameSystem) = File(root(), system.folder)

    fun fileFor(system: GameSystem, game: Game) = File(systemDir(system), game.fileName)

    fun isDownloaded(system: GameSystem, game: Game): Boolean {
        val file = fileFor(system, game)
        return file.isFile && file.length() == game.size
    }

    /** Identifiants des jeux présents localement (un seul listage du dossier). */
    fun downloadedIds(system: GameSystem, games: List<Game>): Set<Long> {
        val sizes = systemDir(system).listFiles()?.associate { it.name to it.length() } ?: return emptySet()
        return games.filter { sizes[it.fileName] == it.size }.map { it.id }.toSet()
    }

    /** Systèmes dont le dossier contient au moins un fichier de jeu (seuls affichés hors ligne). */
    fun systemsWithGames(systems: List<GameSystem>): Set<String> =
        systems.filter { system ->
            systemDir(system).listFiles()?.any { it.isFile && !it.name.endsWith(".part") } == true
        }.mapTo(mutableSetOf()) { it.id }

    /** Emplacement d'un BIOS : <dossier BIOS>/<chemin relatif, sous-dossiers compris>. */
    fun biosFile(bios: BiosFile) = File(
        settings.config.value.biosDir,
        bios.path.split('/').filter { it.isNotEmpty() && it != "." && it != ".." }.joinToString("/"),
    )

    /** BIOS absents de l'appareil (ou de taille différente). */
    fun missingBios(files: List<BiosFile>): List<BiosFile> =
        files.filterNot { biosFile(it).let { f -> f.isFile && f.length() == it.size } }

    fun delete(system: GameSystem, game: Game): Boolean = fileFor(system, game).delete()

    fun hasStoragePermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    /** Le dossier choisi est-il dans l'espace privé de l'application (pas de permission requise) ? */
    fun isAppPrivateDir(): Boolean {
        val appDir = context.getExternalFilesDir(null)?.absolutePath ?: return false
        return settings.config.value.romsDir.startsWith(appDir)
    }
}
