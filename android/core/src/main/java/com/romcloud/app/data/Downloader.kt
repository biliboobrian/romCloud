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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
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
     * Télécharge d'abord les [bios] indiqués (BIOS manquants du système), puis le jeu et ses parties
     * (disques, mises à jour, DLC) sauf si [includeRom] vaut false. La progression affichée couvre l'ensemble.
     */
    fun start(system: GameSystem, game: Game, bios: List<BiosFile> = emptyList(), includeRom: Boolean = true) {
        if (jobs[game.id]?.isActive == true) return
        if (!includeRom && bios.isEmpty()) return
        val total = bios.sumOf { it.size } + if (includeRom) game.fullSize else 0
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
                if (includeRom) {
                    for (file in game.files) {
                        fetch(api.fileUrl(file.id), File(library.systemDir(system), file.fileName), file.size, progress)
                        done += file.size
                    }
                }
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

    private suspend fun fetch(url: String, target: File, size: Long, progress: (Long) -> Unit) =
        fetchFile(api, url, target, size, progress) { dir ->
            if (library.hasStoragePermission()) I18n.get(R.string.err_cannot_create, dir.absolutePath)
            else I18n.get(R.string.err_storage_permission)
        }
}
