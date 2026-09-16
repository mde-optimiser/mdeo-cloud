package com.mdeo.scriptfunctions.service

import com.mdeo.scriptfunctions.protocol.WireModel
import com.mdeo.scriptfunctions.protocol.WireValue
import java.util.concurrent.ConcurrentHashMap

/**
 * The model a call works on, readonly.
 *
 * A service holds one model per session. When the execution moves on to another model — the next
 * solution an optimizer evaluates, for example — this one is dropped together with everything in
 * [cache], so nothing computed for one model is ever seen while working on another.
 *
 * @property metamodelPath The metamodel the model is an instance of
 */
class ScriptModel internal constructor(wire: WireModel) {

    val metamodelPath: String = wire.metamodelPath

    private val subtypes: Map<String, Set<String>> = wire.subtypes.mapValues { it.value.toSet() }

    /**
     * Every instance, by name.
     */
    val instances: Map<String, ScriptModelInstance>

    /**
     * Scratch space for whatever an operation derives from this model and wants to reuse on later
     * calls working on the same model: an index, a distance matrix. It is dropped with the model.
     */
    val cache: MutableMap<Any, Any?> = ConcurrentHashMap()

    init {
        val built = LinkedHashMap<String, ScriptModelInstance>(wire.instances.size * 2)
        for (instance in wire.instances) {
            built[instance.name] = ScriptModelInstance(
                name = instance.name,
                className = instance.className,
                attributeValues = instance.attributes.mapValues { (_, values) -> values.map(::scalar) },
                referenceNames = instance.references,
                model = this
            )
        }
        instances = built
    }

    /**
     * Returns every instance of a class, including instances of its subclasses.
     *
     * @param className The class name as the metamodel declares it
     * @return The instances, in model order
     */
    fun instancesOf(className: String): List<ScriptModelInstance> {
        val classes = subtypes[className] ?: setOf(className)
        return instances.values.filter { it.className in classes }
    }

    private fun scalar(value: WireValue): Any? = when (value) {
        WireValue.Null -> null
        is WireValue.Bool -> value.value
        is WireValue.IntValue -> value.value
        is WireValue.LongValue -> value.value
        is WireValue.FloatValue -> value.value
        is WireValue.DoubleValue -> value.value
        is WireValue.StringValue -> value.value
        is WireValue.Ref, is WireValue.InstanceValue, is WireValue.RecordValue, is WireValue.HandleValue -> null
    }
}

/**
 * One instance of a [ScriptModel], readonly.
 *
 * Instances are compared by identity, and an instance passed to an operation as an argument is the
 * same object as the one in [ScriptModel.instances].
 *
 * @property name The instance's name, unique within its model
 * @property className The instance's class
 * @property model The model it belongs to
 */
class ScriptModelInstance internal constructor(
    val name: String,
    val className: String,
    private val attributeValues: Map<String, List<Any?>>,
    private val referenceNames: Map<String, List<String>>,
    val model: ScriptModel
) {
    /**
     * Returns a single-valued attribute: an `Int`, `Long`, `Float`, `Double`, `Boolean` or `String`,
     * with enum values given as the entry's name.
     *
     * @param name The attribute name
     * @return Its value, or null when it is unset
     */
    fun attribute(name: String): Any? = attributeValues[name]?.firstOrNull()

    /**
     * Returns every value of a multi-valued attribute.
     *
     * @param name The attribute name
     * @return The values, empty when there are none
     */
    fun attributes(name: String): List<Any?> = attributeValues[name].orEmpty()

    /**
     * Returns the instance a single-valued association end refers to.
     *
     * @param name The association end
     * @return The referenced instance, or null when there is none
     */
    fun reference(name: String): ScriptModelInstance? = references(name).firstOrNull()

    /**
     * Returns every instance an association end refers to.
     *
     * @param name The association end
     * @return The referenced instances
     */
    fun references(name: String): List<ScriptModelInstance> =
        referenceNames[name].orEmpty().mapNotNull { model.instances[it] }

    override fun toString(): String = "$className $name"
}
