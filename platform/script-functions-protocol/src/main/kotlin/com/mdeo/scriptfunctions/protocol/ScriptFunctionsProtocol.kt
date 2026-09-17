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
     * Describes a metamodel that models uploaded afterwards are instances of.
     *
     * The execution sends each metamodel once per session, before the first model that needs it,
     * and again only when its content changes. A service keeps every metamodel it was sent, by
     * path, for the whole session. Sending a metamodel under a path the held model is an instance
     * of drops that model, as a new model would.
     *
     * @param metamodel The whole metamodel.
     */
    @Serializable
    @SerialName("metamodel")
    data class MetamodelPut(val metamodel: WireMetamodel) : ClientMessage()

    /**
     * Uploads the model the following calls work on, readonly.
     *
     * A service holds one model per session. Uploading one replaces the previous model and ends
     * everything that belonged to it: the service forgets every collection it holds, and whatever
     * it cached about the old model. The execution uploads a model once and refers to it by
     * [modelId] until the model it works on changes. Its metamodel must have been sent with
     * [MetamodelPut] before; a service that does not hold it answers the next call naming the
     * model with [ServiceMessage.Failure.UNKNOWN_MODEL].
     *
     * @param modelId Identifies the model within the session.
     * @param model The whole model.
     */
    @Serializable
    @SerialName("model")
    data class ModelPut(val modelId: Long, val model: WireModel) : ClientMessage()

    /**
     * Tells the service it may forget collections and handles the execution no longer holds.
     *
     * @param ids The collection ids to forget.
     * @param handles The handle ids to forget.
     */
    @Serializable
    @SerialName("release")
    data class Release(val ids: List<Long>, val handles: List<Long> = emptyList()) : ClientMessage()
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

    /**
     * A record the contribution defines, sent whole. Records are immutable and compared by content.
     *
     * @param className The record's name, as the contribution declares it.
     * @param fields Every field of the record, by name.
     */
    @Serializable @SerialName("record")
    data class RecordValue(val className: String, val fields: Map<String, WireValue>) : WireValue()

    /**
     * A handle to state the service keeps, of an opaque class the contribution defines.
     *
     * @param className The opaque class's name, as the contribution declares it.
     * @param id The id the service keeps the state under.
     */
    @Serializable @SerialName("handle")
    data class HandleValue(val className: String, val id: Long) : WireValue()
}

/**
 * A whole metamodel, as sent with [ClientMessage.MetamodelPut].
 *
 * @param path The metamodel's path, which models name as their [WireModel.metamodelPath].
 * @param classes Every class.
 * @param enums Every enum.
 * @param associations Every association.
 * @param subtypes For every class, the classes that are it or inherit from it, so a service can
 *        find all instances of a class including those of its subclasses.
 */
@Serializable
data class WireMetamodel(
    val path: String,
    val classes: List<WireClass> = emptyList(),
    val enums: List<WireEnum> = emptyList(),
    val associations: List<WireAssociation> = emptyList(),
    val subtypes: Map<String, List<String>> = emptyMap()
)

/**
 * One class of a [WireMetamodel].
 *
 * @param name The class name, unique within the metamodel.
 * @param isAbstract Whether the class has no instances of its own.
 * @param extends The classes it directly inherits from.
 * @param attributes The attributes it declares itself, without inherited ones.
 */
@Serializable
data class WireClass(
    val name: String,
    val isAbstract: Boolean = false,
    val extends: List<String> = emptyList(),
    val attributes: List<WireAttribute> = emptyList()
)

/**
 * One attribute of a [WireClass].
 *
 * @param name The attribute name.
 * @param type A primitive type name (`int`, `long`, `float`, `double`, `boolean`, `string`), or the
 *        name of an enum of the metamodel when [isEnum] is set.
 * @param isEnum Whether [type] names an enum.
 * @param lower The least number of values.
 * @param upper The greatest number of values, `-1` for unbounded.
 */
@Serializable
data class WireAttribute(
    val name: String,
    val type: String,
    val isEnum: Boolean = false,
    val lower: Int = 0,
    val upper: Int = 1
)

/**
 * One enum of a [WireMetamodel].
 *
 * @param name The enum name.
 * @param entries The entry names, in declaration order.
 */
@Serializable
data class WireEnum(val name: String, val entries: List<String> = emptyList())

/**
 * One association of a [WireMetamodel].
 *
 * @param source The end on the left of the operator.
 * @param operator The operator as written in the metamodel, such as `<-->` or `*-->`.
 * @param target The end on the right of the operator.
 */
@Serializable
data class WireAssociation(
    val source: WireAssociationEnd,
    val operator: String,
    val target: WireAssociationEnd
)

/**
 * One end of a [WireAssociation].
 *
 * @param className The class at this end.
 * @param name The property through which instances of [className] refer to the instances at the
 *        other end, and under which they list those in [WireInstance.references]; null when this
 *        end has no property.
 * @param lower The least number of instances at the other end one instance of [className] refers to.
 * @param upper The greatest number of them, `-1` for unbounded.
 */
@Serializable
data class WireAssociationEnd(
    val className: String,
    val name: String? = null,
    val lower: Int = 0,
    val upper: Int = -1
)

/**
 * A whole model, as uploaded with [ClientMessage.ModelPut].
 *
 * @param metamodelPath The metamodel the model is an instance of, as sent with
 *        [ClientMessage.MetamodelPut].
 * @param instances Every instance of the model.
 */
@Serializable
data class WireModel(
    val metamodelPath: String,
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
