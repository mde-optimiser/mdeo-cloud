package com.mdeo.script.external

import com.mdeo.metamodel.Metamodel
import com.mdeo.metamodel.Model
import com.mdeo.metamodel.ModelInstance
import com.mdeo.scriptfunctions.protocol.ScriptFunctionsProtocol
import com.mdeo.scriptfunctions.protocol.WireAssociation
import com.mdeo.scriptfunctions.protocol.WireAssociationEnd
import com.mdeo.scriptfunctions.protocol.WireAttribute
import com.mdeo.scriptfunctions.protocol.WireClass
import com.mdeo.scriptfunctions.protocol.WireEnum
import com.mdeo.scriptfunctions.protocol.WireInstance
import com.mdeo.scriptfunctions.protocol.WireMetamodel
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
 * A metamodel as it is sent, and the digest that identifies its content.
 *
 * @property wire The metamodel on the wire
 * @property digest SHA-256 of the encoded metamodel
 */
internal class EncodedMetamodel(val wire: WireMetamodel, val digest: String)

/**
 * Turns a [Model] into the [WireModel] a service reads, and its [Metamodel] into the
 * [WireMetamodel] the model refers to.
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
        val wire = WireModel(model.metamodelPath, instances)
        return EncodedModel(wire, digest(ScriptFunctionsProtocol.cbor.encodeToByteArray(wire)))
    }

    /**
     * Encodes a metamodel.
     *
     * @param metamodel The metamodel
     * @param path The path models of it name, which is where it was loaded from
     * @return The encoded metamodel and its digest
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun encodeMetamodel(metamodel: Metamodel, path: String): EncodedMetamodel {
        val data = metamodel.data
        val wire = WireMetamodel(
            path = path,
            classes = data.classes.map { classData ->
                WireClass(
                    name = classData.name,
                    isAbstract = classData.isAbstract,
                    extends = classData.extends,
                    attributes = classData.properties.map { property ->
                        WireAttribute(
                            name = property.name,
                            type = property.enumType ?: property.primitiveType.orEmpty(),
                            isEnum = property.enumType != null,
                            lower = property.multiplicity.lower,
                            upper = property.multiplicity.upper
                        )
                    }
                )
            },
            enums = data.enums.map { WireEnum(it.name, it.entries) },
            associations = data.associations.map { association ->
                WireAssociation(
                    source = with(association.source) {
                        WireAssociationEnd(className, name, multiplicity.lower, multiplicity.upper)
                    },
                    operator = association.operator,
                    target = with(association.target) {
                        WireAssociationEnd(className, name, multiplicity.lower, multiplicity.upper)
                    }
                )
            },
            subtypes = metamodel.metadata.classHierarchy.mapValues { it.value.sorted() }.toSortedMap()
        )
        return EncodedMetamodel(wire, digest(ScriptFunctionsProtocol.cbor.encodeToByteArray(wire)))
    }

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

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
