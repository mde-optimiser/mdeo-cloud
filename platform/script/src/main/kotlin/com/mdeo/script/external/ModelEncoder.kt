package com.mdeo.script.external

import com.mdeo.metamodel.Model
import com.mdeo.metamodel.ModelInstance
import com.mdeo.scriptfunctions.protocol.ScriptFunctionsProtocol
import com.mdeo.scriptfunctions.protocol.WireInstance
import com.mdeo.scriptfunctions.protocol.WireModel
import com.mdeo.scriptfunctions.protocol.WireValue
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToByteArray
import java.lang.reflect.Method
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * A model as it is uploaded, and the digest that identifies its content.
 *
 * @property wire The model on the wire
 * @property digest SHA-256 of the encoded model. Two models with the same digest are the same model
 *           to a service, however they were built.
 */
internal class EncodedModel(val wire: WireModel, val digest: String)

/**
 * Turns a [Model] into the [WireModel] a service reads.
 *
 * Models are compared by content rather than by object: an optimizer builds a fresh [Model] for
 * every guidance function it evaluates on the same solution, and those must not be uploaded again.
 * Everything that could vary between two builds of the same content — the order of a reference
 * set, above all — is sorted, so equal content encodes to equal bytes.
 */
internal object ModelEncoder {

    private val entryGetters = ConcurrentHashMap<Class<*>, Method>()

    /**
     * Encodes a model.
     *
     * @param model The model
     * @return The encoded model and its digest
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun encode(model: Model): EncodedModel {
        val metadata = model.metamodel.metadata
        val instances = model.instancesByName.map { (name, instance) ->
            val className = model.metamodel.classNameOf(instance)
            val classMetadata = metadata.classes[className]
            val attributes = classMetadata?.propertyFields.orEmpty().mapValues { (key, mapping) ->
                val raw = instance.getPropertyByKey(key)
                when {
                    raw == null -> emptyList()
                    mapping.isCollection -> (raw as? Collection<*>).orEmpty().map(::scalar)
                    else -> listOf(scalar(raw))
                }
            }
            val references = classMetadata?.linkFields.orEmpty().mapValues { (key, _) ->
                when (val raw = instance.getPropertyByKey(key)) {
                    is Collection<*> -> raw.filterIsInstance<ModelInstance>().mapNotNull(model::nameOf).sorted()
                    is ModelInstance -> listOfNotNull(model.nameOf(raw))
                    else -> emptyList()
                }
            }
            WireInstance(name, className, attributes, references)
        }
        val subtypes = metadata.classHierarchy.mapValues { it.value.sorted() }.toSortedMap()
        val wire = WireModel(model.metamodelPath, subtypes, instances)

        val bytes = ScriptFunctionsProtocol.cbor.encodeToByteArray(wire)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return EncodedModel(wire, digest)
    }

    private fun scalar(value: Any?): WireValue = when (value) {
        null -> WireValue.Null
        is Boolean -> WireValue.Bool(value)
        is Int -> WireValue.IntValue(value)
        is Long -> WireValue.LongValue(value)
        is Float -> WireValue.FloatValue(value)
        is Double -> WireValue.DoubleValue(value)
        is String -> WireValue.StringValue(value)
        // Generated enum values name their entry through getEntry().
        else -> WireValue.StringValue(
            runCatching {
                entryGetters.getOrPut(value.javaClass) { value.javaClass.getMethod("getEntry") }.invoke(value) as String
            }.getOrElse { value.toString() }
        )
    }
}
