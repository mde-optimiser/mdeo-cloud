package com.mdeo.optimizer.moea

import com.mdeo.optimizer.metrics.OptimizationMetricsCollector
import org.moeaframework.analysis.series.ResultSeries
import org.moeaframework.core.population.NondominatedPopulation

/**
 * Container for the results of an optimization search run.
 *
 * @param series The per-step runtime metrics from the instrumented algorithm.
 * @param finalPopulation The final Pareto-optimal solutions from the algorithm.
 * @param metricsCollector The collected per-generation performance metrics.
 * @param paretoFrontHistory Per-generation snapshots of the Pareto front's solutions (objectives +
 *   constraints). Objectives are in MOEA-internal minimisation form (maximised objectives are negated).
 *   Constraint values are raw violations; zero means the constraint is satisfied.
 *   Index 0 corresponds to generation 1.
 */
class SearchResult(
    private val series: ResultSeries,
    private val finalPopulation: NondominatedPopulation,
    private val metricsCollector: OptimizationMetricsCollector = OptimizationMetricsCollector(),
    private val paretoFrontHistory: List<List<SolutionSnapshot>> = emptyList()
) {
    /**
     * Gets the collected runtime series from the instrumented run.
     */
    fun getObservations(): ResultSeries = series

    /**
     * Returns pairs of (objective values, constraint values) for each final Pareto-optimal solution.
     */
    fun getFinalSolutions(): List<SolutionResult> {
        return finalPopulation.map { solution ->
            SolutionResult(
                objectives = solution.getObjectiveValues().toList(),
                constraints = solution.getConstraintValues().toList()
            )
        }
    }

    /**
     * Returns the raw MOEA population; each solution carries a [com.mdeo.optimizer.evaluation.WorkerSolutionRef]
     * that identifies its owning evaluator node.
     */
    fun getRawPopulation(): NondominatedPopulation = finalPopulation

    /**
     * Returns the metrics collector containing all recorded per-generation data for this run.
     *
     * @return The [OptimizationMetricsCollector] populated during the search.
     */
    fun getMetrics(): OptimizationMetricsCollector = metricsCollector

    /**
     * Returns the per-generation Pareto front history recorded during the run.
     *
     * Each element is a list of [SolutionSnapshot]s for that generation (objectives + raw
     * constraint violations). Index 0 is generation 1. Objective values are in MOEA-internal
     * minimisation form — maximised objectives appear negated. Constraint values are zero
     * when the constraint is satisfied.
     *
     * @return Ordered list of Pareto front snapshots, one per completed generation.
     */
    fun getParetoFrontHistory(): List<List<SolutionSnapshot>> = paretoFrontHistory
}

/**
 * Objective and constraint values for a single Pareto-optimal solution.
 *
 * @param objectives Objective function values in declaration order.
 * @param constraints Constraint violation values (zero means satisfied).
 */
data class SolutionResult(
    val objectives: List<Double>,
    val constraints: List<Double>
)
