package com.romcloud.app.data

import androidx.annotation.StringRes
import com.romcloud.core.R
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

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
    /** Couleur de fond « #rrggbb » (réglages du système sur le serveur) : carte, liste des jeux, fiche. */
    val color: String? = null,
    /**
     * Ordre des émulateurs choisi sur le serveur, par plateforme (« android », « androidtv »,
     * « windows ») : uniqueId des émulateurs ; absent : ordre de l'application.
     */
    val emulatorOrder: Map<String, List<String>> = emptyMap(),
    /**
     * Options par défaut des émulateurs intégrés choisies sur le serveur (variables des cœurs libretro,
     * réglages de RomCloud PC pour les jeux PC) ; celles du jeu ([Game.coreOptions]) les remplacent.
     */
    val coreOptions: Map<String, String> = emptyMap(),
) {
    /**
     * Émulateurs de l'ordre choisi sur le serveur pour [platform] d'abord, dans cet ordre ; les
     * autres ensuite, dans leur ordre. Le premier compatible est le choix par défaut.
     */
    fun withEmulatorOrder(platform: String): GameSystem {
        val order = emulatorOrder[platform].orEmpty()
        if (order.isEmpty()) return this
        val sorted = players.sortedBy { p -> order.indexOf(p.uniqueId).let { if (it < 0) order.size else it } }
        return if (sorted == players) this else copy(players = sorted)
    }

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

/**
 * BIOS d'un système sur le serveur ; [path] est relatif au dossier BIOS (« dc/dc_boot.bin »).
 * [version] : version d'un fichier récupéré sur Internet par le serveur (firmware, pilote).
 */
@Serializable
data class BiosFile(
    val id: Long,
    val path: String,
    val size: Long,
    val md5: String? = null,
    val version: String? = null,
) {
    /** Pilote graphique (« drivers/turnip.zip ») : téléchargé seulement si l'option est active. */
    val isDriver: Boolean get() = path.startsWith("drivers/")
}

@Serializable
data class BiosList(val files: List<BiosFile> = emptyList())

/** APK d'un émulateur Android : sur le serveur (un par paquet) ou dans la dernière Release de l'émulateur. */
@Serializable
data class EmulatorApk(
    val id: Long,
    val packageName: String,
    val label: String,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val size: Long,
    /** Adresse de téléchargement hors serveur RomCloud (mise à jour de l'application, Release de l'émulateur). */
    val url: String? = null,
    /** Site de la Release de l'émulateur (« github.com ») ; null pour un APK du serveur. */
    val source: String? = null,
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
    /** Licence zRIF d'un .pkg PS Vita saisie sur le serveur, proposée pour l'installer dans Vita3K. */
    val zrif: String? = null,
    val crc32: String? = null,
    val md5: String? = null,
    val details: GameDetails = GameDetails(),
    val hasBoxart: Boolean = false,
    val hasScreenshot: Boolean = false,
    /** Nombre de captures d'écran (1 à 4) ; absent d'un serveur d'avant les 4 captures. */
    val screenshotCount: Int? = null,
    val addedAt: String = "",
    val updatedAt: String = "",
    /** Scraping du serveur : ok, none (pas encore scrapé), notfound, error ; absent des anciens caches. */
    val scrapeStatus: String = "ok",
    /** Autres disques, mises à jour et DLC regroupés par le serveur, téléchargés avec le jeu. */
    val parts: List<GamePart> = emptyList(),
    /** Jeu en dossier (PC Windows) : dossier [fileName] téléchargé fichier par fichier, lancé par [exe] avec Winlator. */
    val folder: Boolean = false,
    /** Exécutable du jeu en dossier, chemin dans son dossier (« Bin/Game.exe ») ; null : aucun choisi. */
    val exe: String? = null,
    /**
     * Jeu PC compressé par le serveur : « zip » (un .zip par fichier, décompressé à la réception),
     * « partial » (une partie des fichiers) ou « flat » (en clair).
     */
    val packed: String = "flat",
    /** Taille à télécharger du jeu PC (archives comprises) ; [size] : sa taille une fois installé. */
    val packedSize: Long? = null,
    /** Format des archives du jeu PC compressé : « zst », « xz », « zip » ou « mixed » ; null : aucune. */
    val packFormat: String? = null,
    /** Options des émulateurs intégrés propres au jeu, choisies sur le serveur : remplacent celles du système. */
    val coreOptions: Map<String, String> = emptyMap(),
) {
    /** Jeu PC dont des fichiers sont téléchargés compressés : tailles compressée et installée différentes. */
    val zipped: Boolean get() = folder && packed != "flat" && packedSize != null
    /**
     * Options par défaut des émulateurs intégrés pour ce jeu : celles du système [system], remplacées par
     * celles du jeu. Les choix du joueur pendant la partie l'emportent sur elles.
     */
    fun defaultCoreOptions(system: GameSystem): Map<String, String> = system.coreOptions + coreOptions

    /** Fichiers du jeu : le fichier principal puis ses parties. */
    val files: List<GamePart> get() = listOf(GamePart(id, fileName, size)) + parts

    /** Taille du jeu et de ses parties. */
    val fullSize: Long get() = size + parts.sumOf { it.size }

    /** Jeu identifié par le scraping du serveur (réglage « Masquer les jeux non identifiés »). */
    val identified: Boolean get() = scrapeStatus == "ok"

    /** Note en étoiles entières (0 à 5), null sans note. */
    val stars: Int? get() = rating?.let { starsOf(it) }

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

/**
 * Fichier d'un jeu en dossier (PC) : chemin dans son dossier (« Bin/Game.exe ») et taille ;
 * compressé par le serveur : [format] de son archive (« zst », « xz », « zip »), sa taille
 * [packedSize] ([zipSize] pour les serveurs d'avant le xz) et [crc32] du fichier décompressé.
 */
@Serializable
data class FolderFile(
    val path: String,
    val size: Long,
    val zipSize: Long? = null,
    val crc32: String? = null,
    val format: String? = null,
    val packedSize: Long? = null,
) {
    val packed: Boolean get() = archiveSize != null

    /** Taille de l'archive (fichier compressé), null en clair. */
    val archiveSize: Long? get() = packedSize ?: zipSize

    /** Format de l'archive : « zst », « xz » ou « zip ». */
    val archiveFormat: String get() = if (format == "zst" || format == "xz") format else "zip"

    /** Octets à télécharger. */
    val downloadSize: Long get() = archiveSize ?: size
}

/** Fichiers d'un jeu en dossier (`GET /api/games/{id}/files`). */
@Serializable
data class FolderListing(val files: List<FolderFile> = emptyList(), val exe: String? = null, val packed: String = "flat")

/** Partie d'un jeu : [kind] disc (avec son numéro [index]), update ou dlc ; vide pour le fichier principal. */
@Serializable
data class GamePart(val id: Long, val fileName: String, val size: Long, val kind: String = "", val index: Int? = null)

/**
 * Note d'une source du scraping, en étoiles : [source] screenscraper, launchbox ou press (presse
 * relevée par Wikidata) ; [count] : votes (LaunchBox) ou sites de presse.
 */
@Serializable
data class SourceRating(val source: String, val rating: Double? = null, val count: Int? = null)

/** Étoiles entières (0 à 5) d'une note sur 5. */
fun starsOf(rating: Double): Int = rating.roundToInt().coerceIn(0, 5)

/** Étoiles pleines et vides (« ★★★★☆ »). */
fun starsText(stars: Int): String = stars.coerceIn(0, 5).let { "★".repeat(it) + "☆".repeat(5 - it) }

/** Ordre des jeux dans les listes et les rangées du carrousel. */
enum class GameOrder(@StringRes val label: Int) {
    NAME(R.string.order_name),
    RATING(R.string.order_rating),
}

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
    /** Notes de chaque source, combinées par le serveur dans la note du jeu. */
    val ratings: List<SourceRating> = emptyList(),
    val links: List<GameLink> = emptyList(),
)

/**
 * Place occupée sur l'appareil : [systems] octets des fichiers de chaque système (ceux qui en ont),
 * [free] / [total] : espace de la partition du dossier des ROMs (-1 s'il est inconnu).
 */
data class StorageUsage(val systems: Map<String, Long> = emptyMap(), val free: Long = -1, val total: Long = -1) {
    val known: Boolean get() = total > 0 && free >= 0

    /** Part occupée de la partition (0 à 1). */
    val usedFraction: Float get() = if (known) (1f - free.toFloat() / total).coerceIn(0f, 1f) else 0f

    /** Moins de 10 % d'espace libre. */
    val low: Boolean get() = known && free < total / 10
}

/** Données chargées, avec indication si elles proviennent du cache hors ligne. */
data class Loaded<T>(val data: T, val offline: Boolean, val error: String? = null)

/** Médias des captures d'écran du jeu, dans l'ordre : « screenshot », « screenshot2 »… */
val Game.screenshotTypes: List<String>
    get() = List(screenshotCount ?: if (hasScreenshot) 1 else 0) { if (it == 0) "screenshot" else "screenshot${it + 1}" }
