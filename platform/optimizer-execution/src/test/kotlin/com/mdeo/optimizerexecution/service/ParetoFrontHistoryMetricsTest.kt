package com.mdeo.optimizerexecution.service

import com.mdeo.optimizer.moea.SolutionSnapshot
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [ParetoFrontHistoryMetrics]: the per-generation hypervolume with its normalisation and the best
 * constraint violation, as the batch report writes them.
 */
class ParetoFrontHistoryMetricsTest {

    private fun snap(vararg objectives: Double, violation: Double = 0.0) =
        SolutionSnapshot(objectives.toList(), listOf(violation))

    @Test
    fun `hypervolume grows with the front and reports its normalisation`() {
        val history = listOf(
            listOf(snap(4.0, 4.0)),
            listOf(snap(2.0, 4.0), snap(4.0, 2.0)),
            listOf(snap(2.0, 2.0)),
        )
        val hv = assertNotNull(ParetoFrontHistoryMetrics.hypervolumeByGeneration(history))
        assertEquals(3, hv.values.size)
        assertTrue(hv.values[0] < hv.values[1] && hv.values[1] < hv.values[2], "${hv.values}")
        // minimum and maximum of all generations' feasible points, the reference 10 % beyond the maximum
        assertContentEquals(doubleArrayOf(2.0, 2.0), hv.minimum)
        assertContentEquals(doubleArrayOf(4.2, 4.2), hv.reference)
    }

    @Test
    fun `only feasible points count, and a generation without any has none`() {
        val history = listOf(
            listOf(snap(1.0, 1.0, violation = 0.5)),
            listOf(snap(2.0, 4.0), snap(4.0, 2.0)),
        )
        val hv = assertNotNull(ParetoFrontHistoryMetrics.hypervolumeByGeneration(history))
        assertEquals(0.0, hv.values[0])
        assertContentEquals(doubleArrayOf(2.0, 2.0), hv.minimum)
    }

    @Test
    fun `no series with fewer than two feasible points`() {
        assertNull(ParetoFrontHistoryMetrics.hypervolumeByGeneration(listOf(listOf(snap(1.0, 1.0)))))
    }

    @Test
    fun `best constraint violation per generation, none without constraints`() {
        val history = listOf(
            listOf(snap(1.0, violation = 0.5), snap(2.0, violation = 0.25)),
            listOf(snap(1.0)),
        )
        assertEquals(listOf(0.25, 0.0), ParetoFrontHistoryMetrics.minConstraintViolationByGeneration(history))
        assertEquals(emptyList(), ParetoFrontHistoryMetrics.minConstraintViolationByGeneration(listOf(listOf(SolutionSnapshot(listOf(1.0), emptyList())))))
    }
}
