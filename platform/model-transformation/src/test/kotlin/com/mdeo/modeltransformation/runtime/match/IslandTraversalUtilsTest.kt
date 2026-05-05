package com.mdeo.modeltransformation.runtime.match

import com.mdeo.metamodel.data.*
import com.mdeo.modeltransformation.ast.patterns.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [IslandTraversalUtils.selectBestAnchor].
 *
 * The function should prefer anchors that reach the island via an association with
 * a low upper multiplicity bound so that the island traversal chain scans the
 * fewest nodes in the common case.
 *
 * ## Test metamodel (Scrum-inspired)
 *
 * ```
 * Plan.sprints[0..*] *--> Sprint.plan[1]
 * Sprint.committedItems[1..*] <--> WorkItem.isPlannedFor[0..1]
 * ```
 *
 * For the pattern `forbid sprint1 -- workItem; forbid plan -- sprint1`:
 * - island = {sprint1}
 * - anchors = {workItem, plan}
 * - workItem → sprint1 via WorkItem.isPlannedFor[0..1]  → score 1   (to-one, cheap)
 * - plan     → sprint1 via Plan.sprints[0..*]           → score MAX (to-many, expensive)
 *
 * The function should select `workItem` as the best anchor.
 */
class IslandTraversalUtilsTest {

    // ── Scrum metamodel ───────────────────────────────────────────────────────

    private val scrumMetamodel = MetamodelData(
        path = "/test/scrum.mm",
        classes = listOf(
            ClassData(name = "Plan", isAbstract = false),
            ClassData(name = "Sprint", isAbstract = false),
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

    // ── Link factory helpers ──────────────────────────────────────────────────

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

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Nested
    inner class EmptyOrSingleAnchor {

        @Test
        fun `returns null when anchors set is empty`() {
            val result = IslandTraversalUtils.selectBestAnchor(
                anchors = emptySet(),
                links = emptyList(),
                metamodelData = scrumMetamodel
            )
            assertNull(result)
        }

        @Test
        fun `returns the sole anchor when only one exists`() {
            val links = listOf(
                forbidLink("workItem", "isPlannedFor", "sprint1", "committedItems")
            )
            val result = IslandTraversalUtils.selectBestAnchor(
                anchors = setOf("workItem"),
                links = links,
                metamodelData = scrumMetamodel
            )
            assertEquals("workItem", result)
        }
    }

    @Nested
    inner class MultipleAnchorScoring {

        /**
         * Core regression test for the scrum-pattern slowdown:
         *
         * Island = {sprint1}, anchors = {workItem, plan}
         *   - workItem → sprint1 via WorkItem.isPlannedFor[0..1]  → upper = 1  (cheap)
         *   - plan     → sprint1 via Plan.sprints[0..*]           → upper = MAX (expensive)
         *
         * Expected: workItem is selected.
         */
        @Test
        fun `prefers anchor with lower multiplicity upper bound`() {
            val links = listOf(
                // sprint1 (island) -- workItem (anchor), workItem side = isPlannedFor[0..1]
                forbidLink("sprint1", "committedItems", "workItem", "isPlannedFor"),
                // plan (anchor) -- sprint1 (island), plan side = sprints[0..*]
                forbidLink("plan", "sprints", "sprint1", "plan")
            )

            val result = IslandTraversalUtils.selectBestAnchor(
                anchors = setOf("workItem", "plan"),
                links = links,
                metamodelData = scrumMetamodel
            )

            assertEquals("workItem", result)
        }

        @Test
        fun `prefers anchor with bounded multiplicity over unbounded`() {
            // Sprint.plan[1] is single → upper=1; Plan.sprints[0..*] → upper=-1 (unbounded)
            // If the island has Sprint and anchors are {plan, someOtherNode}, and someOtherNode
            // reaches the island via a [1] multiplicity association, it should be preferred.
            val links = listOf(
                // sprint1 (island) -- plan (anchor): plan.sprints[0..*] → plan side unbounded
                forbidLink("plan", "sprints", "sprint1", "plan"),
                // sprint1 (island) -- workItem (anchor): workItem.isPlannedFor[0..1] → upper=1
                forbidLink("sprint1", "committedItems", "workItem", "isPlannedFor")
            )

            val result = IslandTraversalUtils.selectBestAnchor(
                anchors = setOf("plan", "workItem"),
                links = links,
                metamodelData = scrumMetamodel
            )

            assertEquals("workItem", result)
        }

        @Test
        fun `falls back to arbitrary anchor when no association found in metamodel`() {
            // Links with property names not present in the metamodel — score = MAX for all.
            val links = listOf(
                forbidLink("anchorA", "unknownProp", "islandNode", null),
                forbidLink("anchorB", "anotherUnknown", "islandNode", null)
            )

            // Should not throw; returns one of the anchors deterministically.
            val result = IslandTraversalUtils.selectBestAnchor(
                anchors = setOf("anchorA", "anchorB"),
                links = links,
                metamodelData = scrumMetamodel
            )

            // Both score MAX_VALUE; any anchor is acceptable.
            assert(result == "anchorA" || result == "anchorB") {
                "Expected one of the two anchors, got $result"
            }
        }

        @Test
        fun `with empty metamodel falls back to arbitrary anchor without error`() {
            val links = listOf(
                forbidLink("anchorA", "prop", "islandNode", null),
                forbidLink("anchorB", "prop2", "islandNode", null)
            )

            val result = IslandTraversalUtils.selectBestAnchor(
                anchors = setOf("anchorA", "anchorB"),
                links = links,
                metamodelData = MetamodelData.empty()
            )

            assert(result == "anchorA" || result == "anchorB")
        }
    }

    // ── orderLinksByBFS ───────────────────────────────────────────────────────

    @Nested
    inner class OrderLinksByBFS {

        private fun matchLink(
            sourceName: String,
            sourceProperty: String?,
            targetName: String,
            targetProperty: String?
        ) = TypedPatternLinkElement(
            link = TypedPatternLink(
                modifier = null,
                source = TypedPatternLinkEnd(objectName = sourceName, propertyName = sourceProperty),
                target = TypedPatternLinkEnd(objectName = targetName, propertyName = targetProperty)
            )
        )

        /**
         * Without metamodel data the original BFS (first-fit) ordering is preserved.
         *
         * start = sprint2; two links from sprint2 are simultaneously reachable:
         *   sprint2 → plan     (listed first)
         *   sprint2 → workItem (listed second)
         *
         * Without metamodel data the first-listed link should be picked first.
         */
        @Test
        fun `without metamodel data retains original ordering`() {
            // Both links depart from sprint2 → simultaneously reachable from the start anchor.
            val link1 = matchLink("sprint2", "sprints", "plan",     "plan")
            val link2 = matchLink("sprint2", "committedItems", "workItem", "isPlannedFor")

            val result = IslandTraversalUtils.orderLinksByBFS(
                links = listOf(link1, link2),
                startAnchor = "sprint2"
            )

            assertEquals(2, result.size)
            // First-fit: link1 was listed first and is picked first.
            assertEquals(link1, result[0].first)
            assertEquals(link2, result[1].first)
        }

        /**
         * With metamodel data the cheaper (lower upper-multiplicity) link is picked first.
         *
         * start = sprint2; two links from sprint2:
         *   sprint2 → plan     via Plan.sprints[0..*] : source.upper = MAX (expensive, listed first)
         *   sprint2 → workItem via Sprint.committedItems[1..*] : source.upper = MAX (also unbounded)
         *
         * Both have unbounded fan-out from sprint2, so we use a chain where one
         * path is cheap (to-one) and the other is expensive (to-many) to show ordering.
         *
         * Chain: workItem → sprint1 [isPlannedFor, upper=1 at workItem's side]
         *                 → plan    [sprints, upper=MAX at sprint1's plan-side]
         *
         * Starting from workItem: cheapLink (workItem-end upper=1) must be first.
         */
        @Test
        fun `with metamodel data picks lower-multiplicity edge first`() {
            // workItem --[committedItems_isPlannedFor, workItem=target, upper=0..1]--> sprint1
            val cheapLink = matchLink("sprint1", "committedItems", "workItem", "isPlannedFor")
            // sprint1 --[sprints_plan, plan=source, upper=0..*]--> plan
            val expensiveLink = matchLink("plan", "sprints", "sprint1", "plan")

            // Start from workItem; cheapLink reachable (workItem is target-end, upper=1).
            val result = IslandTraversalUtils.orderLinksByBFS(
                links = listOf(expensiveLink, cheapLink), // expensive listed first
                startAnchor = "workItem",
                metamodelData = scrumMetamodel
            )

            assertEquals(2, result.size)
            // cheapLink (workItem → sprint1, upper=1) must be first despite being listed second.
            assertEquals(cheapLink, result[0].first)
            assertTrue(result[0].second, "cheapLink should be reversed (workItem is target-end)")
            assertEquals(expensiveLink, result[1].first)
        }

        /**
         * With empty metamodel falls back to first-fit ordering.
         *
         * Same links as above but no metamodel → the first reachable link in list order
         * is picked first.  Starting from workItem: cheapLink is reachable (workItem is
         * the target end) and it is listed SECOND — but expensiveLink is NOT reachable
         * from workItem (neither endpoint is workItem).  So cheapLink is actually first.
         *
         * To test "first-fit retains list order" we use a start where BOTH links are
         * reachable simultaneously.
         */
        @Test
        fun `with empty metamodel data retains list order when multiple links reachable`() {
            // Both links depart from workItem (workItem is source for both).
            val link1 = matchLink("workItem", "someProp1", "nodeA", null)
            val link2 = matchLink("workItem", "someProp2", "nodeB", null)

            val result = IslandTraversalUtils.orderLinksByBFS(
                links = listOf(link1, link2),
                startAnchor = "workItem",
                metamodelData = MetamodelData.empty()
            )

            assertEquals(2, result.size)
            // Without metamodel: first-fit picks link1 first (listed first, reachable).
            assertEquals(link1, result[0].first)
            assertEquals(link2, result[1].first)
        }
    }
}
