package com.mdeo.modeltransformation.runtime.match.plan

import com.mdeo.expression.ast.expressions.TypedExpression
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLinkElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstanceElement
import com.mdeo.modeltransformation.compiler.VariableBinding
import com.mdeo.modeltransformation.runtime.match.ExpressionNodeAnalyzer
import com.mdeo.modeltransformation.runtime.match.IslandTraversalUtils
import com.mdeo.modeltransformation.runtime.match.PatternCategories

/**
 * Compiles a [PatternCategories] description into an executable [MatchPlan].
 *
 * The builder is stateless: all mutable state is encapsulated in [PlanExecution], which
 * is created fresh for every call to [build].  The [MatchPlanGraph] constructed inside
 * [build] captures the immutable structural and dependency data; [PlanExecution] consumes
 * it to produce the ordered list of [BaseStep]s.
 *
 * @property getVertexId Function returning a pre-bound vertex ID for a named instance,
 *           or `null` if the instance must be matched by graph traversal.
 * @property nodeAnalyzer Analyser that extracts the set of node names referenced by an
 *           expression AST node.
 * @property isCollectionExpression Predicate that returns `true` when an expression
 *           evaluates to a collection type.  Collection-typed expressions cannot be
 *           emitted as simple vertex-property filters.
 * @property metamodelData The metamodel used for association lookups, BFS link ordering,
 *           and pseudo-composition priority computation.
 */
internal class MatchPlanBuilder(
    private val getVertexId: (String) -> Any?,
    private val nodeAnalyzer: ExpressionNodeAnalyzer,
    private val isCollectionExpression: (TypedExpression) -> Boolean,
    private val metamodelData: MetamodelData = MetamodelData.empty()
) {
    /**
     * Builds a [MatchPlan] for the given pattern [elements].
     *
     * @param elements All categorised pattern elements.
     * @param referencedInstances Names of instances referenced from expressions but not
     *        themselves part of the matchable pattern (e.g. context objects).
     * @return A [MatchPlan] containing the ordered list of [BaseStep]s to execute.
     */
    fun build(elements: PatternCategories, referencedInstances: Set<String>): MatchPlan {
        val graph = MatchPlanGraph.create(elements, referencedInstances, getVertexId, nodeAnalyzer, isCollectionExpression, metamodelData)
        return PlanExecution(graph).run()
    }

    /**
     * A property assignment that could not be emitted as an inline constraint when its
     * owning instance was first covered.
     *
     * Deferred properties arise when the property expression references a pattern
     * variable or a not-yet-covered instance.  They are revisited by
     * [PlanExecution.tryInlineDeferredProperties] after every new coverage event and
     * emitted as [BaseStep.DeferredPropertyConstraint] (using a `where(select(...))`
     * Gremlin pattern) once all dependencies are satisfied.
     *
     * @property instanceName Name of the owning pattern instance.
     * @property className Metamodel class name of the owning instance, or `null` if
     *           untyped.
     * @property property The property assignment to be emitted.
     */
    private data class DeferredPropertyInfo(
        val instanceName: String,
        val className: String?,
        val property: com.mdeo.modeltransformation.ast.patterns.TypedPatternPropertyAssignment
    )

    /**
     * A candidate edge traversal that can extend the current structural coverage to an
     * uncovered instance.
     *
     * Walk options are created by [PlanExecution.addWalkOptions] for each matchable link
     * incident on a newly covered instance.  The planner scores each option and picks the
     * best one at every iteration of [PlanExecution.buildStructuralOrder].
     *
     * @property link The typed link element to traverse.
     * @property isReversed `true` if the traversal follows the link in the reverse
     *           direction (AST target → source).
     * @property fromInstanceName Name of the already-covered source instance.
     * @property toInstanceName Name of the not-yet-covered destination instance.
     * @property toInstance Element for [toInstanceName], or `null` if the instance is
     *           not part of the matchable set.
     */
    private data class WalkOption(
        val link: TypedPatternLinkElement,
        val isReversed: Boolean,
        val fromInstanceName: String,
        val toInstanceName: String,
        val toInstance: TypedPatternObjectInstanceElement?
    )

    /**
     * Abstract base for candidates considered during structural ordering.
     *
     * At each iteration of [PlanExecution.buildStructuralOrder] the planner collects all
     * typed-scan and walk candidates, scores them, and selects the best one.
     *
     * @property classPriority Pseudo-composition priority score of the candidate's target
     *           instance (higher = more selective, preferred as a traversal start).
     * @property nacUnlockCost Estimated cost of the cheapest application condition that
     *           becomes evaluable after this candidate is covered.  A lower cost means
     *           choosing this candidate enables cheap condition checking sooner.
     */
    private sealed class TraversalCandidate {
        abstract val classPriority: Int
        abstract val nacUnlockCost: Int
    }

    /**
     * A candidate that covers an uncovered instance by emitting a [BaseStep.VertexScan].
     *
     * @property instance The instance element to be covered.
     * @property classPriority See [TraversalCandidate.classPriority].
     * @property nacUnlockCost See [TraversalCandidate.nacUnlockCost].
     */
    private data class ScanCandidate(
        val instance: TypedPatternObjectInstanceElement,
        override val classPriority: Int,
        override val nacUnlockCost: Int
    ) : TraversalCandidate()

    /**
     * A candidate that covers an uncovered instance by emitting a [BaseStep.EdgeWalk].
     *
     * @property walkOption The walk option describing the link to traverse.
     * @property classPriority See [TraversalCandidate.classPriority].
     * @property nacUnlockCost See [TraversalCandidate.nacUnlockCost].
     */
    private data class WalkCandidate(
        val walkOption: WalkOption,
        override val classPriority: Int,
        override val nacUnlockCost: Int
    ) : TraversalCandidate()

    /**
     * An intermediate representation of one step in the structural (traversal-order)
     * phase of plan construction.
     *
     * Structural steps are assembled by [PlanExecution.buildStructuralOrder], optionally
     * reordered by [PlanExecution.applyPostReordering], and then converted to concrete
     * [BaseStep]s by [PlanExecution.emitPlanFromStructuralOrder].
     */
    private sealed class StructuralStep {
        /**
         * Covers [name] by emitting a [BaseStep.VertexScan].
         *
         * @property name The instance name being covered.
         * @property instance The element for [name], or `null` if the instance is not in
         *           the matchable set (e.g. a pre-bound context reference).
         * @property vertexId A pre-bound vertex ID when available; `null` forces the scan
         *           to use [instance]'s class name as the type filter.
         */
        data class CoverByVertex(
            val name: String,
            val instance: TypedPatternObjectInstanceElement?,
            val vertexId: Any?
        ) : StructuralStep()

        /**
         * Covers [toName] by emitting a [BaseStep.EdgeWalk] from [fromName].
         *
         * @property link The typed link element to traverse.
         * @property isReversed `true` if the edge is followed in reverse (AST target →
         *           source).
         * @property fromName Name of the already-covered source instance.
         * @property toName Name of the instance being covered by this walk.
         * @property toInstance Element for [toName], or `null` if not in the matchable
         *           set.
         * @property toVertexId Pre-bound vertex ID for [toName], or `null`.
         * @property needsSelect `true` when the Gremlin traverser must be repositioned
         *           to [fromName] via `select()` before following the edge.  This field
         *           is recomputed from [PlanExecution.currentNode] during emission and
         *           should be treated as a draft value before that point.
         */
        data class CoverByWalk(
            val link: TypedPatternLinkElement,
            val isReversed: Boolean,
            val fromName: String,
            val toName: String,
            val toInstance: TypedPatternObjectInstanceElement?,
            val toVertexId: Any?,
            var needsSelect: Boolean
        ) : StructuralStep()
    }

    /**
     * Stateful execution context that constructs the ordered [BaseStep] sequence for a
     * single match problem represented by [graph].
     *
     * The algorithm runs in two phases:
     * 1. **Structural ordering** ([buildTraversalOrder]) — greedily selects the order in
     *    which main-pattern instances are covered (scanned or walked), then applies a
     *    1-side-demotion reordering pass.  After each coverage event, all constraints
     *    that have become satisfiable (properties, variables, injective pairs, conditions,
     *    where clauses) are emitted immediately via [applyInlineConstraintsAt].
     * 2. **Remaining steps** ([emitRemainingSteps]) — sweeps through any instances,
     *    links, conditions, variables, deferred properties, where clauses, and injective
     *    pairs that were not resolved during phase 1.
     *
     * @param graph The immutable match-problem graph.
     */
    private inner class PlanExecution(private val graph: MatchPlanGraph) {
        private val baseSteps = mutableListOf<BaseStep>()
        private val coveredInstances = mutableSetOf<String>()
        private val coveredLinks = mutableSetOf<TypedPatternLinkElement>()
        private val emittedConditionIndices = mutableSetOf<Int>()
        private val deferredProperties = mutableListOf<DeferredPropertyInfo>()
        private val pendingWhereClauses = graph.whereClauses.toMutableList()

        private val emittedVariables = mutableSetOf<String>()
        private val pendingVariables = graph.variables.toMutableList()
        private val pendingInjectivePairs = graph.injectivePairs.toMutableList()
        private var currentNode: String? = null

        /**
         * Executes the full two-phase planning algorithm and returns the completed
         * [MatchPlan].
         *
         * @return The [MatchPlan] for the associated [graph].
         */
        fun run(): MatchPlan {
            buildTraversalOrder()
            emitRemainingSteps()
            return MatchPlan(baseSteps)
        }

        /**
         * Orchestrates the structural ordering phase.
         *
         * Delegates to [buildStructuralOrder] for greedy candidate selection, then
         * applies [applyPostReordering] for the 1-side-demotion optimisation, and
         * finally calls [emitPlanFromStructuralOrder] to convert structural steps to
         * [BaseStep]s.
         */
        private fun buildTraversalOrder() {
            val structural = buildStructuralOrder().toMutableList()
            applyPostReordering(structural)
            emitPlanFromStructuralOrder(structural)
        }

        /**
         * Greedily constructs the structural ordering of main-pattern instances.
         *
         * Each iteration of the main loop proceeds as follows:
         * 1. If any instance has a pre-bound vertex ID, it is selected unconditionally
         *    (highest priority regardless of class score).
         * 2. Otherwise, all typed-scan candidates and all available walk candidates are
         *    scored by: (a) descending pseudo-composition priority and (b) ascending cost
         *    of the cheapest application condition newly unlocked by that coverage.  Ties
         *    are broken by preferring walks over scans (walks are cheaper than scans).
         * 3. The highest-scoring candidate is selected and its target instance is marked
         *    as covered.  New walk options incident on the newly covered instance are
         *    added.
         *
         * Instances without a class constraint and without a pre-bound vertex ID are
         * invalid; an [IllegalStateException] is thrown by [emitRemainingSteps] if any
         * such instance remains uncovered after this phase.
         *
         * Referenced context instances are **not** covered here; they are emitted
         * lazily by [tryEmitReferencedInstances] inside the inline fixed-point loop,
         * so they appear in the plan after the main structural traversal.
         *
         * @return The ordered list of structural steps.  [StructuralStep.CoverByWalk
         *         .needsSelect] is initialised to `false`; it is recomputed accurately
         *         by [emitPlanFromStructuralOrder].
         */
        private fun buildStructuralOrder(): List<StructuralStep> {
            val uncovered = graph.instances.toMutableList()
            val availableWalks = mutableListOf<WalkOption>()
            val covered = mutableSetOf<String>()
            val walkedLinks = mutableSetOf<TypedPatternLinkElement>()
            val result = mutableListOf<StructuralStep>()

            while (uncovered.isNotEmpty() || availableWalks.isNotEmpty()) {
                val preBound = uncovered.firstOrNull { getVertexId(it.objectInstance.name) != null }
                if (preBound != null) {
                    val name = preBound.objectInstance.name
                    result.add(StructuralStep.CoverByVertex(name, preBound, getVertexId(name)))
                    uncovered.remove(preBound)
                    covered.add(name)
                    addWalkOptions(name, availableWalks, covered, walkedLinks)
                    continue
                }

                val candidates = mutableListOf<TraversalCandidate>()
                for (inst in uncovered) {
                    if (inst.objectInstance.className == null) continue
                    val name = inst.objectInstance.name
                    val prio = graph.instancePriorities[name] ?: 0
                    val nacCost = minConditionCostUnlockedBy(covered + name, covered)
                    candidates.add(ScanCandidate(inst, prio, nacCost))
                }
                for (walk in availableWalks) {
                    if (walk.toInstanceName in covered) continue
                    val prio = graph.instancePriorities[walk.toInstanceName] ?: 0
                    val nacCost = minConditionCostUnlockedBy(covered + walk.toInstanceName, covered)
                    candidates.add(WalkCandidate(walk, prio, nacCost))
                }

                if (candidates.isEmpty()) break

                val best = candidates.minWith(
                    compareByDescending<TraversalCandidate> { it.classPriority }
                        .thenBy { it.nacUnlockCost }
                        .thenBy { if (it is ScanCandidate) 1 else 0 }
                )

                when (best) {
                    is ScanCandidate -> {
                        val inst = best.instance
                        val name = inst.objectInstance.name
                        result.add(StructuralStep.CoverByVertex(name, inst, graph.getVertexId(name)))
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
                                toVertexId = graph.getVertexId(walk.toInstanceName),
                                needsSelect = false
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
         * Applies the **1-side demotion** optimisation to the structural step list
         * in-place.
         *
         * For each [StructuralStep.CoverByWalk] at position k (walk `fromName →
         * toName`):
         * - If `toName` is at the **1-multiplicity side** of the association (at most
         *   one `toName` vertex exists per `fromName` vertex);
         * - And `fromName` was covered by an unconditional [StructuralStep.CoverByVertex]
         *   (no pre-bound vertex ID) at some earlier position `scanIdx`;
         * - And no other walk from `fromName` lies strictly between `scanIdx` and k;
         * - And no pending application condition requires `fromName` but not `toName`
         *   (a safety check to prevent condition readiness from regressing):
         *
         * → The scan at `scanIdx` is replaced by a scan of `toName`, and the walk at k
         *   is reversed to `toName → fromName`.
         *
         * **Rationale:** Starting a traversal at the 1-side is more selective (the scan
         * finds at most one vertex per type when the multiplicity upper bound is 1), and
         * walking backwards to the many-side fans out naturally.
         *
         * @param structural The structural step list to reorder in-place.
         */
        private fun applyPostReordering(structural: MutableList<StructuralStep>) {
            val assocByProps = graph.metamodelData.associations.associateBy { assoc ->
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

                val safetyBlocked = graph.conditions.any { pending ->
                    val required = graph.pendingConditionRequiredNodes(pending)
                    fromName in required && toName !in required
                }
                if (safetyBlocked) continue

                // Perform the swap
                structural[scanIdx] = StructuralStep.CoverByVertex(
                    name = toName,
                    instance = graph.instanceMap[toName],
                    vertexId = graph.getVertexId(toName)
                )
                structural[k] = StructuralStep.CoverByWalk(
                    link = step.link,
                    isReversed = !step.isReversed,
                    fromName = toName,
                    toName = fromName,
                    toInstance = graph.instanceMap[fromName],
                    toVertexId = graph.getVertexId(fromName),
                    needsSelect = false
                )
            }
        }

        /**
         * Converts the (possibly reordered) structural step list into concrete [BaseStep]s.
         *
         * The coverage sets and [currentNode] are reset before iteration so that inline
         * constraint emission reflects the final step order.  For each structural step
         * the corresponding [BaseStep] is appended to [baseSteps], the coverage sets are
         * updated, and [applyInlineConstraintsAt] is called to emit any constraints newly
         * unlocked by the coverage event.
         *
         * [StructuralStep.CoverByWalk.needsSelect] is recomputed from [currentNode]
         * rather than taken from the pre-computed field, because [applyPostReordering]
         * may have changed the traversal flow.
         *
         * @param structuralSteps The ordered structural steps to emit.
         */
        private fun emitPlanFromStructuralOrder(structuralSteps: List<StructuralStep>) {
            coveredInstances.clear()
            coveredLinks.clear()
            currentNode = null

            for (step in structuralSteps) {
                when (step) {
                    is StructuralStep.CoverByVertex -> {
                        baseSteps.add(BaseStep.VertexScan(step.name, step.instance?.objectInstance?.className, step.vertexId))
                        coveredInstances.add(step.name)
                        currentNode = step.name
                        applyInlineConstraintsAt(step.name, step.instance)
                    }
                    is StructuralStep.CoverByWalk -> {
                        val needsSelect = step.fromName != currentNode
                        baseSteps.add(BaseStep.EdgeWalk(
                            link = step.link,
                            isReversed = step.isReversed,
                            fromInstanceName = step.fromName,
                            toInstanceName = step.toName,
                            toClassName = step.toInstance?.objectInstance?.className,
                            toVertexId = step.toVertexId,
                            needsSelect = needsSelect
                        ))
                        coveredLinks.add(step.link)
                        coveredInstances.add(step.toName)
                        currentNode = step.toName
                        applyInlineConstraintsAt(step.toName, step.toInstance)
                    }
                }
            }
        }

        /**
         * Adds to [availableWalks] every matchable link incident on [newlyCoveredName]
         * that leads to an uncovered matchable instance.
         *
         * Only links not already in [alreadyWalked] are considered.  Both forward
         * (source → target) and reverse (target → source) directions are examined.
         *
         * @param newlyCoveredName The instance name just added to the covered set.
         * @param availableWalks Mutable list of walk options to extend.
         * @param alreadyCovered Names of instances already covered.
         * @param alreadyWalked Links already consumed for structural coverage.
         */
        private fun addWalkOptions(
            newlyCoveredName: String,
            availableWalks: MutableList<WalkOption>,
            alreadyCovered: Set<String>,
            alreadyWalked: Set<TypedPatternLinkElement>
        ) {
            for (link in graph.links) {
                if (link in alreadyWalked) continue
                val src = link.link.source.objectName
                val tgt = link.link.target.objectName
                when {
                    src == newlyCoveredName && tgt !in alreadyCovered && tgt in graph.matchableNames ->
                        availableWalks.add(WalkOption(link, false, src, tgt, graph.instanceMap[tgt]))
                    tgt == newlyCoveredName && src !in alreadyCovered && src in graph.matchableNames ->
                        availableWalks.add(WalkOption(link, true, tgt, src, graph.instanceMap[src]))
                }
            }
        }

        /**
         * Returns the minimum estimated cost among all application conditions that become
         * evaluable when the covered set grows from [before] to [after], but were not
         * evaluable with [before] alone.
         *
         * Used by [buildStructuralOrder] to prefer coverage candidates that unlock cheap
         * condition checks early.  Returns [Int.MAX_VALUE] when no condition is newly
         * unlocked.
         *
         * @param after The hypothetical covered set after the candidate is accepted.
         * @param before The current covered set before the candidate is accepted.
         * @return The minimum cost of a newly unlocked condition, or [Int.MAX_VALUE].
         */
        private fun minConditionCostUnlockedBy(after: Set<String>, before: Set<String>): Int {
            var min = Int.MAX_VALUE
            for (pending in graph.conditions) {
                val required = graph.pendingConditionRequiredNodes(pending)
                if (!required.all { it in before } && required.all { it in after }) {
                    val cost = estimatePendingConditionCost(pending)
                    if (cost < min) min = cost
                }
            }
            return min
        }

        /**
         * Estimates the Gremlin evaluation cost of [pending] for prioritisation purposes.
         *
         * Heuristic:
         * - **Orphan-link island** (no condition-exclusive instances): cost = 1.
         *   The check reduces to a single edge traversal inside `where(...)`.
         * - **Anchored island** (has at least one anchor): cost = 10 × edge count.
         *   Proportional to the number of edge steps inside the `where(...)` block.
         * - **Disconnected island** (no anchors): cost = 1000 + 10 × edge count.
         *   Expensive because the condition requires an uncorrelated vertex scan.
         *
         * @param pending The condition whose cost is to be estimated.
         * @return A non-negative integer; lower values represent cheaper conditions.
         */
        private fun estimatePendingConditionCost(pending: PendingCondition): Int {
            val island = pending.island
            if (island.instances.isEmpty()) return 1
            val islandNames = island.instances.map { it.objectInstance.name }.toSet()
            val anchors = IslandTraversalUtils.findAnchorNames(island.links, islandNames, graph.matchableNames)
            return if (anchors.isEmpty()) 1000 + island.links.size * 10 else island.links.size * 10
        }

        /**
         * Runs a fixed-point loop after [instanceName] has been added to the covered
         * set, emitting all constraints that have become satisfiable.
         *
         * First, if [instance] is non-null, its property constraints are evaluated via
         * [addInlinePropertyConstraints].  Then [runInlineFixedPoint] is invoked to
         * emit all constraints that have become satisfiable.
         *
         * The fixed-point loop is necessary because emitting a variable may unlock a
         * deferred property, which may in turn unlock a where clause, and so on.
         *
         * @param instanceName The name of the instance just covered.
         * @param instance The element for [instanceName], or `null` if not in the
         *        matchable set.
         */
        private fun applyInlineConstraintsAt(
            instanceName: String,
            instance: TypedPatternObjectInstanceElement?
        ) {
            if (instance != null) addInlinePropertyConstraints(instance)
            runInlineFixedPoint()
        }

        /**
         * Runs a fixed-point loop of all inline constraint strategies until no strategy
         * reports progress in a full iteration.
         *
         * Variables and referenced instances are **not** emitted here by a dedicated
         * strategy; they are emitted on demand via [resolve] inside
         * [tryInlineDeferredProperties] and [tryInlineWhereClauses] exactly when — and
         * only when — a constraint's complete set of dependencies is satisfiable.
         *
         * Called by [applyInlineConstraintsAt] after every structural coverage event,
         * and by [emitRemainingSteps] as a safety-net flush for zero-instance patterns.
         */
        private fun runInlineFixedPoint() {
            var progress = true
            while (progress) {
                progress = tryInlineDeferredProperties()
                progress = tryInlineConditions() || progress
                progress = tryInlineWhereClauses() || progress
                progress = tryInlineInjectiveConstraints() || progress
                progress = tryInlineLinks() || progress
            }
        }

        /**
         * Returns `true` when [name] is already emitted or can be emitted on demand
         * without waiting for further structural coverage.
         *
         * Resolvability rules:
         * - Already in [coveredInstances] or [emittedVariables]: always `true`.
         * - Referenced context instance: `true` iff a pre-bound vertex ID is available.
         * - Pattern variable: `true` iff every node dependency
         *   ([MatchPlanGraph.variableNodeDeps]) is itself resolvable **and** every
         *   variable dependency ([MatchPlanGraph.variableVarDeps]) is resolvable.
         * - Matchable instance not yet covered: `false` (must be covered structurally).
         */
        private fun canResolve(name: String): Boolean {
            if (name in coveredInstances || name in emittedVariables) return true
            if (name !in graph.instanceMap && name in graph.referencedInstances)
                return graph.getVertexId(name) != null
            if (name in graph.variableNames) {
                val nodeDeps = graph.variableNodeDeps[name] ?: emptySet()
                if (!nodeDeps.all { canResolve(it) }) return false
                val varDeps = graph.variableVarDeps[name] ?: emptySet()
                return varDeps.all { canResolve(it) }
            }
            return false
        }

        /**
         * Emits [name] and all its transitive unresolved dependencies.
         *
         * - **Referenced context instances** → [BaseStep.VertexScan] added to [baseSteps];
         *   [name] added to [coveredInstances].
         * - **Pattern variables** → variable deps resolved recursively, then
         *   [BaseStep.VariableBinding] added; [name] added to [emittedVariables] and
         *   [currentNode] set to `null`.
         * - **Matchable instances already covered** → no-op.
         *
         * Must only be called after [canResolve] has returned `true` for [name] and all
         * its dependencies; calling it on an unresolvable name is a no-op or may emit
         * only partial output.
         */
        private fun resolve(name: String) {
            if (name in coveredInstances || name in emittedVariables) return
            if (name !in graph.instanceMap && name in graph.referencedInstances) {
                val vertexId = graph.getVertexId(name) ?: return
                baseSteps.add(BaseStep.VertexScan(name, null, vertexId))
                coveredInstances.add(name)
                return
            }
            if (name in graph.variableNames) {
                val varEl = pendingVariables.find { it.variable.name == name } ?: return
                val nodeDeps = graph.variableNodeDeps[name] ?: emptySet()
                nodeDeps.forEach { resolve(it) }
                val varDeps = graph.variableVarDeps[name] ?: emptySet()
                varDeps.forEach { resolve(it) }
                baseSteps.add(BaseStep.VariableBinding(
                    varEl,
                    VariableBinding.variableLabel(name),
                    isReassignment = name in graph.reassignedNames
                ))
                emittedVariables.add(name)
                pendingVariables.remove(varEl)
                currentNode = null
            }
        }

        /**
         * Emits [BaseStep.InjectiveConstraint] steps for all pending injective pairs
         * whose both members are now in [coveredInstances].
         *
         * Injective constraints are emitted as early as possible (immediately after the
         * second member of the pair becomes covered) to prune non-injective partial
         * matches before any further, potentially expensive steps are evaluated.
         *
         * @return `true` if at least one constraint was emitted.
         */
        private fun tryInlineInjectiveConstraints(): Boolean {
            var emitted = false
            val iterator = pendingInjectivePairs.iterator()
            while (iterator.hasNext()) {
                val (nameA, nameB) = iterator.next()
                if (nameA in coveredInstances && nameB in coveredInstances) {
                    baseSteps.add(BaseStep.InjectiveConstraint(nameA, nameB))
                    iterator.remove()
                    emitted = true
                }
            }
            return emitted
        }

        /**
         * Evaluates each `==` property of [instance] and either emits it immediately as
         * a [BaseStep.InlinePropertyConstraint] or adds it to [deferredProperties].
         *
         * A property is **inlined** when its value expression:
         * - Is a *constant* (references no nodes and is not a collection expression), or
         * - References only already-covered instances, no pattern variables, and is not
         *   a collection expression.
         *
         * All other properties are **deferred**: they reference variables or not-yet-
         * covered instances.  They are revisited by [tryInlineDeferredProperties] after
         * every new coverage event.
         *
         * All comparison operators (`==`, `!=`, `<`, `>`, `<=`, `>=`) produce inline
         * or deferred constraints.  The assignment operator (`=`) is skipped — it is
         * handled by [GraphModificationApplier], not by the match plan.
         *
         * @param instance The instance element whose properties are to be processed.
         */
        private fun addInlinePropertyConstraints(instance: TypedPatternObjectInstanceElement) {
            for (property in instance.objectInstance.properties) {
                if (property.operator == "=") continue
                val referencedNodes = graph.nodeAnalyzer.findReferencedNodes(property.value)
                val referencedVars = referencedNodes.filter { it in graph.variableNames }
                val isConstant = referencedNodes.isEmpty() && !graph.isCollectionExpression(property.value)
                val canInline = referencedVars.isEmpty() && (isConstant ||
                    (referencedNodes.isNotEmpty() &&
                     referencedNodes.all { it in coveredInstances } &&
                     !graph.isCollectionExpression(property.value)))
                if (canInline) {
                    baseSteps.add(BaseStep.InlinePropertyConstraint(
                        instance.objectInstance.name,
                        instance.objectInstance.className,
                        property,
                        isConstant
                    ))
                } else {
                    deferredProperties.add(DeferredPropertyInfo(
                        instance.objectInstance.name,
                        instance.objectInstance.className,
                        property
                    ))
                }
            }
        }

        /**
         * Attempts to emit all application conditions whose structural requirements are
         * now satisfied.
         *
         * For each unemitted condition, [tryBuildIslandCondition] is called.  If it
         * returns a non-null result, the condition is added to [readyConditions].  All
         * ready conditions are sorted by [computeConditionCost] (cheapest first) before
         * emission, so that less expensive checks execute before more expensive ones.
         *
         * The `needsSelect` flag of each emitted condition is derived from [currentNode]
         * at the time of emission: `select()` is inserted only when the Gremlin traverser
         * is not already positioned on the anchor node.
         *
         * @return `true` if at least one condition was emitted.
         */
        private fun tryInlineConditions(): Boolean {
            val readyConditions = mutableListOf<Pair<Int, BaseStep.ApplicationCondition>>()
            for ((index, pending) in graph.conditions.withIndex()) {
                if (index in emittedConditionIndices) continue
                val ac = tryBuildIslandCondition(pending) ?: continue
                readyConditions.add(index to ac)
            }
            readyConditions.sortBy { computeConditionCost(it.second) }
            for ((index, ac) in readyConditions) {
                baseSteps.add(ac)
                emittedConditionIndices.add(index)
            }
            return readyConditions.isNotEmpty()
        }

        /**
         * Tries to build an [BaseStep.ApplicationCondition] for [pending] if all
         * required main-pattern nodes are currently in [coveredInstances].  Returns
         * `null` if the condition is not yet ready.
         *
         * Three cases:
         * - **Empty-link island** (no edges in the island): requires only injective-
         *   coverage nodes; emitted as a disconnected condition with no anchor.
         * - **No-anchor island** (all links are internal to the island): treated
         *   identically to the empty-link case.
         * - **Anchored island**: all nodes in [MatchPlanGraph.pendingConditionRequiredNodes]
         *   must be covered; the best anchor is chosen by
         *   [IslandTraversalUtils.selectBestAnchor].
         *
         * The `needsSelect` flag is derived from [currentNode]: if the traverser is
         * already positioned on the anchor, no `select()` step is needed.
         *
         * @param pending The condition to attempt to build.
         * @return A fully constructed [BaseStep.ApplicationCondition], or `null` if not
         *         ready.
         */
        private fun tryBuildIslandCondition(pending: PendingCondition): BaseStep.ApplicationCondition? {
            val island = pending.island
            if (island.links.isEmpty()) {
                if (!graph.computeInjectiveRequiredNodes(island).all { it in coveredInstances }) return null
                return buildApplicationCondition(island, pending.isNegative, anchorName = null, needsSelect = false)
            }
            val islandNames = island.instances.map { it.objectInstance.name }.toSet()
            val anchors = IslandTraversalUtils.findAnchorNames(island.links, islandNames, graph.matchableNames)
            if (anchors.isEmpty()) {
                if (!graph.computeInjectiveRequiredNodes(island).all { it in coveredInstances }) return null
                return buildApplicationCondition(island, pending.isNegative, anchorName = null, needsSelect = false)
            }
            val required = graph.pendingConditionRequiredNodes(pending)
            if (!required.all { it in coveredInstances }) return null
            val bestAnchor = IslandTraversalUtils.selectBestAnchor(anchors, island.links, graph.metamodelData)
                ?: return null
            return buildApplicationCondition(island, pending.isNegative, bestAnchor, needsSelect = bestAnchor != this.currentNode)
        }

        /**
         * Constructs a [BaseStep.ApplicationCondition] for [island] by building the
         * inner Gremlin traversal from scratch.
         *
         * When [anchorName] is non-null the inner traversal starts from that main-pattern
         * node and walks island links via BFS.  When [anchorName] is null (disconnected
         * island) the traversal begins with a vertex scan for the first island instance.
         *
         * For each BFS link:
         * - A [BaseStep.EdgeWalk] is emitted.
         * - If the destination is an outer main-pattern node (not island-exclusive),
         *   a [BaseStep.EqualityFilter] is appended to verify the traversal reached the
         *   correct vertex.
         * - If the destination is an island-exclusive node, its `==` property
         *   constraints are appended via [buildConditionPropertySteps].
         *
         * Injective constraints for island nodes are computed by
         * [buildConditionInjectiveConstraints] and attached to the returned step.
         *
         * @param island The condition island describing the sub-pattern.
         * @param isNegative `true` for NAC (forbid), `false` for PAC (require).
         * @param anchorName The main-pattern instance from which the inner traversal
         *        starts, or `null` for a disconnected island.
         * @param needsSelect `true` when the Gremlin traverser must be repositioned to
         *        [anchorName] via `select()` before following the first island link.
         * @return The fully assembled [BaseStep.ApplicationCondition].
         */
        private fun buildApplicationCondition(
            island: com.mdeo.modeltransformation.runtime.match.Island,
            isNegative: Boolean,
            anchorName: String?,
            needsSelect: Boolean
        ): BaseStep.ApplicationCondition {
            val islandNames = island.instances.map { it.objectInstance.name }.toSet()
            val islandInstanceMap = island.instances.associateBy { it.objectInstance.name }
            val innerSteps = mutableListOf<BaseStep>()

            val startNode: String
            if (anchorName == null) {
                val startInst = island.instances.firstOrNull()
                    ?: return BaseStep.ApplicationCondition(isNegative, null, false, emptyList())
                startNode = startInst.objectInstance.name
                innerSteps.add(BaseStep.VertexScan(startNode, startInst.objectInstance.className, null))
                innerSteps.addAll(buildConditionPropertySteps(startInst))
            } else {
                startNode = anchorName
            }

            val orderedLinks = IslandTraversalUtils.orderLinksByBFS(island.links, startNode, graph.metamodelData)
            var currentInner = startNode

            for ((link, isReversed) in orderedLinks) {
                val fromName = if (isReversed) link.link.target.objectName else link.link.source.objectName
                val toName = if (isReversed) link.link.source.objectName else link.link.target.objectName
                val toIsIslandNode = toName in islandNames
                val toInst = if (toIsIslandNode) islandInstanceMap[toName] else null

                innerSteps.add(BaseStep.EdgeWalk(
                    link = link,
                    isReversed = isReversed,
                    fromInstanceName = fromName,
                    toInstanceName = toName,
                    toClassName = toInst?.objectInstance?.className,
                    toVertexId = null,
                    needsSelect = fromName != currentInner
                ))

                if (!toIsIslandNode && toName != anchorName) innerSteps.add(BaseStep.EqualityFilter(toName))
                if (toIsIslandNode && toInst != null) innerSteps.addAll(buildConditionPropertySteps(toInst))
                currentInner = toName
            }

            return BaseStep.ApplicationCondition(
                isNegative, anchorName, needsSelect, innerSteps,
                buildConditionInjectiveConstraints(island, orderedLinks, startNode)
            )
        }

        /**
         * Returns the inline property constraint steps for [instance] inside an
         * application condition.
         *
         * All comparison operators (`==`, `!=`, `<`, `>`, `<=`, `>=`) are included.
         * The assignment operator (`=`) is skipped — it is handled by the modification
         * applier, not by the match plan.
         * The [BaseStep.InlinePropertyConstraint.isConstant] flag is determined the same
         * way as in [addInlinePropertyConstraints].  Variable-referencing and collection
         * expressions are included unconditionally because they are emitted inside a
         * `where(...)` block where the outer traversal state is already fixed.
         *
         * @param instance An island instance element whose properties are to be emitted.
         * @return The list of inline property constraint steps for [instance].
         */
        private fun buildConditionPropertySteps(
            instance: TypedPatternObjectInstanceElement
        ): List<BaseStep.InlinePropertyConstraint> = instance.objectInstance.properties.mapNotNull { property ->
            if (property.operator == "=") return@mapNotNull null
            val referencedNodes = graph.nodeAnalyzer.findReferencedNodes(property.value)
            val isConstant = referencedNodes.isEmpty() && !graph.isCollectionExpression(property.value)
            BaseStep.InlinePropertyConstraint(instance.objectInstance.name, instance.objectInstance.className, property, isConstant)
        }

        /**
         * Builds the injective-constraint map for the inner traversal of an application
         * condition.
         *
         * For each island node in BFS order (excluding the start node), the map records
         * the Gremlin step labels of:
         * 1. Every main-pattern instance of the same class (preventing the island node
         *    from matching a vertex already bound in the main pattern).
         * 2. Every earlier island node in BFS order of the same class (mutual injectivity
         *    among island nodes).
         *
         * For single-node NAC islands, constraint (1) may be omitted for a specific
         * main-pattern node when [MatchPlanGraph.canOmitNacInjectiveConstraint] returns
         * `true` (see that method for the correctness argument).
         *
         * The map is keyed by [VariableBinding.stepLabel] of the island node.
         *
         * @param island The condition island.
         * @param orderedLinks The BFS-ordered link sequence from
         *        [IslandTraversalUtils.orderLinksByBFS].
         * @param startName The BFS start (anchor) node name.
         * @return A map from island node step label to the list of step labels it must
         *         differ from.
         */
        private fun buildConditionInjectiveConstraints(
            island: com.mdeo.modeltransformation.runtime.match.Island,
            orderedLinks: List<Pair<TypedPatternLinkElement, Boolean>>,
            startName: String
        ): Map<String, List<String>> {
            val constraints = mutableMapOf<String, MutableList<String>>()
            val islandInstanceMap = island.instances.associateBy { it.objectInstance.name }
            val isSingleNodeNac = island.instances.size == 1

            val bfsOrder = mutableListOf<String>()
            val visited = mutableSetOf(startName)
            for ((link, isReversed) in orderedLinks) {
                val toNode = if (isReversed) link.link.source.objectName else link.link.target.objectName
                if (toNode in islandInstanceMap && visited.add(toNode)) bfsOrder.add(toNode)
            }

            for ((i, islandNode) in bfsOrder.withIndex()) {
                val islandClass = islandInstanceMap[islandNode]?.objectInstance?.className ?: continue
                val nodeLabel = VariableBinding.stepLabel(islandNode)

                for (mainInst in graph.instances) {
                    val mainClass = mainInst.objectInstance.className ?: continue
                    if (islandClass != mainClass) continue
                    if (isSingleNodeNac && graph.canOmitNacInjectiveConstraint(islandNode, mainInst.objectInstance.name, island.links)) continue
                    constraints.getOrPut(nodeLabel) { mutableListOf() }
                        .add(VariableBinding.stepLabel(mainInst.objectInstance.name))
                }

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
         * Estimates the Gremlin evaluation cost of [condition] for ordering purposes.
         *
         * Scoring:
         * - **Disconnected** (`anchorName == null`): 1000 + 10 × edge count.  Expensive
         *   because an uncorrelated vertex scan is required inside `where(...)`.
         * - **Anchored**: 10 × edge count + 1 if `needsSelect`.  Proportional to the
         *   number of edge traversals, with a small penalty for needing `select()`.
         *
         * @param condition The assembled application condition.
         * @return A non-negative integer cost estimate; lower is cheaper.
         */
        private fun computeConditionCost(condition: BaseStep.ApplicationCondition): Int {
            if (condition.anchorName == null) return 1000 + condition.innerSteps.count { it is BaseStep.EdgeWalk } * 10
            val edgeCount = condition.innerSteps.count { it is BaseStep.EdgeWalk }
            return edgeCount * 10 + if (condition.needsSelect) 1 else 0
        }

        /**
         * Emits all steps not resolved during the structural ordering phase.
         *
         * All matchable instances must have been covered; an [IllegalStateException] is
         * thrown for any that remain uncovered.
         *
         * Then [runInlineFixedPoint] flushes constraints whose deps became satisfiable
         * after structural ordering (particularly zero-instance patterns).  Variables
         * and referenced instances are emitted lazily inside [tryInlineDeferredProperties]
         * and [tryInlineWhereClauses] only when something depends on them.
         *
         * Afterwards, any referenced context instances still uncovered (those only needed
         * by CREATE steps outside the match plan) are flushed, followed by any remaining
         * variables via [resolve].  [emitRemainingConditions] handles conditions not
         * emitted inline.
         *
         * **Invariant checks.** Deferred property constraints, where clauses, and
         * injective pairs must all be empty at this point.
         */
        private fun emitRemainingSteps() {
            val stillUncovered = graph.instances.filter { it.objectInstance.name !in coveredInstances }
            check(stillUncovered.isEmpty()) {
                val names = stillUncovered.joinToString { it.objectInstance.name }
                "Matchable instances [$names] were not covered during structural ordering. " +
                "Every matchable instance must have a class constraint or a pre-bound vertex ID."
            }

            runInlineFixedPoint()

            for (refName in graph.referencedInstances) {
                if (refName in coveredInstances || refName in graph.instanceMap) continue
                val vertexId = graph.getVertexId(refName) ?: continue
                baseSteps.add(BaseStep.VertexScan(refName, null, vertexId))
                coveredInstances.add(refName)
            }

            emitRemainingConditions()

            for (varEl in pendingVariables.toList()) {
                resolve(varEl.variable.name)
            }

            check(deferredProperties.isEmpty()) {
                "Bug: deferred properties remain after full instance coverage: " +
                deferredProperties.map { it.instanceName }
            }
            check(pendingWhereClauses.isEmpty()) {
                "Bug: where clauses remain after full instance coverage: $pendingWhereClauses"
            }
            check(pendingInjectivePairs.isEmpty()) {
                "Bug: injective pairs remain after full instance coverage: $pendingInjectivePairs"
            }
        }


        /**
         * Emits all application conditions not emitted during the structural phase.
         *
         * For each unemitted condition the best available anchor is selected (or `null`
         * for disconnected islands) and [buildApplicationCondition] is called.  All
         * remaining conditions are sorted by [computeConditionCost] before emission so
         * that cheaper checks execute first.
         */
        private fun emitRemainingConditions() {
            val remaining = mutableListOf<Pair<Int, BaseStep.ApplicationCondition>>()
            for ((index, pending) in graph.conditions.withIndex()) {
                if (index in emittedConditionIndices) continue
                val island = pending.island
                val ac = if (island.links.isEmpty()) {
                    buildApplicationCondition(island, pending.isNegative, null, false)
                } else {
                    val islandNames = island.instances.map { it.objectInstance.name }.toSet()
                    val anchors = IslandTraversalUtils.findAnchorNames(island.links, islandNames, graph.matchableNames)
                    val anchor = IslandTraversalUtils.selectBestAnchor(anchors, island.links, graph.metamodelData)
                    buildApplicationCondition(island, pending.isNegative, anchor, needsSelect = anchor != null)
                }
                remaining.add(index to ac)
            }
            remaining.sortBy { computeConditionCost(it.second) }
            for ((_, ac) in remaining) baseSteps.add(ac)
        }

        /**
         * Promotes deferred property constraints to [BaseStep.DeferredPropertyConstraint]
         * steps when their complete set of dependencies is satisfiable.
         *
         * A deferred property is ready when every node name referenced by its value
         * expression satisfies [canResolve].  Only when **all** deps pass is [resolve]
         * called for each — ensuring that a partially-satisfiable property does not
         * trigger early emission of some deps while others are still waiting.
         *
         * Successfully emitted properties are removed from [deferredProperties].
         *
         * @return `true` if at least one property was emitted.
         */
        private fun tryInlineDeferredProperties(): Boolean {
            var emitted = false
            val iterator = deferredProperties.iterator()
            while (iterator.hasNext()) {
                val info = iterator.next()
                val referencedNodes = graph.nodeAnalyzer.findReferencedNodes(info.property.value)
                if (!referencedNodes.all { canResolve(it) }) continue
                referencedNodes.forEach { resolve(it) }
                baseSteps.add(BaseStep.DeferredPropertyConstraint(info.instanceName, info.className, info.property))
                iterator.remove()
                emitted = true
            }
            return emitted
        }

        /**
         * Emits pending [BaseStep.WhereFilter] steps when the complete set of
         * dependencies is satisfiable.
         *
         * A where clause is ready when every node name it references — whether a
         * matchable instance, referenced context instance, or pattern variable —
         * satisfies [canResolve].  Only when **all** deps pass is [resolve] called for
         * each, so that a partially-satisfiable clause does not trigger early emission
         * of some deps while others still wait.
         *
         * @return `true` if at least one where clause was emitted.
         */
        private fun tryInlineWhereClauses(): Boolean {
            var emitted = false
            val iterator = pendingWhereClauses.iterator()
            while (iterator.hasNext()) {
                val clause = iterator.next()
                val referenced = graph.nodeAnalyzer.findReferencedNodes(clause.whereClause.expression)
                if (!referenced.all { canResolve(it) }) continue
                referenced.forEach { resolve(it) }
                baseSteps.add(BaseStep.WhereFilter(clause))
                iterator.remove()
                emitted = true
            }
            return emitted
        }

        /**
         * Emits an [BaseStep.ApplicationCondition] for each matchable link whose both
         * endpoints are now in [coveredInstances] but whose existence has not yet been
         * verified.
         *
         * Link checks are cheap (a single edge traversal inside `where(...)`) and
         * therefore emitted as early as possible — immediately after the second endpoint
         * of a link enters [coveredInstances] — to prune partial matches before any
         * further, potentially expensive steps are evaluated.
         *
         * The `needsSelect` flag is `true` whenever the Gremlin traverser is not
         * currently positioned on the source endpoint.
         *
         * @return `true` if at least one link check was emitted.
         */
        private fun tryInlineLinks(): Boolean {
            var emitted = false
            for (link in graph.links) {
                if (link in coveredLinks) continue
                val srcName = link.link.source.objectName
                val tgtName = link.link.target.objectName
                if (srcName !in coveredInstances || tgtName !in coveredInstances) continue
                baseSteps.add(BaseStep.ApplicationCondition(
                    isNegative = false,
                    anchorName = srcName,
                    needsSelect = srcName != currentNode,
                    innerSteps = listOf(
                        BaseStep.EdgeWalk(link, false, srcName, tgtName, null, null, needsSelect = false),
                        BaseStep.EqualityFilter(tgtName)
                    )
                ))
                coveredLinks.add(link)
                emitted = true
            }
            return emitted
        }
    }
}
