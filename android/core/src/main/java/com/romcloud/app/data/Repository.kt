package com.romcloud.app.data

import com.romcloud.app.launch.LibretroPlayers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Jeux téléchargés et leur système, gardés pour être listés hors ligne. */
@Serializable
private data class DownloadedIndex(
    val systems: Map<String, GameSystem> = emptyMap(),
    val games: Map<Long, Game> = emptyMap(),
)

/**
 * Accès aux listes de systèmes et de jeux. Chaque réponse du serveur est mise en cache
 * sur l'appareil, ce qui permet de parcourir et lancer les jeux déjà téléchargés hors ligne.
 * Les jeux téléchargés sont aussi notés à part ([rememberDownloaded]) : ils restent listés hors
 * ligne même si la liste de leur système n'a jamais été mise en cache (jeu trouvé par la recherche).
 */
/**
 * Réglages appliqués aux listes de jeux et à la recherche : [hideUnidentified] (« Masquer les jeux
 * non identifiés ») et [gameOrder] (par nom ou par note).
 */
class Repository(
    private val api: ApiClient,
    private val cacheDir: File,
    private val hideUnidentified: () -> Boolean = { false },
    private val gameOrder: () -> GameOrder = { GameOrder.NAME },
    private val gpuDrivers: () -> Boolean = { false },
) {

    private val systemsMemory = ConcurrentHashMap<String, GameSystem>()
    private val gamesMemory = ConcurrentHashMap<String, List<Game>>()

    private val systemListSerializer = ListSerializer(GameSystem.serializer())
    private val gameListSerializer = ListSerializer(Game.serializer())

    private fun cacheFile(name: String) = File(cacheDir, "api-cache").apply { mkdirs() }.resolve(name)

    private suspend fun <T> loadWithCache(
        cacheName: String,
        fetch: suspend () -> String,
        decode: (String) -> T,
    ): Loaded<T> {
        try {
            val raw = fetch()
            val data = decode(raw)
            withContext(Dispatchers.IO) { cacheFile(cacheName).writeText(raw) }
            return Loaded(data, offline = false)
        } catch (e: IOException) {
            val cached = withContext(Dispatchers.IO) {
                cacheFile(cacheName).takeIf { it.exists() }?.readText()
            }
            if (cached != null) {
                return Loaded(decode(cached), offline = true, error = e.message)
            }
            throw e
        }
    }

    private fun downloadedIndex(): DownloadedIndex = runCatching {
        api.json.decodeFromString(DownloadedIndex.serializer(), cacheFile(DOWNLOADED).readText())
    }.getOrDefault(DownloadedIndex())

    private fun writeDownloadedIndex(index: DownloadedIndex) {
        runCatching { cacheFile(DOWNLOADED).writeText(api.json.encodeToString(DownloadedIndex.serializer(), index)) }
    }

    /** Note un jeu téléchargé (et son système) pour le lister hors ligne. */
    @Synchronized
    fun rememberDownloaded(system: GameSystem, game: Game) {
        val index = downloadedIndex()
        writeDownloadedIndex(DownloadedIndex(index.systems + (system.id to system), index.games + (game.id to game)))
    }

    /**
     * Fiches à jour des jeux et systèmes déjà notés dans l'index des jeux téléchargés (jaquette,
     * titre… changés sur le serveur depuis le téléchargement) ; les autres sont ignorés. Renvoie
     * true si l'index a changé.
     */
    @Synchronized
    private fun updateDownloaded(games: Collection<Game> = emptyList(), systems: Collection<GameSystem> = emptyList()): Boolean {
        val index = downloadedIndex()
        val freshGames = games.filter { game -> index.games[game.id].let { it != null && it != game } }
        val freshSystems = systems.filter { system -> index.systems[system.id].let { it != null && it != system } }
        if (freshGames.isEmpty() && freshSystems.isEmpty()) return false
        writeDownloadedIndex(DownloadedIndex(index.systems + freshSystems.associateBy { it.id }, index.games + freshGames.associateBy { it.id }))
        return true
    }

    @Volatile
    private var downloadedRefreshedAt = 0L

    /**
     * Fiches des jeux téléchargés relues sur le serveur (au plus toutes les [DOWNLOADED_REFRESH_MS]) :
     * rangée « Téléchargés » de l'écran des systèmes à jour. Renvoie true si l'index a changé.
     */
    suspend fun refreshDownloaded(): Boolean {
        val now = System.currentTimeMillis()
        if (now - downloadedRefreshedAt < DOWNLOADED_REFRESH_MS) return false
        downloadedRefreshedAt = now
        val ids = withContext(Dispatchers.IO) { downloadedIndex().games.keys }
        val fresh = coroutineScope {
            ids.map { id -> async { runCatching { api.json.decodeFromString(Game.serializer(), api.gameRaw(id)) }.getOrNull() } }.awaitAll()
        }.filterNotNull()
        // Serveur injoignable : nouvel essai au prochain affichage.
        if (fresh.isEmpty() && ids.isNotEmpty()) downloadedRefreshedAt = 0
        return withContext(Dispatchers.IO) { updateDownloaded(games = fresh) }
    }

    private val playedSerializer = MapSerializer(Long.serializer(), Long.serializer())

    /** Dernière partie lancée de chaque jeu (millisecondes) : rangée « Téléchargés », du plus récent au plus ancien. */
    fun lastPlayed(): Map<Long, Long> = runCatching {
        api.json.decodeFromString(playedSerializer, cacheFile(PLAYED).readText())
    }.getOrDefault(emptyMap())

    /** Note une partie lancée maintenant. */
    @Synchronized
    fun rememberPlayed(gameId: Long) {
        if (gameId <= 0) return
        val next = lastPlayed() + (gameId to System.currentTimeMillis())
        runCatching { cacheFile(PLAYED).writeText(api.json.encodeToString(playedSerializer, next)) }
    }

    /** Jeux notés comme téléchargés, avec leur système (présence sur l'appareil non vérifiée). */
    fun downloadedGames(): List<Pair<GameSystem, Game>> {
        val index = downloadedIndex()
        return index.games.values.mapNotNull { game -> index.systems[game.systemId]?.let { it to game } }
    }

    /**
     * Hors ligne : la liste en cache (ou rien, si elle ne l'a jamais été) est complétée par les
     * éléments [extra] de l'index des jeux téléchargés.
     */
    private suspend fun <T> withDownloaded(
        load: suspend () -> Loaded<List<T>>,
        extra: (DownloadedIndex) -> List<T>,
        key: (T) -> Any,
    ): Loaded<List<T>> {
        val loaded = try {
            load()
        } catch (e: IOException) {
            val known = withContext(Dispatchers.IO) { extra(downloadedIndex()) }
            if (known.isEmpty()) throw e
            return Loaded(known, offline = true, error = e.message)
        }
        if (!loaded.offline) return loaded
        val ids = loaded.data.mapTo(HashSet(), key)
        val more = withContext(Dispatchers.IO) { extra(downloadedIndex()) }.filter { key(it) !in ids }
        return if (more.isEmpty()) loaded else loaded.copy(data = loaded.data + more)
    }

    suspend fun systems(): Loaded<List<GameSystem>> {
        val loaded = withDownloaded(
            load = {
                loadWithCache("systems.json", api::systemsRaw) {
                    api.json.decodeFromString(systemListSerializer, it)
                        .map { system -> LibretroPlayers.addTo(system.withUniquePlayerIds()).withEmulatorOrder(api.platform) }
                }
            },
            extra = { it.systems.values.toList() },
            key = { it.id },
        )
        systemsMemory.clear()
        loaded.data.forEach { systemsMemory[it.id] = it }
        if (!loaded.offline) withContext(Dispatchers.IO) { updateDownloaded(systems = loaded.data) }
        return loaded
    }

    suspend fun games(systemId: String): Loaded<List<Game>> {
        val loaded = withDownloaded(
            load = {
                loadWithCache("games-${safeName(systemId)}.json", { api.gamesRaw(systemId) }) {
                    api.json.decodeFromString(gameListSerializer, it)
                }
            },
            extra = { index -> index.games.values.filter { it.systemId == systemId } },
            key = { it.id },
        )
        gamesMemory[systemId] = loaded.data
        if (!loaded.offline) withContext(Dispatchers.IO) { updateDownloaded(games = loaded.data) }
        return visible(loaded)
    }

    private fun visible(loaded: Loaded<List<Game>>): Loaded<List<Game>> {
        val games = if (hideUnidentified()) loaded.data.filter { it.identified } else loaded.data
        return loaded.copy(data = sortGames(games, gameOrder()))
    }

    /**
     * BIOS du système sur le serveur (liste vide sans appel réseau si le système n'en a pas) ;
     * pilotes graphiques seulement si leur option est active.
     */
    suspend fun bios(system: GameSystem): List<BiosFile> {
        if (system.biosCount == 0) return emptyList()
        val files = runCatching {
            loadWithCache("bios-${safeName(system.id)}.json", { api.biosRaw(system.id) }) {
                api.json.decodeFromString(BiosList.serializer(), it).files
            }.data
        }.getOrDefault(emptyList())
        return if (gpuDrivers()) files else files.filterNot { it.isDriver }
    }

    /** APK d'émulateurs du serveur (dernière liste connue hors ligne, liste vide en cas d'erreur). */
    suspend fun apks(): List<EmulatorApk> = runCatching {
        loadWithCache("apks.json", api::apksRaw) {
            api.json.decodeFromString(ListSerializer(EmulatorApk.serializer()), it)
        }.data
    }.getOrDefault(emptyList())

    suspend fun system(systemId: String): GameSystem? =
        systemsMemory[systemId] ?: runCatching { systems() }.getOrNull()?.data?.find { it.id == systemId }

    suspend fun game(systemId: String, gameId: Long): Game? =
        gamesMemory[systemId]?.find { it.id == gameId }
            ?: runCatching { games(systemId) }.getOrNull()?.data?.find { it.id == gameId }

    /**
     * Recherche dans tous les systèmes. Hors ligne : recherche dans les listes de jeux déjà
     * mises en cache (systèmes ouverts au moins une fois) et les jeux téléchargés, avec la même
     * règle que le serveur.
     */
    suspend fun search(query: String): Loaded<List<Game>> {
        try {
            return visible(Loaded(api.json.decodeFromString(gameListSerializer, api.searchRaw(query)), offline = false))
        } catch (e: IOException) {
            val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val cached = withContext(Dispatchers.IO) {
                File(cacheDir, "api-cache").listFiles { f -> f.name.startsWith("games-") }.orEmpty()
                    .flatMap { f -> runCatching { api.json.decodeFromString(gameListSerializer, f.readText()) }.getOrDefault(emptyList()) } +
                    downloadedIndex().games.values
            }
            val results = cached.distinctBy { it.id }
                .filter { g -> words.all { w -> g.title.lowercase().contains(w) || g.fileName.lowercase().contains(w) } }
                .sortedBy { it.title.lowercase() }
            return visible(Loaded(results, offline = true, error = e.message))
        }
    }

    fun clearMemory() {
        systemsMemory.clear()
        gamesMemory.clear()
    }

    private fun safeName(s: String) = s.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private companion object {
        const val DOWNLOADED = "downloaded.json"
        const val PLAYED = "played.json"

        /** Délai entre deux relectures des fiches des jeux téléchargés. */
        const val DOWNLOADED_REFRESH_MS = 5 * 60_000L
    }
}

/** Jeux par nom, ou par note (les mieux notés d'abord, sans note à la fin, puis par nom). */
fun sortGames(games: List<Game>, order: GameOrder): List<Game> = when (order) {
    GameOrder.NAME -> games.sortedBy { it.title.lowercase() }
    GameOrder.RATING -> games.sortedWith(compareByDescending<Game> { it.stars ?: -1 }.thenBy { it.title.lowercase() })
}
