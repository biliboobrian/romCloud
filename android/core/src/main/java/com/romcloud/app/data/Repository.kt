package com.romcloud.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Accès aux listes de systèmes et de jeux. Chaque réponse du serveur est mise en cache
 * sur l'appareil, ce qui permet de parcourir et lancer les jeux déjà téléchargés hors ligne.
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

    suspend fun systems(): Loaded<List<GameSystem>> {
        val loaded = loadWithCache("systems.json", api::systemsRaw) {
            api.json.decodeFromString(systemListSerializer, it)
        }
        systemsMemory.clear()
        loaded.data.forEach { systemsMemory[it.id] = it }
        return loaded
    }

    suspend fun games(systemId: String): Loaded<List<Game>> {
        val loaded = loadWithCache("games-${safeName(systemId)}.json", { api.gamesRaw(systemId) }) {
            api.json.decodeFromString(gameListSerializer, it)
        }
        gamesMemory[systemId] = loaded.data
        return loaded
    }

    suspend fun system(systemId: String): GameSystem? =
        systemsMemory[systemId] ?: runCatching { systems() }.getOrNull()?.data?.find { it.id == systemId }

    suspend fun game(systemId: String, gameId: Long): Game? =
        gamesMemory[systemId]?.find { it.id == gameId }
            ?: runCatching { games(systemId) }.getOrNull()?.data?.find { it.id == gameId }

    /**
     * Recherche dans tous les systèmes. Hors ligne : recherche dans les listes de jeux déjà
     * mises en cache (systèmes ouverts au moins une fois), avec la même règle que le serveur.
     */
    suspend fun search(query: String): Loaded<List<Game>> {
        try {
            return Loaded(api.json.decodeFromString(gameListSerializer, api.searchRaw(query)), offline = false)
        } catch (e: IOException) {
            val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val cached = withContext(Dispatchers.IO) {
                File(cacheDir, "api-cache").listFiles { f -> f.name.startsWith("games-") }.orEmpty()
                    .flatMap { f -> runCatching { api.json.decodeFromString(gameListSerializer, f.readText()) }.getOrDefault(emptyList()) }
            }
            val results = cached
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
}
