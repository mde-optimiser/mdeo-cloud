package com.mdeo.backend.service

import com.mdeo.common.model.SessionType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a manifest may declare, and what makes the backend refuse it as a whole.
 */
class ManifestProblemsTest {

    private val functions = SessionType("script-functions", listOf(1))

    private fun manifest(
        languagePlugins: List<ManifestLanguagePlugin> = emptyList(),
        payloads: List<JsonObject> = emptyList()
    ) = PluginManifest(
        id = "p",
        name = "Plugin",
        description = "",
        icon = buildJsonArray { },
        languagePlugins = languagePlugins,
        contributionPlugins = listOf(
            buildJsonObject {
                put("languageId", "script")
                putJsonArray("serverContributionPlugins") { payloads.forEach { add(it) } }
            }
        )
    )

    private fun language(id: String, sessions: Map<String, SessionType> = emptyMap()) = ManifestLanguagePlugin(
        id = id,
        name = id,
        serverPlugin = ManifestServerPlugin("./plugin.js"),
        icon = buildJsonArray { },
        sessions = sessions
    )

    private fun contribution(id: String?, withSessions: Boolean = false) = buildJsonObject {
        put("type", "script-language-contribution")
        if (id != null) put("id", id)
        if (withSessions) {
            putJsonObject("sessions") {
                putJsonObject("functions") {
                    put("protocol", functions.protocol)
                    putJsonArray("versions") { functions.versions.forEach { add(JsonPrimitive(it)) } }
                }
            }
        }
    }

    @Test
    fun `a manifest whose addresses all work is stored`() {
        val problems = manifestProblems(
            manifest(
                languagePlugins = listOf(language("script", mapOf("functions" to functions)), language("bad id")),
                payloads = listOf(contribution("stats", withSessions = true), contribution(null))
            )
        )
        assertEquals(emptyList(), problems)
    }

    @Test
    fun `a language that declares sessions needs an id an address can carry`() {
        val problems = manifestProblems(manifest(languagePlugins = listOf(language("bad id", mapOf("functions" to functions)))))
        assertEquals(1, problems.size)
        assertTrue("declares sessions" in problems.single(), problems.single())
    }

    @Test
    fun `a contribution that declares sessions needs an id`() {
        val problems = manifestProblems(manifest(payloads = listOf(contribution(null, withSessions = true))))
        assertEquals(listOf("a contribution declares sessions, but carries no id to be addressed by"), problems)
    }

    @Test
    fun `a contribution id must be usable as an address and may be declared once`() {
        assertTrue(
            "cannot be used as an address" in manifestProblems(manifest(payloads = listOf(contribution("has space")))).single()
        )
        assertTrue(
            "declared more than once" in
                manifestProblems(manifest(payloads = listOf(contribution("stats"), contribution("stats")))).single()
        )
    }
}
