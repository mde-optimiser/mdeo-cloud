package com.mdeo.modeltransformation.runtime.match.plan

import com.mdeo.metamodel.data.AssociationData
import com.mdeo.metamodel.data.AssociationEndData
import com.mdeo.metamodel.data.ClassData
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.metamodel.data.MultiplicityData
import com.mdeo.modeltransformation.ast.EdgeLabelUtils
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLink
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLinkElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLinkEnd
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstance
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstanceElement
import com.mdeo.modeltransformation.graph.ModelStatistics
import com.mdeo.modeltransformation.runtime.match.ExpressionNodeAnalyzer
import com.mdeo.modeltransformation.runtime.match.PatternCategories
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Once part of a pattern is covered — a pinned or earlier-bound node, or the first scan — the
 * planner reaches the rest of that connected pattern by walking, instead of scanning class buckets
 * next to it (see `MatchPlanBuilder.PlanExecution.candidateComparator`). The match sets are the
 * same either way (MatchSetOracleTest); these tests check the order.
 *
 * The shape is that of a Palladio realisation statement: a SEFF, its steps, a branch's transitions
 * and their child behaviours, which own further leaves.
 *
 * ```
 * Seff.steps[*]     <>-> Unit.seff[1]
 * Unit.branches[*]  <>-> Trans.unit[1]
 * Trans.child[*]    -->  Child.trans[*]
 * Child.leaves[*]   <>-> Leaf.child[1]
 * Hub.seffs[*]      -->  Seff.hub[*]      (unconnected to the others in the patterns below)
 * Tag.units[*]      -->  Unit.tags[*]     (a single tag on two units)
 * ```
 *
 * `Child` owns `Leaf`s, so the structural priority ranks it above `Trans`; without the rule the
 * planner scanned all children after covering a unit, instead of walking to its transitions.
 */
class PlanFromBoundNodesTest {

    private fun assoc(source: String, role: String, sourceUpper: Int, op: String, target: String, opposite: String, targetUpper: Int) =
        AssociationData(AssociationEndData(source, role, MultiplicityData(0, sourceUpper)), op, AssociationEndData(target, opposite, MultiplicityData(if (targetUpper == 1) 1 else 0, targetUpper)))

    private val metamodel = MetamodelData(
        path = "/test/seff.mm",
        classes = listOf("Seff", "Unit", "Trans", "Child", "Leaf", "Hub", "Tag").map { ClassData(it, false) },
        associations = listOf(
            assoc("Seff", "steps", -1, "<>->", "Unit", "seff", 1),
            assoc("Unit", "branches", -1, "<>->", "Trans", "unit", 1),
            assoc("Trans", "child", -1, "-->", "Child", "trans", -1),
            assoc("Child", "leaves", -1, "<>->", "Leaf", "child", 1),
            assoc("Hub", "seffs", -1, "-->", "Seff", "hub", -1),
            assoc("Tag", "units", -1, "-->", "Unit", "tags", -1)
        )
    )

    private val statistics = object : ModelStatistics {
        val vertices = mapOf("Seff" to 100, "Unit" to 1000, "Trans" to 2000, "Child" to 3000, "Leaf" to 3000, "Hub" to 1, "Tag" to 1)
        val edges = mapOf(
            label("steps", "seff") to 1000, label("branches", "unit") to 2000, label("child", "trans") to 2000,
            label("leaves", "child") to 3000, label("seffs", "hub") to 2, label("units", "tags") to 2
        )
        override val vertexCount: Int = vertices.values.sum()
        override val edgeCount: Int = edges.values.sum()
        override fun verticesWithLabel(label: String): Int = vertices[label] ?: 0
        override fun edgesWithLabel(edgeLabel: String): Int = edges[edgeLabel] ?: 0
    }

    private fun label(role: String, opposite: String) = EdgeLabelUtils.computeEdgeLabel(role, opposite)

    private fun node(name: String, className: String) = TypedPatternObjectInstanceElement(
        objectInstance = TypedPatternObjectInstance(modifier = null, name = name, className = className, properties = emptyList())
    )

    private fun link(source: String, role: String, target: String, opposite: String) = TypedPatternLinkElement(
        link = TypedPatternLink(modifier = null, source = TypedPatternLinkEnd(source, role), target = TypedPatternLinkEnd(target, opposite))
    )

    private fun pattern(nodes: List<TypedPatternObjectInstanceElement>, links: List<TypedPatternLinkElement>) = PatternCategories(
        matchableInstances = nodes, matchableLinks = links, createInstances = emptyList(), deleteInstances = emptyList(),
        createLinks = emptyList(), deleteLinks = emptyList(), variables = emptyList(), whereClauses = emptyList()
    )

    private val branchPattern = pattern(
        listOf(node("s", "Seff"), node("u", "Unit"), node("t", "Trans"), node("c", "Child"), node("l", "Leaf")),
        listOf(link("s", "steps", "u", "seff"), link("u", "branches", "t", "unit"), link("t", "child", "c", "trans"), link("c", "leaves", "l", "child"))
    )

    private fun plan(elements: PatternCategories, bound: Map<String, Int> = emptyMap()): List<BaseStep> {
        val names = elements.matchableInstances.map { it.objectInstance.name }.toSet()
        return MatchPlanBuilder(
            getVertexId = { bound[it] },
            nodeAnalyzer = ExpressionNodeAnalyzer(names, 0),
            isCollectionExpression = { false },
            metamodelData = metamodel,
            statistics = statistics
        ).build(elements, emptySet()).baseSteps
    }

    private fun scans(steps: List<BaseStep>) = steps.filterIsInstance<BaseStep.VertexScan>().map { it.instanceName }

    @Test
    fun `after the first scan the rest of a connected pattern is walked`() {
        val steps = plan(branchPattern)
        assertEquals(1, scans(steps).size, "one scan, the rest walks: $steps")
    }

    @Test
    fun `a bound node is the root of the search, every other node is walked`() {
        for (bound in listOf("s", "u", "t", "c", "l")) {
            val steps = plan(branchPattern, mapOf(bound to 7))
            val first = steps.first()
            assertIs<BaseStep.VertexScan>(first)
            assertEquals(bound, first.instanceName)
            assertEquals(7, first.vertexId)
            assertEquals(listOf(bound), scans(steps), "bound $bound: $steps")
        }
    }

    @Test
    fun `a disconnected node is still scanned`() {
        val steps = plan(pattern(branchPattern.matchableInstances + node("h", "Hub"), branchPattern.matchableLinks), mapOf("t" to 3))
        assertEquals(setOf("t", "h"), scans(steps).toSet(), "$steps")
    }

    @Test
    fun `an anchored scan that is clearly cheaper than the walk still goes first`() {
        // bound SEFF s, its units u (fan-out 10) carrying the tag g. The tag class has a single
        // instance: scanning it yields one candidate, walking to the units ten.
        val elements = pattern(
            listOf(node("s", "Seff"), node("u", "Unit"), node("g", "Tag")),
            listOf(link("s", "steps", "u", "seff"), link("g", "units", "u", "tags"))
        )
        val steps = plan(elements, mapOf("s" to 1))
        val order = steps.mapNotNull { (it as? BaseStep.VertexScan)?.instanceName ?: (it as? BaseStep.EdgeWalk)?.toInstanceName }
        assertEquals(listOf("s", "g"), scans(steps), "$steps")
        assertTrue(order.indexOf("g") < order.indexOf("u"), "the tag before the units: $order")
    }
}
