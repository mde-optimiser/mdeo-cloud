package com.mdeo.optimizer.rulegen

import com.mdeo.metamodel.data.ModelData
import com.mdeo.metamodel.data.ModelDataInstance
import com.mdeo.metamodel.data.ModelDataLink
import com.mdeo.metamodel.data.ModelDataPropertyValue
import com.mdeo.metamodel.data.MultiplicityData
import com.mdeo.metamodel.data.PropertyData
import com.mdeo.optimizer.config.RefinementConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MutationRuleGeneratorTest {

    private fun names(metamodel: com.mdeo.metamodel.data.MetamodelData, vararg specs: String, refinements: List<RefinementConfig> = emptyList()) =
        MutationRuleGenerator.generate(metamodel, specs.map { spec(it) }, refinements).mutations.map { it.name }.toSet()

    private fun pair(e: String, f: String) = mm("Context", "A", "B") {
        assoc("Context.as[*]", "*-->", "A.ctx")
        assoc("Context.bs[*]", "*-->", "B.ctx")
        assoc("A.e[$e]", "<-->", "B.f[$f]")
    }

    private fun model(metamodelPath: String, objects: String, vararg links: String) = ModelData(
        metamodelPath,
        objects.split(" ").map { ModelDataInstance(it.substringBefore(":"), it.substringAfter(":"), emptyMap()) },
        links.map { l ->
            val (s, t) = l.split(" -- ")
            ModelDataLink(s.substringBefore("."), s.substringAfter(".", "").ifEmpty { null }, t.substringBefore("."), t.substringAfter(".", "").ifEmpty { null })
        }
    )

    /** Applies [operator] until a result satisfies [goal], failing after [tries] attempts. */
    private fun applyUntil(metamodel: com.mdeo.metamodel.data.MetamodelData, operator: GeneratedMutation, model: ModelData, tries: Int = 50, goal: (ModelData) -> Boolean): ModelData {
        val runner = RuleRunner(metamodel)
        repeat(tries) {
            val result = runner.apply(operator, model)
            if (result is Applied.Yes && goal(result.model)) return result.model
        }
        throw AssertionError("'${operator.name}' never reached the expected result on ${model.render()}")
    }

    @Nested
    inner class EdgeOperators {

        @Test
        fun `an open pattern gets add and remove`() {
            assertEquals(setOf("ADD_A_e", "REMOVE_A_e"), names(pair("*", "?"), "mutate A.e"))
        }

        @Test
        fun `add and remove can be asked for separately`() {
            assertEquals(setOf("ADD_A_e"), names(pair("*", "?"), "add A.e"))
            assertEquals(setOf("REMOVE_A_e"), names(pair("*", "?"), "remove A.e"))
        }

        @Test
        fun `a fixed opposite gets change instead`() {
            assertEquals(setOf("CHANGE_A_e"), names(pair("*", "1"), "mutate A.e"))
            assertEquals(setOf("CHANGE_A_e"), names(pair("*", "1"), "add A.e"))
        }

        @Test
        fun `a fixed end gets swap and change`() {
            assertEquals(setOf("SWAP_A_e", "CHANGE_A_e"), names(pair("1", "*"), "mutate A.e"))
        }

        @Test
        fun `an end fixed at both sides only gets swap`() {
            assertEquals(setOf("SWAP_A_e"), names(pair("1", "1"), "mutate A.e"))
        }

        @Test
        fun `ends that can be tight also get swap`() {
            // an upper bound at one end and a lower bound at the other
            assertEquals(setOf("ADD_A_e", "REMOVE_A_e", "SWAP_A_e"), names(pair("?", "+"), "mutate A.e"))
            assertEquals(setOf("ADD_A_e", "REMOVE_A_e", "SWAP_A_e"), names(pair("1..2", "2..3"), "mutate A.e"))
            assertEquals(setOf("CHANGE_A_e", "SWAP_A_e"), names(pair("?", "1"), "mutate A.e"))
            // never tight: no upper bound opposite a lower one
            assertEquals(setOf("ADD_A_e", "REMOVE_A_e"), names(pair("2..3", "*"), "mutate A.e"))
            assertEquals(setOf("ADD_A_e", "REMOVE_A_e"), names(pair("+", "+"), "mutate A.e"))
            assertEquals(setOf("CHANGE_A_e"), names(pair("*", "1"), "mutate A.e"))
            // a swap neither adds nor removes
            assertEquals(setOf("ADD_A_e"), names(pair("?", "+"), "add A.e"))
        }

        @Test
        fun `selecting an edge never creates or deletes objects`() {
            val all = names(pair("1..3", "0..2"), "mutate A.e")
            assertTrue(all.none { it.startsWith("CREATE") || it.startsWith("DELETE") }, "$all")
        }

        @Test
        fun `a refined palladio allocation can move a component to an unused server`() {
            val metamodel = mm("ResourceContainer", "AllocationContext") {
                assoc("AllocationContext.resourceContainer[?]", "-->", "ResourceContainer")
            }
            val refinements = listOf(refine("AllocationContext.resourceContainer[1]"))
            val result = MutationRuleGenerator.generate(metamodel, listOf(spec("mutate AllocationContext.resourceContainer")), refinements)
            assertEquals(setOf("SWAP_AllocationContext_resourceContainer", "CHANGE_AllocationContext_resourceContainer"), result.mutations.map { it.name }.toSet())

            val model = model("/test.mm", "rc1:ResourceContainer rc2:ResourceContainer ac1:AllocationContext ac2:AllocationContext",
                "ac1.resourceContainer -- rc1", "ac2.resourceContainer -- rc1")
            val change = result.mutations.single { it.name.startsWith("CHANGE") }
            applyUntil(metamodel, change, model) { m -> m.links.any { it.targetName == "rc2" } }
        }

        @Test
        fun `a part already in a whole through another composition is not added to that whole again`() {
            val metamodel = mm("Pair", "Part") {
                assoc("Pair.left[?]", "*-->", "Part.leftOf[?]")
                assoc("Pair.right[?]", "*-->", "Part.rightOf[?]")
            }
            val add = MutationRuleGenerator.generate(metamodel, listOf(spec("add Pair.left"))).mutations.single()
            val model = model("/test.mm", "p:Pair x:Part", "p.right -- x.rightOf")
            val runner = RuleRunner(metamodel)
            repeat(20) { assertEquals(Applied.No, runner.apply(add, model)) }
            // Nor can another pair take it; a free part can be added.
            assertTrue(runner.apply(add, model("/test.mm", "p:Pair q:Pair x:Part", "p.right -- x.rightOf")) == Applied.No)
            assertTrue(runner.apply(add, model("/test.mm", "p:Pair x:Part")) is Applied.Yes)
        }

        @Test
        fun `a change keeps the old partner's lower bound`() {
            val metamodel = mm("Worker", "Task") { assoc("Worker.tasks[1..*]", "<-->", "Task.worker") }
            val change = MutationRuleGenerator.generate(metamodel, listOf(spec("mutate Worker.tasks"))).mutations.single { it.name == "CHANGE_Worker_tasks" }
            val model = model("/test.mm", "w1:Worker w2:Worker t1:Task t2:Task t3:Task",
                "w1.tasks -- t1.worker", "w1.tasks -- t2.worker", "w2.tasks -- t3.worker")
            val runner = RuleRunner(metamodel)
            repeat(30) {
                val result = runner.apply(change, model)
                assertTrue(result is Applied.Yes)
                assertTrue(MultiplicityChecker.violations((result as Applied.Yes).model, MetamodelInfo(metamodel)).isEmpty())
            }
        }
    }

    @Nested
    inner class CreateOperators {

        @Test
        fun `create follows the paper's table`() {
            // n = 1, l = 5: connect an existing B with room, or take one from another A
            assertEquals(
                setOf("CREATE_A_in_Context_via_as_e_CONNECT", "CREATE_A_in_Context_via_as_e_LBREPAIR"),
                names(pair("1..*", "0..5"), "create A")
            )
            // n = 2, k = l: only repairs
            assertEquals(
                setOf("CREATE_A_in_Context_via_as_e_LBREPAIR", "CREATE_A_in_Context_via_as_e_LBREPAIR_MULTI"),
                names(pair("2..*", "1"), "create A")
            )
            // n = 0: no requirement
            assertEquals(setOf("CREATE_A_in_Context_via_as"), names(pair("*", "1"), "create A"))
            // l = *: connect only
            assertEquals(setOf("CREATE_A_in_Context_via_as_e_CONNECT"), names(pair("1", "*"), "create A"))
        }

        @Test
        fun `a single donor that cannot keep its lower bound is not offered`() {
            assertEquals(
                setOf("CREATE_A_in_Context_via_as_e_CONNECT", "CREATE_A_in_Context_via_as_e_LBREPAIR_MULTI"),
                names(pair("2..3", "?"), "create A")
            )
        }

        @Test
        fun `ends fixed at both sides prevent creation with a warning`() {
            val result = MutationRuleGenerator.generate(pair("1", "1"), listOf(spec("create A")))
            assertTrue(result.mutations.isEmpty())
            assertTrue(result.warnings.single().contains("No create operator for 'A'"))
        }

        @Test
        fun `one operator per combination of the required ends' repairs`() {
            val metamodel = mm("Context", "A", "B", "C") {
                assoc("Context.as[*]", "*-->", "A.ctx")
                assoc("A.b[1..2]", "<-->", "B.a[0..2]")
                assoc("A.c[1..2]", "<-->", "C.a[0..2]")
            }
            assertEquals(
                setOf(
                    "CREATE_A_in_Context_via_as_b_CONNECT_c_CONNECT", "CREATE_A_in_Context_via_as_b_CONNECT_c_LBREPAIR",
                    "CREATE_A_in_Context_via_as_b_LBREPAIR_c_CONNECT", "CREATE_A_in_Context_via_as_b_LBREPAIR_c_LBREPAIR"
                ),
                names(metamodel, "create A")
            )
        }

        @Test
        fun `a new sprint taken over from another sprint is created inside a plan`() {
            val metamodel = mm("Plan", "Sprint", "WorkItem") {
                assoc("Plan.sprints[*]", "*-->", "Sprint.plan")
                assoc("Sprint.committedItems[+]", "<-->", "WorkItem.isPlannedFor[?]")
            }
            val create = MutationRuleGenerator.generate(metamodel, listOf(spec("create Sprint"))).mutations
                .single { it.name == "CREATE_Sprint_in_Plan_via_sprints_committedItems_LBREPAIR" }
            val model = model("/test.mm", "p:Plan s1:Sprint w1:WorkItem w2:WorkItem",
                "p.sprints -- s1.plan", "s1.committedItems -- w1.isPlannedFor", "s1.committedItems -- w2.isPlannedFor")
            val after = applyUntil(metamodel, create, model) { it.instances.size == 5 }
            assertTrue(MultiplicityChecker.violations(after, MetamodelInfo(metamodel)).isEmpty(), after.render())
        }

        @Test
        fun `a whole holding a fixed number of parts gets no new part`() {
            val optional = MutationRuleGenerator.generate(
                mm("Plan", "Backlog") { assoc("Plan.backlog[1]", "*-->", "Backlog.plan[?]") }, listOf(spec("create Backlog"))
            )
            assertEquals(listOf("CREATE_Backlog"), optional.mutations.map { it.name })
            assertTrue(optional.warnings.single().contains("holds a fixed number of parts"), "${optional.warnings}")

            val required = MutationRuleGenerator.generate(
                mm("Plan", "Backlog") { assoc("Plan.backlog[1]", "*-->", "Backlog.plan") }, listOf(spec("create Backlog"))
            )
            assertTrue(required.mutations.isEmpty())
            assertTrue(required.warnings.any { it.contains("No create operator for 'Backlog'") }, "${required.warnings}")
        }

        @Test
        fun `an abstract class is created as each of its concrete subclasses`() {
            val metamodel = MetamodelBuilder("/test.mm").apply {
                cls("Root"); cls("abstract Shape"); cls("Circle : Shape"); cls("Square : Shape")
                assoc("Root.shapes[*]", "*-->", "Shape.root")
            }.build()
            assertEquals(setOf("CREATE_Circle_in_Root_via_shapes", "CREATE_Square_in_Root_via_shapes"), names(metamodel, "create Shape"))
        }

        @Test
        fun `required attributes get defaults`() {
            val metamodel = MetamodelBuilder("/test.mm").apply {
                enum("Colour", "RED", "GREEN")
                cls(
                    "Item",
                    PropertyData("name", primitiveType = "string", multiplicity = MultiplicityData(1, 1)),
                    PropertyData("colour", enumType = "Colour", multiplicity = MultiplicityData(1, 1)),
                    PropertyData("weight", primitiveType = "int", multiplicity = MultiplicityData(1, 1)),
                    PropertyData("note", primitiveType = "string", multiplicity = MultiplicityData(0, 1))
                )
            }.build()
            val create = MutationRuleGenerator.generate(metamodel, listOf(spec("create Item"))).mutations.single()
            val after = RuleRunner(metamodel).apply(create, ModelData("/test.mm", emptyList(), emptyList()))
            val item = (after as Applied.Yes).model.instances.single()
            assertEquals(ModelDataPropertyValue.StringValue(""), item.properties["name"])
            assertEquals(ModelDataPropertyValue.EnumValue("RED"), item.properties["colour"])
            assertEquals(0.0, (item.properties["weight"] as ModelDataPropertyValue.NumberValue).value)
        }
    }

    @Nested
    inner class DeleteOperators {

        @Test
        fun `deleting a house moves every room to another house`() {
            val metamodel = mm("House", "Room") { assoc("House.rooms[*]", "*-->", "Room.house") }
            val delete = MutationRuleGenerator.generate(metamodel, listOf(spec("delete House"))).mutations.single()
            val model = model("/test.mm", "h1:House h2:House r1:Room r2:Room r3:Room",
                "h1.rooms -- r1.house", "h1.rooms -- r2.house", "h1.rooms -- r3.house")
            val after = applyUntil(metamodel, delete, model) { m -> m.instances.none { it.name == "h1" } }
            assertEquals(3, after.links.count { it.sourceName == "h2" })
        }

        @Test
        fun `an object is only deleted when every partner keeps its lower bound`() {
            val metamodel = mm("A", "B") { assoc("A.bs[*]", "<-->", "B.as[1..*]") }
            val delete = MutationRuleGenerator.generate(metamodel, listOf(spec("delete A"))).mutations.single()
            val model = model("/test.mm", "a1:A a2:A b1:B b2:B", "a1.bs -- b1.as", "a1.bs -- b2.as", "a2.bs -- b2.as")
            val runner = RuleRunner(metamodel)
            repeat(30) {
                val result = runner.apply(delete, model)
                // a1 is b1's only partner; only a2 can go
                if (result is Applied.Yes) assertTrue(result.model.instances.any { it.name == "a1" })
            }
        }

        @Test
        fun `an object needed through a unidirectional end hands its partner to another object`() {
            val metamodel = mm("A", "B") { assoc("A.b", "-->", "B") }
            val delete = MutationRuleGenerator.generate(metamodel, listOf(spec("delete B"))).mutations.single()
            val model = model("/test.mm", "a1:A b1:B b2:B", "a1.b -- b2")
            val after = applyUntil(metamodel, delete, model) { m -> m.instances.none { it.name == "b2" } }
            assertEquals(listOf("a1.b--b1."), after.links.map { "${it.sourceName}.${it.sourceProperty}--${it.targetName}.${it.targetProperty ?: ""}" })
        }

        @Test
        fun `a moved partner never becomes its own partner`() {
            // a1's friend must be replaced when a2 goes: a3 can take it, a1 itself may not.
            val metamodel = mm("A") { assoc("A.friend", "-->", "A") }
            val delete = MutationRuleGenerator.generate(metamodel, listOf(spec("delete A"))).mutations.single()
            val model = model("/test.mm", "a1:A a2:A a3:A", "a1.friend -- a2", "a2.friend -- a3", "a3.friend -- a1")
            val runner = RuleRunner(metamodel)
            repeat(30) {
                val result = runner.apply(delete, model)
                if (result is Applied.Yes) assertTrue(result.model.links.none { it.sourceName == it.targetName }, result.model.render())
            }
            applyUntil(metamodel, delete, model) { m -> m.instances.none { it.name == "a2" } }
        }

        @Test
        fun `an object needed through a fixed end is kept when nobody can take its partner`() {
            val metamodel = mm("A", "B") { assoc("A.b", "<-->", "B.a") }
            assertTrue(MutationRuleGenerator.generate(metamodel, listOf(spec("delete B"))).mutations.isEmpty())
        }
    }

    @Nested
    inner class Selection {

        @Test
        fun `inherited ends can be selected`() {
            val metamodel = MetamodelBuilder("/test.mm").apply {
                cls("abstract Shape"); cls("Circle : Shape"); cls("Tag")
                assoc("Shape.tags[*]", "-->", "Tag")
            }.build()
            assertEquals(setOf("ADD_Circle_tags", "REMOVE_Circle_tags"), names(metamodel, "mutate Circle.tags"))
        }

        @Test
        fun `two associations between the same classes are kept apart`() {
            val metamodel = mm("A", "B") {
                assoc("A.x[*]", "<-->", "B.xb")
                assoc("A.y[*]", "<-->", "B.yb[?]")
            }
            val result = MutationRuleGenerator.generate(metamodel, listOf(spec("mutate A.y")))
            assertEquals(setOf("ADD_A_y", "REMOVE_A_y"), result.mutations.map { it.name }.toSet())
            val model = model("/test.mm", "a1:A b1:B", "a1.x -- b1.xb")
            val add = result.mutations.single { it.name == "ADD_A_y" }
            val after = applyUntil(metamodel, add, model) { true }
            assertTrue(after.links.any { it.sourceProperty == "y" && it.targetProperty == "yb" }, after.render())
        }

        @Test
        fun `operators on a recursive composition warn about containment cycles`() {
            val metamodel = mm("Node") { assoc("Node.children[*]", "*-->", "Node.parent[?]") }
            val warnings = MutationRuleGenerator.generate(metamodel, listOf(spec("mutate Node.children"))).warnings
            assertTrue(warnings.single().contains("recursive composition"), "$warnings")
        }

        @Test
        fun `an object of a subclass never plays two roles`() {
            val metamodel = MetamodelBuilder("/test.mm").apply {
                cls("abstract Item"); cls("Folder : Item"); cls("File : Item")
                assoc("Folder.items[*]", "*-->", "Item.folder[?]")
            }.build()
            val add = MutationRuleGenerator.generate(metamodel, listOf(spec("add Item.folder"))).mutations.single()
            // The only folder is the only item: it would have to become its own item.
            val runner = RuleRunner(metamodel)
            repeat(20) { assertEquals(Applied.No, runner.apply(add, model("/test.mm", "f1:Folder"))) }
            assertTrue(runner.apply(add, model("/test.mm", "f1:Folder f2:File")) is Applied.Yes)
        }

        @Test
        fun `the same operator asked for twice is generated once`() {
            assertEquals(setOf("ADD_A_e", "REMOVE_A_e"), names(pair("*", "*"), "mutate A.e", "add A.e", "mutate A.e"))
        }

        @Test
        fun `unknown classes, ends and attributes are rejected`() {
            val metamodel = MetamodelBuilder("/test.mm").apply {
                cls("A", PropertyData("size", primitiveType = "int", multiplicity = MultiplicityData(1, 1))); cls("B")
                assoc("A.b[*]", "-->", "B")
            }.build()
            assertTrue(assertThrows<RuleGenerationException> { names(metamodel, "mutate C") }.message!!.contains("unknown class 'C'"))
            assertTrue(assertThrows<RuleGenerationException> { names(metamodel, "mutate A.c") }.message!!.contains("no association end 'c'"))
            assertTrue(assertThrows<RuleGenerationException> { names(metamodel, "mutate A.size") }.message!!.contains("attribute"))
        }
    }

    @Nested
    inner class Refinements {

        private val metamodel = MetamodelBuilder("/test.mm").apply {
            cls("abstract P"); cls("A : P", PropertyData("size", primitiveType = "int", multiplicity = MultiplicityData(1, 1))); cls("B")
            assoc("P.bs[0..5]", "<-->", "B.p[*]")
        }.build()

        private fun error(refinement: String) =
            assertThrows<RuleGenerationException> { names(metamodel, "mutate A.bs", refinements = listOf(refine(refinement))) }.message!!

        @Test
        fun `refined bounds are what the operators keep`() {
            assertEquals(setOf("SWAP_A_bs", "CHANGE_A_bs"), names(metamodel, "mutate A.bs", refinements = listOf(refine("P.bs[2]"))))
        }

        @Test
        fun `invalid refinements are rejected`() {
            assertTrue(error("P.bs[0..6]").contains("does not lie within"))
            assertTrue(error("P.bs[0..*]").contains("does not lie within"))
            assertTrue(error("A.bs[1]").contains("declared on 'P'"))
            assertTrue(error("A.size[1]").contains("attribute"))
            assertTrue(error("P.foo[1]").contains("no association end 'foo'"))
            assertTrue(error("P.bs[3..2]").contains("invalid bounds"))
        }

        @Test
        fun `the checker reports a model that violates a refinement`() {
            val info = MetamodelInfo(metamodel, listOf(MultiplicityOverride("P", "bs", 1, 5)))
            val model = model("/test.mm", "a1:A b1:B")
            assertEquals(listOf("a1.bs has 0 link(s), outside [1..5]"), MultiplicityChecker.violations(model, info, info.refinedEnds))
        }
    }
}
