package com.romcloud.app.data

import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Dernière version des émulateurs Android publiés sur leur gestionnaire de versions (Releases
 * GitHub, ou Forgejo pour Eden), téléchargée depuis le serveur RomCloud : il récupère chaque version
 * demandée sur Internet et en garde une copie (miroir, emulator-mirror.js du serveur), si bien qu'un
 * émulateur retiré d'Internet reste installable. Seule installation proposée par RomCloud quand
 * l'émulateur a une source dans le miroir (installation et mise à jour) ; sinon, le Play Store.
 * Applications du miroir déclarées par le serveur (« /api/emulators/android » : paquet, source, nom
 * de l'APK, Play Store en repli), gardées dans [cache] pour le démarrage hors ligne ; serveur d'avant
 * cette liste : [FALLBACK]. L'APK est choisi parmi les fichiers de la version d'après le paquet du
 * modèle d'émulateur (variantes d'Eden, d'ARMSX3…).
 */
class EmulatorReleases(private val api: ApiClient, private val cache: java.io.File) {

    @Volatile
    private var sources: Map<String, Source> = runCatching { parseSources(cache.readText()) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: FALLBACK

    @Volatile
    private var refreshed = false

    /** Source du miroir de l'application [packageName], null si elle n'en a pas (Play Store seulement). */
    fun source(packageName: String): Source? = sources[packageName]

    /**
     * Liste des applications du miroir relue sur le serveur (gardée dans [cache]) ; serveur sans cette
     * liste (HTTP 404) : [FALLBACK] ; injoignable : liste gardée.
     */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(api.url("/api/emulators/android")).header("Accept", "application/json").build()
            api.http.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        val body = response.body.string()
                        sources = parseSources(body)
                        cache.writeText(body)
                    }
                    response.code == 404 -> sources = FALLBACK
                }
            }
            refreshed = true
        }.onFailure { Log.i(TAG, "Applications du miroir non relues : ${it.message}") }
    }

    /**
     * Émulateur du miroir : [mirror] identifiant de sa source sur le serveur, [host] site où il est
     * publié, [asset] désigne l'APK du paquet ; [playStore] : faux pour une application absente du
     * Google Play Store (Winlator).
     */
    data class Source(val name: String, val mirror: String, val host: String, val asset: Regex, val playStore: Boolean = true)

    /** Progression de la récupération de l'APK par le serveur (octets reçus, taille ; 0 si inconnue). */
    fun interface Progress {
        fun update(received: Long, total: Long)
    }

    /**
     * Dernière version de l'émulateur du paquet, une fois sur le serveur ([progress] : sa récupération
     * par le serveur, quand il ne l'a pas encore), ou null (paquet inconnu, pas d'APK, serveur injoignable).
     */
    suspend fun latest(packageName: String, progress: Progress = Progress { _, _ -> }): EmulatorApk? {
        if (!refreshed) refresh()
        val source = sources[packageName] ?: return null
        return runCatching { withContext(Dispatchers.IO) { fetch(packageName, source, progress) } }
            .onFailure { Log.i(TAG, "Dernière version de $packageName introuvable : ${it.message}") }
            .getOrNull()
    }

    private fun get(path: String): String? {
        val request = Request.Builder().url(api.url(path)).header("Accept", "application/json").build()
        return api.http.newCall(request).execute().use { response -> if (response.isSuccessful) response.body.string() else null }
    }

    /** Demande au serveur le fichier [path] (récupéré sur Internet s'il ne l'a pas) : son état. */
    private fun prepare(path: String): JsonObject {
        val request = Request.Builder().url(api.url("$path/prepare")).post(ByteArray(0).toRequestBody()).build()
        val body = api.http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiException("HTTP ${response.code}")
            response.body.string()
        }
        return Json.parseToJsonElement(body).jsonObject
    }

    private suspend fun fetch(packageName: String, source: Source, progress: Progress): EmulatorApk? {
        val body = get("/api/emulators/${source.mirror}/releases") ?: return null
        val releases = Json.parseToJsonElement(body) as? JsonArray ?: return null
        // Version la plus récente qui contient l'APK du paquet.
        val (release, asset) = releases.asSequence().map { it.jsonObject }
            .firstNotNullOfOrNull { r -> pickAsset(r, source.asset, Build.VERSION.SDK_INT)?.let { r to it } } ?: return null
        val path = asset.string("path") ?: return null
        var state = asset
        while (state.string("state") != STORED) {
            state = prepare(path)
            when (state.string("state")) {
                STORED -> break
                "error" -> throw ApiException(state.string("error") ?: "error")
            }
            progress.update(state.long("received") ?: 0, state.long("total") ?: 0)
            delay(1500)
        }
        val size = state.long("total")?.takeIf { it > 0 } ?: asset.long("size")?.takeIf { it > 0 } ?: return null
        return EmulatorApk(
            id = 0,
            packageName = packageName,
            label = source.name,
            versionName = versionOf(release.string("tag_name")),
            size = size,
            url = api.url(path),
            source = release.string("source") ?: source.host,
        )
    }

    companion object {
        private const val TAG = "EmulatorReleases"
        private const val STORED = "stored"

        private fun JsonObject.string(key: String) = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
        private fun JsonObject.long(key: String) = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull

        /** Version d'après le tag de la Release : « v2.3 » -> « 2.3 », « cmod_v13.1 » (Winlator Cmod) -> « 13.1 ». */
        internal fun versionOf(tag: String?): String? = tag?.replace(Regex("^(?:[A-Za-z]+_)?v(?=\\d)"), "")

        private const val GITHUB = "github.com"
        private const val EDEN = "git.eden-emu.dev"

        /**
         * Applications du miroir d'un serveur d'avant « /api/emulators/android » (v1.102 et avant) :
         * paquets des modèles d'émulateurs Daijishou -> source du miroir qui publie leur APK.
         */
        val FALLBACK: Map<String, Source> = mapOf(
            "dev.eden.eden_emulator" to Source("Eden", "eden", EDEN, Regex("""Eden-Android-.*-standard\.apk""")),
            // Jeux PC (Wine / Proton) : RomCloud PC (android/pc, publié avec RomCloud), Winlator Cmod.
            "com.romcloud.wine" to Source("RomCloud PC", "romcloud-pc", GITHUB, Regex("""RomCloud-PC-.*\.apk"""), playStore = false),
            "com.winlator.cmod" to Source("Winlator Cmod", "winlator-cmod", GITHUB, Regex("""Winlator-Cmod-.*\.apk"""), playStore = false),
            "dev.legacy.eden_emulator" to Source("Eden (Legacy)", "eden", EDEN, Regex("""Eden-Android-.*-legacy\.apk""")),
            "com.miHoYo.Yuanshen" to Source("Eden (Optimized)", "eden", EDEN, Regex("""Eden-Android-.*-optimized\.apk""")),
            "com.flycast.emulator" to Source("Flycast", "flycast", GITHUB, Regex("""flycast-.*\.apk""")),
            "org.vita3k.emulator" to Source("Vita3K", "vita3k-android", GITHUB, Regex("""vita3k-android-.*\.apk""")),
            "info.cemu.cemu" to Source("Cemu", "cemu-android", GITHUB, Regex("""Cemu-.*\.apk""")),
            "aenu.aps3e" to Source("aPS3e", "aps3e", GITHUB, Regex("""[\w.-]+\.apk""")),
            "com.seleuco.mame4d2024" to Source("MAME4droid", "mame4droid", GITHUB, Regex("""MAME4droid.*\.apk""")),
        )

        /** Liste du serveur : [{ package, name, source, host, asset, playStore }] -> paquet -> source. */
        internal fun parseSources(json: String): Map<String, Source> =
            (Json.parseToJsonElement(json) as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val pkg = o.string("package") ?: return@mapNotNull null
                val asset = o.string("asset")?.let { runCatching { Regex(it) }.getOrNull() } ?: return@mapNotNull null
                pkg to Source(
                    name = o.string("name") ?: pkg,
                    mirror = o.string("source") ?: return@mapNotNull null,
                    host = o.string("host") ?: GITHUB,
                    asset = asset,
                    playStore = (o["playStore"] as? kotlinx.serialization.json.JsonPrimitive)?.content != "false",
                )
            }.toMap()

        private val SDK = Regex("""-sdk(\d+)\.apk$""")

        /**
         * APK de la Release correspondant à [pattern] ; parmi des variantes « -sdkNN », la plus
         * récente compatible avec la version d'Android de l'appareil ([sdk]).
         */
        internal fun pickAsset(release: JsonObject, pattern: Regex, sdk: Int): JsonObject? {
            val assets = release["assets"]?.jsonArray?.map { it.jsonObject }
                ?.filter { pattern.matches(it["name"]?.jsonPrimitive?.content.orEmpty()) }
                .orEmpty()
            fun sdkOf(asset: JsonObject) = SDK.find(asset["name"]?.jsonPrimitive?.content.orEmpty())?.groupValues?.get(1)?.toInt()
            if (assets.none { sdkOf(it) != null }) return assets.firstOrNull()
            return assets.filter { (sdkOf(it) ?: 0) <= sdk }.maxByOrNull { sdkOf(it) ?: 0 }
        }
    }
}
