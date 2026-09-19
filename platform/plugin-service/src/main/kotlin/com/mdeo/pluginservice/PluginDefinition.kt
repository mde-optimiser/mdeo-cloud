package com.mdeo.pluginservice

import com.mdeo.common.model.PluginTarget
import com.mdeo.common.model.PluginTargetKind
import com.mdeo.common.model.SessionType
import com.mdeo.pluginservice.session.SessionHandler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Everything a plugin service offers, from which its manifest and its session endpoint are built.
 *
 * A Kotlin plugin service provides contributions only. A language needs a Langium frontend, which
 * only exists in TypeScript, so a plugin that adds a language is written with `@mdeo/service-common`.
 *
 * @property id Unique id of the plugin
 * @property name Display name in the workbench
 * @property description Shown in the plugin list
 * @property icon A serialized Lucide icon, see [icon]
 * @property contributions The contributions this plugin provides
 */
data class PluginDefinition(
    val id: String,
    val name: String,
    val description: String,
    val icon: JsonArray,
    val contributions: List<Contribution>
) {
    init {
        val duplicate = contributions.groupBy { it.id }.entries.firstOrNull { it.value.size > 1 }
        require(duplicate == null) { "Contribution id '${duplicate!!.key}' is used more than once in plugin '$id'" }
        contributions.forEach { PluginTarget.of(PluginTargetKind.CONTRIBUTION, it.id) }
    }

    /**
     * The sessions this plugin serves, keyed by target and then by session name.
     */
    val sessions: Map<PluginTarget, Map<String, ServedSession>>
        get() = contributions.associate { PluginTarget.of(PluginTargetKind.CONTRIBUTION, it.id) to it.sessions }

    /**
     * Builds the manifest the backend reads from `GET /`.
     *
     * The `id` and `sessions` of each contribution payload are written here from the
     * [Contribution] itself, so what the manifest declares and what the endpoint serves cannot
     * disagree.
     *
     * @return The manifest as JSON
     */
    fun manifest(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("description", description)
        put("icon", icon)
        putJsonArray("languagePlugins") {}
        putJsonArray("contributionPlugins") {
            for (contribution in contributions) {
                add(buildJsonObject {
                    put("languageId", contribution.languageId)
                    put("description", contribution.description)
                    putJsonArray("additionalKeywords") {
                        contribution.additionalKeywords.forEach { add(JsonPrimitive(it)) }
                    }
                    putJsonArray("serverContributionPlugins") {
                        add(JsonObject(
                            contribution.payload() + mapOf(
                                "id" to JsonPrimitive(contribution.id),
                                "sessions" to manifestJson.encodeToJsonElement(
                                    contribution.sessions.mapValues { it.value.type }
                                )
                            )
                        ))
                    }
                })
            }
        }
    }

    companion object {
        private val manifestJson = Json { explicitNulls = false }
    }
}

/**
 * One contribution to another plugin's language.
 *
 * The payload is interpreted by the language being extended; for the script language, build
 * contributions with `scriptContribution` from `:script-plugin-service` rather than implementing
 * this directly.
 */
interface Contribution {
    /**
     * The contribution id, unique within a project. Callers reach its sessions as `contrib:<id>`.
     */
    val id: String

    /**
     * The id of the language being extended.
     */
    val languageId: String

    /**
     * What the contribution provides, shown in the plugin details view.
     */
    val description: String

    /**
     * Keywords the contribution introduces, for the target language's syntax highlighting.
     */
    val additionalKeywords: List<String>
        get() = emptyList()

    /**
     * The sessions this contribution accepts, by session name.
     */
    val sessions: Map<String, ServedSession>
        get() = emptyMap()

    /**
     * The language-specific fields of the payload. `id` and `sessions` are added from this
     * contribution and must not be included.
     *
     * @return The payload fields
     */
    fun payload(): JsonObject
}

/**
 * A session a contribution declares, together with what answers it.
 *
 * @property type What the manifest declares, and what callers negotiate against
 * @property handler What serves each connection
 */
data class ServedSession(
    val type: SessionType,
    val handler: SessionHandler
)

/**
 * Builds a serialized icon from its SVG elements, the same format `convertIcon` produces from a
 * Lucide icon in TypeScript.
 *
 * ```kotlin
 * icon("path" to mapOf("d" to "M12 2v4"), "circle" to mapOf("cx" to "12", "cy" to "12", "r" to "3"))
 * ```
 *
 * @param elements Each element's tag and attributes
 * @return The icon
 */
fun icon(vararg elements: Pair<String, Map<String, String>>): JsonArray = buildJsonArray {
    for ((tag, attributes) in elements) {
        add(buildJsonArray {
            add(JsonPrimitive(tag))
            add(buildJsonObject { attributes.forEach { (key, value) -> put(key, value) } })
        })
    }
}
