package com.mdeo.optimizer.moea

import com.mdeo.optimizer.evaluation.WorkerSolutionRef
import org.moeaframework.core.PRNG
import org.moeaframework.core.Solution
import org.moeaframework.core.operator.Variation

/**
 * Variation operator of `variation = genetic`: takes a group of [parents] parents and returns
 * one offspring per parent, each a fresh, empty MOEA [Solution] carrying what a worker needs to
 * make it, like [DelegatingVariation] does for a mutation.
 *
 * Offspring `i` has primary parent `parents[i]` ([DelegatingVariation.PARENT_REF_KEY]) and the
 * other parents as donors in cyclic order starting at `i + 1` ([DONOR_REFS_KEY]); it is marked
 * for mutation ([DelegatingVariation.NEEDS_MUTATION_KEY]) because every child of a crossover is
 * mutated afterwards, and [ATTEMPT_CROSSOVER_KEY] says whether a crossover is attempted.
 *
 * **Where the rate is decided:** here, on the coordinator, once per group: with probability
 * [rate] all children of the group attempt a crossover, otherwise none does (each is then a copy
 * of its primary, still mutated). Deciding it here keeps a worker task deterministic in what it is
 * asked to do, and lets the [EvaluationCoordinator] send the children of a group that attempts no
 * crossover as plain mutation tasks of their primaries, exactly as `variation = mutation` would.
 *
 * The [EvaluationCoordinator] turns these offspring into
 * [com.mdeo.optimizer.evaluation.CrossoverTask]s (or [com.mdeo.optimizer.evaluation.MutationTask]s)
 * and maps one result back to each offspring by task id.
 *
 * @param parents Parents per group, and children per group; at least 2.
 * @param rate Probability in `[0, 1]` that a group attempts a crossover.
 * @param random Draws whether a group attempts a crossover; MOEA's [PRNG] by default, so seeding
 *   the framework seeds it too.
 */
class DelegatingCrossoverVariation(
    private val parents: Int,
    private val rate: Double,
    private val random: () -> Double = { PRNG.nextDouble() }
) : Variation {

    init {
        require(parents >= 2) { "A crossover takes at least 2 parents, not $parents" }
        require(rate in 0.0..1.0) { "The crossover rate must be in [0, 1], not $rate" }
    }

    companion object {
        /**
         * Attribute key storing the donors of an offspring: an [ArrayList] of [WorkerSolutionRef]s,
         * the other parents of its group in cyclic order after its primary.
         */
        const val DONOR_REFS_KEY = "donorSolutionRefs"

        /** Attribute key storing whether the offspring's group attempts a crossover (a [Boolean]). */
        const val ATTEMPT_CROSSOVER_KEY = "attemptCrossover"
    }

    override fun getName(): String = "DelegatingCrossoverVariation"

    override fun getArity(): Int = parents

    override fun evolve(parents: Array<Solution>): Array<Solution> {
        require(parents.size == this.parents) { "Expected ${this.parents} parents, got ${parents.size}" }
        val attempt = rate > 0.0 && random() < rate
        val refs = parents.map { it.getWorkerRef() }
        return Array(parents.size) { i ->
            val offspring = Solution(0, parents[i].numberOfObjectives, parents[i].numberOfConstraints)
            refs[i]?.let { offspring.setAttribute(DelegatingVariation.PARENT_REF_KEY, it) }
            val donors = ArrayList<WorkerSolutionRef>(parents.size - 1)
            for (k in 1 until parents.size) {
                refs[(i + k) % parents.size]?.let { donors.add(it) }
            }
            offspring.setAttribute(DONOR_REFS_KEY, donors)
            offspring.setAttribute(ATTEMPT_CROSSOVER_KEY, attempt)
            offspring.setAttribute(DelegatingVariation.NEEDS_MUTATION_KEY, true)
            offspring
        }
    }
}
