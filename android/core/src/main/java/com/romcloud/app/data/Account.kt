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
data class AccountUser(val id: Long, val username: String)

@Serializable
private data class SessionResponse(val token: String, val user: AccountUser)

@Serializable
private data class MeResponse(val user: AccountUser, val playSeconds: Long = 0, val gamesPlayed: Int = 0)

@Serializable
data class PlaytimeEntry(val gameId: Long, val seconds: Long, val sessions: Int = 0, val lastPlayedAt: String? = null)

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
            val body = response.body?.string().orEmpty()
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
        scope.launch { refresh() }
    }

    private fun forget() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_USER).remove(KEY_USER_ID).commit()
        _state.value = AccountState()
        _playtime.value = emptyMap()
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
        flush()
        try {
            val me = json.decodeFromString(MeResponse.serializer(), execute(request("/api/account/me")))
            val list = json.decodeFromString(ListSerializer(PlaytimeEntry.serializer()), execute(request("/api/account/playtime")))
            _playtime.value = list.associate { it.gameId to it.seconds }
            _state.value = AccountState(signedIn = true, username = me.user.username, playSeconds = me.playSeconds, gamesPlayed = me.gamesPlayed)
        } catch (e: IOException) {
            if (token != null) _state.value = _state.value.copy(offline = true)
        }
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
    ): com.romcloud.app.netplay.PlayPresenceResponse? {
        if (token == null) return null
        val body = buildJsonObject {
            put("deviceId", JsonPrimitive(deviceId))
            put("name", JsonPrimitive(name))
            put("platform", JsonPrimitive(platform))
            put("busy", JsonPrimitive(busy))
            put("rtt", JsonPrimitive(rtt))
            if (hosting != null) put("hosting", json.encodeToJsonElement(com.romcloud.app.netplay.NetplayProtocol.HostedGame.serializer(), hosting))
        }.toString().toRequestBody(JSON)
        return json.decodeFromString(com.romcloud.app.netplay.PlayPresenceResponse.serializer(), execute(request("/api/account/play/presence").put(body), 8))
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

    // -------------------------------------------------------------------------
    // Temps de jeu
    // -------------------------------------------------------------------------

    /**
     * Ajoute une partie au temps de jeu (mis en file : envoyé tout de suite si possible). Appelable
     * depuis le processus de l'émulateur intégré : l'envoi est alors fait par l'application.
     */
    fun recordPlaytime(gameId: Long, seconds: Long, sendNow: Boolean = true) {
        if (token == null || gameId <= 0 || seconds < MIN_PLAY_SECONDS) return
        enqueue("play-${System.currentTimeMillis()}-${System.nanoTime()}", "$gameId:$seconds")
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
    suspend fun downloadNewer(gameId: Long, core: String, files: Map<String, File>): List<String> {
        val updated = mutableListOf<String>()
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
                        response.body!!.byteStream().use { input -> part.outputStream().use { input.copyTo(it) } }
                        if (!part.renameTo(file)) {
                            part.copyTo(file, overwrite = true)
                            part.delete()
                        }
                        file.setLastModified(save.savedAtMillis)
                    }
                }
                updated += save.kind
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
        enqueue("state-$id", "$gameId\n${history.dir.absolutePath}")
        if (sendNow) scope.launch { flush(savesOnly = true) }
    }

    /** Télécharge l'état en ligne [id] dans [target]. */
    suspend fun downloadState(id: String, target: File) = withContext(Dispatchers.IO) {
        val client = api.http.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()
        client.newCall(request("/api/account/states/$id").build()).execute().use { response ->
            if (!response.isSuccessful) throw failure(response.code, response.body?.string().orEmpty())
            target.parentFile?.mkdirs()
            val part = File(target.path + ".download")
            response.body!!.byteStream().use { input -> part.outputStream().use { input.copyTo(it) } }
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
                if (response.isSuccessful) response.body?.bytes() else null
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
        for ((kind, file) in changed) enqueue(saveEntryName(gameId, core, kind), file.absolutePath)
        if (sendNow) scope.launch { flush(savesOnly = true) }
    }

    private fun saveEntryName(gameId: Long, core: String, kind: String) = "save-$gameId-$kind-" + core.replace(Regex("[^A-Za-z0-9._]"), "_")

    /**
     * Envoie le temps de jeu et les sauvegardes en attente (serveur joignable) ; renvoie le nombre
     * de sauvegardes envoyées. [savesOnly] : processus de l'émulateur intégré (le temps de jeu n'est
     * envoyé que par l'application, pour ne jamais être compté deux fois).
     */
    suspend fun flush(savesOnly: Boolean = false): Int {
        if (token == null) return 0
        var sent = 0
        flushLock.withLock {
            withContext(Dispatchers.IO) {
                if (!savesOnly) {
                    for (entry in queued("play-")) {
                        val parts = runCatching { entry.readText().split(':') }.getOrNull()
                        val gameId = parts?.getOrNull(0)?.toLongOrNull()
                        val seconds = parts?.getOrNull(1)?.toLongOrNull()
                        if (gameId == null || seconds == null) {
                            entry.delete()
                            continue
                        }
                        try {
                            execute(request("/api/account/playtime").post(jsonBody("gameId" to gameId, "seconds" to seconds)))
                            entry.delete()
                        } catch (e: ApiException) {
                            // Refus du serveur (jeu supprimé…) : abandonné.
                            entry.delete()
                        } catch (e: IOException) {
                            // réessayé au prochain retour dans l'application
                        }
                    }
                }
                for (entry in queued("save-")) {
                    val (gameId, kind, core) = entry.name.removePrefix("save-").split('-', limit = 3)
                    val path = runCatching { entry.readText() }.getOrNull()
                    val file = path?.let(::File)
                    if (file == null || !file.isFile) {
                        entry.delete()
                        continue
                    }
                    val modified = entry.lastModified()
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
                for (entry in queued("state-")) {
                    val id = entry.name.removePrefix("state-")
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
            }
        }
        return sent
    }

    // -------------------------------------------------------------------------
    // Erreurs
    // -------------------------------------------------------------------------

    /** Signale une erreur à l'administration (avec le profil connecté) ; sans effet hors ligne. */
    fun reportError(context: String, message: String, details: String? = null) {
        if (runCatching { api.url("") }.isFailure || message.isBlank()) return
        scope.launch {
            runCatching {
                execute(request("/api/account/errors").post(jsonBody("context" to context, "message" to message.take(2000), "details" to details?.take(20000))), 10)
            }
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val BINARY = "application/octet-stream".toMediaType()
        private val JPEG = "image/jpeg".toMediaType()
        /** Compression gardée seulement si elle fait gagner au moins 5 % (états déjà compressés : PPSSPP…). */
        private const val MIN_GAIN = 0.95
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
