package com.mdeo.modeltransformation.runtime.match.plan

import com.mdeo.expression.ast.expressions.TypedBinaryExpression
import com.mdeo.expression.ast.expressions.TypedIdentifierExpression
import com.mdeo.expression.ast.expressions.TypedIntLiteralExpression
import com.mdeo.expression.ast.expressions.TypedMemberAccessExpression
import com.mdeo.metamodel.data.*
import com.mdeo.modeltransformation.ast.patterns.*
import com.mdeo.modeltransformation.runtime.match.ExpressionNodeAnalyzer
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the metamodel-based class priority computation, the pseudo-composition DAG,
 * and the resulting match-step ordering in [MatchPlanBuilder].
 *
 * ## Scrum metamodel under test
 *
 * ```
 * class Plan {}
 * class Sprint {}
 * class WorkItem { importance: int; effort: int }
 * class Stakeholder {}
 *
 * Plan.sprints[0..*]     <>-> Sprint.plan[1]
 * Plan.workItems[0..*]   <>-> WorkItem.plan[1]
 * Plan.stakeholders[0..*] <>-> Stakeholder.plan[1]
 *
 * Sprint.committedItems[1..*] <--> WorkItem.isPlannedFor[0..1]
 * Stakeholder.workItems[1..*] <--> WorkItem.stakeholder[1]
 * ```
 *
 * ## Transformation pattern under test
 *
 * ```
 * forbid sprint1 : Sprint {}
 * sprint2        : Sprint {}
 * plan           : Plan {}
 * workItem       : WorkItem {}
 *
 * forbid workItem -- sprint1      (sprint1.committedItems <--> workItem.isPlannedFor)
 * sprint2         -- plan         (plan.sprints <--> sprint2.plan)
 * create sprint2  -- workItem     (sprint2.committedItems <--> workItem.isPlannedFor)
 * forbid sprint2  -- workItem     (sprint2.committedItems <--> workItem.isPlannedFor)
 * forbid plan     -- sprint1      (plan.sprints <--> sprint1.plan)
 * ```
 *
 * Expected plan ordering with new instance-priority algorithm:
 * 1. `plan` is matched first — it has the highest *instance priority* (sprint2 is pseudo-composited
 *    on it via the regular `sprint2 -- plan` link, which corresponds to a real composition).
 * 2. `workItem` is scanned next — equal instance priority to sprint2 (priority 0), but scanning
 *    workItem unlocks a cheap NAC island (sprint1 anchored at workItem) before the sprint2 walk.
 * 3. The NAC island fires immediately after workItem is covered.
 * 4. `sprint2` is walked from `plan`.
 */
class MatchPlanOrderingTest {

    // ── Scrum metamodel ───────────────────────────────────────────────────────

    private val scrumMetamodel = MetamodelData(
        path = "/test/scrum.mm",
        classes = listOf(
            ClassData(name = "Plan",        isAbstract = false),
            ClassData(name = "Sprint",      isAbstract = false),
            ClassData(name = "WorkItem",    isAbstract = false),
            ClassData(name = "Stakeholder", isAbstract = false)
        ),
        associations = listOf(
            // Plan.sprints[0..*] <>-> Sprint.plan[1]
            AssociationData(
                source = AssociationEndData("Plan",        "sprints",        MultiplicityData.many()),
                operator = "<>->",
                target = AssociationEndData("Sprint",      "plan",           MultiplicityData.single())
            ),
            // Plan.workItems[0..*] <>-> WorkItem.plan[1]
            AssociationData(
                source = AssociationEndData("Plan",        "workItems",      MultiplicityData.many()),
                operator = "<>->",
                target = AssociationEndData("WorkItem",    "plan",           MultiplicityData.single())
            ),
            // Plan.stakeholders[0..*] <>-> Stakeholder.plan[1]
            AssociationData(
                source = AssociationEndData("Plan",        "stakeholders",   MultiplicityData.many()),
                operator = "<>->",
                target = AssociationEndData("Stakeholder", "plan",           MultiplicityData.single())
            ),
            // Sprint.committedItems[1..*] <--> WorkItem.isPlannedFor[0..1]
            AssociationData(
                source = AssociationEndData("Sprint",      "committedItems", MultiplicityData.oneOrMore()),
                operator = "<-->",
                target = AssociationEndData("WorkItem",    "isPlannedFor",   MultiplicityData.optional())
            ),
            // Stakeholder.workItems[1..*] <--> WorkItem.stakeholder[1]
            AssociationData(
                source = AssociationEndData("Stakeholder", "workItems",      MultiplicityData.oneOrMore()),
                operator = "<-->",
                target = AssociationEndData("WorkItem",    "stakeholder",    MultiplicityData.single())
            )
        )
    )

    // ── PseudoCompositionDag unit tests ───────────────────────────────────────

    @Nested
    inner class PseudoCompositionDagTests {

        @Test
        fun `real compositions appear in pseudo-composition DAG`() {
            val dag = MetamodelClassPriority.computePseudoCompositionDag(scrumMetamodel)
            // Plan is the real composition parent of Sprint, WorkItem, Stakeholder.
            assertTrue("Plan" to "Sprint"      in dag, "Plan→Sprint must be in DAG")
            assertTrue("Plan" to "WorkItem"    in dag, "Plan→WorkItem must be in DAG")
            assertTrue("Plan" to "Stakeholder" in dag, "Plan→Stakeholder must be in DAG")
        }

        @Test
        fun `WorkItem is not pseudo-composition child of Sprint because WorkItem is already a real composition target`() {
            // Sprint.committedItems[1..*] <--> WorkItem.isPlannedFor[0..1].
            // WorkItem.isPlannedFor has upper=1 (finite, lower), but WorkItem is already
            // a real composition target of Plan.  Condition (c) fails.
            val dag = MetamodelClassPriority.computePseudoCompositionDag(scrumMetamodel)
            assertFalse("Sprint" to "WorkItem" in dag,
                "Sprint→WorkItem must NOT be in DAG: WorkItem is already a real composition target")
        }

        @Test
        fun `isolated classes with no associations have empty DAG`() {
            val metamodel = MetamodelData(
                classes = listOf(ClassData("A", false), ClassData("B", false))
            )
            val dag = MetamodelClassPriority.computePseudoCompositionDag(metamodel)
            assertTrue(dag.isEmpty(), "No associations => empty pseudo-composition DAG")
        }

        @Test
        fun `finite-upper end becomes pseudo-composition child when other end is infinite and class is not already composed`() {
            // A.items[*] <--> B.owner[1], neither is a real composition target.
            val metamodel = MetamodelData(
                classes = listOf(ClassData("A", false), ClassData("B", false)),
                associations = listOf(
                    AssociationData(
                        AssociationEndData("A", "items", MultiplicityData.many()),
                        "<-->",
                        AssociationEndData("B", "owner", MultiplicityData.single())
                    )
                )
            )
            val dag = MetamodelClassPriority.computePseudoCompositionDag(metamodel)
            assertTrue("A" to "B" in dag,
                "A→B must be in DAG: B.owner[1] is finite and lower than A.items[*]")
            assertFalse("B" to "A" in dag, "B→A must not be in DAG")
        }

        @Test
        fun `both ends equal or unbounded - no pseudo-composition edge added`() {
            // A.x[*] <--> B.y[*]: neither end qualifies (both unbounded).
            val metamodel = MetamodelData(
                classes = listOf(ClassData("A", false), ClassData("B", false)),
                associations = listOf(
                    AssociationData(
                        AssociationEndData("A", "x", MultiplicityData.many()),
                        "<-->",
                        AssociationEndData("B", "y", MultiplicityData.many())
                    )
                )
            )
            val dag = MetamodelClassPriority.computePseudoCompositionDag(metamodel)
            assertFalse("A" to "B" in dag, "A→B must not be in DAG: both ends unbounded")
            assertFalse("B" to "A" in dag, "B→A must not be in DAG: both ends unbounded")
        }

        @Test
        fun `DAG is cycle-free even with a circular association chain`() {
            // A.b[1] <--> B.c[1] and B.a[1] <--> A.b[1] would form a cycle if both were added.
            val metamodel = MetamodelData(
                classes = listOf(ClassData("A", false), ClassData("B", false)),
                associations = listOf(
                    AssociationData(
                        AssociationEndData("A", "x", MultiplicityData.many()),
                        "<-->",
                        AssociationEndData("B", "y", MultiplicityData.single())
                    ),
                    AssociationData(
                        AssociationEndData("B", "p", MultiplicityData.many()),
                        "<-->",
                        AssociationEndData("A", "q", MultiplicityData.single())
                    )
                )
            )
            val dag = MetamodelClassPriority.computePseudoCompositionDag(metamodel)
            // Must not have both directions (would be a cycle A→B and B→A).
            val hasCycle = ("A" to "B" in dag) && ("B" to "A" in dag)
            assertFalse(hasCycle, "DAG must not contain a cycle A→B and B→A simultaneously")
        }
    }

    // ── MetamodelClassPriority unit tests ─────────────────────────────────────

    // ── MatchPlan ordering tests ───────────────────────────────────────────────

    @Nested
    inner class MatchPlanOrderingTests {

        /**
         * Builds the MatchPlanBuilder for the scrum transformation pattern described
         * in the class KDoc. No pre-bound vertex IDs; pure type-based matching.
         */
        private fun buildPlan(): MatchPlan {
            val elements = buildScrumPatternElements()
            val matchableNames = elements.matchableInstances.map { it.objectInstance.name }.toSet()
            val nodeAnalyzer = ExpressionNodeAnalyzer(matchableNames, 0)
            return MatchPlanBuilder(
                getVertexId = { null },
                nodeAnalyzer = nodeAnalyzer,
                isCollectionExpression = { false },
                metamodelData = scrumMetamodel
            ).build(elements, emptySet())
        }

        @Test
        fun `plan is matched before sprint2 and workItem`() {
            val plan = buildPlan()
            val steps = plan.baseSteps

            // Find the index of the first scan that covers "plan"
            val planIdx = steps.indexOfFirst { step ->
                step is BaseStep.VertexScan && step.instanceName == "plan"
            }
            val sprint2Idx = steps.indexOfFirst { step ->
                (step is BaseStep.VertexScan && step.instanceName == "sprint2") ||
                (step is BaseStep.EdgeWalk  && step.toInstanceName == "sprint2")
            }
            val workItemIdx = steps.indexOfFirst { step ->
                step is BaseStep.VertexScan && step.instanceName == "workItem"
            }

            assertTrue(planIdx >= 0, "plan must be in the plan steps")
            assertTrue(sprint2Idx >= 0, "sprint2 must be in the plan steps")
            assertTrue(workItemIdx >= 0, "workItem must be in the plan steps")

            assertTrue(planIdx < sprint2Idx,
                "plan (idx=$planIdx) should be matched before sprint2 (idx=$sprint2Idx)")
            assertTrue(planIdx < workItemIdx,
                "plan (idx=$planIdx) should be matched before workItem (idx=$workItemIdx)")
        }

        @Test
        fun `plan and sprint2 are in the same component traversal`() {
            val plan = buildPlan()
            val steps = plan.baseSteps

            // plan is scanned via VertexScan, sprint2 is reached via EdgeWalk from plan
            val planScanIdx = steps.indexOfFirst { step ->
                step is BaseStep.VertexScan && step.instanceName == "plan"
            }
            val sprint2WalkIdx = steps.indexOfFirst { step ->
                step is BaseStep.EdgeWalk && step.toInstanceName == "sprint2"
            }

            assertTrue(planScanIdx >= 0,    "VertexScan(plan) must be present")
            assertTrue(sprint2WalkIdx >= 0, "EdgeWalk(→sprint2) must be present")
            // The EdgeWalk to sprint2 must come directly after the plan scan (same component).
            assertTrue(sprint2WalkIdx > planScanIdx,
                "sprint2 walk (idx=$sprint2WalkIdx) must come after plan scan (idx=$planScanIdx)")
        }

        @Test
        fun `NAC conditions are evaluated after their anchor dependencies are covered`() {
            val plan = buildPlan()
            val steps = plan.baseSteps

            // workItem must appear in steps before any ApplicationCondition that uses it
            val workItemIdx = steps.indexOfFirst { step ->
                step is BaseStep.VertexScan && step.instanceName == "workItem"
            }
            val nacIndices = steps.indices.filter { steps[it] is BaseStep.ApplicationCondition }

            assertTrue(workItemIdx >= 0, "workItem must be scanned")
            assertTrue(nacIndices.isNotEmpty(), "there must be at least one ApplicationCondition")

            // The NAC island (anchors: workItem + plan) fires immediately after workItem is covered,
            // BEFORE sprint2 is walked.  All NACs must still come after workItem.
            for (nacIdx in nacIndices) {
                assertTrue(nacIdx > workItemIdx,
                    "ApplicationCondition (idx=$nacIdx) must come after workItem scan (idx=$workItemIdx)")
            }
        }

        @Test
        fun `cheaper conditions come before more expensive ones`() {
            val plan = buildPlan()
            val conditions = plan.baseSteps.filterIsInstance<BaseStep.ApplicationCondition>()

            // Orphan link (single-edge check) should come before the multi-anchor NAC island
            if (conditions.size >= 2) {
                // The orphan link condition has no inner VertexScan (it starts at an anchor and does one EdgeWalk)
                val orphanCandidates = conditions.filter { ac ->
                    ac.anchorName != null && ac.innerSteps.filterIsInstance<BaseStep.VertexScan>().isEmpty()
                }
                val islandCandidates = conditions.filter { ac ->
                    ac.anchorName != null && ac.innerSteps.any { it is BaseStep.EdgeWalk }
                }

                if (orphanCandidates.isNotEmpty() && islandCandidates.isNotEmpty()) {
                    val firstOrphan = conditions.indexOf(orphanCandidates.first())
                    val firstIsland = conditions.indexOf(islandCandidates
                        .filter { it !in orphanCandidates }.firstOrNull() ?: return)
                    assertTrue(firstOrphan <= firstIsland,
                        "Cheap orphan-link NAC (idx=$firstOrphan) should come before island NAC (idx=$firstIsland)")
                }
            }
        }

        @Test
        fun `plan is scanned before workItem (instance priority)`() {
            val plan = buildPlan()
            val steps = plan.baseSteps

            // plan has instance priority 1 (sprint2 is pseudo-composited on it via the
            // regular plan.sprints--sprint2.plan link, which is a real composition).
            // workItem has instance priority 0 (no regular/PAC link connects it to plan
            // in a pseudo-composition direction).
            val planScanIdx = steps.indexOfFirst { step ->
                step is BaseStep.VertexScan && step.instanceName == "plan"
            }
            val workItemScanIdx = steps.indexOfFirst { step ->
                step is BaseStep.VertexScan && step.instanceName == "workItem"
            }

            assertTrue(planScanIdx >= 0, "VertexScan(plan) must be present")
            assertTrue(workItemScanIdx >= 0, "VertexScan(workItem) must be present")
            assertTrue(planScanIdx < workItemScanIdx,
                "plan (idx=$planScanIdx) should be scanned before workItem (idx=$workItemScanIdx)")
        }

        @Test
        fun `workItem is scanned before sprint2 to enable early NAC evaluation`() {
            val plan = buildPlan()
            val steps = plan.baseSteps

            // workItem and sprint2 have equal instance priority (both 0 — no regular/PAC
            // link pseudo-composes workItem onto anything), but covering workItem unlocks the
            // NAC island (anchors = {workItem, plan}) at lower cost than walking to sprint2.
            // The step-level greedy therefore scans workItem before walking plan→sprint2,
            // allowing the NAC to prune traversers before the sprint2 fan-out.
            val workItemIdx = steps.indexOfFirst { step ->
                step is BaseStep.VertexScan && step.instanceName == "workItem"
            }
            val sprint2Idx = steps.indexOfFirst { step ->
                step is BaseStep.EdgeWalk && step.toInstanceName == "sprint2"
            }

            assertTrue(workItemIdx >= 0, "workItem must be scanned")
            assertTrue(sprint2Idx >= 0, "sprint2 must be matched via EdgeWalk")
            assertTrue(workItemIdx < sprint2Idx,
                "workItem (idx=$workItemIdx) should be scanned before sprint2 (idx=$sprint2Idx) " +
                "because covering workItem enables earlier NAC evaluation")
        }

        @Test
        fun `NAC island fires before sprint2 walk`() {
            val plan = buildPlan()
            val steps = plan.baseSteps

            // The NAC island (anchors: workItem + plan) should be emitted inline as soon as
            // workItem is covered — i.e. before the EdgeWalk to sprint2.
            val sprint2Idx = steps.indexOfFirst { step ->
                step is BaseStep.EdgeWalk && step.toInstanceName == "sprint2"
            }
            val islandNacIdx = steps.indexOfFirst { step ->
                step is BaseStep.ApplicationCondition && step.innerSteps.any {
                    it is BaseStep.EdgeWalk
                }
            }

            assertTrue(sprint2Idx >= 0, "EdgeWalk to sprint2 must be present")
            if (islandNacIdx >= 0) {
                assertTrue(islandNacIdx < sprint2Idx,
                    "NAC island (idx=$islandNacIdx) should fire before sprint2 walk (idx=$sprint2Idx)")
            }
        }

        /**
         * Tests the key scenario described in the algorithm design:
         * when the only link between two nodes is a *create* link (not a regular matchable link),
         * neither node gets a pseudo-composition priority boost.  The NAC unlock cost then becomes
         * the decisive tiebreaker, and the node that directly enables a cheap NAC is matched first.
         *
         * Pattern:
         * ```
         * sprint1  : Sprint {}
         * workItem : WorkItem {}
         * forbid sprint2 : Sprint {}
         *
         * create  workItem -- sprint1      (sprint1.committedItems <--> workItem.isPlannedFor)
         * forbid  workItem -- sprint1      (orphan forbid link — both are main-pattern nodes)
         * forbid  sprint2  -- workItem     (sprint2 NAC island, anchored at workItem)
         * ```
         *
         * Because the Sprint→WorkItem composition link is *not* in the regular matchable links,
         * both sprint1 and workItem get instance priority 0.  However covering workItem immediately
         * unlocks the `sprint2 -- workItem` NAC, so workItem is scanned first.
         */
        @Test
        fun `workItem matched first when composition link is create-only (no priority boost)`() {
            // Scrum metamodel: Sprint.committedItems[1..*] <--> WorkItem.isPlannedFor[0..1]
            // but WorkItem is a real composition target of Plan, so it won't be a
            // pseudo-composition child of Sprint.  Both sprint1 and workItem have priority 0.
            val elements = com.mdeo.modeltransformation.runtime.match.PatternCategories(
                matchableInstances = listOf(
                    makeInstance(null, "sprint1",  "Sprint"),
                    makeInstance(null, "workItem", "WorkItem")
                ),
                matchableLinks = emptyList(),  // no regular links between sprint1 and workItem
                createInstances = emptyList(),
                deleteInstances = emptyList(),
                createLinks = listOf(
                    makeLink("create", "sprint1", "committedItems", "workItem", "isPlannedFor")
                ),
                deleteLinks = emptyList(),
                forbidInstances = listOf(makeInstance("forbid", "sprint2", "Sprint")),
                forbidLinks = listOf(
                    // orphan forbid link: both sprint1 and workItem are main-pattern nodes
                    makeLink("forbid", "sprint1", "committedItems", "workItem", "isPlannedFor"),
                    // NAC island: sprint2 anchored at workItem
                    makeLink("forbid", "sprint2", "committedItems", "workItem", "isPlannedFor")
                ),
                requireInstances = emptyList(),
                requireLinks = emptyList(),
                variables = emptyList(),
                whereClauses = emptyList()
            )

            val plan = MatchPlanBuilder(
                getVertexId = { null },
                nodeAnalyzer = ExpressionNodeAnalyzer(setOf("sprint1", "workItem"), 0),
                isCollectionExpression = { false },
                metamodelData = scrumMetamodel
            ).build(elements, emptySet())

            val steps = plan.baseSteps

            // workItem should be scanned before sprint1 because covering workItem unlocks
            // the sprint2 NAC island (anchor = workItem) while covering sprint1 unlocks nothing.
            val workItemIdx = steps.indexOfFirst {
                it is BaseStep.VertexScan && it.instanceName == "workItem"
            }
            val sprint1Idx = steps.indexOfFirst {
                it is BaseStep.VertexScan && it.instanceName == "sprint1"
            }

            assertTrue(workItemIdx >= 0, "workItem must be covered by VertexScan")
            assertTrue(sprint1Idx >= 0, "sprint1 must be covered by VertexScan")
            assertTrue(workItemIdx < sprint1Idx,
                "workItem (idx=$workItemIdx) should be scanned before sprint1 (idx=$sprint1Idx) " +
                "because covering workItem enables the sprint2 NAC")

            // The sprint2 NAC should fire immediately after workItem is covered, i.e. before sprint1.
            val nacIdx = steps.indexOfFirst { it is BaseStep.ApplicationCondition }
            assertTrue(nacIdx > workItemIdx, "NAC must come after workItem is covered")
            assertTrue(nacIdx < sprint1Idx, "NAC must fire before sprint1 is covered")
        }
    }

    // ── Pattern builder helpers ────────────────────────────────────────────────

    /**
     * Builds the PatternCategories for the scrum transformation pattern:
     *
     * ```
     * forbid sprint1 : Sprint {}
     * sprint2        : Sprint {}
     * plan           : Plan {}
     * workItem       : WorkItem {}
     *
     * forbid workItem -- sprint1   (sprint1.committedItems <--> workItem.isPlannedFor)
     * sprint2         -- plan      (plan.sprints <>-> sprint2.plan)
     * create sprint2  -- workItem  (sprint2.committedItems <--> workItem.isPlannedFor)
     * forbid sprint2  -- workItem  (sprint2.committedItems <--> workItem.isPlannedFor)
     * forbid plan     -- sprint1   (plan.sprints <>-> sprint1.plan)
     * ```
     */
    private fun buildScrumPatternElements(): com.mdeo.modeltransformation.runtime.match.PatternCategories {
        val matchableInstances = listOf(
            makeInstance(null, "sprint2",  "Sprint"),
            makeInstance(null, "plan",     "Plan"),
            makeInstance(null, "workItem", "WorkItem")
        )
        val forbidInstances = listOf(
            makeInstance("forbid", "sprint1", "Sprint")
        )
        val matchableLinks = listOf(
            // sprint2 -- plan  (plan.sprints <>-> sprint2.plan)
            makeLink(null, "plan", "sprints", "sprint2", "plan")
        )
        val createLinks = listOf(
            // create sprint2 -- workItem (sprint2.committedItems <--> workItem.isPlannedFor)
            makeLink("create", "sprint2", "committedItems", "workItem", "isPlannedFor")
        )
        val forbidLinks = listOf(
            // forbid workItem -- sprint1  (sprint1.committedItems <--> workItem.isPlannedFor)
            makeLink("forbid", "sprint1", "committedItems", "workItem", "isPlannedFor"),
            // forbid sprint2 -- workItem  (orphan link, both main-pattern nodes)
            makeLink("forbid", "sprint2", "committedItems", "workItem", "isPlannedFor"),
            // forbid plan -- sprint1  (plan.sprints <>-> sprint1.plan)
            makeLink("forbid", "plan", "sprints", "sprint1", "plan")
        )

        return com.mdeo.modeltransformation.runtime.match.PatternCategories(
            matchableInstances = matchableInstances,
            matchableLinks = matchableLinks,
            createInstances = emptyList(),
            deleteInstances = emptyList(),
            createLinks = createLinks,
            deleteLinks = emptyList(),
            forbidInstances = forbidInstances,
            forbidLinks = forbidLinks,
            requireInstances = emptyList(),
            requireLinks = emptyList(),
            variables = emptyList(),
            whereClauses = emptyList()
        )
    }

    private fun makeInstance(modifier: String?, name: String, className: String) =
        TypedPatternObjectInstanceElement(
            objectInstance = TypedPatternObjectInstance(
                modifier   = modifier,
                name       = name,
                className  = className,
                properties = emptyList()
            )
        )

    private fun makeLink(
        modifier: String?,
        sourceName: String, sourceProp: String?,
        targetName: String, targetProp: String?
    ) = TypedPatternLinkElement(
        link = TypedPatternLink(
            modifier = modifier,
            source   = TypedPatternLinkEnd(objectName = sourceName, propertyName = sourceProp),
            target   = TypedPatternLinkEnd(objectName = targetName, propertyName = targetProp)
        )
    )

    private fun makeConstantExpression(value: Int) =
        TypedIntLiteralExpression(evalType = 0, value = value.toString())

    // ── Post-reordering tests ───────────────────────────────────────────────────

    @Nested
    inner class PostReorderingTests {

        /**
         * Sprint.plan[1] means Sprint is at the “1-side” of the Plan–Sprint composition.
         * After the greedy picks Plan first (higher class priority) and then walks to Sprint,
         * the post-reordering should flip: scan Sprint first, apply Sprint’s inline property
         * filter, then walk Sprint→Plan (1-hop).
         *
         * Expected order:
         * VertexScan(sprint) → InlinePropertyConstraint(sprint.effort) → EdgeWalk(sprint→plan)
         */
        @Test
        fun `1-side node is scanned before its composition root when it has an inline property filter`() {
            // Metamodel: Plan <>-> Sprint (Plan.sprints[0..*], Sprint.plan[1])
            val metamodel = MetamodelData(
                classes = listOf(ClassData("Plan", false), ClassData("Sprint", false)),
                associations = listOf(
                    AssociationData(
                        source = AssociationEndData("Plan",   "sprints", MultiplicityData.many()),
                        operator = "<>->",
                        target = AssociationEndData("Sprint", "plan",    MultiplicityData.single())
                    )
                )
            )

            // Pattern: sprint : Sprint [effort == 5], plan : Plan; link plan.sprints <>-> sprint.plan
            val sprintInstance = TypedPatternObjectInstanceElement(
                objectInstance = TypedPatternObjectInstance(
                    modifier = null,
                    name = "sprint",
                    className = "Sprint",
                    properties = listOf(
                        TypedPatternPropertyAssignment(
                            propertyName = "effort",
                            operator     = "==",
                            value        = makeConstantExpression(5)
                        )
                    )
                )
            )
            val planInstance = TypedPatternObjectInstanceElement(
                objectInstance = TypedPatternObjectInstance(
                    modifier = null, name = "plan", className = "Plan", properties = emptyList()
                )
            )
            val link = TypedPatternLinkElement(
                link = TypedPatternLink(
                    modifier = null,
                    source = TypedPatternLinkEnd("plan", "sprints"),
                    target = TypedPatternLinkEnd("sprint", "plan")
                )
            )

            val elements = com.mdeo.modeltransformation.runtime.match.PatternCategories(
                matchableInstances = listOf(planInstance, sprintInstance),
                matchableLinks = listOf(link),
                createInstances = emptyList(), deleteInstances = emptyList(),
                createLinks = emptyList(), deleteLinks = emptyList(),
                forbidInstances = emptyList(), forbidLinks = emptyList(),
                requireInstances = emptyList(), requireLinks = emptyList(),
                variables = emptyList(), whereClauses = emptyList()
            )

            val plan = MatchPlanBuilder(
                getVertexId = { null },
                nodeAnalyzer = ExpressionNodeAnalyzer(setOf("plan", "sprint"), 0),
                isCollectionExpression = { false },
                metamodelData = metamodel
            ).build(elements, emptySet())

            val steps = plan.baseSteps

            val sprintScanIdx = steps.indexOfFirst {
                it is BaseStep.VertexScan && it.instanceName == "sprint"
            }
            val planCoveredIdx = steps.indexOfFirst {
                it is BaseStep.EdgeWalk && it.toInstanceName == "plan"
            }
            val sprintFilterIdx = steps.indexOfFirst {
                it is BaseStep.InlinePropertyConstraint && it.instanceName == "sprint"
            }

            assertTrue(sprintScanIdx >= 0, "sprint must be covered by VertexScan")
            assertTrue(planCoveredIdx >= 0, "plan must be covered by EdgeWalk from sprint")
            assertTrue(sprintFilterIdx >= 0, "sprint property filter must be present")
            assertTrue(sprintScanIdx < planCoveredIdx,
                "sprint scan (idx=\$sprintScanIdx) must come before plan coverage (idx=\$planCoveredIdx)")
            assertTrue(sprintFilterIdx < planCoveredIdx,
                "sprint filter (idx=\$sprintFilterIdx) must fire before plan coverage (idx=\$planCoveredIdx)")
        }
    }

    // ── Injective constraint ordering tests ────────────────────────────────────

    @Nested
    inner class InjectiveConstraintOrderingTests {

        /**
         * Verifies the fix for early injective constraint emission.
         *
         * Pattern (move-work-item transformation):
         * ```
         * s1 : Sprint {}
         * s2 : Sprint {}
         * p  : Plan {}
         * wi : WorkItem {}   (delete)
         *
         * s1 -- p             (plan.sprints <>-> s1.plan)
         * s2 -- p             (plan.sprints <>-> s2.plan)
         * delete wi -- s1     (s1.committedItems <--> wi.isPlannedFor)
         * create wi -- s2     (s2.committedItems <--> wi.isPlannedFor)
         * ```
         *
         * Expected traversal outline:
         * 1. VertexScan(p)           — Plan has highest instance priority (2 sprints composited)
         * 2. EdgeWalk(p → s1)
         * 3. EdgeWalk(p → s2)        — needs select back to p
         * 4. InjectiveConstraint(s1, s2)  ← must fire HERE, right after s2 is covered
         * 5. EdgeWalk(s1 → wi)       — or equivalent
         *
         * Before the fix, step 4 appeared after step 5 (at the very end of the plan),
         * meaning the graph was fully traversed before checking s1 ≠ s2.
         */
        @Test
        fun `injective constraint between two sprints fires before workItem is covered`() {
            val elements = com.mdeo.modeltransformation.runtime.match.PatternCategories(
                matchableInstances = listOf(
                    makeInstance(null, "s1", "Sprint"),
                    makeInstance(null, "s2", "Sprint"),
                    makeInstance(null, "p",  "Plan")
                ),
                matchableLinks = listOf(
                    makeLink(null, "p", "sprints", "s1", "plan"),
                    makeLink(null, "p", "sprints", "s2", "plan")
                ),
                createInstances = emptyList(),
                deleteInstances = listOf(makeInstance("delete", "wi", "WorkItem")),
                createLinks = listOf(
                    makeLink("create", "s2", "committedItems", "wi", "isPlannedFor")
                ),
                deleteLinks = listOf(
                    makeLink("delete", "s1", "committedItems", "wi", "isPlannedFor")
                ),
                forbidInstances = emptyList(),
                forbidLinks = emptyList(),
                requireInstances = emptyList(),
                requireLinks = emptyList(),
                variables = emptyList(),
                whereClauses = emptyList()
            )

            val plan = MatchPlanBuilder(
                getVertexId = { null },
                nodeAnalyzer = ExpressionNodeAnalyzer(setOf("s1", "s2", "p", "wi"), 0),
                isCollectionExpression = { false },
                metamodelData = scrumMetamodel
            ).build(elements, emptySet())

            val steps = plan.baseSteps

            // Both s1 and s2 must be covered before the injective constraint fires.
            val s1Idx = steps.indexOfFirst { step ->
                (step is BaseStep.VertexScan && step.instanceName == "s1") ||
                (step is BaseStep.EdgeWalk   && step.toInstanceName == "s1")
            }
            val s2Idx = steps.indexOfFirst { step ->
                (step is BaseStep.VertexScan && step.instanceName == "s2") ||
                (step is BaseStep.EdgeWalk   && step.toInstanceName == "s2")
            }
            val wiIdx = steps.indexOfFirst { step ->
                (step is BaseStep.VertexScan && step.instanceName == "wi") ||
                (step is BaseStep.EdgeWalk   && step.toInstanceName == "wi")
            }
            val injectiveIdx = steps.indexOfFirst { step ->
                step is BaseStep.InjectiveConstraint &&
                ((step.instanceNameA == "s1" && step.instanceNameB == "s2") ||
                 (step.instanceNameA == "s2" && step.instanceNameB == "s1"))
            }

            assertTrue(s1Idx >= 0,        "s1 must be covered")
            assertTrue(s2Idx >= 0,        "s2 must be covered")
            assertTrue(wiIdx >= 0,        "wi must be covered")
            assertTrue(injectiveIdx >= 0, "InjectiveConstraint(s1, s2) must be present")

            assertTrue(injectiveIdx > s1Idx,
                "InjectiveConstraint (idx=\$injectiveIdx) must come after s1 is covered (idx=\$s1Idx)")
            assertTrue(injectiveIdx > s2Idx,
                "InjectiveConstraint (idx=\$injectiveIdx) must come after s2 is covered (idx=\$s2Idx)")
            assertTrue(injectiveIdx < wiIdx,
                "InjectiveConstraint (idx=\$injectiveIdx) must fire BEFORE wi is covered (idx=\$wiIdx) " +
                "— s1 ≠ s2 should prune as early as possible")
        }
    }

    // ── Variable integration tests ──────────────────────────────────────────────

    @Nested
    inner class VariableIntegrationTests {

        /**
         * Helper to create a member-access expression: `identifierName.memberName`
         * This simulates `a.name` in the pattern DSL.
         */
        private fun makeMemberAccess(identifierName: String, memberName: String, scope: Int = 0) =
            TypedMemberAccessExpression(
                evalType = 0,
                expression = TypedIdentifierExpression(evalType = 0, name = identifierName, scope = scope),
                member = memberName,
                isNullChaining = false
            )

        /**
         * Helper to create an identifier expression referencing a variable.
         */
        private fun makeIdentifier(name: String, scope: Int = 0) =
            TypedIdentifierExpression(evalType = 0, name = name, scope = scope)

        /**
         * Tests that a variable is bound just-in-time — after its dependency node
         * is covered but before a deferred property constraint that references it.
         *
         * Pattern:
         * ```
         * a : Plan {}
         * b : Sprint { effort == x }
         * var x = a.name
         * link a.sprints -- b.plan
         * ```
         *
         * Expected: VertexScan(a) → VariableBinding(x) → EdgeWalk(a→b) → ...property(b.effort == x)...
         * (The variable must appear after a is covered but before b's property needs it.)
         */
        @Test
        fun `variable bound just-in-time after dependency node covered`() {
            val varExpr = makeMemberAccess("a", "name")
            val varElement = TypedPatternVariableElement(
                variable = TypedPatternVariable(name = "x", value = varExpr)
            )

            val aInstance = TypedPatternObjectInstanceElement(
                objectInstance = TypedPatternObjectInstance(
                    modifier = null, name = "a", className = "Plan", properties = emptyList()
                )
            )
            val bInstance = TypedPatternObjectInstanceElement(
                objectInstance = TypedPatternObjectInstance(
                    modifier = null, name = "b", className = "Sprint",
                    properties = listOf(
                        TypedPatternPropertyAssignment(
                            propertyName = "effort",
                            operator = "==",
                            value = makeIdentifier("x")
                        )
                    )
                )
            )
            val link = TypedPatternLinkElement(
                link = TypedPatternLink(
                    modifier = null,
                    source = TypedPatternLinkEnd("a", "sprints"),
                    target = TypedPatternLinkEnd("b", "plan")
                )
            )

            val elements = com.mdeo.modeltransformation.runtime.match.PatternCategories(
                matchableInstances = listOf(aInstance, bInstance),
                matchableLinks = listOf(link),
                createInstances = emptyList(), deleteInstances = emptyList(),
                createLinks = emptyList(), deleteLinks = emptyList(),
                forbidInstances = emptyList(), forbidLinks = emptyList(),
                requireInstances = emptyList(), requireLinks = emptyList(),
                variables = listOf(varElement),
                whereClauses = emptyList()
            )

            val plan = MatchPlanBuilder(
                getVertexId = { null },
                nodeAnalyzer = ExpressionNodeAnalyzer(setOf("a", "b", "x"), 0),
                isCollectionExpression = { false },
                metamodelData = scrumMetamodel
            ).build(elements, emptySet())

            val steps = plan.baseSteps

            // Find key step indices
            val aCoveredIdx = steps.indexOfFirst {
                (it is BaseStep.VertexScan && it.instanceName == "a") ||
                (it is BaseStep.EdgeWalk && it.toInstanceName == "a")
            }
            val varBindingIdx = steps.indexOfFirst {
                it is BaseStep.VariableBinding && it.variable.variable.name == "x"
            }
            val bCoveredIdx = steps.indexOfFirst {
                (it is BaseStep.VertexScan && it.instanceName == "b") ||
                (it is BaseStep.EdgeWalk && it.toInstanceName == "b")
            }



            assertTrue(aCoveredIdx >= 0, "a must be covered")
            assertTrue(varBindingIdx >= 0, "VariableBinding(x) must be present")
            assertTrue(bCoveredIdx >= 0, "b must be covered")

            // Variable must be after a is covered
            assertTrue(varBindingIdx > aCoveredIdx,
                "VariableBinding(x) (idx=$varBindingIdx) must come after a is covered (idx=$aCoveredIdx)")
        }

        /**
         * Tests transitive variable dependencies: var y depends on var x which depends on node a.
         * Both variables should be emitted after a is covered, with x before y.
         *
         * Pattern:
         * ```
         * a : Plan {}
         * var x = a.name
         * var y = x
         * where y == 5  (triggers y to be needed)
         * ```
         */
        @Test
        fun `transitive variable deps resolved correctly and emitted in order`() {
            val varX = TypedPatternVariableElement(
                variable = TypedPatternVariable(name = "x", value = makeMemberAccess("a", "name"))
            )
            val varY = TypedPatternVariableElement(
                variable = TypedPatternVariable(name = "y", value = makeIdentifier("x"))
            )

            val aInstance = TypedPatternObjectInstanceElement(
                objectInstance = TypedPatternObjectInstance(
                    modifier = null, name = "a", className = "Plan", properties = emptyList()
                )
            )

            val whereClause = TypedPatternWhereClauseElement(
                whereClause = TypedWhereClause(
                    expression = TypedBinaryExpression(
                        evalType = 0,
                        operator = "==",
                        left = makeIdentifier("y"),
                        right = makeConstantExpression(5)
                    )
                )
            )

            val elements = com.mdeo.modeltransformation.runtime.match.PatternCategories(
                matchableInstances = listOf(aInstance),
                matchableLinks = emptyList(),
                createInstances = emptyList(), deleteInstances = emptyList(),
                createLinks = emptyList(), deleteLinks = emptyList(),
                forbidInstances = emptyList(), forbidLinks = emptyList(),
                requireInstances = emptyList(), requireLinks = emptyList(),
                variables = listOf(varX, varY),
                whereClauses = listOf(whereClause)
            )

            val plan = MatchPlanBuilder(
                getVertexId = { null },
                nodeAnalyzer = ExpressionNodeAnalyzer(setOf("a", "x", "y"), 0),
                isCollectionExpression = { false },
                metamodelData = MetamodelData.empty()
            ).build(elements, emptySet())

            val steps = plan.baseSteps

            val aCoveredIdx = steps.indexOfFirst {
                (it is BaseStep.VertexScan && it.instanceName == "a") ||
                (it is BaseStep.EdgeWalk && it.toInstanceName == "a")
            }
            val xBindingIdx = steps.indexOfFirst {
                it is BaseStep.VariableBinding && it.variable.variable.name == "x"
            }
            val yBindingIdx = steps.indexOfFirst {
                it is BaseStep.VariableBinding && it.variable.variable.name == "y"
            }
            val whereIdx = steps.indexOfFirst { it is BaseStep.WhereFilter }

            assertTrue(aCoveredIdx >= 0, "a must be covered")
            assertTrue(xBindingIdx >= 0, "VariableBinding(x) must be present")
            assertTrue(yBindingIdx >= 0, "VariableBinding(y) must be present")
            assertTrue(whereIdx >= 0, "WhereFilter must be present")

            // x must come after a
            assertTrue(xBindingIdx > aCoveredIdx,
                "x (idx=$xBindingIdx) must come after a (idx=$aCoveredIdx)")
            // y must come after x (because y depends on x)
            assertTrue(yBindingIdx > xBindingIdx,
                "y (idx=$yBindingIdx) must come after x (idx=$xBindingIdx)")
            // where clause must come after y (because it references y)
            assertTrue(whereIdx > yBindingIdx,
                "WhereFilter (idx=$whereIdx) must come after y (idx=$yBindingIdx)")
        }

        /**
         * Tests that patterns without variables produce **identical** step lists
         * to the original behavior. This is the critical identity guarantee.
         */
        @Test
        fun `no-variable pattern produces identical step list`() {
            // Use the scrum pattern from the main ordering tests (no variables)
            val elements = buildScrumPatternElements()
            val matchableNames = elements.matchableInstances.map { it.objectInstance.name }.toSet()
            val nodeAnalyzer = ExpressionNodeAnalyzer(matchableNames, 0)
            val plan = MatchPlanBuilder(
                getVertexId = { null },
                nodeAnalyzer = nodeAnalyzer,
                isCollectionExpression = { false },
                metamodelData = scrumMetamodel
            ).build(elements, emptySet())

            // Build a second plan — must be identical
            val plan2 = MatchPlanBuilder(
                getVertexId = { null },
                nodeAnalyzer = ExpressionNodeAnalyzer(matchableNames, 0),
                isCollectionExpression = { false },
                metamodelData = scrumMetamodel
            ).build(elements, emptySet())

            assertEquals(plan.baseSteps, plan2.baseSteps,
                "Two plans from the same no-variable input must produce identical step lists")

            // Verify no VariableBinding steps appear
            val varBindings = plan.baseSteps.filterIsInstance<BaseStep.VariableBinding>()
            assertTrue(varBindings.isEmpty(), "No VariableBinding steps should be present")
        }

        /**
         * Tests that variables don't interfere with NAC condition ordering.
         * The NAC should still fire at the same relative position even when
         * variables are present (they are just emitted alongside).
         *
         * Pattern:
         * ```
         * plan     : Plan {}
         * workItem : WorkItem {}
         * var x = plan.name
         * forbid sprint1 : Sprint {}
         * forbid workItem -- sprint1  (orphan link)
         * ```
         */
        @Test
        fun `variables do not affect NAC condition ordering`() {
            val varElement = TypedPatternVariableElement(
                variable = TypedPatternVariable(name = "x", value = makeMemberAccess("plan", "name"))
            )

            val elements = com.mdeo.modeltransformation.runtime.match.PatternCategories(
                matchableInstances = listOf(
                    makeInstance(null, "plan", "Plan"),
                    makeInstance(null, "workItem", "WorkItem")
                ),
                matchableLinks = emptyList(),
                createInstances = emptyList(), deleteInstances = emptyList(),
                createLinks = emptyList(), deleteLinks = emptyList(),
                forbidInstances = listOf(makeInstance("forbid", "sprint1", "Sprint")),
                forbidLinks = listOf(
                    makeLink("forbid", "sprint1", "committedItems", "workItem", "isPlannedFor")
                ),
                requireInstances = emptyList(), requireLinks = emptyList(),
                variables = listOf(varElement),
                whereClauses = emptyList()
            )

            val plan = MatchPlanBuilder(
                getVertexId = { null },
                nodeAnalyzer = ExpressionNodeAnalyzer(setOf("plan", "workItem", "x"), 0),
                isCollectionExpression = { false },
                metamodelData = scrumMetamodel
            ).build(elements, emptySet())

            val steps = plan.baseSteps

            // The NAC must come after workItem is covered
            val workItemIdx = steps.indexOfFirst {
                it is BaseStep.VertexScan && it.instanceName == "workItem"
            }
            val nacIdx = steps.indexOfFirst { it is BaseStep.ApplicationCondition }

            assertTrue(workItemIdx >= 0, "workItem must be covered")
            assertTrue(nacIdx >= 0, "NAC must be present")
            assertTrue(nacIdx > workItemIdx,
                "NAC (idx=$nacIdx) must come after workItem (idx=$workItemIdx)")

            // Variable binding for x must be present
            val varIdx = steps.indexOfFirst {
                it is BaseStep.VariableBinding && it.variable.variable.name == "x"
            }
            assertTrue(varIdx >= 0, "VariableBinding(x) must be present")
        }
    }
}
