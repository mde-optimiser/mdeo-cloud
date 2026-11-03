package com.mdeo.backend.plugins

import com.mdeo.common.transport.failureStatusFor
import com.mdeo.common.transport.respondError
import com.mdeo.common.model.ApiResult
import io.ktor.server.application.*
import io.ktor.server.response.*

/**
 * Extension function to respond with an ApiResult.
 * On success, responds with the value directly (with 200 OK).
 * On failure, responds with the error, with 400 Bad Request unless [failureStatusFor] says otherwise.
 *
 * @param result The ApiResult to respond with
 */
suspend inline fun <reified T> ApplicationCall.respondApiResult(result: ApiResult<T>) {
    when (result) {
        is ApiResult.Success -> respondNullable(result.value)
        is ApiResult.Failure -> respondError(failureStatusFor(result.error.code), result.error)
    }
}

/**
 * Extension function to respond with an ApiResult.
 * On success, responds with the value directly (with 200 OK).
 * On failure, responds with the error, with 400 Bad Request unless [failureStatusFor] says otherwise.
 *
 * @param result The ApiResult to respond with
 */
@JvmName("respondApiResultNonNullable")
suspend inline fun <reified T : Any> ApplicationCall.respondApiResult(result: ApiResult<T>) {
    when (result) {
        is ApiResult.Success -> respond(result.value)
        is ApiResult.Failure -> respondError(failureStatusFor(result.error.code), result.error)
    }
}