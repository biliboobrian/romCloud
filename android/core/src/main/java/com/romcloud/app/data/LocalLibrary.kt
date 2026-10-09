package com.romcloud.app.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import com.romcloud.app.launch.PlayerFilter
import java.io.File

/**
 * Emplacement des ROMs sur l'appareil : <dossier ROMs>/<dossier du système>/<fichier>.
 * Un jeu est considéré comme téléchargé si ses fichiers existent avec la taille attendue : le
 * fichier principal et ses parties (disques, mises à jour, DLC), rangés à côté de lui.
 */
class LocalLibrary(private val context: Context, private val settings: Settings) {

    private fun root() = File(settings.config.value.romsDir)

    fun systemDir(system: GameSystem) = File(root(), system.folder)

    fun fileFor(system: GameSystem, game: Game) = File(systemDir(system), game.fileName)

    fun isDownloaded(system: GameSystem, game: Game): Boolean =
        game.files.all { f -> File(systemDir(system), f.fileName).let { it.isFile && it.length() == f.size } }

    /** Identifiants des jeux présents localement (un seul listage du dossier). */
    fun downloadedIds(system: GameSystem, games: List<Game>): Set<Long> {
        val sizes = systemDir(system).listFiles()?.associate { it.name to it.length() } ?: return emptySet()
        return games.filter { g -> g.files.all { sizes[it.fileName] == it.size } }.map { it.id }.toSet()
    }

    /**
     * Fichier à ouvrir : la liste des disques (.m3u, changement de disque en jeu) pour l'émulateur
     * intégré et les émulateurs dont le modèle accepte les .m3u, sinon le fichier principal.
     */
    fun launchFile(system: GameSystem, game: Game, player: Player?): File {
        val main = fileFor(system, game)
        val text = playlistText(game) ?: return main
        val playlist = File(main.parentFile, "${main.nameWithoutExtension}.m3u")
        val readsPlaylist = player != null &&
            (player.libretroCore != null || (!player.acceptedFilenameRegex.isNullOrBlank() && PlayerFilter.accepts(player, playlist.name)))
        if (!readsPlaylist) return main
        if (!main.isFile) return main
        runCatching { if (!playlist.isFile || playlist.readText() != text) playlist.writeText(text) }.onFailure { return main }
        return playlist
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

    /** Supprime les fichiers du jeu, ses parties et sa liste de disques. */
    fun delete(system: GameSystem, game: Game): Boolean {
        val main = fileFor(system, game)
        File(main.parentFile, "${main.nameWithoutExtension}.m3u").delete()
        return game.files.map { File(systemDir(system), it.fileName).delete() }.first()
    }

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

/**
 * Liste des disques d'un jeu (.m3u) : un nom de fichier par ligne, du premier au dernier disque ;
 * null pour un jeu d'un seul disque, ou si un disque est compressé (.zip, .7z : illisible dans une liste).
 * Elle porte le nom du premier disque : sauvegardes et états du jeu gardent leur nom.
 */
fun playlistText(game: Game): String? {
    val discs = game.parts.filter { it.kind == "disc" }.sortedBy { it.index ?: Int.MAX_VALUE }
    if (discs.isEmpty()) return null
    val names = listOf(game.fileName) + discs.map { it.fileName }
    if (names.any { it.substringAfterLast('.', "").lowercase() in setOf("zip", "7z") }) return null
    return names.joinToString("\n", postfix = "\n")
}
