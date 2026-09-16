package com.mdeo.backend.config

import com.mdeo.common.transport.respondError
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("StatusPages")

/**
 * Configures global exception handling for the application.
 *
 * Catches all unhandled exceptions, logs them, and returns a standardized error response.
 */
fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            logger.error("Unhandled exception", cause)
            call.respondError(HttpStatusCode.InternalServerError, "Internal server error")
        }
    }
}
