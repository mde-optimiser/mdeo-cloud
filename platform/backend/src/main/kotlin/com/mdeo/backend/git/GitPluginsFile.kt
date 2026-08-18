package com.mdeo.backend.git

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Encodes and decodes the contents of `.mdeo/plugins.json`: a project's
 * enabled plugins, as their registered urls.
 *
 * Pulled out of [GitRepositoryService] so the parsing and comparison rules,
 * which are pure and have no dependency on JGit or Postgres, can be tested
 * on their own.
 */
object GitPluginsFile {
    /**
     * Serializes a set of plugin urls to the file's JSON array content.
     *
     * @param urls The urls to encode
     * @return The JSON array content, encoded as bytes
     */
    fun serialize(urls: List<String>): ByteArray {
        val json = buildJsonArray { urls.forEach { add(JsonPrimitive(it)) } }
        return json.toString().toByteArray()
    }

    /**
     * Parses the file's content into a sorted list of urls, so it can be
     * compared regardless of how a client formatted or ordered the array.
     *
     * @param content The file's raw bytes
     * @return The urls it lists, sorted, or null if it does not parse as a
     *   JSON array of strings
     */
    fun parse(content: ByteArray): List<String>? =
        try {
            Json.parseToJsonElement(String(content)).jsonArray.map { it.jsonPrimitive.content }.sorted()
        } catch (_: Exception) {
            null
        }

    /**
     * Whether [content] already describes exactly [urls], regardless of how
     * it happens to be formatted or ordered.
     *
     * Used to decide whether existing bytes can be reused as-is rather than
     * re-encoded: re-encoding on every read would make a push whose JSON
     * formatting differs from the canonical output look like a real change
     * on the very next fetch, adding a commit that describes nothing real.
     *
     * @param content Existing file content to compare against
     * @param urls The urls that should be present
     * @return True if [content] parses to exactly the given urls
     */
    fun describes(content: ByteArray, urls: List<String>): Boolean = parse(content) == urls.sorted()
}
