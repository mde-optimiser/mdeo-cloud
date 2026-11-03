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
import com.mdeo.modeltransformation.ast.statements.TypedMatchStatement
import com.mdeo.modeltransformation.graph.mdeo.MdeoModelGraph
import org.junit.jupiter.api.Test
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Which match a single-match statement picks in an optimisation (`deterministic = false`).
 *
 * The engine returns the first match of its search plan after shuffling the graph: every label
 * bucket and adjacency set is an independent, uniformly random permutation, fixed for the match.
 * So at every choice point of the search, each candidate that leads to a match is equally likely
 * to be taken. That is uniform over all matches whenever the matches are spread evenly over the
 * choice points (both cases below); these tests check it with Pearson's chi-square test at a
 * significance level of about 1e-6.
 *
 * The test uses public API only, so the same file can be run against an older engine for a
 * before/after comparison.
 */
class RandomMatchUniformityTest {

    /** `Owner.mentor[0..1] --> Owner`, `Owner.friends[*] --> Owner`. */
    private val metamodelData = MetamodelData(
        path = "/uniform.mm",
        classes = listOf(ClassData("Owner", false)),
        associations = listOf(
            AssociationData(AssociationEndData("Owner", "mentor", MultiplicityData(0, 1)), "-->", AssociationEndData("Owner", null, MultiplicityData(0, -1))),
            AssociationData(AssociationEndData("Owner", "friends", MultiplicityData(0, -1)), "-->", AssociationEndData("Owner", null, MultiplicityData(0, -1)))
        )
    )
    private val metamodel = Metamodel.compile(metamodelData)

    private val types = listOf(
        VoidType(),
        ClassTypeRef(`package` = "class/uniform.mm", type = "Owner", isNullable = false)
    )

    private fun owner(name: String) = TypedPatternObjectInstanceElement(
        objectInstance = TypedPatternObjectInstance(modifier = null, name = name, className = "Owner", properties = emptyList())
    )

    private fun link(modifier: String?, source: String, property: String, target: String) = TypedPatternLinkElement(
        link = TypedPatternLink(
            modifier = modifier,
            source = TypedPatternLinkEnd(objectName = source, propertyName = property),
            target = TypedPatternLinkEnd(objectName = target, propertyName = null)
        )
    )

    private fun model(owners: List<String>, friends: List<Pair<String, String>>) = ModelData(
        "/uniform.mm",
        owners.map { ModelDataInstance(it, "Owner", emptyMap()) },
        friends.map { (s, t) -> ModelDataLink(s, "friends", t, null) }
    )

    /** Runs the one-statement transformation [elements] [draws] times; counts the mentor links it creates. */
    private fun draw(model: ModelData, elements: List<TypedPatternElement>, draws: Int, deterministic: Boolean = false): Map<String, Int> {
        val ast = TypedAst(types = types, metamodelPath = "/uniform.mm", statements = listOf(TypedMatchStatement(pattern = TypedPattern(elements = elements))))
        val counts = HashMap<String, Int>()
        repeat(draws) {
            MdeoModelGraph.create(model, metamodel).use { graph ->
                assertIs<TransformationExecutionResult.Success>(TransformationEngine.create(graph, ast, deterministic = deterministic).execute())
                val created = graph.toModelData().links.filter { it.sourceProperty == "mentor" }
                assertEquals(1, created.size)
                counts.merge("${created[0].sourceName}->${created[0].targetName}", 1, Int::plus)
            }
        }
        return counts
    }

    private fun assertUniform(counts: Map<String, Int>, outcomes: Set<String>) {
        assertEquals(outcomes, counts.keys, "every match must be chosen sometimes, and nothing else")
        val n = counts.values.sum()
        val e = n.toDouble() / outcomes.size
        val statistic = counts.values.sumOf { (it - e).pow(2) / e }
        val df = outcomes.size - 1
        val critical = df * (1 - 2.0 / (9 * df) + 4.75 * sqrt(2.0 / (9 * df))).pow(3)
        println("chi-square $statistic (critical $critical, df $df): $counts")
        assertTrue(statistic < critical, "not uniform: chi-square $statistic >= $critical (df $df): $counts")
    }

    private val owners = listOf("a", "b", "c", "d", "e")

    @Test
    fun `two independent objects of one class are a uniform ordered pair`() {
        val counts = draw(model(owners, emptyList()), listOf(owner("s"), owner("t"), link("create", "s", "mentor", "t")), 2_000)
        assertUniform(counts, owners.flatMap { s -> owners.filter { it != s }.map { "$s->$it" } }.toSet())
    }

    @Test
    fun `a matched link is chosen uniformly when the matches are spread evenly`() {
        val friends = listOf("a" to "b", "a" to "c", "d" to "e", "d" to "f")
        val counts = draw(
            model(owners + "f", friends),
            listOf(owner("s"), owner("t"), link(null, "s", "friends", "t"), link("create", "s", "mentor", "t")),
            1_200
        )
        assertUniform(counts, friends.map { (s, t) -> "$s->$t" }.toSet())
    }

    @Test
    fun `the deterministic engine always picks the same match`() {
        val friends = listOf("a" to "b", "a" to "c", "d" to "e", "d" to "f")
        val counts = draw(
            model(owners + "f", friends),
            listOf(owner("s"), owner("t"), link(null, "s", "friends", "t"), link("create", "s", "mentor", "t")),
            20, deterministic = true
        )
        assertEquals(1, counts.size, "$counts")
    }
}
