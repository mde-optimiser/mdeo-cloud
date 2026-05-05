package com.mdeo.modeltransformation.runtime.match.plan

import com.mdeo.expression.ast.expressions.TypedExpression
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.modeltransformation.ast.EdgeLabelUtils
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLinkElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstance
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstanceElement
import com.mdeo.modeltransformation.compiler.VariableBinding
import com.mdeo.modeltransformation.runtime.match.ExpressionNodeAnalyzer
import com.mdeo.modeltransformation.runtime.match.Island
import com.mdeo.modeltransformation.runtime.match.IslandGrouper
import com.mdeo.modeltransformation.runtime.match.IslandTraversalUtils
import com.mdeo.modeltransformation.runtime.match.PatternCategories

/**
 * Builds a fully imperative [MatchPlan] from categorised pattern elements.
 *
 * The builder places all work into [MatchPlan.baseSteps]:
 *
 * 1. Connected-component starts, edge walks, and reachable vertices form the core traversal.
 * 2. Property constraints whose values are constants or expressions referencing only
 *    already-bound nodes are inlined right after their owning instance.
 * 3. All positive/negative application conditions ([BaseStep.ApplicationCondition]) — whether
 *    anchored to the main pattern or fully disconnected — are emitted as soon as their
 *    dependency nodes are covered. Cheaper conditions (fewer edges, lower multiplicity) are
 *    emitted before more expensive ones via a cost-based ranking.
 * 4. Uncovered instances, referenced instances, deferred conditions, variables,
 *    deferred property constraints, and where clauses are appended as imperative steps.
 * 5. Injective constraints are appended last as [BaseStep.InjectiveConstraint] steps.
 *
 * No `match()` step is ever used — the plan is purely imperative.
 *
 * @param getVertexId Returns the pre-bound vertex ID for an instance name.
 * @param nodeAnalyzer Analyzes expression trees to find referenced match nodes.
 * @param isCollectionExpression Returns true when an expression evaluates to a collection type.
 */
internal class MatchPlanBuilder(
    private val getVertexId: (String) -> Any?,
    private val nodeAnalyzer: ExpressionNodeAnalyzer,
    private val isCollectionExpression: (TypedExpression) -> Boolean,
    private val metamodelData: MetamodelData = MetamodelData.empty()
) {

    /**
     * Pseudo-composition DAG derived from the metamodel.
     *
     * Built once from [metamodelData] via [MetamodelClassPriority.computePseudoCompositionDag].
     * Each pair `(parentClass, childClass)` indicates that instances of [childClass] are
     * structurally contained by (or pseudo-composited on) instances of [parentClass].
     *
     * Used by [PlanExecution] to compute per-instance priority scores from the actual
     * pattern graph rather than static class-level scores.
     */
    private val pseudoCompositionDag: Set<Pair<String, String>> by lazy {
        MetamodelClassPriority.computePseudoCompositionDag(metamodelData)
    }

    /**
     * Builds a [MatchPlan] from the given [elements] and [referencedInstances].
     *
     * All construction logic is encapsulated in a fresh [PlanExecution] instance
     * to keep mutable state local to the call and each phase concise.
     *
     * @param elements The categorised pattern elements to plan.
     * @param referencedInstances Instance names referenced externally that must be bound
     *   even if they are not part of the matchable instance set.
     * @return The completed [MatchPlan].
     */
    fun build(elements: PatternCategories, referencedInstances: Set<String>): MatchPlan =
        PlanExecution(elements, referencedInstances).run()

    /**
     * Merges instances that share the same name.
     *
     * When the same instance name appears multiple times (e.g., once with a className and once
     * without for a re-reference with additional property constraints), the className is taken
     * from the first occurrence that has one, and properties are combined from all occurrences.
     *
     * @param instances The (possibly duplicated) list of instances to merge.
     * @return A de-duplicated list of instances with properties merged per name.
     */
    private fun mergeInstancesByName(
        instances: List<TypedPatternObjectInstanceElement>
    ): List<TypedPatternObjectInstanceElement> {
        val grouped = instances.groupBy { it.objectInstance.name }
        return grouped.map { (_, elements) ->
            if (elements.size == 1) { return@map elements.first() }
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

    private data class DeferredPropertyInfo(
        val instanceName: String,
        val className: String?,
        val property: com.mdeo.modeltransformation.ast.patterns.TypedPatternPropertyAssignment
    )

    /**
     * A matchable link that can be walked because its [fromInstanceName] is already covered.
     * Walking the link will cover [toInstanceName].
     */
    private data class WalkOption(
        val link: TypedPatternLinkElement,
        val isReversed: Boolean,
        val fromInstanceName: String,
        val toInstanceName: String,
        val toInstance: TypedPatternObjectInstanceElement?
    )

    /** A candidate step (scan or walk) considered by the step-level greedy algorithm. */
    private sealed class TraversalCandidate {
        abstract val classPriority: Int
        abstract val nacUnlockCost: Int
    }

    private data class ScanCandidate(
        val instance: TypedPatternObjectInstanceElement,
        override val classPriority: Int,
        override val nacUnlockCost: Int
    ) : TraversalCandidate()

    private data class WalkCandidate(
        val walkOption: WalkOption,
        override val classPriority: Int,
        override val nacUnlockCost: Int
    ) : TraversalCandidate()

    /**
     * An intermediate representation of a single structural traversal step, used between
     * the greedy ordering phase and the BaseStep emission phase.
     */
    private sealed class StructuralStep {
        /** Cover an instance by scanning for it. */
        data class CoverByVertex(
            val name: String,
            val instance: TypedPatternObjectInstanceElement?,
            val vertexId: Any?
        ) : StructuralStep()

        /** Cover an instance by walking an edge from an already-covered instance. */
        data class CoverByWalk(
            val link: TypedPatternLinkElement,
            val isReversed: Boolean,
            val fromName: String,
            val toName: String,
            val toInstance: TypedPatternObjectInstanceElement?,
            val toVertexId: Any?,
            var needsSelect: Boolean   // mutable so post-reordering can update it
        ) : StructuralStep()
    }

    /** Groups a pending PAC or NAC into a uniform representation for the plan builder. */
    private data class PendingCondition(
        val island: Island,
        val isNegative: Boolean
    )

    /**
     * Encapsulates all mutable state for a single invocation of [build].
     *
     * Splitting the execution into an inner class keeps each phase concise and avoids
     * threading the entire mutable context through every private method signature.
     * The outer-class members ([getVertexId], [nodeAnalyzer], [isCollectionExpression],
     * and the utility methods) are accessible via the implicit outer reference.
     */
    private inner class PlanExecution(
        private val elements: PatternCategories,
        private val referencedInstances: Set<String>
    ) {
        private val allMatchable = mergeInstancesByName(elements.matchableInstances + elements.deleteInstances)
        private val allMatchableLinks = elements.matchableLinks + elements.deleteLinks
        private val instanceMap = allMatchable.associateBy { it.objectInstance.name }
        private val matchableNames = allMatchable.map { it.objectInstance.name }.toSet()
        private val variableNames = elements.variables.map { it.variable.name }.toSet()

        // All PAC/NAC expressed uniformly: islands (including synthetic orphan-link islands).
        private val forbidIslands = buildIslandsIncludingOrphanLinks(elements.forbidInstances, elements.forbidLinks, matchableNames)
        private val requireIslands = buildIslandsIncludingOrphanLinks(elements.requireInstances, elements.requireLinks, matchableNames)
        private val allConditions: List<PendingCondition> = buildAllConditions()

        private val baseSteps = mutableListOf<BaseStep>()
        private val coveredInstances = mutableSetOf<String>()
        private val coveredLinks = mutableSetOf<TypedPatternLinkElement>()
        private val emittedConditionIndices = mutableSetOf<Int>()
        private val deferredProperties = mutableListOf<DeferredPropertyInfo>()
        private val pendingWhereClauses = elements.whereClauses.toMutableList()

        /**
         * Per-instance priority scores computed from the actual pattern graph.
         *
         * Only regular (no modifier) matchable instances and PAC (require) instances
         * participate.  For each regular/PAC link whose source and target classes form
         * a pseudo-composition pair in [pseudoCompositionDag] (parent→child), the
         * parent instance receives one credit per transitively pseudo-composited instance.
         * The priority of an instance equals the count of instances that are
         * transitively pseudo-composited on it in the pattern.
         *
         * By basing priorities on the *actual* pattern edges rather than static class
         * scores, nodes that act as containers in *this* pattern are preferred, while
         * nodes whose containment edge is absent (e.g., a create-only link) receive no
         * extra boost and are ordered by other criteria (NAC unlock cost, step type).
         */
        private val instancePriorities: Map<String, Int> = computeInstancePriorities()

        private fun computeInstancePriorities(): Map<String, Int> {
            // Regular matchable + PAC instances are eligible to receive priority.
            val regularNames = allMatchable.map { it.objectInstance.name }.toSet()
            val requireNames = elements.requireInstances.map { it.objectInstance.name }.toSet()
            val priorityNames = regularNames + requireNames

            // Build a name→class map from both pools.
            val classOf = HashMap<String, String>()
            for (inst in allMatchable) {
                inst.objectInstance.className?.let { classOf[inst.objectInstance.name] = it }
            }
            for (inst in elements.requireInstances) {
                inst.objectInstance.className?.let { classOf[inst.objectInstance.name] = it }
            }

            // Directed priority graph: parentName → set of childNames.
            // An edge A→B means B is pseudo-composited on A in the pattern.
            val priorityChildren = priorityNames.associateWith { mutableSetOf<String>() }.toMutableMap()

            val relevantLinks = elements.matchableLinks + elements.requireLinks
            for (link in relevantLinks) {
                val srcName = link.link.source.objectName
                val tgtName = link.link.target.objectName
                if (srcName !in priorityNames || tgtName !in priorityNames) continue

                val srcClass = classOf[srcName] ?: continue
                val tgtClass = classOf[tgtName] ?: continue

                when {
                    (srcClass to tgtClass) in pseudoCompositionDag ->
                        // src is pseudo-parent of tgt
                        priorityChildren.getOrPut(srcName) { mutableSetOf() }.add(tgtName)
                    (tgtClass to srcClass) in pseudoCompositionDag ->
                        // tgt is pseudo-parent of src
                        priorityChildren.getOrPut(tgtName) { mutableSetOf() }.add(srcName)
                }
            }

            // Priority = number of instances transitively pseudo-composited on this one.
            return priorityNames.associateWith { name ->
                countTransitiveDescendants(name, priorityChildren)
            }
        }

        /**
         * Counts the number of nodes reachable from [start] via [children] edges
         * (excluding [start] itself). Used to compute transitive pseudo-composition depth.
         */
        private fun countTransitiveDescendants(
            start: String,
            children: Map<String, Set<String>>
        ): Int {
            val visited = mutableSetOf<String>()
            val queue = ArrayDeque<String>()
            queue.add(start)
            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                if (visited.add(cur)) {
                    for (child in children[cur] ?: emptySet()) queue.add(child)
                }
            }
            return visited.size - 1 // exclude start itself
        }

        private fun buildAllConditions(): List<PendingCondition> {
            val result = mutableListOf<PendingCondition>()
            forbidIslands.forEach  { result.add(PendingCondition(it, isNegative = true)) }
            requireIslands.forEach { result.add(PendingCondition(it, isNegative = false)) }
            return result
        }

        /**
         * Executes all plan construction phases and returns the completed [MatchPlan].
         *
         * @return The [MatchPlan] ready for compilation into a Gremlin traversal.
         */
        fun run(): MatchPlan {
            buildTraversalOrder()
            addUncoveredInstances()
            addReferencedInstances()
            addUncoveredLinks()
            addDeferredConditions()
            addVariableBindings()
            addDeferredPropertyConstraints()
            addWhereClauses()
            addInjectiveConstraints()
            return MatchPlan(baseSteps)
        }

        /**
         * Builds the full match traversal order by:
         * 1. Greedily selecting structural steps ([buildStructuralOrder]).
         * 2. Applying the 1-side demotion reordering pass ([applyPostReordering]).
         * 3. Emitting [BaseStep]s and inline constraints for each structural step ([emitPlanFromStructuralOrder]).
         */
        private fun buildTraversalOrder() {
            val structural = buildStructuralOrder().toMutableList()
            applyPostReordering(structural)
            emitPlanFromStructuralOrder(structural)
        }

        /**
         * Greedy step-level algorithm: produces the same ordering as the old [buildTraversalOrder]
         * but returns [StructuralStep] objects instead of emitting [BaseStep]s.
         *
         * Uses local [covered] and [walkedLinks] sets so that [coveredInstances] and
         * [coveredLinks] are left untouched for [emitPlanFromStructuralOrder] to repopulate.
         *
         * Selection criterion:
         * 1. Pre-bound instances first.
         * 2. Highest per-instance priority (number of nodes transitively pseudo-composited on
         *    this instance in the pattern's regular/PAC edges — see [instancePriorities]).
         * 3. Lowest NAC/PAC unlock cost.
         * 4. EdgeWalk over VertexScan on tie.
         */
        private fun buildStructuralOrder(): List<StructuralStep> {
            val uncovered = allMatchable.toMutableList()
            val availableWalks = mutableListOf<WalkOption>()
            val covered = mutableSetOf<String>()
            val walkedLinks = mutableSetOf<TypedPatternLinkElement>()
            val result = mutableListOf<StructuralStep>()

            while (uncovered.isNotEmpty() || availableWalks.isNotEmpty()) {
                // Pre-bound instances have absolute priority regardless of class score.
                val preBound = uncovered.firstOrNull { getVertexId(it.objectInstance.name) != null }
                if (preBound != null) {
                    val name = preBound.objectInstance.name
                    result.add(StructuralStep.CoverByVertex(name, preBound, getVertexId(name)))
                    uncovered.remove(preBound)
                    covered.add(name)
                    addWalkOptions(name, availableWalks, covered, walkedLinks)
                    continue
                }

                // Build candidate list: typed scans + available walks.
                val candidates = mutableListOf<TraversalCandidate>()
                for (inst in uncovered) {
                    if (inst.objectInstance.className == null) continue  // can't scan without class
                    val name = inst.objectInstance.name
                    val prio = instancePriorities[name] ?: 0
                    val nacCost = minConditionCostUnlockedBy(covered + name, covered)
                    candidates.add(ScanCandidate(inst, prio, nacCost))
                }
                for (walk in availableWalks) {
                    if (walk.toInstanceName in covered) continue  // stale
                    val prio = instancePriorities[walk.toInstanceName] ?: 0
                    val nacCost = minConditionCostUnlockedBy(covered + walk.toInstanceName, covered)
                    candidates.add(WalkCandidate(walk, prio, nacCost))
                }

                if (candidates.isEmpty()) break

                val best = candidates.minWith(
                    compareByDescending<TraversalCandidate> { it.classPriority }
                        .thenBy { it.nacUnlockCost }
                        .thenBy { if (it is ScanCandidate) 1 else 0 }  // prefer walk over scan
                )

                when (best) {
                    is ScanCandidate -> {
                        val inst = best.instance
                        val name = inst.objectInstance.name
                        result.add(StructuralStep.CoverByVertex(name, inst, getVertexId(name)))
                        uncovered.remove(inst)
                        covered.add(name)
                        addWalkOptions(name, availableWalks, covered, walkedLinks)
                    }
                    is WalkCandidate -> {
                        val walk = best.walkOption
                        val toInst = walk.toInstance
                            ?: uncovered.find { it.objectInstance.name == walk.toInstanceName }
                        result.add(
                            StructuralStep.CoverByWalk(
                                link = walk.link,
                                isReversed = walk.isReversed,
                                fromName = walk.fromInstanceName,
                                toName = walk.toInstanceName,
                                toInstance = toInst,
                                toVertexId = getVertexId(walk.toInstanceName),
                                needsSelect = false  // placeholder; recomputed at emit time
                            )
                        )
                        walkedLinks.add(walk.link)
                        uncovered.removeIf { it.objectInstance.name == walk.toInstanceName }
                        covered.add(walk.toInstanceName)
                        availableWalks.removeAll { it.link == walk.link }
                        addWalkOptions(walk.toInstanceName, availableWalks, covered, walkedLinks)
                    }
                }
            }
            return result
        }

        /**
         * 1-side demotion reordering pass.
         *
         * For each [StructuralStep.CoverByWalk] where the destination node is at the
         * "1-side" of the traversed association (upper multiplicity == 1), attempts to
         * swap the source's earlier [StructuralStep.CoverByVertex] with a reversed walk
         * from the destination instead.
         *
         * This allows filters on the destination node (property constraints, NACs) to be
         * applied **before** the lookup of the source node, reducing intermediate state.
         *
         * The swap is skipped when:
         * - The source has no unconditional scan before the walk (it was covered via a walk, or pre-bound).
         * - Another walk from the source appears between the source's scan and this walk (blocking).
         * - A pending condition depends only on the source and not the destination (injective safety).
         */
        private fun applyPostReordering(structural: MutableList<StructuralStep>) {
            val assocByProps = metamodelData.associations.associateBy { assoc ->
                assoc.source.name to assoc.target.name
            }

            for (k in structural.indices) {
                val step = structural[k] as? StructuralStep.CoverByWalk ?: continue
                val fromName = step.fromName
                val toName = step.toName
                val link = step.link

                // Find the association for this link
                val assoc = assocByProps[
                    link.link.source.propertyName to link.link.target.propertyName
                ] ?: continue

                // Check whether toName is at the 1-side of the association
                val toIsOneSide = if (!step.isReversed) {
                    assoc.target.multiplicity.upper == 1
                } else {
                    assoc.source.multiplicity.upper == 1
                }
                if (!toIsOneSide) continue

                // Find the unconditional VertexScan for fromName strictly before k
                val scanIdx = (0 until k).indexOfFirst { i ->
                    val s = structural[i]
                    s is StructuralStep.CoverByVertex && s.name == fromName && s.vertexId == null
                }
                if (scanIdx < 0) continue

                // Blocking check: another walk from fromName between the scan and this walk
                val blocked = (scanIdx + 1 until k).any { i ->
                    val s = structural[i]
                    s is StructuralStep.CoverByWalk && s.fromName == fromName
                }
                if (blocked) continue

                // Injective constraint safety check: no condition that requires fromName
                // but not toName (deferring fromName would delay that condition unnecessarily)
                val safetyBlocked = allConditions.any { pending ->
                    val required = pendingConditionRequiredNodes(pending)
                    fromName in required && toName !in required
                }
                if (safetyBlocked) continue

                // Perform the swap
                structural[scanIdx] = StructuralStep.CoverByVertex(
                    name = toName,
                    instance = instanceMap[toName],
                    vertexId = getVertexId(toName)
                )
                structural[k] = StructuralStep.CoverByWalk(
                    link = step.link,
                    isReversed = !step.isReversed,
                    fromName = toName,
                    toName = fromName,
                    toInstance = instanceMap[fromName],
                    toVertexId = getVertexId(fromName),
                    needsSelect = false
                )
            }
        }

        /**
         * Emits [BaseStep]s from the (possibly reordered) structural step list.
         *
         * Resets [coveredInstances] and [coveredLinks] before iterating so that
         * inline constraint emission reflects the new order.
         *
         * [needsSelect] for each [StructuralStep.CoverByWalk] is always recomputed from
         * [currentNode] rather than read from the pre-computed field.
         */
        private fun emitPlanFromStructuralOrder(structuralSteps: List<StructuralStep>) {
            coveredInstances.clear()
            coveredLinks.clear()
            var currentNode: String? = null

            for (step in structuralSteps) {
                when (step) {
                    is StructuralStep.CoverByVertex -> {
                        baseSteps.add(
                            BaseStep.VertexScan(
                                step.name,
                                step.instance?.objectInstance?.className,
                                step.vertexId
                            )
                        )
                        coveredInstances.add(step.name)
                        currentNode = step.name
                        applyInlineConstraintsAt(step.name, step.instance)
                    }
                    is StructuralStep.CoverByWalk -> {
                        val needsSelect = step.fromName != currentNode
                        baseSteps.add(
                            BaseStep.EdgeWalk(
                                link = step.link,
                                isReversed = step.isReversed,
                                fromInstanceName = step.fromName,
                                toInstanceName = step.toName,
                                toClassName = step.toInstance?.objectInstance?.className,
                                toVertexId = step.toVertexId,
                                needsSelect = needsSelect
                            )
                        )
                        coveredLinks.add(step.link)
                        coveredInstances.add(step.toName)
                        currentNode = step.toName
                        applyInlineConstraintsAt(step.toName, step.toInstance)
                    }
                }
            }
        }

        /**
         * Adds [WalkOption]s to [availableWalks] for each matchable link incident on
         * [newlyCoveredName] that leads to an uncovered instance.
         *
         * Uses explicitly supplied [alreadyCovered] and [alreadyWalked] sets so that
         * this helper can be called from both the greedy-ordering phase ([buildStructuralOrder])
         * and the emission phase without accessing the shared mutable fields directly.
         */
        private fun addWalkOptions(
            newlyCoveredName: String,
            availableWalks: MutableList<WalkOption>,
            alreadyCovered: Set<String>,
            alreadyWalked: Set<TypedPatternLinkElement>
        ) {
            for (link in allMatchableLinks) {
                if (link in alreadyWalked) continue
                val src = link.link.source.objectName
                val tgt = link.link.target.objectName
                when {
                    src == newlyCoveredName && tgt !in alreadyCovered && tgt in matchableNames ->
                        availableWalks.add(WalkOption(link, false, src, tgt, instanceMap[tgt]))
                    tgt == newlyCoveredName && src !in alreadyCovered && src in matchableNames ->
                        availableWalks.add(WalkOption(link, true, tgt, src, instanceMap[src]))
                }
            }
        }

        /**
         * Returns the minimum estimated condition cost that becomes newly ready when
         * the covered set changes from [before] to [after].
         *
         * A condition is "newly ready" if it was **not** ready with [before] but **is**
         * ready with [after].  "Ready" means all required nodes (anchors + injective
         * siblings) are present in the covered set.
         *
         * Returns [Int.MAX_VALUE] when no condition becomes newly ready.
         */
        private fun minConditionCostUnlockedBy(
            after: Set<String>,
            before: Set<String>
        ): Int {
            var min = Int.MAX_VALUE
            for (pending in allConditions) {
                val required = pendingConditionRequiredNodes(pending)
                val readyBefore = required.all { it in before }
                val readyAfter  = required.all { it in after  }
                if (!readyBefore && readyAfter) {
                    val cost = estimatePendingConditionCost(pending)
                    if (cost < min) min = cost
                }
            }
            return min
        }

        /**
         * Returns the set of main-pattern node names that must be covered before
         * [pending] can be emitted (anchors + injective-required nodes).
         *
         * Injective nodes whose constraint would be omitted by [canOmitNacInjectiveConstraint]
         * are excluded — requiring them would cause the greedy to wait for those nodes
         * even though the actual ApplicationCondition doesn't need them.
         */
        private fun pendingConditionRequiredNodes(pending: PendingCondition): Set<String> {
            val island = pending.island
            val islandNames = island.instances.map { it.objectInstance.name }.toSet()
            val anchors = IslandTraversalUtils.findAnchorNames(island.links, islandNames, matchableNames)
            val isSingleNodeNac = island.instances.size == 1
            val injectiveRequired = computeInjectiveRequiredNodes(island).filter { mainInstName ->
                if (!isSingleNodeNac) return@filter true
                // Exclude this injective sibling if its constraint would be omitted.
                val islandNode = island.instances.firstOrNull()?.objectInstance?.name ?: return@filter true
                !canOmitNacInjectiveConstraint(islandNode, mainInstName, island.links)
            }.toSet()
            return anchors + injectiveRequired
        }

        /**
         * Estimates the cost of evaluating [pending] as an application condition.
         *
         * Uses the same logic as [computeConditionCost] but operates on the raw
         * [PendingCondition] to avoid building the full [BaseStep.ApplicationCondition]
         * just for ordering purposes.
         */
        private fun estimatePendingConditionCost(pending: PendingCondition): Int {
            val island = pending.island
            if (island.instances.isEmpty()) return 1  // orphan link island: single edge check
            val islandNames = island.instances.map { it.objectInstance.name }.toSet()
            val anchors = IslandTraversalUtils.findAnchorNames(island.links, islandNames, matchableNames)
            val edgeCount = island.links.size
            return if (anchors.isEmpty()) {
                1000 + edgeCount * 10
            } else {
                edgeCount * 10
            }
        }

        /**
         * Applies all inline constraints for [instanceName] immediately after it is covered.
         *
         * This includes inline property constraints for [instance] (when non-null) and
         * any application conditions whose dependency nodes are now all covered.
         *
         * @param instanceName The name of the newly covered instance.
         * @param instance The element for [instanceName], or `null` when not in the matchable set.
         */
        private fun applyInlineConstraintsAt(
            instanceName: String,
            instance: TypedPatternObjectInstanceElement?
        ) {
            if (instance != null) {
                addInlinePropertyConstraints(instance)
            }
            tryInlineConditions(instanceName)
            tryInlineDeferredProperties()
            tryInlineWhereClauses()
        }

        /**
         * Evaluates each property of [instance] and either emits an inline property
         * constraint or defers it to [deferredProperties].
         *
         * A property is inlined when its expression is a constant (no referenced nodes,
         * non-collection) or references only already-covered instances and no pattern
         * variables. All other properties are deferred.
         *
         * @param instance The instance whose properties are to be evaluated.
         */
        private fun addInlinePropertyConstraints(instance: TypedPatternObjectInstanceElement) {
            for (property in instance.objectInstance.properties) {
                if (property.operator != "==") { continue }
                val referencedNodes = nodeAnalyzer.findReferencedNodes(property.value)
                val referencedVars = referencedNodes.filter { it in variableNames }
                val isConstant = referencedNodes.isEmpty() && !isCollectionExpression(property.value)
                val canInline = referencedVars.isEmpty() && (isConstant || (referencedNodes.isNotEmpty()
                    && referencedNodes.all { it in coveredInstances }
                    && !isCollectionExpression(property.value)))
                if (canInline) {
                    baseSteps.add(
                        BaseStep.InlinePropertyConstraint(
                            instance.objectInstance.name,
                            instance.objectInstance.className,
                            property,
                            isConstant
                        )
                    )
                } else {
                    deferredProperties.add(
                        DeferredPropertyInfo(
                            instance.objectInstance.name,
                            instance.objectInstance.className,
                            property
                        )
                    )
                }
            }
        }

        /**
         * Attempts to emit any pending application conditions whose required nodes are now
         * all covered. Ready conditions are sorted cheapest-first before emitting.
         *
         * A condition is ready when:
         * - All anchor/outer nodes it references are covered.
         * - All main-pattern nodes whose class matches any condition node (needed for injective
         *   constraints) are also covered.
         *
         * @param currentNode The node most recently added to the traversal; a condition with
         *   this node as anchor is emitted without a preceding `select()`.
         */
        private fun tryInlineConditions(currentNode: String) {
            val readyConditions = mutableListOf<Pair<Int, BaseStep.ApplicationCondition>>()

            for ((index, pending) in allConditions.withIndex()) {
                if (index in emittedConditionIndices) continue
                val ac = tryBuildIslandCondition(pending.island, pending.isNegative, currentNode) ?: continue
                readyConditions.add(index to ac)
            }

            readyConditions.sortBy { computeConditionCost(it.second) }
            for ((index, ac) in readyConditions) {
                baseSteps.add(ac)
                emittedConditionIndices.add(index)
            }
        }

        /**
         * Tries to build an [BaseStep.ApplicationCondition] for [island] if all required
         * outer nodes are already covered. Returns `null` if not yet ready.
         */
        private fun tryBuildIslandCondition(
            island: Island,
            isNegative: Boolean,
            currentNode: String
        ): BaseStep.ApplicationCondition? {
            if (island.links.isEmpty()) {
                // Singleton disconnected island: only requires injective-sibling coverage.
                val injectiveRequired = computeInjectiveRequiredNodes(island)
                if (!injectiveRequired.all { it in coveredInstances }) return null
                return buildApplicationCondition(island, isNegative, anchorName = null, needsSelect = false)
            }

            val islandNames = island.instances.map { it.objectInstance.name }.toSet()
            val anchors = IslandTraversalUtils.findAnchorNames(island.links, islandNames, matchableNames)

            if (anchors.isEmpty()) {
                // Fully internal island (no links to main pattern): treat as disconnected.
                val injectiveRequired = computeInjectiveRequiredNodes(island)
                if (!injectiveRequired.all { it in coveredInstances }) return null
                return buildApplicationCondition(island, isNegative, anchorName = null, needsSelect = false)
            }

            val isSingleNodeNac = island.instances.size == 1
            val injectiveRequired = computeInjectiveRequiredNodes(island).filter { mainInstName ->
                if (!isSingleNodeNac) return@filter true
                val islandNode = island.instances.firstOrNull()?.objectInstance?.name ?: return@filter true
                !canOmitNacInjectiveConstraint(islandNode, mainInstName, island.links)
            }.toSet()
            val required = anchors + injectiveRequired
            if (!required.all { it in coveredInstances }) return null

            val bestAnchor = IslandTraversalUtils.selectBestAnchor(anchors, island.links, metamodelData)
                ?: return null
            val needsSelect = bestAnchor != currentNode
            return buildApplicationCondition(island, isNegative, bestAnchor, needsSelect)
        }

        /**
         * Computes the set of main-pattern instance names that must be covered before [island]
         * can be emitted, because they share a class with at least one island instance and
         * therefore require injective constraints.
         */
        private fun computeInjectiveRequiredNodes(island: Island): Set<String> {
            val result = mutableSetOf<String>()
            for (islandInst in island.instances) {
                val islandClass = islandInst.objectInstance.className ?: continue
                for (mainInst in allMatchable) {
                    val mainClass = mainInst.objectInstance.className ?: continue
                    if (islandClass == mainClass) {
                        result.add(mainInst.objectInstance.name)
                    }
                }
            }
            return result
        }

        /**
         * Builds an [BaseStep.ApplicationCondition] for [island].
         *
         * When [anchorName] is non-null the inner traversal starts from that outer node
         * and walks island links via [BaseStep.EdgeWalk] steps.
         *
         * When [anchorName] is null (disconnected island) the inner traversal starts with
         * a [BaseStep.VertexScan] for the first island instance and then walks any internal
         * island links.
         *
         * Property constraints for island nodes are emitted as [BaseStep.InlinePropertyConstraint].
         * Links to secondary outer nodes are followed by [BaseStep.EqualityFilter] to verify
         * the traversal reached the correct already-matched vertex.
         */
        private fun buildApplicationCondition(
            island: Island,
            isNegative: Boolean,
            anchorName: String?,
            needsSelect: Boolean
        ): BaseStep.ApplicationCondition {
            val islandNames = island.instances.map { it.objectInstance.name }.toSet()
            val islandInstanceMap = island.instances.associateBy { it.objectInstance.name }

            val innerSteps = mutableListOf<BaseStep>()
            val startNode: String

            if (anchorName == null) {
                // Disconnected: begin with a VertexScan for the first island instance.
                val startInst = island.instances.firstOrNull()
                    ?: return BaseStep.ApplicationCondition(isNegative, null, false, emptyList())
                startNode = startInst.objectInstance.name
                innerSteps.add(BaseStep.VertexScan(startNode, startInst.objectInstance.className, null))
                innerSteps.addAll(buildConditionPropertySteps(startInst))
            } else {
                startNode = anchorName
            }

            // BFS over island links from startNode.
            val orderedLinks = IslandTraversalUtils.orderLinksByBFS(island.links, startNode, metamodelData)
            var currentInner = startNode

            for ((link, isReversed) in orderedLinks) {
                val fromName = if (isReversed) link.link.target.objectName else link.link.source.objectName
                val toName   = if (isReversed) link.link.source.objectName else link.link.target.objectName
                val toIsIslandNode = toName in islandNames
                val toInst = if (toIsIslandNode) islandInstanceMap[toName] else null

                innerSteps.add(
                    BaseStep.EdgeWalk(
                        link = link,
                        isReversed = isReversed,
                        fromInstanceName = fromName,
                        toInstanceName = toName,
                        toClassName = toInst?.objectInstance?.className,
                        toVertexId = null,
                        needsSelect = fromName != currentInner
                    )
                )

                // When the destination is an outer node (not island-only, not the primary anchor),
                // add an equality filter to verify the traversal reached the correct vertex.
                if (!toIsIslandNode && toName != anchorName) {
                    innerSteps.add(BaseStep.EqualityFilter(toName))
                }

                // Inline property constraints for island-only destination nodes.
                if (toIsIslandNode && toInst != null) {
                    innerSteps.addAll(buildConditionPropertySteps(toInst))
                }

                currentInner = toName
            }

            val injectiveConstraints = buildConditionInjectiveConstraints(island, orderedLinks, startNode)

            return BaseStep.ApplicationCondition(
                isNegative = isNegative,
                anchorName = anchorName,
                needsSelect = needsSelect,
                innerSteps = innerSteps,
                injectiveConstraints = injectiveConstraints
            )
        }

        /**
         * Builds the [BaseStep.InlinePropertyConstraint] inner steps for an island instance.
         *
         * Only `==` properties are included; the `isConstant` flag is determined the same
         * way as for main-pattern property constraints.
         */
        private fun buildConditionPropertySteps(
            instance: TypedPatternObjectInstanceElement
        ): List<BaseStep.InlinePropertyConstraint> {
            return instance.objectInstance.properties.mapNotNull { property ->
                if (property.operator != "==") return@mapNotNull null
                val referencedNodes = nodeAnalyzer.findReferencedNodes(property.value)
                val isConstant = referencedNodes.isEmpty() && !isCollectionExpression(property.value)
                BaseStep.InlinePropertyConstraint(
                    instance.objectInstance.name,
                    instance.objectInstance.className,
                    property,
                    isConstant
                )
            }
        }

        /**
         * Builds the injective-constraint map for an application condition's chain traversal.
         *
         * For each non-start island node (in BFS order), computes the list of step labels
         * that the node must be distinct from:
         * - [VariableBinding.stepLabel] of every main-pattern node that shares the same class.
         * - [VariableBinding.stepLabel] of every earlier island node in BFS order that shares
         *   the same class (using the standard step-label convention instead of synthetic names).
         *
         * **Single-node NAC optimisation (improvement 1):** when the island contains exactly
         * one non-main-pattern node X, the constraint `X != Z` against a main-pattern node Z
         * of the same class is **omitted** when Z is already connected to some main-pattern
         * node Yi in the NAC via a forbid orphan link that uses the same edge label and
         * direction as the NAC edge (X – Yi).
         *
         * Correctness: if X = Z would cause the NAC to fire (edge X–Yi exists), then that
         * same edge Z–Yi already causes the simpler `forbid Z–Yi` orphan-link constraint to
         * fail, so the global match is rejected regardless — making `X != Z` redundant.
         *
         * The returned map uses the island node's step label as key.
         */
        private fun buildConditionInjectiveConstraints(
            island: Island,
            orderedLinks: List<Pair<TypedPatternLinkElement, Boolean>>,
            startName: String
        ): Map<String, List<String>> {
            val constraints = mutableMapOf<String, MutableList<String>>()
            val islandInstanceMap = island.instances.associateBy { it.objectInstance.name }

            val bfsOrder = mutableListOf<String>()
            val visited = mutableSetOf(startName)
            for ((link, isReversed) in orderedLinks) {
                val toNode = if (isReversed) link.link.source.objectName else link.link.target.objectName
                if (toNode in islandInstanceMap && visited.add(toNode)) {
                    bfsOrder.add(toNode)
                }
            }

            // True when the island has exactly one island node X whose edges all connect
            // to main-pattern nodes — the precondition for the single-node NAC optimisation.
            val isSingleNodeNac = island.instances.size == 1

            for ((i, islandNode) in bfsOrder.withIndex()) {
                val islandClass = islandInstanceMap[islandNode]?.objectInstance?.className ?: continue
                val nodeLabel = VariableBinding.stepLabel(islandNode)

                // Against already-matched main-pattern nodes of the same class.
                for (mainInst in allMatchable) {
                    val mainClass = mainInst.objectInstance.className ?: continue
                    if (islandClass != mainClass) continue

                    // Optimisation: skip X != Z when a matching forbid orphan link on Z
                    // already makes the constraint redundant (see KDoc above).
                    if (isSingleNodeNac &&
                        canOmitNacInjectiveConstraint(islandNode, mainInst.objectInstance.name, island.links)
                    ) continue

                    constraints.getOrPut(nodeLabel) { mutableListOf() }
                        .add(VariableBinding.stepLabel(mainInst.objectInstance.name))
                }

                // Against earlier island nodes of the same class in BFS order.
                for (j in 0 until i) {
                    val prevNode = bfsOrder[j]
                    val prevClass = islandInstanceMap[prevNode]?.objectInstance?.className ?: continue
                    if (islandClass == prevClass) {
                        constraints.getOrPut(nodeLabel) { mutableListOf() }
                            .add(VariableBinding.stepLabel(prevNode))
                    }
                }
            }

            return constraints
        }

        /**
         * Returns `true` when the injective constraint `xName != zName` can be safely
         * omitted for a single-node NAC island.
         *
         * The constraint is redundant when there exists at least one NAC edge (xName – Yi)
         * in [nacIslandLinks] for which there is a forbid orphan link (zName – Yi) with the
         * **same edge label and direction** (identical source/target property names, and
         * zName on the same side of the link as xName).
         *
         * Reasoning: if xName and zName map to the same vertex V, the NAC fires only when V
         * is connected to some Yi via the matching edge.  But a forbid orphan link already
         * prevents the overall match whenever that edge exists, so the `V != zName` guard is
         * redundant.
         */
        private fun canOmitNacInjectiveConstraint(
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
                    if (orphanIsland.instances.isNotEmpty()) continue  // skip non-orphan islands
                    val orphanLink = orphanIsland.links.singleOrNull() ?: continue
                    // Same edge label: identical source- and target-property names.
                    if (orphanLink.link.source.propertyName != srcProp ||
                        orphanLink.link.target.propertyName != tgtProp) continue

                    // Z must occupy the same position in the orphan link as X in the NAC link,
                    // and Yi must be on the other side.
                    val zOnSameSide = if (xIsSrc) orphanLink.link.source.objectName == zName else orphanLink.link.target.objectName == zName
                    val yiOnOtherSide = if (xIsSrc) orphanLink.link.target.objectName == yiName else orphanLink.link.source.objectName == yiName

                    if (zOnSameSide && yiOnOtherSide) return true
                }
            }
            return false
        }

        /**
         * Estimates the evaluation cost of [condition] for ordering purposes.
         *
         * Lower scores are cheaper:
         * - Orphan links (single-edge anchored, <= 2 steps): 1-2
         * - Multi-step anchored islands: 10 * edge count, +1 if needsSelect
         * - Disconnected/unanchored: 1000 + steps
         */
        private fun computeConditionCost(condition: BaseStep.ApplicationCondition): Int {
            if (condition.anchorName == null) {
                return 1000 + condition.innerSteps.count { it is BaseStep.EdgeWalk } * 10
            }
            val edgeCount = condition.innerSteps.count { it is BaseStep.EdgeWalk }
            val selectPenalty = if (condition.needsSelect) 1 else 0
            return edgeCount * 10 + selectPenalty
        }

        /**
         * Emits [BaseStep.VertexScan] steps for matchable instances not covered during
         * the connected-component traversal phase, and attempts to inline any application
         * conditions that become ready after each instance is covered.
         */
        private fun addUncoveredInstances() {
            for (instance in allMatchable) {
                val name = instance.objectInstance.name
                if (name in coveredInstances) { continue }
                val vertexId = getVertexId(name)
                val className = instance.objectInstance.className
                when {
                    vertexId != null -> baseSteps.add(BaseStep.VertexScan(name, className, vertexId))
                    className != null -> baseSteps.add(BaseStep.VertexScan(name, className, null))
                    else -> throw IllegalStateException(
                        "Instance '${name}' has no class constraint and no pre-bound vertex. " +
                        "All matchable instances must be typed or pre-bound."
                    )
                }
                coveredInstances.add(name)
                applyInlineConstraintsAt(name, instance)
            }
        }

        /**
         * Emits [BaseStep.VertexScan] steps for externally referenced instances not yet
         * covered and not part of the matchable instance set.
         *
         * A referenced instance is silently skipped when no pre-bound vertex ID is available.
         */
        private fun addReferencedInstances() {
            for (refName in referencedInstances) {
                if (refName in coveredInstances || refName in instanceMap) continue
                val vertexId = getVertexId(refName) ?: continue
                baseSteps.add(BaseStep.VertexScan(refName, null, vertexId))
                coveredInstances.add(refName)
            }
        }

        /**
         * Emits steps for matchable links not covered during component traversal.
         *
         * - Both endpoints covered → [BaseStep.EqualityFilter]-style check via require edge walk.
         * - Source covered only → forward [BaseStep.EdgeWalk] to reach the target.
         * - Target covered only → reversed [BaseStep.EdgeWalk] to reach the source.
         * - Neither covered → forward [BaseStep.EdgeWalk] from source to target.
         */
        private fun addUncoveredLinks() {
            for (link in allMatchableLinks) {
                if (link in coveredLinks) continue
                val srcName = link.link.source.objectName
                val tgtName = link.link.target.objectName
                val edgeLabel = EdgeLabelUtils.computeEdgeLabel(
                    link.link.source.propertyName, link.link.target.propertyName
                )
                when {
                    srcName in coveredInstances && tgtName in coveredInstances -> {
                        // Both endpoints already matched; verify the edge exists.
                        baseSteps.add(
                            BaseStep.ApplicationCondition(
                                isNegative = false,
                                anchorName = srcName,
                                needsSelect = true,
                                innerSteps = listOf(
                                    BaseStep.EdgeWalk(link, false, srcName, tgtName, null, null, needsSelect = false),
                                    BaseStep.EqualityFilter(tgtName)
                                )
                            )
                        )
                    }
                    srcName in coveredInstances -> {
                        val toInstance = instanceMap[tgtName]
                        baseSteps.add(
                            BaseStep.EdgeWalk(
                                link = link, isReversed = false,
                                fromInstanceName = srcName, toInstanceName = tgtName,
                                toClassName = toInstance?.objectInstance?.className,
                                toVertexId = getVertexId(tgtName),
                                needsSelect = true
                            )
                        )
                        coveredInstances.add(tgtName)
                    }
                    tgtName in coveredInstances -> {
                        val fromInstance = instanceMap[srcName]
                        baseSteps.add(
                            BaseStep.EdgeWalk(
                                link = link, isReversed = true,
                                fromInstanceName = tgtName, toInstanceName = srcName,
                                toClassName = fromInstance?.objectInstance?.className,
                                toVertexId = getVertexId(srcName),
                                needsSelect = true
                            )
                        )
                        coveredInstances.add(srcName)
                    }
                    else -> {
                        val toInstance = instanceMap[tgtName]
                        baseSteps.add(
                            BaseStep.EdgeWalk(
                                link = link, isReversed = false,
                                fromInstanceName = srcName, toInstanceName = tgtName,
                                toClassName = toInstance?.objectInstance?.className,
                                toVertexId = getVertexId(tgtName),
                                needsSelect = true
                            )
                        )
                    }
                }
            }
        }

        /**
         * Emits all remaining [BaseStep.ApplicationCondition] steps that were not inlined
         * during traversal. Conditions are sorted by estimated cost (cheapest first).
         */
        private fun addDeferredConditions() {
            val remaining = mutableListOf<Pair<Int, BaseStep.ApplicationCondition>>()

            for ((index, pending) in allConditions.withIndex()) {
                if (index in emittedConditionIndices) continue

                val island = pending.island
                val ac: BaseStep.ApplicationCondition = if (island.links.isEmpty()) {
                    buildApplicationCondition(island, pending.isNegative, null, false)
                } else {
                    val islandNames = island.instances.map { it.objectInstance.name }.toSet()
                    val anchors = IslandTraversalUtils.findAnchorNames(island.links, islandNames, matchableNames)
                    val anchor = IslandTraversalUtils.selectBestAnchor(anchors, island.links, metamodelData)
                    if (anchor == null) {
                        buildApplicationCondition(island, pending.isNegative, null, false)
                    } else {
                        buildApplicationCondition(island, pending.isNegative, anchor, needsSelect = true)
                    }
                }

                remaining.add(index to ac)
            }

            remaining.sortBy { computeConditionCost(it.second) }
            for ((_, ac) in remaining) {
                baseSteps.add(ac)
            }
        }

        /**
         * Emits [BaseStep.VariableBinding] steps for all pattern variable definitions.
         */
        private fun addVariableBindings() {
            for (varElement in elements.variables) {
                baseSteps.add(
                    BaseStep.VariableBinding(varElement, VariableBinding.variableLabel(varElement.variable.name))
                )
            }
        }

        /**
         * Emits [BaseStep.DeferredPropertyConstraint] steps for all properties that
         * could not be inlined during component traversal.
         */
        private fun addDeferredPropertyConstraints() {
            for (info in deferredProperties) {
                baseSteps.add(BaseStep.DeferredPropertyConstraint(info.instanceName, info.className, info.property))
            }
        }

        /**
         * Emits any remaining pending where-clause filters as [BaseStep.WhereFilter] steps.
         *
         * This is the final sweep; clauses whose dependencies were already met have been
         * emitted inline earlier via [tryInlineWhereClauses].
         */
        private fun addWhereClauses() {
            for (clause in pendingWhereClauses) {
                baseSteps.add(BaseStep.WhereFilter(clause))
            }
        }

        /**
         * Appends [BaseStep.InjectiveConstraint]s for all pairs of matched instances
         * that share the same class name, ensuring they bind to distinct vertices.
         */
        private fun addInjectiveConstraints() {
            for (i in allMatchable.indices) {
                for (j in i + 1 until allMatchable.size) {
                    val a = allMatchable[i].objectInstance
                    val b = allMatchable[j].objectInstance
                    if (a.name != b.name && a.className != null && a.className == b.className) {
                        baseSteps.add(BaseStep.InjectiveConstraint(a.name, b.name))
                    }
                }
            }
        }

        /**
         * Groups condition instances+links into islands, PLUS creates synthetic single-link
         * islands for "orphan links" — forbid/require links whose both endpoints are
         * main-pattern (matchable) nodes and therefore have no condition-exclusive instances.
         *
         * An orphan link {A -- B} (both A, B ∈ matchableNames) is turned into an Island with
         * instances = [] and links = [the link]. When the island is processed, both A and B
         * are recognised as anchors (they are in matchableNames), so the condition is emitted
         * as a 2-anchor, 0-island-node ApplicationCondition — equivalent to the old
         * buildOrphanLinkCondition behaviour.
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
            val orphanIslands = orphanLinks.map { link -> Island(instances = emptyList(), links = listOf(link)) }
            return regularIslands + orphanIslands
        }

        /**
         * Emits any deferred property constraints whose referenced instances are now all covered.
         * Removes successfully inlined constraints from [deferredProperties].
         */
        private fun tryInlineDeferredProperties() {
            val iterator = deferredProperties.iterator()
            while (iterator.hasNext()) {
                val info = iterator.next()
                val referencedNodes = nodeAnalyzer.findReferencedNodes(info.property.value)
                val referencedVars = referencedNodes.filter { it in variableNames }
                if (referencedVars.isNotEmpty()) continue  // still needs variables — wait for variable binding phase
                if (!referencedNodes.all { it in coveredInstances }) continue  // not all deps covered yet
                if (isCollectionExpression(info.property.value)) continue  // keep deferred for collection types
                baseSteps.add(
                    BaseStep.InlinePropertyConstraint(
                        info.instanceName, info.className, info.property, isConstant = false
                    )
                )
                iterator.remove()
            }
        }

        /**
         * Emits any pending where-clause filters whose referenced matchable instances are
         * now all covered. Removes successfully emitted clauses from [pendingWhereClauses].
         */
        private fun tryInlineWhereClauses() {
            val iterator = pendingWhereClauses.iterator()
            while (iterator.hasNext()) {
                val clause = iterator.next()
                val referenced = nodeAnalyzer.findReferencedNodes(clause.whereClause.expression)
                    .filter { it in matchableNames }
                if (!referenced.all { it in coveredInstances }) continue
                baseSteps.add(BaseStep.WhereFilter(clause))
                iterator.remove()
            }
        }
    }
}
