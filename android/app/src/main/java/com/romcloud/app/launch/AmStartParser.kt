package com.romcloud.app.launch

import android.content.ComponentName
import android.content.Intent
import android.net.Uri

/**
 * Convertit les arguments « am start » des modèles d'émulateurs Daijishou en Intent.
 *
 * Exemple :
 * ```
 * -n com.retroarch.aarch64/com.retroarch.browser.retroactivity.RetroActivityFuture
 *  -e ROM {file.path}
 *  -e LIBRETRO snes9x
 *  --activity-clear-task
 * ```
 * Les placeholders ({file.path}, {file.uri}…) sont remplacés APRÈS le découpage en jetons,
 * pour qu'un chemin contenant des espaces reste un seul argument.
 */
object AmStartParser {

    fun tokenize(input: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var hasToken = false
        var i = 0
        while (i < input.length) {
            val c = input[i]
            when {
                quote != null && c == quote -> quote = null
                quote == null && (c == '"' || c == '\'') -> {
                    quote = c
                    hasToken = true
                }
                c == '\\' && i + 1 < input.length && quote != '\'' &&
                    (input[i + 1] == '"' || input[i + 1] == '\\' || input[i + 1].isWhitespace()) -> {
                    current.append(input[i + 1])
                    hasToken = true
                    i++
                }
                quote == null && c.isWhitespace() -> {
                    if (hasToken) tokens += current.toString()
                    current.clear()
                    hasToken = false
                }
                else -> {
                    current.append(c)
                    hasToken = true
                }
            }
            i++
        }
        if (hasToken) tokens += current.toString()
        return tokens
    }

    /** Remplace les placeholders connus ; les inconnus ({tags.xxx}…) deviennent une chaîne vide. */
    fun substitute(token: String, values: Map<String, String>): String =
        PLACEHOLDER.replace(token) { match -> values[match.groupValues[1]] ?: "" }

    fun parse(arguments: String, values: Map<String, String>): Intent {
        val tokens = tokenize(arguments).map { substitute(it, values) }
        val intent = Intent()
        var data: Uri? = null
        var type: String? = null
        var i = 0
        fun next(): String = tokens.getOrElse(++i) { "" }

        while (i < tokens.size) {
            when (tokens[i]) {
                "-n" -> intent.component = parseComponent(next())
                "-p" -> intent.setPackage(next())
                "-a" -> intent.action = next()
                "-d" -> data = next().takeIf { it.isNotEmpty() }?.let(Uri::parse)
                "-t" -> type = next().takeIf { it.isNotEmpty() }
                "-c" -> intent.addCategory(next())
                "-f" -> intent.addFlags(parseInt(next()))
                "-e", "--es" -> { val k = next(); intent.putExtra(k, next()) }
                "--esn" -> intent.putExtra(next(), null as String?)
                "--ez" -> { val k = next(); val v = next(); intent.putExtra(k, v.equals("true", true) || v == "1") }
                "--ei" -> { val k = next(); intent.putExtra(k, parseInt(next())) }
                "--el" -> { val k = next(); intent.putExtra(k, next().toLongOrNull() ?: 0L) }
                "--ef" -> { val k = next(); intent.putExtra(k, next().toFloatOrNull() ?: 0f) }
                "--eu" -> { val k = next(); intent.putExtra(k, Uri.parse(next())) }
                "--esa" -> { val k = next(); intent.putExtra(k, splitArray(next())) }
                "--activity-clear-task" -> intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                "--activity-clear-top" -> intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                "--activity-no-history" -> intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                "--activity-new-task" -> intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                "--activity-single-top" -> intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                "--activity-reorder-to-front" -> intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                "--activity-exclude-from-recents" -> intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                "--activity-multiple-task" -> intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                "--grant-read-uri-permission" -> intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                "--grant-write-uri-permission" -> intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                else -> Unit // option inconnue : ignorée
            }
            i++
        }
        when {
            data != null && type != null -> intent.setDataAndType(data, type)
            data != null -> intent.data = data
            type != null -> intent.type = type
        }
        return intent
    }

    /** Valeur d'un extra chaîne (-e / --es) du modèle, sans remplacer les placeholders. */
    fun stringExtra(arguments: String, key: String): String? {
        val tokens = tokenize(arguments)
        for (i in 0 until tokens.size - 2) {
            if ((tokens[i] == "-e" || tokens[i] == "--es") && tokens[i + 1] == key) return tokens[i + 2]
        }
        return null
    }

    /** "pkg/.Activity" ou "pkg/pkg.Activity" */
    fun parseComponent(value: String): ComponentName? {
        val slash = value.indexOf('/')
        if (slash <= 0) return null
        val pkg = value.substring(0, slash)
        val cls = value.substring(slash + 1).let { if (it.startsWith(".")) pkg + it else it }
        return ComponentName(pkg, cls)
    }

    private fun parseInt(value: String): Int =
        if (value.startsWith("0x", ignoreCase = true)) value.substring(2).toLong(16).toInt()
        else value.toIntOrNull() ?: 0

    /** Tableau séparé par des virgules ; "\," permet d'inclure une virgule. */
    private fun splitArray(value: String): Array<String> =
        value.split(Regex("(?<!\\\\),")).map { it.replace("\\,", ",") }.toTypedArray()

    private val PLACEHOLDER = Regex("\\{([A-Za-z0-9_.]+)\\}")
}
