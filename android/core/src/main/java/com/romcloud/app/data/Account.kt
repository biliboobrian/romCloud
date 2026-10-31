package com.romcloud.app.data

import android.content.Context
import android.os.Build
import android.provider.Settings as AndroidSettings
import com.romcloud.app.I18n
import com.romcloud.core.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

@Serializable
data class AccountUser(val id: Long, val username: String, val avatar: String? = null)

@Serializable
private data class SessionResponse(val token: String, val user: AccountUser)

@Serializable
private data class MeResponse(val user: AccountUser, val avatars: List<String> = emptyList(), val playSeconds: Long = 0, val gamesPlayed: Int = 0)

@Serializable
data class PlaytimeEntry(val gameId: Long, val seconds: Long, val sessions: Int = 0, val lastPlayedAt: String? = null)

/** Statistiques du profil (GET /api/account/stats). */
@Serializable
data class ProfileStats(
    val totals: Totals = Totals(),
    val devices: List<Device> = emptyList(),
    val topGames: List<TopGame> = emptyList(),
    val history: List<Session> = emptyList(),
) {
    @Serializable
    data class Totals(val seconds: Long = 0, val sessions: Int = 0, val gamesPlayed: Int = 0, val gamesDownloaded: Int = 0)

    /** Appareil du profil : jeux téléchargés (distincts) et temps de jeu dessus. */
    @Serializable
    data class Device(
        val device: String? = null, val platform: String? = null, val games: Int = 0, val downloads: Int = 0,
        val bytes: Long = 0, val lastDownloadAt: String? = null, val seconds: Long = 0, val sessions: Int = 0,
    )

    @Serializable
    data class TopGame(val gameId: Long, val title: String? = null, val systemId: String? = null, val seconds: Long = 0, val sessions: Int = 0, val lastPlayedAt: String? = null)

    /** Partie de l'historique ; [endedAt] : « 2026-10-24 18:03:12 » (UTC). */
    @Serializable
    data class Session(
        val gameId: Long, val title: String? = null, val systemId: String? = null, val seconds: Long = 0,
        val endedAt: String = "", val device: String? = null, val platform: String? = null,
    )
}

@Serializable
data class OnlineSave(
    val gameId: Long,
    val core: String,
    val kind: String,
    val size: Long,
    val savedAt: String,
    val device: String? = null,
    val platform: String? = null,
) {
    val savedAtMillis: Long get() = runCatching { Instant.parse(savedAt).toEpochMilli() }.getOrDefault(0L)
}

/** Demande de connexion d'une TV : code (affiché en QR code) et secret pour attendre la validation. */
@Serializable
data class PairRequest(val code: String, val secret: String, val expiresAt: String) {
    /** Contenu du QR code, lu par l'application téléphone (Profil › Connecter une TV). */
    val qrContent: String get() = "romcloud://pair?code=$code"
}

@Serializable
data class PairStatus(val status: String, val token: String? = null, val user: AccountUser? = null)

@Serializable
data class PairInfo(val device: String? = null, val platform: String? = null)

/**
 * TV du profil prête à recevoir un jeu diffusé (application RomCloud ouverte) : adresses sur le
 * réseau local, port d'écoute, clé à présenter et définition de son écran.
 */
@Serializable
data class StreamReceiver(
    val id: Long,
    val device: String? = null,
    val addresses: List<String>,
    val port: Int,
    val key: String,
    val width: Int? = null,
    val height: Int? = null,
) {
    val name: String get() = device?.takeIf { it.isNotBlank() } ?: "Android TV"
}

/** Profil affiché : [signedIn] et, une fois relu sur le serveur, ses totaux ([offline] : non relu). */
data class AccountState(
    val signedIn: Boolean = false,
    val username: String = "",
    /** Avatar choisi (émoji, affiché à côté du nom dans les lobbies) ; [avatars] : ceux proposés par le serveur. */
    val avatar: String? = null,
    val avatars: List<String> = emptyList(),
    val playSeconds: Long = 0,
    val gamesPlayed: Int = 0,
    val offline: Boolean = false,
)

/**
 * Profil du joueur sur le serveur RomCloud : connexion (mot de passe, ou QR code validé depuis un
 * téléphone pour la TV), temps de jeu par jeu, sauvegardes en ligne de l'émulateur intégré et
 * erreurs signalées à l'administration. La session et le travail en attente (temps de jeu, envois
 * de sauvegardes de l'émulateur intégré, qui tourne dans un autre processus arrêté brutalement à la
 * sortie du jeu) sont gardés dans les préférences et envoyés au retour dans l'application.
 */
class Account(private val context: Context, private val api: ApiClient, private val scope: CoroutineScope) {

    private val prefs = context.getSharedPreferences("romcloud_account", Context.MODE_PRIVATE)
    /**
     * Travail en attente (temps de jeu, sauvegardes à envoyer) : un fichier par élément. Partagé avec
     * le processus de l'émulateur intégré, contrairement aux préférences, gardées en mémoire par
     * chaque processus (ce qu'écrit l'un n'est pas relu par l'autre).
     */
    private val queueDir = File(context.filesDir, "account-queue")
    private val json get() = api.json
    private val flushLock = Mutex()

    private val _state = MutableStateFlow(storedState())
    val state: StateFlow<AccountState> = _state.asStateFlow()

    private val _playtime = MutableStateFlow<Map<Long, Long>>(emptyMap())
    /** Temps de jeu du profil par jeu (secondes). */
    val playtime: StateFlow<Map<Long, Long>> = _playtime.asStateFlow()

    private fun storedState(): AccountState {
        val token = prefs.getString(KEY_TOKEN, null)
        return if (token == null) AccountState() else AccountState(signedIn = true, username = prefs.getString(KEY_USER, "").orEmpty(), offline = true)
    }

    private val token: String? get() = prefs.getString(KEY_TOKEN, null)

    val isTv: Boolean get() = api.platform == "androidtv"

    /** Nom de l'appareil (réglages Android, sinon fabricant et modèle). */
    val deviceName: String by lazy {
        runCatching { AndroidSettings.Global.getString(context.contentResolver, AndroidSettings.Global.DEVICE_NAME) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
    }

    private val appVersion: String by lazy {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }

    // -------------------------------------------------------------------------
    // Requêtes
    // -------------------------------------------------------------------------

    /** Appareil, version et session du profil : joints aux téléchargements (journal du serveur). */
    fun clientHeaders(): Map<String, String> = buildMap {
        put("X-RomCloud-Device", deviceName)
        put("X-RomCloud-Version", appVersion)
        token?.let { put("X-RomCloud-Session", it) }
    }

    private fun request(path: String, withSession: Boolean = true): Request.Builder =
        Request.Builder().url(api.url(path)).apply {
            header("X-RomCloud-Device", deviceName)
            header("X-RomCloud-Platform", api.platform)
            header("X-RomCloud-Version", appVersion)
            if (withSession) token?.let { header("X-RomCloud-Session", it) }
        }

    private fun jsonBody(vararg pairs: Pair<String, Any?>): RequestBody = buildJsonObject {
        for ((k, v) in pairs) when (v) {
            null -> Unit
            is Number -> put(k, JsonPrimitive(v))
            is Boolean -> put(k, JsonPrimitive(v))
            else -> put(k, JsonPrimitive(v.toString()))
        }
    }.toString().toRequestBody(JSON)

    /** Exécute la requête ; renvoie le corps (texte), ou lève [ApiException] avec le message du serveur. */
    private suspend fun execute(builder: Request.Builder, timeoutSeconds: Long = 20): String = withContext(Dispatchers.IO) {
        val client = api.http.newBuilder().readTimeout(timeoutSeconds, TimeUnit.SECONDS).writeTimeout(timeoutSeconds, TimeUnit.SECONDS).build()
        client.newCall(builder.build()).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) throw failure(response.code, body)
            body
        }
    }

    private fun failure(code: Int, body: String): ApiException {
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val message = obj?.get("error")?.jsonPrimitive?.content
        // Session refusée (déconnectée depuis l'administration, mot de passe changé) : oubliée ici aussi.
        if (obj?.get("code")?.jsonPrimitive?.content == "errors.signedOut") forget()
        return ApiException(message ?: I18n.get(R.string.err_server, code), code)
    }

    // -------------------------------------------------------------------------
    // Connexion
    // -------------------------------------------------------------------------

    private fun remember(session: SessionResponse) {
        prefs.edit()
            .putString(KEY_TOKEN, session.token)
            .putString(KEY_USER, session.user.username)
            .putLong(KEY_USER_ID, session.user.id)
            .commit()
        _state.value = AccountState(signedIn = true, username = session.user.username, offline = true)
        // Favoris choisis sans profil : ajoutés à ceux du profil.
        val local = _favorites.value
        scope.launch {
            for (game in local) runCatching { execute(request(favoritePath(game.id)).put(jsonBody()), 10) }
            refresh()
        }
    }

    private fun forget() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_USER).remove(KEY_USER_ID).commit()
        _state.value = AccountState()
        _playtime.value = emptyMap()
        saveFavorites(emptyList())
    }

    suspend fun login(username: String, password: String) {
        val body = execute(request("/api/account/login", withSession = false).post(jsonBody("username" to username.trim(), "password" to password)))
        remember(json.decodeFromString(SessionResponse.serializer(), body))
    }

    suspend fun register(username: String, password: String) {
        val body = execute(request("/api/account/register", withSession = false).post(jsonBody("username" to username.trim(), "password" to password)))
        remember(json.decodeFromString(SessionResponse.serializer(), body))
    }

    suspend fun logout() {
        runCatching { execute(request("/api/account/logout").post(jsonBody())) }
        forget()
    }

    /** Relit le profil et le temps de jeu ; envoie d'abord le travail en attente. */
    suspend fun refresh() {
        if (token == null) return
        // Sauvegardes faites hors ligne : envoyées seulement avec l'accord de l'utilisateur (uploadPending).
        flush()
        try {
            val me = json.decodeFromString(MeResponse.serializer(), execute(request("/api/account/me")))
            val list = json.decodeFromString(ListSerializer(PlaytimeEntry.serializer()), execute(request("/api/account/playtime")))
            _playtime.value = list.associate { it.gameId to it.seconds }
            refreshFavorites()
            _state.value = AccountState(
                signedIn = true, username = me.user.username, avatar = me.user.avatar, avatars = me.avatars,
                playSeconds = me.playSeconds, gamesPlayed = me.gamesPlayed,
            )
        } catch (e: IOException) {
            if (token != null) _state.value = _state.value.copy(offline = true)
        }
    }

    /** Avatar du profil (un de [AccountState.avatars], null : aucun), affiché à côté du nom dans les lobbies. */
    suspend fun setAvatar(avatar: String?) {
        val body = buildJsonObject { put("avatar", avatar?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull) }.toString().toRequestBody(JSON)
        execute(request("/api/account/avatar").put(body))
        _state.value = _state.value.copy(avatar = avatar)
    }

    // -------------------------------------------------------------------------
    // Connexion d'une TV par QR code
    // -------------------------------------------------------------------------

    /** TV : nouvelle demande de connexion (code affiché en QR code, valable quelques minutes). */
    suspend fun createPair(): PairRequest =
        json.decodeFromString(PairRequest.serializer(), execute(request("/api/account/pair", withSession = false).post(jsonBody())))

    /** TV : état de la demande ; validée -> la TV est connectée au profil du téléphone. */
    suspend fun pollPair(pair: PairRequest): PairStatus {
        val path = "/api/account/pair/${pair.code}?secret=" + URLEncoder.encode(pair.secret, "UTF-8")
        val status = json.decodeFromString(PairStatus.serializer(), execute(request(path, withSession = false)))
        if (status.status == "approved" && status.token != null && status.user != null) remember(SessionResponse(status.token, status.user))
        return status
    }

    /** Téléphone : appareil qui attend derrière un code scanné (ou saisi). */
    suspend fun pairInfo(code: String): PairInfo =
        json.decodeFromString(PairInfo.serializer(), execute(request("/api/account/pair/${normalizeCode(code)}", withSession = false)))

    /** Téléphone : connecte l'appareil du code à ce profil. */
    suspend fun approvePair(code: String) {
        execute(request("/api/account/pair/${normalizeCode(code)}/approve").post(jsonBody()))
    }

    // -------------------------------------------------------------------------
    // Diffusion d'un jeu du téléphone sur une TV du profil
    // -------------------------------------------------------------------------

    /** TV : s'annonce prête à recevoir (à renouveler toutes les quelques secondes). */
    suspend fun announceReceiver(addresses: List<String>, port: Int, key: String, width: Int, height: Int) {
        if (token == null) return
        val body = buildJsonObject {
            put("addresses", JsonArray(addresses.map(::JsonPrimitive)))
            put("port", JsonPrimitive(port))
            put("key", JsonPrimitive(key))
            put("width", JsonPrimitive(width))
            put("height", JsonPrimitive(height))
        }.toString().toRequestBody(JSON)
        execute(request("/api/account/stream/receiver").put(body), 10)
    }

    // -------------------------------------------------------------------------
    // Jeu à plusieurs par Internet (relais du serveur)
    // -------------------------------------------------------------------------

    /**
     * Annonce de cet appareil (partie proposée, « en partie », aller-retour mesuré) ; renvoie les
     * autres appareils connectés, ou null sans profil (ou hors ligne : exception).
     */
    suspend fun playPresence(
        deviceId: String,
        name: String,
        platform: String,
        hosting: com.romcloud.app.netplay.NetplayProtocol.HostedGame?,
        busy: Boolean,
        rtt: Int,
        activeAt: Long = 0,
    ): com.romcloud.app.netplay.PlayPresenceResponse? {
        if (token == null) return null
        val body = buildJsonObject {
            put("deviceId", JsonPrimitive(deviceId))
            put("name", JsonPrimitive(name))
            put("platform", JsonPrimitive(platform))
            put("busy", JsonPrimitive(busy))
            put("rtt", JsonPrimitive(rtt))
            if (activeAt > 0) put("activeAt", JsonPrimitive(activeAt))
            if (hosting != null) put("hosting", json.encodeToJsonElement(com.romcloud.app.netplay.NetplayProtocol.HostedGame.serializer(), hosting))
        }.toString().toRequestBody(JSON)
        return json.decodeFromString(com.romcloud.app.netplay.PlayPresenceResponse.serializer(), execute(request("/api/account/play/presence").put(body), 8))
    }

    // ---- Lobbies du multijoueur (server/src/lobbies.js) ----

    /** Joueurs connectés et lobbies ouverts (null sans profil). */
    suspend fun lobbies(): com.romcloud.app.netplay.LobbyList? {
        if (token == null) return null
        return json.decodeFromString(com.romcloud.app.netplay.LobbyList.serializer(), execute(request("/api/account/play/lobbies"), 8))
    }

    private fun lobbyBody(deviceId: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): RequestBody =
        buildJsonObject {
            put("deviceId", JsonPrimitive(deviceId))
            build()
        }.toString().toRequestBody(JSON)

    private fun lobbyGameJson(game: com.romcloud.app.netplay.LobbyGame?, modes: List<String>?): kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {
        put("game", if (game == null) kotlinx.serialization.json.JsonNull else json.encodeToJsonElement(com.romcloud.app.netplay.LobbyGame.serializer(), game))
        put("modes", JsonArray(modes.orEmpty().map { JsonPrimitive(it) }))
    }

    private fun lobbyPath(id: String, suffix: String = ""): String {
        require(id.isNotEmpty() && id.length <= 32 && id.all { it.isLetterOrDigit() }) { "lobby invalide : $id" }
        return "/api/account/play/lobbies/$id$suffix"
    }

    private fun systemsJson(systems: List<String>?): kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {
        if (systems != null) put("systems", JsonArray(systems.map { JsonPrimitive(it) }))
    }

    /** [systems] : systèmes que l'appareil sait jouer en réseau avec un émulateur intégré. */
    suspend fun createLobby(deviceId: String, game: com.romcloud.app.netplay.LobbyGame?, modes: List<String>, systems: List<String>? = null) {
        execute(request("/api/account/play/lobbies").post(lobbyBody(deviceId) {
            if (game != null) lobbyGameJson(game, modes)()
            systemsJson(systems)()
        }), 8)
    }

    suspend fun joinLobby(id: String, deviceId: String, systems: List<String>? = null) {
        execute(request(lobbyPath(id, "/join")).post(lobbyBody(deviceId) { systemsJson(systems)() }), 8)
    }

    suspend fun leaveLobby(id: String, deviceId: String) {
        execute(request(lobbyPath(id, "/leave")).post(lobbyBody(deviceId)), 8)
    }

    /** Hôte : jeu ([game], [modes]) ou façon de jouer ([mode]) du lobby. */
    suspend fun updateLobby(id: String, deviceId: String, game: com.romcloud.app.netplay.LobbyGame?, modes: List<String>?, mode: String?) {
        val body = lobbyBody(deviceId) {
            if (game != null) lobbyGameJson(game, modes)()
            if (mode != null) put("mode", JsonPrimitive(mode))
        }
        execute(request(lobbyPath(id)).put(body), 8)
    }

    suspend fun lobbyStatus(id: String, deviceId: String, status: String) {
        execute(request(lobbyPath(id, "/status")).put(lobbyBody(deviceId) { put("status", JsonPrimitive(status)) }), 8)
    }

    suspend fun dissolveLobby(id: String, deviceId: String) {
        execute(request(lobbyPath(id, "?deviceId=${URLEncoder.encode(deviceId, "UTF-8")}")).delete(), 8)
    }

    suspend fun withdrawPlayPresence(deviceId: String) {
        if (token == null) return
        execute(request("/api/account/play/presence/$deviceId").delete(), 5)
    }

    /** Connexion de relais à ouvrir (partie [session], [channel], rôle [role]), ou null sans profil. */
    fun relayRequest(session: String, channel: String, role: String): com.romcloud.app.netplay.RelayRequest? {
        val session0 = token ?: return null
        val url = runCatching { api.url("/api/play/relay?session=$session&channel=$channel&role=$role") }.getOrNull() ?: return null
        return com.romcloud.app.netplay.RelayRequest(url, api.authHeaders() + mapOf(
            "X-RomCloud-Session" to session0,
            "X-RomCloud-Device" to deviceName,
            "X-RomCloud-Platform" to api.platform,
            "X-RomCloud-Version" to appVersion,
        ))
    }

    /**
     * Connexion au réseau ad hoc PSP de la partie [session] sur le serveur ([kind] : « adhocctl » ou
     * « postoffice »), ou null sans profil.
     */
    fun pspRequest(session: String, kind: String): com.romcloud.app.netplay.RelayRequest? {
        val session0 = token ?: return null
        val url = runCatching { api.url("/api/play/psp/$kind?session=$session") }.getOrNull() ?: return null
        return com.romcloud.app.netplay.RelayRequest(url, api.authHeaders() + mapOf(
            "X-RomCloud-Session" to session0,
            "X-RomCloud-Device" to deviceName,
            "X-RomCloud-Platform" to api.platform,
            "X-RomCloud-Version" to appVersion,
        ))
    }

    /** TV : application quittée, plus proposée aux téléphones. */
    suspend fun withdrawReceiver() {
        if (token == null) return
        runCatching { execute(request("/api/account/stream/receiver").delete(), 5) }
    }

    /** Téléphone : TV du profil allumées avec RomCloud ouvert (vide sans profil ou hors ligne). */
    suspend fun streamReceivers(): List<StreamReceiver> {
        if (token == null) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(StreamReceiver.serializer()), execute(request("/api/account/stream/receivers"), 8))
        }.getOrDefault(emptyList())
    }

    /** Statistiques du profil (temps de jeu, jeux téléchargés par appareil, dernières parties) ; null : sans profil ou hors ligne. */
    suspend fun stats(): ProfileStats? {
        if (token == null) return null
        return runCatching {
            json.decodeFromString(ProfileStats.serializer(), execute(request("/api/account/stats"), 15))
        }.getOrNull()
    }

    // -------------------------------------------------------------------------
    // Temps de jeu
    // -------------------------------------------------------------------------

    /**
     * Ajoute une partie au temps de jeu (mis en file : envoyé tout de suite si possible). Appelable
     * depuis le processus de l'émulateur intégré : l'envoi est alors fait par l'application.
     */
    fun recordPlaytime(gameId: Long, seconds: Long, sendNow: Boolean = true) {
        if (token == null || gameId <= 0 || seconds < MIN_PLAY_SECONDS) return
        // Fin de la partie jointe : historique du profil juste même si la partie part plus tard.
        enqueue("play-${System.currentTimeMillis()}-${System.nanoTime()}", "$gameId:$seconds:${System.currentTimeMillis()}")
        _playtime.value = _playtime.value + (gameId to ((_playtime.value[gameId] ?: 0) + seconds))
        if (sendNow) scope.launch { refresh() }
    }

    /**
     * Partie lancée dans un émulateur externe : la durée est comptée jusqu'au retour dans
     * l'application ([onAppResumed]).
     */
    fun startExternalSession(gameId: Long) {
        if (token == null) return
        prefs.edit().putLong(KEY_EXTERNAL_GAME, gameId).putLong(KEY_EXTERNAL_START, System.currentTimeMillis()).commit()
    }

    /** Retour dans l'application : partie externe terminée, travail en attente envoyé. */
    fun onAppResumed() {
        val gameId = prefs.getLong(KEY_EXTERNAL_GAME, 0)
        if (gameId > 0) {
            val seconds = (System.currentTimeMillis() - prefs.getLong(KEY_EXTERNAL_START, 0)) / 1000
            prefs.edit().remove(KEY_EXTERNAL_GAME).remove(KEY_EXTERNAL_START).commit()
            // Au-delà de 12 h : l'application est sans doute restée en arrière-plan sans partie.
            if (seconds in MIN_PLAY_SECONDS..MAX_EXTERNAL_SECONDS) recordPlaytime(gameId, seconds, sendNow = false)
        }
        scope.launch { refresh() }
    }

    // -------------------------------------------------------------------------
    // Favoris
    // -------------------------------------------------------------------------

    private val favoritesFile = File(context.filesDir, "favorites.json")
    private val favoritesSerializer = ListSerializer(Game.serializer())

    private val _favorites = MutableStateFlow(
        runCatching { json.decodeFromString(favoritesSerializer, favoritesFile.readText()) }.getOrDefault(emptyList()),
    )

    /**
     * Jeux favoris (téléchargés ou non), du plus récemment ajouté au plus ancien : ceux du profil
     * (communs à ses appareils), sinon ceux de l'appareil ; copie de l'appareil hors ligne.
     */
    val favorites: StateFlow<List<Game>> = _favorites.asStateFlow()

    private fun favoritePath(gameId: Long) = "/api/account/favorites/$gameId"

    private fun saveFavorites(games: List<Game>) {
        _favorites.value = games
        runCatching {
            val tmp = File(favoritesFile.path + ".tmp")
            tmp.writeText(json.encodeToString(favoritesSerializer, games))
            tmp.renameTo(favoritesFile)
        }
    }

    /** Favoris du profil relus sur le serveur (copie de l'appareil gardée s'il est injoignable). */
    suspend fun refreshFavorites() {
        if (token == null) return
        runCatching { json.decodeFromString(favoritesSerializer, execute(request("/api/account/favorites"), 10)) }
            .onSuccess(::saveFavorites)
    }

    /**
     * Ajoute ([favorite]) ou retire le jeu des favoris : tout de suite sur l'appareil, puis dans le
     * profil (ApiException si le serveur le refuse ou est injoignable : favoris remis comme avant).
     */
    suspend fun setFavorite(game: Game, favorite: Boolean) {
        val before = _favorites.value
        val others = before.filter { it.id != game.id }
        saveFavorites(if (favorite) listOf(game) + others else others)
        if (token == null) return
        try {
            val call = request(favoritePath(game.id))
            execute(if (favorite) call.put(jsonBody()) else call.delete(), 10)
        } catch (e: Exception) {
            saveFavorites(before)
            throw e
        }
    }

    // -------------------------------------------------------------------------
    // Réglages des jeux (jeux PC : Proton, Turnip, DXVK, qualité graphique)
    // -------------------------------------------------------------------------

    private val gameSettingsCache = context.getSharedPreferences("romcloud_game_settings", Context.MODE_PRIVATE)

    private fun cacheGameSettings(gameId: Long, settings: Map<String, String>) {
        val edit = gameSettingsCache.edit()
        if (settings.isEmpty()) edit.remove(gameId.toString())
        else edit.putString(gameId.toString(), buildJsonObject { for ((k, v) in settings) put(k, JsonPrimitive(v)) }.toString())
        edit.apply()
    }

    /** Réglages du jeu gardés sur l'appareil (derniers lus ou choisis) : lancement sans attendre le serveur. */
    fun cachedGameSettings(gameId: Long): Map<String, String> =
        gameSettingsCache.getString(gameId.toString(), null)
            ?.let { saved -> runCatching { json.parseToJsonElement(saved).jsonObject.mapValues { it.value.jsonPrimitive.content } }.getOrNull() }
            .orEmpty()

    /** Réglages du jeu choisis par le profil, relus sur le serveur (copie de l'appareil s'il est injoignable). */
    suspend fun gameSettings(gameId: Long): Map<String, String> {
        if (token == null) return cachedGameSettings(gameId)
        return runCatching {
            val obj = json.parseToJsonElement(execute(request("/api/account/game-settings?gameId=$gameId"), 10)).jsonObject
            obj["settings"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty().also { cacheGameSettings(gameId, it) }
        }.getOrElse { cachedGameSettings(gameId) }
    }

    /**
     * Réglages du jeu ([settings] : ceux choisis, sans ceux par défaut) : gardés sur l'appareil, puis
     * enregistrés dans le profil (ApiException si le serveur les refuse ou est injoignable).
     */
    suspend fun setGameSettings(gameId: Long, settings: Map<String, String>) {
        cacheGameSettings(gameId, settings)
        if (token == null) return
        val body = buildJsonObject {
            put("gameId", JsonPrimitive(gameId))
            put("settings", buildJsonObject { for ((k, v) in settings) put(k, JsonPrimitive(v)) })
        }.toString().toRequestBody(JSON)
        execute(request("/api/account/game-settings").put(body), 10)
    }

    // -------------------------------------------------------------------------
    // Catalogue des options des cœurs (options par défaut choisies dans l'administration)
    // -------------------------------------------------------------------------

    /** Option déclarée par un cœur intégré ou réglage de RomCloud PC : valeurs (valeur, libellé). */
    data class CatalogOption(val key: String, val label: String, val values: List<Pair<String, String>>, val default: String?)

    private val catalogSent = context.getSharedPreferences("romcloud_core_catalog", Context.MODE_PRIVATE)

    /**
     * Options déclarées par le cœur [core] lancé pour le système [systemId], envoyées au serveur pour que
     * l'administration les propose en listes. Envoyées une fois par version de l'application, de cœur
     * (options déclarées) et par système ; réessayé au lancement suivant si le serveur est injoignable.
     */
    fun reportCoreOptions(core: String, systemId: String, options: List<CatalogOption>) {
        if (options.isEmpty() || runCatching { api.url("") }.isFailure) return
        val body = buildJsonObject {
            put("core", JsonPrimitive(core))
            put("system", JsonPrimitive(systemId))
            put("options", JsonArray(options.map { o ->
                buildJsonObject {
                    put("key", JsonPrimitive(o.key))
                    put("label", JsonPrimitive(o.label))
                    o.default?.let { put("default", JsonPrimitive(it)) }
                    put("values", JsonArray(o.values.map { (value, label) ->
                        buildJsonObject { put("value", JsonPrimitive(value)); put("label", JsonPrimitive(label)) }
                    }))
                }
            }))
        }.toString()
        val key = "$core|$systemId"
        val fingerprint = "$appVersion|${body.hashCode()}"
        if (catalogSent.getString(key, null) == fingerprint) return
        scope.launch {
            runCatching { execute(request("/api/account/core-options").post(body.toRequestBody(JSON)), 20) }
                .onSuccess { catalogSent.edit().putString(key, fingerprint).apply() }
        }
    }

    // -------------------------------------------------------------------------
    // Sauvegardes en ligne (émulateur intégré)
    // -------------------------------------------------------------------------

    private fun savePath(gameId: Long, core: String, kind: String) = "/api/account/saves/$gameId/${URLEncoder.encode(core, "UTF-8")}/$kind"

    /** Sauvegardes en ligne du jeu (vide sans profil ou serveur injoignable). */
    suspend fun saves(gameId: Long): List<OnlineSave> {
        if (token == null) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(OnlineSave.serializer()), execute(request("/api/account/saves?gameId=$gameId"), 10))
        }.getOrDefault(emptyList())
    }

    /**
     * Avant de jouer : les sauvegardes en ligne plus récentes que les fichiers de l'appareil (partie
     * continuée ailleurs) les remplacent. [files] : type (« state », « sram ») -> fichier.
     */
    suspend fun downloadNewer(gameId: Long, core: String, files: Map<String, File>): List<OnlineSave> {
        val updated = mutableListOf<OnlineSave>()
        // Variantes du même cœur (N64 : mupen64plus_next sous Windows, _gles3 sous Android) : la plus récente.
        val latest = saves(gameId).filter { sameSaveCore(it.core, core) }.groupBy { it.kind }.values.map { it.maxBy(OnlineSave::savedAtMillis) }
        for (save in latest) {
            val file = files[save.kind] ?: continue
            if (save.savedAtMillis <= file.lastModified() + 1000) continue
            runCatching {
                withContext(Dispatchers.IO) {
                    val client = api.http.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()
                    client.newCall(request(savePath(gameId, save.core, save.kind)).build()).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                        file.parentFile?.mkdirs()
                        val part = File(file.path + ".download")
                        response.body.byteStream().use { input -> part.outputStream().use { input.copyTo(it) } }
                        if (!part.renameTo(file)) {
                            part.copyTo(file, overwrite = true)
                            part.delete()
                        }
                        file.setLastModified(save.savedAtMillis)
                    }
                }
                updated += save
            }
        }
        return updated
    }

    // -------------------------------------------------------------------------
    // Historique des états (plusieurs par jeu, par appareil, avec miniature)
    // -------------------------------------------------------------------------

    /** États en ligne du jeu (null : sans profil ou serveur injoignable). */
    suspend fun onlineStates(gameId: Long): List<OnlineState>? {
        if (token == null || gameId <= 0) return null
        return runCatching {
            json.decodeFromString(ListSerializer(OnlineState.serializer()), execute(request("/api/account/states?gameId=$gameId"), 10))
        }.getOrNull()
    }

    /**
     * Met en file l'envoi d'un état de l'historique (et de sa miniature) ; [sendNow] : envoyé tout de
     * suite depuis ce processus, sinon par l'application au retour ([flush]).
     */
    fun queueStateUpload(gameId: Long, history: StateHistory, id: String, sendNow: Boolean = false) {
        if (token == null || gameId <= 0) return
        enqueue("${onlinePrefix()}state-$id","$gameId\n${history.dir.absolutePath}")
        if (sendNow) scope.launch { flush(savesOnly = true) }
    }

    /** Télécharge l'état en ligne [id] dans [target]. */
    suspend fun downloadState(id: String, target: File) = withContext(Dispatchers.IO) {
        val client = api.http.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()
        client.newCall(request("/api/account/states/$id").build()).execute().use { response ->
            if (!response.isSuccessful) throw failure(response.code, response.body.string())
            target.parentFile?.mkdirs()
            val part = File(target.path + ".download")
            response.body.byteStream().use { input -> part.outputStream().use { input.copyTo(it) } }
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
        }
    }

    /** Miniature d'un état en ligne (null sans réseau ou sans miniature). */
    suspend fun stateThumbnail(id: String): ByteArray? = runCatching {
        withContext(Dispatchers.IO) {
            api.http.newCall(request("/api/account/states/$id/thumbnail").build()).execute().use { response ->
                if (response.isSuccessful) response.body.bytes() else null
            }
        }
    }.getOrNull()

    /** Épingle ou désépingle un état en ligne. */
    suspend fun pinOnlineState(id: String, pinned: Boolean) {
        execute(request("/api/account/states/$id").patch(jsonBody("pinned" to pinned)))
    }

    suspend fun deleteOnlineState(id: String) {
        try {
            execute(request("/api/account/states/$id").delete())
        } catch (e: ApiException) {
            if (e.code != 404) throw e
        }
    }

    /** Envoie un état de l'historique (fichier puis miniature) ; marqué « envoyé » sur l'appareil. */
    private suspend fun uploadState(gameId: Long, history: StateHistory, meta: StateMeta) {
        val file = history.stateFile(meta.id)
        val body = putCompressed(
            request("/api/account/states/$gameId/${URLEncoder.encode(meta.core, "UTF-8")}/${meta.id}")
                .header("X-Created-At", meta.createdAt.toString())
                .header("X-Pinned", if (meta.pinned) "1" else "0"),
            file,
        )
        val thumbnail = history.thumbnailFile(meta.id)
        // Réponse vide : état plus ancien que ceux gardés en ligne, aussitôt retiré par le serveur.
        if (body.isNotBlank() && thumbnail.isFile) {
            execute(request("/api/account/states/${meta.id}/thumbnail").put(thumbnail.asRequestBody(JPEG)), 60)
        }
        history.markUploaded(meta.id)
    }

    /**
     * Envoie [file] (PUT), compressé en gzip quand c'est plus petit : moins de place sur le serveur et
     * moins de données envoyées. Au téléchargement, OkHttp décompresse lui-même (Accept-Encoding).
     */
    private suspend fun putCompressed(builder: Request.Builder, file: File): String {
        val packed = withContext(Dispatchers.IO) { runCatching { gzipped(file) }.getOrNull() }
        try {
            if (packed != null) builder.header("Content-Encoding", "gzip").header("X-Uncompressed-Size", file.length().toString())
            return execute(builder.put((packed ?: file).asRequestBody(BINARY)), timeoutSeconds = 300)
        } finally {
            packed?.delete()
        }
    }

    /** Copie compressée de [file] dans le cache ; null si la compression n'y gagne rien (état déjà compressé). */
    private fun gzipped(file: File): File? {
        val out = File(context.cacheDir, "upload-${System.nanoTime()}.gz")
        GZIPOutputStream(out.outputStream().buffered(), 64 * 1024).use { gz -> file.inputStream().use { it.copyTo(gz, 64 * 1024) } }
        if (out.length() < file.length() * MIN_GAIN) return out
        out.delete()
        return null
    }

    /** Ajoute un élément à la file (écrit d'un bloc : jamais lu à moitié par l'autre processus). */
    private fun enqueue(name: String, content: String) {
        queueDir.mkdirs()
        val tmp = File(queueDir, ".$name.tmp")
        tmp.writeText(content)
        tmp.renameTo(File(queueDir, name))
    }

    private fun queued(prefix: String): List<File> =
        queueDir.listFiles { f -> f.name.startsWith(prefix) }.orEmpty().sortedBy { it.name }

    /**
     * Sauvegardes de l'émulateur intégré à envoyer : les fichiers modifiés depuis [since]. Une seule
     * entrée par jeu, cœur et type (le fichier le plus récent est envoyé). [sendNow] : envoi tout de
     * suite depuis ce processus (partie en cours) ; sinon par l'application, au retour ([flush]).
     */
    fun queueUploads(gameId: Long, core: String, files: Map<String, File>, since: Long, sendNow: Boolean = false) {
        if (token == null || gameId <= 0) return
        val changed = files.filter { (_, f) -> f.isFile && f.length() > 0 && f.lastModified() >= since }
        if (changed.isEmpty()) return
        for ((kind, file) in changed) enqueue(onlinePrefix() + saveEntryName(gameId, core, kind), file.absolutePath)
        if (sendNow) scope.launch { flush(savesOnly = true) }
    }

    /**
     * Préfixe des sauvegardes et états mis en file en ligne : faits après la reprise des sauvegardes
     * en ligne plus récentes ([downloadNewer]), ils sont envoyés sans demander ([flush]). Sans
     * préfixe : faits hors ligne, envoyés avec l'accord de l'utilisateur ([uploadPending]).
     */
    private fun onlinePrefix() = if (api.isOffline()) "" else ONLINE

    private fun saveEntryName(gameId: Long, core: String, kind: String) = "save-$gameId-$kind-" + core.replace(Regex("[^A-Za-z0-9._]"), "_")

    /** Sauvegardes et états faits hors ligne, en attente d'envoi (proposé au retour en ligne). */
    fun pendingSaves(): Int = if (token == null) 0 else queued("save-").size + queued("state-").size

    /**
     * Envoie les sauvegardes et états faits hors ligne (accord de l'utilisateur) : (envoyés, ignorés).
     * Ignorés : un état dont le jeu a déjà assez d'états plus récents en ligne (il ne serait pas
     * gardé), une sauvegarde dont le serveur a une version plus récente (elle l'écraserait).
     */
    suspend fun uploadPending(): Pair<Int, Int> {
        if (token == null) return 0 to 0
        return flushLock.withLock { withContext(Dispatchers.IO) { sendQueued(prefix = "", skipOutdated = true) } }
    }

    /**
     * Envoie le temps de jeu et, si [includeSaves], les sauvegardes en attente (serveur joignable) ;
     * renvoie le nombre de sauvegardes envoyées. [savesOnly] : processus de l'émulateur intégré (le
     * temps de jeu n'est envoyé que par l'application, pour ne jamais être compté deux fois).
     */
    suspend fun flush(savesOnly: Boolean = false, includeSaves: Boolean = true): Int {
        // Erreurs en attente : envoyées avec ou sans profil.
        if (!savesOnly) flushErrors()
        if (token == null) return 0
        var sent = 0
        flushLock.withLock {
            withContext(Dispatchers.IO) {
                if (!savesOnly) {
                    for (entry in queued("play-")) {
                        val parts = runCatching { entry.readText().split(':') }.getOrNull()
                        val gameId = parts?.getOrNull(0)?.toLongOrNull()
                        val seconds = parts?.getOrNull(1)?.toLongOrNull()
                        val endedAt = parts?.getOrNull(2)?.toLongOrNull()
                        if (gameId == null || seconds == null) {
                            entry.delete()
                            continue
                        }
                        try {
                            execute(request("/api/account/playtime").post(jsonBody("gameId" to gameId, "seconds" to seconds, "at" to endedAt)))
                            entry.delete()
                        } catch (e: ApiException) {
                            // Refus du serveur (jeu supprimé…) : abandonné.
                            entry.delete()
                        } catch (e: IOException) {
                            // réessayé au prochain retour dans l'application
                        }
                    }
                }
                // Faites en ligne seulement : celles faites hors ligne attendent l'accord de l'utilisateur.
                if (includeSaves) sent += sendQueued(prefix = ONLINE, skipOutdated = false).first
            }
        }
        return sent
    }


    /**
     * Sauvegardes puis états en file (appelé sous [flushLock], hors du fil principal) : (envoyés,
     * ignorés). [skipOutdated] : ceux qui ne seraient pas gardés ou écraseraient plus récent sont retirés.
     */
    private suspend fun sendQueued(prefix: String, skipOutdated: Boolean): Pair<Int, Int> {
        var sent = 0
        var skipped = 0
        // Sauvegardes et états en ligne de chaque jeu, relus une fois.
        val savesOf = HashMap<Long, List<OnlineSave>>()
        val statesOf = HashMap<Long, List<OnlineState>>()
        for (entry in queued("${prefix}save-")) {
            val (gameId, kind, core) = entry.name.removePrefix("${prefix}save-").split('-', limit = 3)
            val path = runCatching { entry.readText() }.getOrNull()
            val file = path?.let(::File)
            if (file == null || !file.isFile) {
                entry.delete()
                continue
            }
            val modified = entry.lastModified()
            if (skipOutdated) {
                val online = savesOf.getOrPut(gameId.toLong()) { saves(gameId.toLong()) }
                if (newerOnline(online, core, kind, file.lastModified())) {
                    skipped++
                    entry.delete()
                    continue
                }
            }
            try {
                putCompressed(request(savePath(gameId.toLong(), core, kind)).header("X-Saved-At", file.lastModified().toString()), file)
                sent++
                // Nouvelle sauvegarde mise en file pendant l'envoi : gardée pour le prochain.
                if (entry.lastModified() == modified) entry.delete()
            } catch (e: ApiException) {
                entry.delete()
                reportError("saves:$kind", e.message ?: "upload", file.name)
            } catch (e: IOException) {
                // réessayé au retour de la connexion ou dans l'application
            }
        }
        for (entry in queued("${prefix}state-")) {
            val id = entry.name.removePrefix("${prefix}state-")
            val lines = runCatching { entry.readText().lines() }.getOrNull()
            val gameId = lines?.getOrNull(0)?.toLongOrNull()
            val dir = lines?.getOrNull(1)?.let(::File)
            val core = dir?.parentFile?.name
            val meta = if (dir != null && core != null) StateHistory(dir, core).get(id) else null
            // État supprimé de l'historique entre-temps : rien à envoyer.
            if (gameId == null || dir == null || core == null || meta == null) {
                entry.delete()
                continue
            }
            if (skipOutdated) {
                val online = statesOf.getOrPut(gameId) { onlineStates(gameId).orEmpty() }
                if (tooManyNewer(online, meta.createdAt)) {
                    skipped++
                    entry.delete()
                    continue
                }
            }
            try {
                uploadState(gameId, StateHistory(dir, core), meta)
                sent++
                entry.delete()
            } catch (e: ApiException) {
                entry.delete()
                reportError("states", e.message ?: "upload", id)
            } catch (e: IOException) {
                // réessayé au retour de la connexion ou dans l'application
            }
        }
        return sent to skipped
    }

    // -------------------------------------------------------------------------
    // Erreurs
    // -------------------------------------------------------------------------

    /**
     * Signale une erreur à l'administration (avec le profil connecté s'il y en a un) : mise en file
     * (fichier partagé avec le processus de l'émulateur intégré), envoyée aussitôt si le serveur est
     * joignable, sinon au retour de la connexion ([flushErrors]).
     */
    fun reportError(context: String, message: String, details: String? = null) {
        if (runCatching { api.url("") }.isFailure || message.isBlank()) return
        queueError(context, message, details?.take(MAX_ERROR_DETAILS))
        scope.launch { flushErrors() }
    }

    private fun queueError(context: String, message: String, details: String?) {
        // File bornée : les plus anciennes erreurs non envoyées laissent la place.
        queued("error-").dropLast(MAX_QUEUED_ERRORS - 1).forEach { it.delete() }
        val body = buildJsonObject {
            put("context", JsonPrimitive(context))
            put("message", JsonPrimitive(message.take(2000)))
            details?.let { put("details", JsonPrimitive(it)) }
            put("at", JsonPrimitive(System.currentTimeMillis()))
        }
        enqueue("error-${System.currentTimeMillis()}-${System.nanoTime()}", body.toString())
    }

    /** Envoie les erreurs en attente ; renvoie vrai si la file est vide (tout est parti). */
    suspend fun flushErrors(): Boolean = withContext(Dispatchers.IO) {
        for (entry in queued("error-")) {
            val body = runCatching { entry.readText() }.getOrNull()
            if (body.isNullOrBlank()) {
                entry.delete()
                continue
            }
            try {
                execute(request("/api/account/errors").post(body.toRequestBody(JSON)), 30)
                entry.delete()
            } catch (e: ApiException) {
                if (e.code == 0) return@withContext false // serveur non configuré
                entry.delete() // refusé par le serveur : abandonné
            } catch (e: IOException) {
                return@withContext false // injoignable : réessayé plus tard
            }
        }
        true
    }

    /**
     * Rapport envoyé par l'utilisateur (paramètres) : sa description, l'appareil et, si [withLogs],
     * le journal récent de l'application et celui de la dernière partie dans l'émulateur intégré.
     * Renvoie vrai s'il est parti, faux s'il attend le retour du serveur.
     */
    suspend fun sendReport(description: String, withLogs: Boolean): Boolean {
        val details = withContext(Dispatchers.IO) {
            buildString {
                appendLine("RomCloud ${appVersion} (${api.platform})")
                appendLine("Appareil : ${Build.MANUFACTURER} ${Build.MODEL} — $deviceName")
                appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.SUPPORTED_ABIS.joinToString()}")
                appendLine("Profil : ${if (token != null) state.value.username else "non connecté"}")
                if (description.isNotBlank()) {
                    appendLine()
                    appendLine("--- Description ---")
                    appendLine(description.trim())
                }
                if (withLogs) {
                    appendLine()
                    appendLine("--- Journal de l'application (dernières lignes) ---")
                    append(appLog())
                    val session = File(context.filesDir, "libretro/session.log")
                    if (session.isFile) {
                        appendLine()
                        appendLine("--- Journal de la dernière partie (émulateur intégré) ---")
                        session.readLines().takeLast(300).forEach { appendLine(it) }
                    }
                    com.romcloud.app.launch.Winlator.companionLog(context)?.let {
                        appendLine()
                        appendLine("--- RomCloud PC (jeux PC, Wine) ---")
                        append(it)
                    }
                }
            }
        }
        val title = description.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(200) ?: I18n.get(R.string.report_default_title)
        queueError("report", title, details.take(MAX_ERROR_DETAILS))
        return flushErrors()
    }

    /** Journal récent du processus de l'application (logcat ; sans autorisation : le sien seulement). */
    private fun appLog(): String = runCatching {
        val process = ProcessBuilder("logcat", "-d", "-v", "time", "-t", "1500", "--pid=${android.os.Process.myPid()}")
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().use { it.readText() }.takeLast(100_000)
    }.getOrElse { "(journal indisponible : ${it.message})\n" }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val BINARY = "application/octet-stream".toMediaType()
        private val JPEG = "image/jpeg".toMediaType()
        /** Détail d'un rapport (journal joint) : limite du serveur. */
        private const val MAX_ERROR_DETAILS = 200_000
        private const val MAX_QUEUED_ERRORS = 50
        /** Compression gardée seulement si elle fait gagner au moins 5 % (états déjà compressés : PPSSPP…). */
        private const val MIN_GAIN = 0.95
        /** Préfixe des entrées de la file faites en ligne ([onlinePrefix]). */
        private const val ONLINE = "online-"
        private const val KEY_TOKEN = "token"
        private const val KEY_USER = "username"
        private const val KEY_USER_ID = "userId"
        private const val KEY_EXTERNAL_GAME = "externalGame"
        private const val KEY_EXTERNAL_START = "externalStart"
        /** Parties plus courtes ignorées (jeu quitté aussitôt, lancement raté). */
        const val MIN_PLAY_SECONDS = 10L
        private const val MAX_EXTERNAL_SECONDS = 12 * 3600L

        fun normalizeCode(code: String) = code.uppercase().filter { it.isLetterOrDigit() }

        /** Code d'un QR code de connexion de TV (« romcloud://pair?code=… »), ou le texte saisi. */
        fun codeFromQr(content: String): String? {
            val code = Regex("[?&]code=([A-Za-z0-9-]+)").find(content)?.groupValues?.get(1) ?: content
            return normalizeCode(code).takeIf { it.length in 6..12 }
        }

        /** Durée lisible : « 3 h 05 », « 12 min », « moins d'une minute ». */
        fun formatDuration(seconds: Long): String {
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            return when {
                h > 0 -> I18n.get(R.string.profile_hours, h, "%02d".format(m))
                m > 0 -> I18n.get(R.string.profile_minutes, m)
                else -> I18n.get(R.string.profile_less_than_minute)
            }
        }
    }
}

/**
 * Cœurs dont les sauvegardes sont interchangeables : variantes d'un même émulateur compilées pour
 * des rendus différents (mupen64plus_next sous Windows, mupen64plus_next_gles3 sous Android).
 */
internal fun saveCoreFamily(core: String): String = core.removeSuffix("_gles3").removeSuffix("_gles2")

internal fun sameSaveCore(a: String, b: String): Boolean = saveCoreFamily(a) == saveCoreFamily(b)

/**
 * État fait hors ligne à [createdAt] (ms) : le jeu a-t-il déjà [StateHistory.LIMIT] états plus récents
 * en ligne (hors épinglés) ? Envoyé, il ne serait pas gardé.
 */
internal fun tooManyNewer(online: List<OnlineState>, createdAt: Long): Boolean =
    online.count { !it.pinned && it.createdAtMillis > createdAt } >= StateHistory.LIMIT

/** Le serveur a-t-il une sauvegarde [kind] du même cœur plus récente que celle de l'appareil ([modified], ms) ? */
internal fun newerOnline(online: List<OnlineSave>, core: String, kind: String, modified: Long): Boolean =
    online.any { it.kind == kind && sameSaveCore(it.core, core) && it.savedAtMillis > modified + 1000 }
