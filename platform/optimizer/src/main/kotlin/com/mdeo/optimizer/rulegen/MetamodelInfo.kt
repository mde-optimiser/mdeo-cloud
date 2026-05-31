package com.mdeo.optimizer.rulegen

import com.mdeo.metamodel.data.AssociationData
import com.mdeo.metamodel.data.MetamodelData

/**
 * Summary of the multiplicity bounds on the opposite end of a bidirectional association, from the
 * perspective of a forward [ReferenceInfo].
 *
 * @param lower Lower bound of the opposite end (0 = optional, ≥1 = required).
 * @param upper Upper bound of the opposite end (-1 = unbounded).
 */
data class OppositeInfo(
    val lower: Int,
    val upper: Int
)

/**
 * Describes a single navigable reference from a metamodel class.
 *
 * For a bidirectional association `A.foo <>-> B.bar`:
 * - The *forward* ReferenceInfo for class A has `refName="foo"`, `isReverse=false`, and an
 *   [opposite] with the multiplicity of the B-end.
 * - The *reverse* ReferenceInfo for class B has `refName="bar"`, `isReverse=true`, and an
 *   [opposite] with the multiplicity of the A-end.
 *
 * @param refName       The role name of this end.
 * @param targetClass   The name of the class at the other end.
 * @param isContainment Whether this is the *forward* end of a containment (`<>->`) association.
 *                      The reverse end of a containment is never marked as containment.
 * @param isReverse     `true` when this ReferenceInfo represents the target-end of a bidirectional
 *                      association being exposed on the target class.
 * @param lower         Lower bound of this end's multiplicity.
 * @param upper         Upper bound of this end's multiplicity (-1 = unbounded).
 * @param opposite      Multiplicity of the other end, or `null` for unidirectional associations.
 */
data class ReferenceInfo(
    val refName: String,
    val targetClass: String,
    val isContainment: Boolean,
    val isReverse: Boolean,
    val lower: Int,
    val upper: Int,
    val opposite: OppositeInfo?
)

/**
 * Convenience wrapper around [MetamodelData] that answers structural queries needed by the
 * consistency-preserving operator generation pipeline.
 *
 * Calling [withOverrides] returns a new instance that applies [MultiplicityOverride] values on top
 * of the base metamodel; used for the solution-space ("S_") rule generation pass.
 */
class MetamodelInfo private constructor(
    private val classes: Set<String>,
    private val referencesByClass: Map<String, List<ReferenceInfo>>,
    private val containmentContextsByClass: Map<String, List<Pair<String, String>>>
) {

    /** Returns the names of all non-abstract classes in the metamodel. */
    fun classNames(): List<String> = classes.toList()

    /**
     * Returns all navigable references for [className], including both forward references and the
     * reverse ends of bidirectional associations where [className] is the target.
     */
    fun referencesForNode(className: String): List<ReferenceInfo> =
        referencesByClass[className] ?: emptyList()

    /**
     * Returns the containment contexts for [className]: every `(containerClass, refName)` pair
     * where a `<>->` association has [className] as its target.
     */
    fun containmentContextsFor(className: String): List<Pair<String, String>> =
        containmentContextsByClass[className] ?: emptyList()

    companion object {

        /**
         * Builds a [MetamodelInfo] from the given [metamodelData].
         */
        operator fun invoke(metamodelData: MetamodelData): MetamodelInfo =
            build(metamodelData, emptyList())

        /**
         * Builds a [MetamodelInfo] from [metamodelData] with the multiplicities of specific
         * references replaced by the values in [overrides].
         */
        fun withOverrides(
            metamodelData: MetamodelData,
            overrides: List<MultiplicityOverride>
        ): MetamodelInfo = build(metamodelData, overrides)

        // -----------------------------------------------------------------------
        // Internal builder
        // -----------------------------------------------------------------------

        private fun build(
            metamodelData: MetamodelData,
            overrides: List<MultiplicityOverride>
        ): MetamodelInfo {
            val overrideMap: Map<Pair<String, String>, MultiplicityOverride> =
                overrides.associateBy { it.className to it.refName }

            val classes = metamodelData.classes
                .filter { !it.isAbstract }
                .map { it.name }
                .toSet()

            val referencesByClass = mutableMapOf<String, MutableList<ReferenceInfo>>()
            val containmentContextsByClass = mutableMapOf<String, MutableList<Pair<String, String>>>()

            for (assoc in metamodelData.associations) {
                val isContainment = assoc.operator == "<>->"
                val src = assoc.source
                val tgt = assoc.target

                // Forward reference: src.className → tgt.className via src.name
                val srcName = src.name
                if (srcName != null) {
                    // Apply override if present
                    val srcOverride = overrideMap[src.className to srcName]
                    val srcLower = srcOverride?.lower ?: src.multiplicity.lower
                    val srcUpper = srcOverride?.upper ?: src.multiplicity.upper

                    val tgtName = tgt.name
                    val opposite: OppositeInfo? = if (tgtName != null) {
                        val tgtOverride = overrideMap[tgt.className to tgtName]
                        OppositeInfo(
                            lower = tgtOverride?.lower ?: tgt.multiplicity.lower,
                            upper = tgtOverride?.upper ?: tgt.multiplicity.upper
                        )
                    } else null

                    val ref = ReferenceInfo(
                        refName = srcName,
                        targetClass = tgt.className,
                        isContainment = isContainment,
                        isReverse = false,
                        lower = srcLower,
                        upper = srcUpper,
                        opposite = opposite
                    )
                    referencesByClass.getOrPut(src.className) { mutableListOf() }.add(ref)
                }

                // Reverse reference: tgt.className → src.className via tgt.name
                val tgtName2 = tgt.name
                if (tgtName2 != null) {
                    val tgtOverride = overrideMap[tgt.className to tgtName2]
                    val tgtLower = tgtOverride?.lower ?: tgt.multiplicity.lower
                    val tgtUpper = tgtOverride?.upper ?: tgt.multiplicity.upper

                    val srcName2 = src.name
                    val opposite: OppositeInfo? = if (srcName2 != null) {
                        val srcOverride = overrideMap[src.className to srcName2]
                        OppositeInfo(
                            lower = srcOverride?.lower ?: src.multiplicity.lower,
                            upper = srcOverride?.upper ?: src.multiplicity.upper
                        )
                    } else null

                    val ref = ReferenceInfo(
                        refName = tgtName2,
                        targetClass = src.className,
                        // Reverse end of containment is NOT itself a containment
                        isContainment = false,
                        isReverse = true,
                        lower = tgtLower,
                        upper = tgtUpper,
                        opposite = opposite
                    )
                    referencesByClass.getOrPut(tgt.className) { mutableListOf() }.add(ref)
                }

                // Containment contexts: any <->-> assoc contributes a context for the target class
                val srcNameForCtx = src.name
                if (isContainment && srcNameForCtx != null) {
                    containmentContextsByClass
                        .getOrPut(tgt.className) { mutableListOf() }
                        .add(src.className to srcNameForCtx)
                }
            }

            return MetamodelInfo(
                classes = classes,
                referencesByClass = referencesByClass,
                containmentContextsByClass = containmentContextsByClass
            )
        }

        // Helper to pick the class-level override for a given (class, ref) pair
        private fun pickOverride(
            overrideMap: Map<Pair<String, String>, MultiplicityOverride>,
            className: String?,
            refName: String?,
            fallbackLower: Int,
            fallbackUpper: Int
        ): Pair<Int, Int> {
            if (className == null || refName == null) return fallbackLower to fallbackUpper
            val ov = overrideMap[className to refName] ?: return fallbackLower to fallbackUpper
            return ov.lower to ov.upper
        }
    }
}
