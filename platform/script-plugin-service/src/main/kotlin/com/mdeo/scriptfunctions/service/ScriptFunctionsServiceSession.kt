package com.mdeo.scriptfunctions.service

import com.mdeo.pluginservice.session.SessionContext
import com.mdeo.scriptfunctions.protocol.ClientMessage
import com.mdeo.scriptfunctions.protocol.Delta
import com.mdeo.scriptfunctions.protocol.HeapKind
import com.mdeo.scriptfunctions.protocol.HeapObject
import com.mdeo.scriptfunctions.protocol.ServiceMessage
import com.mdeo.scriptfunctions.protocol.WireValue
import kotlinx.coroutines.CancellationException
import java.util.IdentityHashMap

/**
 * The service side of one `script-functions` session, independent of any connection.
 *
 * It holds the collections the execution has sent, under the ids the execution gave them, and
 * answers each call: it materializes the arguments, runs the operation, diffs every collection it
 * was given against what it received, and answers with deltas for only those that changed.
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
    private val objects = HashMap<Long, Any>()
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
        ids.clear()
        handles.clear()
        handleIds.clear()
        model = null
        modelId = null
    }

    private fun forget(id: Long) {
        objects.remove(id)?.let { ids.remove(it) }
    }

    private suspend fun call(call: ClientMessage.Call): ServiceMessage {
        // The copies are kept even when the operation changed them before failing: other held
        // collections may contain them, and the execution sends their content again with the next
        // call, which refills them in place.
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
                objects[obj.id] = instance
                ids[instance] = obj.id
            }
        }
        try {
            for (obj in call.objects) fill(objects.getValue(obj.id), obj)
        } catch (e: IllegalArgumentException) {
            return failure(e.message ?: "Malformed call")
        }

        val before = call.objects.associate { it.id to snapshot(objects.getValue(it.id)) }

        val operation = operations[call.operation]
            ?: return failure("Unknown operation '${call.operation}'")

        val returned = try {
            val arguments = call.args.map(::decode)
            operation.invoke(ScriptFunctionCall(call.operation, arguments, session, callModel))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return failure(e.message ?: e.toString())
        }

        val created = mutableListOf<HeapObject>()
        return try {
            val deltas = mutableListOf<Delta>()
            for (obj in call.objects) {
                val was = before.getValue(obj.id)
                val now = snapshot(objects.getValue(obj.id))
                if (sameSnapshot(was, now)) continue
                if (!obj.mutable) {
                    return failure(
                        "Operation '${call.operation}' changed a collection it was given as readonly"
                    )
                }
                deltas += diff(obj.id, obj.kind, was, now) { encode(it, created) }
            }
            val value = encode(returned, created)
            ServiceMessage.Result(call.callId, created, deltas, value)
        } catch (e: UnsupportedValueException) {
            created.forEach { forget(it.id) }
            failure("Operation '${call.operation}' ${e.message}")
        }
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
        is WireValue.Ref -> objects[value.id]
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
        objects[id] = value
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

    private companion object {
        /**
         * Collections are compared by identity, everything else by value: a list replaced by an
         * equal list is a change, `1` replaced by `1L` is one as well.
         */
        fun key(value: Any?): Any? = if (value is Collection<*> || value is Map<*, *>) Identity(value) else value

        fun snapshot(instance: Any): List<Any?> = when (instance) {
            is Map<*, *> -> instance.entries.flatMap { listOf(it.key, it.value) }
            else -> ArrayList(instance as Collection<*>)
        }

        fun sameSnapshot(a: List<Any?>, b: List<Any?>): Boolean =
            a.size == b.size && a.indices.all { key(a[it]) == key(b[it]) }

        fun diff(id: Long, kind: HeapKind, was: List<Any?>, now: List<Any?>, enc: (Any?) -> WireValue): List<Delta> =
            when (kind) {
                HeapKind.LIST -> {
                    var prefix = 0
                    while (prefix < was.size && prefix < now.size && key(was[prefix]) == key(now[prefix])) prefix++
                    var suffix = 0
                    while (suffix < was.size - prefix && suffix < now.size - prefix &&
                        key(was[was.size - 1 - suffix]) == key(now[now.size - 1 - suffix])
                    ) suffix++
                    listOf(
                        Delta.Splice(id, prefix, was.size - prefix - suffix, now.subList(prefix, now.size - suffix).map(enc))
                    )
                }
                HeapKind.SET -> {
                    val wasKeys = was.mapTo(HashSet(), ::key)
                    val nowKeys = now.mapTo(HashSet(), ::key)
                    membership(id, was.filter { key(it) !in nowKeys }, now.filter { key(it) !in wasKeys }, enc)
                }
                HeapKind.ORDERED_SET -> {
                    val wasKeys = was.mapTo(HashSet(), ::key)
                    val nowKeys = now.mapTo(HashSet(), ::key)
                    val kept = was.filter { key(it) in nowKeys }
                    if (kept.indices.any { key(kept[it]) != key(now[it]) }) {
                        listOf(Delta.Replace(id, now.map(enc)))
                    } else {
                        membership(id, was.filter { key(it) !in nowKeys }, now.drop(kept.size).filter { key(it) !in wasKeys }, enc)
                    }
                }
                HeapKind.BAG -> {
                    val before = LinkedHashMap<Any?, Pair<Any?, Int>>()
                    was.forEach { v -> before.merge(key(v), v to 1) { a, _ -> a.first to a.second + 1 } }
                    val after = LinkedHashMap<Any?, Pair<Any?, Int>>()
                    now.forEach { v -> after.merge(key(v), v to 1) { a, _ -> a.first to a.second + 1 } }
                    (before.keys + after.keys).mapNotNull { k ->
                        val count = after[k]?.second ?: 0
                        if ((before[k]?.second ?: 0) == count) null
                        else Delta.Count(id, enc((after[k] ?: before.getValue(k)).first), count)
                    }
                }
                HeapKind.MAP -> {
                    val before = LinkedHashMap<Any?, Pair<Any?, Any?>>()
                    for (i in was.indices step 2) before[key(was[i])] = was[i] to was[i + 1]
                    val after = LinkedHashMap<Any?, Pair<Any?, Any?>>()
                    for (i in now.indices step 2) after[key(now[i])] = now[i] to now[i + 1]
                    val removed = before.filterKeys { it !in after }.values.map { Delta.RemoveKey(id, enc(it.first)) }
                    val put = after.filter { (k, entry) -> before[k]?.let { key(it.second) == key(entry.second) } != true }
                        .values.map { Delta.Put(id, enc(it.first), enc(it.second)) }
                    removed + put
                }
            }

        fun membership(id: Long, removed: List<Any?>, added: List<Any?>, enc: (Any?) -> WireValue): List<Delta> =
            listOfNotNull(
                removed.takeIf { it.isNotEmpty() }?.let { Delta.Remove(id, it.map(enc)) },
                added.takeIf { it.isNotEmpty() }?.let { Delta.Add(id, it.map(enc)) }
            )
    }

    private class Identity(val value: Any?) {
        override fun equals(other: Any?) = other is Identity && other.value === value
        override fun hashCode() = System.identityHashCode(value)
    }
}
