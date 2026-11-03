package com.mdeo.optimizer.rulegen

import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.metamodel.data.ModelData
import com.mdeo.metamodel.data.ModelDataInstance
import com.mdeo.metamodel.data.ModelDataLink
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * The edge operators for an end are complete: for a fixed set of objects, they lead from any
 * consistent way of linking them to every other one.
 *
 * For a few `A`s and `B`s, every subset of the possible `A.e -- B.f` links is enumerated and the
 * consistent ones are kept. Starting from one of them, a breadth-first search follows the changes
 * the operators for `mutate A.e` can make ([validChanges], which [EdgeOperatorExactnessTest]
 * shows to be exactly what the operators do) and must reach all of them.
 */
class EdgeOperatorCompletenessTest {

    private val bounds = listOf("*", "?", "+", "1", "2..3", "2..*", "1..2", "2")
    private val sizes = listOf(2 to 2, 2 to 3, 3 to 2, 3 to 3)

    @TestFactory
    fun `edge operators connect every consistent linking of a few objects`(): List<DynamicTest> =
        bounds.flatMap { e -> bounds.map { f -> e to f } }.map { (e, f) ->
            DynamicTest.dynamicTest("A.e[$e] <--> B.f[$f]") {
                val metamodel = mm("A", "B") { assoc("A.e[$e]", "<-->", "B.f[$f]") }
                for ((as_, bs) in sizes) check(metamodel, "A.e", as_, bs)
            }
        }

    @TestFactory
    fun `edge operators of other shapes connect every consistent linking`(): List<DynamicTest> = listOf(
        "unidirectional" to mm("A", "B") { assoc("A.e[1..2]", "-->", "B") },
        "unidirectional, fixed" to mm("A", "B") { assoc("A.e[2]", "-->", "B") },
        "composition" to mm("A", "B") { assoc("A.e[1..2]", "*-->", "B.f[?]") },
        "composition, fixed part" to mm("A", "B") { assoc("A.e[*]", "*-->", "B.f") }
    ).map { (name, metamodel) ->
        DynamicTest.dynamicTest(name) {
            for ((as_, bs) in sizes) check(metamodel, "A.e", as_, bs)
        }
    }

    // -------------------------------------------------------------------------

    private fun check(metamodel: MetamodelData, endName: String, aCount: Int, bCount: Int) {
        val info = MetamodelInfo(metamodel)
        val end = info.endOf("A", endName.substringAfter("."))!!
        val objects = (1..aCount).map { ModelDataInstance("a$it", "A", emptyMap()) } +
            (1..bCount).map { ModelDataInstance("b$it", "B", emptyMap()) }
        val pairs = (1..aCount).flatMap { a -> (1..bCount).map { b -> linkOf(end, "a$a", "b$b") } }

        val consistent = (0 until (1 shl pairs.size)).map { mask ->
            val links = pairs.filterIndexed { i, _ -> mask and (1 shl i) != 0 }
            ModelData(metamodel.path, objects, links.map { ModelDataLink(it.source, it.sourceProperty, it.target, it.targetProperty) })
        }.filter { problemsOf(it, info).isEmpty() }
        if (consistent.isEmpty()) return

        val specs = operatorSpecs(info, listOf(spec("mutate $endName"))).values.filterIsInstance<EdgeSpec>()
        val byKey = consistent.associateBy { it.key() }
        val reached = mutableSetOf(consistent.first().key())
        val pending = ArrayDeque(listOf(consistent.first()))
        while (pending.isNotEmpty()) {
            val model = pending.removeFirst()
            for (spec in specs) {
                for (next in validChanges(spec, model, info)) {
                    if (reached.add(next)) pending.addLast(byKey.getValue(next))
                }
            }
        }
        val unreached = byKey.keys - reached
        assertTrue(unreached.isEmpty()) {
            "with $aCount A and $bCount B, ${specs.map { it.name }} reach ${reached.size} of ${byKey.size} consistent linkings " +
                "from ${consistent.first().render()}, not e.g. ${byKey.getValue(unreached.first()).render()}"
        }
    }
}
