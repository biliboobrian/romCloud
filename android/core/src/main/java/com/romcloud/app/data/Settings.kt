package com.romcloud.app.data

import android.content.Context
import android.os.Build
import com.romcloud.app.AppLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class ServerConfig(
    val serverUrl: String,
    val apiKey: String,
    /** Dossier RomCloud (roms, bios, system, drivers, saves, states : voir [Folders]). */
    val rootDir: String,
) {
    val isConfigured: Boolean get() = serverUrl.isNotBlank()

    val folders: Folders get() = Folders(File(rootDir))

    /** Dossier des ROMs : <dossier RomCloud>/roms (un sous-dossier par système). */
    val romsDir: String get() = folders.roms.absolutePath
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
        rootDir = Folders.rootOf(appContext),
    )

    fun save(serverUrl: String, apiKey: String, rootDir: String) {
        val url = normalizeUrl(serverUrl)
        prefs.edit()
            .putString(KEY_URL, url)
            .putString(KEY_API_KEY, apiKey.trim())
            .putString(Folders.KEY_ROOT, rootDir.trim().trimEnd('/').ifBlank { defaultRootDir() })
            .apply()
        _config.value = read()
    }

    /** Relit le dossier RomCloud (fixé par la mise en place des dossiers, [Folders.migrate]). */
    fun reload() {
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
    /** Licence (zRIF) d'un jeu PS Vita en .pkg, saisie dans sa fiche pour l'installer dans Vita3K. */
    fun vitaLicense(gameId: Long): String = prefs.getString("vitaLicense.$gameId", "") ?: ""

    fun setVitaLicense(gameId: Long, zrif: String) {
        val value = zrif.filterNot { it.isWhitespace() }
        prefs.edit().apply { if (value.isEmpty()) remove("vitaLicense.$gameId") else putString("vitaLicense.$gameId", value) }.apply()
    }

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

    /** Ordre des jeux dans les listes et les rangées : par nom ou par note (les mieux notés d'abord). */
    var gameOrder: GameOrder
        get() = runCatching { GameOrder.valueOf(prefs.getString("gameOrder", null) ?: "") }.getOrDefault(GameOrder.NAME)
        set(value) = prefs.edit().putString("gameOrder", value.name).apply()

    /**
     * Profil connecté : chaque état sauvegardé dans l'émulateur intégré est envoyé en ligne (sinon
     * à la demande, depuis l'historique des états). Réglage propre à cet appareil.
     */
    var autoUploadStates: Boolean
        get() = prefs.getBoolean("autoUploadStates", true)
        set(value) = prefs.edit().putBoolean("autoUploadStates", value).apply()

    /** Conseil « lecteur des ROMs à ajouter dans Winlator » déjà affiché (jeux PC). */
    var winlatorDriveHintShown: Boolean
        get() = prefs.getBoolean("winlatorDriveHintShown", false)
        set(value) = prefs.edit().putBoolean("winlatorDriveHintShown", value).apply()

    /** Dossier de données de Winlator autorisé par l'utilisateur (URI), pour y ajouter le lecteur des ROMs. */
    var winlatorTree: String?
        get() = prefs.getString("winlatorTree", null)
        set(value) = prefs.edit().putString("winlatorTree", value).apply()

    /** Dossier de l'émulateur externe [pkg] autorisé pour « Configurer … » (EmulatorConfigFiles), null sinon. */
    fun emulatorConfigTree(pkg: String): String? = prefs.getString("emulatorConfigTree.$pkg", null)

    fun setEmulatorConfigTree(pkg: String, tree: String?) = prefs.edit().putString("emulatorConfigTree.$pkg", tree).apply()

    /**
     * Pilotes graphiques du serveur (Turnip, puces Adreno) téléchargés avec les BIOS et recopiés
     * pour les émulateurs qui les installent eux-mêmes (Eden, Dolphin, Vita3K…). Par défaut sur les
     * appareils Snapdragon, seuls à pouvoir s'en servir.
     */
    var gpuDrivers: Boolean
        get() = prefs.getBoolean("gpuDrivers", isSnapdragon())
        set(value) = prefs.edit().putBoolean("gpuDrivers", value).apply()

    /** Affichage des jeux en cartes (jaquettes) plutôt qu'en liste. */
    var gamesAsGrid: Boolean
        get() = prefs.getBoolean(KEY_GAMES_GRID, false)
        set(value) = prefs.edit().putBoolean(KEY_GAMES_GRID, value).apply()

    companion object {
        private const val KEY_GAMES_GRID = "gamesGrid"
        private const val KEY_URL = "serverUrl"
        private const val KEY_API_KEY = "apiKey"


        /** Puce Qualcomm Snapdragon (carte graphique Adreno) : fabricant de la puce, sinon matériel « qcom ». */
        fun isSnapdragon(): Boolean {
            val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER else ""
            return soc.equals("QTI", ignoreCase = true) || soc.contains("qualcomm", ignoreCase = true) ||
                Build.HARDWARE.startsWith("qcom", ignoreCase = true)
        }

        /** Dossier RomCloud par défaut : /storage/emulated/0/RomCloud. */
        fun defaultRootDir(): String = Folders.defaultRoot()

        /** "192.168.1.10:8080" -> "http://192.168.1.10:8080" */
        fun normalizeUrl(input: String): String {
            val trimmed = input.trim().trimEnd('/')
            if (trimmed.isEmpty()) return ""
            return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
        }
    }
}
