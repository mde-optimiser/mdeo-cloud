package com.mdeo.backend.service

import com.mdeo.backend.database.ContributionPluginsTable
import com.mdeo.backend.database.LanguagePluginsTable
import com.mdeo.backend.database.PluginsTable
import com.mdeo.backend.database.ProjectPluginsTable
import com.mdeo.common.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.*
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

/**
 * Plugin manifest returned from plugin's GET / endpoint.
 */
@Serializable
data class PluginManifest(
    val id: String,
    val url: String? = null,
    val name: String,
    val description: String,
    val icon: JsonArray,
    val languagePlugins: List<ManifestLanguagePlugin> = emptyList(),
    val contributionPlugins: List<JsonObject> = emptyList()
)

@Serializable
data class ManifestLanguagePlugin(
    val id: String,
    val name: String,
    val extension: String? = null,
    val newFileAction: Boolean = false,
    val serverPlugin: ManifestServerPlugin,
    val graphicalEditorPlugin: ManifestGraphicalEditorPlugin? = null,
    val textualEditorPlugin: ManifestTextualEditorPlugin? = null,
    val icon: JsonArray,
    val isGenerated: Boolean = false,
    val documentationUrl: String? = null
)

@Serializable
data class ManifestServerPlugin(
    val import: String
)

@Serializable
data class ManifestGraphicalEditorPlugin(
    val import: String,
    val stylesUrl: String,
    val stylesCls: String
)

@Serializable
data class ManifestTextualEditorPlugin(
    val languageConfiguration: JsonObject,
    val monarchTokensProvider: JsonObject
)

/**
 * Service for managing plugins and their associations with projects.
 *
 * @param services The injected services providing access to configuration and other services
 */
class PluginService(services: InjectedServices) : BaseService(), InjectedServices by services {
    private val logger = LoggerFactory.getLogger(PluginService::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Plugin configuration settings 
     */
    private val pluginConfig get() = config.plugin
    private val pluginBaseUrl get() = pluginConfig.baseUrl
    private val internalPluginBaseUrl get() = pluginConfig.internalBaseUrl

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .version(if (pluginConfig.forceHttp1) HttpClient.Version.HTTP_1_1 else HttpClient.Version.HTTP_2)
        .build()

    /**
     * Retrieves all registered plugins.
     *
     * @return List of all plugins
     */
    fun getPlugins(): List<BackendPlugin> {
        return transaction {
            PluginsTable.selectAll()
                .map { row ->
                    val id = row[PluginsTable.id]
                    val url = row[PluginsTable.url]
                    val name = row[PluginsTable.name]
                    val description = row[PluginsTable.description]
                    val icon = json.parseToJsonElement(row[PluginsTable.icon]).jsonArray
                    val default = row[PluginsTable.default]
                    createBackendPlugin(id.toJavaUuid(), resolvePluginUrl(url, useInternal = false), name, description, icon, default)
                }
        }
    }

    /**
     * Retrieves all plugins associated with a specific project.
     *
     * @param projectId The UUID of the project
     * @return List of plugins associated with the project
     */
    fun getProjectPlugins(projectId: UUID): List<BackendPlugin> {
        return transaction {
            (ProjectPluginsTable innerJoin PluginsTable)
                .selectAll()
                .where { ProjectPluginsTable.projectId eq projectId.toKotlinUuid() }
                .map { row ->
                    val id = row[PluginsTable.id]
                    val url = row[PluginsTable.url]
                    val name = row[PluginsTable.name]
                    val description = row[PluginsTable.description]
                    val icon = json.parseToJsonElement(row[PluginsTable.icon]).jsonArray
                    val default = row[PluginsTable.default]
                    createBackendPlugin(id.toJavaUuid(), resolvePluginUrl(url, useInternal = false), name, description, icon, default)
                }
        }
    }

    /**
     * Urls of the plugins enabled for a project, as registered rather than
     * resolved against a base url, sorted for a stable order.
     *
     * Used to publish a project's plugin selection as part of its git tree
     * (see [com.mdeo.backend.git.GitRepositoryService]), where the registered
     * url is what a push can be matched back against.
     *
     * @param projectId The UUID of the project
     * @return The enabled plugins' registered urls
     */
    fun getProjectPluginUrls(projectId: UUID): List<String> {
        return transaction {
            (ProjectPluginsTable innerJoin PluginsTable)
                .select(PluginsTable.url)
                .where { ProjectPluginsTable.projectId eq projectId.toKotlinUuid() }
                .map { it[PluginsTable.url] }
                .sorted()
        }
    }

    /**
     * Replaces a project's enabled plugins to match exactly the given urls.
     *
     * A url that does not match a registered plugin is skipped rather than
     * failing the call, since a git clone can be pushed back to a different
     * MDEO instance than the one it came from, which may not have every
     * plugin registered.
     *
     * @param projectId The UUID of the project
     * @param urls The urls that should be enabled, replacing the current set
     * @return The urls that did not match a registered plugin
     */
    fun setProjectPlugins(projectId: UUID, urls: List<String>): List<String> {
        val normalized = urls.map { it.trimEnd('/') + "/" }.toSet()

        val unknown = transaction {
            val resolved = PluginsTable.selectAll()
                .where { PluginsTable.url inList normalized }
                .associate { it[PluginsTable.url] to it[PluginsTable.id] }

            val project = projectId.toKotlinUuid()
            ProjectPluginsTable.deleteWhere { ProjectPluginsTable.projectId eq project }
            for (pluginId in resolved.values) {
                ProjectPluginsTable.insert {
                    it[ProjectPluginsTable.projectId] = project
                    it[ProjectPluginsTable.pluginId] = pluginId
                }
            }

            normalized - resolved.keys
        }

        fileDataService.invalidateProjectData(projectId)
        return unknown.toList()
    }

    /**
     * Retrieves a specific plugin by ID.
     *
     * @param pluginId The UUID of the plugin
     * @return ApiResult containing the plugin information, or an error
     */
    fun getPlugin(pluginId: UUID): ApiResult<BackendPlugin> {
        return transaction {
            val row = PluginsTable.selectAll()
                .where { PluginsTable.id eq pluginId.toKotlinUuid() }
                .firstOrNull()

            if (row == null) {
                return@transaction pluginFailure(ErrorCodes.PLUGIN_NOT_FOUND, "Plugin not found")
            }

            val url = row[PluginsTable.url]
            val name = row[PluginsTable.name]
            val description = row[PluginsTable.description]
            val icon = json.parseToJsonElement(row[PluginsTable.icon]).jsonArray
            val default = row[PluginsTable.default]

            success(createBackendPlugin(pluginId, resolvePluginUrl(url, useInternal = false), name, description, icon, default))
        }
    }

    /**
     * Creates a new plugin with the specified URL.
     * Fetches the plugin manifest and stores language plugins.
     *
     * @param url The URL of the plugin
     * @return ApiResult containing the plugin ID if successful, or an error
     */
    suspend fun createPlugin(url: String): ApiResult<BackendPlugin> {
        val normalizedUrl = url.trimEnd('/') + "/"
        val existingCheck = transaction {
            PluginsTable.selectAll()
                .where { PluginsTable.url eq normalizedUrl }
                .firstOrNull()
        }

        if (existingCheck != null) {
            return pluginFailure(
                ErrorCodes.PLUGIN_ALREADY_EXISTS,
                "Plugin with URL already exists: $normalizedUrl"
            )
        }

        val manifest = try {
            fetchPluginManifest(normalizedUrl)
        } catch (e: Exception) {
            logger.error("Failed to fetch plugin manifest from $normalizedUrl", e)
            return pluginFailure(
                ErrorCodes.PLUGIN_NOT_FOUND,
                "Failed to fetch plugin manifest: ${e.message}"
            )
        }

        return transaction {
            val pluginId = UUID.randomUUID()
            val now = Instant.now()

            PluginsTable.insert {
                it[id] = pluginId.toKotlinUuid()
                it[PluginsTable.url] = normalizedUrl
                it[name] = manifest.name
                it[description] = manifest.description
                it[icon] = manifest.icon.toString()
                it[createdAt] = now
                it[updatedAt] = now
            }

            storeLanguagePlugins(pluginId, manifest.languagePlugins, now)

            storeContributionPlugins(pluginId, manifest.contributionPlugins, now)

            success(
                createBackendPlugin(
                    pluginId,
                    resolvePluginUrl(url, useInternal = false),
                    manifest.name,
                    manifest.description,
                    manifest.icon,
                    false
                )
            )
        }
    }

    /**
     * Refreshes plugin data by re-fetching the manifest and updating stored language plugins.
     * Invalidates file data for all projects using this plugin.
     *
     * @param pluginId The UUID of the plugin to refresh
     * @return ApiResult indicating success or containing an error
     */
    suspend fun refreshPluginData(pluginId: UUID): ApiResult<Unit> {
        val url = transaction {
            PluginsTable.selectAll()
                .where { PluginsTable.id eq pluginId.toKotlinUuid() }
                .firstOrNull()
                ?.get(PluginsTable.url)
        } ?: return pluginFailure(ErrorCodes.PLUGIN_NOT_FOUND, "Plugin not found")

        val manifest = try {
            fetchPluginManifest(url)
        } catch (e: Exception) {
            logger.error("Failed to fetch plugin manifest from $url", e)
            return pluginFailure(
                ErrorCodes.PLUGIN_NOT_FOUND,
                "Failed to fetch plugin manifest: ${e.message}"
            )
        }

        transaction {
            val now = Instant.now()

            LanguagePluginsTable.deleteWhere { LanguagePluginsTable.pluginId eq pluginId.toKotlinUuid() }

            ContributionPluginsTable.deleteWhere { ContributionPluginsTable.pluginId eq pluginId.toKotlinUuid() }

            storeLanguagePlugins(pluginId, manifest.languagePlugins, now)

            storeContributionPlugins(pluginId, manifest.contributionPlugins, now)

            PluginsTable.update({ PluginsTable.id eq pluginId.toKotlinUuid() }) {
                it[name] = manifest.name
                it[description] = manifest.description
                it[icon] = manifest.icon.toString()
                it[updatedAt] = now
            }
        }

        fileDataService.invalidatePluginData(pluginId)
        
        return success(Unit)
    }

    /**
     * Refreshes all plugins by re-fetching each manifest and updating stored language plugins.
     *
     * @return ApiResult indicating success or containing an error from the first failed refresh
     */
    suspend fun refreshAllPluginsData(): ApiResult<Unit> {
        val pluginIds = transaction {
            PluginsTable.selectAll().map { it[PluginsTable.id].toJavaUuid() }
        }

        for (pluginId in pluginIds) {
            when (val refreshResult = refreshPluginData(pluginId)) {
                is ApiResult.Success -> Unit
                is ApiResult.Failure -> return refreshResult
            }
        }

        return success(Unit)
    }

    /**
     * Fetches the plugin manifest from the plugin's GET / endpoint.
     * Uses the internal base URL for backend-to-plugin communication.
     */
    private suspend fun fetchPluginManifest(url: String): PluginManifest {
        return withContext(Dispatchers.IO) {
            val resolvedUrl = resolvePluginUrl(url, useInternal = true)
            val request = HttpRequest.newBuilder()
                .uri(URI.create(resolvedUrl))
                .GET()
                .timeout(Duration.ofSeconds(30))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() != 200) {
                throw RuntimeException("Plugin returned status ${response.statusCode()}")
            }

            json.decodeFromString<PluginManifest>(response.body())
        }
    }

    /**
     * Stores language plugins in the database.
     */
    private fun storeLanguagePlugins(pluginId: UUID, languagePlugins: List<ManifestLanguagePlugin>, now: Instant) {
        for (plugin in languagePlugins) {
            LanguagePluginsTable.insert {
                it[id] = plugin.id
                it[LanguagePluginsTable.pluginId] = pluginId.toKotlinUuid()
                it[name] = plugin.name
                it[extension] = plugin.extension
                it[newFileAction] = plugin.newFileAction
                it[serverPluginImport] = plugin.serverPlugin.import
                it[graphicalEditorPluginImport] = plugin.graphicalEditorPlugin?.import
                it[graphicalEditorStylesUrl] = plugin.graphicalEditorPlugin?.stylesUrl
                it[graphicalEditorStylesCls] = plugin.graphicalEditorPlugin?.stylesCls
                it[textualEditorLanguageConfiguration] = plugin.textualEditorPlugin?.languageConfiguration?.toString()
                it[textualEditorMonarchTokensProvider] = plugin.textualEditorPlugin?.monarchTokensProvider?.toString()
                it[icon] = plugin.icon.toString()
                it[isGenerated] = plugin.isGenerated
                it[documentationUrl] = plugin.documentationUrl
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
    }

    /**
     * Stores contribution plugins in the database.
     * Contribution plugins provide additional functionality to existing languages.
     */
    private fun storeContributionPlugins(pluginId: UUID, contributionPlugins: List<JsonObject>, now: Instant) {
        for (plugin in contributionPlugins) {
            val languageId = plugin["languageId"]?.jsonPrimitive?.content ?: continue
            val description = plugin["description"]?.jsonPrimitive?.content ?: ""
            val additionalKeywords =
                plugin["additionalKeywords"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            val serverPlugins = plugin["serverContributionPlugins"]?.jsonArray?.map { it.jsonObject } ?: emptyList()

            ContributionPluginsTable.insert {
                it[id] = UUID.randomUUID().toKotlinUuid()
                it[ContributionPluginsTable.pluginId] = pluginId.toKotlinUuid()
                it[ContributionPluginsTable.languageId] = languageId
                it[ContributionPluginsTable.description] = description
                it[ContributionPluginsTable.additionalKeywords] = Json.encodeToString(additionalKeywords)
                it[ContributionPluginsTable.serverContributionPlugins] = Json.encodeToString(serverPlugins)
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
    }

    /**
     * Deletes a plugin and removes it from all projects.
     * Cascading deletes handle language plugins, contribution plugins, and project associations.
     * Invalidates file data for all projects using this plugin.
     *
     * @param pluginId The UUID of the plugin to delete
     * @return ApiResult indicating success or containing an error
     */
    fun deletePlugin(pluginId: UUID): ApiResult<Unit> {
        fileDataService.invalidatePluginData(pluginId)
        
        return transaction {
            val deleted = PluginsTable.deleteWhere { PluginsTable.id eq pluginId.toKotlinUuid() }

            if (deleted == 0) {
                return@transaction pluginFailure(ErrorCodes.PLUGIN_NOT_FOUND, "Plugin not found")
            }

            success(Unit)
        }
    }

    /**
     * Associates a plugin with a project.
     * Invalidates file data for the project after adding the plugin.
     *
     * @param projectId The UUID of the project
     * @param pluginId The UUID of the plugin
     * @return ApiResult indicating success or containing an error
     */
    fun addPluginToProject(projectId: UUID, pluginId: UUID): ApiResult<BackendPlugin> {
        val result = transaction {
            val pluginExists = PluginsTable.selectAll()
                .where { PluginsTable.id eq pluginId.toKotlinUuid() }
                .count() > 0

            if (!pluginExists) {
                return@transaction pluginFailure(ErrorCodes.PLUGIN_NOT_FOUND, "Plugin not found")
            }

            val alreadyAdded = ProjectPluginsTable.selectAll()
                .where {
                    (ProjectPluginsTable.projectId eq projectId.toKotlinUuid()) and
                            (ProjectPluginsTable.pluginId eq pluginId.toKotlinUuid())
                }
                .count() > 0

            if (alreadyAdded) {
                return@transaction pluginFailure(
                    ErrorCodes.PLUGIN_ALREADY_ADDED_TO_PROJECT,
                    "Plugin already added to project"
                )
            }

            ProjectPluginsTable.insert {
                it[ProjectPluginsTable.projectId] = projectId.toKotlinUuid()
                it[ProjectPluginsTable.pluginId] = pluginId.toKotlinUuid()
            }

            val plugin = when(val pluginResult = getPlugin(pluginId)) {
                is ApiResult.Success -> pluginResult.value
                is ApiResult.Failure -> return@transaction pluginResult
            }

            success(plugin)
        }
        
        if (result is ApiResult.Success) {
            fileDataService.invalidateProjectData(projectId)
        }
        
        return result
    }

    /**
     * Removes a plugin association from a project.
     * Invalidates file data for the project after removing the plugin.
     *
     * @param projectId The UUID of the project
     * @param pluginId The UUID of the plugin
     * @return ApiResult indicating success or containing an error
     */
    fun removePluginFromProject(projectId: UUID, pluginId: UUID): ApiResult<Unit> {
        val result = transaction {
            val deleted = ProjectPluginsTable.deleteWhere {
                (ProjectPluginsTable.projectId eq projectId.toKotlinUuid()) and
                        (ProjectPluginsTable.pluginId eq pluginId.toKotlinUuid())
            }

            if (deleted == 0) {
                return@transaction pluginFailure(
                    ErrorCodes.PLUGIN_NOT_ADDED_TO_PROJECT,
                    "Plugin not added to project"
                )
            }

            success(Unit)
        }
        
        if (result is ApiResult.Success) {
            fileDataService.invalidateProjectData(projectId)
        }
        
        return result
    }

    /**
     * Updates the default status of a plugin.
     *
     * @param pluginId The UUID of the plugin
     * @param default Whether the plugin should be added by default to new projects
     * @return ApiResult indicating success or containing an error
     */
    fun updatePluginDefault(pluginId: UUID, default: Boolean): ApiResult<Unit> {
        return transaction {
            val updated = PluginsTable.update({ PluginsTable.id eq pluginId.toKotlinUuid() }) {
                it[PluginsTable.default] = default
                it[updatedAt] = Instant.now()
            }

            if (updated == 0) {
                return@transaction pluginFailure(ErrorCodes.PLUGIN_NOT_FOUND, "Plugin not found")
            }

            success(Unit)
        }
    }

    /**
     * Retrieves all default plugins (plugins that should be added to new projects).
     *
     * @return List of default plugins
     */
    fun getDefaultPlugins(): List<UUID> {
        return transaction {
            PluginsTable.selectAll()
                .where { PluginsTable.default eq true }
                .map { it[PluginsTable.id].toJavaUuid() }
        }
    }

    /**
     * Initializes default plugins from a list of URLs.
     * Creates plugins if they don't exist yet (based on normalized URL).
     * Does not modify the default flag of existing plugins.
     *
     * @param urls List of plugin URLs to initialize as default
     */
    suspend fun initializeDefaultPlugins(urls: List<String>) {
        for (url in urls) {
            val normalizedUrl = url.trimEnd('/') + "/"
            
            val exists = transaction {
                PluginsTable.selectAll()
                    .where { PluginsTable.url eq normalizedUrl }
                    .count() > 0
            }

            if (!exists) {
                logger.info("Initializing default plugin: $normalizedUrl")
                
                val manifest = try {
                    fetchPluginManifest(normalizedUrl)
                } catch (e: Exception) {
                    logger.error("Failed to fetch plugin manifest from $normalizedUrl", e)
                    continue
                }

                transaction {
                    val pluginId = UUID.randomUUID()
                    val now = Instant.now()

                    PluginsTable.insert {
                        it[id] = pluginId.toKotlinUuid()
                        it[PluginsTable.url] = normalizedUrl
                        it[name] = manifest.name
                        it[description] = manifest.description
                        it[icon] = manifest.icon.toString()
                        it[default] = true
                        it[createdAt] = now
                        it[updatedAt] = now
                    }

                    storeLanguagePlugins(pluginId, manifest.languagePlugins, now)
                    storeContributionPlugins(pluginId, manifest.contributionPlugins, now)
                }
                
                logger.info("Successfully initialized default plugin: ${manifest.name}")
            } else {
                logger.info("Plugin already exists: $normalizedUrl")
            }
        }
    }

    /**
     * Finds the plugin responsible for a file based on its extension.
     * Only considers plugins that are associated with the project.
     *
     * @param projectId The UUID of the project
     * @param path The path to the file
     * @return The plugin and language plugin responsible for this file, or null if none found
     */
    fun findPluginForFile(projectId: UUID, path: String): Pair<UUID, BackendLanguagePlugin>? {
        val extension = path.substringAfterLast('.', "")
        if (extension.isEmpty()) return null

        val extensionWithDot = ".$extension"

        return transaction {
            val projectPluginIds = ProjectPluginsTable.selectAll()
                .where { ProjectPluginsTable.projectId eq projectId.toKotlinUuid() }
                .map { it[ProjectPluginsTable.pluginId] }

            if (projectPluginIds.isEmpty()) return@transaction null

            val result = LanguagePluginsTable.selectAll()
                .where {
                    (LanguagePluginsTable.pluginId inList projectPluginIds) and
                            (LanguagePluginsTable.extension eq extensionWithDot) and
                            (LanguagePluginsTable.extension.isNotNull())
                }
                .firstOrNull() ?: return@transaction null

            val pluginId = result[LanguagePluginsTable.pluginId].toJavaUuid()
            val pluginUrl = getPluginUrl(pluginId, useInternal = false) ?: return@transaction null
            val languagePlugin = rowToLanguagePlugin(result, pluginUrl)
            Pair(pluginId, languagePlugin)
        }
    }

    /**
     * Finds the plugin and language plugin responsible for a specific language ID in a project.
     *
     * @param projectId The UUID of the project
     * @param languageId The language ID to find the plugin for
     * @return Pair of plugin UUID and language plugin, or null if not found
     */
    fun findPluginByLanguage(projectId: UUID, languageId: String): Pair<UUID, BackendLanguagePlugin>? {
        return transaction {
            val projectPluginIds = ProjectPluginsTable.selectAll()
                .where { ProjectPluginsTable.projectId eq projectId.toKotlinUuid() }
                .map { it[ProjectPluginsTable.pluginId] }

            if (projectPluginIds.isEmpty()) return@transaction null

            val result = LanguagePluginsTable.selectAll()
                .where {
                    (LanguagePluginsTable.pluginId inList projectPluginIds) and
                            (LanguagePluginsTable.id eq languageId)
                }
                .firstOrNull() ?: return@transaction null

            val pluginId = result[LanguagePluginsTable.pluginId].toJavaUuid()
            val pluginUrl = getPluginUrl(pluginId, useInternal = false) ?: return@transaction null
            val languagePlugin = rowToLanguagePlugin(result, pluginUrl)
            Pair(pluginId, languagePlugin)
        }
    }

    /**
     * Gets the URL for a plugin.
     *
     * @param pluginId The UUID of the plugin
     * @param useInternal If true, returns internal URL for backend communication; if false, returns public URL for frontend
     * @return The resolved plugin URL, or null if not found
     */
    fun getPluginUrl(pluginId: UUID, useInternal: Boolean = false): String? {
        return transaction {
            PluginsTable.selectAll()
                .where { PluginsTable.id eq pluginId.toKotlinUuid() }
                .firstOrNull()
                ?.get(PluginsTable.url)
                ?.let { resolvePluginUrl(it, useInternal) }
        }
    }

    /**
     * Gets all server contribution plugins for a specific language across all plugins in a project.
     * 
     * @param projectId The UUID of the project
     * @param languageId The language ID to get contribution plugins for
     * @return List of server contribution plugins as arbitrary JSON objects
     */
    fun getContributionPluginsForLanguage(projectId: UUID, languageId: String): List<JsonObject> {
        return transaction {
            val projectPluginIds = ProjectPluginsTable.selectAll()
                .where { ProjectPluginsTable.projectId eq projectId.toKotlinUuid() }
                .map { it[ProjectPluginsTable.pluginId] }

            if (projectPluginIds.isEmpty()) return@transaction emptyList()

            ContributionPluginsTable.selectAll()
                .where {
                    (ContributionPluginsTable.pluginId inList projectPluginIds) and
                            (ContributionPluginsTable.languageId eq languageId)
                }
                .flatMap { row ->
                    json.decodeFromString<List<JsonObject>>(row[ContributionPluginsTable.serverContributionPlugins])
                }
        }
    }

    /**
     * Gets all project IDs that use a specific plugin.
     *
     * @param pluginId The UUID of the plugin
     * @return List of project IDs using this plugin
     */
    fun getProjectsUsingPlugin(pluginId: UUID): List<UUID> {
        return transaction {
            ProjectPluginsTable.selectAll()
                .where { ProjectPluginsTable.pluginId eq pluginId.toKotlinUuid() }
                .map { it[ProjectPluginsTable.projectId].toJavaUuid() }
        }
    }

    /**
     * Creates a BackendPlugin object with all its language and contribution plugins.
     *
     * @param pluginId The UUID of the plugin
     * @param url The plugin URL
     * @param name The plugin name
     * @param description The plugin description
     * @param icon The plugin icon as JsonArray
     * @param default Whether this plugin is added by default to new projects
     * @return The constructed BackendPlugin
     */
    private fun createBackendPlugin(
        pluginId: UUID,
        url: String,
        name: String,
        description: String,
        icon: JsonArray,
        default: Boolean
    ): BackendPlugin {
        val languagePlugins = transaction {
            LanguagePluginsTable.selectAll()
                .where { LanguagePluginsTable.pluginId eq pluginId.toKotlinUuid() }
                .map { rowToLanguagePlugin(it, url) }
        }

        val contributionPlugins = transaction {
            ContributionPluginsTable.selectAll()
                .where { ContributionPluginsTable.pluginId eq pluginId.toKotlinUuid() }
                .map { rowToContributionPlugin(it) }
        }

        return BackendPlugin(
            id = pluginId.toKotlinUuid().toString(),
            url = url,
            name = name,
            description = description,
            icon = icon,
            default = default,
            languagePlugins = languagePlugins,
            contributionPlugins = contributionPlugins
        )
    }

    /**
     * Converts a database row to a BackendContributionPlugin object.
     *
     * @param row The database result row
     * @return The constructed BackendContributionPlugin
     */
    private fun rowToContributionPlugin(row: ResultRow): BackendContributionPlugin {
        return BackendContributionPlugin(
            id = row[ContributionPluginsTable.id].toJavaUuid().toString(),
            languageId = row[ContributionPluginsTable.languageId],
            description = row[ContributionPluginsTable.description],
            additionalKeywords = json.decodeFromString<List<String>>(row[ContributionPluginsTable.additionalKeywords]),
            serverContributionPlugins = json.decodeFromString<List<JsonObject>>(row[ContributionPluginsTable.serverContributionPlugins])
        )
    }

    /**
     * Converts a database row to a BackendLanguagePlugin object.
     * Resolves relative paths in serverPlugin and editorPlugin against the plugin URL.
     *
     * @param row The database result row
     * @param pluginUrl The base URL of the plugin for resolving relative paths
     * @return The constructed BackendLanguagePlugin
     */
    private fun rowToLanguagePlugin(row: ResultRow, pluginUrl: String): BackendLanguagePlugin {
        return BackendLanguagePlugin(
            id = row[LanguagePluginsTable.id],
            name = row[LanguagePluginsTable.name],
            extension = row[LanguagePluginsTable.extension],
            newFileAction = row[LanguagePluginsTable.newFileAction],
            serverPlugin = LanguageServerPlugin(
                import = resolveUrl(row[LanguagePluginsTable.serverPluginImport], pluginUrl)
            ),
            graphicalEditorPlugin = row[LanguagePluginsTable.graphicalEditorPluginImport]?.let { importPath ->
                LanguageGraphicalEditorPlugin(
                    import = resolveUrl(importPath, pluginUrl),
                    stylesUrl = resolveUrl(row[LanguagePluginsTable.graphicalEditorStylesUrl] ?: "", pluginUrl),
                    stylesCls = row[LanguagePluginsTable.graphicalEditorStylesCls] ?: ""
                )
            },
            textualEditorPlugin = row[LanguagePluginsTable.textualEditorLanguageConfiguration]?.let { langConfig ->
                LanguageTextualEditorPlugin(
                    languageConfiguration = json.parseToJsonElement(langConfig).jsonObject,
                    monarchTokensProvider = json.parseToJsonElement(row[LanguagePluginsTable.textualEditorMonarchTokensProvider] ?: "{}").jsonObject
                )
            },
            icon = json.parseToJsonElement(row[LanguagePluginsTable.icon]).jsonArray,
            isGenerated = row[LanguagePluginsTable.isGenerated],
            documentationUrl = row[LanguagePluginsTable.documentationUrl]
        )
    }

    /**
     * Resolves a potentially relative URL against a base URL.
     * If the path starts with "./" or is relative (not starting with http:// or https://),
     * it is resolved against the base URL.
     *
     * @param path The path to resolve (may be relative or absolute)
     * @param baseUrl The base URL to resolve relative paths against
     * @return The resolved absolute URL
     */
    private fun resolveUrl(path: String, baseUrl: String): String {
        if (path.isEmpty()) {
            return path
        }

        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path
        }

        return URI.create(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/").resolve(path).toString()
    }

    /**
     * Resolves a plugin URL against the configured base URL.
     * 
     * @param pluginUrl The stored plugin URL (may be relative or absolute)
     * @param useInternal If true, uses internal base URL for backend communication; if false, uses public base URL for frontend
     * @return The resolved absolute plugin URL
     */
    private fun resolvePluginUrl(pluginUrl: String, useInternal: Boolean = false): String {
        val baseUrl = if (useInternal) internalPluginBaseUrl else pluginBaseUrl
        return resolveUrl(pluginUrl, baseUrl)
    }
}
