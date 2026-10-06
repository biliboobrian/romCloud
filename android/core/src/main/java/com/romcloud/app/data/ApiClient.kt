package com.romcloud.app.data

import com.romcloud.app.I18n
import com.romcloud.core.R
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
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class ApiException(message: String, val code: Int = 0) : IOException(message)

class ApiClient(private val settings: Settings) {

    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /**
     * Serveur configuré joint (true) ou injoignable (false), d'après chaque requête qui lui est
     * adressée : suivi par [Connectivity].
     */
    @Volatile
    var onReachability: (Boolean) -> Unit = {}

    /**
     * Requêtes destinées au serveur configuré (y compris les images Coil) : clé d'API et langue
     * de l'application (le serveur renvoie ses messages d'erreur dans cette langue).
     */
    private val authInterceptor = Interceptor { chain ->
        val config = settings.config.value
        val request = chain.request()
        val base = config.serverUrl.toHttpUrlOrNull()
        val sameServer = base != null && request.url.host == base.host && request.url.port == base.port
        if (!sameServer) return@Interceptor chain.proceed(request)
        val builder = request.newBuilder().header("Accept-Language", I18n.language())
        if (config.apiKey.isNotEmpty()) builder.header("Authorization", "Bearer ${config.apiKey}")
        try {
            chain.proceed(builder.build()).also { onReachability(true) }
        } catch (e: IOException) {
            // Requête annulée (téléchargement interrompu…) : ne dit rien de la connexion.
            if (!chain.call().isCanceled()) onReachability(false)
            throw e
        }
    }

    val http: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun baseUrl(): String {
        val url = settings.config.value.serverUrl
        if (url.isBlank()) throw ApiException(I18n.get(R.string.err_no_server))
        return url
    }

    fun url(path: String): String = baseUrl() + path

    fun mediaUrl(game: Game, type: String): String? {
        val has = if (type == "boxart") game.hasBoxart else game.hasScreenshot
        if (!has || settings.config.value.serverUrl.isBlank()) return null
        return url("/api/games/${game.id}/media/$type?v=${game.updatedAt}")
    }

    fun systemImageUrl(system: GameSystem): String? {
        if (!system.hasImage || settings.config.value.serverUrl.isBlank()) return null
        return url("/api/systems/${system.id}/image?v=${system.imageVersion}")
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
            response.code == 401 && serverMessage == null -> I18n.get(R.string.err_api_key)
            serverMessage != null -> serverMessage
            else -> I18n.get(R.string.err_server, response.code)
        }
    }

    suspend fun info(): ServerInfo = json.decodeFromString(get("/api/info"))

    /** Teste une adresse et une clé sans modifier les paramètres enregistrés. */
    suspend fun testConnection(serverUrl: String, apiKey: String): ServerInfo = withContext(Dispatchers.IO) {
        val base = Settings.normalizeUrl(serverUrl)
        if (base.toHttpUrlOrNull() == null) throw ApiException(I18n.get(R.string.err_invalid_address))
        val client = http.newBuilder().apply { interceptors().clear() }.build()
        fun call(path: String): String {
            val request = Request.Builder().url(base + path).apply {
                header("Accept-Language", I18n.language())
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

    /** Recherche dans tous les systèmes. */
    suspend fun searchRaw(query: String): String = get("/api/search?q=" + URLEncoder.encode(query, "UTF-8"))

    /** BIOS présents sur le serveur pour un système (sans le catalogue des cœurs RetroArch). */
    suspend fun biosRaw(systemId: String): String = get("/api/systems/$systemId/bios?catalog=0")

    fun biosFileUrl(bios: BiosFile): String = url("/api/bios/${bios.id}/file")

    /** APK des émulateurs Android disponibles sur le serveur. */
    suspend fun apksRaw(): String = get("/api/apks")

    fun apkFileUrl(apk: EmulatorApk): String = url("/api/apks/${apk.id}/file")
}
