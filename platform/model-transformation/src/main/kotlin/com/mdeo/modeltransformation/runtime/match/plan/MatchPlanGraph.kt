package com.mdeo.modeltransformation.runtime.match.plan

import com.mdeo.expression.ast.expressions.TypedExpression
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLinkElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstance
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstanceElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternVariableElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternWhereClauseElement
import com.mdeo.modeltransformation.runtime.match.ExpressionNodeAnalyzer
import com.mdeo.modeltransformation.runtime.match.Island
import com.mdeo.modeltransformation.runtime.match.IslandGrouper
import com.mdeo.modeltransformation.runtime.match.IslandTraversalUtils
import com.mdeo.modeltransformation.runtime.match.PatternCategories

/**
 * A condition island that has not yet been emitted into the match plan.
 *
 * @property island The structural island describing the condition sub-pattern (instances
 *           and links exclusive to the condition).
 * @property isNegative `true` for a NAC (negative application condition / forbid);
 *           `false` for a PAC (positive application condition / require).
 */
internal data class PendingCondition(
    val island: Island,
    val isNegative: Boolean
)

/**
 * Immutable representation of the match problem as a typed graph.
 *
 * All structural data (instances, links, conditions, variables) and all precomputed
 * dependency information are stored in this class.  The planning algorithm in
 * [MatchPlanBuilder.PlanExecution] is a pure consumer of this graph: every piece of
 * mutable execution state lives in [PlanExecution], never here.
 *
 * Instances are created exclusively via the [create] factory.
 *
 * @property instances Merged list of all main-pattern (matchable + delete) instance
 *           elements, deduplicated by name.
 * @property links All main-pattern link elements (matchable + delete).
 * @property variables All pattern variable elements.
 * @property whereClauses All where-clause filter elements.
 * @property referencedInstances Names of instances referenced from expressions that are
 *           not themselves matchable (e.g. context objects bound by the caller).
 * @property conditions Ordered list of all pending application conditions (NAC + PAC),
 *           with NAC conditions listed before PAC conditions.
 * @property forbidIslands Structural islands derived from forbid (NAC) elements,
 *           including synthetic single-link islands for orphan forbid links.  Used by
 *           [canOmitNacInjectiveConstraint].
 * @property variableNodeDeps Transitive instance-level dependencies per variable name,
 *           as computed by [computeVariableNodeDeps].
 * @property variableVarDeps Direct variable-to-variable dependencies per variable name,
 *           as computed by [computeVariableVarDeps].
 * @property instancePriorities Greedy traversal-priority score per instance name, as
 *           computed by [computeInstancePriorities].  Higher = more selective.
 * @property injectivePairs All pairs of same-class main-pattern instances that must be
 *           bound to distinct vertices, as computed by [computeInjectivePairs].
 * @property metamodelData The metamodel used for association lookups and BFS ordering
 *           inside application conditions.
 * @property getVertexId Function returning a pre-bound vertex ID for an instance name,
 *           or `null` if the instance must be found by graph traversal.
 * @property nodeAnalyzer Analyser that extracts the set of node names referenced by an
 *           expression AST node.
 * @property isCollectionExpression Predicate that returns `true` when an expression
 *           evaluates to a collection type.  Collection-typed expressions cannot be
 *           emitted as simple vertex-property filters.
 */
internal class MatchPlanGraph(
    val instances: List<TypedPatternObjectInstanceElement>,
    val links: List<TypedPatternLinkElement>,
    val variables: List<TypedPatternVariableElement>,
    val whereClauses: List<TypedPatternWhereClauseElement>,
    val referencedInstances: Set<String>,
    val conditions: List<PendingCondition>,
    val forbidIslands: List<Island>,
    val variableNodeDeps: Map<String, Set<String>>,
    val variableVarDeps: Map<String, Set<String>>,
    val instancePriorities: Map<String, Int>,
    val injectivePairs: List<Pair<String, String>>,
    val metamodelData: MetamodelData,
    val getVertexId: (String) -> Any?,
    val nodeAnalyzer: ExpressionNodeAnalyzer,
    val isCollectionExpression: (TypedExpression) -> Boolean
) {
    /** Fast lookup of an instance element by name; derived from [instances]. */
    val instanceMap: Map<String, TypedPatternObjectInstanceElement> =
        instances.associateBy { it.objectInstance.name }
    /** Set of all matchable instance names; derived from [instances]. */
    val matchableNames: Set<String> =
        instances.map { it.objectInstance.name }.toSet()
    /** Set of all variable names; derived from [variables]. */
    val variableNames: Set<String> =
        variables.map { it.variable.name }.toSet()

    /**
     * Resolves a set of raw referenced node names to the set of *instance* names that
     * must be covered before any expression over [referencedNodes] can be evaluated.
     *
     * Each name in [referencedNodes] is classified:
     * - If it is a *variable* name, its transitive instance dependencies (from
     *   [variableNodeDeps]) are added to the result.
     * - If it is an *instance* name, it is added directly.
     *
     * This function is used when deciding whether a deferred property constraint is
     * ready to be promoted to an inline constraint.
     *
     * @param referencedNodes Raw set of node names extracted from an expression.
     * @return The set of instance names that must all be in the covered set before the
     *         expression is safe to evaluate inline.
     */
    fun resolveTransitiveNodeDeps(referencedNodes: Set<String>): Set<String> {
        val result = mutableSetOf<String>()
        for (name in referencedNodes) {
            if (name in variableNames) result.addAll(variableNodeDeps[name] ?: emptySet())
            else result.add(name)
        }
        return result
    }

    /**
     * Returns the set of main-pattern instance names that share a metamodel class with
     * at least one instance in [island].
     *
     * Before an application condition for [island] can be emitted, every instance in
     * this set must already be covered so that injective inequality constraints between
     * the condition's nodes and the same-class main-pattern nodes can be expressed as
     * `where(neq(...))` checks against already-labelled Gremlin traverser steps.
     *
     * @param island The condition island whose injective requirements are computed.
     * @return The set of main-pattern instance names that require prior coverage.
     */
    fun computeInjectiveRequiredNodes(island: Island): Set<String> {
        val result = mutableSetOf<String>()
        for (islandInst in island.instances) {
            val islandClass = islandInst.objectInstance.className ?: continue
            for (mainInst in instances) {
                if (mainInst.objectInstance.className == islandClass)
                    result.add(mainInst.objectInstance.name)
            }
        }
        return result
    }

    /**
     * Computes the full set of main-pattern instance names that must be covered before
     * [pending] can be emitted.
     *
     * The required set is the union of:
     * 1. **Anchor nodes** — main-pattern instances that appear as endpoints of island
     *    links, forming the structural attachment points for the condition sub-traversal.
     * 2. **Injective-required nodes** — main-pattern instances that share a class with an
     *    island node and therefore need injective inequality constraints.  For single-node
     *    NACs the set may be smaller due to the [canOmitNacInjectiveConstraint]
     *    optimisation.
     *
     * @param pending The condition whose readiness requirements are to be determined.
     * @return The set of main-pattern instance names that must all be covered before this
     *         condition can be safely emitted.
     */
    fun pendingConditionRequiredNodes(pending: PendingCondition): Set<String> {
        val island = pending.island
        val islandNames = island.instances.map { it.objectInstance.name }.toSet()
        val anchors = IslandTraversalUtils.findAnchorNames(island.links, islandNames, matchableNames)
        val isSingleNodeNac = island.instances.size == 1
        val injectiveRequired = computeInjectiveRequiredNodes(island).filter { mainInstName ->
            if (!isSingleNodeNac) return@filter true
            val islandNode = island.instances.firstOrNull()?.objectInstance?.name ?: return@filter true
            !canOmitNacInjectiveConstraint(islandNode, mainInstName, island.links)
        }.toSet()
        return anchors + injectiveRequired
    }

    /**
     * Returns `true` when the injective inequality constraint `xName ≠ zName` is
     * redundant for a single-node NAC island and can be safely omitted.
     *
     * **Correctness argument:** Suppose the NAC island has exactly one node X, and the
     * main pattern contains a node Z of the same class.  The constraint `X ≠ Z` prevents
     * the NAC from firing when X and Z map to the same vertex V.  However, the NAC fires
     * on V only when V is connected via a NAC edge (X–Yᵢ) to some neighbour Yᵢ.  If
     * there exists a *forbid orphan link* (Z–Yᵢ) with the same edge label and direction,
     * the overall match is rejected the moment that edge is found — independently of
     * whether X = Z.  Therefore `X ≠ Z` is redundant: the rejection is already
     * guaranteed by the orphan-link check.
     *
     * The optimisation is conservative: it returns `true` only when *every* NAC edge
     * (X–Yᵢ) has a matching forbid orphan link (Z–Yᵢ); returns `false` as soon as one
     * NAC edge lacks such coverage.
     *
     * @param xName The single island node in the NAC.
     * @param zName The main-pattern node of the same class as [xName].
     * @param nacIslandLinks The links of the NAC island (all incident on [xName]).
     * @return `true` if the constraint can be omitted without affecting correctness.
     */
    fun canOmitNacInjectiveConstraint(
        xName: String,
        zName: String,
        nacIslandLinks: List<TypedPatternLinkElement>
    ): Boolean {
        for (nacLink in nacIslandLinks) {
            val xIsSrc = nacLink.link.source.objectName == xName
            val yiName = if (xIsSrc) nacLink.link.target.objectName else nacLink.link.source.objectName
            val srcProp = nacLink.link.source.propertyName
            val tgtProp = nacLink.link.target.propertyName
            for (orphanIsland in forbidIslands) {
                if (orphanIsland.instances.isNotEmpty()) continue
                val orphanLink = orphanIsland.links.singleOrNull() ?: continue
                if (orphanLink.link.source.propertyName != srcProp ||
                    orphanLink.link.target.propertyName != tgtProp) continue
                val zOnSameSide = if (xIsSrc) orphanLink.link.source.objectName == zName
                                  else orphanLink.link.target.objectName == zName
                val yiOnOtherSide = if (xIsSrc) orphanLink.link.target.objectName == yiName
                                    else orphanLink.link.source.objectName == yiName
                if (zOnSameSide && yiOnOtherSide) return true
            }
        }
        return false
    }

    companion object {
        /**
         * Constructs a [MatchPlanGraph] from the raw pattern categories produced by the
         * language front-end.
         *
         * Steps performed:
         * 1. Compute the pseudo-composition DAG from the metamodel.
         * 2. Merge matchable + delete instances by name into a deduplicated list.
         * 3. Build condition islands for forbid and require elements, including synthetic
         *    single-link islands for orphan links.
         * 4. Compute dependency maps and priority scores via [MatchPlanDependencies]
         *    functions.
         * 5. Assemble and return the fully populated [MatchPlanGraph].
         *
         * @param elements All categorised pattern elements (matchable, delete, forbid,
         *        require, variables, where-clauses).
         * @param referencedInstances Names of instances referenced from expressions but
         *        not themselves part of the matchable pattern.
         * @param getVertexId Function returning a pre-bound vertex ID for an instance
         *        name, or `null` for instances that must be found by traversal.
         * @param nodeAnalyzer Analyser that extracts node names referenced by an
         *        expression.
         * @param isCollectionExpression Predicate returning `true` for collection-typed
         *        expressions.
         * @param metamodelData The metamodel used for DAG computation and BFS ordering.
         * @return A fully populated [MatchPlanGraph] ready for consumption by
         *         [MatchPlanBuilder.PlanExecution].
         */
        fun create(
            elements: PatternCategories,
            referencedInstances: Set<String>,
            getVertexId: (String) -> Any?,
            nodeAnalyzer: ExpressionNodeAnalyzer,
            isCollectionExpression: (TypedExpression) -> Boolean,
            metamodelData: MetamodelData
        ): MatchPlanGraph {
            val pseudoCompositionDag = MetamodelClassPriority.computePseudoCompositionDag(metamodelData)
            val instances = mergeInstancesByName(elements.matchableInstances + elements.deleteInstances)
            val links = elements.matchableLinks + elements.deleteLinks
            val matchableNames = instances.map { it.objectInstance.name }.toSet()
            val variableNames = elements.variables.map { it.variable.name }.toSet()

            val forbidIslands = buildIslandsIncludingOrphanLinks(elements.forbidInstances, elements.forbidLinks, matchableNames)
            val requireIslands = buildIslandsIncludingOrphanLinks(elements.requireInstances, elements.requireLinks, matchableNames)
            val conditions = forbidIslands.map { PendingCondition(it, true) } +
                             requireIslands.map { PendingCondition(it, false) }

            return MatchPlanGraph(
                instances = instances,
                links = links,
                variables = elements.variables,
                whereClauses = elements.whereClauses,
                referencedInstances = referencedInstances,
                conditions = conditions,
                forbidIslands = forbidIslands,
                variableNodeDeps = computeVariableNodeDeps(elements.variables, variableNames, nodeAnalyzer),
                variableVarDeps = computeVariableVarDeps(elements.variables, variableNames, nodeAnalyzer),
                instancePriorities = computeInstancePriorities(instances, elements.requireInstances, links, elements.requireLinks, pseudoCompositionDag),
                injectivePairs = computeInjectivePairs(instances),
                metamodelData = metamodelData,
                getVertexId = getVertexId,
                nodeAnalyzer = nodeAnalyzer,
                isCollectionExpression = isCollectionExpression
            )
        }

        /**
         * Merges a flat list of instance elements that may contain duplicate names into a
         * deduplicated list.
         *
         * When the same instance name appears in multiple pattern categories (e.g. once
         * in the matchable set and once in the delete set), the elements are unified into
         * a single [TypedPatternObjectInstanceElement]:
         * - `className` is taken from the first element that provides one.
         * - `modifier` is taken from the first element that provides one.
         * - `properties` are the concatenation of all per-element property lists.
         *
         * @param instances The raw (potentially duplicate) list of instance elements.
         * @return A list where each logical instance name appears exactly once.
         */
        private fun mergeInstancesByName(
            instances: List<TypedPatternObjectInstanceElement>
        ): List<TypedPatternObjectInstanceElement> {
            val grouped = instances.groupBy { it.objectInstance.name }
            return grouped.map { (_, elements) ->
                if (elements.size == 1) return@map elements.first()
                val className = elements.firstNotNullOfOrNull { it.objectInstance.className }
                val modifier = elements.firstNotNullOfOrNull { it.objectInstance.modifier }
                val allProperties = elements.flatMap { it.objectInstance.properties }
                TypedPatternObjectInstanceElement(
                    objectInstance = TypedPatternObjectInstance(
                        modifier = modifier,
                        name = elements.first().objectInstance.name,
                        className = className,
                        properties = allProperties
                    )
                )
            }
        }

        /**
         * Groups condition elements into structural islands and additionally creates
         * synthetic single-link islands for *orphan links*.
         *
         * An **orphan link** is a condition link whose both endpoints are main-pattern
         * (matchable) nodes, meaning neither endpoint is a condition-exclusive instance.
         * Such a link has no island after [IslandGrouper.groupIntoIslands] runs (the
         * grouper only considers condition-exclusive instances).  A synthetic [Island]
         * with an empty instance list and a single link is created for each orphan link,
         * so the planner can emit it as an [BaseStep.ApplicationCondition] anchored to
         * the two main-pattern endpoints.
         *
         * @param conditionInstances Instance elements exclusive to the condition.
         * @param conditionLinks All link elements belonging to the condition.
         * @param matchableNames The set of main-pattern instance names.
         * @return The combined list of regular and orphan-link islands.
         */
        private fun buildIslandsIncludingOrphanLinks(
            conditionInstances: List<TypedPatternObjectInstanceElement>,
            conditionLinks: List<TypedPatternLinkElement>,
            matchableNames: Set<String>
        ): List<Island> {
            val conditionNames = conditionInstances.map { it.objectInstance.name }.toSet()
            val regularIslands = IslandGrouper.groupIntoIslands(conditionInstances, conditionLinks)
            val orphanLinks = conditionLinks.filter { link ->
                val src = link.link.source.objectName
                val tgt = link.link.target.objectName
                src !in conditionNames && src in matchableNames &&
                tgt !in conditionNames && tgt in matchableNames
            }
            return regularIslands + orphanLinks.map { Island(instances = emptyList(), links = listOf(it)) }
        }
    }
}
