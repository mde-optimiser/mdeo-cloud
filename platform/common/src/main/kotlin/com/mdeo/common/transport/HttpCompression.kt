package com.mdeo.common.transport

import io.ktor.client.*
import io.ktor.client.plugins.compression.*
import io.ktor.server.application.*
import io.ktor.server.plugins.compression.*
import io.ktor.websocket.*
import java.io.ByteArrayInputStream
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import java.nio.charset.Charset

/**
 * Bodies smaller than this are sent as they are: compressing them saves less than it costs.
 */
const val COMPRESSION_MIN_BYTES: Long = 1024

/**
 * Compresses HTTP responses for every client that accepts it.
 *
 * The platform's payloads are JSON — ASTs, typed ASTs, contribution payloads, model data — which
 * shrink by an order of magnitude or more, so every service compresses what it answers with.
 */
fun Application.installHttpCompression() {
    if (pluginOrNull(Compression) != null) return
    install(Compression) {
        gzip {
            minimumSize(COMPRESSION_MIN_BYTES)
        }
        deflate {
            minimumSize(COMPRESSION_MIN_BYTES)
            priority = 0.9
        }
    }
}

/**
 * Lets a Ktor client accept compressed responses, and inflates them transparently.
 */
fun HttpClientConfig<*>.acceptCompressedResponses() {
    install(ContentEncoding) {
        gzip()
        deflate(0.9f)
    }
}

/**
 * Negotiates permessage-deflate on a WebSocket, for the side this is installed on. The other side
 * decides whether it is used; a peer that does not offer or accept it gets uncompressed messages.
 */
fun WebSocketExtensionsConfig.installDeflate() {
    install(WebSocketDeflateExtension) {
        compressIfBiggerThan(COMPRESSION_MIN_BYTES.toInt())
    }
}

/**
 * Compressed responses for `java.net.http`, which neither asks for nor inflates them on its own.
 */
object CompressedResponses {
    /**
     * Asks the server for a compressed response.
     *
     * @param builder The request being built
     * @return The same builder
     */
    fun accept(builder: HttpRequest.Builder): HttpRequest.Builder =
        builder.header("Accept-Encoding", "gzip, deflate")

    /**
     * Reads a response body as bytes, inflating it if the server compressed it.
     */
    fun ofByteArray(): HttpResponse.BodyHandler<ByteArray> = HttpResponse.BodyHandler { info ->
        HttpResponse.BodySubscribers.mapping(HttpResponse.BodySubscribers.ofByteArray()) { bytes ->
            inflate(bytes, info.headers())
        }
    }

    /**
     * Reads a response body as text, inflating it if the server compressed it. The charset is taken
     * from the `Content-Type`, UTF-8 when it names none.
     */
    fun ofString(): HttpResponse.BodyHandler<String> = HttpResponse.BodyHandler { info ->
        HttpResponse.BodySubscribers.mapping(HttpResponse.BodySubscribers.ofByteArray()) { bytes ->
            String(inflate(bytes, info.headers()), charsetOf(info.headers()))
        }
    }

    private fun inflate(bytes: ByteArray, headers: HttpHeaders): ByteArray {
        val encoding = headers.firstValue("Content-Encoding").orElse("").trim().lowercase()
        return when (encoding) {
            "gzip", "x-gzip" -> GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
            "deflate" -> InflaterInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
            "", "identity" -> bytes
            else -> throw IllegalStateException("Unsupported response encoding '$encoding'")
        }
    }

    private fun charsetOf(headers: HttpHeaders): Charset {
        val contentType = headers.firstValue("Content-Type").orElse("")
        val declared = contentType.split(';').map { it.trim() }
            .firstOrNull { it.startsWith("charset=", ignoreCase = true) }
            ?.substringAfter('=')?.trim('"')
        return declared?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
    }
}
