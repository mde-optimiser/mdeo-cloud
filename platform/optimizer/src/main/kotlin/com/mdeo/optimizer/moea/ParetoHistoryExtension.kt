package com.mdeo.optimizer.moea

import org.moeaframework.algorithm.Algorithm
import org.moeaframework.algorithm.extension.Extension

/**
 * A snapshot of a single solution from the Pareto front at a given generation.
 *
 * @param objectives Objective values in MOEA-internal minimisation form
 *   (maximised objectives are negated before being stored here).
 * @param constraints Raw constraint violation values; zero means the constraint is satisfied.
 */
data class SolutionSnapshot(
    val objectives: List<Double>,
    val constraints: List<Double>
)

/**
 * MOEA Framework [Extension] that records a snapshot of the current Pareto front
 * after every generation step, capturing both objective and constraint values.
 *
 * Objective values are in MOEA-internal minimisation form (i.e. objectives configured
 * as MAXIMIZE are already negated by the time they reach the algorithm). Constraint
 * values are raw violations: zero means the constraint is satisfied.
 *
 * The history is accessed via [getHistory] after the run completes.
 */
class ParetoHistoryExtension : Extension {

    /**
     * Ordered list of Pareto front snapshots, one per generation.
     *
     * Each entry is a list of [SolutionSnapshot]s for that generation.
     * An empty inner list means no non-dominated solutions existed that generation.
     */
    private val _history = mutableListOf<List<SolutionSnapshot>>()

    /**
     * Returns an immutable view of the per-generation Pareto front history collected so far.
     */
    fun getHistory(): List<List<SolutionSnapshot>> = _history

    override fun onStep(algorithm: Algorithm) {
        val snapshot = try {
            algorithm.getResult().map { solution ->
                SolutionSnapshot(
                    objectives = (0 until solution.numberOfObjectives).map { i ->
                        solution.getObjectiveValue(i)
                    },
                    constraints = (0 until solution.numberOfConstraints).map { i ->
                        solution.getConstraintValue(i)
                    }
                )
            }
        } catch (_: Exception) {
            emptyList()
        }

        _history.add(snapshot)
    }
}
