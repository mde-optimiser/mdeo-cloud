package com.mdeo.optimizer.rulegen

import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.optimizer.config.RefinementConfig
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Applies every generated operator to random consistent models and checks that the result is
 * consistent too, that it changed the model only the way the operator may, and that every
 * operator can apply to some consistent model. See [ConsistencyOracle].
 */
class RuleConsistencyTest {

    private val bounds = listOf("*", "?", "+", "1", "2..3", "2..*", "1..2", "2")

    /** Every pair of end multiplicities, as in MDEOptimiser's rule generation tests. */
    @TestFactory
    fun `operators for every pair of multiplicities keep models consistent`(): List<DynamicTest> =
        bounds.flatMap { e -> bounds.map { f -> e to f } }.map { (e, f) ->
            DynamicTest.dynamicTest("A.e[$e] <--> B.f[$f]") {
                val metamodel = mm("Context", "A", "B") {
                    assoc("Context.as[*]", "*-->", "A.ctx")
                    assoc("Context.bs[*]", "*-->", "B.ctx")
                    assoc("A.e[$e]", "<-->", "B.f[$f]")
                }
                check(metamodel, listOf(spec("mutate A"), spec("mutate B")))
            }
        }

    @TestFactory
    fun `operators for a unidirectional end keep models consistent`(): List<DynamicTest> =
        bounds.map { e ->
            DynamicTest.dynamicTest("A.e[$e] --> B") {
                val metamodel = mm("Context", "A", "B") {
                    assoc("Context.as[*]", "*-->", "A.ctx")
                    assoc("Context.bs[*]", "*-->", "B.ctx")
                    assoc("A.e[$e]", "-->", "B")
                }
                check(metamodel, listOf(spec("mutate A"), spec("mutate B")))
            }
        }

    @TestFactory
    fun `operators for compositions keep models consistent`(): List<DynamicTest> = listOf(
        "rooms need a house" to mm("House", "Room") { assoc("House.rooms[*]", "*-->", "Room.house") },
        "a house needs rooms" to mm("House", "Room") { assoc("House.rooms[+]", "*-->", "Room.house[?]") },
        "whole on the right" to mm("Parent", "Child") { assoc("Child.parent", "<--*", "Parent.children[*]") },
        "unnamed whole end" to mm("Whole", "Part") { assoc("Part.whole", "--*", "Whole") },
        "two possible containers" to mm("Box", "Shelf", "Item") {
            assoc("Box.items[*]", "*-->", "Item.box[?]")
            assoc("Shelf.items[0..2]", "*-->", "Item.shelf[?]")
        },
        "folders need files" to mm("Folder", "File") { assoc("Folder.files[1..*]", "*-->", "File.folder") },
        "recursive" to mm("Node") { assoc("Node.children[*]", "*-->", "Node.parent[?]") },
        "recursive, bounded" to mm("Node") { assoc("Node.children[0..2]", "*-->", "Node.parent[?]") },
        "recursive with a second container" to mm("ResourceEnvironment", "ResourceContainer") {
            assoc("ResourceEnvironment.resourceContainer[*]", "*-->", "ResourceContainer.resourceEnvironment[?]")
            assoc("ResourceContainer.nestedResourceContainers[*]", "*-->", "ResourceContainer.parentResourceContainer[?]")
        },
        "mutually recursive" to mm("Folder", "Archive") {
            assoc("Folder.archives[*]", "*-->", "Archive.folder[?]")
            assoc("Archive.folders[*]", "*-->", "Folder.archive[?]")
        },
        "recursive through a subclass" to MetamodelBuilder("/test.mm").apply {
            cls("abstract Item")
            cls("Folder : Item")
            cls("File : Item")
            assoc("Folder.items[*]", "*-->", "Item.folder[?]")
        }.build()
    ).map { (name, metamodel) ->
        DynamicTest.dynamicTest(name) {
            check(metamodel, metamodel.classes.map { spec("mutate ${it.name}") })
        }
    }

    @TestFactory
    fun `operators on metamodels with several associations keep models consistent`(): List<DynamicTest> = listOf(
        "two associations between the same classes" to mm("A", "B") {
            assoc("A.x[*]", "<-->", "B.xb")
            assoc("A.y[*]", "<-->", "B.yb[?]")
        },
        "scrum planning" to mm("Plan", "Sprint", "WorkItem", "Stakeholder") {
            assoc("Plan.sprints[*]", "*-->", "Sprint.plan")
            assoc("Plan.backlog[*]", "*-->", "WorkItem.plan")
            assoc("Plan.stakeholders[*]", "*-->", "Stakeholder.plan")
            assoc("Sprint.committedItems[+]", "<-->", "WorkItem.isPlannedFor[?]")
            assoc("WorkItem.stakeholder", "<-->", "Stakeholder.workItems[*]")
        },
        "self association, fixed" to mm("Root", "A") {
            assoc("Root.as[*]", "*-->", "A.root")
            assoc("A.next[1]", "<-->", "A.prev[1]")
        },
        "self association, optional" to mm("Root", "A") {
            assoc("Root.as[*]", "*-->", "A.root")
            assoc("A.next[?]", "<-->", "A.prev[?]")
            assoc("A.friends[1..2]", "<-->", "A.friendOf[*]")
        },
        "self association, unidirectional" to mm("Root", "A") {
            assoc("Root.as[*]", "*-->", "A.root")
            assoc("A.boss[1]", "-->", "A")
        },
        "multiple inheritance" to MetamodelBuilder("/test.mm").apply {
            cls("Root")
            cls("abstract Named")
            cls("abstract Owned")
            cls("Item : Named, Owned")
            cls("Owner")
            cls("Label")
            assoc("Root.items[*]", "*-->", "Item.root")
            assoc("Root.owners[*]", "*-->", "Owner.root")
            assoc("Root.labels[*]", "*-->", "Label.root")
            assoc("Named.label", "<-->", "Label.named[*]")
            assoc("Owned.owner[1..2]", "<-->", "Owner.owned[0..3]")
        }.build(),
        "inheritance" to MetamodelBuilder("/test.mm").apply {
            cls("Root")
            cls("abstract Shape")
            cls("Circle : Shape")
            cls("Square : Shape")
            cls("Tag")
            cls("Corner")
            assoc("Root.shapes[*]", "*-->", "Shape.root")
            assoc("Root.tags[*]", "*-->", "Tag.root")
            assoc("Root.corners[*]", "*-->", "Corner.root")
            assoc("Shape.tags[0..2]", "<-->", "Tag.shapes[*]")
            assoc("Square.corner", "<-->", "Corner.square[?]")
        }.build()
    ).map { (name, metamodel) ->
        DynamicTest.dynamicTest(name) {
            check(metamodel, metamodel.classes.map { spec("mutate ${it.name}") })
        }
    }

    @TestFactory
    fun `operators keep refined multiplicities`(): List<DynamicTest> = listOf(
        Triple(
            "palladio allocation",
            mm("ResourceEnvironment", "ResourceContainer", "Allocation", "AllocationContext") {
                assoc("ResourceEnvironment.resourceContainer[*]", "*-->", "ResourceContainer.resourceEnvironment[?]")
                assoc("Allocation.allocationContexts[*]", "*-->", "AllocationContext.allocation[?]")
                assoc("AllocationContext.resourceContainer[?]", "-->", "ResourceContainer")
            },
            Pair(listOf(spec("mutate AllocationContext.resourceContainer")), listOf(refine("AllocationContext.resourceContainer[1]")))
        ),
        Triple(
            "scrum with planned work items",
            mm("Plan", "Sprint", "WorkItem") {
                assoc("Plan.sprints[*]", "*-->", "Sprint.plan")
                assoc("Plan.backlog[*]", "*-->", "WorkItem.plan")
                assoc("Sprint.committedItems[+]", "<-->", "WorkItem.isPlannedFor[?]")
            },
            Pair(listOf(spec("mutate Sprint")), listOf(refine("WorkItem.isPlannedFor[1]")))
        )
    ).map { (name, metamodel, specsAndRefinements) ->
        DynamicTest.dynamicTest(name) {
            check(metamodel, specsAndRefinements.first, specsAndRefinements.second)
        }
    }

    // -------------------------------------------------------------------------

    private fun check(metamodel: MetamodelData, specs: List<MutationRuleSpec>, refinements: List<RefinementConfig> = emptyList()) =
        ConsistencyOracle(metamodel, specs, refinements).check()
}
