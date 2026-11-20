package com.romcloud.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.romcloud.app.RomCloudApp
import com.romcloud.app.data.GameSystem
import com.romcloud.app.data.StorageDetails
import com.romcloud.app.data.StorageKind
import com.romcloud.app.data.StorageScanner
import com.romcloud.app.data.SystemStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Page du stockage (clic sur la barre du disque) : place occupée par système et par catégorie,
 * suppression des données d'un système.
 */
class StorageViewModel(private val app: RomCloudApp) : ViewModel() {

    data class UiState(val loading: Boolean = true, val details: StorageDetails = StorageDetails(), val busy: Boolean = false)

    private val scanner = StorageScanner(
        context = app,
        folders = { app.settings.config.value.folders },
        cachedBios = app.repository::cachedBios,
        packageOf = app.launcher::packageOf,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var systems: List<GameSystem> = emptyList()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.details.systems.isEmpty()) }
            // Systèmes du serveur (ou du cache hors ligne) : tous, même ceux sans jeu sur l'appareil.
            systems = runCatching { app.repository.systems().data }.getOrDefault(systems)
            val details = withContext(Dispatchers.IO) { scanner.details(systems) }
            _state.update { it.copy(loading = false, details = details) }
        }
    }

    /**
     * Supprime les données du système pour les catégories [kinds], puis relit la page ; [onDone] :
     * octets libérés et applications d'émulateurs à désinstaller (émulateurs choisis).
     */
    fun clear(row: SystemStorage, kinds: Set<StorageKind>, onDone: (freed: Long, uninstall: List<String>) -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val freed = withContext(Dispatchers.IO) { scanner.clear(row.system, kinds, systems) }
            _state.update { it.copy(busy = false) }
            onDone(freed, if (StorageKind.EMULATORS in kinds) row.apps else emptyList())
            refresh()
        }
    }
}
