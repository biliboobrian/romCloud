package com.romcloud.app.data

import android.content.Context
import android.os.StatFs
import androidx.annotation.StringRes
import com.romcloud.app.libretro.LibretroCores
import com.romcloud.core.R
import java.io.File

/**
 * Catégories de la page du stockage, dans l'ordre des barres et de la légende, avec leur couleur
 * (mêmes couleurs que l'application Windows).
 */
enum class StorageKind(@StringRes val label: Int, val color: Long) {
    GAMES(R.string.storage_kind_games, 0xFF9D95FF),
    EMULATORS(R.string.storage_kind_emulators, 0xFF3FB6E8),
    BIOS(R.string.storage_kind_bios, 0xFFF2B84B),
    DRIVERS(R.string.storage_kind_drivers, 0xFFFF7AB8),
    SAVES(R.string.storage_kind_saves, 0xFF2EB872),
}

/**
 * Place occupée par un système, par catégorie ; [apps] : paquets des émulateurs externes installés
 * utilisés par ce système seul (désinstallés avec ses émulateurs), [sharedApps] : utilisés aussi par
 * d'autres systèmes (comptés, gardés).
 */
data class SystemStorage(
    val system: GameSystem,
    val sizes: Map<StorageKind, Long>,
    val apps: List<String> = emptyList(),
    val sharedApps: List<String> = emptyList(),
) {
    val total: Long get() = sizes.values.sum()
    fun size(kind: StorageKind): Long = sizes[kind] ?: 0
}

/** Page du stockage : systèmes ayant des données (du plus gros au plus petit), espace de la partition. */
data class StorageDetails(val systems: List<SystemStorage> = emptyList(), val free: Long = -1, val total: Long = -1) {
    val known: Boolean get() = total > 0 && free >= 0

    /** Total de chaque catégorie, tous systèmes confondus. */
    fun sum(kind: StorageKind): Long = systems.sumOf { it.size(kind) }
}

/**
 * Calcul de la place occupée par chaque système et suppression de ses données :
 *  - jeux : roms/<dossier du système> ;
 *  - émulateurs : cœurs libretro du système (stockage privé) et APK des émulateurs externes installés ;
 *  - BIOS : bios/<dossier du système>, system/<console> et fichiers « system » de la liste du serveur ;
 *  - pilotes : fichiers « drivers » de la liste du serveur ;
 *  - sauvegardes : saves/<cœur>, states/<cœur> des cœurs du système (tout le dossier si aucun autre
 *    système n'utilise ce cœur, sinon les fichiers nommés comme un jeu du système).
 * Un cœur ou un pilote commun à plusieurs systèmes est compté pour chacun (re-téléchargé à la demande).
 */
class StorageScanner(
    private val context: Context,
    private val folders: () -> Folders,
    private val cachedBios: (GameSystem) -> List<BiosFile>,
    private val packageOf: (Player) -> String?,
) {
    private val cores = LibretroCores(context)

    private fun coresOf(system: GameSystem) = system.players.mapNotNull { it.libretroCore }.distinct()

    /** Taille de l'APK installé (et de ses parties), 0 s'il n'est pas installé. */
    private fun installedSize(pkg: String): Long = runCatching {
        val info = context.packageManager.getApplicationInfo(pkg, 0)
        (listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()).sumOf { File(it).length() }
    }.getOrDefault(0)

    private fun paths(system: GameSystem, systems: List<GameSystem>): Map<StorageKind, List<File>> {
        val f = folders()
        val games = File(f.roms, system.folder)
        val emulators = coresOf(system).flatMap { cores.files(it) }
        val area = f.systemFiles(system)
        val bios = mutableListOf(f.bios(system)).apply { area?.let(::add) }
        val drivers = mutableListOf<File>()
        for (entry in cachedBios(system)) {
            val file = f.file(system, entry)
            when (entry.zone) {
                Folders.DRIVERS -> drivers += file
                Folders.SYSTEM -> if (area == null || !file.startsWith(area)) bios += file
            }
        }
        val stems = games.listFiles().orEmpty().map { it.nameWithoutExtension.lowercase() }.toSet()
        val others = systems.filter { it.id != system.id }
        val saves = mutableListOf<File>()
        for (core in coresOf(system)) {
            val shared = others.any { core in coresOf(it) }
            for (dir in listOf(File(f.saves, core), File(f.states, core))) {
                if (!shared) saves += dir
                else saves += dir.listFiles().orEmpty().filter { it.nameWithoutExtension.lowercase() in stems }
            }
        }
        return mapOf(
            StorageKind.GAMES to listOf(games),
            StorageKind.EMULATORS to emulators.distinct(),
            StorageKind.BIOS to bios.distinct(),
            StorageKind.DRIVERS to drivers.distinct(),
            StorageKind.SAVES to saves.distinct(),
        )
    }

    fun details(systems: List<GameSystem>): StorageDetails {
        // Émulateurs externes de chaque système, et taille de chaque APK : lus une fois.
        val apkSizes = HashMap<String, Long>()
        val packages = systems.associate { system ->
            system.id to system.players.mapNotNull { runCatching { packageOf(it) }.getOrNull() }
                .filter { it != context.packageName && apkSizes.getOrPut(it) { installedSize(it) } > 0 }.distinct()
        }
        val rows = systems.mapNotNull { system ->
            val sizes = paths(system, systems).mapValues { (_, files) -> files.sumOf(::sizeOf) }.toMutableMap()
            val (shared, own) = packages[system.id].orEmpty().partition { pkg -> systems.any { it.id != system.id && pkg in packages[it.id].orEmpty() } }
            sizes[StorageKind.EMULATORS] = (sizes[StorageKind.EMULATORS] ?: 0) + (own + shared).sumOf { apkSizes[it] ?: 0 }
            SystemStorage(system, sizes, own, shared).takeIf { it.total > 0 }
        }.sortedByDescending { it.total }
        var dir: File? = folders().root
        while (dir != null && !dir.exists()) dir = dir.parentFile
        val stat = dir?.let { runCatching { StatFs(it.absolutePath) }.getOrNull() }
        return StorageDetails(rows, stat?.availableBytes ?: -1, stat?.totalBytes ?: -1)
    }

    /**
     * Supprime les fichiers du système pour les catégories [kinds] ; renvoie les octets libérés. Les
     * applications d'émulateurs ([SystemStorage.apps]) sont désinstallées par l'interface (Android le confirme).
     */
    fun clear(system: GameSystem, kinds: Set<StorageKind>, systems: List<GameSystem>): Long =
        paths(system, systems).filterKeys { it in kinds }.values.flatten().sumOf { file ->
            val size = sizeOf(file)
            if (file.deleteRecursively()) size else 0
        }

    companion object {
        /** Taille d'un fichier ou d'un dossier (sous-dossiers compris) ; 0 s'il n'existe pas. */
        fun sizeOf(file: File): Long = when {
            file.isFile -> file.length()
            file.isDirectory -> file.listFiles().orEmpty().sumOf(::sizeOf)
            else -> 0
        }
    }
}
