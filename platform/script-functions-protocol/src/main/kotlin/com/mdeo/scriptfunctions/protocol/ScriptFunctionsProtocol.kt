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
     * @param modelId The model the call works on, as uploaded with [ModelPut]; null for a call
     *        that needs no model.
     */
    @Serializable
    @SerialName("call")
    data class Call(
        val callId: Long,
        val operation: String,
        val objects: List<HeapObject>,
        val args: List<WireValue>,
        val modelId: Long? = null
    ) : ClientMessage()

    /**
     * Uploads the model the following calls work on, readonly.
     *
     * A service holds one model per session. Uploading one replaces the previous model and ends
     * everything that belonged to it: the service forgets every collection it holds, and whatever
     * it cached about the old model. The execution uploads a model once and refers to it by
     * [modelId] until the model it works on changes.
     *
     * @param modelId Identifies the model within the session.
     * @param model The whole model.
     */
    @Serializable
    @SerialName("model")
    data class ModelPut(val modelId: Long, val model: WireModel) : ClientMessage()

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
     *        service does not hold, or [UNKNOWN_MODEL] when it named a model the service does not
     *        hold; the execution answers either by sending everything again. Null for a failure of
     *        the operation itself.
     */
    @Serializable
    @SerialName("failure")
    data class Failure(val callId: Long, val message: String, val code: String? = null) : ServiceMessage() {
        companion object {
            /**
             * The service was sent a collection without content that it does not hold.
             */
            const val UNKNOWN_OBJECT = "unknown-object"

            /**
             * The call names a model the service does not hold, which the execution answers by
             * uploading the model and sending the call again.
             */
            const val UNKNOWN_MODEL = "unknown-model"
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

    /**
     * An instance of the call's model, by its name. Instances are readonly: nothing can change
     * one, and no delta can address one.
     */
    @Serializable @SerialName("instance") data class InstanceValue(val name: String) : WireValue()
}

/**
 * A whole model, as uploaded with [ClientMessage.ModelPut].
 *
 * @param metamodelPath The metamodel the model is an instance of.
 * @param subtypes For every class, the classes that are it or inherit from it, so a service can
 *        find all instances of a class including those of its subclasses.
 * @param instances Every instance of the model.
 */
@Serializable
data class WireModel(
    val metamodelPath: String,
    val subtypes: Map<String, List<String>> = emptyMap(),
    val instances: List<WireInstance> = emptyList()
)

/**
 * One instance of a [WireModel].
 *
 * @param name The instance's name, unique within the model.
 * @param className The instance's class.
 * @param attributes Attribute values by attribute name. A single-valued attribute has at most one
 *        value; an unset one has none. Enum values are sent as the entry's name.
 * @param references The names of the referenced instances, by association end.
 */
@Serializable
data class WireInstance(
    val name: String,
    val className: String,
    val attributes: Map<String, List<WireValue>> = emptyMap(),
    val references: Map<String, List<String>> = emptyMap()
)

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
