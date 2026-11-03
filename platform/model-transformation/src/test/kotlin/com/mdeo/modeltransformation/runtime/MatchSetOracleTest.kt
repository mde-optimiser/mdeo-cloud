package com.mdeo.modeltransformation.runtime

import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.expression.ast.types.VoidType
import com.mdeo.metamodel.Metamodel
import com.mdeo.metamodel.data.AssociationData
import com.mdeo.metamodel.data.AssociationEndData
import com.mdeo.metamodel.data.ClassData
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.metamodel.data.ModelData
import com.mdeo.metamodel.data.ModelDataInstance
import com.mdeo.metamodel.data.ModelDataLink
import com.mdeo.metamodel.data.MultiplicityData
import com.mdeo.modeltransformation.ast.TypedAst
import com.mdeo.modeltransformation.ast.patterns.TypedPattern
import com.mdeo.modeltransformation.ast.patterns.TypedPatternElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLink
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLinkElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLinkEnd
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstance
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstanceElement
import com.mdeo.modeltransformation.ast.statements.TypedForMatchStatement
import com.mdeo.modeltransformation.ast.statements.TypedMatchStatement
import com.mdeo.modeltransformation.ast.statements.TypedTransformationStatement
import com.mdeo.modeltransformation.graph.mdeo.MdeoModelGraph
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Whatever order the planner chooses — scanning class buckets or walking from bound nodes — a
 * `for match` statement must find exactly the matches of its pattern. Checked against a
 * brute-force oracle on random graphs, for patterns with and without a node bound by an earlier
 * statement, in deterministic and in shuffled mode. Uses public API only.
 */
class MatchSetOracleTest {

    /** `A.ab[*] --> B`, `B.bc[*] --> C`, `C.ca[*] --> A`; `Mark` records a match. */
    private val metamodelData = MetamodelData(
        path = "/oracle.mm",
        classes = listOf("A", "B", "C", "Mark").map { ClassData(it, false) },
        associations = listOf(
            assoc("A", "ab", "B"), assoc("B", "bc", "C"), assoc("C", "ca", "A"),
            assoc("Mark", "a", "A"), assoc("Mark", "b", "B"), assoc("Mark", "b2", "B"), assoc("Mark", "c", "C")
        )
    )
    private val metamodel = Metamodel.compile(metamodelData)
    private val types = listOf(VoidType(), ClassTypeRef(`package` = "class/oracle.mm", type = "A", isNullable = false))

    private fun assoc(source: String, role: String, target: String) =
        AssociationData(AssociationEndData(source, role, MultiplicityData(0, -1)), "-->", AssociationEndData(target, null, MultiplicityData(0, -1)))

    private fun node(modifier: String?, name: String, className: String) = TypedPatternObjectInstanceElement(
        objectInstance = TypedPatternObjectInstance(modifier = modifier, name = name, className = className, properties = emptyList())
    )

    private fun link(modifier: String?, source: String, role: String, target: String) = TypedPatternLinkElement(
        link = TypedPatternLink(modifier, TypedPatternLinkEnd(source, role), TypedPatternLinkEnd(target, null))
    )

    /** A pattern: its nodes (name to class) and links (source, role, target). */
    private data class Shape(val name: String, val nodes: Map<String, String>, val links: List<Triple<String, String, String>>)

    private val shapes = listOf(
        Shape("chain", mapOf("a" to "A", "b" to "B", "c" to "C"), listOf(Triple("a", "ab", "b"), Triple("b", "bc", "c"))),
        Shape("triangle", mapOf("a" to "A", "b" to "B", "c" to "C"), listOf(Triple("a", "ab", "b"), Triple("b", "bc", "c"), Triple("c", "ca", "a"))),
        Shape("star", mapOf("a" to "A", "b" to "B", "b2" to "B"), listOf(Triple("a", "ab", "b"), Triple("a", "ab", "b2"))),
        Shape("path of four", mapOf("c" to "C", "a" to "A", "b" to "B", "b2" to "B"), listOf(Triple("c", "ca", "a"), Triple("a", "ab", "b"), Triple("b", "bc", "c"), Triple("a", "ab", "b2")))
    )

    private fun randomModel(random: Random): ModelData {
        val instances = listOf("A" to 6, "B" to 8, "C" to 6).flatMap { (c, n) -> (0 until n).map { ModelDataInstance("$c$it", c, emptyMap()) } }
        val byClass = instances.groupBy { it.className }
        val links = mutableListOf<ModelDataLink>()
        for ((from, role, to) in listOf(Triple("A", "ab", "B"), Triple("B", "bc", "C"), Triple("C", "ca", "A"))) {
            for (s in byClass.getValue(from)) for (t in byClass.getValue(to)) {
                if (random.nextDouble() < 0.3) links += ModelDataLink(s.name, role, t.name, null)
            }
        }
        return ModelData("/oracle.mm", instances, links)
    }

    /** All injective assignments of [shape]'s nodes with every link present ([fixed] nodes given). */
    private fun oracle(model: ModelData, shape: Shape, fixed: Map<String, String>): Set<Map<String, String>> {
        val edges = model.links.map { Triple(it.sourceName, it.sourceProperty, it.targetName) }.toSet()
        val byClass = model.instances.groupBy({ it.className }, { it.name })
        var partial = listOf(emptyMap<String, String>())
        for ((name, className) in shape.nodes) {
            val candidates = fixed[name]?.let { listOf(it) } ?: byClass.getValue(className)
            partial = partial.flatMap { m -> candidates.filter { it !in m.values }.map { m + (name to it) } }
        }
        return partial.filter { m -> shape.links.all { (s, r, t) -> Triple(m.getValue(s), r, m.getValue(t)) in edges } }.toSet()
    }

    /**
     * Runs `[match { bound: Class }] for match { shape; create mark }` and returns the matches
     * recorded by the marks, and the vertex the first statement bound.
     */
    private fun engine(model: ModelData, shape: Shape, bound: String?, deterministic: Boolean): Pair<Set<Map<String, String>>, String?> {
        val statements = mutableListOf<TypedTransformationStatement>()
        if (bound != null) {
            statements += TypedMatchStatement(pattern = TypedPattern(elements = listOf(node(null, bound, shape.nodes.getValue(bound)), node("create", "pin", "Mark"), link("create", "pin", bound, bound))))
        }
        val elements = mutableListOf<TypedPatternElement>()
        shape.nodes.forEach { (n, c) -> elements += node(null, n, c) }
        shape.links.forEach { (s, r, t) -> elements += link(null, s, r, t) }
        elements += node("create", "mark", "Mark")
        shape.nodes.keys.forEach { elements += link("create", "mark", it, it) }
        statements += TypedForMatchStatement(pattern = TypedPattern(elements = elements), doBlock = emptyList())
        val ast = TypedAst(types = types, metamodelPath = "/oracle.mm", statements = statements)
        return MdeoModelGraph.create(model, metamodel).use { graph ->
            assertIs<TransformationExecutionResult.Success>(TransformationEngine.create(graph, ast, deterministic = deterministic).execute())
            val after = graph.toModelData()
            val marks = after.instances.filter { it.className == "Mark" }.map { it.name }.toSet()
            val byMark = after.links.filter { it.sourceName in marks }.groupBy { it.sourceName }
            val pin = byMark.values.firstOrNull { links -> links.size == 1 }?.single()?.targetName
            val matches = byMark.values.filter { it.size == shape.nodes.size }.map { links -> links.associate { it.sourceProperty!! to it.targetName } }
            assertEquals(matches.size, matches.toSet().size, "a match was found twice")
            matches.toSet() to pin
        }
    }

    @Test
    fun `for match finds exactly the oracle's matches, with and without a bound node`() {
        val random = Random(20261019)
        var compared = 0
        repeat(8) {
            val model = randomModel(random)
            for (shape in shapes) {
                for (bound in listOf(null) + shape.nodes.keys) {
                    for (deterministic in listOf(true, false)) {
                        val (found, pin) = engine(model, shape, bound, deterministic)
                        val expected = oracle(model, shape, if (bound == null) emptyMap() else mapOf(bound to pin!!))
                        assertEquals(expected, found, "${shape.name}, bound $bound ($pin), deterministic $deterministic")
                        compared += expected.size
                    }
                }
            }
        }
        assertTrue(compared > 500, "the random graphs should give enough matches to compare: $compared")
    }
}
