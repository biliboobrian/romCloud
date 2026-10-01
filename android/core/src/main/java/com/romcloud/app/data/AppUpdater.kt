package com.romcloud.app.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Request

/**
 * Mise à jour de l'application au démarrage : la dernière Release GitHub de RomCloud est comparée
 * à la version installée ; si elle est plus récente, son APK (téléphone ou TV) est proposé et
 * installé par [ApkInstaller]. Les versions de développement (« 1.0.0-dev ») ne sont pas vérifiées.
 */
class AppUpdater(private val context: Context, private val api: ApiClient) {

    /** Nouvelle version : [apk] pointe vers l'APK de la Release. */
    data class Update(val version: String, val installed: String, val apk: EmulatorApk)

    private val _available = MutableStateFlow<Update?>(null)

    /** Mise à jour proposée (null : aucune, déjà installée, ou proposition refusée). */
    val available: StateFlow<Update?> = _available.asStateFlow()

    private var checked = false

    /** Vérifie une seule fois par lancement de l'application ; sans réseau, ne fait rien. */
    suspend fun check() {
        if (checked) return
        checked = true
        val installed = installedVersion() ?: return
        if (parseVersion(installed) == null) return // version de développement
        val update = runCatching { withContext(Dispatchers.IO) { latest(installed) } }
            .onFailure { Log.i(TAG, "Vérification de mise à jour impossible : ${it.message}") }
            .getOrNull()
        _available.value = update
    }

    fun dismiss() {
        _available.value = null
    }

    private fun installedVersion(): String? = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.versionName
    }.getOrNull()

    private fun latest(installed: String): Update? {
        val request = Request.Builder().url(LATEST_RELEASE)
            .header("Accept", "application/vnd.github+json")
            .build()
        val body = api.http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string() ?: return null
        }
        val release = Json.parseToJsonElement(body).jsonObject
        val version = release["tag_name"]?.jsonPrimitive?.content?.removePrefix("v") ?: return null
        if (!isNewer(version, installed)) return null
        val name = apkName(version, tv = context.packageName.endsWith(".tv"))
        val asset = release["assets"]?.jsonArray?.map { it.jsonObject }
            ?.find { it["name"]?.jsonPrimitive?.content == name } ?: return null
        val url = asset["browser_download_url"]?.jsonPrimitive?.content ?: return null
        val size = asset["size"]?.jsonPrimitive?.longOrNull ?: return null
        val apk = EmulatorApk(
            id = 0,
            packageName = context.packageName,
            label = "RomCloud $version",
            versionName = version,
            size = size,
            url = url,
        )
        return Update(version, installed, apk)
    }

    companion object {
        private const val TAG = "AppUpdater"
        private const val LATEST_RELEASE = "https://api.github.com/repos/biliboobrian/romCloud/releases/latest"

        /** « 1.12.0 » -> [1, 12, 0] ; null pour une version de développement ou mal formée. */
        internal fun parseVersion(version: String): List<Int>? {
            val parts = version.trim().removePrefix("v").split('.')
            if (parts.isEmpty() || parts.any { it.isEmpty() || !it.all(Char::isDigit) }) return null
            return parts.map { it.toInt() }
        }

        /** [candidate] est-elle plus récente que [installed] ? */
        internal fun isNewer(candidate: String, installed: String): Boolean {
            val a = parseVersion(candidate) ?: return false
            val b = parseVersion(installed) ?: return false
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }

        /** Nom de l'APK dans la Release (voir .github/workflows/android.yml). */
        internal fun apkName(version: String, tv: Boolean) =
            if (tv) "RomCloud-TV-$version.apk" else "RomCloud-$version.apk"
    }
}
