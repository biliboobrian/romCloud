package com.romcloud.app.data

import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Request
import java.net.URI

/**
 * Dernière version des émulateurs Android publiés sur leur gestionnaire de versions (Releases
 * GitHub, ou Forgejo / Gitea pour Eden, même format JSON) : proposée à la place de l'APK du serveur
 * RomCloud quand l'émulateur n'est pas installé. L'APK est choisi parmi les fichiers de la Release
 * d'après le paquet du modèle d'émulateur (variantes d'Eden, d'Azahar…).
 */
class EmulatorReleases(private val api: ApiClient) {

    /** Release d'un émulateur : [api] renvoie la dernière version, [asset] désigne l'APK du paquet. */
    data class Source(val name: String, val api: String, val asset: Regex) {
        /** Site du gestionnaire de versions (« github.com », « git.eden-emu.dev »). */
        val host: String get() = URI(api).host.removePrefix("api.")
    }

    /** Dernière version de l'émulateur du paquet, ou null (paquet inconnu, pas d'APK, pas de réseau). */
    suspend fun latest(packageName: String): EmulatorApk? {
        val source = SOURCES[packageName] ?: return null
        return runCatching { withContext(Dispatchers.IO) { fetch(packageName, source) } }
            .onFailure { Log.i(TAG, "Dernière version de $packageName introuvable : ${it.message}") }
            .getOrNull()
    }

    private fun fetch(packageName: String, source: Source): EmulatorApk? {
        val request = Request.Builder().url(source.api).header("Accept", "application/json").build()
        val body = api.http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string() ?: return null
        }
        val release = Json.parseToJsonElement(body).jsonObject
        val asset = pickAsset(release, source.asset, Build.VERSION.SDK_INT) ?: return null
        val url = asset["browser_download_url"]?.jsonPrimitive?.content ?: return null
        // Eden : fichiers hébergés hors de Forgejo, taille 0 dans la Release -> en-tête Content-Length.
        val size = asset["size"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 } ?: contentLength(url) ?: return null
        val version = release["tag_name"]?.jsonPrimitive?.content?.removePrefix("v")
        return EmulatorApk(
            id = 0,
            packageName = packageName,
            label = source.name,
            versionName = version,
            size = size,
            url = url,
            source = source.host,
        )
    }

    private fun contentLength(url: String): Long? {
        val request = Request.Builder().url(url).head().build()
        return api.http.newCall(request).execute().use { response ->
            response.header("Content-Length")?.toLongOrNull()?.takeIf { response.isSuccessful && it > 0 }
        }
    }

    companion object {
        private const val TAG = "EmulatorReleases"

        private fun github(repo: String) = "https://api.github.com/repos/$repo/releases/latest"
        private const val EDEN = "https://git.eden-emu.dev/api/v1/repos/eden-emu/eden/releases/latest"
        private val AZAHAR = github("azahar-emu/azahar")

        /** Paquets des modèles d'émulateurs Daijishou -> Release qui publie leur APK. */
        val SOURCES: Map<String, Source> = mapOf(
            "dev.eden.eden_emulator" to Source("Eden", EDEN, Regex("""Eden-Android-.*-standard\.apk""")),
            "dev.legacy.eden_emulator" to Source("Eden (Legacy)", EDEN, Regex("""Eden-Android-.*-legacy\.apk""")),
            "com.miHoYo.Yuanshen" to Source("Eden (Optimized)", EDEN, Regex("""Eden-Android-.*-optimized\.apk""")),
            "org.azahar_emu.azahar" to Source("Azahar", AZAHAR, Regex("""azahar-android-vanilla-.*\.apk""")),
            "io.github.lime3ds.android" to Source("Azahar", AZAHAR, Regex("""azahar-android-googleplay-.*\.apk""")),
            "me.magnum.melonds" to Source("melonDS", github("rafaelvcaetano/melonDS-android"), Regex("""app-gitHub-prod-release\.apk""")),
            "com.flycast.emulator" to Source("Flycast", github("flyinghead/flycast"), Regex("""flycast-.*\.apk""")),
            "org.vita3k.emulator" to Source("Vita3K", github("Vita3K/Vita3K-Android"), Regex("""vita3k-android-.*\.apk""")),
            // Une variante par version d'Android (« -sdk30 », « -sdk33 »…) : la plus récente acceptée.
            "com.armsx2" to Source("ARMSX2", github("ARMSX2/ARMSX2"), Regex("""ARMSX2-.*-sdk\d+\.apk""")),
            "info.cemu.cemu" to Source("Cemu", github("SSimco/Cemu"), Regex("""Cemu-.*\.apk""")),
            "aenu.aps3e" to Source("aPS3e", github("aenu1/aps3e"), Regex("""[\w.-]+\.apk""")),
            "com.seleuco.mame4d2024" to Source("MAME4droid", github("seleuco/MAME4droid-Current"), Regex("""MAME4droid.*\.apk""")),
            "com.sky.SkyEmu" to Source("SkyEmu", github("skylersaleh/SkyEmu"), Regex("""SkyEmu-.*-Android\.apk""")),
        )

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
