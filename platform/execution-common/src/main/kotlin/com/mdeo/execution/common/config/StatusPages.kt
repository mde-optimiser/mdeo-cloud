package com.mdeo.execution.common.config

import com.mdeo.common.transport.respondError
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("StatusPages")

/**
 * Configures status pages for standardized error handling across execution services.
 * Installs exception handlers that return consistent JSON error responses.
 */
fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            logger.error("Unhandled exception", cause)
            call.respondError(HttpStatusCode.InternalServerError, cause.message ?: "Internal server error")
        }
    }
}
