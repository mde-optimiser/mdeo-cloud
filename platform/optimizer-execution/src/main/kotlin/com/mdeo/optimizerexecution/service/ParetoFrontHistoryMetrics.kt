package com.mdeo.optimizerexecution.service

import com.mdeo.optimizer.moea.DelegatingProblem
import com.mdeo.optimizer.moea.SolutionSnapshot
import org.moeaframework.core.Solution
import org.moeaframework.core.indicator.Normalizer
import org.moeaframework.core.indicator.WFGNormalizedHypervolume
import org.moeaframework.core.population.NondominatedPopulation

/**
 * Per-generation quality of a batch's Pareto front history ([com.mdeo.optimizer.moea.SearchResult.getParetoFrontHistory]):
 * the hypervolume of the feasible front and the best constraint violation, for the batch's report (report.json) and
 * its summary plots.
 */
internal object ParetoFrontHistoryMetrics {
    /**
     * The hypervolume of each generation and the normalisation it was computed in: objectives in MOEA-internal
     * minimisation form, normalised from [minimum] (0) to [reference] (1).
     */
    class HypervolumeSeries(val values: List<Double>, val minimum: DoubleArray, val reference: DoubleArray)

    /**
     * Computes the normalised WFG hypervolume for each generation's Pareto front snapshot,
     * considering **only feasible solutions** (all constraint violations equal to zero).
     *
     * Normalisation bounds are derived from the union of feasible solutions across ALL generations
     * so that hypervolume values are comparable generation-to-generation. Objectives are already
     * in MOEA-internal minimisation form.
     *
     * Generations with no feasible solutions return 0.0. If fewer than two feasible solutions
     * exist across the entire run there is no series (insufficient data for normalisation).
     *
     * @param paretoFrontHistory Per-generation snapshots from [com.mdeo.optimizer.moea.SearchResult.getParetoFrontHistory].
     * @return The hypervolume values in generation order with their normalisation; null when computation is not
     *   possible.
     */
    fun hypervolumeByGeneration(
        paretoFrontHistory: List<List<SolutionSnapshot>>
    ): HypervolumeSeries? {
        // Only use feasible solutions (all constraints exactly 0 within tolerance)
        val isFeasible: (SolutionSnapshot) -> Boolean = { snap ->
            snap.constraints.all { kotlin.math.abs(it) < 1e-10 }
        }

        val allFeasiblePoints = paretoFrontHistory.flatMap { gen -> gen.filter(isFeasible).map { it.objectives } }
        if (allFeasiblePoints.size < 2) return null

        val nObj = allFeasiblePoints.first().size
        if (nObj == 0) return null

        val minimum = DoubleArray(nObj) { i -> allFeasiblePoints.minOf { it[i] } }
        val maximum = DoubleArray(nObj) { i -> allFeasiblePoints.maxOf { it[i] } }

        // Ensure no degenerate objective range
        for (i in 0 until nObj) {
            if (maximum[i] - minimum[i] < 1e-10) {
                maximum[i] = minimum[i] + 1.0
            }
        }

        val problem = DelegatingProblem(numberOfObjectives = nObj, numberOfConstraints = 0)
        // Reference point sits 10% beyond the worst feasible value found across all generations
        val delta = 0.1
        val referenceMaximum = DoubleArray(nObj) { i -> maximum[i] + delta * (maximum[i] - minimum[i]) }
        val normalizer = Normalizer(minimum, referenceMaximum)
        val hvIndicator = WFGNormalizedHypervolume(problem, normalizer)

        val values = paretoFrontHistory.map { genSnapshot ->
            val feasible = genSnapshot.filter(isFeasible)
            if (feasible.isEmpty()) {
                0.0
            } else {
                val population = NondominatedPopulation()
                for (snap in feasible) {
                    val solution = Solution(0, nObj, 0)
                    for (i in 0 until nObj) {
                        solution.setObjectiveValue(i, snap.objectives[i])
                    }
                    population.add(solution)
                }
                try {
                    hvIndicator.evaluate(population)
                } catch (_: Exception) {
                    0.0
                }
            }
        }
        return HypervolumeSeries(values, minimum, referenceMaximum)
    }

    /**
     * Computes the minimum total constraint violation across all Pareto front solutions
     * for each generation. This represents the "best" feasibility achieved that generation:
     * 0.0 means at least one fully feasible solution existed.
     *
     * Total violation for a solution is \u03a3 |constraint_i|. Generations with an empty
     * snapshot return 0.0.
     *
     * Returns an empty list when the run has no constraint data at all (unconstrained problem).
     *
     * @param paretoFrontHistory Per-generation snapshots from [com.mdeo.optimizer.moea.SearchResult.getParetoFrontHistory].
     * @return List of minimum violation values in generation order; empty when unconstrained.
     */
    fun minConstraintViolationByGeneration(
        paretoFrontHistory: List<List<SolutionSnapshot>>
    ): List<Double> {
        // Only emit this graph when the problem actually has constraints
        val hasConstraints = paretoFrontHistory.any { gen -> gen.any { it.constraints.isNotEmpty() } }
        if (!hasConstraints) return emptyList()

        return paretoFrontHistory.map { genSnapshot ->
            if (genSnapshot.isEmpty()) {
                0.0
            } else {
                genSnapshot.minOf { snap ->
                    snap.constraints.sumOf { kotlin.math.abs(it) }
                }
            }
        }
    }
}
