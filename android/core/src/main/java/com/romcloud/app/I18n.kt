package com.romcloud.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.util.Locale

/**
 * Langue de l'application, indépendante de celle du système : « system », « fr » ou « en ».
 *
 * Les activités l'appliquent dans attachBaseContext() (wrap), et le code non graphique
 * (erreurs, notifications) passe par [I18n] pour obtenir les textes dans cette langue.
 */
object AppLanguage {
    const val SYSTEM = "system"
    val SUPPORTED = listOf("fr", "en")
    private const val PREFS = "romcloud"
    private const val KEY = "language"

    fun stored(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, SYSTEM) ?: SYSTEM

    fun store(context: Context, code: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, code).apply()
        I18n.invalidate()
    }

    /** Langue du système (non affectée par Locale.setDefault()). */
    private fun systemLocale(): Locale = Resources.getSystem().configuration.locales[0]

    fun locale(code: String): Locale = if (code == SYSTEM) systemLocale() else Locale.forLanguageTag(code)

    /** Code effectivement utilisé (« fr » ou « en ») : pour l'en-tête Accept-Language du serveur. */
    fun effective(context: Context): String {
        val language = locale(stored(context)).language
        return if (language in SUPPORTED) language else "en"
    }

    /** Contexte dont les ressources sont dans la langue choisie (APK complet : pas de téléchargement de langue). */
    @SuppressLint("AppBundleLocaleChanges")
    fun wrap(base: Context): Context {
        val locale = locale(stored(base))
        Locale.setDefault(locale) // formats numériques (String.format) dans la même langue
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}

/** Textes traduits hors de Compose (erreurs, notifications, messages des ViewModels). */
@SuppressLint("StaticFieldLeak") // contexte de l’application uniquement : même durée de vie que l’app
object I18n {
    private lateinit var app: Context
    private var cached: Pair<String, Context>? = null

    fun init(context: Context) {
        app = context.applicationContext
    }

    internal fun invalidate() {
        cached = null
    }

    private fun context(): Context {
        val code = AppLanguage.stored(app)
        cached?.let { (c, ctx) -> if (c == code) return ctx }
        return AppLanguage.wrap(app).also { cached = code to it }
    }

    fun get(@StringRes id: Int, vararg args: Any): String = context().getString(id, *args)

    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String =
        context().resources.getQuantityString(id, count, *args)

    fun language(): String = AppLanguage.effective(app)
}
