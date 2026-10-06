package com.romcloud.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Connexion au serveur RomCloud : hors ligne dès qu'une requête n'aboutit pas (ou que l'appareil
 * perd le réseau), de nouveau en ligne à la première réponse, quel que soit son code. Hors ligne,
 * le serveur est sondé régulièrement et à chaque nouveau réseau ; chaque retour de la connexion est
 * signalé par [reconnected] (envoi des sauvegardes faites hors ligne, listes relues).
 */
class Connectivity(private val context: Context, private val api: ApiClient, private val scope: CoroutineScope) {

    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private val _reconnected = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val reconnected: SharedFlow<Unit> = _reconnected.asSharedFlow()

    private var probeJob: Job? = null

    /** Suit le réseau de l'appareil et les requêtes au serveur (processus de l'application seulement). */
    fun start() {
        api.onReachability = ::set
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch { probe() }
            }

            override fun onLost(network: Network) {
                if (manager.activeNetwork == null) set(false)
            }
        })
        if (manager.activeNetwork == null) set(false)
    }

    @Synchronized
    private fun set(value: Boolean) {
        if (_online.value == value) return
        _online.value = value
        probeJob?.cancel()
        probeJob = null
        if (value) {
            _reconnected.tryEmit(Unit)
        } else {
            probeJob = scope.launch {
                while (isActive) {
                    delay(PROBE_INTERVAL_MS)
                    probe()
                }
            }
        }
    }

    /** Interroge le serveur : la réponse (ou son absence) met l'état à jour. */
    suspend fun probe(): Boolean {
        runCatching { api.info() }
        return _online.value
    }

    private companion object {
        const val PROBE_INTERVAL_MS = 15_000L
    }
}
