package com.romcloud.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Connexion au serveur RomCloud : mode en ligne ou hors ligne. Le serveur est sondé (/api/ping) à
 * l'ouverture de l'application et quand l'utilisateur touche la pastille « hors ligne »
 * ([ping]) ; une requête qui n'aboutit pas (délai dépassé, serveur injoignable) passe l'application
 * hors ligne. Hors ligne, aucune requête ne part vers le serveur ([ApiClient.isOffline]) : seuls les
 * fichiers déjà sur l'appareil sont utilisés. Chaque retour en ligne est signalé par [reconnected]
 * (envoi des sauvegardes faites hors ligne, listes relues). Le mode est mémorisé pour le processus
 * de l'émulateur intégré, qui le reprend au lancement d'un jeu sans sonder le serveur.
 */
class Connectivity(context: Context, private val api: ApiClient) {

    private val prefs = context.getSharedPreferences("romcloud_connectivity", Context.MODE_PRIVATE)

    private val _online = MutableStateFlow(prefs.getBoolean(KEY_ONLINE, true))
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private val _reconnected = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val reconnected: SharedFlow<Unit> = _reconnected.asSharedFlow()

    init {
        api.isOffline = { !_online.value }
        // Requête sans réponse : hors ligne (le retour en ligne passe seulement par le ping).
        api.onReachability = { reachable -> if (!reachable) set(false) }
    }

    @Synchronized
    private fun set(value: Boolean) {
        if (_online.value == value) return
        _online.value = value
        prefs.edit().putBoolean(KEY_ONLINE, value).apply()
        if (value) _reconnected.tryEmit(Unit)
    }

    /**
     * Sonde le serveur : toute réponse met l'application en ligne, son absence hors ligne (sans
     * serveur configuré : inchangé). Renvoie l'état obtenu.
     */
    suspend fun ping(): Boolean {
        val reachable = api.ping() ?: return _online.value
        set(reachable)
        return reachable
    }

    private companion object {
        const val KEY_ONLINE = "online"
    }
}
