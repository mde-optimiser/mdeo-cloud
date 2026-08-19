package com.mdeo.modeltransformation.runtime.match.plan

import com.mdeo.expression.ast.expressions.TypedExpression
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLinkElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstanceElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternWhereClauseElement
import com.mdeo.modeltransformation.compiler.VariableBinding
import com.mdeo.modeltransformation.graph.ModelStatistics
import com.mdeo.modeltransformation.runtime.match.ExpressionNodeAnalyzer
import com.mdeo.modeltransformation.runtime.match.ApplicationConditionBlock
import com.mdeo.modeltransformation.runtime.match.ConditionTraversalUtils
import com.mdeo.modeltransformation.runtime.match.PatternCategories

/**
 * One connected component of the graph of an application condition.
 *
 * A block is no longer required to be connected: `forbid { a: A {}  b: B {} }` demands
 * that an `A` *and* a `B` exist, so both components have to be walked inside the same
 * condition traversal.
 *
 * @property instances The condition-exclusive nodes of this component.
 * @property links The links of this component.
 * @property anchors The main-pattern nodes this component is attached to.
 */
private data class ConditionComponent(
    val instances: List<TypedPatternObjectInstanceElement>,
    val links: List<TypedPatternLinkElement>,
    val anchors: Set<String>
)

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
 * @property statistics Cardinality snapshot of the model to be matched. When supplied, the
 *           structural order is chosen by estimated branching factor ([MatchCostModel]);
 *           when `null`, the purely structural pseudo-composition heuristic is used.
 */
internal class MatchPlanBuilder(
    private val getVertexId: (String) -> Any?,
    private val nodeAnalyzer: ExpressionNodeAnalyzer,
    private val isCollectionExpression: (TypedExpression) -> Boolean,
    private val metamodelData: MetamodelData = MetamodelData.empty(),
    private val statistics: ModelStatistics? = null
) {

    companion object {
        /**
         * A step must be estimated *strictly below* this to count as shrinking the
         * partial-match set, which is the only case in which the cost model overrides the
         * planner's pre-existing order; see [PlanExecution.candidateComparator].
         */
        private const val SHRINKING_FACTOR = 1.0

        /**
         * When this system property is set, the structural step order chosen by
         * [PlanExecution.buildStructuralOrder] is printed.
         *
         * `mdeo.debug.matchplan` shows the finished plan, in which the scans and walks are
         * interleaved with the constraints they unlock; this shows the join order alone,
         * which is what decides how large the intermediate results get.
         */
        const val DEBUG_STRUCTURAL_ORDER_PROPERTY = "mdeo.debug.structuralorder"
    }
    /**
     * Builds a [MatchPlan] for the given pattern [elements].
     *
     * @param elements All categorised pattern elements.
     * @param referencedInstances Names of instances referenced from expressions but not
     *        themselves part of the matchable pattern (e.g. context objects).
     * @return A [MatchPlan] containing the ordered list of [BaseStep]s to execute.
     */
    fun build(elements: PatternCategories, referencedInstances: Set<String>): MatchPlan {
        val graph = MatchPlanGraph.create(
            elements, referencedInstances, getVertexId, nodeAnalyzer,
            isCollectionExpression, metamodelData, statistics
        )
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

        /**
         * Name of the instance this candidate would cover. Used as the final, purely
         * lexicographic tie-breaker so that plan construction stays deterministic.
         */
        abstract val targetName: String

        /**
         * Estimated factor by which accepting this candidate multiplies the number of
         * partial matches, as produced by [MatchCostModel]; `Double.NaN` when no statistics
         * are available.  Lower is better.
         */
        abstract val branchingFactor: Double
    }

    /**
     * A candidate that covers an uncovered instance by emitting a [BaseStep.VertexScan].
     *
     * @property instance The instance element to be covered.
     * @property classPriority See [TraversalCandidate.classPriority].
     * @property nacUnlockCost See [TraversalCandidate.nacUnlockCost].
     * @property branchingFactor See [TraversalCandidate.branchingFactor].
     */
    private data class ScanCandidate(
        val instance: TypedPatternObjectInstanceElement,
        override val classPriority: Int,
        override val nacUnlockCost: Int,
        override val branchingFactor: Double
    ) : TraversalCandidate() {
        override val targetName: String get() = instance.objectInstance.name
    }

    /**
     * A candidate that covers an uncovered instance by emitting a [BaseStep.EdgeWalk].
     *
     * @property walkOption The walk option describing the link to traverse.
     * @property classPriority See [TraversalCandidate.classPriority].
     * @property nacUnlockCost See [TraversalCandidate.nacUnlockCost].
     * @property branchingFactor See [TraversalCandidate.branchingFactor].
     */
    private data class WalkCandidate(
        val walkOption: WalkOption,
        override val classPriority: Int,
        override val nacUnlockCost: Int,
        override val branchingFactor: Double
    ) : TraversalCandidate() {
        override val targetName: String get() = walkOption.toInstanceName
    }

    /**
     * An intermediate representation of one step in the structural (traversal-order)
     * phase of plan construction.
     *
     * Structural steps are assembled by [PlanExecution.buildStructuralOrder] and then
     * converted to concrete [BaseStep]s by [PlanExecution.emitPlanFromStructuralOrder].
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
         * Delegates to [buildStructuralOrder] for greedy candidate selection, then calls
         * [emitPlanFromStructuralOrder] to convert structural steps to [BaseStep]s.
         */
        private fun buildTraversalOrder() {
            val structural = buildStructuralOrder().toMutableList()
            val debug = System.getProperty(DEBUG_STRUCTURAL_ORDER_PROPERTY) != null
            if (debug) println("[structural] order:  " + structural.joinToString(" | ") { describe(it) })
            emitPlanFromStructuralOrder(structural)
        }

        /**
         * Greedily constructs the structural ordering of main-pattern instances.
         *
         * Each iteration of the main loop proceeds as follows:
         * 1. If any instance has a pre-bound vertex ID, it is selected unconditionally
         *    (highest priority regardless of class score).
         * 2. Otherwise, all typed-scan candidates and all available walk candidates are
         *    scored and the cheapest is taken; see [candidateComparator] for the ordering.
         *    With model statistics available the primary criterion is the estimated
         *    branching factor ([estimateBranchingFactor]); without them it is the
         *    pseudo-composition priority, as before.
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
            val comparators = HashMap<Boolean, Comparator<TraversalCandidate>>()
            fun comparator(costModel: MatchCostModel?) =
                comparators.getOrPut(costModel != null) { candidateComparator(costModel) }

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

                val walkable = availableWalks.filter { it.toInstanceName !in covered }

                // An instance that can be reached by a walk is never worth scanning for.
                // The walk yields the neighbours of an already-covered vertex, which are a
                // subset of the class extent a scan would read, and it consumes the link
                // that a scan would afterwards have to verify as a cycle closure — so both
                // routes produce exactly the same partial matches, one of them for a
                // fraction of the work. The estimator cannot see this on its own: it scores
                // a step by how many partial matches it *produces*, and the cycle-closure
                // term makes such a scan look as selective as the walk it duplicates, which
                // is how `V(HospitalisationShift)` came to be chosen over a to-one walk from
                // an already-bound RoomShiftAssignment.
                val walkTargets = walkable.mapTo(HashSet()) { it.toInstanceName }
                val scannable = uncovered.filter {
                    it.objectInstance.className != null && it.objectInstance.name !in walkTargets
                }
                if (scannable.isEmpty() && walkable.isEmpty()) break

                // With a single option there is nothing to order, so neither the estimates nor
                // the structural scores are computed. Small patterns hit this on every step.
                val onlyChoice = scannable.size + walkable.size == 1
                val costModel = if (onlyChoice) null else graph.costModel

                val candidates = mutableListOf<TraversalCandidate>()
                for (inst in scannable) {
                    val name = inst.objectInstance.name
                    val prio = if (onlyChoice) 0 else graph.instancePriorities[name] ?: 0
                    val nacCost =
                        if (onlyChoice) 0 else minConditionCostUnlockedBy(covered + name, covered)
                    val factor = costModel?.let {
                        estimateBranchingFactor(it, name, it.scanFactor(inst), covered, coveringLink = null)
                    } ?: Double.NaN
                    candidates.add(ScanCandidate(inst, prio, nacCost, factor))
                }
                for (walk in walkable) {
                    val prio = if (onlyChoice) 0 else graph.instancePriorities[walk.toInstanceName] ?: 0
                    val nacCost = if (onlyChoice) 0
                                  else minConditionCostUnlockedBy(covered + walk.toInstanceName, covered)
                    val factor = costModel?.let {
                        val base = it.walkFactor(walk.link, walk.isReversed, walk.toInstance)
                        estimateBranchingFactor(it, walk.toInstanceName, base, covered, walk.link)
                    } ?: Double.NaN
                    candidates.add(WalkCandidate(walk, prio, nacCost, factor))
                }

                val best = if (onlyChoice) candidates.first() else candidates.minWith(comparator(costModel))

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
         * Returns the comparator that decides which candidate the greedy loop takes next.
         *
         * **Without statistics** ([costModel] `null`) the original ordering is used unchanged.
         *
         * **With statistics** the estimate is consulted only where it is a fair comparison:
         *
         * 1. **A non-increasing step beats a fan-out.** A candidate whose estimated branching
         *    factor is at most [NON_INCREASING_FACTOR] cannot enlarge the partial-match set,
         *    so doing it earlier can only shrink what every later step processes — it weakly
         *    dominates every alternative regardless of the rest of the plan. This is what
         *    fixes the pathologies the cost model exists for: a constant guard on a singleton
         *    class (factor 0.1), a class with no instances at all (factor 0), a to-one walk.
         * 2. **Between two candidates of the same kind, the smaller factor wins.** Two scans
         *    cost the same to run, as do two walks, so their output sizes are directly
         *    comparable — this is the classic model-sensitive rule, and it is what picks the
         *    right starting class and the right walk order.
         * 3. **Between a scan and a walk that both fan out, the estimate is not used**; the
         *    legacy structural criteria decide.
         *
         * Rule 3 exists because the branching factor measures how many partial matches a step
         * *produces*, not what it *costs*. A [BaseStep.VertexScan] re-reads the whole vertex
         * list for every incoming traverser, while a walk only visits its fan-out, so the two
         * are not on the same scale. Ordering across kinds by factor alone reliably chose a
         * small fan-out ahead of a mid-sized scan and then paid for that scan once per
         * traverser, and it also demoted the "unlock a cheap application condition early"
         * rule that does the real pruning in NAC-heavy patterns. Measured as ~25 % slower on
         * the scrum case study for no measurable gain elsewhere; see
         * `tools/MATCH_PLANNER_COST_MODEL_REPORT.md`.
         *
         * Wherever the estimates are equal within [MatchCostModel.approximatelyEqual], the
         * legacy tie-breakers decide, so a model whose statistics carry no signal keeps
         * producing exactly the legacy order.
         *
         * @param costModel The cost model, or `null` when no statistics were supplied.
         */
        private fun candidateComparator(costModel: MatchCostModel?): Comparator<TraversalCandidate> {
            val legacy = compareByDescending<TraversalCandidate> { it.classPriority }
                .thenBy { it.nacUnlockCost }
                .thenBy { if (it is ScanCandidate) 1 else 0 }
            if (costModel == null) return legacy

            return Comparator<TraversalCandidate> { a, b ->
                val aShrinks = a.branchingFactor < SHRINKING_FACTOR
                val bShrinks = b.branchingFactor < SHRINKING_FACTOR
                when {
                    // A step that strictly shrinks the result is taken ahead of one that
                    // does not; among several, the one that shrinks it most.
                    aShrinks != bShrinks -> if (aShrinks) -1 else 1
                    !aShrinks -> 0
                    costModel.approximatelyEqual(a.branchingFactor, b.branchingFactor) -> 0
                    else -> a.branchingFactor.compareTo(b.branchingFactor)
                }
            }.then(legacy).thenBy { it.targetName }
        }

        /**
         * Estimates the factor by which covering [name] multiplies the number of partial
         * matches.
         *
         * The estimate starts from the raw access cost of the step ([baseFactor] — a scan's
         * filtered class size, or a walk's average fan-out) and then applies the selectivity
         * of every constraint that becomes checkable *because of* this coverage:
         *
         * - **Cycle closure.** Any other pattern link whose two endpoints are both covered
         *   after this step becomes a `where(...)` edge check.  Its selectivity is the
         *   probability that an arbitrary vertex pair carries such an edge, which is tiny
         *   for any realistic model — this is why a step that closes a cycle beats a step
         *   with the same fan-out that does not.
         * - **Attribute predicates.** Deferred property constraints and where-clauses whose
         *   dependencies are all covered after this step, scored with the System R defaults.
         *
         * Application conditions are deliberately *not* folded in here; they keep their own
         * ordering criterion ([minConditionCostUnlockedBy]) as a tie-breaker, because their
         * benefit is not a selectivity but the point in the plan at which they can run.
         *
         * @param costModel The model-sensitive cost model.
         * @param name The instance name that this candidate covers.
         * @param baseFactor The candidate's raw access factor.
         * @param covered Instance names covered before this step.
         * @param coveringLink The link consumed by a walk candidate, excluded from the cycle
         *        closure scan; `null` for a scan candidate.
         * @return The estimated branching factor, `0.0` when the step cannot produce any
         *         partial match at all.
         */
        private fun estimateBranchingFactor(
            costModel: MatchCostModel,
            name: String,
            baseFactor: Double,
            covered: Set<String>,
            coveringLink: TypedPatternLinkElement?
        ): Double {
            if (baseFactor == 0.0) return 0.0
            var factor = baseFactor

            // "Covered after this step" is `covered + name`, tested without materialising the
            // union: this runs for every candidate of every step of every match.
            fun coveredAfter(other: String) = other == name || other in covered

            for (link in graph.links) {
                if (link === coveringLink) continue
                val srcName = link.link.source.objectName
                val tgtName = link.link.target.objectName
                if (!coveredAfter(srcName) || !coveredAfter(tgtName)) continue
                if (srcName in covered && tgtName in covered) continue
                factor *= costModel.linkCheckSelectivity(link)
                if (factor == 0.0) return 0.0
            }

            for ((dependencies, selectivity) in deferredPredicates) {
                if (!dependencies.all { coveredAfter(it) }) continue
                if (covered.containsAll(dependencies)) continue
                factor *= selectivity
            }
            return factor
        }

        /**
         * Every predicate in the pattern that cannot be inlined the moment its owning
         * instance is covered, paired with its estimated selectivity.
         *
         * Two kinds contribute:
         * - **Deferred property constraints** — `x.p OP expr` where `expr` references other
         *   pattern nodes.  Its dependencies are `{x}` plus the instances `expr` reads.
         * - **Where clauses** — dependencies are the instances the expression reads.
         *
         * Dependency sets are restricted to *matchable* names: referenced context instances
         * are pre-bound and pattern variables are resolved to the instances they read
         * through [MatchPlanGraph.resolveTransitiveNodeDeps], so neither ever blocks a
         * predicate from being counted.
         *
         * Constant property filters are excluded — they are already priced into
         * [MatchCostModel.scanFactor] and [MatchCostModel.walkFactor].
         */
        private val deferredPredicates: List<Pair<Set<String>, Double>> by lazy {
            val costModel = graph.costModel ?: return@lazy emptyList()
            val result = mutableListOf<Pair<Set<String>, Double>>()

            for (instance in graph.instances) {
                val owner = instance.objectInstance.name
                for (property in instance.objectInstance.properties) {
                    if (property.operator == "=") continue
                    val referenced = graph.nodeAnalyzer.findReferencedNodes(property.value)
                    if (referenced.isEmpty() && !graph.isCollectionExpression(property.value)) continue
                    val dependencies = matchableDependencies(referenced) + owner
                    result.add(dependencies to costModel.operatorSelectivity(property.operator))
                }
            }

            for (clause in graph.whereClauses) {
                val referenced = graph.nodeAnalyzer.findReferencedNodes(clause.whereClause.expression)
                result.add(
                    matchableDependencies(referenced) to
                        costModel.predicateSelectivity(clause.whereClause.expression)
                )
            }
            result
        }

        /**
         * Resolves [referencedNodes] through pattern variables and keeps only the names that
         * must be covered by the structural traversal.
         */
        private fun matchableDependencies(referencedNodes: Set<String>): Set<String> =
            graph.resolveTransitiveNodeDeps(referencedNodes).filterTo(mutableSetOf()) {
                it in graph.matchableNames
            }

        /** One-line rendering of [step] for [DEBUG_STRUCTURAL_ORDER_PROPERTY]. */
        private fun describe(step: StructuralStep): String = when (step) {
            is StructuralStep.CoverByVertex -> "scan ${step.name}"
            is StructuralStep.CoverByWalk -> "walk ${step.fromName}->${step.toName}"
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
         * rather than taken from the pre-computed field, which the greedy loop fills in
         * before the final traversal flow is known.
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
         * - **Anchor-only block** (no condition-exclusive nodes): cost = 1.
         *   The check reduces to a single edge traversal or property filter inside
         *   `where(...)`.
         * - **Anchored block** (every component is attached to the match): cost = 10 ×
         *   edge count. Proportional to the number of edge steps inside the block.
         * - **Block with an unanchored component**: cost = 1000 + 10 × edge count.
         *   Expensive because such a component requires an uncorrelated vertex scan.
         *
         * @param pending The condition whose cost is to be estimated.
         * @return A non-negative integer; lower values represent cheaper conditions.
         */
        private fun estimatePendingConditionCost(pending: PendingCondition): Int {
            val block = pending.block
            if (block.instances.isEmpty()) return 1
            val hasUnanchoredComponent = splitIntoComponents(block).any { it.anchors.isEmpty() }
            return if (hasUnanchoredComponent) 1000 + block.links.size * 10 else block.links.size * 10
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
         * Every unemitted condition that [isConditionReady] accepts is compiled here. All
         * ready conditions are sorted by [computeConditionCost] (cheapest first) before
         * emission, so that less expensive checks execute before more expensive ones.
         *
         * Everything the ready blocks read from the enclosing match is bound *before* any of
         * them is compiled: resolving a variable emits a step that moves the traverser, which
         * would invalidate the `needsSelect` decision of a condition compiled earlier.
         *
         * The `needsSelect` flag of each emitted condition is derived from [currentNode]
         * at the time of emission: `select()` is inserted only when the Gremlin traverser
         * is not already positioned on the anchor node.
         *
         * @return `true` if at least one condition was emitted.
         */
        private fun tryInlineConditions(): Boolean {
            val ready = graph.conditions.withIndex()
                .filter { (index, pending) -> index !in emittedConditionIndices && isConditionReady(pending) }
            if (ready.isEmpty()) return false

            for ((_, pending) in ready) {
                conditionExternalDependencies(pending.block).forEach { resolve(it) }
            }

            val readyConditions = ready
                .map { (index, pending) -> index to buildApplicationCondition(pending.block) }
                .sortedBy { computeConditionCost(it.second) }
            for ((index, ac) in readyConditions) {
                baseSteps.add(ac)
                emittedConditionIndices.add(index)
            }
            return true
        }

        /**
         * Splits the graph of [block] into its connected components.
         *
         * Nodes are the condition-exclusive instances plus every node name appearing on a
         * link; two nodes belong to the same component when a link connects them. Instances
         * without links, and references to enclosing nodes that carry only property
         * constraints, each form a component of their own.
         *
         * @param block The condition graph to split.
         * @return The components, anchored ones first, so that the cheap components are
         *         walked before those needing an uncorrelated vertex scan.
         */
        private fun splitIntoComponents(block: ApplicationConditionBlock): List<ConditionComponent> {
            val parent = mutableMapOf<String, String>()

            fun find(name: String): String {
                var root = name
                while (parent[root] != null && parent[root] != root) root = parent[root]!!
                return root
            }

            fun union(a: String, b: String) {
                parent.putIfAbsent(a, a)
                parent.putIfAbsent(b, b)
                val rootA = find(a)
                val rootB = find(b)
                if (rootA != rootB) parent[rootB] = rootA
            }

            for (instance in block.instances) parent.putIfAbsent(instance.objectInstance.name, instance.objectInstance.name)
            for (link in block.links) union(link.link.source.objectName, link.link.target.objectName)
            for (reference in block.references) parent.putIfAbsent(reference.objectInstance.name, reference.objectInstance.name)

            val instancesByRoot = block.instances.groupBy { find(it.objectInstance.name) }
            val linksByRoot = block.links.groupBy { find(it.link.source.objectName) }
            val roots = LinkedHashSet<String>()
            for (instance in block.instances) roots.add(find(instance.objectInstance.name))
            for (link in block.links) roots.add(find(link.link.source.objectName))
            for (reference in block.references) roots.add(find(reference.objectInstance.name))

            val components = roots.map { root ->
                val links = linksByRoot[root] ?: emptyList()
                val instances = instancesByRoot[root] ?: emptyList()
                ConditionComponent(
                    instances = instances,
                    links = links,
                    anchors = ConditionTraversalUtils.findAnchorNames(links, block.instanceNames, graph.matchableNames) +
                        block.references.map { it.objectInstance.name }
                            .filter { find(it) == root && it in graph.matchableNames }
                            .toSet()
                )
            }

            return components.sortedBy { if (it.anchors.isEmpty()) 1 else 0 }
        }

        /**
         * Reports whether [pending] can be compiled at the current point of the plan.
         *
         * Two things have to hold: every main-pattern node the condition attaches to or
         * constrains injectively must be covered ([MatchPlanGraph.pendingConditionRequiredNodes]),
         * and everything its where clauses read from the enclosing match must be resolvable
         * ([conditionExternalDependencies]) — a clause comparing a condition node against a
         * pattern variable cannot be emitted before that variable is bound.
         *
         * @param pending The condition to check.
         * @return `true` when the condition can be compiled now.
         */
        private fun isConditionReady(pending: PendingCondition): Boolean {
            if (!graph.pendingConditionRequiredNodes(pending).all { it in coveredInstances }) return false
            return conditionExternalDependencies(pending.block).all { canResolve(it) }
        }

        /**
         * Constructs a [BaseStep.ApplicationCondition] for [block] by building the
         * inner Gremlin traversal from scratch.
         *
         * The whole block becomes a single traversal, so the condition only holds when all
         * of its components match at once. The first component starts the chain — from an
         * anchor of the enclosing match when it has one, otherwise from a vertex scan — and
         * every further component is started by a [BaseStep.SelectNode] onto its anchor or
         * by a mid-traversal [BaseStep.VertexScan].
         *
         * Within a component the links are walked in BFS order. For each walk:
         * - A [BaseStep.EdgeWalk] is emitted.
         * - If the destination is a node of the enclosing match, a [BaseStep.EqualityFilter]
         *   verifies that the walk reached exactly that bound vertex.
         * - If the destination belongs to the condition, its property constraints are
         *   appended via [buildConditionPropertySteps].
         *
         * The block's where clauses are emitted by [emitReadyConditionWhereClauses] as soon
         * as the chain has reached every condition node they read, so that a failing
         * constraint prunes the walk early. Clauses that read nothing but the enclosing
         * match — and any clause left over when the block has no graph at all — are appended
         * once the walk is complete.
         *
         * Injective constraints for the condition's nodes are computed by
         * [buildConditionInjectiveConstraints] and attached to the returned step.
         *
         * @param block The condition graph to compile.
         * @return The fully assembled [BaseStep.ApplicationCondition].
         */
        private fun buildApplicationCondition(block: ApplicationConditionBlock): BaseStep.ApplicationCondition {
            val conditionNames = block.instanceNames
            val instanceMap = block.instances.associateBy { it.objectInstance.name }
            val referenceMap = block.references.associateBy { it.objectInstance.name }
            val innerSteps = mutableListOf<BaseStep>()
            val traversalOrder = mutableListOf<String>()
            val pendingWhereClauses = block.whereClauses.toMutableList()

            var outerAnchor: String? = null
            var needsSelect = false

            for ((index, component) in splitIntoComponents(block).withIndex()) {
                val start: String
                if (component.anchors.isNotEmpty()) {
                    start = ConditionTraversalUtils.selectBestAnchor(
                        component.anchors, component.links, graph.metamodelData
                    ) ?: component.anchors.first()
                    if (index == 0) {
                        outerAnchor = start
                        needsSelect = start != this.currentNode
                    } else {
                        innerSteps.add(BaseStep.SelectNode(start))
                    }
                } else {
                    val startInstance = component.instances.firstOrNull() ?: continue
                    start = startInstance.objectInstance.name
                    innerSteps.add(BaseStep.VertexScan(start, startInstance.objectInstance.className, null))
                    innerSteps.addAll(buildConditionPropertySteps(block, startInstance))
                    traversalOrder.add(start)
                }
                referenceMap[start]?.let { innerSteps.addAll(buildConditionPropertySteps(block, it)) }
                emitReadyConditionWhereClauses(block, pendingWhereClauses, traversalOrder, innerSteps)

                val orderedLinks = ConditionTraversalUtils.orderLinksByBFS(
                    component.links, start, graph.metamodelData
                )
                var currentInner = start

                for ((link, isReversed) in orderedLinks) {
                    val fromName = if (isReversed) link.link.target.objectName else link.link.source.objectName
                    val toName = if (isReversed) link.link.source.objectName else link.link.target.objectName
                    val toIsConditionNode = toName in conditionNames
                    val toInstance = if (toIsConditionNode) instanceMap[toName] else null

                    innerSteps.add(BaseStep.EdgeWalk(
                        link = link,
                        isReversed = isReversed,
                        fromInstanceName = fromName,
                        toInstanceName = toName,
                        toClassName = toInstance?.objectInstance?.className,
                        toVertexId = null,
                        needsSelect = fromName != currentInner
                    ))

                    if (toIsConditionNode && toInstance != null) {
                        if (traversalOrder.none { it == toName }) traversalOrder.add(toName)
                        innerSteps.addAll(buildConditionPropertySteps(block, toInstance))
                    } else {
                        innerSteps.add(BaseStep.EqualityFilter(toName))
                        referenceMap[toName]?.let { innerSteps.addAll(buildConditionPropertySteps(block, it)) }
                    }
                    emitReadyConditionWhereClauses(block, pendingWhereClauses, traversalOrder, innerSteps)
                    currentInner = toName
                }
            }

            for (clause in pendingWhereClauses) {
                innerSteps.add(BaseStep.WhereFilter(clause, conditionNodesOf(clause, block)))
            }

            return BaseStep.ApplicationCondition(
                block.isNegative, outerAnchor, needsSelect, innerSteps,
                buildConditionInjectiveConstraints(block, traversalOrder),
                block.name,
                block.instanceNames
            )
        }

        /**
         * Returns the inline property constraint steps for [instance] inside an
         * application condition.
         *
         * All comparison operators (`==`, `!=`, `<`, `>`, `<=`, `>=`) are included.
         * Names of the block's own nodes that the compared expression reads are recorded on
         * the step, so that the condition chain labels them.
         * The assignment operator (`=`) is skipped — it is handled by the modification
         * applier, not by the match plan.
         * The [BaseStep.InlinePropertyConstraint.isConstant] flag is determined the same
         * way as in [addInlinePropertyConstraints].  Variable-referencing and collection
         * expressions are included unconditionally because they are emitted inside a
         * `where(...)` block where the outer traversal state is already fixed.
         *
         * @param block The condition graph the instance belongs to.
         * @param instance A condition instance whose properties are to be emitted.
         * @return The list of inline property constraint steps for [instance].
         */
        private fun buildConditionPropertySteps(
            block: ApplicationConditionBlock,
            instance: TypedPatternObjectInstanceElement
        ): List<BaseStep.InlinePropertyConstraint> = instance.objectInstance.properties.mapNotNull { property ->
            if (property.operator == "=") return@mapNotNull null
            val referencedNodes = graph.conditionNodeAnalyzer.findReferencedNodes(property.value)
            val isConstant = referencedNodes.isEmpty() && !graph.isCollectionExpression(property.value)
            BaseStep.InlinePropertyConstraint(
                instance.objectInstance.name, instance.objectInstance.className, property, isConstant,
                referencedNodes.filter { it in block.instanceNames }.toSet()
            )
        }

        /**
         * Emits every where clause of [block] whose condition-local dependencies are
         * already reached by the chain built so far.
         *
         * A clause is emitted at the earliest point at which it can be evaluated, so that a
         * failing constraint prunes the condition's walk instead of being checked only after
         * the whole graph has been found. Names that belong to the enclosing match need no
         * check here: a condition is only compiled once everything it reads from the match
         * is bound (see [conditionExternalDependencies]).
         *
         * Emitted clauses are removed from [pendingWhereClauses].
         *
         * @param block The condition graph being compiled.
         * @param pendingWhereClauses The clauses not yet emitted; mutated in place.
         * @param reachedNodes The condition-local nodes the chain has reached so far.
         * @param innerSteps The chain built so far; the ready clauses are appended to it.
         */
        private fun emitReadyConditionWhereClauses(
            block: ApplicationConditionBlock,
            pendingWhereClauses: MutableList<TypedPatternWhereClauseElement>,
            reachedNodes: List<String>,
            innerSteps: MutableList<BaseStep>
        ) {
            val iterator = pendingWhereClauses.iterator()
            while (iterator.hasNext()) {
                val clause = iterator.next()
                val conditionNodes = conditionNodesOf(clause, block)
                if (!reachedNodes.containsAll(conditionNodes)) continue
                innerSteps.add(BaseStep.WhereFilter(clause, conditionNodes))
                iterator.remove()
            }
        }

        /**
         * Returns the names of [block]'s own nodes that [clause] reads.
         *
         * @param clause The where clause to inspect.
         * @param block The condition graph the clause belongs to.
         * @return The condition-local node names read by the clause.
         */
        private fun conditionNodesOf(
            clause: TypedPatternWhereClauseElement,
            block: ApplicationConditionBlock
        ): Set<String> =
            graph.conditionNodeAnalyzer.findReferencedNodes(clause.whereClause.expression)
                .filter { it in block.instanceNames }
                .toSet()

        /**
         * Returns everything a condition reads from outside its own graph: the instances and
         * variables of the enclosing match that its where clauses and the property
         * constraints of its nodes refer to.
         *
         * These have to be bound before the condition is compiled, because the condition's
         * sub-traversal reaches them with `select(label)` and a label only exists once the
         * step producing it has been emitted.
         *
         * @param block The condition graph to inspect.
         * @return The names the block reads from the enclosing match.
         */
        private fun conditionExternalDependencies(block: ApplicationConditionBlock): Set<String> {
            val expressions = block.whereClauses.map { it.whereClause.expression } +
                (block.instances + block.references)
                    .flatMap { instance -> instance.objectInstance.properties }
                    .filter { property -> property.operator != "=" }
                    .map { property -> property.value }
            return expressions
                .flatMap { expression -> graph.conditionNodeAnalyzer.findReferencedNodes(expression) }
                .filter { name -> name !in block.instanceNames }
                .toSet()
        }

        /**
         * Builds the injective-constraint map for the inner traversal of an application
         * condition.
         *
         * For each condition node in traversal order, the map records the Gremlin step
         * labels of:
         * 1. Every main-pattern instance of the same class (preventing the condition node
         *    from matching a vertex already bound in the main pattern).
         * 2. Every earlier condition node of the same class (mutual injectivity among the
         *    condition's own nodes, across component boundaries as well).
         *
         * For a NAC whose graph has a single node, constraint (1) may be omitted for a
         * specific main-pattern node when [MatchPlanGraph.canOmitNacInjectiveConstraint]
         * returns `true` (see that method for the correctness argument).
         *
         * The map is keyed by [VariableBinding.stepLabel] of the condition node.
         *
         * @param block The condition graph.
         * @param traversalOrder The condition nodes in the order the chain reaches them.
         * @return A map from condition node step label to the list of step labels it must
         *         differ from.
         */
        private fun buildConditionInjectiveConstraints(
            block: ApplicationConditionBlock,
            traversalOrder: List<String>
        ): Map<String, List<String>> {
            val constraints = mutableMapOf<String, MutableList<String>>()
            val instanceMap = block.instances.associateBy { it.objectInstance.name }
            val isSingleNodeNac = block.isNegative && block.instances.size == 1

            for ((i, conditionNode) in traversalOrder.withIndex()) {
                val conditionClass = instanceMap[conditionNode]?.objectInstance?.className ?: continue
                val nodeLabel = VariableBinding.stepLabel(conditionNode)

                for (mainInst in graph.instances) {
                    val mainClass = mainInst.objectInstance.className ?: continue
                    if (conditionClass != mainClass) continue
                    if (isSingleNodeNac &&
                        graph.canOmitNacInjectiveConstraint(conditionNode, mainInst.objectInstance.name, block.links)
                    ) continue
                    constraints.getOrPut(nodeLabel) { mutableListOf() }
                        .add(VariableBinding.stepLabel(mainInst.objectInstance.name))
                }

                for (j in 0 until i) {
                    val previousNode = traversalOrder[j]
                    val previousClass = instanceMap[previousNode]?.objectInstance?.className ?: continue
                    if (conditionClass == previousClass) {
                        constraints.getOrPut(nodeLabel) { mutableListOf() }
                            .add(VariableBinding.stepLabel(previousNode))
                    }
                }
            }
            return constraints
        }

        /**
         * Estimates the Gremlin evaluation cost of [condition] for ordering purposes.
         *
         * Scoring:
         * - **Contains an unanchored component** (a vertex scan inside the chain):
         *   1000 + 10 × edge count.  Expensive because an uncorrelated vertex scan is
         *   required inside `where(...)`.
         * - **Anchored**: 10 × edge count + 1 if `needsSelect`.  Proportional to the
         *   number of edge traversals, with a small penalty for needing `select()`.
         *
         * @param condition The assembled application condition.
         * @return A non-negative integer cost estimate; lower is cheaper.
         */
        private fun computeConditionCost(condition: BaseStep.ApplicationCondition): Int {
            val edgeCount = condition.innerSteps.count { it is BaseStep.EdgeWalk }
            val hasVertexScan = condition.innerSteps.any { it is BaseStep.VertexScan }
            if (hasVertexScan) return 1000 + edgeCount * 10
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
         * By this point every main-pattern instance is covered, so each remaining condition
         * can be compiled unconditionally by [buildApplicationCondition] — only the
         * variables its where clauses read may still be pending, and those are bound first.
         * All remaining conditions are sorted by [computeConditionCost] before emission so
         * that cheaper checks execute first.
         */
        private fun emitRemainingConditions() {
            val pending = graph.conditions.withIndex().filter { (index, _) -> index !in emittedConditionIndices }
            for ((_, condition) in pending) {
                conditionExternalDependencies(condition.block).forEach { resolve(it) }
            }
            val remaining = pending
                .map { (_, condition) -> buildApplicationCondition(condition.block) }
                .sortedBy { computeConditionCost(it) }
            for (ac in remaining) baseSteps.add(ac)
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
