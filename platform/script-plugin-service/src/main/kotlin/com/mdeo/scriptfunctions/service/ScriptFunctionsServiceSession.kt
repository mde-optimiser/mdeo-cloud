package com.mdeo.scriptfunctions.service

import com.mdeo.pluginservice.session.SessionContext
import com.mdeo.scriptfunctions.protocol.ClientMessage
import com.mdeo.scriptfunctions.protocol.HeapKind
import com.mdeo.scriptfunctions.protocol.HeapObject
import com.mdeo.scriptfunctions.protocol.ServiceMessage
import com.mdeo.scriptfunctions.protocol.WireValue
import kotlinx.coroutines.CancellationException
import java.util.Collections
import java.util.IdentityHashMap

/**
 * The service side of one `script-functions` session, independent of any connection.
 *
 * It holds the collections the execution has sent, under the ids the execution gave them, and
 * answers each call: it materializes the arguments, runs the operation and sends back what it
 * returns. Operations receive every collection as a readonly view, because every argument of an
 * external function is *in*: an operation that tries to change one fails the call.
 *
 * [ScriptFunctionService] runs one of these per connection. Use it directly to answer the protocol
 * over something other than a platform session, as tests do.
 *
 * Not thread-safe: hand it one message at a time.
 *
 * @param operations The operations this service answers, by name
 * @param session The session calls arrive on, passed through to operations
 */
class ScriptFunctionsServiceSession(
    private val operations: Map<String, ScriptFunctionOperation>,
    private val session: SessionContext? = null
) {
    /**
     * The collections by id, as the content they were last sent with is filled into.
     */
    private val objects = HashMap<Long, Any>()

    /**
     * The readonly view operations receive of each collection, by id.
     */
    private val views = HashMap<Long, Any>()

    /**
     * The id of every collection and of every view of one, by identity.
     */
    private val ids = IdentityHashMap<Any, Long>()
    private var nextNewId = -1L

    private val handles = HashMap<Long, OpaqueValue>()
    private val handleIds = IdentityHashMap<Any, Long>()
    private var nextHandleId = 1L

    private val metamodels = HashMap<String, ScriptMetamodel>()
    private var model: ScriptModel? = null
    private var modelId: Long? = null

    /**
     * The model calls currently work on, if one was uploaded.
     */
    val currentModel: ScriptModel?
        get() = model

    /**
     * Handles one message from the execution.
     *
     * @param message The decoded message
     * @return The answer to send back, or null when the message needs none
     */
    suspend fun handle(message: ClientMessage): ServiceMessage? = when (message) {
        is ClientMessage.Release -> {
            message.ids.forEach(::forget)
            message.handles.forEach { id -> handles.remove(id)?.let { handleIds.remove(it.state) } }
            null
        }
        is ClientMessage.Call -> call(message)
        is ClientMessage.MetamodelPut -> {
            val metamodel = ScriptMetamodel(message.metamodel)
            // The held model was built on the metamodel this one replaces.
            if (model?.metamodelPath == metamodel.path) dropModel()
            metamodels[metamodel.path] = metamodel
            null
        }
        is ClientMessage.ModelPut -> {
            // A new model ends everything that belonged to the old one, collections included:
            // they may hold its instances.
            dropModel()
            // Without its metamodel the model cannot be read; the next call naming it answers
            // unknown-model, and the execution sends both again.
            metamodels[message.model.metamodelPath]?.let { metamodel ->
                model = ScriptModel(message.model, metamodel)
                modelId = message.modelId
            }
            null
        }
    }

    /**
     * Forgets everything the session was sent, as when the session ends.
     */
    fun clear() {
        dropModel()
        metamodels.clear()
    }

    private fun dropModel() {
        objects.clear()
        views.clear()
        ids.clear()
        handles.clear()
        handleIds.clear()
        model = null
        modelId = null
    }

    private fun forget(id: Long) {
        objects.remove(id)?.let { ids.remove(it) }
        views.remove(id)?.let { ids.remove(it) }
    }

    private suspend fun call(call: ClientMessage.Call): ServiceMessage {
        fun failure(message: String, code: String? = null): ServiceMessage =
            ServiceMessage.Failure(call.callId, message, code)

        if (call.modelId != null && call.modelId != modelId) {
            return ServiceMessage.Failure(
                call.callId,
                "Model ${call.modelId} is not held",
                ServiceMessage.Failure.UNKNOWN_MODEL
            )
        }
        val callModel = if (call.modelId != null) model else null

        for (obj in call.objects) {
            if (obj.elements == null && obj.entries == null && obj.id !in objects) {
                return ServiceMessage.Failure(
                    call.callId,
                    "Collection ${obj.id} is not held",
                    ServiceMessage.Failure.UNKNOWN_OBJECT
                )
            }
        }

        // Two passes, so a collection may refer to one listed after it, or to itself.
        for (obj in call.objects) {
            if (obj.id !in objects) {
                val instance: Any = when (obj.kind) {
                    HeapKind.LIST, HeapKind.BAG -> ArrayList<Any?>()
                    HeapKind.SET, HeapKind.ORDERED_SET -> LinkedHashSet<Any?>()
                    HeapKind.MAP -> LinkedHashMap<Any?, Any?>()
                }
                hold(obj.id, instance)
            }
        }
        try {
            for (obj in call.objects) fill(objects.getValue(obj.id), obj)
        } catch (e: IllegalArgumentException) {
            return failure(e.message ?: "Malformed call")
        }

        val operation = operations[call.operation]
            ?: return failure("Unknown operation '${call.operation}'")

        val returned = try {
            val arguments = call.args.map(::decode)
            operation.invoke(ScriptFunctionCall(call.operation, arguments, session, callModel))
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnsupportedOperationException) {
            return failure(
                "Operation '${call.operation}' failed: ${e.message ?: e.toString()}. " +
                        "Arguments of external functions are readonly; copy a collection to change it."
            )
        } catch (e: Exception) {
            return failure(e.message ?: e.toString())
        }

        val created = mutableListOf<HeapObject>()
        return try {
            val value = encode(returned, created)
            ServiceMessage.Result(call.callId, created, value)
        } catch (e: UnsupportedValueException) {
            created.forEach { forget(it.id) }
            failure("Operation '${call.operation}' ${e.message}")
        }
    }

    /**
     * Holds a collection under an id, together with the readonly view operations receive of it.
     *
     * @param id The id
     * @param instance The collection
     */
    private fun hold(id: Long, instance: Any) {
        val view: Any = when (instance) {
            is Map<*, *> -> Collections.unmodifiableMap(instance)
            is Set<*> -> Collections.unmodifiableSet(instance)
            is List<*> -> Collections.unmodifiableList(instance)
            else -> Collections.unmodifiableCollection(instance as Collection<*>)
        }
        objects[id] = instance
        views[id] = view
        ids[instance] = id
        ids[view] = id
    }

    @Suppress("UNCHECKED_CAST")
    private fun fill(target: Any, obj: HeapObject) {
        when (target) {
            is MutableMap<*, *> -> obj.entries?.let { entries ->
                require(entries.size % 2 == 0) { "Map ${obj.id} has an odd number of entry values" }
                val map = target as MutableMap<Any?, Any?>
                map.clear()
                for (i in entries.indices step 2) map[decode(entries[i])] = decode(entries[i + 1])
            }
            else -> obj.elements?.let { elements ->
                val collection = target as MutableCollection<Any?>
                collection.clear()
                elements.forEach { collection.add(decode(it)) }
            }
        }
    }

    private fun decode(value: WireValue): Any? = when (value) {
        WireValue.Null -> null
        is WireValue.Bool -> value.value
        is WireValue.IntValue -> value.value
        is WireValue.LongValue -> value.value
        is WireValue.FloatValue -> value.value
        is WireValue.DoubleValue -> value.value
        is WireValue.StringValue -> value.value
        is WireValue.Ref -> views[value.id]
            ?: throw IllegalArgumentException("Collection ${value.id} is referenced but was not sent")
        is WireValue.InstanceValue -> model?.instances?.get(value.name)
            ?: throw IllegalArgumentException("Instance '${value.name}' is not part of the model")
        is WireValue.RecordValue -> RecordValue(value.className, value.fields.mapValues { decode(it.value) })
        is WireValue.HandleValue -> handles[value.id]?.state
            ?: throw IllegalArgumentException(
                "The ${value.className} handle ${value.id} is no longer held; handles do not outlive the model they were created on"
            )
    }

    /**
     * Encodes one value, adopting a collection the service created under a fresh negative id.
     * The id is taken before the content is encoded, so a new collection may contain itself.
     */
    private fun encode(value: Any?, created: MutableList<HeapObject>): WireValue {
        // State an operation was handed as a handle goes back as that handle, whatever it is.
        val handleId = value?.let { handleIds[it] }
        if (handleId != null) return WireValue.HandleValue(handles.getValue(handleId).className, handleId)
        return encodeValue(value, created)
    }

    private fun encodeValue(value: Any?, created: MutableList<HeapObject>): WireValue = when (value) {
        null, Unit -> WireValue.Null
        is Boolean -> WireValue.Bool(value)
        is Int -> WireValue.IntValue(value)
        is Long -> WireValue.LongValue(value)
        is Float -> WireValue.FloatValue(value)
        is Double -> WireValue.DoubleValue(value)
        is String -> WireValue.StringValue(value)
        is RecordValue -> WireValue.RecordValue(value.recordName, value.fields.mapValues { encode(it.value, created) })
        is OpaqueValue -> WireValue.HandleValue(value.className, handleIdFor(value))
        is ScriptModelInstance -> if (value.model === model) {
            WireValue.InstanceValue(value.name)
        } else {
            throw UnsupportedValueException("returned an instance of a model the call does not work on")
        }
        is Collection<*>, is Map<*, *> -> ids[value]?.let { WireValue.Ref(it) } ?: adopt(value, created)
        else -> throw UnsupportedValueException(
            "returned a ${value::class.qualifiedName}, which cannot be sent to a script"
        )
    }

    /**
     * The id of a handle, the same one every time the same state is returned.
     */
    private fun handleIdFor(value: OpaqueValue): Long =
        handleIds.getOrPut(value.state) {
            val id = nextHandleId++
            handles[id] = value
            id
        }

    private fun adopt(value: Any, created: MutableList<HeapObject>): WireValue {
        // An execution that reconnected may still send collections under ids an earlier
        // connection's service chose; a new id must not collide with one of those.
        while (nextNewId in objects) nextNewId--
        val id = nextNewId--
        // The collection is held as a copy the execution's later sends can be filled into, since
        // the operation's own may be immutable; returning the original again still finds the id.
        hold(
            id,
            when (value) {
                is Map<*, *> -> LinkedHashMap(value)
                is Set<*> -> LinkedHashSet(value)
                else -> ArrayList(value as Collection<*>)
            }
        )
        ids[value] = id
        val kind = when (value) {
            is Map<*, *> -> HeapKind.MAP
            is Set<*> -> HeapKind.ORDERED_SET
            else -> HeapKind.LIST
        }
        val index = created.size
        created += HeapObject(id, kind)
        created[index] = if (value is Map<*, *>) {
            created[index].copy(entries = value.entries.flatMap { listOf(encode(it.key, created), encode(it.value, created)) })
        } else {
            created[index].copy(elements = (value as Collection<*>).map { encode(it, created) })
        }
        return WireValue.Ref(id)
    }

    private class UnsupportedValueException(message: String) : RuntimeException(message)
}
