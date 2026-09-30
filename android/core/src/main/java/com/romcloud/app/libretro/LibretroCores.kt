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
 * chargement de bibliothèques natives téléchargées que depuis ce dossier.
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

    fun delete(core: String) = fileFor(core).delete()

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
            http.newCall(Request.Builder().url("$BUILDBOT/$abi/${core}_libretro_android.so.zip").build()).execute().use { response ->
                if (response.code == 404) throw IOException(I18n.get(R.string.core_not_found, core, abi))
                if (!response.isSuccessful) throw IOException(I18n.get(R.string.err_server, response.code))
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
            target
        } finally {
            zip.delete()
            part.delete()
        }
    }

    private companion object {
        const val BUILDBOT = "https://buildbot.libretro.com/nightly/android/latest"
        val BUILDBOT_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
    }
}
