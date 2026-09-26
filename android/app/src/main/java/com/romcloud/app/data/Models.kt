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
    val updatedAt: String = "",
) {
    val year: String? get() = releaseDate?.take(4)?.takeIf { it.all(Char::isDigit) }
}

/** Données chargées, avec indication si elles proviennent du cache hors ligne. */
data class Loaded<T>(val data: T, val offline: Boolean, val error: String? = null)
