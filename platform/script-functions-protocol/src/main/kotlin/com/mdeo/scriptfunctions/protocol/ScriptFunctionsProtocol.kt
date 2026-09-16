package com.mdeo.scriptfunctions.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

/**
 * The `script-functions` session protocol, version 1.
 *
 * This protocol is owned by the script language, not by the platform: the platform only carries
 * its bytes over a session. The execution side speaks it through `ScriptFunctionsClient` in
 * `:script`; plugin services answer it, usually through `ScriptFunctionService` in
 * `:script-plugin-service`. The wire format is specified language-neutrally in the docs
 * (`develop/script-functions-protocol`).
 *
 * Every message is one CBOR item. Polymorphic values are encoded the way kotlinx-serialization
 * encodes sealed classes in CBOR: a two-element array of the serial name and the object.
 *
 * ## Semantics
 *
 * A call is copy-restore. The client sends the arguments together with every collection they
 * reach, each under an id it keeps for the whole session, so aliasing and cycles survive the trip.
 * The service may change the collections it was given as *inout* — mutable collection types —
 * and answers with per-object deltas for only those that changed. Collections given as *in* —
 * readonly types, anything reached through `Any` — must come back untouched; a delta against one
 * rejects the whole result before anything is applied.
 */
object ScriptFunctionsProtocol {
    /**
     * Protocol name, as declared on a contribution's session type.
     */
    const val NAME = "script-functions"

    /**
     * The protocol version this implementation speaks.
     */
    const val VERSION = 1

    /**
     * The CBOR format every message is encoded with.
     */
    @OptIn(ExperimentalSerializationApi::class)
    val cbor: Cbor = Cbor {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Encodes a message sent by the execution.
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun encodeClient(message: ClientMessage): ByteArray = cbor.encodeToByteArray(message)

    /**
     * Decodes a message sent by the execution.
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun decodeClient(bytes: ByteArray): ClientMessage = cbor.decodeFromByteArray(bytes)

    /**
     * Encodes a message sent by the service.
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun encodeService(message: ServiceMessage): ByteArray = cbor.encodeToByteArray(message)

    /**
     * Decodes a message sent by the service.
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun decodeService(bytes: ByteArray): ServiceMessage = cbor.decodeFromByteArray(bytes)
}

/**
 * A message from the execution to the service.
 */
@Serializable
sealed class ClientMessage {

    /**
     * Calls one operation.
     *
     * @param callId Correlates the answer with this call; unique within the session.
     * @param operation The operation name, as declared on the external implementation.
     * @param objects Collections the arguments reach. A collection the service already holds at
     *        its current version is sent without content.
     * @param args The arguments, in declaration order.
     */
    @Serializable
    @SerialName("call")
    data class Call(
        val callId: Long,
        val operation: String,
        val objects: List<HeapObject>,
        val args: List<WireValue>
    ) : ClientMessage()

    /**
     * Tells the service it may forget collections the execution no longer holds.
     *
     * @param ids The ids to forget.
     */
    @Serializable
    @SerialName("release")
    data class Release(val ids: List<Long>) : ClientMessage()
}

/**
 * A message from the service to the execution.
 */
@Serializable
sealed class ServiceMessage {

    /**
     * The successful answer to a call.
     *
     * @param callId The call being answered.
     * @param objects Collections the service created, under negative ids of its own choosing.
     * @param deltas Changes to inout collections, in the order they must be applied.
     * @param value The return value; [WireValue.Null] for a void operation.
     */
    @Serializable
    @SerialName("result")
    data class Result(
        val callId: Long,
        val objects: List<HeapObject> = emptyList(),
        val deltas: List<Delta> = emptyList(),
        val value: WireValue = WireValue.Null
    ) : ServiceMessage()

    /**
     * A call that failed on the service. Nothing it may have changed is applied.
     *
     * @param callId The call being answered.
     * @param message What went wrong, as the operation reported it.
     * @param code [UNKNOWN_OBJECT] when the call referred to a collection by id alone that the
     *        service does not hold, which the execution answers by sending the call again in full;
     *        null for a failure of the operation itself.
     */
    @Serializable
    @SerialName("failure")
    data class Failure(val callId: Long, val message: String, val code: String? = null) : ServiceMessage() {
        companion object {
            /**
             * The service was sent a collection without content that it does not hold.
             */
            const val UNKNOWN_OBJECT = "unknown-object"
        }
    }
}

/**
 * The kinds of collection the protocol carries.
 */
@Serializable
enum class HeapKind {
    @SerialName("list") LIST,
    @SerialName("set") SET,
    @SerialName("orderedSet") ORDERED_SET,
    @SerialName("bag") BAG,
    @SerialName("map") MAP
}

/**
 * One collection on the heap.
 *
 * @param id The collection's id for the whole session. Ids the execution assigns are positive;
 *        ids the service assigns to collections it creates are negative.
 * @param kind What kind of collection it is.
 * @param version The collection's mutation counter when it was sent.
 * @param mutable Whether the service may change it (inout) or must leave it alone (in).
 * @param elements The elements, for every kind but a map; null when the content is omitted
 *        because the service already holds this id at this version.
 * @param entries The entries of a map, as alternating keys and values; null when omitted.
 */
@Serializable
data class HeapObject(
    val id: Long,
    val kind: HeapKind,
    val version: Long = 0,
    val mutable: Boolean = false,
    val elements: List<WireValue>? = null,
    val entries: List<WireValue>? = null
)

/**
 * One value on the wire.
 */
@Serializable
sealed class WireValue {
    @Serializable @SerialName("null") data object Null : WireValue()
    @Serializable @SerialName("bool") data class Bool(val value: Boolean) : WireValue()
    @Serializable @SerialName("int") data class IntValue(val value: Int) : WireValue()
    @Serializable @SerialName("long") data class LongValue(val value: Long) : WireValue()
    @Serializable @SerialName("float") data class FloatValue(val value: Float) : WireValue()
    @Serializable @SerialName("double") data class DoubleValue(val value: Double) : WireValue()
    @Serializable @SerialName("string") data class StringValue(val value: String) : WireValue()

    /**
     * A reference to a collection on the heap, by id.
     */
    @Serializable @SerialName("ref") data class Ref(val id: Long) : WireValue()
}

/**
 * One change to one inout collection.
 */
@Serializable
sealed class Delta {
    /**
     * The collection the change applies to.
     */
    abstract val id: Long

    /**
     * Replaces [deleteCount] elements at [index] with [insert]. Lists and ordered sets.
     */
    @Serializable @SerialName("splice")
    data class Splice(override val id: Long, val index: Int, val deleteCount: Int, val insert: List<WireValue>) : Delta()

    /**
     * Adds each value. Sets, ordered sets (appended) and bags (one occurrence each).
     */
    @Serializable @SerialName("add")
    data class Add(override val id: Long, val values: List<WireValue>) : Delta()

    /**
     * Removes each value. Sets, ordered sets and bags (one occurrence each).
     */
    @Serializable @SerialName("remove")
    data class Remove(override val id: Long, val values: List<WireValue>) : Delta()

    /**
     * Sets how often a value occurs in a bag.
     */
    @Serializable @SerialName("count")
    data class Count(override val id: Long, val value: WireValue, val count: Int) : Delta()

    /**
     * Associates a value with a key in a map.
     */
    @Serializable @SerialName("put")
    data class Put(override val id: Long, val key: WireValue, val value: WireValue) : Delta()

    /**
     * Removes a key from a map.
     */
    @Serializable @SerialName("removeKey")
    data class RemoveKey(override val id: Long, val key: WireValue) : Delta()

    /**
     * Replaces the whole content of any collection. For a map, [elements] alternates keys and values.
     */
    @Serializable @SerialName("replace")
    data class Replace(override val id: Long, val elements: List<WireValue>) : Delta()
}
