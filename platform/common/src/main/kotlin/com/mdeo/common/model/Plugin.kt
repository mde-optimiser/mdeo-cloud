package com.mdeo.common.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Represents a backend plugin configuration.
 *
 * @property id Unique identifier for the plugin
 * @property url URL where the plugin is hosted or can be accessed
 * @property name Display name of the plugin
 * @property description Brief description of the plugin's functionality
 * @property icon Icon data in Lucide IconNode format
 * @property default Whether this plugin is added by default to new projects
 * @property languagePlugins List of language plugins provided by this plugin
 * @property contributionPlugins List of contribution plugins provided by this plugin
 */
@Serializable
data class BackendPlugin(
    val id: String,
    val url: String,
    val name: String,
    val description: String,
    val icon: JsonArray,
    val default: Boolean = false,
    val languagePlugins: List<BackendLanguagePlugin> = emptyList(),
    val contributionPlugins: List<BackendContributionPlugin> = emptyList()
)

/**
 * Represents a fully resolved plugin with all its metadata.
 *
 * @property id Unique identifier for the plugin
 * @property url URL where the plugin is hosted or can be accessed
 * @property name Display name of the plugin
 * @property description Brief description of the plugin's functionality
 * @property icon Icon data in Lucide IconNode format
 * @property languagePlugins List of language plugins provided by this plugin
 * @property contributionPlugins List of contribution plugins provided by this plugin
 */
@Serializable
data class ResolvedPlugin(
    val id: String,
    val url: String,
    val name: String,
    val description: String,
    val icon: JsonArray,
    val languagePlugins: List<BackendLanguagePlugin> = emptyList(),
    val contributionPlugins: List<BackendContributionPlugin> = emptyList()
)

/**
 * Represents a contribution plugin that extends a language with additional functionality.
 *
 * @property id Unique identifier for the contribution plugin
 * @property languageId The language ID that this contribution plugin is associated with
 * @property description Brief description of what this contribution plugin provides
 * @property additionalKeywords Optional array of additional keywords that this contribution plugin introduces
 * @property serverContributionPlugins Server contribution plugins as arbitrary JSON objects.
 *            Each payload carries its own `id` and, when it declares any, its `sessions`.
 */
@Serializable
data class BackendContributionPlugin(
    val id: String,
    val languageId: String,
    val description: String = "",
    val additionalKeywords: List<String> = emptyList(),
    val serverContributionPlugins: List<JsonObject> = emptyList()
)

/**
 * Represents a language plugin configuration from the plugin manifest.
 *
 * @property id Unique identifier for the language plugin
 * @property name Display name of the language
 * @property extension File extension including the dot (e.g., ".model")
 * @property newFileAction When true, an action dialog will be triggered when a new file is created
 * @property serverPlugin Server plugin configuration
 * @property graphicalEditorPlugin Optional graphical editor plugin configuration
 * @property textualEditorPlugin Optional textual editor plugin configuration
 * @property icon Icon data in Lucide IconNode format
 * @property documentationUrl Optional URL of the documentation for this language
 */
@Serializable
data class BackendLanguagePlugin(
    val id: String,
    val name: String,
    val extension: String? = null,
    val newFileAction: Boolean = false,
    val serverPlugin: LanguageServerPlugin,
    val graphicalEditorPlugin: LanguageGraphicalEditorPlugin? = null,
    val textualEditorPlugin: LanguageTextualEditorPlugin? = null,
    val icon: JsonArray,
    val isGenerated: Boolean = false,
    val documentationUrl: String? = null
)

/**
 * Server plugin configuration for a language.
 *
 * @property import Import path which resolves to the server plugin module
 */
@Serializable
data class LanguageServerPlugin(
    val import: String
)

/**
 * Graphical editor plugin configuration.
 * Provides the container configuration for GLSP/Sprotty diagrams.
 *
 * @property import Import path which resolves to the container configuration
 * @property stylesUrl URL to the CSS styles for this editor
 * @property stylesCls CSS class name to apply to the editor container
 */
@Serializable
data class LanguageGraphicalEditorPlugin(
    val import: String,
    val stylesUrl: String,
    val stylesCls: String
)

/**
 * Textual editor plugin configuration.
 * Provides Monaco editor configuration for syntax highlighting and language features.
 *
 * @property languageConfiguration Monaco language configuration as JSON
 * @property monarchTokensProvider Monarch tokens provider for syntax highlighting as JSON
 */
@Serializable
data class LanguageTextualEditorPlugin(
    val languageConfiguration: JsonObject,
    val monarchTokensProvider: JsonObject
)

/**
 * Request payload for creating a new plugin.
 *
 * @property url URL of the plugin to be created
 */
@Serializable
data class CreatePluginRequest(
    val url: String
)

/**
 * Request payload for updating the default status of a plugin.
 *
 * @property default Whether the plugin should be added by default to new projects
 */
@Serializable
data class UpdatePluginDefaultRequest(
    val default: Boolean
)

/**
 * Request payload for adding a plugin to a project.
 *
 * @property pluginId Unique identifier of the plugin to add to the project
 */
@Serializable
data class AddPluginToProjectRequest(
    val pluginId: String
)

/**
 * Response for file read operations including version information.
 *
 * @property content Text file content
 * @property version Current version of the file
 */
@Serializable
data class FileReadResponse(
    val content: String,
    val version: Int
)

/**
 * Source data for file or directory.
 *
 * @property version Current version of the file (only for files, not directories)
 * @property content Text file content (only for files, not directories)
 * @property path Path to the file or directory
 */
@Serializable
data class FileSource(
    val version: Int,
    val content: String,
    val path: String
)

/**
 * Request payload for computing file data.
 * Can be used for both files and directories.
 * The path is now included inside the FileSource object.
 *
 * @property project Project ID the file/directory belongs to
 * @property source Source data (version, content, and path), only present for files, absent for directories
 * @property contributionPlugins Server contribution plugins associated with this language; left out
 *           when the service is sent [contributionHash] alone
 * @property contributionHash Identifies [contributionPlugins]. A service that already holds the set
 *           under this hash is sent the hash alone, see `ContributionDelivery` in the backend.
 */
@Serializable
data class FileDataComputeRequest(
    val project: String,
    val source: FileSource? = null,
    val contributionPlugins: List<JsonObject>? = null,
    val contributionHash: String? = null
)

/**
 * Response from plugin file data computation.
 *
 * @property data Computed data as JSON
 * @property fileDependencies List of file dependencies with versions
 * @property dataDependencies List of data dependencies (other file-data computations)
 * @property additionalFileData Optional additional file data computed as part of this computation
 */
@Serializable
data class FileDataComputeResponse(
    val data: JsonElement,
    val fileDependencies: List<FileDependency> = emptyList(),
    val dataDependencies: List<DataDependency> = emptyList(),
    val additionalFileData: List<AdditionalFileData> = emptyList()
)

/**
 * Represents a file dependency for file data computation.
 *
 * @property path Path to the file or directory
 * @property version Version of the file at computation time (only for files, not directories)
 */
@Serializable
data class FileDependency(
    val path: String,
    val version: Int? = null
)

/**
 * Represents a data dependency for file data computation.
 *
 * @property path Path to the file or directory
 * @property key Data key
 * @property version Version of the file at computation time (only for files, not directories)
 */
@Serializable
data class DataDependency(
    val path: String,
    val key: String,
    val version: Int? = null
)

/**
 * Represents additional file data computed as part of another computation.
 *
 * @property path Path to the file or directory
 * @property key Data key
 * @property data Computed data
 * @property sourceVersion Version of the source file (only for files, not directories)
 * @property fileDependencies List of file dependencies with versions
 * @property dataDependencies List of data dependencies
 */
@Serializable
data class AdditionalFileData(
    val path: String,
    val key: String,
    val data: JsonElement,
    val sourceVersion: Int? = null,
    val fileDependencies: List<FileDependency> = emptyList(),
    val dataDependencies: List<DataDependency> = emptyList()
)

/**
 * Response for file data request.
 *
 * @property data The computed file data
 * @property version The version of the source file used to compute the data (only for files, not directories)
 */
@Serializable
data class FileDataResponse(
    val data: JsonElement,
    val version: Int? = null
)

/**
 * Request payload for language plugin requests.
 * This is a general request mechanism that forwards the body to the plugin.
 *
 * @property project Project ID the request belongs to
 * @property body Arbitrary JSON body to be forwarded to the plugin
 * @property contributionPlugins Server contribution plugins associated with this language; left out
 *           when the service is sent [contributionHash] alone
 * @property contributionHash Identifies [contributionPlugins]. A service that already holds the set
 *           under this hash is sent the hash alone, see `ContributionDelivery` in the backend.
 */
@Serializable
data class LanguagePluginRequest(
    val project: String,
    val body: JsonElement,
    val contributionPlugins: List<JsonObject>? = null,
    val contributionHash: String? = null
)

/**
 * Response from language plugin request.
 *
 * @property data Response data as JSON
 */
@Serializable
data class LanguagePluginResponse(
    val data: JsonElement
)
