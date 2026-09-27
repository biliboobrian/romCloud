package com.romcloud.app.data

import com.romcloud.app.I18n
import com.romcloud.core.R
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

sealed interface DownloadState {
    val title: String

    data class Running(override val title: String, val bytes: Long, val total: Long) : DownloadState {
        val progress: Float get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
    }

    data class Failed(override val title: String, val message: String) : DownloadState
}

sealed interface DownloadEvent {
    /** [romIncluded] = false : seuls les BIOS manquants ont été téléchargés (jeu déjà présent). */
    data class Completed(val system: GameSystem, val game: Game, val romIncluded: Boolean = true) : DownloadEvent
    data class Failed(val game: Game, val message: String) : DownloadEvent
}

/**
 * Télécharge les ROMs (et les BIOS de leur système) depuis le serveur vers les dossiers locaux.
 * Un fichier « .part » est utilisé pendant le transfert, ce qui permet de reprendre un
 * téléchargement interrompu (requête HTTP Range).
 */
class Downloader(
    private val context: Context,
    private val api: ApiClient,
    private val library: LocalLibrary,
    private val scope: CoroutineScope,
) {
    private val _states = MutableStateFlow<Map<Long, DownloadState>>(emptyMap())
    val states: StateFlow<Map<Long, DownloadState>> = _states.asStateFlow()

    private val _events = MutableSharedFlow<DownloadEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<DownloadEvent> = _events.asSharedFlow()

    private val jobs = ConcurrentHashMap<Long, Job>()

    val activeCount: Int get() = _states.value.values.count { it is DownloadState.Running }

    /**
     * Télécharge d'abord les [bios] indiqués (BIOS manquants du système), puis le jeu sauf si
     * [includeRom] vaut false. La progression affichée couvre l'ensemble.
     */
    fun start(system: GameSystem, game: Game, bios: List<BiosFile> = emptyList(), includeRom: Boolean = true) {
        if (jobs[game.id]?.isActive == true) return
        if (!includeRom && bios.isEmpty()) return
        val total = bios.sumOf { it.size } + if (includeRom) game.size else 0
        _states.update { it + (game.id to DownloadState.Running(game.title, 0, total)) }
        ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        jobs[game.id] = scope.launch(Dispatchers.IO) {
            try {
                var done = 0L
                val progress = { bytes: Long ->
                    _states.update { it + (game.id to DownloadState.Running(game.title, done + bytes, total)) }
                }
                for (file in bios) {
                    fetch(api.biosFileUrl(file), library.biosFile(file), file.size, progress)
                    done += file.size
                }
                if (includeRom) fetch(api.fileUrl(game), library.fileFor(system, game), game.size, progress)
                _states.update { it - game.id }
                _events.emit(DownloadEvent.Completed(system, game, includeRom))
            } catch (e: CancellationException) {
                _states.update { it - game.id }
                throw e
            } catch (e: Exception) {
                val message = e.message ?: e.javaClass.simpleName
                _states.update { it + (game.id to DownloadState.Failed(game.title, message)) }
                _events.emit(DownloadEvent.Failed(game, message))
            } finally {
                jobs.remove(game.id)
            }
        }
    }

    fun cancel(gameId: Long) {
        jobs[gameId]?.cancel()
        _states.update { it - gameId }
    }

    fun dismissError(gameId: Long) {
        _states.update { if (it[gameId] is DownloadState.Failed) it - gameId else it }
    }

    /** Télécharge [url] vers [target] (via « .part », avec reprise) ; [progress] reçoit les octets reçus. */
    private suspend fun fetch(url: String, target: File, size: Long, progress: (Long) -> Unit) {
        val dir = target.parentFile ?: throw IOException(I18n.get(R.string.err_invalid_folder))
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException(
                if (library.hasStoragePermission()) I18n.get(R.string.err_cannot_create, dir.absolutePath)
                else I18n.get(R.string.err_storage_permission),
            )
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
}
