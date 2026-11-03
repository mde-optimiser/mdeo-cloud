package com.mdeo.common.transport

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.utils.io.*
import kotlinx.io.readByteArray

/**
 * A request body larger than the endpoint accepts.
 *
 * @param limit The most bytes the endpoint accepts
 */
class RequestBodyTooLargeException(val limit: Long) : RuntimeException("Request body is larger than $limit bytes")

/**
 * Reads the request body as UTF-8 text, refusing more than [limit] bytes.
 *
 * Compressed bodies are bounded when they are inflated, see [installHttpCompression]; this bounds a
 * plain one, which would otherwise be buffered whole, whatever its size.
 *
 * @param limit The most bytes accepted
 * @return The body
 * @throws RequestBodyTooLargeException if the body is larger
 */
suspend fun ApplicationCall.receiveBoundedText(limit: Long = MAX_DECODED_REQUEST_BYTES): String {
    request.contentLength()?.let { if (it > limit) throw RequestBodyTooLargeException(limit) }
    val bytes = receiveChannel().readBuffer(limit + 1).readByteArray()
    if (bytes.size > limit) throw RequestBodyTooLargeException(limit)
    return bytes.decodeToString()
}
