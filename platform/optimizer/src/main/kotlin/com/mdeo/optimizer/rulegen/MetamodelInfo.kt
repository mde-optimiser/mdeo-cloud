package com.mdeo.optimizer.rulegen

import com.mdeo.metamodel.data.AssociationData
import com.mdeo.metamodel.data.ClassData
import com.mdeo.metamodel.data.EnumData
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.metamodel.data.PropertyData

/**
 * One end of an association, seen from the class whose objects hold it.
 *
 * For `A.x[n..m] <--> B.y[k..l]` the association has two ends: `x` owned by `A` pointing at `B`
 * with bounds `n..m`, and `y` owned by `B` pointing at `A` with bounds `k..l`. Each is the other's
 * [opposite].
 *
 * An end without a property name cannot be navigated and carries no multiplicity in the language,
 * so it is unconstrained (`0..*`).
 *
 * @param association Index of the association in [MetamodelData.associations].
 * @param owner The class at this end's side, i.e. the class whose objects hold the property.
 * @param name The property name, or `null` for an unnamed end.
 * @param target The class at the other side.
 * @param lower The effective lower bound, after refinements.
 * @param upper The effective upper bound (-1 = unbounded), after refinements.
 * @param isSourceSide Whether [owner] is the association's left-hand (source) class.
 * @param isContainment Whether [owner] is the whole of a composition, so this end holds its parts.
 * @param isContainer Whether [owner] is the part of a composition, so this end points at its whole.
 * @param isSingle Whether the language types the end as one optional object rather than a list:
 *   the metamodel declares it with an upper bound of 1. Refinements leave this unchanged.
 */
class AssociationEnd internal constructor(
    val association: Int,
    val owner: String,
    val name: String?,
    val target: String,
    val lower: Int,
    val upper: Int,
    val isSourceSide: Boolean,
    val isContainment: Boolean,
    val isContainer: Boolean,
    val isSingle: Boolean = false
) {
    /** The other end of the same association. */
    lateinit var opposite: AssociationEnd
        internal set

    /** Whether the bounds admit exactly one count (`lower == upper`). */
    val isFixed: Boolean get() = lower == upper

    /** Whether the upper bound is finite. */
    val isBounded: Boolean get() = upper != -1

    override fun toString(): String =
        "$owner.${name ?: "<unnamed>"}[$lower..${if (upper == -1) "*" else upper}] -> $target"
}

/**
 * A composition that can contain objects of some class.
 *
 * @param containmentEnd The end owned by the whole, holding the parts; possibly unnamed.
 * @param containerEnd The end owned by the part, pointing at the whole; always named.
 */
data class ContainerContext(
    val containmentEnd: AssociationEnd,
    val containerEnd: AssociationEnd
) {
    /** The class of the whole. */
    val wholeClass: String get() = containmentEnd.owner
}

/**
 * A multiplicity refinement: tighter bounds for one association end, for the search only.
 *
 * @param className The class declaring the end.
 * @param refName The end's property name.
 * @param lower The refined lower bound.
 * @param upper The refined upper bound (-1 = unbounded).
 */
data class MultiplicityOverride(
    val className: String,
    val refName: String,
    val lower: Int,
    val upper: Int
)

/**
 * Raised when the operators asked for cannot be generated: an unknown class or end, an invalid
 * refinement, or a selection the generator does not support.
 */
class RuleGenerationException(message: String) : RuntimeException(message)

/**
 * The structure of a metamodel as the operator generator sees it: classes with their inheritance,
 * properties and association ends, with refinements applied.
 *
 * Every query that names a class covers the ends and properties it inherits, and every end whose
 * owner or target is a supertype applies to subclasses too.
 */
class MetamodelInfo private constructor(
    val metamodelPath: String,
    private val classesByName: Map<String, ClassData>,
    private val enumsByName: Map<String, EnumData>,
    private val ends: List<AssociationEnd>,
    private val supertypes: Map<String, Set<String>>,
    private val subtypes: Map<String, Set<String>>
) {

    /** The refined ends, in the order the refinements were given. */
    var refinedEnds: List<AssociationEnd> = emptyList()
        private set

    /** Both ends of every association, named or not. */
    val allEnds: List<AssociationEnd> get() = ends

    /** Whether [className] is a class of the metamodel. */
    fun hasClass(className: String): Boolean = className in classesByName

    /** Whether [className] is abstract. */
    fun isAbstract(className: String): Boolean = classesByName.getValue(className).isAbstract

    /** [className] and all its transitive supertypes. */
    fun supertypesOf(className: String): Set<String> = supertypes[className] ?: setOf(className)

    /** [className] and all its transitive subtypes. */
    fun subtypesOf(className: String): Set<String> = subtypes[className] ?: setOf(className)

    /** Whether an object of one of the classes can also be an object of the other. */
    fun compatible(a: String, b: String): Boolean = b in supertypesOf(a) || b in subtypesOf(a)

    /** The non-abstract classes among [className] and its subtypes, in declaration order. */
    fun concreteSubtypesOf(className: String): List<String> {
        val subs = subtypesOf(className)
        return classesByName.values.filter { it.name in subs && !it.isAbstract }.map { it.name }
    }

    /** The path of the file declaring [className]. */
    fun pathOf(className: String): String = classesByName[className]?.path ?: metamodelPath

    /** The path of the file declaring the enum [enumName]. */
    fun enumPathOf(enumName: String): String = enumsByName[enumName]?.path ?: metamodelPath

    /** The entries of the enum [enumName]. */
    fun enumEntries(enumName: String): List<String> = enumsByName[enumName]?.entries ?: emptyList()

    /** The attributes of [className], including inherited ones. */
    fun propertiesOf(className: String): List<PropertyData> =
        classesByName.values.filter { it.name in supertypesOf(className) }.flatMap { it.properties }

    /** The named ends an object of [className] holds, including inherited ones. */
    fun endsOf(className: String): List<AssociationEnd> {
        val supers = supertypesOf(className)
        return ends.filter { it.name != null && it.owner in supers }
    }

    /** The named end [name] of [className], declared on it or inherited, or `null`. */
    fun endOf(className: String, name: String): AssociationEnd? = endsOf(className).find { it.name == name }

    /**
     * Every named end through which another object may be linked to an object of [className] or
     * of one of its subtypes: the opposites of the ends such an object can hold.
     */
    fun endsPointingAt(className: String): List<AssociationEnd> =
        ends.filter { it.name != null && compatible(it.opposite.owner, className) }

    /** The compositions that can contain an object of exactly [className]. */
    fun containerContextsOf(className: String): List<ContainerContext> {
        val supers = supertypesOf(className)
        return ends.filter { it.isContainer && it.owner in supers }
            .map { ContainerContext(containmentEnd = it.opposite, containerEnd = it) }
    }

    /** The compositions whose parts may be objects of [className] or of one of its subtypes. */
    fun containerEndsCompatibleWith(className: String): List<AssociationEnd> =
        ends.filter { it.isContainer && compatible(it.owner, className) }

    companion object {

        /**
         * Builds the structure of [metamodelData], with [refinements] applied to the ends they
         * name.
         *
         * @throws RuleGenerationException when a refinement names an unknown class or end, an
         *   attribute, an end its class inherits, or loosens the metamodel's bounds.
         */
        operator fun invoke(
            metamodelData: MetamodelData,
            refinements: List<MultiplicityOverride> = emptyList()
        ): MetamodelInfo {
            val classesByName = metamodelData.classes.associateBy { it.name }
            val supertypes = classesByName.keys.associateWith { transitiveSupertypes(it, classesByName) }
            val subtypes = classesByName.keys.associateWith { c -> classesByName.keys.filter { c in supertypes.getValue(it) }.toSet() }

            val overrides = resolveRefinements(metamodelData, classesByName, refinements)
            val ends = metamodelData.associations.flatMapIndexed { index, association ->
                buildEnds(index, association, overrides)
            }
            val info = MetamodelInfo(
                metamodelPath = metamodelData.path,
                classesByName = classesByName,
                enumsByName = metamodelData.enums.associateBy { it.name },
                ends = ends,
                supertypes = supertypes,
                subtypes = subtypes
            )
            info.refinedEnds = refinements.map { r -> ends.first { it.owner == r.className && it.name == r.refName } }
            return info
        }

        private fun transitiveSupertypes(className: String, classesByName: Map<String, ClassData>): Set<String> {
            val result = linkedSetOf(className)
            val pending = ArrayDeque(listOf(className))
            while (pending.isNotEmpty()) {
                val parents = classesByName[pending.removeFirst()]?.extends ?: continue
                for (parent in parents) {
                    if (result.add(parent)) pending.add(parent)
                }
            }
            return result
        }

        private fun buildEnds(
            index: Int,
            association: AssociationData,
            overrides: Map<Pair<String, String>, MultiplicityOverride>
        ): List<AssociationEnd> {
            val wholeOnLeft = association.operator == "*-->" || association.operator == "*--"
            val wholeOnRight = association.operator == "<--*" || association.operator == "--*"

            fun end(own: com.mdeo.metamodel.data.AssociationEndData, other: String, isSource: Boolean): AssociationEnd {
                val name = own.name
                val override = name?.let { overrides[own.className to it] }
                val isWhole = if (isSource) wholeOnLeft else wholeOnRight
                val isPart = if (isSource) wholeOnRight else wholeOnLeft
                return AssociationEnd(
                    association = index,
                    owner = own.className,
                    name = name,
                    target = other,
                    lower = when {
                        name == null -> 0
                        override != null -> override.lower
                        else -> own.multiplicity.lower
                    },
                    upper = when {
                        name == null -> -1
                        override != null -> override.upper
                        else -> own.multiplicity.upper
                    },
                    isSourceSide = isSource,
                    isContainment = isWhole,
                    isContainer = isPart,
                    isSingle = name != null && own.multiplicity.upper == 1
                )
            }

            val left = end(association.source, association.target.className, isSource = true)
            val right = end(association.target, association.source.className, isSource = false)
            left.opposite = right
            right.opposite = left
            return listOf(left, right)
        }

        private fun resolveRefinements(
            metamodelData: MetamodelData,
            classesByName: Map<String, ClassData>,
            refinements: List<MultiplicityOverride>
        ): Map<Pair<String, String>, MultiplicityOverride> {
            val declared = metamodelData.associations.flatMap { listOf(it.source, it.target) }
                .filter { it.name != null }
                .associateBy { it.className to it.name!! }
            val result = mutableMapOf<Pair<String, String>, MultiplicityOverride>()
            for (r in refinements) {
                val where = "refine ${r.className}.${r.refName}"
                val classData = classesByName[r.className]
                    ?: throw RuleGenerationException("$where: unknown class '${r.className}'")
                val end = declared[r.className to r.refName]
                if (end == null) {
                    if (classData.properties.any { it.name == r.refName }) {
                        throw RuleGenerationException("$where: '${r.refName}' is an attribute; only association ends can be refined")
                    }
                    val declaring = declared.keys.firstOrNull { it.second == r.refName && it.first in transitiveSupertypes(r.className, classesByName) }
                    if (declaring != null) {
                        throw RuleGenerationException("$where: the end is declared on '${declaring.first}'; refine it there")
                    }
                    throw RuleGenerationException("$where: '${r.className}' has no association end '${r.refName}'")
                }
                val base = end.multiplicity
                val tightensLower = r.lower >= base.lower
                val tightensUpper = base.upper == -1 || (r.upper != -1 && r.upper <= base.upper)
                if (r.lower < 0 || r.upper == 0 || (r.upper != -1 && r.lower > r.upper)) {
                    throw RuleGenerationException("$where: invalid bounds ${bounds(r.lower, r.upper)}")
                }
                if (!tightensLower || !tightensUpper) {
                    throw RuleGenerationException(
                        "$where: ${bounds(r.lower, r.upper)} does not lie within the metamodel's ${bounds(base.lower, base.upper)}"
                    )
                }
                if (result.put(r.className to r.refName, r) != null) {
                    throw RuleGenerationException("$where: refined more than once")
                }
            }
            return result
        }

        internal fun bounds(lower: Int, upper: Int): String = "[$lower..${if (upper == -1) "*" else upper}]"
    }
}
