package com.mdeo.common.transport

import com.mdeo.common.model.ApiError
import com.mdeo.common.model.ErrorCodes
import com.mdeo.common.model.ErrorResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Encoded without content negotiation, so services that do not install it answer errors alike.
 */
private val errorJson = Json

/**
 * The error code that describes a status when nothing more specific is known.
 *
 * @param status The HTTP status of the error response
 * @return One of the general [ErrorCodes]
 */
fun errorCodeFor(status: HttpStatusCode): String = when (status) {
    HttpStatusCode.BadRequest -> ErrorCodes.BAD_REQUEST
    HttpStatusCode.Unauthorized -> ErrorCodes.UNAUTHENTICATED
    HttpStatusCode.Forbidden -> ErrorCodes.FORBIDDEN
    HttpStatusCode.NotFound -> ErrorCodes.NOT_FOUND
    HttpStatusCode.Conflict -> ErrorCodes.CONFLICT
    HttpStatusCode.ServiceUnavailable, HttpStatusCode.BadGateway -> ErrorCodes.UNAVAILABLE
    HttpStatusCode.GatewayTimeout -> ErrorCodes.DEADLINE_EXCEEDED
    else -> if (status.value >= 500) ErrorCodes.INTERNAL else ErrorCodes.BAD_REQUEST
}

/**
 * Answers the call with an error in the platform's error shape, `{"error": {"code", "message"}}`.
 *
 * @param status The HTTP status
 * @param message Human-readable description
 * @param code One of [ErrorCodes]; derived from [status] when omitted
 */
suspend fun ApplicationCall.respondError(
    status: HttpStatusCode,
    message: String,
    code: String = errorCodeFor(status)
) {
    respondError(status, ApiError(code, message))
}

/**
 * Answers the call with [error] in the platform's error shape.
 *
 * @param status The HTTP status
 * @param error The error to report
 */
suspend fun ApplicationCall.respondError(status: HttpStatusCode, error: ApiError) {
    respondText(
        errorJson.encodeToString(ErrorResponse.serializer(), ErrorResponse(error)),
        ContentType.Application.Json,
        status
    )
}

/**
 * What another service's error response says, fit to pass on: the message of an answer in the
 * platform's error shape, or just its status. The raw body is never included, so nothing a service
 * did not mean to report — a stack trace, an internal address — travels further.
 *
 * @param status The HTTP status of the response
 * @param body The response body
 * @return A description of the failure
 */
fun describeErrorResponse(status: Int, body: String): String {
    val message = runCatching {
        val error = errorJson.parseToJsonElement(body).jsonObject["error"] as? JsonObject
        (error?.get("message") as? JsonPrimitive)?.takeIf { it.isString }?.content
    }.getOrNull()
    return if (message.isNullOrBlank()) "status $status" else "status $status: $message"
}
