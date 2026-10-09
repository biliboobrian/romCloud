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
    /** Adresse de téléchargement hors serveur RomCloud (mise à jour de l'application). */
    val url: String? = null,
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
    val crc32: String? = null,
    val md5: String? = null,
    val details: GameDetails = GameDetails(),
    val hasBoxart: Boolean = false,
    val hasScreenshot: Boolean = false,
    val addedAt: String = "",
    val updatedAt: String = "",
    /** Scraping du serveur : ok, none (pas encore scrapé), notfound, error ; absent des anciens caches. */
    val scrapeStatus: String = "ok",
) {
    /** Jeu identifié par le scraping du serveur (réglage « Masquer les jeux non identifiés »). */
    val identified: Boolean get() = scrapeStatus == "ok"

    val year: String? get() = releaseDate?.take(4)?.takeIf { it.all(Char::isDigit) }

    /** Premier genre (« Plateforme, Action » -> « Plateforme »), pour les rangées du carrousel. */
    val mainGenre: String?
        get() = genre?.split(',', '/', ';')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
}

/** Valeur propre à une région (« eu », « jp », « wor »…) : titre, date de sortie. */
@Serializable
data class RegionText(val region: String? = null, val text: String)

@Serializable
data class AgeRating(val type: String? = null, val text: String)

@Serializable
data class GameLink(val label: String, val url: String)

/** Informations détaillées du jeu (scraping, nom de fichier No-Intro / Redump). */
@Serializable
data class GameDetails(
    val otherTitles: List<RegionText> = emptyList(),
    val releaseDates: List<RegionText> = emptyList(),
    val regions: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val series: String? = null,
    val modes: List<String> = emptyList(),
    val themes: List<String> = emptyList(),
    val ageRatings: List<AgeRating> = emptyList(),
    val serial: String? = null,
    val romFlags: List<String> = emptyList(),
    /** Jeu d'arcade : nom court MAME / FinalBurn Neo (« fatfury1 ») et jeu original d'un clone. */
    val arcadeSet: String? = null,
    val arcadeParent: String? = null,
    val resolution: String? = null,
    val rotation: String? = null,
    val controls: String? = null,
    val rumble: Boolean? = null,
    val analog: Boolean? = null,
    /** Jeu jouable à plusieurs en coopération (LaunchBox). */
    val cooperative: Boolean? = null,
    val links: List<GameLink> = emptyList(),
)

/** Données chargées, avec indication si elles proviennent du cache hors ligne. */
data class Loaded<T>(val data: T, val offline: Boolean, val error: String? = null)
