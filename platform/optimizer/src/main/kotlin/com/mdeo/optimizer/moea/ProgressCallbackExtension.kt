package com.mdeo.optimizer.moea

import com.mdeo.optimizer.metrics.GenerationMetrics
import com.mdeo.optimizer.metrics.NodeGenerationMetrics
import com.mdeo.optimizer.metrics.OptimizationMetricsCollector
import com.mdeo.optimizer.provider.OptimizationProgressListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.moeaframework.algorithm.Algorithm
import org.moeaframework.algorithm.extension.Extension

/**
 * MOEA Framework [Extension] that reports progress after each step, records per-generation
 * metrics, and supports cooperative cancellation by catching [CancellationException].
 *
 * @param listener Receives the 1-based generation index after each step.
 * @param coordinator Source of solution-count and batch-size metrics.
 * @param metricsCollector Accumulates [GenerationMetrics] for the run.
 */
class ProgressCallbackExtension(
    private val listener: OptimizationProgressListener,
    private val coordinator: EvaluationCoordinator,
    private val metricsCollector: OptimizationMetricsCollector
) : Extension {

    private var generation = 0
    private var lastStepTimeNanos = System.nanoTime()

    override fun onStep(algorithm: Algorithm) {
        val now = System.nanoTime()
        val iterationTimeMs = (now - lastStepTimeNanos) / 1_000_000
        lastStepTimeNanos = now

        generation++

        val (totalModels, perNodeModels) = coordinator.getMetricsSnapshot()
        val rebalancedCount = coordinator.getLastRebalancedCount()
        val transformationInfo = coordinator.getLastTransformationInfo()

        val perNodeMetrics = perNodeModels.mapValues { (nodeId, totalCount) ->
            NodeGenerationMetrics(
                totalModels = totalCount,
                executedTransformations = transformationInfo.appliedPerNode[nodeId] ?: 0,
                skippedOperatorSlots = transformationInfo.skippedPerNode[nodeId] ?: 0
            )
        }

        val metrics = GenerationMetrics(
            generation = generation,
            totalModels = totalModels,
            executedTransformations = transformationInfo.appliedTotal,
            skippedOperatorSlots = transformationInfo.skippedTotal,
            rebalancedSolutions = rebalancedCount,
            iterationTimeMs = iterationTimeMs,
            perNode = perNodeMetrics
        )
        metricsCollector.record(metrics)

        try {
            runBlocking { listener.onGenerationComplete(generation) }
        } catch (_: CancellationException) {
            algorithm.terminate()
        }
    }
}
