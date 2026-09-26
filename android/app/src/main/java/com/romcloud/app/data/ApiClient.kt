package com.romcloud.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(message: String, val code: Int = 0) : IOException(message)

class ApiClient(private val settings: Settings) {

    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** Ajoute la clé d'API aux requêtes destinées au serveur configuré (y compris les images Coil). */
    private val authInterceptor = Interceptor { chain ->
        val config = settings.config.value
        val request = chain.request()
        val base = config.serverUrl.toHttpUrlOrNull()
        val sameServer = base != null && request.url.host == base.host && request.url.port == base.port
        if (sameServer && config.apiKey.isNotEmpty()) {
            chain.proceed(request.newBuilder().header("Authorization", "Bearer ${config.apiKey}").build())
        } else {
            chain.proceed(request)
        }
    }

    val http: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun baseUrl(): String {
        val url = settings.config.value.serverUrl
        if (url.isBlank()) throw ApiException("Aucun serveur configuré")
        return url
    }

    fun url(path: String): String = baseUrl() + path

    fun mediaUrl(game: Game, type: String): String? {
        val has = if (type == "boxart") game.hasBoxart else game.hasScreenshot
        if (!has || settings.config.value.serverUrl.isBlank()) return null
        return url("/api/games/${game.id}/media/$type?v=${game.updatedAt}")
    }

    fun fileUrl(game: Game): String = url("/api/games/${game.id}/file")

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url(path)).build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException(errorMessage(response, body), response.code)
            body
        }
    }

    private fun errorMessage(response: Response, body: String): String {
        val serverMessage = runCatching {
            json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content
        }.getOrNull()
        return when {
            response.code == 401 -> "Clé d’API invalide ou manquante"
            serverMessage != null -> serverMessage
            else -> "Erreur serveur ${response.code}"
        }
    }

    suspend fun info(): ServerInfo = json.decodeFromString(get("/api/info"))

    /** Teste une adresse et une clé sans modifier les paramètres enregistrés. */
    suspend fun testConnection(serverUrl: String, apiKey: String): ServerInfo = withContext(Dispatchers.IO) {
        val base = Settings.normalizeUrl(serverUrl)
        if (base.toHttpUrlOrNull() == null) throw ApiException("Adresse invalide")
        val client = http.newBuilder().apply { interceptors().clear() }.build()
        fun call(path: String): String {
            val request = Request.Builder().url(base + path).apply {
                if (apiKey.isNotBlank()) header("Authorization", "Bearer ${apiKey.trim()}")
            }.build()
            return client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw ApiException(errorMessage(response, body), response.code)
                body
            }
        }
        val info = json.decodeFromString<ServerInfo>(call("/api/info"))
        call("/api/status")
        info
    }

    suspend fun systemsRaw(): String = get("/api/systems")

    suspend fun gamesRaw(systemId: String): String = get("/api/systems/$systemId/games")
}
