package com.mdeo.execution.common.routes

import com.mdeo.common.transport.respondError
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import java.util.*

/**
 * Standardized error responses for execution routes.
 * Provides consistent error formatting across execution services.
 */
object ErrorResponses {
    /**
     * Responds with an unauthorized error.
     */
    suspend fun ApplicationCall.respondUnauthorized(message: String = "Authentication required") {
        respondError(HttpStatusCode.Unauthorized, message)
    }

    /**
     * Responds with a forbidden error.
     */
    suspend fun ApplicationCall.respondForbidden(message: String = "Insufficient permissions") {
        respondError(HttpStatusCode.Forbidden, message)
    }

    /**
     * Responds with a bad request error.
     */
    suspend fun ApplicationCall.respondBadRequest(message: String) {
        respondError(HttpStatusCode.BadRequest, message)
    }

    /**
     * Responds with a not found error.
     */
    suspend fun ApplicationCall.respondNotFound(message: String = "Resource not found") {
        respondError(HttpStatusCode.NotFound, message)
    }

    /**
     * Responds with an internal server error.
     */
    suspend fun ApplicationCall.respondInternalError(message: String = "Internal server error") {
        respondError(HttpStatusCode.InternalServerError, message)
    }
}

/**
 * Utility functions for route parameter parsing.
 */
object RouteUtils {
    /**
     * Parses a string parameter as UUID.
     *
     * @param value The string value to parse
     * @return The UUID if valid, null otherwise
     */
    fun parseUuid(value: String?): UUID? {
        return value?.let {
            try {
                UUID.fromString(it)
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Gets and parses a path parameter as UUID.
     *
     * @param paramName The name of the path parameter
     * @return The UUID if valid, null otherwise
     */
    fun io.ktor.server.routing.RoutingContext.getUuidParam(paramName: String): UUID? {
        return parseUuid(call.parameters[paramName])
    }
}
