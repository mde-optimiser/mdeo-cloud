package com.mdeo.script.external

import com.mdeo.script.runtime.ScriptRecord
import com.mdeo.script.runtime.ScriptOpaque
import com.mdeo.script.compiler.ContributedClassSpec
import com.mdeo.script.ast.TypedPluginClass
import java.util.WeakHashMap
import com.mdeo.script.ast.ExternalImplementation
import com.mdeo.metamodel.ModelInstance
import com.mdeo.metamodel.Model
import com.mdeo.scriptfunctions.protocol.ScriptFunctionsProtocol
import com.mdeo.scriptfunctions.protocol.ClientMessage
import com.mdeo.scriptfunctions.protocol.ServiceMessage
import com.mdeo.scriptfunctions.protocol.HeapKind
import com.mdeo.scriptfunctions.protocol.HeapObject
import com.mdeo.scriptfunctions.protocol.WireValue
import com.mdeo.scriptfunctions.protocol.Delta
import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.expression.ast.types.ReturnType
import com.mdeo.script.compiler.ExternalCallSpec
import com.mdeo.script.runtime.ExternalCallDispatcher
import com.mdeo.script.stdlib.impl.collections.BagImpl
import com.mdeo.script.stdlib.impl.collections.DeltaTarget
import com.mdeo.script.stdlib.impl.collections.ListImpl
import com.mdeo.script.stdlib.impl.collections.MapDeltaTarget
import com.mdeo.script.stdlib.impl.collections.MapImpl
import com.mdeo.script.stdlib.impl.collections.OrderedSetImpl
import com.mdeo.script.stdlib.impl.collections.SetImpl

/**
 * Raised when a call to an external function cannot be completed.
 *
 * Whatever the reason — the service reported a failure, or its answer broke the contract — no
 * change from that call has been applied.
 *
 * @param message What went wrong
 */
class ExternalCallException(message: String) : RuntimeException(message)

/**
 * The execution side of the `script-functions` protocol.
 *
 * Installed as the [ExternalCallDispatcher] of a script context, it turns each call a compiled
 * stub makes into one [ClientMessage.Call], waits for the answer, checks it, and applies it.
 *
 * Checking comes strictly before applying. Every delta is validated against the state it will be
 * applied to — that it targets a collection sent as inout in this very call, that its kind fits,
 * that its indices are in range as the deltas before it leave them — and a single violation
 * rejects the whole result. A call therefore either takes effect completely or not at all.
 *
 * Calls are serialized: one session carries one conversation at a time.
 *
 * @param transport The pipe to the service
 * @param specs The external calls of the compiled program, keyed by call id
 * @param classes The records and opaque classes of the contribution this client calls, keyed by
 *        [ContributedClassSpec.typeId]
 */
class ScriptFunctionsClient(
    private val transport: ScriptFunctionsTransport,
    private val specs: Map<String, ExternalCallSpec>,
    classes: Map<String, ContributedClassSpec> = emptyMap()
) : ExternalCallDispatcher {

    private val registry = IdentityRegistry()
    private val handles = HandleRegistry()
    private val classesByType = classes
    private val classesByName = classes.values.associateBy { it.name }

    /**
     * For each id, the version at which the service is known to hold the collection. A collection
     * still at that version is sent as just its id.
     */
    private val serviceVersions = HashMap<Long, Long>()

    private var nextCallId = 1L
    private val lock = Any()

    /**
     * Encoded models by the object they were built from. An execution calls many times on one
     * model, and encoding it is the costly part of deciding whether it has to be uploaded.
     */
    private val encodedModels = WeakHashMap<Model, EncodedModel>()

    /**
     * The digest and id of the model the service holds, if any.
     */
    private var heldModelDigest: String? = null
    private var heldModelId = 0L
    private var nextModelId = 1L

    override fun call(callId: String, arguments: Array<Any?>, model: Model?, classLoader: ClassLoader): Any? = synchronized(lock) {
        val spec = specs[callId] ?: throw ExternalCallException("No external call '$callId' was compiled")

        val released = registry.drainReleased()
        val releasedHandles = handles.drainReleased()
        if (released.isNotEmpty() || releasedHandles.isNotEmpty()) {
            released.forEach { serviceVersions.remove(it) }
            transport.send(ScriptFunctionsProtocol.encodeClient(ClientMessage.Release(released, releasedHandles)))
        }

        var resentInFull = false
        while (true) {
            val encoder = HeapEncoder(model)
            val args = arguments.mapIndexed { index, argument ->
                encoder.encode(argument, spec.parameterTypes.getOrElse(index) { ParameterModes.any }, spec.functionName)
            }

            val needsModel = spec.model == ExternalImplementation.MODEL_READONLY || encoder.usesInstances
            val modelId = if (needsModel) {
                if (model == null) {
                    throw ExternalCallException(
                        "External function '${spec.functionName}' reads the model, but the script runs on none"
                    )
                }
                // Uploading a model resets the service, so this comes before deciding which
                // collections can be sent without content.
                uploadIfNotHeld(model)
            } else {
                null
            }

            val id = nextCallId++
            transport.send(
                ScriptFunctionsProtocol.encodeClient(
                    ClientMessage.Call(id, spec.operation, encoder.objects(), args, modelId)
                )
            )

            when (val answer = awaitAnswer(id)) {
                is ServiceMessage.Failure -> {
                    // Whatever the service did to its copies before failing is not ours; it has
                    // to be sent everything again next time.
                    encoder.sent.keys.forEach { serviceVersions.remove(it) }
                    val lostState = answer.code == ServiceMessage.Failure.UNKNOWN_OBJECT ||
                            answer.code == ServiceMessage.Failure.UNKNOWN_MODEL
                    if (lostState && !resentInFull) {
                        // The service lost what it held, as after a reconnect: send everything.
                        heldModelDigest = null
                        serviceVersions.clear()
                        resentInFull = true
                        continue
                    }
                    throw ExternalCallException("External function '${spec.functionName}' failed: ${answer.message}")
                }
                is ServiceMessage.Result -> {
                    val applier = ResultApplier(encoder, spec, classLoader)
                    try {
                        applier.validate(answer)
                    } catch (e: ExternalCallException) {
                        encoder.sent.keys.forEach { serviceVersions.remove(it) }
                        throw e
                    }
                    return applier.apply(answer)
                }
            }
        }
    }

    /**
     * Makes sure the service holds [model], uploading it when the service holds a different one or
     * none.
     *
     * @return The id the service holds the model under
     */
    private fun uploadIfNotHeld(model: Model): Long {
        val encoded = encodedModels.getOrPut(model) { ModelEncoder.encode(model) }
        if (encoded.digest != heldModelDigest) {
            val modelId = nextModelId++
            transport.send(ScriptFunctionsProtocol.encodeClient(ClientMessage.ModelPut(modelId, encoded.wire)))
            heldModelDigest = encoded.digest
            heldModelId = modelId
            // A new model replaces everything the service held.
            serviceVersions.clear()
        }
        return heldModelId
    }

    private fun awaitAnswer(callId: Long): ServiceMessage {
        while (true) {
            val message = ScriptFunctionsProtocol.decodeService(transport.receive())
            val answered = when (message) {
                is ServiceMessage.Result -> message.callId
                is ServiceMessage.Failure -> message.callId
            }
            if (answered == callId) return message
        }
    }

    /**
     * Encodes the arguments of one call, collecting every collection they reach.
     */
    private inner class HeapEncoder(val model: Model?) {
        /**
         * Whether an argument reaches a model instance, which the service can only resolve with
         * the model.
         */
        var usesInstances = false

        /**
         * The collections of this call by id, and whether any path reached them as inout.
         */
        val sent = LinkedHashMap<Long, Any>()
        val mutableIds = HashSet<Long>()
        val declaredTypes = HashMap<Long, ReturnType>()

        fun encode(value: Any?, declared: ReturnType, functionName: String): WireValue = when (value) {
            null -> WireValue.Null
            is Boolean -> WireValue.Bool(value)
            is Int -> WireValue.IntValue(value)
            is Long -> WireValue.LongValue(value)
            is Float -> WireValue.FloatValue(value)
            is Double -> WireValue.DoubleValue(value)
            is String -> WireValue.StringValue(value)
            is ScriptRecord -> {
                val recordClass = classesByType[value.recordType]
                    ?: throw ExternalCallException(
                        "External function '$functionName' was passed a ${value.recordType}, which its contribution does not define"
                    )
                val values = value.fields()
                WireValue.RecordValue(
                    recordClass.name,
                    recordClass.fieldNames.withIndex().associate { (index, fieldName) ->
                        fieldName to encode(values[index], recordClass.fieldTypes[index], functionName)
                    }
                )
            }
            is ScriptOpaque -> {
                val opaqueClass = classesByType[value.opaqueType]
                    ?: throw ExternalCallException(
                        "External function '$functionName' was passed a ${value.opaqueType}, which its contribution does not define"
                    )
                WireValue.HandleValue(opaqueClass.name, value.handle)
            }
            is ModelInstance -> {
                val name = model?.nameOf(value) ?: throw ExternalCallException(
                    "External function '$functionName' was passed a model instance that is not part " +
                            "of the model the script runs on"
                )
                usesInstances = true
                WireValue.InstanceValue(name)
            }
            is DeltaTarget, is MapDeltaTarget -> {
                val id = registry.idFor(value)
                val inout = ParameterModes.isInout(declared)
                if (inout) {
                    mutableIds += id
                    declaredTypes[id] = declared
                }
                if (id !in sent) {
                    sent[id] = value
                    declaredTypes[id] = declared
                    if (value is MapDeltaTarget) {
                        for ((k, v) in value.deltaEntries()) {
                            encode(k, ParameterModes.keyType(declared), functionName)
                            encode(v, ParameterModes.valueType(declared), functionName)
                        }
                    } else {
                        for (element in (value as DeltaTarget).deltaSnapshot()) {
                            encode(element, ParameterModes.elementType(declared), functionName)
                        }
                    }
                }
                WireValue.Ref(id)
            }
            else -> throw ExternalCallException(
                "External function '$functionName' was passed a ${value::class.simpleName}, which " +
                        "script-functions version ${ScriptFunctionsProtocol.VERSION} cannot carry. " +
                        "Only scalars, strings, model instances and collections of them can be passed."
            )
        }

        /**
         * The heap objects of this call, content omitted where the service already has it.
         */
        fun objects(): List<HeapObject> = sent.map { (id, value) ->
            val version = versionOf(value)
            val known = serviceVersions[id] == version
            val declared = declaredTypes.getValue(id)
            when (value) {
                is MapDeltaTarget -> HeapObject(
                    id, HeapKind.MAP, version, id in mutableIds,
                    entries = if (known) null else value.deltaEntries().flatMap { (k, v) ->
                        listOf(
                            encode(k, ParameterModes.keyType(declared), ""),
                            encode(v, ParameterModes.valueType(declared), "")
                        )
                    }
                )
                else -> HeapObject(
                    id, kindOf(value), version, id in mutableIds,
                    elements = if (known) null else (value as DeltaTarget).deltaSnapshot()
                        .map { encode(it, ParameterModes.elementType(declared), "") }
                )
            }
        }.also { objects -> objects.forEach { serviceVersions[it.id] = it.version } }
    }

    /**
     * Checks and applies one result.
     */
    private inner class ResultApplier(
        private val encoder: HeapEncoder,
        private val spec: ExternalCallSpec,
        private val classLoader: ClassLoader
    ) {
        private val created = HashMap<Long, Any>()

        /**
         * Rejects a result that breaks the contract, before anything is applied.
         */
        fun validate(result: ServiceMessage.Result) {
            val newIds = result.objects.map { it.id }.toSet()
            for (obj in result.objects) {
                if (obj.id >= 0) reject("created a collection under non-negative id ${obj.id}")
                if (registry.objectOf(obj.id) != null) reject("reused id ${obj.id} for a new collection")
                (obj.elements.orEmpty() + obj.entries.orEmpty()).forEach { checkValue(it, newIds) }
            }

            val sizes = HashMap<Long, Int>()
            for (delta in result.deltas) {
                val target = encoder.sent[delta.id] ?: reject("changed collection ${delta.id}, which was not part of this call")
                if (delta.id !in encoder.mutableIds) {
                    reject("changed collection ${delta.id}, which was passed as readonly")
                }
                val size = sizes.getOrPut(delta.id) { sizeOf(target) }
                val kind = kindOf(target)
                when (delta) {
                    is Delta.Splice -> {
                        if (kind != HeapKind.LIST && kind != HeapKind.ORDERED_SET) reject("spliced a $kind")
                        if (delta.index < 0 || delta.deleteCount < 0 || delta.index + delta.deleteCount > size) {
                            reject("spliced ${delta.deleteCount} at ${delta.index} into a collection of size $size")
                        }
                        delta.insert.forEach { checkValue(it, newIds) }
                        sizes[delta.id] = size - delta.deleteCount + delta.insert.size
                    }
                    is Delta.Add -> {
                        if (kind == HeapKind.LIST || kind == HeapKind.MAP) reject("added to a $kind without an index or key")
                        delta.values.forEach { checkValue(it, newIds) }
                    }
                    is Delta.Remove -> {
                        if (kind == HeapKind.LIST || kind == HeapKind.MAP) reject("removed from a $kind without an index or key")
                        delta.values.forEach { checkValue(it, newIds) }
                    }
                    is Delta.Count -> {
                        if (kind != HeapKind.BAG) reject("set a count on a $kind")
                        if (delta.count < 0) reject("set a negative count")
                        checkValue(delta.value, newIds)
                    }
                    is Delta.Put -> {
                        if (kind != HeapKind.MAP) reject("put into a $kind")
                        checkValue(delta.key, newIds); checkValue(delta.value, newIds)
                    }
                    is Delta.RemoveKey -> {
                        if (kind != HeapKind.MAP) reject("removed a key from a $kind")
                        checkValue(delta.key, newIds)
                    }
                    is Delta.Replace -> {
                        if (kind == HeapKind.MAP && delta.elements.size % 2 != 0) reject("replaced a map with an odd entry list")
                        delta.elements.forEach { checkValue(it, newIds) }
                        sizes[delta.id] = if (kind == HeapKind.MAP) delta.elements.size / 2 else delta.elements.size
                    }
                }
            }
            checkValue(result.value, newIds)
        }

        private fun checkValue(value: WireValue, newIds: Set<Long>) {
            if (value is WireValue.RecordValue) {
                val recordClass = classesByName[value.className]
                if (recordClass?.kind != TypedPluginClass.KIND_RECORD) {
                    reject("returned a record '${value.className}', which the contribution does not define")
                }
                if (value.fields.keys != recordClass.fieldNames.toSet()) {
                    reject("returned a record '${value.className}' with fields ${value.fields.keys}, not ${recordClass.fieldNames}")
                }
                value.fields.values.forEach { checkValue(it, newIds) }
            }
            if (value is WireValue.HandleValue && classesByName[value.className]?.kind != TypedPluginClass.KIND_OPAQUE) {
                reject("returned a handle of '${value.className}', which the contribution does not define as opaque")
            }
            if (value is WireValue.InstanceValue && encoder.model?.instancesByName?.containsKey(value.name) != true) {
                reject("referred to instance '${value.name}', which is not part of the model the script runs on")
            }
            if (value is WireValue.Ref && value.id !in newIds && value.id !in encoder.sent && registry.objectOf(value.id) == null) {
                reject("referred to unknown collection ${value.id}")
            }
        }

        private fun reject(reason: String): Nothing =
            throw ExternalCallException(
                "External function '${spec.functionName}' returned a result that was rejected and not applied: " +
                        "the service $reason"
            )

        /**
         * Applies a validated result and returns the call's value.
         */
        fun apply(result: ServiceMessage.Result): Any? {
            for (obj in result.objects) {
                val instance = instantiate(obj.kind)
                created[obj.id] = instance
            }
            for (obj in result.objects) {
                val instance = created.getValue(obj.id)
                if (instance is MapDeltaTarget) {
                    instance.deltaReplace(obj.entries.orEmpty().chunked(2).map { (k, v) -> decode(k, null) to decode(v, null) })
                } else {
                    (instance as DeltaTarget).deltaReplace(obj.elements.orEmpty().map { decode(it, null) })
                }
            }

            for (delta in result.deltas) {
                val target = encoder.sent.getValue(delta.id)
                val declared = encoder.declaredTypes.getValue(delta.id)
                val element = ParameterModes.elementType(declared)
                when (delta) {
                    is Delta.Splice -> (target as DeltaTarget).deltaSplice(delta.index, delta.deleteCount, delta.insert.map { decode(it, element) })
                    is Delta.Add -> (target as DeltaTarget).deltaAdd(delta.values.map { decode(it, element) })
                    is Delta.Remove -> (target as DeltaTarget).deltaRemove(delta.values.map { decode(it, element) })
                    is Delta.Count -> (target as DeltaTarget).deltaSetCount(decode(delta.value, element), delta.count)
                    is Delta.Put -> (target as MapDeltaTarget).deltaPut(
                        decode(delta.key, ParameterModes.keyType(declared)),
                        decode(delta.value, ParameterModes.valueType(declared))
                    )
                    is Delta.RemoveKey -> (target as MapDeltaTarget).deltaRemoveKey(decode(delta.key, ParameterModes.keyType(declared)))
                    is Delta.Replace -> if (target is MapDeltaTarget) {
                        target.deltaReplace(delta.elements.chunked(2).map { (k, v) ->
                            decode(k, ParameterModes.keyType(declared)) to decode(v, ParameterModes.valueType(declared))
                        })
                    } else {
                        (target as DeltaTarget).deltaReplace(delta.elements.map { decode(it, element) })
                    }
                }
            }

            // After applying, what the execution holds is exactly what the service holds.
            val touched = result.deltas.map { it.id }.toSet()
            for (id in touched) serviceVersions[id] = versionOf(encoder.sent.getValue(id))
            for ((id, instance) in created) {
                registry.register(instance, id)
                serviceVersions[id] = versionOf(instance)
            }

            val value = decode(result.value, spec.returnType)
            return convertToDeclared(value, spec.returnType)
        }

        private fun decode(value: WireValue, expected: ReturnType?): Any? {
            val decoded: Any? = when (value) {
                WireValue.Null -> null
                is WireValue.Bool -> value.value
                is WireValue.IntValue -> value.value
                is WireValue.LongValue -> value.value
                is WireValue.FloatValue -> value.value
                is WireValue.DoubleValue -> value.value
                is WireValue.StringValue -> value.value
                is WireValue.Ref -> created[value.id] ?: encoder.sent[value.id] ?: registry.objectOf(value.id)
                is WireValue.InstanceValue -> encoder.model?.instancesByName?.get(value.name)
                is WireValue.RecordValue -> {
                    val recordClass = classesByName.getValue(value.className)
                    val values = arrayOfNulls<Any?>(recordClass.fieldNames.size)
                    recordClass.fieldNames.forEachIndexed { index, fieldName ->
                        values[index] = decode(value.fields.getValue(fieldName), recordClass.fieldTypes[index])
                    }
                    classLoader.loadClass(recordClass.jvmClassName.replace('/', '.'))
                        .getConstructor(Array<Any?>::class.java)
                        .newInstance(values)
                }
                is WireValue.HandleValue -> handles.handleFor(value.id) {
                    classLoader.loadClass(classesByName.getValue(value.className).jvmClassName.replace('/', '.'))
                        .getConstructor(Long::class.javaPrimitiveType)
                        .newInstance(value.id)
                }
            }
            return coerceNumber(decoded, expected)
        }
    }

    companion object {
        private fun versionOf(value: Any): Long = when (value) {
            is MapDeltaTarget -> value.deltaVersion
            else -> (value as DeltaTarget).deltaVersion
        }

        private fun sizeOf(value: Any): Int = when (value) {
            is MapDeltaTarget -> value.deltaEntries().size
            else -> (value as DeltaTarget).deltaSnapshot().size
        }

        private fun kindOf(value: Any): HeapKind = when (value) {
            is MapDeltaTarget -> HeapKind.MAP
            is BagImpl<*> -> HeapKind.BAG
            is OrderedSetImpl<*> -> HeapKind.ORDERED_SET
            is SetImpl<*> -> HeapKind.SET
            else -> HeapKind.LIST
        }

        private fun instantiate(kind: HeapKind): Any = when (kind) {
            HeapKind.LIST -> ListImpl<Any?>()
            HeapKind.SET -> SetImpl<Any?>()
            HeapKind.ORDERED_SET -> OrderedSetImpl<Any?>()
            HeapKind.BAG -> BagImpl<Any?>()
            HeapKind.MAP -> MapImpl<Any?, Any?>()
        }

        /**
         * Brings a number decoded from the wire back to the type it was declared as. A service
         * written in a language with one number type cannot say whether `2` was an int or a
         * double; the declaration can.
         */
        private fun coerceNumber(value: Any?, expected: ReturnType?): Any? {
            if (value !is Number || expected !is ClassTypeRef || expected.`package` != "builtin") return value
            return when (expected.type) {
                "int" -> value.toInt()
                "long" -> value.toLong()
                "float" -> value.toFloat()
                "double" -> value.toDouble()
                else -> value
            }
        }

        /**
         * Rebuilds a returned collection as the collection type the signature declares, when the
         * service created it as a different kind — a service returning an array for a `Set`.
         */
        private fun convertToDeclared(value: Any?, declared: ReturnType): Any? {
            if (value !is DeltaTarget || declared !is ClassTypeRef || declared.`package` != "builtin") return value
            val elements = value.deltaSnapshot()
            val wanted: Any = when (declared.type) {
                "List", "ReadonlyList", "OrderedCollection", "ReadonlyOrderedCollection" ->
                    if (value is ListImpl<*>) return value else ListImpl(elements)
                "Set", "ReadonlySet" -> if (value is SetImpl<*>) return value else SetImpl(elements)
                "OrderedSet", "ReadonlyOrderedSet" -> if (value is OrderedSetImpl<*>) return value else OrderedSetImpl(elements)
                "Bag", "ReadonlyBag" -> if (value is BagImpl<*>) return value else BagImpl(elements)
                else -> return value
            }
            return wanted
        }
    }
}
