package com.mdeo.script.external

import com.mdeo.script.runtime.ScriptRecord
import com.mdeo.script.runtime.ScriptOpaque
import com.mdeo.script.compiler.ContributedClassSpec
import com.mdeo.script.ast.TypedPluginClass
import java.util.WeakHashMap
import java.util.concurrent.locks.ReentrantLock
import com.mdeo.script.ast.ExternalImplementation
import com.mdeo.metamodel.ModelInstance
import com.mdeo.metamodel.Metamodel
import com.mdeo.metamodel.Model
import com.mdeo.scriptfunctions.protocol.ScriptFunctionsProtocol
import com.mdeo.scriptfunctions.protocol.ClientMessage
import com.mdeo.scriptfunctions.protocol.ServiceMessage
import com.mdeo.scriptfunctions.protocol.HeapKind
import com.mdeo.scriptfunctions.protocol.HeapObject
import com.mdeo.scriptfunctions.protocol.WireScalars
import com.mdeo.scriptfunctions.protocol.WireValue
import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.expression.ast.types.ReturnType
import com.mdeo.script.compiler.ExternalCallSpec
import com.mdeo.script.runtime.ExternalCallDispatcher
import com.mdeo.script.stdlib.impl.collections.BagImpl
import com.mdeo.script.stdlib.impl.collections.HeapCollection
import com.mdeo.script.stdlib.impl.collections.ListImpl
import com.mdeo.script.stdlib.impl.collections.HeapMap
import com.mdeo.script.stdlib.impl.collections.MapImpl
import com.mdeo.script.stdlib.impl.collections.OrderedSetImpl
import com.mdeo.script.stdlib.impl.collections.SetImpl

/**
 * Raised when a call to an external function cannot be completed, because the service reported a
 * failure or its answer broke the contract.
 *
 * @param message What went wrong
 */
class ExternalCallException(message: String) : RuntimeException(message)

/**
 * The execution side of the `script-functions` protocol.
 *
 * Installed as the [ExternalCallDispatcher] of a script context, it turns each call a compiled
 * stub makes into one [ClientMessage.Call], waits for the answer, checks it, and returns its value.
 *
 * Every argument is *in*: the service never changes what the script holds. The answer is checked
 * as a whole before any collection it creates is built, and a single violation rejects it.
 *
 * Calls are serialized: one session carries one conversation at a time.
 *
 * When the transport reconnects, the service on the other side starts empty. The client notices
 * the new connection, gives the collections the old service created ids of its own, sends what the
 * next call needs in full, and refuses handles whose state only the old service held. A call whose
 * message went out on a connection that was not the one it was prepared for is sent again, at most
 * [ScriptFunctionsTransport.maxReconnects] times.
 *
 * Enum values are sent as their enum and entry name, with or without a model, and come back as the
 * script's own entries, see [EnumValues].
 *
 * @param transport The pipe to the service
 * @param specs The external calls of the compiled program, keyed by call id
 * @param classes The records and opaque classes of the contribution this client calls
 */
class ScriptFunctionsClient(
    private val transport: ScriptFunctionsTransport,
    private val specs: Map<String, ExternalCallSpec>,
    classes: Collection<ContributedClassSpec> = emptyList()
) : ExternalCallDispatcher {

    private val registry = IdentityRegistry()
    private val handles = HandleRegistry()

    /**
     * The contributed classes by the binary name of their generated class, which is what tells a
     * record or handle the script passes apart.
     */
    private val classesByJvmName = classes.associateBy { it.jvmClassName.replace('/', '.') }

    /**
     * The contributed classes by the name the service knows them by.
     */
    private val classesByName = classes.associateBy { it.name }

    private val enumValues = EnumValues(
        specs.values.flatMap { it.parameterTypes + it.returnType } + classes.flatMap { it.fieldTypes }
    )

    /**
     * For each id, the version at which the service is known to hold the collection. A collection
     * still at that version is sent as just its id.
     */
    private val serviceVersions = HashMap<Long, Long>()

    private var nextCallId = 1L

    /**
     * Held for the whole of a call, since a session carries one conversation at a time. Taken
     * interruptibly, so a thread waiting behind another call can still be cancelled.
     */
    private val lock = ReentrantLock()

    /**
     * Encoded models by the object they were built from. An execution calls many times on one
     * model, and encoding it is the costly part of deciding whether it has to be uploaded.
     */
    private val encodedModels = WeakHashMap<Model, EncodedModel>()
    private val encodedMetamodels = WeakHashMap<Metamodel, EncodedMetamodel>()

    /**
     * The digest of every metamodel the service holds, by path. Each is sent once per connection.
     */
    private val heldMetamodelDigests = HashMap<String, String>()

    /**
     * The digest and id of the model the service holds, if any.
     */
    private var heldModelDigest: String? = null
    private var heldModelId = 0L
    private var nextModelId = 1L

    /**
     * The transport connection the state above describes, once a call was made.
     */
    private var knownConnection: Long? = null

    override fun call(callId: String, arguments: Array<Any?>, model: Model?, classLoader: ClassLoader): Any? {
        lock.lockInterruptibly()
        try {
            return callLocked(callId, arguments, model, classLoader)
        } finally {
            lock.unlock()
        }
    }

    private fun callLocked(callId: String, arguments: Array<Any?>, model: Model?, classLoader: ClassLoader): Any? {
        val spec = specs[callId] ?: throw ExternalCallException("No external call '$callId' was compiled")

        followConnection()
        val released = registry.drainReleased()
        val releasedHandles = handles.drainReleased()
        if (released.isNotEmpty() || releasedHandles.isNotEmpty()) {
            released.forEach { serviceVersions.remove(it) }
            transport.send(ScriptFunctionsProtocol.encodeClient(ClientMessage.Release(released, releasedHandles)))
        }

        var resentInFull = false
        var reconnects = 0
        while (true) {
            followConnection()
            val preparedFor = transport.connection
            val encoder = HeapEncoder(model)
            val args = arguments.mapIndexed { index, argument ->
                encoder.encode(argument, spec.parameterTypes.getOrElse(index) { DeclaredTypes.any }, spec.functionName)
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

            val answer = awaitAnswer(id)
            if (transport.connection != preparedFor) {
                // The call reached a service that started empty, with ids and handles meant for
                // the one before it: whatever it answered describes nothing this side holds.
                discard(answer, encoder)
                // Each resend follows a reconnect the transport made; it gives up on a dropped
                // connection after as many redials, and a call does not outlast it.
                if (++reconnects > transport.maxReconnects) {
                    throw ExternalCallException(
                        "External function '${spec.functionName}' could not be called: the session kept reconnecting"
                    )
                }
                continue
            }
            when (answer) {
                is ServiceMessage.Failure -> {
                    // A failed call may not have taken in everything it was sent, so it is all
                    // sent again next time.
                    encoder.sent.keys.forEach { serviceVersions.remove(it) }
                    val lostState = answer.code == ServiceMessage.Failure.UNKNOWN_OBJECT ||
                            answer.code == ServiceMessage.Failure.UNKNOWN_MODEL
                    if (lostState && !resentInFull) {
                        // The service lost what it held, as after a reconnect: send everything.
                        heldModelDigest = null
                        heldMetamodelDigests.clear()
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
     * Brings this side's state in line with a service that started empty, when the transport
     * reconnected since the last look.
     */
    private fun followConnection() {
        val current = transport.connection
        val known = knownConnection
        knownConnection = current
        if (known == null || known == current) return
        registry.rekeyServiceIds()
        handles.reset()
        serviceVersions.clear()
        heldModelDigest = null
        heldMetamodelDigests.clear()
    }

    /**
     * Lets the service drop what an answer that is being thrown away made it hold.
     */
    private fun discard(answer: ServiceMessage, encoder: HeapEncoder) {
        val stale = encoder.sent.keys.filter { it < 0 } +
                ((answer as? ServiceMessage.Result)?.objects?.map { it.id } ?: emptyList())
        if (stale.isNotEmpty()) {
            transport.send(ScriptFunctionsProtocol.encodeClient(ClientMessage.Release(stale, emptyList())))
        }
    }

    /**
     * Makes sure the service holds [model] and its metamodel, sending the metamodel when the
     * service does not hold it at this content, and the model when the service holds a different
     * one or none.
     *
     * @return The id the service holds the model under
     */
    private fun uploadIfNotHeld(model: Model): Long {
        val path = model.metamodelPath
        val metamodel = encodedMetamodels[model.metamodel]?.takeIf { it.wire.path == path }
            ?: ModelEncoder.encodeMetamodel(model.metamodel, path).also { encodedMetamodels[model.metamodel] = it }
        if (heldMetamodelDigests[path] != metamodel.digest) {
            transport.send(ScriptFunctionsProtocol.encodeClient(ClientMessage.MetamodelPut(metamodel.wire)))
            heldMetamodelDigests[path] = metamodel.digest
            // A metamodel replacing one under the held model's path drops that model on the service.
            heldModelDigest = null
        }
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
         * The collections of this call by id, with the type each was first reached as.
         */
        val sent = LinkedHashMap<Long, Any>()
        val declaredTypes = HashMap<Long, ReturnType>()

        fun encode(value: Any?, declared: ReturnType, functionName: String): WireValue = when (value) {
            null -> WireValue.Null
            is ScriptRecord -> {
                val recordClass = classesByJvmName[value.javaClass.name]
                    ?: throw ExternalCallException(
                        "External function '$functionName' was passed a ${value.recordName}, which its contribution does not define"
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
                val opaqueClass = classesByJvmName[value.javaClass.name]
                    ?: throw ExternalCallException(
                        "External function '$functionName' was passed a ${value.className}, which its contribution does not define"
                    )
                if (!handles.isCurrent(value.handle, value)) {
                    throw ExternalCallException(
                        "External function '$functionName' was passed a ${opaqueClass.name} whose state was held by " +
                                "a connection to the service that was lost; create it again after a reconnect"
                    )
                }
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
            is HeapCollection, is HeapMap -> {
                val id = registry.idFor(value)
                if (id !in sent) {
                    sent[id] = value
                    declaredTypes[id] = declared
                    if (value is HeapMap) {
                        for ((k, v) in value.heapEntries()) {
                            encode(k, DeclaredTypes.keyType(declared), functionName)
                            encode(v, DeclaredTypes.valueType(declared), functionName)
                        }
                    } else {
                        for (element in (value as HeapCollection).heapSnapshot()) {
                            encode(element, DeclaredTypes.elementType(declared), functionName)
                        }
                    }
                }
                WireValue.Ref(id)
            }
            else -> WireScalars.encode(value)
                ?: enumValues.encode(value, model?.metamodel)
                ?: throw ExternalCallException(
                    "External function '$functionName' was passed a ${value::class.simpleName}, which " +
                            "script-functions version ${ScriptFunctionsProtocol.VERSION} cannot carry. " +
                            "Only scalars, strings, model instances, enum values, the contribution's records " +
                            "and opaque classes, and collections of those can be passed."
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
                is HeapMap -> HeapObject(
                    id, HeapKind.MAP, version,
                    entries = if (known) null else value.heapEntries().flatMap { (k, v) ->
                        listOf(
                            encode(k, DeclaredTypes.keyType(declared), ""),
                            encode(v, DeclaredTypes.valueType(declared), "")
                        )
                    }
                )
                else -> HeapObject(
                    id, kindOf(value), version,
                    elements = if (known) null else (value as HeapCollection).heapSnapshot()
                        .map { encode(it, DeclaredTypes.elementType(declared), "") }
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
            if (value is WireValue.EnumValue && enumValues.decode(value, classLoader) == null) {
                reject("returned the enum value ${value.enumName}.${value.entry}, which the metamodel does not declare")
            }
        }

        private fun reject(reason: String): Nothing =
            throw ExternalCallException(
                "External function '${spec.functionName}' returned a result that was rejected and not applied: " +
                        "the service $reason"
            )

        /**
         * Builds the collections of a validated result and returns the call's value.
         */
        fun apply(result: ServiceMessage.Result): Any? {
            for (obj in result.objects) {
                val instance = instantiate(obj.kind)
                created[obj.id] = instance
            }
            for (obj in result.objects) {
                val instance = created.getValue(obj.id)
                if (instance is HeapMap) {
                    instance.heapReplace(obj.entries.orEmpty().chunked(2).map { (k, v) -> decode(k, null) to decode(v, null) })
                } else {
                    (instance as HeapCollection).heapReplace(obj.elements.orEmpty().map { decode(it, null) })
                }
            }

            val reshaped = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
            val value = conform(decode(result.value, spec.returnType), spec.returnType, reshaped)

            // What the execution holds is exactly what the service holds, except for collections
            // rebuilt as their declared kind: those are sent in full next time.
            for ((id, instance) in created) {
                registry.register(instance, id)
                if (instance !in reshaped) serviceVersions[id] = versionOf(instance)
            }
            return value
        }

        /**
         * Rebuilds the collections this result created as the kinds the signature declares, at every
         * level: a service may return a list where a `Set<List<int>>` is declared, and only the
         * declaration says what the script expects.
         *
         * Collections the script already held keep their kind; they came from the script.
         *
         * @param value A decoded value
         * @param declared Its declared type
         * @param reshaped Collects the created collections that were changed or replaced
         * @return The value, as its declared kind
         */
        private fun conform(value: Any?, declared: ReturnType, reshaped: MutableSet<Any>): Any? {
            if (value !is HeapCollection && value !is HeapMap) return value
            if (created.values.none { it === value } || value in reshaped) return value
            // Marked before descending, so a collection that contains itself is visited once.
            reshaped += value

            var contentChanged = false
            if (value is HeapMap) {
                val entries = value.heapEntries()
                val conformed = entries.map { (k, v) ->
                    conform(k, DeclaredTypes.keyType(declared), reshaped) to conform(v, DeclaredTypes.valueType(declared), reshaped)
                }
                if (conformed.indices.any { conformed[it].first !== entries[it].first || conformed[it].second !== entries[it].second }) {
                    value.heapReplace(conformed)
                    contentChanged = true
                }
            } else {
                val elements = (value as HeapCollection).heapSnapshot()
                val conformed = elements.map { conform(it, DeclaredTypes.elementType(declared), reshaped) }
                if (conformed.indices.any { conformed[it] !== elements[it] }) {
                    value.heapReplace(conformed)
                    contentChanged = true
                }
            }

            val converted = convertToDeclared(value, declared)
            if (converted === value && !contentChanged) reshaped -= value
            return converted
        }

        private fun decode(value: WireValue, expected: ReturnType?): Any? {
            val decoded: Any? = when (value) {
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
                is WireValue.EnumValue -> enumValues.decode(value, classLoader)
                else -> WireScalars.decode(value)
            }
            return coerceNumber(decoded, expected)
        }
    }

    companion object {
        private fun versionOf(value: Any): Long = when (value) {
            is HeapMap -> value.heapVersion
            else -> (value as HeapCollection).heapVersion
        }

        private fun kindOf(value: Any): HeapKind = when (value) {
            is HeapMap -> HeapKind.MAP
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
            if (value !is HeapCollection || declared !is ClassTypeRef || declared.`package` != "builtin") return value
            val elements = value.heapSnapshot()
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
