package com.mdeo.optimizer

import com.mdeo.optimizer.config.OptimizationConfig
import com.mdeo.optimizer.config.SolverConfig
import com.mdeo.optimizer.evaluation.EvaluationFailedException
import com.mdeo.optimizer.evaluation.MutationEvaluator
import com.mdeo.optimizer.metrics.OptimizationMetricsCollector
import com.mdeo.optimizer.moea.DelegatingAlgorithmProvider
import com.mdeo.optimizer.moea.DelegatingProblem
import com.mdeo.optimizer.moea.EvaluationCoordinator
import com.mdeo.optimizer.moea.ProgressCallbackExtension
import com.mdeo.optimizer.moea.SearchResult
import com.mdeo.optimizer.moea.TerminationConditionAdapter
import com.mdeo.optimizer.provider.OptimizationProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.moeaframework.algorithm.extension.Frequency
import org.moeaframework.analysis.runtime.Instrumenter
import org.slf4j.LoggerFactory

/**
 * Main orchestrator for optimization runs.
 *
 * Drives the evolutionary search using the delegating algorithm pattern: a
 * [DelegatingAlgorithmProvider] creates MOEA algorithm subclasses whose evaluation
 * is routed through an [EvaluationCoordinator] to the supplied [MutationEvaluator].
 * This design is backend-agnostic — the same orchestration logic works with both
 * local (in-process) and federated (multi-node) evaluators.
 *
 * @param config Full optimization configuration (problem, goal, search, solver).
 * @param evaluator The mutation evaluator that performs the actual work (local or federated).
 */
class OptimizationOrchestrator(
    private val config: OptimizationConfig,
    private val evaluator: MutationEvaluator
) {
    private val logger = LoggerFactory.getLogger(OptimizationOrchestrator::class.java)

    /**
     * Runs the optimization and returns the search results for every batch.
     *
     * Creates the [EvaluationCoordinator], [DelegatingProblem], and
     * [DelegatingAlgorithmProvider], then executes the configured number of batches.
     * Before each batch after the first the coordinator and evaluator are fully reset
     * so that no stale solution references leak across batch boundaries.
     *
     * The [onGenerationComplete] callback is invoked after every generation so that
     * callers can report progress and check for cancellation.
     *
     * The [onBatchComplete] callback is invoked at the end of each batch while the
     * evaluator still holds the solutions for that batch (i.e. before the evaluator
     * is reset for the next one).  Callers can use this window to fetch solution model
     * data that would become unavailable after the reset.
     *
     * @param onGenerationComplete Suspend callback that receives the 1-based generation counter.
     *   Throwing [kotlinx.coroutines.CancellationException] from this callback aborts the search.
     * @param onBatchComplete Suspend callback invoked after each batch with the 1-based batch
     *   index, the [SearchResult] for that batch, and the wall-clock duration in milliseconds.
     * @return The list of [SearchResult]s, one per batch, in execution order.
     */
    suspend fun run(
        onGenerationComplete: suspend (generation: Int) -> Unit = {},
        onBatchComplete: suspend (batchIndex: Int, result: SearchResult, durationMs: Long) -> Unit = { _, _, _ -> }
    ): List<SearchResult> =
        withContext(Dispatchers.IO) {
            logger.info(
                "Starting optimization: algorithm=${config.solver.algorithm}, " +
                    "objectives=${config.goal.objectives.size}, " +
                    "constraints=${config.goal.constraints.size}"
            )

            val coordinator = EvaluationCoordinator(evaluator)
            val problem = DelegatingProblem(
                numberOfObjectives = config.goal.objectives.size,
                numberOfConstraints = config.goal.constraints.size
            )
            val provider = DelegatingAlgorithmProvider(coordinator)
            val properties = provider.buildProperties(config.solver)

            val batches = config.solver.batches.coerceAtLeast(1)
            val allResults = mutableListOf<SearchResult>()

            for (batchIndex in 1..batches) {
                if (batchIndex > 1) {
                    // Reset coordinator first so no stale refs are sent to the fresh workers.
                    coordinator.reset()
                    evaluator.resetBatch()
                }

                logger.info("Running optimization batch $batchIndex/$batches with algorithm ${config.solver.algorithm}")

                val instrumenter = createInstrumenter()
                val algorithm = provider.getAlgorithm(config.solver.algorithm.name, properties, problem)

                val metricsCollector = OptimizationMetricsCollector()

                val progressListener = OptimizationProgressListener { generation ->
                    onGenerationComplete(generation)
                }
                algorithm.addExtension(ProgressCallbackExtension(progressListener, coordinator, metricsCollector))

                val instrumentedAlgorithm = instrumenter.instrument(algorithm)
                val terminationCondition = TerminationConditionAdapter(config.solver).create()

                val batchStartMs = System.currentTimeMillis()
                try {
                    instrumentedAlgorithm.run(terminationCondition)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: EvaluationFailedException) {
                    throw e
                } catch (e: Throwable) {
                    val msg = "Unable to run search. Encountered exception: ${e.message}"
                    logger.error(msg, e)
                    throw RuntimeException(msg, e)
                }
                val batchDurationMs = System.currentTimeMillis() - batchStartMs

                val result = SearchResult(instrumentedAlgorithm.getSeries(), instrumentedAlgorithm.getResult(), metricsCollector)
                allResults.add(result)

                // Notify the caller while this batch's solutions are still live in the evaluator.
                onBatchComplete(batchIndex, result, batchDurationMs)
            }

            logger.info("Optimization completed after $batches batch(es)")
            allResults
        }

    /**
     * Creates a MOEA [Instrumenter] that tracks elapsed time and population size per iteration.
     * Common JDK/framework packages are excluded to reduce instrumentation overhead.
     *
     * @return A configured [Instrumenter].
     */
    private fun createInstrumenter(): Instrumenter {
        return Instrumenter()
            .attachElapsedTimeCollector()
            .attachPopulationSizeCollector()
            .withFrequency(Frequency.ofIterations(1))
            .addExcludedPackage("jdk")
            .addExcludedPackage("sun")
            .addExcludedPackage("org.xml")
            .addExcludedPackage("javax")
            .addExcludedPackage("com.sun")
            .addExcludedPackage("org.apache")
            .addExcludedPackage("kotlinx")
            .addExcludedPackage("io.netty")
    }
}
