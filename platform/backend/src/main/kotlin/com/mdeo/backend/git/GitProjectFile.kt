package com.mdeo.backend.git

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Encodes and decodes MDEO's generated project metadata file, which every
 * clone carries at [com.mdeo.backend.service.RESERVED_PROJECT_FILE].
 *
 * One file rather than a `.mdeo/` directory: the platform reserves the
 * `.mdeo` *extension* (see [com.mdeo.backend.service.RESERVED_FILE_EXTENSION]),
 * which keeps a single generated file collision-free without also taking a
 * whole directory name out of the project's namespace.
 *
 * A JSON object rather than a bare array, because the file is named for the
 * project rather than for its one current key: what it holds has to say so
 * itself, and anything added later gets its own key rather than another
 * reserved path.
 *
 * Pulled out of [GitRepositoryService] so the parsing and comparison rules,
 * which are pure and have no dependency on JGit or Postgres, can be tested
 * on their own.
 */
object GitProjectFile {
    /**
     * The key carrying the project's enabled plugins, as their registered urls.
     */
    private const val PLUGINS_KEY = "plugins"

    /**
     * Pretty-printed because this is a file people read and hand-edit in a
     * clone, and because a one-url-per-line array gives a plugin change a
     * one-line diff rather than a rewritten line.
     */
    private val json = Json { prettyPrint = true }

    /**
     * The metadata a project file describes.
     *
     * @property plugins The urls it lists, sorted so two files listing the
     *   same plugins in different orders compare equal, or null when the file
     *   carries no `plugins` key at all. Null and an empty list mean
     *   different things: no key means the file does not describe plugins and
     *   a push must leave them alone, an empty list means it describes having
     *   none.
     */
    data class Contents(val plugins: List<String>?)

    /**
     * Serializes a project's metadata to the file's JSON content.
     *
     * @param urls The enabled plugin urls to encode
     * @return The file content, encoded as bytes
     */
    fun serialize(urls: List<String>): ByteArray {
        val document = buildJsonObject {
            put(PLUGINS_KEY, buildJsonArray { urls.sorted().forEach { add(JsonPrimitive(it)) } })
        }
        return (json.encodeToString(JsonObject.serializer(), document) + "\n").toByteArray()
    }

    /**
     * Parses the file's content.
     *
     * Every entry has to be a JSON *string*: `jsonPrimitive.content` alone
     * would also accept numbers and booleans, so a pushed `[false]` would
     * parse to the url `"false"`, match no registered plugin, and quietly
     * clear every plugin the project had while logging only that `false` was
     * unknown. Malformed metadata is refused instead.
     *
     * @param content The file's raw bytes
     * @return What the file describes, or null if it is not a JSON object, or
     *   carries a `plugins` key that is not an array of strings
     */
    fun parse(content: ByteArray): Contents? =
        try {
            val document = Json.parseToJsonElement(String(content)) as? JsonObject ?: return null
            when (val plugins = document[PLUGINS_KEY]) {
                null -> Contents(plugins = null)
                is JsonArray -> {
                    val urls = plugins.map { entry ->
                        val primitive = entry as? JsonPrimitive ?: return null
                        if (!primitive.isString) return null
                        primitive.content
                    }
                    Contents(plugins = urls.sorted())
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }

    /**
     * Whether [content] already describes exactly [urls] as the enabled
     * plugins, regardless of how it happens to be formatted or ordered.
     *
     * Used to decide whether existing bytes can be reused as-is rather than
     * re-encoded: re-encoding on every read would make a push whose JSON
     * formatting differs from the canonical output look like a real change
     * on the very next fetch, adding a commit that describes nothing real.
     *
     * @param content Existing file content to compare against
     * @param urls The urls that should be present
     * @return True if [content] lists exactly the given urls
     */
    fun describes(content: ByteArray, urls: List<String>): Boolean =
        parse(content)?.plugins == urls.sorted()
}
