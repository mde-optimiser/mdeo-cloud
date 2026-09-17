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
 * @property metamodel The metamodel the model is an instance of
 */
class ScriptModel internal constructor(wire: WireModel, val metamodel: ScriptMetamodel) {

    /**
     * The path of the metamodel the model is an instance of.
     */
    val metamodelPath: String get() = metamodel.path

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
        val classes = metamodel.subtypesOf(className)
        return instances.values.filter { it.className in classes }
    }

    /**
     * Every link of the model, each exactly once however many of its ends have a property: a link
     * of a `<-->` association is listed once, not once from each side. Links follow the
     * associations of the metamodel in declaration order.
     */
    val links: List<ScriptModelLink> by lazy {
        metamodel.associations.flatMap { association ->
            val source = association.source
            val target = association.target
            when {
                source.name != null -> instancesOf(source.className).flatMap { from ->
                    from.references(source.name).map { to -> ScriptModelLink(association, from, to) }
                }
                target.name != null -> instancesOf(target.className).flatMap { to ->
                    to.references(target.name).map { from -> ScriptModelLink(association, from, to) }
                }
                else -> emptyList()
            }
        }
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
 * One link of a [ScriptModel]: a pair of instances an association connects.
 *
 * @property association The association
 * @property source The instance at the association's source end
 * @property target The instance at the association's target end
 */
data class ScriptModelLink(
    val association: MetamodelAssociation,
    val source: ScriptModelInstance,
    val target: ScriptModelInstance
)

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
