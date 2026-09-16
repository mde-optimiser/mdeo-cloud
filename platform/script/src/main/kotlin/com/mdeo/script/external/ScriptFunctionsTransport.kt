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
}
