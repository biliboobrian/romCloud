package com.romcloud.app.data

import kotlinx.serialization.Serializable

@Serializable
data class ServerInfo(
    val name: String,
    val version: String,
    val authRequired: Boolean = false,
)

/** Modèle d'émulateur au format Daijishou (lancement via arguments « am start »). */
@Serializable
data class Player(
    val name: String,
    val uniqueId: String,
    val description: String? = null,
    val acceptedFilenameRegex: String? = null,
    val amStartArguments: String,
    val killPackageProcesses: Boolean = false,
    /**
     * Cœur libretro exécuté par l'émulateur intégré (LibretroDroid), ex. « snes9x ».
     * Renseigné seulement pour les émulateurs ajoutés par RomCloud (voir LibretroPlayers).
     */
    val libretroCore: String? = null,
)

@Serializable
data class GameSystem(
    val id: String,
    val name: String,
    val shortname: String,
    val folder: String,
    val filenameRegex: String? = null,
    val players: List<Player> = emptyList(),
    val gameCount: Int = 0,
    val totalSize: Long = 0,
    val hasImage: Boolean = false,
    val imageVersion: String? = null,
    val biosCount: Int = 0,
) {
    /**
     * Certains modèles Daijishou contiennent deux émulateurs de même uniqueId (ex. deux variantes
     * de DuckStation pour la PlayStation) : les suivants sont suffixés (« …#2 ») pour que chacun
     * puisse être choisi et mémorisé séparément.
     */
    fun withUniquePlayerIds(): GameSystem {
        val seen = mutableMapOf<String, Int>()
        val renamed = players.map { p ->
            val n = (seen[p.uniqueId] ?: 0) + 1
            seen[p.uniqueId] = n
            if (n == 1) p else p.copy(uniqueId = "${p.uniqueId}#$n")
        }
        return if (renamed == players) this else copy(players = renamed)
    }
}

/** BIOS d'un système sur le serveur ; [path] est relatif au dossier BIOS (« dc/dc_boot.bin »). */
@Serializable
data class BiosFile(
    val id: Long,
    val path: String,
    val size: Long,
    val md5: String? = null,
)

@Serializable
data class BiosList(val files: List<BiosFile> = emptyList())

/** APK d'un émulateur Android disponible sur le serveur (un par paquet). */
@Serializable
data class EmulatorApk(
    val id: Long,
    val packageName: String,
    val label: String,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val size: Long,
)

@Serializable
data class Game(
    val id: Long,
    val systemId: String,
    val fileName: String,
    val size: Long,
    val title: String,
    val description: String? = null,
    val releaseDate: String? = null,
    val developer: String? = null,
    val publisher: String? = null,
    val genre: String? = null,
    val players: String? = null,
    val rating: Double? = null,
    val hasBoxart: Boolean = false,
    val hasScreenshot: Boolean = false,
    val addedAt: String = "",
    val updatedAt: String = "",
) {
    val year: String? get() = releaseDate?.take(4)?.takeIf { it.all(Char::isDigit) }

    /** Premier genre (« Plateforme, Action » -> « Plateforme »), pour les rangées du carrousel. */
    val mainGenre: String?
        get() = genre?.split(',', '/', ';')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
}

/** Données chargées, avec indication si elles proviennent du cache hors ligne. */
data class Loaded<T>(val data: T, val offline: Boolean, val error: String? = null)
