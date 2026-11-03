package com.mdeo.optimizer.metrics

/**
 * Per-generation metrics collected during optimization.
 *
 * @param generation 1-based generation index.
 * @param totalModels Total number of live solutions across all nodes.
 * @param executedTransformations Total number of transformation operators actually tried
 *   (successful + failed, not pre-skipped) this generation.
 * @param skippedOperatorSlots Number of operator slots pre-skipped due to known deterministic
 *   failures across all mutations this generation.
 * @param rebalancedSolutions Total number of solutions moved between nodes this generation.
 * @param iterationTimeMs Wall-clock time for this generation in milliseconds.
 * @param perNode Per-node breakdown of models and transformations.
 * @param crossover Crossovers of this generation (all zero with `variation = mutation`).
 */
data class GenerationMetrics(
    val generation: Int,
    val totalModels: Int,
    val executedTransformations: Int,
    val skippedOperatorSlots: Int,
    val rebalancedSolutions: Int,
    val iterationTimeMs: Long,
    val perNode: Map<String, NodeGenerationMetrics>,
    val crossover: CrossoverGenerationMetrics = CrossoverGenerationMetrics()
)

/**
 * Crossovers of one generation, counted per child: every child of a group that attempts a
 * crossover runs one crossover of its own.
 *
 * [executedTransformations][GenerationMetrics.executedTransformations] counts mutation attempts
 * only; crossover operators are counted here.
 *
 * @param attempted Children that attempted a crossover.
 * @param succeeded Children to which some crossover operator applied.
 * @param fellBack Children whose crossover operators all failed, so that they started as a copy
 *   of their primary parent.
 * @param perOperator Children each crossover operator applied to, by operator id (crossover id
 *   space, see [com.mdeo.optimizer.operators.OperatorCatalog]); operators that never applied are
 *   absent.
 */
data class CrossoverGenerationMetrics(
    val attempted: Int = 0,
    val succeeded: Int = 0,
    val fellBack: Int = 0,
    val perOperator: Map<Int, Int> = emptyMap()
)

/**
 * Per-node metrics within a single generation.
 *
 * @param totalModels Solutions stored on this node at end of generation.
 * @param executedTransformations Transformation operators actually tried on this node this generation.
 * @param skippedOperatorSlots Operator slots pre-skipped on this node this generation.
 */
data class NodeGenerationMetrics(
    val totalModels: Int,
    val executedTransformations: Int,
    val skippedOperatorSlots: Int
)

/**
 * Accumulates [GenerationMetrics] across all generations of an optimization run.
 */
class OptimizationMetricsCollector {
    private val _generations = mutableListOf<GenerationMetrics>()

    /**
     * All recorded generations in order. 
     */
    val generations: List<GenerationMetrics> get() = _generations

    /**
     * Appends [metrics] for the completed generation.
     *
     * @param metrics The generation metrics to record.
     */
    fun record(metrics: GenerationMetrics) {
        _generations.add(metrics)
    }
}
