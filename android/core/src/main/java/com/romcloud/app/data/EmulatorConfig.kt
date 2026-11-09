package com.romcloud.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Réglages des émulateurs externes proposés par « Configurer … » (Eden, Dolphin) : emulator-config.json,
 * commun avec l'application Windows (desktop/src/renderer/emulator-config, copié dans les assets par
 * core/build.gradle.kts). Seuls les réglages et valeurs d'Android sont gardés.
 */
class EmulatorConfig private constructor(
    private val schemas: Map<String, Schema>,
    private val packages: List<Pair<String, String>>,
    /** Fichier de l'application Windows -> chemin dans le dossier de l'émulateur Android. */
    val files: Map<String, String>,
) {
    /** Libellé en anglais et en français (ou commun). */
    data class Label(val en: String, val fr: String) {
        fun get(language: String): String = if (language == "fr") fr else en
    }

    data class Option(val value: String, val label: Label)

    enum class Type { SELECT, BOOL, NUMBER }

    data class Field(
        val file: String,
        val section: String,
        val key: String,
        val label: Label,
        val type: Type,
        val options: List<Option> = emptyList(),
        val on: String = "",
        val off: String = "",
        val min: Int = 0,
        val max: Int = 0,
        val unit: String? = null,
        val hint: Label? = null,
    )

    data class Group(val title: Label, val fields: List<Field>)

    data class Schema(val id: String, val style: IniText.Style, val note: Label, val groups: List<Group>) {
        val fields: List<Field> get() = groups.flatMap { it.fields }
        /** Fichiers à lire (noms de l'application Windows). */
        val fileNames: List<String> get() = fields.map { it.file }.distinct()
    }

    /** Réglages de l'émulateur du paquet [pkg] (« dev.eden.eden_emulator.nightly » : ceux d'Eden), null sinon. */
    fun forPackage(pkg: String): Schema? =
        packages.firstOrNull { (prefix, _) -> pkg == prefix || pkg.startsWith("$prefix.") }?.let { schemas[it.second] }

    companion object {
        const val ASSET = "emulator-config.json"
        private const val PLATFORM = "android"

        private fun label(e: JsonElement?): Label? = when (e) {
            is JsonPrimitive -> e.contentOrNull?.let { Label(it, it) }
            is JsonObject -> {
                val en = e["en"]?.jsonPrimitive?.contentOrNull ?: return null
                Label(en, e["fr"]?.jsonPrimitive?.contentOrNull ?: en)
            }
            else -> null
        }

        /** Réglage ou valeur d'Android : sans « platforms », ou « android » dans la liste. */
        private fun here(o: JsonObject): Boolean =
            (o["platforms"] as? JsonArray)?.any { it.jsonPrimitive.contentOrNull == PLATFORM } ?: true

        private fun text(o: JsonObject, key: String) = o[key]?.jsonPrimitive?.contentOrNull

        private fun field(o: JsonObject): Field? {
            val type = when (text(o, "type")) {
                "select" -> Type.SELECT
                "bool" -> Type.BOOL
                "number" -> Type.NUMBER
                else -> return null
            }
            val options = (o["options"] as? JsonArray).orEmpty().map { it.jsonObject }.filter(::here).mapNotNull { opt ->
                val value = text(opt, "value") ?: return@mapNotNull null
                Option(value, label(opt["label"]) ?: Label(value, value))
            }
            if (type == Type.SELECT && options.isEmpty()) return null
            return Field(
                file = text(o, "file") ?: return null,
                section = text(o, "section") ?: return null,
                key = text(o, "key") ?: return null,
                label = label(o["label"]) ?: return null,
                type = type,
                options = options,
                on = text(o, "on").orEmpty(),
                off = text(o, "off").orEmpty(),
                min = o["min"]?.jsonPrimitive?.intOrNull ?: 0,
                max = o["max"]?.jsonPrimitive?.intOrNull ?: 0,
                unit = text(o, "unit"),
                hint = label(o["hint"]),
            )
        }

        fun parse(json: String): EmulatorConfig {
            val root = Json.parseToJsonElement(json).jsonObject
            val schemas = root["schemas"]!!.jsonObject.mapValues { (id, value) ->
                val s = value.jsonObject
                Schema(
                    id = id,
                    style = if (text(s, "style") == "eden") IniText.Style.EDEN else IniText.Style.SPACED,
                    note = label(s["notes"]?.jsonObject?.get(PLATFORM)) ?: Label("", ""),
                    groups = s["groups"]!!.jsonArray.map { g ->
                        val group = g.jsonObject
                        Group(
                            label(group["title"]) ?: Label("", ""),
                            group["fields"]!!.jsonArray.map { it.jsonObject }.filter(::here).mapNotNull(::field),
                        )
                    }.filter { it.fields.isNotEmpty() },
                )
            }
            val android = root[PLATFORM]!!.jsonObject
            val packages = android["packages"]!!.jsonArray.map { it.jsonObject }.mapNotNull { p ->
                val prefix = text(p, "prefix") ?: return@mapNotNull null
                val schema = text(p, "schema")?.takeIf { it in schemas } ?: return@mapNotNull null
                prefix to schema
            }
            val files = android["files"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
            return EmulatorConfig(schemas, packages, files)
        }

        /**
         * Valeur du fichier ramenée à celle d'une option ou d'un booléen du champ (Dolphin écrit
         * « 0x00000004 » pour 4) ; null si le réglage est absent du fichier.
         */
        fun valueOf(field: Field, raw: String?): String? {
            raw ?: return null
            return when (field.type) {
                Type.BOOL -> if (raw.equals(field.on, ignoreCase = true)) field.on else field.off
                Type.SELECT -> field.options.firstOrNull { it.value == raw || (number(raw) != null && number(it.value) == number(raw)) }?.value ?: raw
                Type.NUMBER -> number(raw)?.toString() ?: raw
            }
        }

        private fun number(s: String): Long? = s.trim().let { t ->
            if (t.startsWith("0x", ignoreCase = true)) t.substring(2).toLongOrNull(16) else t.toLongOrNull()
        }
    }
}
