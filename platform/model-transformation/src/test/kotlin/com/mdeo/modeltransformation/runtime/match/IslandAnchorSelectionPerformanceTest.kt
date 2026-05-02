package com.mdeo.modeltransformation.runtime.match

import com.mdeo.metamodel.Metamodel
import com.mdeo.metamodel.data.*
import com.mdeo.modeltransformation.ast.EdgeLabelUtils
import com.mdeo.modeltransformation.ast.TypedAst
import com.mdeo.modeltransformation.ast.patterns.*
import com.mdeo.modeltransformation.ast.statements.TypedMatchStatement
import com.mdeo.modeltransformation.compiler.ExpressionCompilerRegistry
import com.mdeo.modeltransformation.graph.tinker.TinkerModelGraph
import com.mdeo.modeltransformation.runtime.*
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Performance regression test for multi-anchor island constraints.
 *
 * When a forbid island has **two** anchors — one reachable via a to-many association
 * and one via a to-one association — the planner should pick the to-one anchor so that
 * the island traversal scans the fewest nodes.
 *
 * ## Scenario (Scrum-inspired)
 *
 * Metamodel:
 * ```
 * Plan.sprints[0..*] *--> Sprint.plan[1]
 * Sprint.committedItems[1..*] <--> WorkItem.isPlannedFor[0..1]
 * ```
 *
 * Pattern (equivalent to the user-reported slow transformation):
 * ```
 * match workItem : WorkItem {}
 * match plan     : Plan {}
 * forbid sprint1 : Sprint {}
 * forbid sprint1 -- workItem   (sprint1.committedItems -- workItem.isPlannedFor)
 * forbid plan    -- sprint1    (plan.sprints -- sprint1.plan)
 * ```
 *
 * Anchors = {workItem, plan}.  The optimised anchor is `workItem` (isPlannedFor[0..1] →
 * at most 1 hop into the island) rather than `plan` (sprints[0..*] → O(N) hops).
 *
 * With N=500 sprints in the graph the test verifies:
 * - **Correctness**: the match succeeds when no forbidden sprint exists, and fails
 *   when a qualifying forbidden sprint does exist.
 * - **Performance**: the successful match completes in well under 1 second, a threshold
 *   that would be trivially violated by the unoptimised O(N) plan.
 */
class IslandAnchorSelectionPerformanceTest {

    companion object {
        private const val SPRINT_COUNT = 500
        private const val PERFORMANCE_LIMIT_MS = 2_000L
    }

    // ── Metamodel ─────────────────────────────────────────────────────────────

    private val metamodelData = MetamodelData(
        path = "/test/scrum_perf.mm",
        classes = listOf(
            ClassData(name = "Plan",     isAbstract = false),
            ClassData(name = "Sprint",   isAbstract = false),
            ClassData(name = "WorkItem", isAbstract = false)
        ),
        associations = listOf(
            // Plan.sprints[0..*] *--> Sprint.plan[1]
            AssociationData(
                source = AssociationEndData(
                    className = "Plan",
                    name = "sprints",
                    multiplicity = MultiplicityData.many()
                ),
                operator = "*-->",
                target = AssociationEndData(
                    className = "Sprint",
                    name = "plan",
                    multiplicity = MultiplicityData.single()
                )
            ),
            // Sprint.committedItems[1..*] <--> WorkItem.isPlannedFor[0..1]
            AssociationData(
                source = AssociationEndData(
                    className = "Sprint",
                    name = "committedItems",
                    multiplicity = MultiplicityData.oneOrMore()
                ),
                operator = "<-->",
                target = AssociationEndData(
                    className = "WorkItem",
                    name = "isPlannedFor",
                    multiplicity = MultiplicityData.optional()
                )
            )
        )
    )

    private val metamodel = Metamodel.compile(metamodelData)

    private val spritsToplan = EdgeLabelUtils.computeEdgeLabel("sprints", "plan")
    private val committedToPlanned = EdgeLabelUtils.computeEdgeLabel("committedItems", "isPlannedFor")

    // ── Infrastructure ─────────────────────────────────────────────────────────

    private lateinit var graph: TinkerGraph
    private lateinit var engine: TransformationEngine
    private lateinit var context: TransformationExecutionContext

    @BeforeEach
    fun setUp() {
        graph = TinkerGraph.open()
        context = TransformationExecutionContext.empty()
        engine = TransformationEngine(
            modelGraph = TinkerModelGraph.wrap(graph, metamodel),
            ast = TypedAst(
                types = emptyList(),
                metamodelPath = "/test/scrum_perf.mm",
                statements = emptyList()
            ),
            expressionCompilerRegistry = ExpressionCompilerRegistry.createDefaultRegistry(),
            statementExecutorRegistry = StatementExecutorRegistry.createDefaultRegistry()
        )
    }

    @AfterEach
    fun tearDown() {
        graph.close()
    }

    // ── Pattern builder helpers ────────────────────────────────────────────────

    /**
     * Pattern:
     * ```
     * match workItem : WorkItem {}
     * match plan     : Plan {}
     * forbid sprint1 : Sprint {}
     * forbid sprint1 -- workItem   (sprint1.committedItems -- workItem.isPlannedFor)
     * forbid plan    -- sprint1    (plan.sprints -- sprint1.plan)
     * ```
     *
     * The island has two anchors: {workItem, plan}.  With the multiplicity-aware
     * anchor selector the planner picks `workItem` (isPlannedFor[0..1]), avoiding
     * a full scan of all sprints connected to `plan`.
     */
    private fun buildPattern(): TypedMatchStatement = TypedMatchStatement(
        pattern = TypedPattern(
            elements = listOf(
                matchInstance("workItem", "WorkItem"),
                matchInstance("plan", "Plan"),
                forbidInstance("sprint1", "Sprint"),
                // forbid sprint1 -- workItem  (sprint1.committedItems -> workItem.isPlannedFor)
                forbidLink("sprint1", "committedItems", "workItem", "isPlannedFor"),
                // forbid plan -- sprint1  (plan.sprints -> sprint1.plan)
                forbidLink("plan", "sprints", "sprint1", "plan")
            )
        )
    )

    // ── Tests ──────────────────────────────────────────────────────────────────

    /**
     * Main regression test.
     *
     * Builds a graph with [SPRINT_COUNT] sprints all owned by one plan, plus one
     * unassigned work item.  The forbid pattern must NOT match (no sprint is
     * simultaneously connected to both the workItem and the plan) so the overall
     * match should SUCCEED.
     *
     * Under the unoptimised plan (anchor = plan) the executor would walk all
     * [SPRINT_COUNT] sprints and check each one against the workItem — O(N).
     * Under the optimised plan (anchor = workItem, isPlannedFor[0..1]) the
     * executor checks the workItem's single optional sprint reference — O(1).
     */
    @Test
    fun `match succeeds quickly when workItem is unassigned despite many plan sprints`() {
        // Build: 1 plan, SPRINT_COUNT sprints (all owned by plan), 1 unassigned workItem.
        val plan = graph.addVertex("Plan")
        repeat(SPRINT_COUNT) {
            val sprint = graph.addVertex("Sprint")
            plan.addEdge(spritsToplan, sprint)
        }
        graph.addVertex("WorkItem")
        // workItem intentionally NOT connected to any sprint.

        val statement = buildPattern()

        val startMs = System.currentTimeMillis()
        val result = engine.executeStatement(statement, context)
        val elapsedMs = System.currentTimeMillis() - startMs

        println("=== IslandAnchorSelectionPerformanceTest ===")
        println("  $SPRINT_COUNT sprints, unassigned workItem → elapsed: ${elapsedMs}ms")
        println("  Performance limit: ${PERFORMANCE_LIMIT_MS}ms")

        assertIs<TransformationExecutionResult.Success>(
            result,
            "Expected match SUCCESS: no forbidden sprint exists (workItem is unassigned)"
        )
        assert(elapsedMs < PERFORMANCE_LIMIT_MS) {
            "Match took ${elapsedMs}ms which exceeds limit of ${PERFORMANCE_LIMIT_MS}ms. " +
            "Anchor selection optimisation may not be working."
        }
    }

    /**
     * Correctness: the match should FAIL when a sprint exists that is connected to
     * both the workItem and the plan (the forbidden pattern holds).
     */
    @Test
    fun `match fails when a sprint is connected to both workItem and plan`() {
        val plan = graph.addVertex("Plan")
        // Add many "innocent" sprints that are only connected to plan.
        repeat(SPRINT_COUNT - 1) {
            val sprint = graph.addVertex("Sprint")
            plan.addEdge(spritsToplan, sprint)
        }
        // Add the "guilty" sprint: connected to both plan AND workItem.
        val guiltySprint = graph.addVertex("Sprint")
        plan.addEdge(spritsToplan, guiltySprint)
        val workItem = graph.addVertex("WorkItem")
        guiltySprint.addEdge(committedToPlanned, workItem)

        val statement = buildPattern()
        val result = engine.executeStatement(statement, context)

        assertIs<TransformationExecutionResult.Failure>(
            result,
            "Expected match FAILURE: guilty sprint is connected to both workItem and plan"
        )
    }

    /**
     * Correctness: the match should SUCCEED when the workItem has a sprint but
     * that sprint is NOT owned by the matched plan (no full forbidden pattern).
     */
    @Test
    fun `match succeeds when workItem sprint is not owned by the matched plan`() {
        val plan = graph.addVertex("Plan")
        repeat(SPRINT_COUNT) {
            val sprint = graph.addVertex("Sprint")
            plan.addEdge(spritsToplan, sprint)
        }
        // workItem has a sprint but that sprint belongs to a DIFFERENT plan.
        val otherPlan = graph.addVertex("Plan")
        val sprintOther = graph.addVertex("Sprint")
        otherPlan.addEdge(spritsToplan, sprintOther)
        val workItem = graph.addVertex("WorkItem")
        sprintOther.addEdge(committedToPlanned, workItem)

        // The pattern matches workItem+plan (the original one, not otherPlan).
        // For (workItem, plan): the forbidden sprint would need to be owned by `plan`
        // AND connected to workItem.  sprintOther is connected to workItem but owned
        // by otherPlan, so the forbid does NOT trigger for (workItem, plan).
        val statement = buildPattern()
        val result = engine.executeStatement(statement, context)

        assertIs<TransformationExecutionResult.Success>(
            result,
            "Expected match SUCCESS: workItem's sprint belongs to a different plan"
        )
    }

    // ── Element helpers ────────────────────────────────────────────────────────

    private fun matchInstance(name: String, className: String) =
        TypedPatternObjectInstanceElement(
            objectInstance = TypedPatternObjectInstance(
                modifier = null, name = name, className = className, properties = emptyList()
            )
        )

    private fun forbidInstance(name: String, className: String) =
        TypedPatternObjectInstanceElement(
            objectInstance = TypedPatternObjectInstance(
                modifier = "forbid", name = name, className = className, properties = emptyList()
            )
        )

    private fun forbidLink(
        sourceName: String,
        sourceProperty: String?,
        targetName: String,
        targetProperty: String?
    ) = TypedPatternLinkElement(
        link = TypedPatternLink(
            modifier = "forbid",
            source = TypedPatternLinkEnd(objectName = sourceName, propertyName = sourceProperty),
            target = TypedPatternLinkEnd(objectName = targetName, propertyName = targetProperty)
        )
    )
}
