package com.mdeo.backend.database

import com.mdeo.backend.config.DatabaseConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory

/**
 * Factory object responsible for database connection management and schema initialization.
 */
object DatabaseFactory {
    private val logger = LoggerFactory.getLogger(DatabaseFactory::class.java)
    private var dataSource: HikariDataSource? = null
    
    /**
     * Initializes the database connection pool and creates tables.
     *
     * @param config Database configuration containing connection parameters
     */
    fun init(config: DatabaseConfig) {
        logger.info("Initializing database connection to ${config.url}")
        
        val hikariConfig = HikariConfig().apply {
            jdbcUrl = config.url
            username = config.user
            password = config.password
            maximumPoolSize = config.maxPoolSize
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
            validate()
        }
        
        dataSource = HikariDataSource(hikariConfig)
        Database.connect(dataSource!!)
        
        transaction {
            SchemaUtils.create(
                UsersTable,
                ProjectsTable,
                ProjectOwnersTable,
                FilesTable,
                FileVersionCountersTable,
                FileMetadataTable,
                PluginsTable,
                ProjectPluginsTable,
                LanguagePluginsTable,
                ContributionPluginsTable,
                FileDataTable,
                FileDataComputationsTable,
                FileDependenciesTable,
                DataDependenciesTable,
                ExecutionsTable,
                ExecutionFileMetadataTable,
                GitPacksTable,
                GitPackFilesTable,
                GitRefsTable,
                PersonalAccessTokensTable,
                SshPublicKeysTable,
                PersonalAccessTokenProjectsTable
            )
            backfillFileVersionCounters()
        }

        logger.info("Database initialized successfully")
    }

    /**
     * Seeds [FileVersionCountersTable] with every existing file's current version, for any path
     * that does not already have a counter row.
     *
     * [FileVersionCountersTable] was introduced after [FilesTable] already had live data in it.
     * Left unseeded, a pre-existing file's *first* edit after this table was introduced calls
     * `nextVersion` for a path with no counter row yet, which starts counting from 1 regardless
     * of the version the file was actually already at - silently rolling it backwards. A client
     * or cache that recorded a dependency on the file at its real, higher version would then see
     * a *lower* version number after a completely ordinary edit, defeating the counter's whole
     * purpose of never reusing a version number for a given path.
     *
     * `ON CONFLICT DO NOTHING` makes this safe to run on every startup, not just the first one
     * after upgrading: a path that already has a counter row (because it was seeded here before,
     * or has been edited since) is left untouched, so this never overwrites a counter that has
     * already moved past a file's stored version.
     */
    private fun JdbcTransaction.backfillFileVersionCounters() {
        exec(
            """
            INSERT INTO file_version_counters (project_id, path, next_version)
            SELECT project_id, path, version FROM files
            ON CONFLICT (project_id, path) DO NOTHING
            """.trimIndent()
        )
    }
    
    /**
     * Closes the database connection pool.
     */
    fun close() {
        dataSource?.close()
        logger.info("Database connection closed")
    }
}
