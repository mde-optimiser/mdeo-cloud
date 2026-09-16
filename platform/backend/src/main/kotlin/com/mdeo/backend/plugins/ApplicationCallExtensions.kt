package com.mdeo.backend.plugins

import com.mdeo.common.transport.respondError
import com.mdeo.common.model.ApiResult
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.response.*

/**
 * Extension function to respond with an ApiResult.
 * On success, responds with the value directly (with 200 OK).
 * On failure, responds with an object containing an error key (with 400 Bad Request).
 *
 * @param result The ApiResult to respond with
 */
suspend inline fun <reified T> ApplicationCall.respondApiResult(result: ApiResult<T>) {
    when (result) {
        is ApiResult.Success -> respondNullable(result.value)
        is ApiResult.Failure -> respondError(HttpStatusCode.BadRequest, result.error)
    }
}

/**
 * Extension function to respond with an ApiResult.
 * On success, responds with the value directly (with 200 OK).
 * On failure, responds with an object containing an error key (with 400 Bad Request).
 *
 * @param result The ApiResult to respond with
 */
@JvmName("respondApiResultNonNullable")
suspend inline fun <reified T : Any> ApplicationCall.respondApiResult(result: ApiResult<T>) {
    when (result) {
        is ApiResult.Success -> respond(result.value)
        is ApiResult.Failure -> respondError(HttpStatusCode.BadRequest, result.error)
    }
}