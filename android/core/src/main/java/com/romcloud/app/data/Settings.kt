package com.romcloud.app.data

import android.content.Context
import android.os.Environment
import androidx.annotation.StringRes
import com.romcloud.app.AppLanguage
import com.romcloud.core.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class ServerConfig(
    val serverUrl: String,
    val apiKey: String,
    val romsDir: String,
    val biosDir: String,
) {
    val isConfigured: Boolean get() = serverUrl.isNotBlank()
}

enum class RetroArchSafMode(@StringRes val label: Int) {
    AUTO(R.string.saf_auto),
    SAF(R.string.saf_saf),
    PATH(R.string.saf_path),
}

class Settings(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("romcloud", Context.MODE_PRIVATE)

    /** Langue de l'application : [AppLanguage.SYSTEM], « fr » ou « en » (appliquée en recréant l'écran). */
    var language: String
        get() = AppLanguage.stored(appContext)
        set(value) = AppLanguage.store(appContext, value)

    private val _config = MutableStateFlow(read())
    val config: StateFlow<ServerConfig> = _config.asStateFlow()

    private fun read() = ServerConfig(
        serverUrl = prefs.getString(KEY_URL, "") ?: "",
        apiKey = prefs.getString(KEY_API_KEY, "") ?: "",
        romsDir = prefs.getString(KEY_ROMS_DIR, null) ?: defaultRomsDir(),
        biosDir = prefs.getString(KEY_BIOS_DIR, null) ?: defaultBiosDir(),
    )

    fun save(serverUrl: String, apiKey: String, romsDir: String, biosDir: String) {
        val url = normalizeUrl(serverUrl)
        val dir = romsDir.trim().trimEnd('/').ifBlank { defaultRomsDir() }
        prefs.edit()
            .putString(KEY_URL, url)
            .putString(KEY_API_KEY, apiKey.trim())
            .putString(KEY_ROMS_DIR, dir)
            .putString(KEY_BIOS_DIR, biosDir.trim().trimEnd('/').ifBlank { defaultBiosDir() })
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

    /**
     * Chemin de ROM transmis à RetroArch : AUTO = chemin saf:// si RetroArch vient du Play Store
     * (pas d'accès à tous les fichiers), SAF = toujours, PATH = chemin de fichier classique.
     */
    var retroArchSafMode: RetroArchSafMode
        get() = runCatching { RetroArchSafMode.valueOf(prefs.getString("retroArchSafMode", null) ?: "") }
            .getOrDefault(RetroArchSafMode.AUTO)
        set(value) = prefs.edit().putString("retroArchSafMode", value.name).apply()

    /**
     * RetroArch se ferme complètement dès qu'on le quitte (extra QUITFOCUS). Évite l'écran noir
     * au lancement suivant, RomCloud ne pouvant plus fermer RetroArch lui-même depuis Android 14.
     */
    var retroArchQuitOnExit: Boolean
        get() = prefs.getBoolean("retroArchQuitOnExit", true)
        set(value) = prefs.edit().putBoolean("retroArchQuitOnExit", value).apply()

    /** Guide « Configurer RetroArch » masqué après confirmation d'un téléchargement, pour ce paquet. */
    fun isRetroArchHelpDismissed(packageName: String) = prefs.getBoolean("retroArchHelp.$packageName", false)

    fun setRetroArchHelpDismissed(packageName: String, dismissed: Boolean) =
        prefs.edit().putBoolean("retroArchHelp.$packageName", dismissed).apply()

    /** Avertissement « fermez l'émulateur avant de jouer » masqué pour ce paquet. */
    fun isCloseWarningDismissed(packageName: String) = prefs.getBoolean("closeWarning.$packageName", false)

    fun setCloseWarningDismissed(packageName: String, dismissed: Boolean) =
        prefs.edit().putBoolean("closeWarning.$packageName", dismissed).apply()

    private val _fullscreen = MutableStateFlow(prefs.getBoolean("fullscreen", true))

    /** Plein écran immersif : barre d'état et boutons de navigation masqués. */
    val fullscreen: StateFlow<Boolean> = _fullscreen.asStateFlow()

    fun setFullscreen(value: Boolean) {
        prefs.edit().putBoolean("fullscreen", value).apply()
        _fullscreen.value = value
    }

    /**
     * Jeux non identifiés par le serveur (introuvables dans les bases de jeux, ou pas encore
     * scrapés) absents des listes de jeux et de la recherche.
     */
    var hideUnidentified: Boolean
        get() = prefs.getBoolean("hideUnidentified", false)
        set(value) = prefs.edit().putBoolean("hideUnidentified", value).apply()

    /** Affichage des jeux en cartes (jaquettes) plutôt qu'en liste. */
    var gamesAsGrid: Boolean
        get() = prefs.getBoolean(KEY_GAMES_GRID, false)
        set(value) = prefs.edit().putBoolean(KEY_GAMES_GRID, value).apply()

    companion object {
        private const val KEY_GAMES_GRID = "gamesGrid"
        private const val KEY_URL = "serverUrl"
        private const val KEY_API_KEY = "apiKey"
        private const val KEY_ROMS_DIR = "romsDir"
        private const val KEY_BIOS_DIR = "biosDir"

        @Suppress("DEPRECATION")
        private val storage: File get() = Environment.getExternalStorageDirectory()

        fun defaultRomsDir(): String = File(storage, "RomCloud").absolutePath

        /**
         * Dossiers BIOS proposés : dossier « system » de RetroArch (version Play Store, dans
         * Android/media, ou version du site) et dossier RomCloud.
         */
        fun biosPresets(): List<Pair<Int, String>> = listOf(
            R.string.bios_preset_retroarch_play to File(storage, "Android/media/com.retroarch.aarch64/RetroArch/system").absolutePath,
            R.string.bios_preset_retroarch_site to File(storage, "RetroArch/system").absolutePath,
            R.string.bios_preset_romcloud to File(storage, "RomCloud/bios").absolutePath,
        )

        /** Par défaut : le dossier « system » de RetroArch s'il existe déjà, sinon RomCloud/bios. */
        fun defaultBiosDir(): String {
            val presets = biosPresets().map { it.second }
            return presets.dropLast(1).firstOrNull { File(it).isDirectory } ?: presets.last()
        }

        /** "192.168.1.10:8080" -> "http://192.168.1.10:8080" */
        fun normalizeUrl(input: String): String {
            val trimmed = input.trim().trimEnd('/')
            if (trimmed.isEmpty()) return ""
            return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
        }
    }
}
