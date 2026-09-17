package com.mdeo.scriptfunctions.service

import com.mdeo.scriptfunctions.protocol.WireAssociationEnd
import com.mdeo.scriptfunctions.protocol.WireMetamodel
import java.util.concurrent.ConcurrentHashMap

/**
 * A metamodel the models of a session are instances of, readonly.
 *
 * The execution sends each metamodel once per session, so everything derived from it — in
 * [cache] — is computed once and survives every model the session moves through.
 *
 * @property path The metamodel's path, which its models name as [ScriptModel.metamodelPath]
 */
class ScriptMetamodel internal constructor(wire: WireMetamodel) {

    val path: String = wire.path

    /**
     * Every class, by name.
     */
    val classes: Map<String, MetamodelClass> = wire.classes.associate { wireClass ->
        wireClass.name to MetamodelClass(
            name = wireClass.name,
            isAbstract = wireClass.isAbstract,
            extends = wireClass.extends,
            attributes = wireClass.attributes.map {
                MetamodelAttribute(it.name, it.type, it.isEnum, Multiplicity(it.lower, it.upper))
            }
        )
    }

    /**
     * The entries of every enum, by enum name.
     */
    val enums: Map<String, List<String>> = wire.enums.associate { it.name to it.entries }

    /**
     * Every association, in declaration order.
     */
    val associations: List<MetamodelAssociation> = wire.associations.map {
        MetamodelAssociation(end(it.source), it.operator, end(it.target))
    }

    /**
     * Scratch space for whatever an operation derives from this metamodel. It lives as long as the
     * session.
     */
    val cache: MutableMap<Any, Any?> = ConcurrentHashMap()

    private val subtypes: Map<String, Set<String>> = wire.subtypes.mapValues { it.value.toSet() }

    /**
     * Returns a class and every class that inherits from it, directly or not.
     *
     * @param className The class name
     * @return The class names, including [className] itself
     */
    fun subtypesOf(className: String): Set<String> = subtypes[className] ?: setOf(className)

    private fun end(wire: WireAssociationEnd) =
        AssociationEnd(wire.className, wire.name, Multiplicity(wire.lower, wire.upper))

    override fun toString(): String = "metamodel $path"
}

/**
 * One class of a [ScriptMetamodel].
 *
 * @property name The class name
 * @property isAbstract Whether the class has no instances of its own
 * @property extends The classes it directly inherits from
 * @property attributes The attributes it declares itself, without inherited ones
 */
data class MetamodelClass(
    val name: String,
    val isAbstract: Boolean,
    val extends: List<String>,
    val attributes: List<MetamodelAttribute>
)

/**
 * One attribute of a [MetamodelClass].
 *
 * @property name The attribute name
 * @property type A primitive type name (`int`, `long`, `float`, `double`, `boolean`, `string`), or
 *           the name of an enum when [isEnum] is set
 * @property isEnum Whether [type] names an enum
 * @property multiplicity How many values it holds
 */
data class MetamodelAttribute(
    val name: String,
    val type: String,
    val isEnum: Boolean,
    val multiplicity: Multiplicity
)

/**
 * One association of a [ScriptMetamodel].
 *
 * @property source The end on the left of the operator
 * @property operator The operator as written in the metamodel, such as `<-->` or `*-->`
 * @property target The end on the right of the operator
 */
data class MetamodelAssociation(
    val source: AssociationEnd,
    val operator: String,
    val target: AssociationEnd
)

/**
 * One end of a [MetamodelAssociation].
 *
 * @property className The class at this end
 * @property name The property through which instances of [className] refer to the instances at the
 *           other end; null when this end has none. Read it with [ScriptModelInstance.references].
 * @property multiplicity How many instances at the other end one instance of [className] refers to
 */
data class AssociationEnd(
    val className: String,
    val name: String?,
    val multiplicity: Multiplicity
)

/**
 * The bounds of how many values something holds.
 *
 * @property lower The least number
 * @property upper The greatest number, `-1` for unbounded
 */
data class Multiplicity(val lower: Int, val upper: Int) {

    /**
     * Whether more than one value is allowed.
     */
    val isMany: Boolean get() = upper == -1 || upper > 1
}
