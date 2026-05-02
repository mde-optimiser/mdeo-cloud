package com.mdeo.modeltransformation.runtime.match

import com.mdeo.metamodel.data.*
import com.mdeo.modeltransformation.ast.patterns.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
