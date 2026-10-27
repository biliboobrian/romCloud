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

/** Requête au serveur refusée sans réseau : l'application est en mode hors ligne. */
class OfflineException : IOException(I18n.get(R.string.err_offline))

/**
 * [platform] : « android » ou « androidtv », envoyé au serveur (en-tête X-RomCloud-Platform) qui ne
 * liste que les systèmes proposés à cette plateforme. [deviceId] : identifiant de l'appareil
 * (X-RomCloud-Device-Id), qui compte les appareils d'une clé d'accès générée sur le serveur.
 */
class ApiClient(private val settings: Settings, val platform: String, val deviceId: String) {

    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /**
     * Serveur configuré joint (true) ou injoignable (false), d'après chaque requête qui lui est
     * adressée : suivi par [Connectivity].
     */
    @Volatile
    var onReachability: (Boolean) -> Unit = {}

    /** Mode hors ligne ([Connectivity]) : aucune requête vers le serveur, sauf le ping. */
    @Volatile
    var isOffline: () -> Boolean = { false }

    /**
     * En-têtes ajoutés aux requêtes vers le serveur (appareil, version, session du profil) : le
     * serveur note qui télécharge quoi. Ceux déjà présents dans la requête sont gardés.
     */
    @Volatile
    var clientHeaders: () -> Map<String, String> = { emptyMap() }

    /**
     * Requêtes destinées au serveur configuré (y compris les images Coil) : clé d'API, langue
     * de l'application (le serveur renvoie ses messages d'erreur dans cette langue) et plateforme.
     */
    private val authInterceptor = Interceptor { chain ->
        val config = settings.config.value
        val request = chain.request()
        val base = config.serverUrl.toHttpUrlOrNull()
        val sameServer = base != null && request.url.host == base.host && request.url.port == base.port
        if (!sameServer) return@Interceptor chain.proceed(request)
        // Hors ligne : refusée sans réseau (listes relues depuis le cache, images du cache disque).
        if (isOffline() && request.tag(Ping::class.java) == null) throw OfflineException()
        val builder = request.newBuilder().header("Accept-Language", I18n.language()).header("X-RomCloud-Platform", platform)
            .header("X-RomCloud-Device-Id", deviceId)
        // Valeur refusée par OkHttp (caractère hors ASCII dans le nom de l'appareil) : en-tête omis.
        for ((name, value) in clientHeaders()) if (request.header(name) == null) runCatching { builder.header(name, value) }
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

    /** En-têtes de la clé d'API et de l'appareil (connexions hors OkHttp : relais du jeu par Internet). */
    fun authHeaders(): Map<String, String> {
        val key = settings.config.value.apiKey
        val device = mapOf("X-RomCloud-Device-Id" to deviceId)
        return if (key.isNotEmpty()) device + ("Authorization" to "Bearer $key") else device
    }

    /**
     * Image d'un jeu ; [size] : version allégée du serveur ([MediaSize.THUMB] : grilles et listes,
     * [MediaSize.MEDIUM] : fiche et fond d'écran), sinon l'original.
     */
    fun mediaUrl(game: Game, type: String, size: String? = null): String? {
        val has = if (type == "boxart") game.hasBoxart else type in game.screenshotTypes
        if (!has || settings.config.value.serverUrl.isBlank()) return null
        val sized = size?.let { "&size=$it" }.orEmpty()
        return url("/api/games/${game.id}/media/$type?v=${game.updatedAt}$sized")
    }

    /** Image du système, en vignette (cartes des systèmes, pastilles). */
    fun systemImageUrl(system: GameSystem): String? {
        if (!system.hasImage || settings.config.value.serverUrl.isBlank()) return null
        return url("/api/systems/${system.id}/image?v=${system.imageVersion}&size=${MediaSize.THUMB}")
    }

    fun fileUrl(game: Game): String = fileUrl(game.id)

    /** Fichier d'un jeu ou d'une de ses parties (disque, mise à jour, DLC). */
    fun fileUrl(id: Long): String = url("/api/games/$id/file")

    /**
     * Fichier [path] du dossier d'un jeu en dossier (PC) ; [packed] : son archive (jeu compressé ;
     * zst=1 : archives zstd lues par l'application).
     */
    fun folderFileUrl(id: Long, path: String, packed: Boolean = false): String =
        url("/api/games/$id/file?path=${java.net.URLEncoder.encode(path, "UTF-8").replace("+", "%20")}${if (packed) "&packed=1&zst=1" else ""}")

    /** Fichiers d'un jeu en dossier (PC), à télécharger un par un (archives zstd comprises : zst=1). */
    suspend fun folderFiles(id: Long): List<FolderFile> =
        json.decodeFromString(FolderListing.serializer(), get("/api/games/$id/files?zst=1")).files

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url(path)).build()
        http.newCall(request).execute().use { response ->
            val body = response.body.string()
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

    /**
     * Sonde de connexion (/api/ping, permise hors ligne) : true si le serveur répond, quel que soit
     * le code ; null sans serveur configuré (ou adresse invalide).
     */
    suspend fun ping(): Boolean? = withContext(Dispatchers.IO) {
        val request = runCatching { Request.Builder().url(url("/api/ping")).tag(Ping::class.java, Ping).build() }.getOrNull()
            ?: return@withContext null
        runCatching { pingClient.newCall(request).execute().close() }.isSuccess
    }

    private object Ping

    private val pingClient: OkHttpClient by lazy {
        http.newBuilder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).callTimeout(6, TimeUnit.SECONDS).build()
    }

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
                val body = response.body.string()
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

    /** Fiche d'un jeu (avec ses parties). */
    suspend fun gameRaw(id: Long): String = get("/api/games/$id")

    /** Recherche dans tous les systèmes. */
    suspend fun searchRaw(query: String): String = get("/api/search?q=" + URLEncoder.encode(query, "UTF-8"))

    /** BIOS présents sur le serveur pour un système (sans le catalogue des cœurs RetroArch). */
    suspend fun biosRaw(systemId: String): String = get("/api/systems/$systemId/bios?catalog=0")

    fun biosFileUrl(bios: BiosFile): String = url("/api/bios/${bios.id}/file")

    /** APK des émulateurs Android disponibles sur le serveur. */
    suspend fun apksRaw(): String = get("/api/apks")

    fun apkFileUrl(apk: EmulatorApk): String = url("/api/apks/${apk.id}/file")
}

/** Tailles des images allégées du serveur (server/src/media-variants.js). */
object MediaSize {
    /** Vignette (400 px au plus) : grilles, listes, petites jaquettes. */
    const val THUMB = "thumb"
    /** Version moyenne (800 px au plus) : fiche du jeu, fonds d'écran, galeries. */
    const val MEDIUM = "medium"
}

/** Adresse d'une image d'un jeu : jeu, type (« boxart », « screenshot »…), taille ([MediaSize], null : original). */
typealias MediaUrl = (game: Game, type: String, size: String?) -> String?
