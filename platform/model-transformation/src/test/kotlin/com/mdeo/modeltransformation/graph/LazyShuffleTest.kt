package com.mdeo.modeltransformation.graph

import com.mdeo.metamodel.Metamodel
import com.mdeo.metamodel.data.AssociationData
import com.mdeo.metamodel.data.AssociationEndData
import com.mdeo.metamodel.data.ClassData
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.metamodel.data.ModelData
import com.mdeo.metamodel.data.ModelDataInstance
import com.mdeo.metamodel.data.ModelDataLink
import com.mdeo.metamodel.data.MultiplicityData
import com.mdeo.modeltransformation.ast.EdgeLabelUtils
import com.mdeo.modeltransformation.graph.mdeo.MdeoGraph
import org.apache.tinkerpop.gremlin.process.traversal.P
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.`__`
import org.apache.tinkerpop.gremlin.structure.Vertex
import org.junit.jupiter.api.Test
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The lazy shuffle of [MdeoGraph.shuffleVertices]: every ordered structure a traversal reads must
 * be an independent, uniformly random permutation, fixed until the next shuffle — exactly what the
 * former eager shuffle of the whole graph gave — and an unshuffled graph keeps insertion order.
 *
 * The statistical checks use Pearson's chi-square test at a significance level of about 1e-6
 * (critical values by the Wilson–Hilferty approximation), so that a correct implementation fails
 * with negligible probability while a biased order (e.g. one structure left unshuffled, or
 * re-shuffled within an epoch) fails by a wide margin.
 */
class LazyShuffleTest {

    /** `House.rooms[*] <>-> Room.house[1]`. */
    private val metamodelData = MetamodelData(
        path = "/shuffle.mm",
        classes = listOf(ClassData("House", false), ClassData("Room", false)),
        associations = listOf(
            AssociationData(
                AssociationEndData("House", "rooms", MultiplicityData.many()), "<>->",
                AssociationEndData("Room", "house", MultiplicityData.single())
            )
        )
    )
    private val metamodel = Metamodel.compile(metamodelData)
    private val rooms = EdgeLabelUtils.computeEdgeLabel("rooms", "house")

    /** h1 has r1..r4, h2 has r5, h3..h5 have none. */
    private val modelData = ModelData(
        "/shuffle.mm",
        (1..5).map { ModelDataInstance("h$it", "House", emptyMap()) } + (1..5).map { ModelDataInstance("r$it", "Room", emptyMap()) },
        (1..4).map { ModelDataLink("h1", "rooms", "r$it", "house") } + ModelDataLink("h2", "rooms", "r5", "house")
    )

    private fun graph(): MdeoGraph = MdeoGraph.open(metamodel, metamodel.loadModel(modelData))

    /** Instance names by vertex: [MdeoGraph.open] numbers the vertices in model order. */
    private val names = modelData.instances.map { it.name }
    private fun name(v: Any): String = names[(v as Vertex).id() as Int]

    /** Starts a new epoch, lazily (as the engine does) or followed by the eager reference. */
    private fun reset(graph: MdeoGraph, eager: Boolean) {
        graph.shuffleVertices()
        if (eager) graph.settleAll()
    }

    private fun criticalValue(df: Int): Double {
        val z = 4.75
        return df * (1 - 2.0 / (9 * df) + z * sqrt(2.0 / (9 * df))).pow(3)
    }

    /** Pearson's goodness of fit of [counts] against [expected] probabilities. */
    private fun assertFits(counts: Map<String, Int>, expected: Map<String, Double>, what: String) {
        val n = counts.values.sum()
        assertTrue(counts.keys.all { it in expected }, "$what: unexpected outcomes ${counts.keys - expected.keys}")
        val statistic = expected.entries.sumOf { (k, p) -> val e = n * p; (counts.getOrDefault(k, 0) - e).pow(2) / e }
        val df = expected.size - 1
        assertTrue(statistic < criticalValue(df), "$what: chi-square $statistic >= ${criticalValue(df)} (df $df), counts $counts")
    }

    /** Pearson's test that two samples come from one distribution. */
    private fun assertHomogeneous(a: Map<String, Int>, b: Map<String, Int>, what: String) {
        val keys = a.keys + b.keys
        val na = a.values.sum().toDouble()
        val nb = b.values.sum().toDouble()
        var statistic = 0.0
        for (k in keys) {
            val total = a.getOrDefault(k, 0) + b.getOrDefault(k, 0)
            val ea = total * na / (na + nb)
            val eb = total * nb / (na + nb)
            statistic += (a.getOrDefault(k, 0) - ea).pow(2) / ea + (b.getOrDefault(k, 0) - eb).pow(2) / eb
        }
        val df = keys.size - 1
        assertTrue(statistic < criticalValue(df), "$what: lazy and eager differ, chi-square $statistic (df $df): $a vs $b")
    }

    private fun sample(draws: Int, eager: Boolean, read: (GraphTraversalSource) -> String): Map<String, Int> {
        val graph = graph()
        val g = graph.traversal()
        val counts = HashMap<String, Int>()
        repeat(draws) {
            reset(graph, eager)
            counts.merge(read(g), 1, Int::plus)
        }
        return counts
    }

    private fun permutations(items: List<String>): List<List<String>> =
        if (items.size <= 1) listOf(items)
        else items.flatMap { first -> permutations(items - first).map { listOf(first) + it } }

    private fun uniform(outcomes: Collection<String>) = outcomes.associateWith { 1.0 / outcomes.size }

    @Test
    fun `a label bucket is a uniform permutation after every reset`() {
        val expected = uniform(permutations((1..5).map { "r$it" }).map { it.joinToString(",") })
        for (eager in listOf(false, true)) {
            val counts = sample(12_000, eager) { g -> g.V().hasLabel("Room").toList().joinToString(",") { name(it) } }
            assertFits(counts, expected, "Room bucket (eager=$eager)")
        }
    }

    @Test
    fun `an adjacency set is a uniform permutation after every reset`() {
        val expected = uniform(permutations((1..4).map { "r$it" }).map { it.joinToString(",") })
        for (eager in listOf(false, true)) {
            val counts = sample(4_800, eager) { g -> g.V(0).out(rooms).toList().joinToString(",") { name(it) } }
            assertFits(counts, expected, "h1.rooms (eager=$eager)")
        }
    }

    @Test
    fun `the vertex list is shuffled for a full scan`() {
        val expected = uniform(names)
        for (eager in listOf(false, true)) {
            val counts = sample(5_000, eager) { g -> name(g.V().next()) }
            assertFits(counts, expected, "first vertex of V() (eager=$eager)")
        }
    }

    @Test
    fun `the order stays fixed within an epoch and changes between epochs`() {
        val graph = graph()
        val g = graph.traversal()
        val seen = HashSet<String>()
        repeat(50) {
            graph.shuffleVertices()
            val bucket = g.V().hasLabel("Room").toList()
            val walk = g.V(0).out(rooms).toList()
            val all = g.V().toList()
            repeat(3) {
                assertEquals(bucket, g.V().hasLabel("Room").toList())
                assertEquals(walk, g.V(0).out(rooms).toList())
                assertEquals(all, g.V().toList())
            }
            seen += bucket.joinToString(",") { name(it) }
        }
        assertTrue(seen.size > 10, "the bucket order should change between epochs: $seen")
    }

    @Test
    fun `an element added after a reset comes last, as after an eager shuffle`() {
        for (eager in listOf(false, true)) {
            val graph = graph()
            val g = graph.traversal()
            repeat(20) {
                reset(graph, eager)
                val room = g.addV("Room").next()
                val house = g.V(0).next()
                house.addEdge(rooms, room)
                assertEquals(room, g.V().hasLabel("Room").toList().last())
                assertEquals(room, g.V(0).out(rooms).toList().last())
                assertEquals(room, g.V().toList().last())
                room.remove()
            }
        }
    }

    @Test
    fun `an unshuffled graph keeps insertion order`() {
        val graph = graph()
        val g = graph.traversal()
        repeat(3) {
            assertEquals(names, g.V().toList().map { name(it) })
            assertEquals((1..5).map { "r$it" }, g.V().hasLabel("Room").toList().map { name(it) })
            assertEquals((1..4).map { "r$it" }, g.V(0).out(rooms).toList().map { name(it) })
        }
    }

    @Test
    fun `the first match of a scan and a walk has the distribution of the eager shuffle`() {
        // the first house with rooms is h1 or h2 with probability 1/2; then its first room
        val expected = (1..4).associate { "r$it" to 1.0 / 8 } + ("r5" to 0.5)
        val read = { g: GraphTraversalSource -> name(g.V().hasLabel("House").out(rooms).limit(1).next()) }
        val lazy = sample(8_000, eager = false, read = read)
        val eager = sample(8_000, eager = true, read = read)
        assertFits(lazy, expected, "scan and walk (lazy)")
        assertFits(eager, expected, "scan and walk (eager)")
        assertHomogeneous(lazy, eager, "scan and walk")
    }

    @Test
    fun `a bucket read twice in one match keeps the epoch's order, as with the eager shuffle`() {
        // a: the first house with rooms, b: the first other house, both from one House bucket. With
        // the order fixed for the epoch, a house without rooms that precedes a is preferred as b.
        val houses = (1..5).map { "h$it" }
        val expected = HashMap<String, Double>()
        for (order in permutations(houses)) {
            val a = order.first { it == "h1" || it == "h2" }
            val b = order.first { it != a }
            expected.merge("$a,$b", 1.0 / 120, Double::plus)
        }
        val read = { g: GraphTraversalSource ->
            val m = g.V().hasLabel("House").where(`__`.out(rooms)).`as`("a")
                .V().hasLabel("House").where(P.neq("a")).`as`("b")
                .select<Vertex>("a", "b").limit(1).next()
            "${name(m.getValue("a"))},${name(m.getValue("b"))}"
        }
        val lazy = sample(8_000, eager = false, read = read)
        val eager = sample(8_000, eager = true, read = read)
        assertFits(lazy, expected, "re-read bucket (lazy)")
        assertFits(eager, expected, "re-read bucket (eager)")
        assertHomogeneous(lazy, eager, "re-read bucket")
    }
}
