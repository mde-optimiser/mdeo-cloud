package com.mdeo.backend.database

import com.mdeo.backend.config.DatabaseConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.jdbc.Database
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
                FileMetadataTable,
                PluginsTable,
                ProjectPluginsTable,
                LanguagePluginsTable,
                ContributionPluginsTable,
                ContributionTargetsTable,
                PluginSessionsTable,
                FileDataTable,
                FileDataComputationsTable,
                FileDependenciesTable,
                DataDependenciesTable,
                ExecutionsTable,
                ExecutionFileMetadataTable
            )
        }
        
        logger.info("Database initialized successfully")
    }
    
    /**
     * Closes the database connection pool.
     */
    fun close() {
        dataSource?.close()
        logger.info("Database connection closed")
    }
}
