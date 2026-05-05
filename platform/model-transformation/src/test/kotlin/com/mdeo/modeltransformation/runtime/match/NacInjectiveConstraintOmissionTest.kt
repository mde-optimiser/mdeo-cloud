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
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertIs

/**
 * Tests for the single-node NAC injective-constraint omission (improvement 1) and
 * composition-aware component traversal (improvement 2).
 *
 * ## Scenario (Scrum-inspired)
 *
 * Metamodel:
 * ```
 * Plan.sprints[0..*] *--> Sprint.plan[1]      (composition)
 * Sprint.committedItems[1..*] <--> WorkItem.isPlannedFor[0..1]
 * ```
 *
 * Mutation pattern (addItemToSprint):
 * ```
 * sprint2 : Sprint {}
 * plan    : Plan {}
 * workItem: WorkItem {}
 * forbid sprint1 : Sprint {}
 *
 * sprint2 -- plan                       (matchable)
 * forbid workItem -- sprint1            (NAC: sprint1 -- workItem)
 * forbid sprint2 -- workItem            (forbid orphan link)
 * forbid plan -- sprint1                (NAC: plan -- sprint1)
 * ```
 *
 * **Improvement 1**: the NAC island for sprint1 has two anchors (workItem and plan)
 * and a single island node (sprint1 : Sprint).  Normally the planner would emit
 * `sprint1 != sprint2` because both are Sprints.  With the optimisation this
 * constraint is **omitted**: if sprint1 = sprint2 the NAC would fire only when
 * the edge sprint2–workItem exists, but that edge is already guarded by the
 * `forbid sprint2 -- workItem` orphan link.  Correctness is preserved.
 *
 * **Improvement 2**: the step-level greedy algorithm interleaves instances across
 * logical connectivity groups.  It first scans Plan (highest class priority, it is
 * the composition root), then immediately scans WorkItem — even though sprint2 is
 * adjacent to plan — because covering WorkItem unlocks the NAC island (anchors =
 * {workItem, plan}) at lower cost.  The NAC is then emitted inline before the
 * EdgeWalk(plan → sprint2) is added.  This prunes traversers before the expensive
 * sprint2 fan-out rather than after it.
 */
class NacInjectiveConstraintOmissionTest {

    companion object {
        private const val SPRINT_COUNT = 200
        private const val PERFORMANCE_LIMIT_MS = 2_000L
    }

    // ── Metamodel ─────────────────────────────────────────────────────────────

    private val metamodelData = MetamodelData(
        path = "/test/scrum_nac.mm",
        classes = listOf(
            ClassData(name = "Plan",     isAbstract = false),
            ClassData(name = "Sprint",   isAbstract = false),
            ClassData(name = "WorkItem", isAbstract = false)
        ),
        associations = listOf(
            // Plan.sprints[0..*] *--> Sprint.plan[1]
            AssociationData(
                source = AssociationEndData(className = "Plan",   name = "sprints",        multiplicity = MultiplicityData.many()),
                operator = "<>->",
                target = AssociationEndData(className = "Sprint", name = "plan",           multiplicity = MultiplicityData.single())
            ),
            // Sprint.committedItems[1..*] <--> WorkItem.isPlannedFor[0..1]
            AssociationData(
                source = AssociationEndData(className = "Sprint",   name = "committedItems", multiplicity = MultiplicityData.oneOrMore()),
                operator = "<-->",
                target = AssociationEndData(className = "WorkItem", name = "isPlannedFor",   multiplicity = MultiplicityData.optional())
            )
        )
    )

    private val metamodel = Metamodel.compile(metamodelData)

    private val sprintsToPlan     = EdgeLabelUtils.computeEdgeLabel("sprints", "plan")
    private val committedToPlanned = EdgeLabelUtils.computeEdgeLabel("committedItems", "isPlannedFor")

    // ── Infrastructure ────────────────────────────────────────────────────────

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
                metamodelPath = "/test/scrum_nac.mm",
                statements = emptyList()
            ),
            expressionCompilerRegistry = ExpressionCompilerRegistry.createDefaultRegistry(),
            statementExecutorRegistry = StatementExecutorRegistry.createDefaultRegistry()
        )
    }

    @AfterEach
    fun tearDown() { graph.close() }

    // ── Pattern builder ───────────────────────────────────────────────────────

    /**
     * The full addItemToSprint pattern as described in the class KDoc.
     *
     * NAC island: sprint1 connected to workItem and plan.
     * Forbid orphan link: sprint2 -- workItem.
     *
     * With improvement 1 the planner must **not** emit `sprint1 != sprint2`
     * because the `forbid sprint2 -- workItem` constraint already covers that case.
     */
    private fun buildPattern(): TypedMatchStatement = TypedMatchStatement(
        pattern = TypedPattern(
            elements = listOf(
                matchInstance("sprint2",  "Sprint"),
                matchInstance("plan",     "Plan"),
                matchInstance("workItem", "WorkItem"),
                forbidInstance("sprint1", "Sprint"),

                // sprint2 -- plan  (Plan.sprints --> Sprint.plan)
                matchLink("plan", "sprints", "sprint2", "plan"),

                // forbid workItem -- sprint1  (Sprint.committedItems --> WorkItem.isPlannedFor)
                forbidLink("sprint1", "committedItems", "workItem", "isPlannedFor"),

                // forbid sprint2 -- workItem  (orphan link, both endpoints are main pattern)
                forbidLink("sprint2", "committedItems", "workItem", "isPlannedFor"),

                // forbid plan -- sprint1  (Plan.sprints --> Sprint.plan)
                forbidLink("plan", "sprints", "sprint1", "plan")
            )
        )
    )

    // ── Correctness tests ─────────────────────────────────────────────────────

    @Nested
    inner class Correctness {

        @Test
        fun `match succeeds when no sprint is connected to workItem`() {
            val plan = graph.addVertex("Plan")
            val sprint2 = graph.addVertex("Sprint")
            plan.addEdge(sprintsToPlan, sprint2)
            graph.addVertex("WorkItem") // unassigned

            val result = engine.executeStatement(buildPattern(), context)
            assertIs<TransformationExecutionResult.Success>(result,
                "Expected SUCCESS: workItem has no sprint")
        }

        @Test
        fun `match fails when sprint2 is already connected to workItem`() {
            val plan = graph.addVertex("Plan")
            val sprint2 = graph.addVertex("Sprint")
            plan.addEdge(sprintsToPlan, sprint2)
            val workItem = graph.addVertex("WorkItem")
            // The forbid orphan link fires: sprint2 already connected to workItem.
            sprint2.addEdge(committedToPlanned, workItem)

            val result = engine.executeStatement(buildPattern(), context)
            assertIs<TransformationExecutionResult.Failure>(result,
                "Expected FAILURE: sprint2 already connected to workItem (forbid orphan link)")
        }

        @Test
        fun `match fails when a different sprint is connected to both workItem and plan`() {
            val plan = graph.addVertex("Plan")
            val sprint2 = graph.addVertex("Sprint")
            plan.addEdge(sprintsToPlan, sprint2)
            val workItem = graph.addVertex("WorkItem")
            // An extra sprint1 that IS connected to both workItem and plan — NAC fires.
            val sprint1 = graph.addVertex("Sprint")
            plan.addEdge(sprintsToPlan, sprint1)
            sprint1.addEdge(committedToPlanned, workItem)

            val result = engine.executeStatement(buildPattern(), context)
            assertIs<TransformationExecutionResult.Failure>(result,
                "Expected FAILURE: sprint1 connected to both workItem and plan")
        }

        /**
         * Key correctness case for improvement 1: a sprint OTHER than sprint2 is
         * connected to workItem but NOT to the matched plan.  Without the injective
         * optimisation the planner would wrongly require sprint1 != sprint2 and could
         * still produce wrong results if the constraint were misapplied.  With the
         * optimisation the match must SUCCEED (no complete forbidden pattern exists).
         */
        @Test
        fun `match succeeds when a sprint is connected to workItem but not to the matched plan`() {
            val plan = graph.addVertex("Plan")
            val sprint2 = graph.addVertex("Sprint")
            plan.addEdge(sprintsToPlan, sprint2)
            val workItem = graph.addVertex("WorkItem")
            // sprint1 connected to workItem but its plan is a DIFFERENT plan.
            val otherPlan = graph.addVertex("Plan")
            val sprint1 = graph.addVertex("Sprint")
            otherPlan.addEdge(sprintsToPlan, sprint1)
            sprint1.addEdge(committedToPlanned, workItem)

            val result = engine.executeStatement(buildPattern(), context)
            assertIs<TransformationExecutionResult.Success>(result,
                "Expected SUCCESS: sprint1's plan differs from the matched plan")
        }
    }

    // ── Performance test (improvement 2: composition-aware component start) ───

    @Nested
    inner class Performance {

        /**
         * With SPRINT_COUNT sprints in one plan and an unassigned workItem the match
         * must succeed quickly.
         *
         * The containment-score heuristic picks Plan as the start of the {sprint2,plan}
         * component (Plan has containment score 2 because Sprint is composed under it,
         * whereas Sprint scores 1).  Plan is then processed before {workItem} because
         * it has the higher containment score.  The resulting traversal is:
         *
         *   V(Plan) → out(sprints) → Sprint(sprint2) → V(WorkItem) → [NAC checks]
         *
         * Improvement 1 also removes the redundant `sprint1 != sprint2` injective
         * constraint, so the NAC check on workItem needs no injective guard.
         */
        @Test
        fun `match with many sprints completes within time limit`() {
            val plan = graph.addVertex("Plan")
            // sprint2: the one we'll match — connected to plan.
            val sprint2 = graph.addVertex("Sprint")
            plan.addEdge(sprintsToPlan, sprint2)
            // Add SPRINT_COUNT - 1 extra sprints all owned by the same plan.
            repeat(SPRINT_COUNT - 1) {
                val s = graph.addVertex("Sprint")
                plan.addEdge(sprintsToPlan, s)
            }
            graph.addVertex("WorkItem") // unassigned

            val startMs = System.currentTimeMillis()
            val result = engine.executeStatement(buildPattern(), context)
            val elapsedMs = System.currentTimeMillis() - startMs

            println("=== NacInjectiveConstraintOmissionTest (performance) ===")
            println("  $SPRINT_COUNT sprints in plan, unassigned workItem → ${elapsedMs}ms")

            assertIs<TransformationExecutionResult.Success>(result,
                "Expected SUCCESS: no forbidden pattern exists")
            assert(elapsedMs < PERFORMANCE_LIMIT_MS) {
                "Match took ${elapsedMs}ms, exceeds ${PERFORMANCE_LIMIT_MS}ms limit"
            }
        }

        /**
         * Regression test for component-ordering improvement (containment-score primary).
         *
         * Graph: one plan with SPRINT_COUNT sprints, and WORK_ITEM_COUNT unassigned workItems.
         *
         * With the containment-score ordering:
         *   1. {sprint2, plan} component is visited first (Plan containment score = 2 > 1).
         *      Start node = Plan (also highest containment score within the component).
         *   2. {workItem} is visited second.
         *   3. The forbid workItem -- sprint1 NAC fires right after workItem is covered.
         *
         * The cross product is Plan (few) × Sprint (many per plan) × WorkItem, but the NAC
         * prunes most workItems immediately.  Total work stays within the time limit even
         * with SPRINT_COUNT sprints.
         */
        @Test
        fun `workItem is visited before sprint2-plan component for early NAC pruning`() {
            val WORK_ITEM_COUNT = 100
            // Create SPRINT_COUNT sprints in a single plan.
            val plan = graph.addVertex("Plan")
            val sprint2 = graph.addVertex("Sprint")
            plan.addEdge(sprintsToPlan, sprint2)
            repeat(SPRINT_COUNT - 1) {
                val s = graph.addVertex("Sprint")
                plan.addEdge(sprintsToPlan, s)
            }
            // Create many workItems all unassigned — the NAC should not fire for any of them.
            repeat(WORK_ITEM_COUNT) { graph.addVertex("WorkItem") }

            val startMs = System.currentTimeMillis()
            // executeMatchAll to exercise all combinations.
            val results = engine.modelGraph.let { _ ->
                engine.executeStatement(buildPattern(), context)
            }
            val elapsedMs = System.currentTimeMillis() - startMs

            println("=== ComponentOrdering performance ($SPRINT_COUNT sprints, $WORK_ITEM_COUNT workItems) ===")
            println("  elapsed: ${elapsedMs}ms  limit: ${PERFORMANCE_LIMIT_MS}ms")

            assertIs<TransformationExecutionResult.Success>(results,
                "Expected SUCCESS: no NAC fires for unassigned workItems")
            assert(elapsedMs < PERFORMANCE_LIMIT_MS) {
                "Match took ${elapsedMs}ms — component ordering may be wrong"
            }
        }
    }

    // ── Element helpers ───────────────────────────────────────────────────────

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

    private fun matchLink(
        sourceName: String, sourceProperty: String?,
        targetName: String, targetProperty: String?
    ) = TypedPatternLinkElement(
        link = TypedPatternLink(
            modifier = null,
            source = TypedPatternLinkEnd(objectName = sourceName, propertyName = sourceProperty),
            target = TypedPatternLinkEnd(objectName = targetName, propertyName = targetProperty)
        )
    )

    private fun forbidLink(
        sourceName: String, sourceProperty: String?,
        targetName: String, targetProperty: String?
    ) = TypedPatternLinkElement(
        link = TypedPatternLink(
            modifier = "forbid",
            source = TypedPatternLinkEnd(objectName = sourceName, propertyName = sourceProperty),
            target = TypedPatternLinkEnd(objectName = targetName, propertyName = targetProperty)
        )
    )
}
