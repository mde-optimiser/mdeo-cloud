package com.mdeo.modeltransformation.graph

import com.mdeo.modeltransformation.graph.mdeo.MdeoGraph
import org.apache.tinkerpop.gremlin.structure.Graph
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategies
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.FilterRankingStrategy
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.LazyBarrierStrategy
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.PathRetractionStrategy
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The traversal strategies the model graphs register for their traversals.
 *
 * A match traversal is built once and run once, so every strategy is paid once per match; the
 * matcher's traversals are long (one step per pattern node, link check, condition and
 * modification) and select every pattern label at the end. `PathRetractionStrategy` drops path
 * labels no later step reads: here every label is read by the final `select`, so it can retract
 * almost nothing, while its analysis of every step's referenced labels grows with the square of
 * the traversal's length (most of the strategies' time on a realisation's repair statements).
 * Like `LazyBarrierStrategy`, it is not registered. Nor is `FilterRankingStrategy`: it reorders
 * runs of filter steps by a fixed rank, while the match planner already places every constraint
 * where its nodes are bound and orders conditions by their estimated cost; on a realisation's
 * repair statements the strategy took about a fifth of a match's time and the traversals' own
 * execution under a tenth.
 *
 * `MDEO_MT_DEFAULT_STRATEGIES=1` ([ModelGraphStrategies]) keeps path retraction and filter ranking,
 * for traversals where retracting labels saves memory; the tests cover both settings.
 */
class TraversalStrategiesTest {

    private fun strategies(graphClass: Class<*>) =
        TraversalStrategies.GlobalCache.getStrategies(graphClass).toList().map { it.javaClass.name }

    private fun names(strategies: TraversalStrategies) = strategies.toList().map { it.javaClass.name }

    /** The registered strategies follow the flag this JVM was started with (removed by default). */
    private fun assertRegistered(registered: List<String>) {
        val kept = ModelGraphStrategies.defaultStrategies
        assertEquals(kept, PathRetractionStrategy::class.java.name in registered, "$registered")
        assertEquals(kept, FilterRankingStrategy::class.java.name in registered, "$registered")
        assertFalse(LazyBarrierStrategy::class.java.name in registered, "$registered")
    }

    @Test
    fun `MdeoGraph traversals apply no path retraction, no filter ranking and no lazy barriers`() {
        Class.forName(MdeoGraph::class.java.name) // registers its strategies
        val registered = strategies(MdeoGraph::class.java)
        assertRegistered(registered)
        assertTrue(registered.isNotEmpty())
    }

    @Test
    fun `TinkerGraph traversals of the model graph apply no path retraction, no filter ranking and no lazy barriers`() {
        Class.forName(com.mdeo.modeltransformation.graph.tinker.TinkerModelGraph::class.java.name)
        assertRegistered(strategies(TinkerGraph::class.java))
    }

    @Test
    fun `by default path retraction, filter ranking and lazy barriers are removed`() {
        val configured = names(ModelGraphStrategies.configure(TraversalStrategies.GlobalCache.getStrategies(Graph::class.java).clone(), false))
        assertFalse(PathRetractionStrategy::class.java.name in configured, "$configured")
        assertFalse(FilterRankingStrategy::class.java.name in configured, "$configured")
        assertFalse(LazyBarrierStrategy::class.java.name in configured, "$configured")
        assertTrue(configured.isNotEmpty())
    }

    @Test
    fun `with the flag path retraction and filter ranking are kept, lazy barriers still removed`() {
        val configured = names(ModelGraphStrategies.configure(TraversalStrategies.GlobalCache.getStrategies(Graph::class.java).clone(), true))
        assertTrue(PathRetractionStrategy::class.java.name in configured, "$configured")
        assertTrue(FilterRankingStrategy::class.java.name in configured, "$configured")
        assertFalse(LazyBarrierStrategy::class.java.name in configured, "$configured")
    }

    @Test
    fun `the flag is enabled by 1 or true only`() {
        for (on in listOf("1", "true", "TRUE", " 1 ")) assertTrue(ModelGraphStrategies.isEnabled(on), on)
        for (off in listOf(null, "", "0", "false", "yes")) assertFalse(ModelGraphStrategies.isEnabled(off), "$off")
    }
}
