package com.mdeo.optimizer.moea

import com.mdeo.optimizer.evaluation.WorkerSolutionRef

/**
 * A single planned transfer of solutions from one node to another.
 *
 * @param solutionIds The solution identifiers to move.
 * @param sourceNodeId The node currently holding the solutions.
 * @param destinationNodeId The node that should receive the solutions.
 */
data class RebalanceTransfer(
    val solutionIds: List<String>,
    val sourceNodeId: String,
    val destinationNodeId: String
)

/**
 * Computes a minimal greedy transfer plan to rebalance solutions across worker nodes.
 *
 * The rebalancer tolerates slight imbalances to avoid unnecessary overhead from moving
 * models that would not significantly improve workload distribution. It considers that
 * each model may be used in more than one transformation (i.e., selected as a parent
 * multiple times per generation), so the actual transformation counts per node from the
 * last generation are used together with solution counts to pick which solutions to move.
 *
 * Only population members are balanced: the references it is given are the coordinator's
 * tracked ones, which never include the replicas a crossover batch copies to other nodes
 * ([com.mdeo.optimizer.evaluation.NodeBatch.replicas]). Those exist for one batch only and are
 * neither counted nor moved.
 */
object PopulationRebalancer {

    /**
     * Minimum absolute difference between the most-loaded and least-loaded node
     * before rebalancing is triggered. Moves have overhead, so small imbalances
     * are tolerable.
     */
    private const val MIN_ABSOLUTE_DIFF = 3

    /**
     * Fractional threshold relative to the ideal per-node count. If no node
     * deviates from the ideal by more than this fraction, no rebalancing occurs.
     */
    private const val IMBALANCE_FRACTION = 0.25

    /**
     * Computes the set of transfers needed to bring the per-node solution counts
     * closer to balanced.
     *
     * The algorithm:
     * 1. Compute the ideal count per node (total / numNodes, with remainder distributed).
     * 2. Identify donor nodes (those above ideal) and receiver nodes (those below ideal).
     * 3. Check whether the overall imbalance exceeds the threshold.
     * 4. Greedily transfer solutions from the most over-loaded donor to the most
     *    under-loaded receiver until all nodes are within tolerance.
     *
     * When choosing which solutions to move from a donor, solutions that appeared
     * less frequently in the last generation's transformation batch are preferred,
     * since moving a heavily-used parent would shift a disproportionate amount of
     * work to the destination.
     *
     * @param allKnownRefs All live solution references tracked by the coordinator.
     * @param nodeIds All node identifiers (including nodes that may currently have zero solutions).
     * @param lastBatchPerNode Number of transformations each node performed in the last generation.
     * @return A list of [RebalanceTransfer] instructions. Empty if no rebalancing is needed.
     */
    fun computeTransferPlan(
        allKnownRefs: Set<WorkerSolutionRef>,
        nodeIds: List<String>,
        lastBatchPerNode: Map<String, Int>
    ): List<RebalanceTransfer> {
        if (nodeIds.size <= 1) {
            return emptyList()
        }

        val refsByNode: MutableMap<String, MutableList<WorkerSolutionRef>> = nodeIds.associateWith { mutableListOf<WorkerSolutionRef>() }
            .toMutableMap()
        for (ref in allKnownRefs) {
            refsByNode.getOrPut(ref.nodeId) { mutableListOf() }.add(ref)
        }

        val total = allKnownRefs.size
        val numNodes = nodeIds.size
        val idealPerNode = distributeIdeal(total, numNodes)

        val counts = nodeIds.map { (refsByNode[it]?.size ?: 0) }
        val maxCount = counts.max()
        val minCount = counts.min()
        val threshold = maxOf(MIN_ABSOLUTE_DIFF, (total.toDouble() / numNodes * IMBALANCE_FRACTION).toInt())
        if (maxCount - minCount <= threshold) {
            return emptyList()
        }

        val excess = mutableMapOf<String, Int>()
        for ((i, nodeId) in nodeIds.withIndex()) {
            val have = refsByNode[nodeId]?.size ?: 0
            excess[nodeId] = have - idealPerNode[i]
        }

        val mutableRefsByNode = refsByNode.mapValues { it.value.toMutableList().apply { shuffle() } }.toMutableMap()

        val transfers = mutableListOf<RebalanceTransfer>()

        while (true) {
            val donor = excess.entries.filter { it.value > 0 }.maxByOrNull { it.value } ?: break
            val receiver = excess.entries.filter { it.value < 0 }.minByOrNull { it.value } ?: break

            val moveCount = minOf(donor.value, -receiver.value)
            if (moveCount <= 0) break

            val donorRefs = mutableRefsByNode[donor.key] ?: break
            val toMove = donorRefs.take(moveCount)
            if (toMove.isEmpty()) break

            repeat(toMove.size) { donorRefs.removeFirst() }

            transfers.add(
                RebalanceTransfer(
                    solutionIds = toMove.map { it.solutionId },
                    sourceNodeId = donor.key,
                    destinationNodeId = receiver.key
                )
            )

            excess[donor.key] = donor.value - toMove.size
            excess[receiver.key] = receiver.value + toMove.size
        }

        return transfers
    }

    /**
     * Distributes a total count as evenly as possible across [buckets] slots.
     * The remainder is distributed one-per-slot to the first slots.
     */
    private fun distributeIdeal(total: Int, buckets: Int): List<Int> {
        val base = total / buckets
        val remainder = total % buckets
        return List(buckets) { i -> if (i < remainder) base + 1 else base }
    }
}
