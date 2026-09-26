package com.romcloud.app.data

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class ServerConfig(
    val serverUrl: String,
    val apiKey: String,
    val romsDir: String,
) {
    val isConfigured: Boolean get() = serverUrl.isNotBlank()
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("romcloud", Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(read())
    val config: StateFlow<ServerConfig> = _config.asStateFlow()

    private fun read() = ServerConfig(
        serverUrl = prefs.getString(KEY_URL, "") ?: "",
        apiKey = prefs.getString(KEY_API_KEY, "") ?: "",
        romsDir = prefs.getString(KEY_ROMS_DIR, null) ?: defaultRomsDir(),
    )

    fun save(serverUrl: String, apiKey: String, romsDir: String) {
        val url = normalizeUrl(serverUrl)
        val dir = romsDir.trim().trimEnd('/').ifBlank { defaultRomsDir() }
        prefs.edit()
            .putString(KEY_URL, url)
            .putString(KEY_API_KEY, apiKey.trim())
            .putString(KEY_ROMS_DIR, dir)
            .apply()
        _config.value = read()
    }

    /** Émulateur choisi par l'utilisateur pour un système (uniqueId du modèle Daijishou). */
    fun preferredPlayer(systemId: String): String? = prefs.getString("player.$systemId", null)

    fun setPreferredPlayer(systemId: String, playerId: String?) {
        prefs.edit().apply {
            if (playerId == null) remove("player.$systemId") else putString("player.$systemId", playerId)
        }.apply()
    }

    /** Avertissement « fermez l'émulateur avant de jouer » masqué pour ce paquet. */
    fun isCloseWarningDismissed(packageName: String) = prefs.getBoolean("closeWarning.$packageName", false)

    fun setCloseWarningDismissed(packageName: String, dismissed: Boolean) =
        prefs.edit().putBoolean("closeWarning.$packageName", dismissed).apply()

    /** Affichage des jeux en cartes (jaquettes) plutôt qu'en liste. */
    var gamesAsGrid: Boolean
        get() = prefs.getBoolean(KEY_GAMES_GRID, false)
        set(value) = prefs.edit().putBoolean(KEY_GAMES_GRID, value).apply()

    companion object {
        private const val KEY_GAMES_GRID = "gamesGrid"
        private const val KEY_URL = "serverUrl"
        private const val KEY_API_KEY = "apiKey"
        private const val KEY_ROMS_DIR = "romsDir"

        @Suppress("DEPRECATION")
        fun defaultRomsDir(): String =
            File(Environment.getExternalStorageDirectory(), "RomCloud").absolutePath

        /** "192.168.1.10:8080" -> "http://192.168.1.10:8080" */
        fun normalizeUrl(input: String): String {
            val trimmed = input.trim().trimEnd('/')
            if (trimmed.isEmpty()) return ""
            return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
        }
    }
}
