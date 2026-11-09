package com.romcloud.app.data

/**
 * Fichiers INI des émulateurs externes modifiés clé par clé (« Configurer … »), le reste du fichier
 * gardé tel quel ; même fonctionnement que l'application Windows (emulator_config.rs).
 */
object IniText {

    enum class Style {
        /** « clé=valeur » précédé de « clé\default=false » (Eden : sinon la valeur par défaut est prise). */
        EDEN,
        /** « Clé = Valeur » (Dolphin). */
        SPACED,
    }

    /** Clé et valeur d'une ligne « clé=valeur » (espaces autour du « = » retirés). */
    private fun keyValue(line: String): Pair<String, String>? {
        val trimmed = line.trim()
        if (trimmed.startsWith('#') || trimmed.startsWith(';') || trimmed.startsWith('[')) return null
        val eq = trimmed.indexOf('=')
        if (eq < 0) return null
        return trimmed.substring(0, eq).trim() to trimmed.substring(eq + 1).trim()
    }

    /** Valeurs du fichier : « Section/clé » -> valeur (lignes « clé\default » ignorées). */
    fun parse(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var section = ""
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith('[') && trimmed.endsWith(']')) {
                section = trimmed.substring(1, trimmed.length - 1)
            } else {
                val (k, v) = keyValue(line) ?: continue
                if (!k.endsWith("\\default")) out["$section/$k"] = v
            }
        }
        return out
    }

    /**
     * [text] avec [key] de [section] à [value] : ligne remplacée, sinon ajoutée à la fin de la section
     * (avant ses lignes vides), section ajoutée à la fin du fichier si absente.
     */
    fun setValue(text: String, section: String, key: String, value: String, style: Style): String {
        val eol = if (text.contains("\r\n")) "\r\n" else "\n"
        val defaultKey = "$key\\default"
        val newLines = when (style) {
            Style.EDEN -> listOf("$defaultKey=false", "$key=$value")
            Style.SPACED -> listOf("$key = $value")
        }
        val lines = text.split(eol).toMutableList()
        if (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.size - 1)
        val header = "[$section]"
        val start = lines.indexOfFirst { it.trim().equals(header, ignoreCase = true) }
        if (start < 0) {
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
            lines.add(header)
            lines.addAll(newLines)
            return lines.joinToString(eol) + eol
        }
        var stop = (start + 1 until lines.size).firstOrNull { lines[it].trim().startsWith('[') } ?: lines.size
        fun same(line: String, k: String) = keyValue(line)?.first?.equals(k, ignoreCase = true) == true
        // Anciennes lignes de la clé (et de son « \default ») retirées ; nouvelles à la place de la première.
        var at: Int? = null
        var i = start + 1
        while (i < stop) {
            if (same(lines[i], key) || (style == Style.EDEN && same(lines[i], defaultKey))) {
                if (at == null) at = i
                lines.removeAt(i)
                stop--
            } else {
                i++
            }
        }
        val pos = at ?: run {
            var p = stop
            while (p > start + 1 && lines[p - 1].isBlank()) p--
            p
        }
        lines.addAll(pos, newLines)
        return lines.joinToString(eol) + eol
    }
}
