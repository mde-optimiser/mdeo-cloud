package com.mdeo.script.external

/**
 * The byte pipe a [ScriptFunctionsClient] talks over.
 *
 * In an execution this is a platform session; in a test it is a service running in the same
 * process. The client neither knows nor cares which: it writes one encoded message, and reads
 * encoded messages until the answer to its call arrives.
 */
interface ScriptFunctionsTransport {

    /**
     * Writes one message.
     *
     * @param message The encoded message
     */
    fun send(message: ByteArray)

    /**
     * Blocks until the next message from the service arrives.
     *
     * @return The encoded message
     */
    fun receive(): ByteArray

    /**
     * Identifies the connection messages currently go out on. It changes when the transport
     * reconnected, which tells the client that the service lost everything it held.
     */
    val connection: Long get() = 0

    /**
     * How many times the transport redials a dropped connection before it gives up. A call is sent
     * again at most this often because the connection changed under it, so a connection that keeps
     * coming back and dropping cannot hold a call forever. Zero for a transport that never
     * reconnects.
     */
    val maxReconnects: Int get() = 0
}
