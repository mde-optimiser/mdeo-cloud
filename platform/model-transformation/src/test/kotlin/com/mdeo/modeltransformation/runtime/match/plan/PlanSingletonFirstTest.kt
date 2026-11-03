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
import kotlin.test.assertTrue

/**
 * Two planner rules about scans (see `MatchPlanBuilder.PlanExecution.candidateComparator`):
 *
 * 1. A step that cannot enlarge the partial-match set (estimated branching factor at most 1: a
 *    scan of a class with a single instance, a to-one walk) goes before any step that fans out,
 *    whatever the structural priority says. Before, only factors strictly below 1 counted, so a
 *    scan of the singleton root of a realisation came after a scan of a large class that the
 *    structural priority ranked higher, and the root was then joined to every candidate.
 * 2. A scan of an instance that pattern links connect to the covered part, but that no link
 *    joins to a covered instance directly (an "anchored" scan: a walk reaches it later), forms the
 *    cross product of the partial matches with its class. It goes before a fan-out walk only when
 *    it cannot enlarge the partial-match set (rule 1); a smaller estimate alone is not enough,
 *    since the cross product is checked against the link only after the walk.
 *
 * The shape is that of a realisation's repair statement:
 * ```
 * Root.calls[*]   <>-> Call.root[1]
 * Root.dirty[*]   -->  Call           (the calls a repair looks at)
 * Call.caller[1]  -->  Asm
 * Block.realised[1] --> Call
 * Root.iface[0..1] --> Iface          (an interface the root names)
 * Iface.ops[*]    <>-> Op.iface[1]
 * Asm.items[*]    <>-> Item.asm[1]   (Asm owns two levels: the structural priority ranks it high)
 * Item.leaves[*]  <>-> Leaf.item[1]
 * ```
 */
class PlanSingletonFirstTest {

    private fun assoc(source: String, role: String, sourceLower: Int, sourceUpper: Int, op: String, target: String, opposite: String?, targetLower: Int, targetUpper: Int) =
        AssociationData(AssociationEndData(source, role, MultiplicityData(sourceLower, sourceUpper)), op, AssociationEndData(target, opposite, MultiplicityData(targetLower, targetUpper)))

    private val metamodel = MetamodelData(
        path = "/test/realisation.mm",
        classes = listOf("Root", "Call", "Asm", "Block", "Iface", "Op", "Item", "Leaf").map { ClassData(it, false) },
        associations = listOf(
            assoc("Root", "calls", 0, -1, "<>->", "Call", "root", 1, 1),
            assoc("Root", "dirty", 0, -1, "-->", "Call", null, 0, -1),
            assoc("Call", "caller", 1, 1, "-->", "Asm", null, 0, -1),
            assoc("Block", "realised", 1, 1, "-->", "Call", null, 0, -1),
            assoc("Root", "iface", 0, 1, "-->", "Iface", null, 0, -1),
            assoc("Iface", "ops", 0, -1, "<>->", "Op", "iface", 1, 1),
            assoc("Asm", "items", 0, -1, "<>->", "Item", "asm", 1, 1),
            assoc("Item", "leaves", 0, -1, "<>->", "Leaf", "item", 1, 1)
        )
    )

    private fun label(role: String, opposite: String?) = EdgeLabelUtils.computeEdgeLabel(role, opposite)

    private fun statistics(blocks: Int, dirty: Int) = object : ModelStatistics {
        val vertices = mapOf("Root" to 1, "Call" to 1000, "Asm" to 500, "Block" to blocks, "Iface" to 400, "Op" to 2000)
        val edges = mapOf(
            label("calls", "root") to 1000, label("dirty", null) to dirty, label("caller", null) to 1000,
            label("realised", null) to blocks, label("iface", null) to 1, label("ops", "iface") to 2000
        )
        override val vertexCount: Int = vertices.values.sum()
        override val edgeCount: Int = edges.values.sum()
        override fun verticesWithLabel(label: String): Int = vertices[label] ?: 0
        override fun edgesWithLabel(edgeLabel: String): Int = edges[edgeLabel] ?: 0
    }

    private fun node(name: String, className: String) = TypedPatternObjectInstanceElement(
        objectInstance = TypedPatternObjectInstance(modifier = null, name = name, className = className, properties = emptyList())
    )

    private fun link(source: String, role: String, target: String, opposite: String?) = TypedPatternLinkElement(
        link = TypedPatternLink(modifier = null, source = TypedPatternLinkEnd(source, role), target = TypedPatternLinkEnd(target, opposite))
    )

    private fun pattern(nodes: List<TypedPatternObjectInstanceElement>, links: List<TypedPatternLinkElement>) = PatternCategories(
        matchableInstances = nodes, matchableLinks = links, createInstances = emptyList(), deleteInstances = emptyList(),
        createLinks = emptyList(), deleteLinks = emptyList(), variables = emptyList(), whereClauses = emptyList()
    )

    private fun plan(elements: PatternCategories, stats: ModelStatistics, bound: Map<String, Int> = emptyMap()): List<BaseStep> {
        val names = elements.matchableInstances.map { it.objectInstance.name }.toSet()
        return MatchPlanBuilder(
            getVertexId = { bound[it] },
            nodeAnalyzer = ExpressionNodeAnalyzer(names, 0),
            isCollectionExpression = { false },
            metamodelData = metamodel,
            statistics = stats
        ).build(elements, emptySet()).baseSteps
    }

    private fun scans(steps: List<BaseStep>) = steps.filterIsInstance<BaseStep.VertexScan>().map { it.instanceName }

    private fun order(steps: List<BaseStep>) =
        steps.mapNotNull { (it as? BaseStep.VertexScan)?.instanceName ?: (it as? BaseStep.EdgeWalk)?.toInstanceName }

    @Test
    fun `the singleton root is scanned first and the rest is walked`() {
        // root.dirty -- call, call.caller -- asm: Asm and Call are large, Root has one instance
        val elements = pattern(
            listOf(node("a", "Asm"), node("c", "Call"), node("r", "Root")),
            listOf(link("r", "dirty", "c", null), link("c", "caller", "a", null))
        )
        val steps = plan(elements, statistics(blocks = 300, dirty = 50))
        assertEquals(listOf("r"), scans(steps), "$steps")
    }

    @Test
    fun `a singleton in a part of the pattern that nothing links to the bound node is scanned before a large class`() {
        // bound call c; the root and its interface form a second component: the root (one
        // instance) is scanned, the interface walked, instead of scanning all 400 interfaces
        val elements = pattern(
            listOf(node("c", "Call"), node("a", "Asm"), node("i", "Iface"), node("r", "Root")),
            listOf(link("c", "caller", "a", null), link("r", "iface", "i", null))
        )
        val steps = plan(elements, statistics(blocks = 300, dirty = 50), mapOf("c" to 5))
        assertEquals(listOf("c", "r"), scans(steps), "$steps")
    }

    @Test
    fun `an anchored scan with a smaller estimate than the walk does not go first`() {
        // bound root; its 50 dirty calls, and the blocks (30 in all) realising them: walking to
        // the calls and then to their blocks visits the blocks of 50 calls; scanning the 30 blocks
        // first would pair each with each of the 50 calls before checking the link
        val elements = pattern(
            listOf(node("r", "Root"), node("c", "Call"), node("b", "Block")),
            listOf(link("r", "dirty", "c", null), link("b", "realised", "c", null))
        )
        val steps = plan(elements, statistics(blocks = 30, dirty = 50), mapOf("r" to 1))
        assertEquals(listOf("r"), scans(steps), "$steps")
        val o = order(steps)
        assertTrue(o.indexOf("c") < o.indexOf("b"), "calls before blocks: $o")
    }
}
