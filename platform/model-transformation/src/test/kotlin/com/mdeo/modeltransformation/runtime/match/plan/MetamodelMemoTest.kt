package com.mdeo.modeltransformation.runtime.match.plan

import com.mdeo.metamodel.data.AssociationData
import com.mdeo.metamodel.data.AssociationEndData
import com.mdeo.metamodel.data.ClassData
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.metamodel.data.MultiplicityData
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The per-metamodel caches of the planner ([MetamodelMemo]): the cached pseudo-composition DAG and
 * [MetamodelIndex] must always equal a fresh computation for the metamodel asked about.
 */
class MetamodelMemoTest {

    /** `A <>-> B`, `B.c[0..1] --> C[0..*]`, `D extends A`. */
    private fun metamodel(cUpper: Int = 1, extra: List<ClassData> = emptyList()) = MetamodelData(
        path = "/memo.mm",
        classes = listOf(ClassData("A", false), ClassData("B", false), ClassData("C", false), ClassData("D", false, listOf("A"))) + extra,
        associations = listOf(
            AssociationData(AssociationEndData("A", "bs", MultiplicityData.many()), "<>->", AssociationEndData("B", "a", MultiplicityData.single())),
            AssociationData(AssociationEndData("B", "c", MultiplicityData(0, -1)), "-->", AssociationEndData("C", "b", MultiplicityData(0, cUpper)))
        )
    )

    @Test
    fun `the cached DAG equals a fresh computation and is shared`() {
        val md = metamodel()
        val cached = MetamodelClassPriority.pseudoCompositionDag(md)
        assertEquals(MetamodelClassPriority.computePseudoCompositionDag(md), cached)
        assertSame(cached, MetamodelClassPriority.pseudoCompositionDag(md))
        // an equal metamodel loaded separately shares the entry
        assertSame(cached, MetamodelClassPriority.pseudoCompositionDag(md.copy()))
    }

    @Test
    fun `a different metamodel gets its own DAG, also when the two alternate`() {
        val one = metamodel(cUpper = 1)
        val other = metamodel(cUpper = -1)
        val dagOne = MetamodelClassPriority.computePseudoCompositionDag(one)
        val dagOther = MetamodelClassPriority.computePseudoCompositionDag(other)
        assertNotEquals(dagOne, dagOther, "the test needs metamodels whose DAGs differ")
        repeat(5) {
            assertEquals(dagOne, MetamodelClassPriority.pseudoCompositionDag(one))
            assertEquals(dagOther, MetamodelClassPriority.pseudoCompositionDag(other))
        }
    }

    @Test
    fun `the cached DAG cannot be modified by a caller`() {
        val dag = MetamodelClassPriority.pseudoCompositionDag(metamodel())
        @Suppress("UNCHECKED_CAST")
        assertFailsWith<UnsupportedOperationException> { (dag as MutableSet<Pair<String, String>>).add("X" to "Y") }
    }

    @Test
    fun `the metamodel index follows the metamodel asked about`() {
        val one = metamodel()
        val other = metamodel(extra = listOf(ClassData("E", false, listOf("D"))))
        repeat(3) {
            assertEquals(setOf("A", "D"), MetamodelIndex.of(one).subclassesOf["A"])
            assertEquals(setOf("A", "D", "E"), MetamodelIndex.of(other).subclassesOf["A"])
        }
        assertSame(MetamodelIndex.of(one), MetamodelIndex.of(one))
    }

    @Test
    fun `concurrent lookups of alternating metamodels always get their own value`() {
        val metamodels = (0 until 4).map { i -> metamodel(cUpper = if (i % 2 == 0) 1 else -1, extra = List(i) { ClassData("X$it", false) }) }
        val expected = metamodels.map { MetamodelClassPriority.computePseudoCompositionDag(it) to it.classes.size }
        val wrong = AtomicInteger()
        val pool = Executors.newFixedThreadPool(4)
        repeat(4) { t ->
            pool.execute {
                repeat(5_000) { i ->
                    val k = (i + t) % metamodels.size
                    if (MetamodelClassPriority.pseudoCompositionDag(metamodels[k]) != expected[k].first) wrong.incrementAndGet()
                    if (MetamodelIndex.of(metamodels[k]).subclassesOf.size != expected[k].second) wrong.incrementAndGet()
                }
            }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))
        assertEquals(0, wrong.get())
    }
}
