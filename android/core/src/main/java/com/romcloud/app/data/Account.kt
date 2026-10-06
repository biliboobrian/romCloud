package com.romcloud.app.data

import android.content.Context
import android.content.pm.PackageManager
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

    val isTv: Boolean get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

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
            header("X-RomCloud-Platform", if (isTv) "androidtv" else "android")
            header("X-RomCloud-Version", appVersion)
            if (withSession) token?.let { header("X-RomCloud-Session", it) }
        }

    private fun jsonBody(vararg pairs: Pair<String, Any?>): RequestBody = buildJsonObject {
        for ((k, v) in pairs) when (v) {
            null -> Unit
            is Number -> put(k, JsonPrimitive(v))
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
        for (save in saves(gameId).filter { it.core == core }) {
            val file = files[save.kind] ?: continue
            if (save.savedAtMillis <= file.lastModified() + 1000) continue
            runCatching {
                withContext(Dispatchers.IO) {
                    val client = api.http.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()
                    client.newCall(request(savePath(gameId, core, save.kind)).build()).execute().use { response ->
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
                        execute(
                            request(savePath(gameId.toLong(), core, kind))
                                .header("X-Saved-At", file.lastModified().toString())
                                .put(file.asRequestBody(BINARY)),
                            timeoutSeconds = 300,
                        )
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
