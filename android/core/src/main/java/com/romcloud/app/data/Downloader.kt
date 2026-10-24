package com.romcloud.app.data

import com.romcloud.app.I18n
import com.romcloud.app.ui.formatSize
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
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
                    // BIOS introuvable sur le serveur (404…) : ignoré, le jeu est téléchargé quand même
                    // (un BIOS facultatif ou absent ne doit pas empêcher de jouer). Annulation : arrêt.
                    try {
                        fetch(api.biosFileUrl(file), library.biosFile(file), file.size, progress)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: java.io.IOException) {
                        android.util.Log.w("RomCloudDownload", "BIOS ${file.path} : ${e.message}")
                    }
                    done += file.size
                }
                // Clés et firmware importés par l'émulateur externe (Eden) : là où son sélecteur les trouve.
                if (bios.isNotEmpty()) runCatching { library.exportForEmulators(system) }
                if (includeRom && game.folder) {
                    fetchFolder(system, game, done) { bytes, folderTotal ->
                        _states.update { it + (game.id to DownloadState.Running(game.title, bytes, done + folderTotal)) }
                    }
                    library.folderMarker(system, game).writeText(game.size.toString())
                } else if (includeRom) {
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

    /**
     * Jeu en dossier (PC) : ses fichiers, [FolderInstall.PARALLEL] à la fois et les plus lourds
     * d'abord, ceux déjà complets gardés (reprise) ; jeu compressé : archives décompressées une à une
     * dès leur réception, pendant que les suivantes se téléchargent. Refusé d'avance s'il n'y a pas
     * assez de place. [progress] reçoit les octets reçus (avec les [before] déjà comptés) et le total à télécharger.
     */
    private suspend fun fetchFolder(system: GameSystem, game: Game, before: Long, progress: (Long, Long) -> Unit) = coroutineScope {
        val files = api.folderFiles(game.id)
        val complete = { f: FolderFile -> library.folderFile(system, game, f.path).let { it.isFile && it.length() == f.size } }
        val todo = FolderInstall.order(files.filterNot(complete))
        val needed = FolderInstall.neededBytes(todo)
        val free = library.freeBytes(library.fileFor(system, game))
        if (free in 0 until needed) {
            throw java.io.IOException(I18n.get(R.string.err_not_enough_space, formatSize(needed), formatSize(free)))
        }
        val total = files.sumOf { it.downloadSize }
        val received = java.util.concurrent.atomic.AtomicLong(before + total - todo.sumOf { it.downloadSize })
        val inFlight = ConcurrentHashMap<String, Long>()
        val report = { progress(received.get() + inFlight.values.sum(), total) }
        report()
        // Archives reçues, décompressées une à une.
        val unzipQueue = Channel<Pair<FolderFile, File>>(FolderInstall.UNZIP_QUEUE)
        val unzipper = launch {
            for ((file, target) in unzipQueue) FolderInstall.unzip(FolderInstall.zipFile(target), target, file.size, file.crc32) { isActive }
        }
        val work = Channel<FolderFile>(Channel.UNLIMITED)
        todo.forEach { work.trySend(it) }
        work.close()
        List(FolderInstall.PARALLEL) {
            launch {
                for (file in work) {
                    val target = library.folderFile(system, game, file.path)
                    val zip = FolderInstall.zipFile(target)
                    // Archive déjà reçue (téléchargement interrompu pendant la décompression) : décompressée seulement.
                    if (!(file.packed && zip.isFile && zip.length() == file.zipSize)) {
                        fetch(api.folderFileUrl(game.id, file.path, file.packed), if (file.packed) zip else target, file.downloadSize) { bytes ->
                            inFlight[file.path] = bytes
                            report()
                        }
                    }
                    inFlight.remove(file.path)
                    received.addAndGet(file.downloadSize)
                    report()
                    if (file.packed) unzipQueue.send(file to target)
                }
            }
        }.joinAll()
        unzipQueue.close()
        unzipper.join()
    }

    private suspend fun fetch(url: String, target: File, size: Long, progress: (Long) -> Unit) =
        fetchFile(api, url, target, size, progress) { dir ->
            if (library.hasStoragePermission()) I18n.get(R.string.err_cannot_create, dir.absolutePath)
            else I18n.get(R.string.err_storage_permission)
        }
}
