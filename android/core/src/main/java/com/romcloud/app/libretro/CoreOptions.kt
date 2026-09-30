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

/** Groupe d'options affiché sous un même titre ; [title] null = options générales. */
internal data class CoreOptionGroup(val title: String?, val options: List<CoreOption>)

internal object CoreOptionGroups {

    private val SEPARATORS = Regex("[_-]")

    /**
     * Répartit les options par type d'après leur clé : le préfixe commun à toutes (nom du cœur,
     * « beetle_psx_hw_ ») est retiré, puis le mot suivant sert de groupe (« gpu », « cpu »…).
     * Un mot porté par une seule option, ou une clé sans mot suivant, va dans les options générales,
     * affichées en premier ; les autres groupes suivent par ordre alphabétique.
     */
    fun group(options: List<CoreOption>): List<CoreOptionGroup> {
        val tokens = options.associateWith { it.key.lowercase().split(SEPARATORS).filter(String::isNotEmpty) }
        val common = if (options.size < 2) 0 else commonPrefixSize(tokens.values.toList())
        val typeOf = tokens.mapValues { (_, t) -> t.getOrNull(common)?.takeIf { t.size > common + 1 } }
        val counts = typeOf.values.filterNotNull().groupingBy { it }.eachCount()
        return options
            .groupBy { option -> typeOf[option]?.takeIf { (counts[it] ?: 0) >= 2 } }
            .map { (type, list) -> CoreOptionGroup(type?.let(::title), list.sortedBy { it.label.lowercase() }) }
            .sortedWith(compareBy({ it.title != null }, { it.title }))
    }

    /** Nombre de mots communs à toutes les clés, en laissant au moins un mot à chacune. */
    private fun commonPrefixSize(keys: List<List<String>>): Int {
        val max = keys.minOf { it.size } - 1
        var n = 0
        while (n < max && keys.all { it[n] == keys[0][n] }) n++
        return n
    }

    /** « gpu » -> « GPU », « video » -> « Video ». */
    private fun title(type: String) =
        if (type.length <= 3) type.uppercase() else type.replaceFirstChar { it.uppercase() }
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
