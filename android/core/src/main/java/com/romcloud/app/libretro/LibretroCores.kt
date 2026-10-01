package com.romcloud.app.libretro

import android.content.Context
import android.os.Build
import com.romcloud.app.I18n
import com.romcloud.core.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Cœurs libretro de l'émulateur intégré, téléchargés à la demande depuis le buildbot libretro
 * (mêmes cœurs que RetroArch) dans le stockage privé de l'application : Android n'autorise le
 * chargement de bibliothèques natives téléchargées que depuis ce dossier. La version installée
 * (date « Last-Modified » du buildbot) est comparée au plus une fois par jour à celle du buildbot :
 * un cœur corrigé depuis son téléchargement est ainsi mis à jour.
 */
class LibretroCores(context: Context) {

    private val dir = File(context.filesDir, "libretro/cores")

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** ABI du buildbot correspondant à l'appareil (arm64-v8a, armeabi-v7a, x86_64, x86). */
    private val abi: String? = Build.SUPPORTED_ABIS.firstOrNull { it in BUILDBOT_ABIS }

    fun fileFor(core: String) = File(dir, "${core}_libretro_android.so")

    fun installed(core: String): File? = fileFor(core).takeIf { it.isFile && it.length() > 0 }

    fun delete(core: String) {
        fileFor(core).delete()
        versionFile(core).delete()
        checkedFile(core).delete()
    }

    /** Version du buildbot du cœur installé (en-tête Last-Modified au téléchargement). */
    private fun versionFile(core: String) = File(dir, "$core.version")

    /** Date de la dernière vérification de version. */
    private fun checkedFile(core: String) = File(dir, "$core.checked")

    private fun url(abi: String, core: String) = "$BUILDBOT/$abi/${core}_libretro_android.so.zip"

    /**
     * Le buildbot a-t-il une version plus récente que le cœur installé ? Vérifié au plus une fois
     * par jour ; hors ligne ou en cas d'erreur, le cœur installé est gardé. Un cœur installé sans
     * version connue (téléchargé par une version antérieure de RomCloud) est mis à jour une fois.
     */
    suspend fun isOutdated(core: String): Boolean = withContext(Dispatchers.IO) {
        val abi = abi ?: return@withContext false
        if (installed(core) == null) return@withContext false
        val checked = checkedFile(core)
        if (checked.isFile && System.currentTimeMillis() - checked.lastModified() < CHECK_INTERVAL_MS) return@withContext false
        val remote = runCatching {
            quickHttp.newCall(Request.Builder().url(url(abi, core)).head().build()).execute().use { response ->
                if (response.isSuccessful) response.header("Last-Modified") else null
            }
        }.getOrNull() ?: return@withContext false
        runCatching { checked.writeText(remote) }
        val local = versionFile(core).takeIf { it.isFile }?.readText()
        local != remote
    }

    private val quickHttp by lazy {
        OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).build()
    }

    /**
     * Télécharge et décompresse le cœur ; [progress] reçoit (octets reçus, taille totale ou -1).
     * Renvoie le fichier .so installé.
     */
    suspend fun download(core: String, progress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        val abi = abi ?: throw IOException(I18n.get(R.string.core_unsupported_abi))
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException(I18n.get(R.string.err_invalid_folder))
        val target = fileFor(core)
        val zip = File(dir, "${core}.zip.part")
        val part = File(dir, "${target.name}.part")
        try {
            var version: String? = null
            http.newCall(Request.Builder().url(url(abi, core)).build()).execute().use { response ->
                if (response.code == 404) throw IOException(I18n.get(R.string.core_not_found, core, abi))
                if (!response.isSuccessful) throw IOException(I18n.get(R.string.err_server, response.code))
                version = response.header("Last-Modified")
                val body = response.body ?: throw IOException(I18n.get(R.string.err_empty_response))
                val total = body.contentLength()
                var bytes = 0L
                var lastUpdate = 0L
                body.byteStream().use { input ->
                    zip.outputStream().use { output ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            bytes += read
                            val now = System.currentTimeMillis()
                            if (now - lastUpdate > 150) {
                                lastUpdate = now
                                progress(bytes, total)
                            }
                        }
                    }
                }
                progress(bytes, total)
            }
            // L'archive contient un seul fichier : <cœur>_libretro_android.so.
            ZipInputStream(zip.inputStream().buffered()).use { input ->
                generateSequence { input.nextEntry }.firstOrNull { !it.isDirectory && it.name.endsWith(".so") }
                    ?: throw IOException(I18n.get(R.string.core_invalid_archive, core))
                part.outputStream().use { input.copyTo(it) }
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw IOException(I18n.get(R.string.err_rename))
            version?.let { runCatching { versionFile(core).writeText(it) } }
            runCatching { checkedFile(core).writeText(version.orEmpty()) }
            target
        } finally {
            zip.delete()
            part.delete()
        }
    }

    private companion object {
        const val BUILDBOT = "https://buildbot.libretro.com/nightly/android/latest"
        val BUILDBOT_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        const val CHECK_INTERVAL_MS = 24 * 3600_000L
    }
}
