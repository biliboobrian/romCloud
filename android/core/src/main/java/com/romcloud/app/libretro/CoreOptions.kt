package com.romcloud.app.libretro

import android.content.Context
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Option déclarée par un cœur libretro : libellé, valeurs possibles (la première par défaut). */
internal data class CoreOption(val key: String, val label: String, val values: List<String>, val value: String) {
    val default: String get() = values.first()

    companion object {
        /**
         * [description] au format libretro « Libellé; valeur1|valeur2|… » (ex.
         * « Internal resolution; 1x(native)|2x|4x »).
         */
        fun parse(key: String, description: String?, value: String): CoreOption {
            val separator = description?.indexOf("; ") ?: -1
            if (description == null || separator < 0) return CoreOption(key, key, listOf(value), value)
            val values = description.substring(separator + 2).split('|').filter { it.isNotEmpty() }
            return CoreOption(
                key = key,
                label = description.substring(0, separator).ifBlank { key },
                values = values.ifEmpty { listOf(value) },
                value = value,
            )
        }
    }
}

/**
 * Options de cœur choisies par l'utilisateur, mémorisées par système (clé libretro -> valeur) :
 * réappliquées au lancement suivant d'un jeu du même système.
 */
internal class CoreOptionsStore(context: Context) {

    private val prefs = context.getSharedPreferences("libretro_options", Context.MODE_PRIVATE)
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    fun load(systemId: String): Map<String, String> =
        prefs.getString(systemId, null)
            ?.let { runCatching { Json.decodeFromString(serializer, it) }.getOrNull() }
            .orEmpty()

    fun set(systemId: String, key: String, value: String) {
        save(systemId, load(systemId) + (key to value))
    }

    fun clear(systemId: String) = prefs.edit().remove(systemId).apply()

    private fun save(systemId: String, options: Map<String, String>) {
        // commit() : le processus de jeu est arrêté brutalement en quittant.
        prefs.edit().putString(systemId, Json.encodeToString(serializer, options)).commit()
    }
}
