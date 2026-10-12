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

    /**
     * Nouvelle version : [apk] pointe vers l'APK de la Release ; [changes] : modifications depuis
     * la version installée, de la plus récente à la plus ancienne (vide si GitHub ne les donne pas).
     */
    data class Update(val version: String, val installed: String, val apk: EmulatorApk, val changes: List<VersionChanges> = emptyList())

    /** Modifications apportées par une version. */
    data class VersionChanges(val version: String, val items: List<String>)

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
            response.body.string()
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
        return Update(version, installed, apk, changes(installed, version))
    }

    /** Commits entre les tags des deux versions (comparaison GitHub) ; vide en cas d'échec. */
    private fun changes(installed: String, latest: String): List<VersionChanges> = runCatching {
        val request = Request.Builder().url("$COMPARE/v$installed...v$latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        api.http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            changesFromCompare(response.body.string(), latest)
        }
    }.getOrDefault(emptyList())

    companion object {
        private const val TAG = "AppUpdater"
        private const val LATEST_RELEASE = "https://api.github.com/repos/biliboobrian/romCloud/releases/latest"
        private const val COMPARE = "https://api.github.com/repos/biliboobrian/romCloud/compare"
        private val VERSION_SUFFIX = Regex(";\\s*v(\\d+(?:\\.\\d+)*)$")

        /**
         * Commits d'une comparaison GitHub (du plus ancien au plus récent) regroupés par version : un
         * commit se termine par sa version (« … ; v1.35.6 ») ; un commit sans version appartient à
         * la version publiée après lui. Chaque message (premier paragraphe) est découpé à ses « ; ».
         */
        internal fun changesFromCompare(body: String, latest: String): List<VersionChanges> {
            val commits = Json.parseToJsonElement(body).jsonObject["commits"]?.jsonArray ?: return emptyList()
            val groups = mutableListOf<VersionChanges>()
            var version = latest
            for (commit in commits.reversed()) {
                val message = commit.jsonObject["commit"]?.jsonObject?.get("message")?.jsonPrimitive?.content ?: continue
                var text = message.replace("\r\n", "\n").substringBefore("\n\n").trim()
                if (text.isEmpty() || text.startsWith("Merge ")) continue
                VERSION_SUFFIX.find(text)?.let {
                    version = it.groupValues[1]
                    text = text.removeRange(it.range).trimEnd()
                }
                val items = text.split(" ; ").map { it.trim() }.filter { it.isNotEmpty() }
                val last = groups.lastOrNull()
                if (last?.version == version) groups[groups.lastIndex] = last.copy(items = last.items + items)
                else groups += VersionChanges(version, items)
            }
            return groups
        }

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
