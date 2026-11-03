package com.mdeo.optimizer.moea

import org.moeaframework.algorithm.IBEA
import org.moeaframework.algorithm.NSGAII
import org.moeaframework.algorithm.PAES
import org.moeaframework.algorithm.PESA2
import org.moeaframework.algorithm.RandomSearch
import org.moeaframework.algorithm.SMSEMOA
import org.moeaframework.algorithm.SPEA2
import org.moeaframework.algorithm.VEGA
import org.moeaframework.core.Solution
import org.moeaframework.core.fitness.FitnessEvaluator
import org.moeaframework.core.fitness.HypervolumeFitnessEvaluator
import org.moeaframework.core.initialization.Initialization
import org.moeaframework.core.operator.Mutation
import org.moeaframework.core.operator.Variation
import org.moeaframework.core.population.NondominatedPopulation
import org.moeaframework.core.population.NondominatedSortingPopulation
import org.moeaframework.core.population.Population
import org.moeaframework.problem.Problem

/**
 * NSGA-II subclass that delegates evaluation to an [EvaluationCoordinator].
 *
 * Overrides `evaluateAll` and `evaluate` to route solutions through the coordinator,
 * and overrides `iterate` to detect discarded solutions before each generation.
 * Discards are queued and sent alongside the next evaluation batch.
 *
 * **Crossover (`variation = genetic`):** `iterate` selects groups of `arity` parents by binary
 * tournament from a shuffled pool (refilled while it holds fewer than `2 × arity`) and adds every
 * child of every group to the offspring until there are at least `populationSize` of them. All
 * offspring are evaluated in one batch, merged with the population, and the union is truncated
 * to `populationSize` by non-dominated sorting and crowding. When `populationSize` is not a
 * multiple of `parents`, the last group overshoots by up to `parents - 1` children: they are
 * evaluated and compete in the truncation like the others. The population size is unchanged.
 *
 * @param problem The problem definition (typically [DelegatingProblem]).
 * @param populationSize The population size.
 * @param variation The variation operator (typically [DelegatingVariation]).
 * @param initialization The initialization strategy.
 * @param coordinator The evaluation coordinator managing solution lifecycle.
 */
class DelegatingNSGAII(
    problem: Problem,
    populationSize: Int,
    variation: Variation,
    initialization: Initialization,
    private val coordinator: EvaluationCoordinator
) : NSGAII(
    problem,
    populationSize,
    NondominatedSortingPopulation(),
    null,
    null,
    variation,
    initialization
) {
    override fun evaluateAll(solutions: Iterable<Solution>) {
        numberOfEvaluations += coordinator.batchEvaluateAndUpdate(solutions)
    }

    override fun evaluate(solution: Solution) {
        coordinator.singleEvaluateAndUpdate(solution)
        numberOfEvaluations++
    }

    override fun iterate() {
        coordinator.prepareIteration(getPopulation())
        super.iterate()
    }
}

/**
 * SPEA2 subclass that delegates evaluation to an [EvaluationCoordinator].
 *
 * **Crossover (`variation = genetic`):** `iterate` selects groups of `arity` parents and adds
 * every child until there are at least `numberOfOffspring` (here `populationSize`) offspring, so
 * the last group may overshoot by up to `parents - 1` children. All are evaluated in one batch,
 * merged with the population, and the union is truncated to `populationSize` by strength fitness
 * and density. The population size is unchanged.
 *
 * @param problem The problem definition (typically [DelegatingProblem]).
 * @param populationSize The population size.
 * @param initialization The initialization strategy.
 * @param variation The variation operator (typically [DelegatingVariation]).
 * @param numberOfOffspring The number of offspring generated each iteration.
 * @param k The niching parameter for crowding distance (typically `1`).
 * @param coordinator The evaluation coordinator managing solution lifecycle.
 */
class DelegatingSPEA2(
    problem: Problem,
    populationSize: Int,
    initialization: Initialization,
    variation: Variation,
    numberOfOffspring: Int,
    k: Int,
    private val coordinator: EvaluationCoordinator
) : SPEA2(problem, populationSize, initialization, variation, numberOfOffspring, k) {

    override fun evaluateAll(solutions: Iterable<Solution>) {
        numberOfEvaluations += coordinator.batchEvaluateAndUpdate(solutions)
    }

    override fun evaluate(solution: Solution) {
        coordinator.singleEvaluateAndUpdate(solution)
        numberOfEvaluations++
    }

    override fun iterate() {
        coordinator.prepareIteration(getPopulation())
        super.iterate()
    }
}

/**
 * IBEA subclass that delegates evaluation to an [EvaluationCoordinator].
 *
 * **Crossover (`variation = genetic`):** `iterate` selects groups of `arity` parents and adds
 * every child until there are at least `populationSize` offspring (the last group may overshoot
 * by up to `parents - 1`). All are evaluated in one batch and merged with the population; the
 * indicator-worst solution is removed until `populationSize` remain. The population size is
 * unchanged.
 *
 * @param problem The problem definition (typically [DelegatingProblem]).
 * @param populationSize The population size.
 * @param initialization The initialization strategy.
 * @param variation The variation operator (typically [DelegatingVariation]).
 * @param fitnessEvaluator The indicator-based fitness evaluator.
 * @param coordinator The evaluation coordinator managing solution lifecycle.
 */
class DelegatingIBEA(
    problem: Problem,
    populationSize: Int,
    initialization: Initialization,
    variation: Variation,
    fitnessEvaluator: HypervolumeFitnessEvaluator,
    private val coordinator: EvaluationCoordinator
) : IBEA(problem, populationSize, null, initialization, variation, fitnessEvaluator) {

    override fun evaluateAll(solutions: Iterable<Solution>) {
        numberOfEvaluations += coordinator.batchEvaluateAndUpdate(solutions)
    }

    override fun evaluate(solution: Solution) {
        coordinator.singleEvaluateAndUpdate(solution)
        numberOfEvaluations++
    }

    override fun iterate() {
        coordinator.prepareIteration(getPopulation())
        super.iterate()
    }
}

/**
 * SMS-EMOA subclass that delegates evaluation to an [EvaluationCoordinator].
 *
 * SMS-EMOA evaluates a single offspring per iteration via `evaluate()`, making
 * the single-solution override critical.
 *
 * **Crossover (`variation = genetic`):** SMS-EMOA is steady state: its `iterate` selects one
 * group, calls the variation, and evaluates and inserts only the first returned child (then
 * removes the worst solution), silently dropping the others. So that every child of a group is
 * evaluated and enters the population, a variation returning several children is wrapped in a
 * [QueuedChildrenVariation]: it evaluates all children of a group in one batch, returns the first,
 * and returns one queued child per following iteration (ignoring that iteration's selected
 * parents) before it selects a new group. Each child is thus inserted by a steady-state step of
 * its own (add one, remove the worst), the population size is unchanged, and the evaluations of
 * a group still run in parallel. Children still queued when the run terminates were evaluated
 * but never inserted. With `variation = mutation` the variation is not wrapped and SMS-EMOA
 * runs as before.
 *
 * @param problem The problem definition (typically [DelegatingProblem]).
 * @param populationSize The population size.
 * @param initialization The initialization strategy.
 * @param variation The variation operator (typically [DelegatingVariation]).
 * @param fitnessEvaluator The fitness evaluator for SMS-EMOA selection.
 * @param coordinator The evaluation coordinator managing solution lifecycle.
 */
class DelegatingSMSEMOA(
    problem: Problem,
    populationSize: Int,
    initialization: Initialization,
    variation: Variation,
    fitnessEvaluator: FitnessEvaluator,
    private val coordinator: EvaluationCoordinator
) : SMSEMOA(problem, populationSize, initialization, QueuedChildrenVariation.wrap(variation), fitnessEvaluator) {

    /** The wrapping variation of a crossover run, or `null` for a mutation run. */
    private val queuedVariation = getVariation() as? QueuedChildrenVariation

    init {
        queuedVariation?.evaluateChildren = { children -> evaluateAll(children) }
    }

    override fun evaluateAll(solutions: Iterable<Solution>) {
        numberOfEvaluations += coordinator.batchEvaluateAndUpdate(solutions)
    }

    override fun evaluate(solution: Solution) {
        // A queued child was evaluated with its group.
        if (solution.getAttribute(QueuedChildrenVariation.EVALUATED_WITH_GROUP_KEY) == true) return
        coordinator.singleEvaluateAndUpdate(solution)
        numberOfEvaluations++
    }

    override fun iterate() {
        val pending = queuedVariation?.pending
        if (pending.isNullOrEmpty()) {
            coordinator.prepareIteration(getPopulation())
        } else {
            // Queued children live on the workers but are not in the population yet: they must
            // not be taken for discarded solutions.
            val live = Population()
            live.addAll(getPopulation())
            live.addAll(pending)
            coordinator.prepareIteration(live)
        }
        super.iterate()
    }
}

/**
 * Lets a steady-state algorithm that inserts one child per iteration (SMS-EMOA) insert every
 * child of a variation that returns several: the first call evaluates all children of a group at
 * once through [evaluateChildren] and returns the first; the following calls return one queued
 * child each, ignoring their parents, until the queue is empty.
 *
 * @param inner The variation returning several children per call.
 */
class QueuedChildrenVariation private constructor(private val inner: Variation) : Variation {

    /** The evaluated children of the last group that were not returned yet. */
    val pending: ArrayDeque<Solution> = ArrayDeque()

    /** Evaluates the children of a group in one batch; set by the algorithm. */
    var evaluateChildren: (List<Solution>) -> Unit = { error("No evaluation wired for queued children") }

    override fun getName(): String = inner.name

    override fun getArity(): Int = inner.arity

    override fun evolve(parents: Array<Solution>): Array<Solution> {
        pending.removeFirstOrNull()?.let { return arrayOf(it) }
        val children = inner.evolve(parents)
        if (children.size <= 1) return children
        evaluateChildren(children.toList())
        children.forEach { it.setAttribute(EVALUATED_WITH_GROUP_KEY, true) }
        pending.addAll(children.drop(1))
        return arrayOf(children.first())
    }

    companion object {
        /** Attribute marking a child that was evaluated with its group, before it is inserted. */
        const val EVALUATED_WITH_GROUP_KEY = "evaluatedWithGroup"

        /**
         * Wraps [variation] when it returns several children per call (a
         * [DelegatingCrossoverVariation]); returns any other variation unchanged.
         */
        fun wrap(variation: Variation): Variation =
            if (variation is DelegatingCrossoverVariation) QueuedChildrenVariation(variation) else variation
    }
}

/**
 * VEGA subclass that delegates evaluation to an [EvaluationCoordinator].
 *
 * **Crossover (`variation = genetic`):** VEGA selects `populationSize` parents (per-objective
 * subpopulations), shuffles them, clears the population and refills it with offspring: it calls
 * the variation on consecutive groups of `arity` parents (wrapping around) and adds children one
 * by one until the population is full. When `populationSize` is not a multiple of `parents`, the
 * remaining children of the last group are dropped before evaluation (they never become tasks).
 * Only the children added are evaluated, in one batch; the population size is unchanged. VEGA is
 * not elitist: parents do not survive.
 *
 * @param problem The problem definition (typically [DelegatingProblem]).
 * @param populationSize The population size.
 * @param initialization The initialization strategy.
 * @param variation The variation operator (typically [DelegatingVariation]).
 * @param coordinator The evaluation coordinator managing solution lifecycle.
 */
class DelegatingVEGA(
    problem: Problem,
    populationSize: Int,
    initialization: Initialization,
    variation: Variation,
    private val coordinator: EvaluationCoordinator
) : VEGA(problem, populationSize, Population(), NondominatedPopulation(), initialization, variation) {

    override fun evaluateAll(solutions: Iterable<Solution>) {
        numberOfEvaluations += coordinator.batchEvaluateAndUpdate(solutions)
    }

    override fun evaluate(solution: Solution) {
        coordinator.singleEvaluateAndUpdate(solution)
        numberOfEvaluations++
    }

    override fun iterate() {
        coordinator.prepareIteration(getPopulation())
        super.iterate()
    }
}

/**
 * PESA2 subclass that delegates evaluation to an [EvaluationCoordinator].
 *
 * PESA2 uses an adaptive grid archive as the primary solution store, so lifecycle
 * tracking operates on the archive rather than the population.
 *
 * **Crossover (`variation = genetic`):** PESA2 clears its population, selects groups of `arity`
 * parents from the archive by region-based selection, and adds every child until the population
 * holds at least `populationSize` offspring (the last group may overshoot by up to
 * `parents - 1`). All are evaluated in one batch and offered to the adaptive grid archive, whose
 * capacity bounds what survives. The population is rebuilt every iteration.
 *
 * @param problem The problem definition (typically [DelegatingProblem]).
 * @param populationSize The population size.
 * @param variation The variation operator (typically [DelegatingVariation]).
 * @param initialization The initialization strategy.
 * @param bisections The number of bisections for the adaptive grid archive.
 * @param archiveSize The capacity of the adaptive grid archive.
 * @param coordinator The evaluation coordinator managing solution lifecycle.
 */
class DelegatingPESA2(
    problem: Problem,
    populationSize: Int,
    variation: Variation,
    initialization: Initialization,
    bisections: Int,
    archiveSize: Int,
    private val coordinator: EvaluationCoordinator
) : PESA2(problem, populationSize, variation, initialization, bisections, archiveSize) {

    override fun evaluateAll(solutions: Iterable<Solution>) {
        numberOfEvaluations += coordinator.batchEvaluateAndUpdate(solutions)
    }

    override fun evaluate(solution: Solution) {
        coordinator.singleEvaluateAndUpdate(solution)
        numberOfEvaluations++
    }

    override fun iterate() {
        coordinator.prepareIteration(archive)
        super.iterate()
    }
}

/**
 * PAES subclass that delegates evaluation to an [EvaluationCoordinator].
 *
 * PAES is a (1+1) evolution strategy that evaluates a single offspring per iteration
 * via `evaluate()`. It maintains both a population (size 1) and an archive, so
 * lifecycle tracking covers the combined set.
 *
 * **Crossover:** PAES takes a mutation operator only; `variation = genetic` is rejected by
 * [DelegatingAlgorithmProvider].
 *
 * @param problem The problem definition (typically [DelegatingProblem]).
 * @param mutation The mutation operator (typically [DelegatingVariation]).
 * @param bisections The number of bisections for the adaptive grid archive.
 * @param archiveSize The capacity of the adaptive grid archive.
 * @param coordinator The evaluation coordinator managing solution lifecycle.
 */
class DelegatingPAES(
    problem: Problem,
    mutation: Mutation,
    bisections: Int,
    archiveSize: Int,
    private val coordinator: EvaluationCoordinator
) : PAES(problem, mutation, bisections, archiveSize) {

    override fun evaluateAll(solutions: Iterable<Solution>) {
        numberOfEvaluations += coordinator.batchEvaluateAndUpdate(solutions)
    }

    override fun evaluate(solution: Solution) {
        coordinator.singleEvaluateAndUpdate(solution)
        numberOfEvaluations++
    }

    override fun iterate() {
        val allSolutions = Population()
        allSolutions.addAll(getPopulation())
        allSolutions.addAll(archive)
        coordinator.prepareIteration(allSolutions)
        super.iterate()
    }
}

/**
 * RandomSearch subclass that delegates evaluation to an [EvaluationCoordinator].
 *
 * RandomSearch generates random solutions each iteration and adds non-dominated ones
 * to an archive. Lifecycle tracking operates on the result set.
 *
 * @param problem The problem definition (typically [DelegatingProblem]).
 * @param sampleSize The number of solutions sampled each iteration.
 * @param initialization The initialization strategy.
 * @param archive The non-dominated archive.
 * @param coordinator The evaluation coordinator managing solution lifecycle.
 */
class DelegatingRandomSearch(
    problem: Problem,
    sampleSize: Int,
    initialization: Initialization,
    archive: NondominatedPopulation,
    private val coordinator: EvaluationCoordinator
) : RandomSearch(problem, sampleSize, initialization, archive) {

    override fun evaluateAll(solutions: Iterable<Solution>) {
        numberOfEvaluations += coordinator.batchEvaluateAndUpdate(solutions)
    }

    override fun evaluate(solution: Solution) {
        coordinator.singleEvaluateAndUpdate(solution)
        numberOfEvaluations++
    }

    override fun iterate() {
        coordinator.prepareIteration(result)
        super.iterate()
    }
}
