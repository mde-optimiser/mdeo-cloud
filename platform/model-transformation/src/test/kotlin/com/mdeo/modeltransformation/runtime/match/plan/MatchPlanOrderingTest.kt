package com.mdeo.modeltransformation.runtime.match.plan

import com.mdeo.expression.ast.expressions.TypedIntLiteralExpression
import com.mdeo.metamodel.data.*
import com.mdeo.modeltransformation.ast.patterns.*
import com.mdeo.modeltransformation.runtime.match.ExpressionNodeAnalyzer
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the metamodel-based class priority computation and the resulting
 * match-step ordering in [MatchPlanBuilder].
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
 * Expected class priorities: Plan > Sprint = WorkItem = Stakeholder
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
 * Expected plan ordering:
 * 1. `plan` is matched first (Plan has the highest class priority as composition root).
 * 2. `sprint2` is walked from `plan` via the matchable link (same component {plan, sprint2}).
 * 3. `workItem` is scanned (separate component, lower class priority but processed next).
 * 4. The cheapest condition — the orphan forbid link `sprint2 -- workItem` — is evaluated
 *    immediately after `workItem` becomes covered.
 * 5. The NAC island (sprint1 anchored at workItem + plan) is evaluated next.
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

    // ── MetamodelClassPriority unit tests ─────────────────────────────────────

    @Nested
    inner class ClassPriorityTests {

        @Test
        fun `Plan has strictly higher priority than Sprint, WorkItem, Stakeholder`() {
            val priorities = MetamodelClassPriority.computeClassPriorities(scrumMetamodel)

            val planPrio        = priorities["Plan"]        ?: error("Plan not in priorities")
            val sprintPrio      = priorities["Sprint"]      ?: error("Sprint not in priorities")
            val workItemPrio    = priorities["WorkItem"]    ?: error("WorkItem not in priorities")
            val stakeholderPrio = priorities["Stakeholder"] ?: error("Stakeholder not in priorities")

            assertTrue(planPrio > sprintPrio,
                "Plan priority ($planPrio) should be > Sprint priority ($sprintPrio)")
            assertTrue(planPrio > workItemPrio,
                "Plan priority ($planPrio) should be > WorkItem priority ($workItemPrio)")
            assertTrue(planPrio > stakeholderPrio,
                "Plan priority ($planPrio) should be > Stakeholder priority ($stakeholderPrio)")
        }

        @Test
        fun `Sprint, WorkItem, Stakeholder have equal priority`() {
            val priorities = MetamodelClassPriority.computeClassPriorities(scrumMetamodel)

            val sprintPrio      = priorities["Sprint"]      ?: error("Sprint not in priorities")
            val workItemPrio    = priorities["WorkItem"]    ?: error("WorkItem not in priorities")
            val stakeholderPrio = priorities["Stakeholder"] ?: error("Stakeholder not in priorities")

            assertEquals(sprintPrio, workItemPrio,
                "Sprint and WorkItem should have equal priority")
            assertEquals(sprintPrio, stakeholderPrio,
                "Sprint and Stakeholder should have equal priority")
        }

        @Test
        fun `isolated root classes all have maximum priority`() {
            // A metamodel with no associations: every class is a root with max priority.
            val metamodel = MetamodelData(
                classes = listOf(
                    ClassData("A", false),
                    ClassData("B", false),
                    ClassData("C", false)
                )
            )
            val priorities = MetamodelClassPriority.computeClassPriorities(metamodel)
            val a = priorities["A"]!!; val b = priorities["B"]!!; val c = priorities["C"]!!
            assertEquals(a, b, "All roots should have equal priority")
            assertEquals(b, c, "All roots should have equal priority")
        }

        @Test
        fun `inheritance hierarchy - parent has higher priority than child`() {
            val metamodel = MetamodelData(
                classes = listOf(
                    ClassData("Animal", isAbstract = true),
                    ClassData("Dog", isAbstract = false, extends = listOf("Animal")),
                    ClassData("Cat", isAbstract = false, extends = listOf("Animal"))
                )
            )
            val priorities = MetamodelClassPriority.computeClassPriorities(metamodel)
            val animalPrio = priorities["Animal"]!!
            val dogPrio    = priorities["Dog"]!!
            val catPrio    = priorities["Cat"]!!

            assertTrue(animalPrio > dogPrio,    "Animal > Dog (parent > child)")
            assertTrue(animalPrio > catPrio,    "Animal > Cat (parent > child)")
            assertEquals(dogPrio, catPrio,       "Siblings have equal priority")
        }

        @Test
        fun `composition cycle is broken and does not crash`() {
            // Artificial cycle: A contains B, B contains A
            val metamodel = MetamodelData(
                classes = listOf(ClassData("A", false), ClassData("B", false)),
                associations = listOf(
                    AssociationData(
                        AssociationEndData("A", "b", MultiplicityData.many()),
                        "<>->",
                        AssociationEndData("B", "a", MultiplicityData.single())
                    ),
                    AssociationData(
                        AssociationEndData("B", "a", MultiplicityData.many()),
                        "<>->",
                        AssociationEndData("A", "b", MultiplicityData.single())
                    )
                )
            )
            // Should not throw; one of A or B will be root, the other will be child.
            val priorities = MetamodelClassPriority.computeClassPriorities(metamodel)
            assertNotNull(priorities["A"])
            assertNotNull(priorities["B"])
            // One must have strictly higher priority (the DFS root wins)
            assertTrue(priorities["A"]!! != priorities["B"]!!,
                "Cycle broken: one class should have higher priority than the other")
        }

        @Test
        fun `regular association joins separate trees`() {
            // A and B in separate trees (no composition/inheritance).
            // A.items[*] <--> B.owner[1]: A has higher multiplicity => A is parent.
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
            val priorities = MetamodelClassPriority.computeClassPriorities(metamodel)
            val aPrio = priorities["A"]!!
            val bPrio = priorities["B"]!!
            assertTrue(aPrio > bPrio,
                "A should have higher priority than B (A.items[*] makes A the parent)")
        }
    }

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
        fun `plan is scanned before workItem (class priority)`() {
            val plan = buildPlan()
            val steps = plan.baseSteps

            // Plan has higher class priority (2) vs workItem (1), so it is scanned first.
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

            // workItem and sprint2 have equal class priority (both 1) but covering workItem
            // unlocks the NAC island (anchors = {workItem, plan}) at lower cost than covering
            // sprint2 first.  The step-level greedy therefore scans workItem before walking
            // plan → sprint2, allowing the NAC to prune traversers before the sprint2 fan-out.
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
}
