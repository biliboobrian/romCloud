package com.romcloud.app.data

import com.romcloud.app.launch.LibretroPlayers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
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
class Repository(private val api: ApiClient, private val cacheDir: File) {

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

    /** Note un jeu téléchargé (et son système) pour le lister hors ligne. */
    @Synchronized
    fun rememberDownloaded(system: GameSystem, game: Game) {
        val index = downloadedIndex()
        val next = DownloadedIndex(index.systems + (system.id to system), index.games + (game.id to game))
        runCatching { cacheFile(DOWNLOADED).writeText(api.json.encodeToString(DownloadedIndex.serializer(), next)) }
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
                        .map { system -> LibretroPlayers.addTo(system.withUniquePlayerIds()) }
                }
            },
            extra = { it.systems.values.toList() },
            key = { it.id },
        )
        systemsMemory.clear()
        loaded.data.forEach { systemsMemory[it.id] = it }
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
        return loaded
    }

    /** BIOS du système sur le serveur (liste vide sans appel réseau si le système n'en a pas). */
    suspend fun bios(system: GameSystem): List<BiosFile> {
        if (system.biosCount == 0) return emptyList()
        return runCatching {
            loadWithCache("bios-${safeName(system.id)}.json", { api.biosRaw(system.id) }) {
                api.json.decodeFromString(BiosList.serializer(), it).files
            }.data
        }.getOrDefault(emptyList())
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
            return Loaded(api.json.decodeFromString(gameListSerializer, api.searchRaw(query)), offline = false)
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
            return Loaded(results, offline = true, error = e.message)
        }
    }

    fun clearMemory() {
        systemsMemory.clear()
        gamesMemory.clear()
    }

    private fun safeName(s: String) = s.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private companion object {
        const val DOWNLOADED = "downloaded.json"
    }
}
