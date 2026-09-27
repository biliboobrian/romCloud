package com.romcloud.app.data

import com.romcloud.app.I18n
import com.romcloud.core.R
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Télécharge [url] vers [target] via un fichier « .part » : un téléchargement interrompu reprend
 * où il s'était arrêté (requête HTTP Range). [progress] reçoit le nombre d'octets reçus ;
 * [cannotCreate] donne le message d'erreur si le dossier de destination ne peut pas être créé.
 */
internal suspend fun fetchFile(
    api: ApiClient,
    url: String,
    target: File,
    size: Long,
    progress: (Long) -> Unit,
    cannotCreate: (File) -> String,
) {
    val dir = target.parentFile ?: throw IOException(I18n.get(R.string.err_invalid_folder))
    if (!dir.exists() && !dir.mkdirs()) {
        throw IOException(cannotCreate(dir))
    }
    val part = File(dir, "${target.name}.part")
    var offset = if (part.exists()) part.length() else 0L
    if (offset > size) {
        part.delete()
        offset = 0
    }

    val request = Request.Builder().url(url).apply {
        if (offset > 0) header("Range", "bytes=$offset-")
    }.build()

    api.http.newCall(request).execute().use { response ->
        if (response.code == 416) {
            // Le fichier partiel est déjà complet.
            offset = part.length()
        } else {
            if (!response.isSuccessful) {
                throw ApiException(
                    if (response.code == 401) I18n.get(R.string.err_api_key) else I18n.get(R.string.err_server, response.code),
                )
            }
            val append = response.code == 206 && offset > 0
            if (!append) offset = 0
            val body = response.body ?: throw IOException(I18n.get(R.string.err_empty_response))
            var bytes = offset
            var lastUpdate = 0L
            body.byteStream().use { input ->
                FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        bytes += read
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 200) {
                            lastUpdate = now
                            progress(bytes)
                        }
                    }
                }
            }
        }
    }

    if (part.length() != size) {
        throw IOException(I18n.get(R.string.err_wrong_size, part.length(), size))
    }
    if (target.exists()) target.delete()
    if (!part.renameTo(target)) throw IOException(I18n.get(R.string.err_rename))
}
