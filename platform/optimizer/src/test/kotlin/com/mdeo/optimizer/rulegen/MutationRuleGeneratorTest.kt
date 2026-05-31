package com.mdeo.optimizer.rulegen

import com.mdeo.metamodel.data.AssociationData
import com.mdeo.metamodel.data.AssociationEndData
import com.mdeo.metamodel.data.ClassData
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.metamodel.data.MultiplicityData
import com.mdeo.modeltransformation.ast.statements.TypedMatchStatement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstanceElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLinkElement
import com.mdeo.optimizer.config.RefinementConfig
import com.mdeo.modeltransformation.ast.patterns.TypedPatternWhereClauseElement
import com.mdeo.expression.ast.expressions.TypedBinaryExpression
import com.mdeo.expression.ast.expressions.TypedMemberCallExpression
import com.mdeo.expression.ast.expressions.TypedMemberAccessExpression
import com.mdeo.expression.ast.expressions.TypedIdentifierExpression
import com.mdeo.expression.ast.expressions.TypedIntLiteralExpression
import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.expression.ast.types.VoidType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Nested

/**
 * Unit tests for [MutationRuleGenerator] and its collaborators.
 *
 * Uses a simple in-memory metamodel:
 *
 *   House <>-> Room (1..*) -- (rooms / house bidirectional)
 *   Room  --> Window (0..*) -- windows (unidirectional)
 *
 * This covers the key multiplicity and bidirectionality combinations used
 * in [SpecsGenerator] decision logic.
 */
class MutationRuleGeneratorTest {

    // -------------------------------------------------------------------------
    // Shared metamodel fixture
    // -------------------------------------------------------------------------

    private val houseClass = ClassData(name = "House", isAbstract = false)
    private val roomClass  = ClassData(name = "Room",  isAbstract = false)
    private val windowClass = ClassData(name = "Window", isAbstract = false)

    /**
     * House <>-> Room (containment, bidirectional) 
     */
    private val houseRoomsAssoc = AssociationData(
        source = AssociationEndData(
            className = "House",
            name = "rooms",
            multiplicity = MultiplicityData.many()     // 0..*
        ),
        operator = "<>->",
        target = AssociationEndData(
            className = "Room",
            name = "house",
            multiplicity = MultiplicityData.single()   // 1..1
        )
    )

    /**
     * Room --> Window (non-containment, unidirectional 0..*) 
     */
    private val roomWindowsAssoc = AssociationData(
        source = AssociationEndData(
            className = "Room",
            name = "windows",
            multiplicity = MultiplicityData.many()     // 0..*
        ),
        operator = "-->",
        target = AssociationEndData(
            className = "Window",
            name = null,                               // unidirectional
            multiplicity = MultiplicityData.optional() // not relevant
        )
    )

    private val metamodelData = MetamodelData(
        path = "/project/home.mm",
        classes = listOf(houseClass, roomClass, windowClass),
        associations = listOf(houseRoomsAssoc, roomWindowsAssoc)
    )

    // -------------------------------------------------------------------------
    // MutationRuleNameGenerator
    // -------------------------------------------------------------------------

    @Nested
    inner class NamingTests {

        @Test
        fun `forNode produces ACTION_ClassName`() {
            assertEquals("CREATE_Foo", MutationRuleNameGenerator.forNode("CREATE", "Foo"))
            assertEquals("DELETE_Foo", MutationRuleNameGenerator.forNode("DELETE", "Foo"))
        }

        @Test
        fun `forEdge produces ACTION_ClassName_refName`() {
            assertEquals("ADD_Foo_bar", MutationRuleNameGenerator.forEdge("ADD", "Foo", "bar"))
            assertEquals("REMOVE_Foo_bar", MutationRuleNameGenerator.forEdge("REMOVE", "Foo", "bar"))
        }

        @Test
        fun `forNodeCreate produces contextual name`() {
            assertEquals(
                "CREATE_Room_in_House_via_rooms",
                MutationRuleNameGenerator.forNodeCreate("Room", "House", "rooms")
            )
        }

        @Test
        fun `fromRepairSpec with LB-repair appends suffix`() {
            val s = RepairSpec("Foo", "bar", RepairSpecType.CREATE_LB_REPAIR)
            assertTrue(MutationRuleNameGenerator.fromRepairSpec(s).endsWith("_LBREPAIR"))
        }

        @Test
        fun `fromRepairSpec with node-only spec omits edge`() {
            val s = RepairSpec("Foo", null, RepairSpecType.DELETE)
            assertEquals("DELETE_Foo", MutationRuleNameGenerator.fromRepairSpec(s))
        }
    }

    // -------------------------------------------------------------------------
    // MetamodelInfo
    // -------------------------------------------------------------------------

    @Nested
    inner class MetamodelInfoTests {

        private val info = MetamodelInfo(metamodelData)

        @Test
        fun `classNames returns only non-abstract classes`() {
            val names = info.classNames()
            assertEquals(setOf("House", "Room", "Window"), names.toSet())
        }

        @Test
        fun `referencesForNode House returns rooms`() {
            val refs = info.referencesForNode("House")
            assertEquals(1, refs.size)
            val r = refs[0]
            assertEquals("rooms", r.refName)
            assertEquals("Room", r.targetClass)
            assertTrue(r.isContainment)
            assertNotNull(r.opposite)
            assertEquals(1, r.opposite.lower)
            assertEquals(1, r.opposite.upper)
        }

        @Test
        fun `referencesForNode Room returns windows and reverse house`() {
            val refs = info.referencesForNode("Room")
            assertEquals(2, refs.size)

            val windows = refs.find { it.refName == "windows" }
            assertNotNull(windows)
            assertEquals("Window", windows.targetClass)
            assertFalse(windows.isContainment)
            assertEquals(null, windows.opposite)
            assertFalse(windows.isReverse)

            val house = refs.find { it.refName == "house" }
            assertNotNull(house)
            assertEquals("House", house.targetClass)
            assertFalse(house.isContainment, "Reverse-end of containment must not be marked as containment")
            assertNotNull(house.opposite)
            assertEquals(1, house.lower)
            assertEquals(1, house.upper)
            assertTrue(house.isReverse)
        }

        @Test
        fun `referencesForNode Room reverse house has correct opposite multiplicity`() {
            val house = info.referencesForNode("Room").find { it.refName == "house" }
            assertNotNull(house)
            val opp = house.opposite!!
            assertEquals(0, opp.lower, "Opposite lower should match House.rooms lower (0)")
            assertEquals(-1, opp.upper, "Opposite upper should match House.rooms upper (0..*  = -1)")
        }

        @Test
        fun `containmentContextsFor Room returns House via rooms`() {
            val contexts = info.containmentContextsFor("Room")
            assertEquals(1, contexts.size)
            assertEquals("House" to "rooms", contexts[0])
        }

        @Test
        fun `containmentContextsFor House returns empty`() {
            assertTrue(info.containmentContextsFor("House").isEmpty())
        }
    }

    // -------------------------------------------------------------------------
    // SpecsGenerator
    // -------------------------------------------------------------------------

    @Nested
    inner class SpecsGeneratorTests {

        private val info = MetamodelInfo(metamodelData)
        private val generator = SpecsGenerator()

        @Test
        fun `ALL action on Room produces ADD and REMOVE for windows`() {
            val spec = MutationRuleSpec("Room", action = MutationAction.ALL)
            val result = generator.getRepairsForRuleSpec(spec, info)

            // Should have CREATE, DELETE, ADD, REMOVE keys
            assertTrue(result.containsKey("CREATE"))
            assertTrue(result.containsKey("DELETE"))
            assertTrue(result.containsKey("ADD"))
            assertTrue(result.containsKey("REMOVE"))
        }

        @Test
        fun `REMOVE action on Room windows produces REMOVE repair type`() {
            val spec = MutationRuleSpec("Room", edge = "windows", action = MutationAction.REMOVE)
            val result = generator.getRepairsForRuleSpec(spec, info)

            val removeSpecs = result["REMOVE"] ?: emptyList()
            assertEquals(1, removeSpecs.size)
            assertEquals(RepairSpecType.REMOVE, removeSpecs.first().type)
            assertEquals("windows", removeSpecs.first().edgeName)
        }

        @Test
        fun `reference with lower=1 upper=3 and bounded opposite produces CREATE_LB_REPAIR`() {
            // Build a local metamodel with a reference that has lower!=upper and bounded opposite
            val garageClass = ClassData(name = "Garage", isAbstract = false)
            val garagesAssoc = AssociationData(
                source = AssociationEndData(
                    className = "House",
                    name = "garages",
                    multiplicity = MultiplicityData(lower = 1, upper = 3)
                ),
                operator = "-->",
                target = AssociationEndData(
                    className = "Garage",
                    name = "owner",
                    multiplicity = MultiplicityData(lower = 0, upper = 2)
                )
            )
            val localMeta = MetamodelData(
                path = "/project/garage.mm",
                classes = listOf(houseClass, garageClass),
                associations = listOf(garagesAssoc)
            )
            val localInfo = MetamodelInfo(localMeta)
            val spec = MutationRuleSpec("House", edge = "garages", action = MutationAction.CREATE)
            val result = generator.getRepairsForRuleSpec(spec, localInfo)

            val createSpecs = result["CREATE"] ?: emptyList()
            val types = createSpecs.map { it.type }.toSet()
            assertTrue(
                types.contains(RepairSpecType.CREATE_LB_REPAIR),
                "Expected CREATE_LB_REPAIR for ref with lower=1, upper=3 and bounded opposite; got: $types"
            )
        }

        @Test
        fun `reference with lower=upper and no opposite produces SWAP not ADD`() {
            // Build a local metamodel with a reference that has lower==upper
            val garageClass = ClassData(name = "Garage", isAbstract = false)
            val mainGarageAssoc = AssociationData(
                source = AssociationEndData(
                    className = "House",
                    name = "mainGarage",
                    multiplicity = MultiplicityData(lower = 1, upper = 1)
                ),
                operator = "-->",
                target = AssociationEndData(
                    className = "Garage",
                    name = null,           // unidirectional
                    multiplicity = MultiplicityData(lower = 0, upper = 1)
                )
            )
            val localMeta = MetamodelData(
                path = "/project/garage.mm",
                classes = listOf(houseClass, garageClass),
                associations = listOf(mainGarageAssoc)
            )
            val localInfo = MetamodelInfo(localMeta)
            val spec = MutationRuleSpec("House", edge = "mainGarage", action = MutationAction.ADD)
            val result = generator.getRepairsForRuleSpec(spec, localInfo)

            val addSpecs = result["ADD"] ?: emptyList()
            val types = addSpecs.map { it.type }.toSet()
            assertTrue(
                types.contains(RepairSpecType.SWAP),
                "Expected SWAP for ref with lower==upper==1 and no opposite; got: $types"
            )
            assertFalse(
                types.contains(RepairSpecType.ADD),
                "Expected no plain ADD when lower==upper; got: $types"
            )
        }

        @Test
        fun `ADD action on windows ref produces ADD repair spec`() {
            val spec = MutationRuleSpec("Room", edge = "windows", action = MutationAction.ADD)
            val result = generator.getRepairsForRuleSpec(spec, info)

            val addSpecs = result["ADD"] ?: emptyList()
            assertTrue(addSpecs.isNotEmpty(), "Expected ADD specs for Room.windows")
            val types = addSpecs.map { it.type }.toSet()
            assertTrue(
                types.any { it == RepairSpecType.ADD || it == RepairSpecType.SWAP },
                "Expected ADD or SWAP but got: $types"
            )
        }

        @Test
        fun `REMOVE action only produces REMOVE key`() {
            val spec = MutationRuleSpec("Room", edge = "windows", action = MutationAction.REMOVE)
            val result = generator.getRepairsForRuleSpec(spec, info)

            assertTrue(result.containsKey("REMOVE"))
            assertFalse(result.containsKey("ADD"))
            assertFalse(result.containsKey("CREATE"))
            assertFalse(result.containsKey("DELETE"))
        }

        @Test
        fun `edge spec filters to specified reference only`() {
            val spec = MutationRuleSpec("Room", edge = "windows", action = MutationAction.ADD)
            val result = generator.getRepairsForRuleSpec(spec, info)

            result.values.flatten().forEach { r ->
                assertEquals("windows", r.edgeName, "Only 'windows' ref should appear in specs")
            }
        }
    }

    // -------------------------------------------------------------------------
    // MutationAstBuilder
    // -------------------------------------------------------------------------

    @Nested
    inner class MutationAstBuilderTests {

        private val info = MetamodelInfo(metamodelData)
        private val mmPath = "/project/home.mm"

        @Test
        fun `ADD rule produces match with forbid and create link`() {
            val spec = RepairSpec("Room", "windows", RepairSpecType.ADD)
            val ast = MutationAstBuilder.build("ADD_Room_windows", spec, mmPath, info)

            assertNotNull(ast)
            assertEquals(mmPath, ast.metamodelPath)
            assertEquals(1, ast.statements.size)

            val stmt = ast.statements[0] as TypedMatchStatement
            val links = stmt.pattern.elements.filterIsInstance<TypedPatternLinkElement>()
            val modifiers = links.map { it.link.modifier }.toSet()
            assertTrue(modifiers.contains("forbid"), "Expected forbid NAC")
            assertTrue(modifiers.contains("create"), "Expected create link")
        }

        @Test
        fun `REMOVE rule produces match with delete link only`() {
            val spec = RepairSpec("Room", "windows", RepairSpecType.REMOVE)
            val ast = MutationAstBuilder.build("REMOVE_Room_windows", spec, mmPath, info)

            assertNotNull(ast)
            val stmt = ast.statements[0] as TypedMatchStatement
            val links = stmt.pattern.elements.filterIsInstance<TypedPatternLinkElement>()
            val modifiers = links.map { it.link.modifier }.toSet()
            assertEquals(setOf("delete"), modifiers, "REMOVE should have exactly one delete link (no redundant match link)")
        }

        @Test
        fun `CHANGE rule produces match with delete and create links`() {
            val spec = RepairSpec("Room", "windows", RepairSpecType.CHANGE)
            val ast = MutationAstBuilder.build("CHANGE_Room_windows", spec, mmPath, info)

            assertNotNull(ast)
            val stmt = ast.statements[0] as TypedMatchStatement
            val links = stmt.pattern.elements.filterIsInstance<TypedPatternLinkElement>()
            val modifiers = links.map { it.link.modifier }.toSet()
            assertTrue(modifiers.contains("delete"))
            assertTrue(modifiers.contains("create"))
            assertTrue(modifiers.contains("forbid"))
        }

        @Test
        fun `CREATE Room produces containment rule with House container`() {
            val spec = RepairSpec("Room", null, RepairSpecType.CREATE)
            val ast = MutationAstBuilder.build(
                "CREATE_Room_in_House_via_rooms", spec, mmPath, info,
                createContext = "House" to "rooms"
            )

            assertNotNull(ast)
            val stmt = ast.statements[0] as TypedMatchStatement
            val instances = stmt.pattern.elements.filterIsInstance<TypedPatternObjectInstanceElement>()
            val created = instances.filter { it.objectInstance.modifier == "create" }
            assertEquals(1, created.size)
            assertEquals("Room", created[0].objectInstance.className)
        }

        @Test
        fun `CREATE standalone returns create-only pattern`() {
            val spec = RepairSpec("House", null, RepairSpecType.CREATE)
            val ast = MutationAstBuilder.build("CREATE_House", spec, mmPath, info, createContext = null)

            assertNotNull(ast)
            val stmt = ast.statements[0] as TypedMatchStatement
            val instances = stmt.pattern.elements.filterIsInstance<TypedPatternObjectInstanceElement>()
            assertEquals(1, instances.size)
            assertEquals("create", instances[0].objectInstance.modifier)
            assertEquals("House", instances[0].objectInstance.className)
        }

        @Test
        fun `DELETE rule produces single merged delete element`() {
            val spec = RepairSpec("Room", null, RepairSpecType.DELETE)
            val ast = MutationAstBuilder.build("DELETE_Room", spec, mmPath, info)

            assertNotNull(ast)
            val stmt = ast.statements[0] as TypedMatchStatement
            val instances = stmt.pattern.elements.filterIsInstance<TypedPatternObjectInstanceElement>()
            val matchable = instances.filter { it.objectInstance.modifier == null && it.objectInstance.name == "node" }
            val deleted   = instances.filter { it.objectInstance.modifier == "delete" && it.objectInstance.name == "node" }
            assertEquals(0, matchable.size,  "Expected no separate match-only instance for node")
            assertEquals(1, deleted.size,    "Expected one delete instance")
            assertEquals("Room", deleted[0].objectInstance.className)
        }
    }

    // -------------------------------------------------------------------------
    // MutationRuleGenerator (end-to-end)
    // -------------------------------------------------------------------------

    @Nested
    inner class MutationRuleGeneratorTests {

        @Test
        fun `generate returns non-empty list for simple spec`() {
            val specs = listOf(MutationRuleSpec("Room", action = MutationAction.ADD))
            val mutations = MutationRuleGenerator.generate(metamodelData, specs)
            assertTrue(mutations.isNotEmpty(), "Expected at least one mutation rule")
        }

        @Test
        fun `generate CREATE for node with no outgoing references produces standalone CREATE rule`() {
            // Window has no outgoing references in the fixture
            val specs = listOf(MutationRuleSpec("Window", action = MutationAction.CREATE))
            val mutations = MutationRuleGenerator.generate(metamodelData, specs)
            val names = mutations.map { it.name }.toSet()
            assertTrue(
                names.contains("CREATE_Window"),
                "Expected standalone CREATE_Window for class with no outgoing refs; got: $names"
            )
        }

        @Test
        fun `generate with ALL produces multiple rule types`() {
            val specs = listOf(MutationRuleSpec("Room", action = MutationAction.ALL))
            val mutations = MutationRuleGenerator.generate(metamodelData, specs)
            val names = mutations.map { it.name }.toSet()
            assertTrue(names.isNotEmpty())
            // Should have ADD/REMOVE/SWAP rules for windows
            assertTrue(names.any { it.startsWith("ADD") || it.startsWith("REMOVE") || it.startsWith("SWAP") },
                "Expected ADD/REMOVE/SWAP rule; got: $names")
            // Should have CREATE rules (Room has containment context via House.rooms)
            assertTrue(names.any { it.startsWith("CREATE") },
                "Expected CREATE rule; got: $names")
            // Should have DELETE rules for windows (unidirectional, no mandatory opposite)
            assertTrue(names.any { it.startsWith("DELETE") },
                "Expected DELETE rule; got: $names")
        }

        @Test
        fun `generate deduplicates rules with same name`() {
            // Two identical specs for the same node/edge
            val specs = listOf(
                MutationRuleSpec("Room", edge = "windows", action = MutationAction.ADD),
                MutationRuleSpec("Room", edge = "windows", action = MutationAction.ADD)
            )
            val mutations = MutationRuleGenerator.generate(metamodelData, specs)
            val names = mutations.map { it.name }
            assertEquals(names.distinct().size, names.size, "Expected no duplicate names")
        }

        @Test
        fun `generate skips unknown class with warning`() {
            val specs = listOf(MutationRuleSpec("NonExistent", action = MutationAction.ALL))
            val mutations = MutationRuleGenerator.generate(metamodelData, specs)
            assertTrue(mutations.isEmpty(), "Expected empty result for unknown class")
        }

        @Test
        fun `generate with empty specs returns empty list`() {
            val mutations = MutationRuleGenerator.generate(metamodelData, emptyList())
            assertTrue(mutations.isEmpty())
        }

        @Test
        fun `generate CREATE Room produces containment-context rules`() {
            val specs = listOf(MutationRuleSpec("Room", action = MutationAction.CREATE))
            val mutations = MutationRuleGenerator.generate(metamodelData, specs)
            val names = mutations.map { it.name }
            assertTrue(
                names.any { it.contains("House") && it.contains("rooms") },
                "Expected a CREATE rule for Room in House via rooms, got: $names"
            )
        }

        @Test
        fun `each generated mutation has valid TypedAst with one statement`() {
            val specs = listOf(MutationRuleSpec("Room", edge = "windows", action = MutationAction.ADD))
            val mutations = MutationRuleGenerator.generate(metamodelData, specs)
            for (m in mutations) {
                assertNotNull(m.typedAst)
                assertTrue(m.typedAst.statements.isNotEmpty(), "Rule '${m.name}' has no statements")
                assertTrue(m.typedAst.statements.all { it is TypedMatchStatement })
            }
        }

        @Test
        fun `generate ADD for Room produces operators for reverse house reference`() {
            val specs = listOf(MutationRuleSpec("Room", action = MutationAction.ADD))
            val mutations = MutationRuleGenerator.generate(metamodelData, specs)
            val names = mutations.map { it.name }.toSet()
            assertTrue(
                names.any { it.contains("house") },
                "Expected at least one operator referencing the reverse-end 'house' reference; got: $names"
            )
        }

        @Test
        fun `generate ADD for Room house reverse reference builds valid TypedAst`() {
            val specs = listOf(MutationRuleSpec("Room", edge = "house", action = MutationAction.ADD))
            val mutations = MutationRuleGenerator.generate(metamodelData, specs)
            assertTrue(mutations.isNotEmpty(), "Expected at least one mutation for Room.house")
            for (m in mutations) {
                assertNotNull(m.typedAst)
                assertTrue(m.typedAst.statements.isNotEmpty(), "Rule '${m.name}' has no statements")
                val stmt = m.typedAst.statements[0] as TypedMatchStatement
                val links = stmt.pattern.elements.filterIsInstance<TypedPatternLinkElement>()
                val link = links.first().link
                assertEquals("rooms", link.source.propertyName, "Link source should use canonical source-end property")
                assertEquals("house", link.target.propertyName, "Link target should use canonical target-end property")
            }
        }
    }

    // -------------------------------------------------------------------------
    // S-type (refinement) rule generation
    // -------------------------------------------------------------------------

    @Nested
    inner class STypeRuleGenerationTests {

        @Test
        fun `generate without refinements produces no S_ prefixed rules`() {
            val specs = listOf(MutationRuleSpec("Room", action = MutationAction.ALL))
            val mutations = MutationRuleGenerator.generate(metamodelData, specs)
            val sTypeNames = mutations.map { it.name }.filter { it.startsWith("S_") }
            assertTrue(
                sTypeNames.isEmpty(),
                "Expected no S_-prefixed rules without refinements; got: $sTypeNames"
            )
        }

        @Test
        fun `generate with refinements produces S_ prefixed rules`() {
            val specs = listOf(MutationRuleSpec("Room", edge = "windows", action = MutationAction.ADD))
            val refinements = listOf(
                RefinementConfig(className = "Room", fieldName = "windows", lower = 0, upper = 3)
            )
            val mutations = MutationRuleGenerator.generate(metamodelData, specs, refinements = refinements)
            val sTypeNames = mutations.map { it.name }.filter { it.startsWith("S_") }
            assertTrue(
                sTypeNames.isNotEmpty(),
                "Expected S_-prefixed rules when refinements provided; got: ${mutations.map { it.name }}"
            )
        }

        @Test
        fun `S_type rules reflect tightened multiplicity bounds`() {
            // Room.windows base: lower=0, upper=-1 (0..*), no opposite → ADD rule.
            // Refinement tightens to lower=1, upper=1 (1..1); lower==upper and no opposite → SWAP.
            val specs = listOf(MutationRuleSpec("Room", edge = "windows", action = MutationAction.ADD))
            val refinements = listOf(
                RefinementConfig(className = "Room", fieldName = "windows", lower = 1, upper = 1)
            )
            val mutations = MutationRuleGenerator.generate(metamodelData, specs, refinements = refinements)
            val names = mutations.map { it.name }.toSet()

            assertTrue(
                names.contains("S_SWAP_Room_windows"),
                "Expected S_SWAP_Room_windows with 1..1 refinement; got: $names"
            )
            assertFalse(
                names.contains("S_ADD_Room_windows"),
                "Expected no S_ADD_Room_windows when lower==upper; got: $names"
            )
        }

        @Test
        fun `S_type rules are merged alongside base rules`() {
            val specs = listOf(MutationRuleSpec("Room", edge = "windows", action = MutationAction.ADD))
            val refinements = listOf(
                RefinementConfig(className = "Room", fieldName = "windows", lower = 0, upper = 3)
            )
            val mutations = MutationRuleGenerator.generate(metamodelData, specs, refinements = refinements)
            val names = mutations.map { it.name }.toSet()

            assertTrue(names.any { !it.startsWith("S_") }, "Expected base rules in merged result")
            assertTrue(names.any { it.startsWith("S_") }, "Expected S_ rules in merged result")
        }

        @Test
        fun `duplicate specs with refinements yield no duplicate S_ rule names`() {
            val specs = listOf(
                MutationRuleSpec("Room", edge = "windows", action = MutationAction.ADD),
                MutationRuleSpec("Room", edge = "windows", action = MutationAction.ADD)
            )
            val refinements = listOf(
                RefinementConfig(className = "Room", fieldName = "windows", lower = 0, upper = 3)
            )
            val mutations = MutationRuleGenerator.generate(metamodelData, specs, refinements = refinements)
            val names = mutations.map { it.name }
            assertEquals(
                names.distinct().size,
                names.size,
                "Expected no duplicate rule names; got: $names"
            )
        }

        @Test
        fun `MetamodelInfo withOverrides applies lower bound override`() {
            val overrides = listOf(
                MultiplicityOverride(className = "Room", refName = "windows", lower = 2, upper = -1)
            )
            val refinedInfo = MetamodelInfo.withOverrides(metamodelData, overrides)
            val refs = refinedInfo.referencesForNode("Room")
            val windows = refs.first { it.refName == "windows" }
            assertEquals(2, windows.lower, "Expected overridden lower bound of 2")
            assertEquals(-1, windows.upper, "Expected upper bound unchanged at -1")
        }

        @Test
        fun `MetamodelInfo withOverrides leaves unrefined references unchanged`() {
            val overrides = listOf(
                MultiplicityOverride(className = "Room", refName = "windows", lower = 2, upper = 5)
            )
            val refinedInfo = MetamodelInfo.withOverrides(metamodelData, overrides)
            val baseInfo = MetamodelInfo(metamodelData)

            assertEquals(
                baseInfo.referencesForNode("House"),
                refinedInfo.referencesForNode("House"),
                "House refs must be unchanged when only Room.windows is overridden"
            )
        }
    }

    // -------------------------------------------------------------------------
    // Multiplicity guard integration tests
    // -------------------------------------------------------------------------

    /**
     * Tests that [MutationAstBuilder] adds (or omits) multiplicity-guard `where` clauses
     * on generated rules based on reference upper/lower bounds, containment context,
     * bidirectionality, and spec type.
     */
    @Nested
    inner class MultiplicityGuardTests {

        private val mmPath = "/project/home.mm"
        private val info = MetamodelInfo(metamodelData)

        @Test
        fun `ADD rule with bounded upper multiplicity contains upper-bound where clause`() {
            val garageClass = ClassData(name = "Garage", isAbstract = false)
            val assoc = AssociationData(
                source = AssociationEndData(
                    className = "House",
                    name = "garages",
                    multiplicity = MultiplicityData(lower = 0, upper = 3)
                ),
                operator = "-->",
                target = AssociationEndData(
                    className = "Garage",
                    name = null,
                    multiplicity = MultiplicityData(lower = 0, upper = 1)
                )
            )
            val localMeta = MetamodelData(
                path = "/project/garage.mm",
                classes = listOf(houseClass, garageClass),
                associations = listOf(assoc)
            )
            val localInfo = MetamodelInfo(localMeta)
            val spec = RepairSpec("House", "garages", RepairSpecType.ADD)
            val ast = MutationAstBuilder.build("ADD_House_garages", spec, "/project/garage.mm", localInfo)

            assertNotNull(ast)
            val stmt = ast.statements[0] as TypedMatchStatement
            val whereClauses = stmt.pattern.elements.filterIsInstance<TypedPatternWhereClauseElement>()
            assertEquals(1, whereClauses.size, "Expected exactly one where clause for bounded upper")

            val expr = whereClauses[0].whereClause.expression as TypedBinaryExpression
            assertEquals("<", expr.operator)
            val sizeCall = expr.left as TypedMemberCallExpression
            assertEquals("size", sizeCall.member)
            val memberAccess = sizeCall.expression as TypedMemberAccessExpression
            assertEquals("garages", memberAccess.member)
            val identifier = memberAccess.expression as TypedIdentifierExpression
            assertEquals("source", identifier.name)
            val bound = expr.right as TypedIntLiteralExpression
            assertEquals("3", bound.value)
        }

        @Test
        fun `ADD rule with unbounded upper multiplicity has no where clause`() {
            val spec = RepairSpec("Room", "windows", RepairSpecType.ADD)
            val ast = MutationAstBuilder.build("ADD_Room_windows", spec, mmPath, info)

            assertNotNull(ast)
            val stmt = ast.statements[0] as TypedMatchStatement
            val whereClauses = stmt.pattern.elements.filterIsInstance<TypedPatternWhereClauseElement>()
            assertTrue(whereClauses.isEmpty(), "Expected no where clause for unbounded upper (-1)")
        }

        @Test
        fun `REMOVE rule with positive lower bound contains lower-bound where clause`() {
            val partClass = ClassData(name = "Part", isAbstract = false)
            val assoc = AssociationData(
                source = AssociationEndData(
                    className = "House",
                    name = "parts",
                    multiplicity = MultiplicityData(lower = 2, upper = -1)
                ),
                operator = "-->",
                target = AssociationEndData(
                    className = "Part",
                    name = null,
                    multiplicity = MultiplicityData(lower = 0, upper = 1)
                )
            )
            val localMeta = MetamodelData(
                path = "/project/parts.mm",
                classes = listOf(houseClass, partClass),
                associations = listOf(assoc)
            )
            val localInfo = MetamodelInfo(localMeta)
            val spec = RepairSpec("House", "parts", RepairSpecType.REMOVE)
            val ast = MutationAstBuilder.build("REMOVE_House_parts", spec, "/project/parts.mm", localInfo)

            assertNotNull(ast)
            val stmt = ast.statements[0] as TypedMatchStatement
            val whereClauses = stmt.pattern.elements.filterIsInstance<TypedPatternWhereClauseElement>()
            assertEquals(1, whereClauses.size, "Expected exactly one where clause for lower=2")

            val expr = whereClauses[0].whereClause.expression as TypedBinaryExpression
            assertEquals(">", expr.operator)
            val sizeCall = expr.left as TypedMemberCallExpression
            assertEquals("size", sizeCall.member)
            val memberAccess = sizeCall.expression as TypedMemberAccessExpression
            assertEquals("parts", memberAccess.member)
            val identifier = memberAccess.expression as TypedIdentifierExpression
            assertEquals("source", identifier.name)
            val bound = expr.right as TypedIntLiteralExpression
            assertEquals("2", bound.value)
        }

        @Test
        fun `REMOVE rule with lower=0 has no where clause`() {
            val spec = RepairSpec("Room", "windows", RepairSpecType.REMOVE)
            val ast = MutationAstBuilder.build("REMOVE_Room_windows", spec, mmPath, info)

            assertNotNull(ast)
            val stmt = ast.statements[0] as TypedMatchStatement
            val whereClauses = stmt.pattern.elements.filterIsInstance<TypedPatternWhereClauseElement>()
            assertTrue(whereClauses.isEmpty(), "Expected no where clause when lower=0")
        }

        @Test
        fun `CHANGE rule with bounded opposite produces guards on opposite reference`() {
            val nodeClass = ClassData(name = "Node", isAbstract = false)
            val edgeClass = ClassData(name = "Edge", isAbstract = false)
            val assoc = AssociationData(
                source = AssociationEndData(
                    className = "Node",
                    name = "edges",
                    multiplicity = MultiplicityData(lower = 0, upper = -1)
                ),
                operator = "-->",
                target = AssociationEndData(
                    className = "Edge",
                    name = "owner",
                    multiplicity = MultiplicityData(lower = 1, upper = 2)
                )
            )
            val localMeta = MetamodelData(
                path = "/project/graph.mm",
                classes = listOf(nodeClass, edgeClass),
                associations = listOf(assoc)
            )
            val localInfo = MetamodelInfo(localMeta)
            val spec = RepairSpec("Node", "edges", RepairSpecType.CHANGE)
            val ast = MutationAstBuilder.build("CHANGE_Node_edges", spec, "/project/graph.mm", localInfo)

            assertNotNull(ast)
            val stmt = ast.statements[0] as TypedMatchStatement
            val whereClauses = stmt.pattern.elements.filterIsInstance<TypedPatternWhereClauseElement>()
            assertEquals(2, whereClauses.size, "Expected two where clauses: upper-bound on newTarget, lower-bound on oldTarget")

            val upperGuard = whereClauses[0].whereClause.expression as TypedBinaryExpression
            assertEquals("<", upperGuard.operator)
            val upperSizeCall = upperGuard.left as TypedMemberCallExpression
            assertEquals("size", upperSizeCall.member)
            val upperAccess = upperSizeCall.expression as TypedMemberAccessExpression
            assertEquals("owner", upperAccess.member)
            val upperIdent = upperAccess.expression as TypedIdentifierExpression
            assertEquals("newTarget", upperIdent.name)
            val upperBound = upperGuard.right as TypedIntLiteralExpression
            assertEquals("2", upperBound.value)

            val lowerGuard = whereClauses[1].whereClause.expression as TypedBinaryExpression
            assertEquals(">", lowerGuard.operator)
            val lowerSizeCall = lowerGuard.left as TypedMemberCallExpression
            assertEquals("size", lowerSizeCall.member)
            val lowerAccess = lowerSizeCall.expression as TypedMemberAccessExpression
            assertEquals("owner", lowerAccess.member)
            val lowerIdent = lowerAccess.expression as TypedIdentifierExpression
            assertEquals("oldTarget", lowerIdent.name)
            val lowerBound = lowerGuard.right as TypedIntLiteralExpression
            assertEquals("1", lowerBound.value)
        }

        @Test
        fun `SWAP rule has no multiplicity guards even with bounded multiplicities`() {
            val nodeClass = ClassData(name = "Node", isAbstract = false)
            val edgeClass = ClassData(name = "Edge", isAbstract = false)
            val assoc = AssociationData(
                source = AssociationEndData(
                    className = "Node",
                    name = "edges",
                    multiplicity = MultiplicityData(lower = 1, upper = 3)
                ),
                operator = "-->",
                target = AssociationEndData(
                    className = "Edge",
                    name = "owner",
                    multiplicity = MultiplicityData(lower = 1, upper = 2)
                )
            )
            val localMeta = MetamodelData(
                path = "/project/graph.mm",
                classes = listOf(nodeClass, edgeClass),
                associations = listOf(assoc)
            )
            val localInfo = MetamodelInfo(localMeta)
            val spec = RepairSpec("Node", "edges", RepairSpecType.SWAP)
            val ast = MutationAstBuilder.build("SWAP_Node_edges", spec, "/project/graph.mm", localInfo)

            assertNotNull(ast)
            val stmt = ast.statements[0] as TypedMatchStatement
            val whereClauses = stmt.pattern.elements.filterIsInstance<TypedPatternWhereClauseElement>()
            assertTrue(whereClauses.isEmpty(), "SWAP must not add where clauses (cardinality preserved)")
        }

        @Test
        fun `CREATE rule with containment upper bound has where clause on container`() {
            val boxClass = ClassData(name = "Box", isAbstract = false)
            val itemClass = ClassData(name = "Item", isAbstract = false)
            val assoc = AssociationData(
                source = AssociationEndData(
                    className = "Box",
                    name = "items",
                    multiplicity = MultiplicityData(lower = 0, upper = 5)
                ),
                operator = "<>->",
                target = AssociationEndData(
                    className = "Item",
                    name = "box",
                    multiplicity = MultiplicityData(lower = 1, upper = 1)
                )
            )
            val localMeta = MetamodelData(
                path = "/project/box.mm",
                classes = listOf(boxClass, itemClass),
                associations = listOf(assoc)
            )
            val localInfo = MetamodelInfo(localMeta)
            val spec = RepairSpec("Item", null, RepairSpecType.CREATE)
            val ast = MutationAstBuilder.build(
                "CREATE_Item_in_Box_via_items", spec, "/project/box.mm", localInfo,
                createContext = "Box" to "items"
            )

            assertNotNull(ast)
            val stmt = ast.statements[0] as TypedMatchStatement
            val whereClauses = stmt.pattern.elements.filterIsInstance<TypedPatternWhereClauseElement>()
            assertEquals(1, whereClauses.size, "Expected where clause for containment upper=5")

            val expr = whereClauses[0].whereClause.expression as TypedBinaryExpression
            assertEquals("<", expr.operator)
            val sizeCall = expr.left as TypedMemberCallExpression
            assertEquals("size", sizeCall.member)
            val memberAccess = sizeCall.expression as TypedMemberAccessExpression
            assertEquals("items", memberAccess.member)
            val identifier = memberAccess.expression as TypedIdentifierExpression
            assertEquals("container", identifier.name)
            val bound = expr.right as TypedIntLiteralExpression
            assertEquals("5", bound.value)
        }

        @Test
        fun `CREATE standalone has no multiplicity guards`() {
            val spec = RepairSpec("House", null, RepairSpecType.CREATE)
            val ast = MutationAstBuilder.build("CREATE_House", spec, mmPath, info, createContext = null)

            assertNotNull(ast)
            val stmt = ast.statements[0] as TypedMatchStatement
            val whereClauses = stmt.pattern.elements.filterIsInstance<TypedPatternWhereClauseElement>()
            assertTrue(whereClauses.isEmpty(), "Standalone CREATE must have no where clauses")
        }

        @Test
        fun `types array is populated with builtin types and metamodel class types`() {
            val garageClass = ClassData(name = "Garage", isAbstract = false)
            val assoc = AssociationData(
                source = AssociationEndData(
                    className = "House",
                    name = "garages",
                    multiplicity = MultiplicityData(lower = 0, upper = 3)
                ),
                operator = "-->",
                target = AssociationEndData(
                    className = "Garage",
                    name = null,
                    multiplicity = MultiplicityData(lower = 0, upper = 1)
                )
            )
            val localMeta = MetamodelData(
                path = "/project/garage.mm",
                classes = listOf(houseClass, garageClass),
                associations = listOf(assoc)
            )
            val localInfo = MetamodelInfo(localMeta)
            val spec = RepairSpec("House", "garages", RepairSpecType.ADD)
            val ast = MutationAstBuilder.build("ADD_House_garages", spec, "/project/garage.mm", localInfo)

            assertNotNull(ast)
            assertTrue(ast.types.size >= 6, "Expected at least 6 builtin types")
            assertTrue(ast.types[0] is VoidType, "Index 0 must be VoidType")
            assertTrue(ast.types[1] is ClassTypeRef, "Index 1 must be ClassTypeRef")
            assertEquals("string", (ast.types[1] as ClassTypeRef).type)
            assertEquals("int", (ast.types[5] as ClassTypeRef).type)

            val classRefs = ast.types.filterIsInstance<ClassTypeRef>()
            assertTrue(
                classRefs.any { it.type == "House" && it.`package`.contains("/project/garage.mm") },
                "Expected House class type in types array"
            )
            assertTrue(
                classRefs.any { it.type == "Garage" && it.`package`.contains("/project/garage.mm") },
                "Expected Garage class type in types array"
            )
            assertTrue(
                classRefs.any { it.type == "List" && it.typeArgs?.containsKey("T") == true },
                "Expected List<Garage> type in types array"
            )
        }
    }

    // -------------------------------------------------------------------------
    // MultiplicityGuardBuilder unit tests
    // -------------------------------------------------------------------------

    /**
     * Unit tests for [MultiplicityGuardBuilder] verifying expression tree structure,
     * type index management, and guard semantics.
     */
    @Nested
    inner class MultiplicityGuardBuilderTests {

        @Test
        fun `buildUpperBoundGuard produces correct expression tree`() {
            val builder = MultiplicityGuardBuilder("/test/meta.mm")
            val guard = builder.buildUpperBoundGuard(
                varName = "source",
                varClassName = "Node",
                refName = "edges",
                targetClassName = "Edge",
                upperBound = 5
            )

            val expr = guard.whereClause.expression as TypedBinaryExpression
            assertEquals("<", expr.operator)
            assertEquals(MultiplicityGuardBuilder.BOOLEAN_INDEX, expr.evalType)

            val sizeCall = expr.left as TypedMemberCallExpression
            assertEquals("size", sizeCall.member)
            assertEquals(MultiplicityGuardBuilder.INT_INDEX, sizeCall.evalType)
            assertTrue(sizeCall.arguments.isEmpty())

            val memberAccess = sizeCall.expression as TypedMemberAccessExpression
            assertEquals("edges", memberAccess.member)
            assertFalse(memberAccess.isNullChaining)

            val identifier = memberAccess.expression as TypedIdentifierExpression
            assertEquals("source", identifier.name)
            assertEquals(1, identifier.scope)

            val bound = expr.right as TypedIntLiteralExpression
            assertEquals("5", bound.value)
            assertEquals(MultiplicityGuardBuilder.INT_INDEX, bound.evalType)
        }

        @Test
        fun `buildLowerBoundGuard produces correct expression tree`() {
            val builder = MultiplicityGuardBuilder("/test/meta.mm")
            val guard = builder.buildLowerBoundGuard(
                varName = "container",
                varClassName = "Box",
                refName = "items",
                targetClassName = "Item",
                lowerBound = 3
            )

            val expr = guard.whereClause.expression as TypedBinaryExpression
            assertEquals(">", expr.operator)
            assertEquals(MultiplicityGuardBuilder.BOOLEAN_INDEX, expr.evalType)

            val sizeCall = expr.left as TypedMemberCallExpression
            assertEquals("size", sizeCall.member)

            val memberAccess = sizeCall.expression as TypedMemberAccessExpression
            assertEquals("items", memberAccess.member)

            val identifier = memberAccess.expression as TypedIdentifierExpression
            assertEquals("container", identifier.name)

            val bound = expr.right as TypedIntLiteralExpression
            assertEquals("3", bound.value)
        }

        @Test
        fun `getOrAddClassType registers metamodel class with correct package`() {
            val builder = MultiplicityGuardBuilder("/project/test.mm")
            val idx = builder.getOrAddClassType("Widget")

            assertTrue(idx >= 6, "Class type index must be beyond builtin slots")
            val types = builder.getTypes()
            val classType = types[idx] as ClassTypeRef
            assertEquals("Widget", classType.type)
            assertEquals("class/project/test.mm", classType.`package`)
            assertFalse(classType.isNullable)
        }

        @Test
        fun `getOrAddClassType returns same index for duplicate registration`() {
            val builder = MultiplicityGuardBuilder("/project/test.mm")
            val first = builder.getOrAddClassType("Widget")
            val second = builder.getOrAddClassType("Widget")
            assertEquals(first, second, "Duplicate class type must reuse the same index")
            assertEquals(7, builder.getTypes().size, "No duplicate entry should be added")
        }

        @Test
        fun `getOrAddListType registers List type with correct typeArgs`() {
            val builder = MultiplicityGuardBuilder("/project/test.mm")
            val listIdx = builder.getOrAddListType("Edge")

            assertTrue(listIdx >= 7, "List type index must be after class type")
            val types = builder.getTypes()
            val listType = types[listIdx] as ClassTypeRef
            assertEquals("List", listType.type)
            assertEquals("builtin", listType.`package`)
            assertFalse(listType.isNullable)

            val typeArg = listType.typeArgs!!["T"] as ClassTypeRef
            assertEquals("Edge", typeArg.type)
            assertEquals("class/project/test.mm", typeArg.`package`)
        }

        @Test
        fun `getTypes returns snapshot with all builtin entries`() {
            val builder = MultiplicityGuardBuilder("/test.mm")
            val types = builder.getTypes()

            assertEquals(6, types.size, "Fresh builder must have exactly 6 builtin types")
            assertTrue(types[0] is VoidType)
            assertEquals("string", (types[1] as ClassTypeRef).type)
            assertEquals("double", (types[2] as ClassTypeRef).type)
            assertEquals("boolean", (types[3] as ClassTypeRef).type)
            assertEquals("Any", (types[4] as ClassTypeRef).type)
            assertTrue((types[4] as ClassTypeRef).isNullable)
            assertEquals("int", (types[5] as ClassTypeRef).type)
        }

        @Test
        fun `upper bound guard type indices reference correct entries`() {
            val builder = MultiplicityGuardBuilder("/test/meta.mm")
            val guard = builder.buildUpperBoundGuard(
                varName = "src",
                varClassName = "Alpha",
                refName = "betas",
                targetClassName = "Beta",
                upperBound = 10
            )

            val types = builder.getTypes()
            val expr = guard.whereClause.expression as TypedBinaryExpression
            val sizeCall = expr.left as TypedMemberCallExpression
            val memberAccess = sizeCall.expression as TypedMemberAccessExpression
            val identifier = memberAccess.expression as TypedIdentifierExpression

            val alphaType = types[identifier.evalType] as ClassTypeRef
            assertEquals("Alpha", alphaType.type)

            val listType = types[memberAccess.evalType] as ClassTypeRef
            assertEquals("List", listType.type)
            val listElementType = listType.typeArgs!!["T"] as ClassTypeRef
            assertEquals("Beta", listElementType.type)

            assertEquals(MultiplicityGuardBuilder.INT_INDEX, sizeCall.evalType)
            assertEquals(MultiplicityGuardBuilder.BOOLEAN_INDEX, expr.evalType)
        }
    }

    // -------------------------------------------------------------------------
    // DeleteGuardTests
    // -------------------------------------------------------------------------

    /**
     * Tests for the multiplicity guards added to DELETE rules.
     *
     * When a node is deleted all edges connected to it are removed. Guards are
     * added for every reference whose opposite end has a positive lower bound, to
     * prevent deletion when a neighbour would drop below its required minimum.
     */
    @Nested
    inner class DeleteGuardTests {

        @Test
        fun `DELETE rule with opposite lower=1 adds neighbor where-clause guard`() {
            val assignmentClass = ClassData(name = "Assignment", isAbstract = false)
            val taskClass       = ClassData(name = "Task",       isAbstract = false)
            val assoc = AssociationData(
                source = AssociationEndData(
                    className = "Assignment",
                    name = "task",
                    multiplicity = MultiplicityData.optional()
                ),
                operator = "-->",
                target = AssociationEndData(
                    className = "Task",
                    name = "assignments",
                    multiplicity = MultiplicityData.oneOrMore()
                )
            )
            val localMeta = MetamodelData(
                path = "/project/assign.mm",
                classes = listOf(assignmentClass, taskClass),
                associations = listOf(assoc)
            )
            val localInfo = MetamodelInfo(localMeta)
            val spec = RepairSpec("Assignment", null, RepairSpecType.DELETE)
            val ast = MutationAstBuilder.build("DELETE_Assignment", spec, "/project/assign.mm", localInfo)

            assertNotNull(ast)
            val stmt = ast.statements[0] as TypedMatchStatement

            val whereClauses = stmt.pattern.elements.filterIsInstance<TypedPatternWhereClauseElement>()
            assertEquals(1, whereClauses.size, "Expected exactly one where clause for opposite lower=1")

            val expr = whereClauses[0].whereClause.expression as TypedBinaryExpression
            assertEquals(">", expr.operator)
            val sizeCall = expr.left as TypedMemberCallExpression
            assertEquals("size", sizeCall.member)
            val memberAccess = sizeCall.expression as TypedMemberAccessExpression
            assertEquals("assignments", memberAccess.member)
            val identifier = memberAccess.expression as TypedIdentifierExpression
            assertEquals("neighbor_task", identifier.name)
            val bound = expr.right as TypedIntLiteralExpression
            assertEquals("1", bound.value)

            val links = stmt.pattern.elements.filterIsInstance<TypedPatternLinkElement>()
            val guardLink = links.find { it.link.source.objectName == "node" && it.link.source.propertyName == "task" }
            assertNotNull(guardLink, "Expected a link element for node.task -- neighbor_task")
            assertEquals(null, guardLink.link.modifier, "Guard link must be a match (modifier=null)")
            assertEquals("neighbor_task", guardLink.link.target.objectName)

            val objects = stmt.pattern.elements.filterIsInstance<TypedPatternObjectInstanceElement>()
            val neighborObj = objects.find { it.objectInstance.name == "neighbor_task" }
            assertNotNull(neighborObj, "Expected a match object element for neighbor_task")
            assertEquals("Task", neighborObj.objectInstance.className)
        }

        @Test
        fun `DELETE rule with no opposite references has no guards`() {
            val info = MetamodelInfo(metamodelData)
            val spec = RepairSpec("Room", null, RepairSpecType.DELETE)
            val ast = MutationAstBuilder.build("DELETE_Room", spec, "/project/home.mm", info)

            assertNotNull(ast)
            val stmt = ast.statements[0] as TypedMatchStatement
            val whereClauses = stmt.pattern.elements.filterIsInstance<TypedPatternWhereClauseElement>()
            assertTrue(whereClauses.isEmpty(), "Expected no where clauses when all refs are unidirectional or opposite lower=0")

            val objects = stmt.pattern.elements.filterIsInstance<TypedPatternObjectInstanceElement>()
            val nodeObj = objects.find { it.objectInstance.name == "node" && it.objectInstance.className == "Room" }
            assertNotNull(nodeObj, "Expected a match element for node: Room")
            val deleteMarker = objects.find { it.objectInstance.name == "node" && it.objectInstance.modifier == "delete" }
            assertNotNull(deleteMarker, "Expected a delete marker for node")
            assertEquals("Room", deleteMarker.objectInstance.className, "Delete marker should carry className")
        }

        @Test
        fun `DELETE rule with opposite lower=0 has no guard for that ref`() {
            val roomClass2  = ClassData(name = "Room2",  isAbstract = false)
            val houseClass2 = ClassData(name = "House2", isAbstract = false)
            val assoc = AssociationData(
                source = AssociationEndData(
                    className = "House2",
                    name = "rooms",
                    multiplicity = MultiplicityData.many()
                ),
                operator = "-->",
                target = AssociationEndData(
                    className = "Room2",
                    name = "house",
                    multiplicity = MultiplicityData.many()
                )
            )
            val localMeta = MetamodelData(
                path = "/project/house2.mm",
                classes = listOf(houseClass2, roomClass2),
                associations = listOf(assoc)
            )
            val localInfo = MetamodelInfo(localMeta)
            val spec = RepairSpec("Room2", null, RepairSpecType.DELETE)
            val ast = MutationAstBuilder.build("DELETE_Room2", spec, "/project/house2.mm", localInfo)

            assertNotNull(ast)
            val stmt = ast.statements[0] as TypedMatchStatement
            val whereClauses = stmt.pattern.elements.filterIsInstance<TypedPatternWhereClauseElement>()
            assertTrue(whereClauses.isEmpty(), "Expected no where clause when opposite.lower=0")
        }

        @Test
        fun `DELETE rule neighbor object and link are MATCH elements (modifier=null)`() {
            val assignmentClass = ClassData(name = "Assignment", isAbstract = false)
            val taskClass       = ClassData(name = "Task",       isAbstract = false)
            val assoc = AssociationData(
                source = AssociationEndData(
                    className = "Assignment",
                    name = "task",
                    multiplicity = MultiplicityData.optional()
                ),
                operator = "-->",
                target = AssociationEndData(
                    className = "Task",
                    name = "assignments",
                    multiplicity = MultiplicityData.oneOrMore()
                )
            )
            val localMeta = MetamodelData(
                path = "/project/assign.mm",
                classes = listOf(assignmentClass, taskClass),
                associations = listOf(assoc)
            )
            val localInfo = MetamodelInfo(localMeta)
            val spec = RepairSpec("Assignment", null, RepairSpecType.DELETE)
            val ast = MutationAstBuilder.build("DELETE_Assignment", spec, "/project/assign.mm", localInfo)

            assertNotNull(ast)
            val stmt = ast.statements[0] as TypedMatchStatement

            val objects = stmt.pattern.elements.filterIsInstance<TypedPatternObjectInstanceElement>()
            val neighborObj = objects.find { it.objectInstance.name == "neighbor_task" }
            assertNotNull(neighborObj, "Expected neighbor_task object element")
            assertEquals(null, neighborObj.objectInstance.modifier, "Neighbor must be a match (modifier=null)")

            val links = stmt.pattern.elements.filterIsInstance<TypedPatternLinkElement>()
            val guardLink = links.find { it.link.source.propertyName == "task" }
            assertNotNull(guardLink, "Expected a link element for task ref")
            assertEquals(null, guardLink.link.modifier, "Guard link must be a match (modifier=null)")
        }

        @Test
        fun `DELETE rule with multiple guarded references adds a guard per reference`() {
            val orderClass    = ClassData(name = "Order",    isAbstract = false)
            val customerClass = ClassData(name = "Customer", isAbstract = false)
            val productClass  = ClassData(name = "Product",  isAbstract = false)
            val customerAssoc = AssociationData(
                source = AssociationEndData(
                    className = "Order",
                    name = "customer",
                    multiplicity = MultiplicityData.optional()
                ),
                operator = "-->",
                target = AssociationEndData(
                    className = "Customer",
                    name = "orders",
                    multiplicity = MultiplicityData.oneOrMore()
                )
            )
            val productAssoc = AssociationData(
                source = AssociationEndData(
                    className = "Order",
                    name = "product",
                    multiplicity = MultiplicityData.optional()
                ),
                operator = "-->",
                target = AssociationEndData(
                    className = "Product",
                    name = "purchases",
                    multiplicity = MultiplicityData(lower = 2, upper = -1)
                )
            )
            val localMeta = MetamodelData(
                path = "/project/order.mm",
                classes = listOf(orderClass, customerClass, productClass),
                associations = listOf(customerAssoc, productAssoc)
            )
            val localInfo = MetamodelInfo(localMeta)
            val spec = RepairSpec("Order", null, RepairSpecType.DELETE)
            val ast = MutationAstBuilder.build("DELETE_Order", spec, "/project/order.mm", localInfo)

            assertNotNull(ast)
            val stmt = ast.statements[0] as TypedMatchStatement

            val whereClauses = stmt.pattern.elements.filterIsInstance<TypedPatternWhereClauseElement>()
            assertEquals(2, whereClauses.size, "Expected two where clauses for two guarded refs")

            val guardExprs = whereClauses.map { it.whereClause.expression as TypedBinaryExpression }

            val customerGuard = guardExprs.find { expr ->
                val sizeCall = expr.left as TypedMemberCallExpression
                val memberAccess = sizeCall.expression as TypedMemberAccessExpression
                memberAccess.member == "orders"
            }
            assertNotNull(customerGuard, "Expected a guard for Customer.orders")
            val customerIdent = ((customerGuard.left as TypedMemberCallExpression).expression as TypedMemberAccessExpression).expression as TypedIdentifierExpression
            assertEquals("neighbor_customer", customerIdent.name)
            assertEquals("1", (customerGuard.right as TypedIntLiteralExpression).value)

            val productGuard = guardExprs.find { expr ->
                val sizeCall = expr.left as TypedMemberCallExpression
                val memberAccess = sizeCall.expression as TypedMemberAccessExpression
                memberAccess.member == "purchases"
            }
            assertNotNull(productGuard, "Expected a guard for Product.purchases")
            val productIdent = ((productGuard.left as TypedMemberCallExpression).expression as TypedMemberAccessExpression).expression as TypedIdentifierExpression
            assertEquals("neighbor_product", productIdent.name)
            assertEquals("2", (productGuard.right as TypedIntLiteralExpression).value)

            val objects = stmt.pattern.elements.filterIsInstance<TypedPatternObjectInstanceElement>()
            val neighborObjects = objects.filter { it.objectInstance.modifier == null && it.objectInstance.name.startsWith("neighbor_") }
            assertEquals(2, neighborObjects.size, "Expected two neighbor match object instances")

            val links = stmt.pattern.elements.filterIsInstance<TypedPatternLinkElement>()
            val guardLinks = links.filter { it.link.modifier == null }
            assertEquals(2, guardLinks.size, "Expected two guard link match elements")
        }
    }

    // -------------------------------------------------------------------------
    // Link direction correctness for forward vs. reverse references
    // -------------------------------------------------------------------------

    /**
     * Tests that generated TypedPatternLinks always carry both property names
     * (source end and target end of the metamodel association) and that the link is
     * always emitted in the canonical metamodel direction regardless of whether
     * the MutationRuleSpec names the forward or the reverse end.
     *
     * The reference association used throughout this suite is:
     *   Item.knapsack <--> Knapsack.items [0..*]
     * stored as assoc.source = Item/knapsack and assoc.target = Knapsack/items.
     *
     * Bug: MutationAstBuilder previously always set target.propertyName = null,
     * producing an edge label that never matched any graph edge.
     */
    @Nested
    inner class LinkDirectionTests {

        private val knapsackClass = ClassData(name = "Knapsack", isAbstract = false)
        private val itemClass     = ClassData(name = "Item",     isAbstract = false)

        private val itemKnapsackAssoc = AssociationData(
            source   = AssociationEndData("Item",     "knapsack", MultiplicityData(lower = 1, upper = 1)),
            operator = "<-->",
            target   = AssociationEndData("Knapsack", "items",    MultiplicityData(lower = 0, upper = -1))
        )

        private val knapsackMeta = MetamodelData(
            path         = "/project/knapsack.mm",
            classes      = listOf(knapsackClass, itemClass),
            associations = listOf(itemKnapsackAssoc)
        )

        private val info   = MetamodelInfo(knapsackMeta)
        private val mmPath = "/project/knapsack.mm"

        private fun linksOf(spec: RepairSpec): List<TypedPatternLinkElement> {
            val ast  = MutationAstBuilder.build(spec.edgeName ?: "rule", spec, mmPath, info)
            val stmt = ast.statements[0] as TypedMatchStatement
            return stmt.pattern.elements.filterIsInstance<TypedPatternLinkElement>()
        }

        @Test
        fun `forward ref Item knapsack links have both property names set`() {
            val links = linksOf(RepairSpec("Item", "knapsack", RepairSpecType.CHANGE))
            assertTrue(links.isNotEmpty())
            for (link in links) {
                assertNotNull(link.link.source.propertyName, "source.propertyName null (modifier=${link.link.modifier})")
                assertNotNull(link.link.target.propertyName, "target.propertyName null (modifier=${link.link.modifier})")
            }
        }

        @Test
        fun `reverse ref Knapsack items links have both property names set`() {
            val links = linksOf(RepairSpec("Knapsack", "items", RepairSpecType.ADD))
            assertTrue(links.isNotEmpty())
            for (link in links) {
                assertNotNull(link.link.source.propertyName, "source.propertyName null (modifier=${link.link.modifier})")
                assertNotNull(link.link.target.propertyName, "target.propertyName null (modifier=${link.link.modifier})")
            }
        }

        @Test
        fun `forward ref Item knapsack links use canonical association direction`() {
            val links = linksOf(RepairSpec("Item", "knapsack", RepairSpecType.CHANGE))
            for (link in links) {
                assertEquals("knapsack", link.link.source.propertyName, "Forward: source must be 'knapsack'")
                assertEquals("items",    link.link.target.propertyName, "Forward: target must be 'items'")
            }
        }

        @Test
        fun `reverse ref Knapsack items links use canonical association direction`() {
            val links = linksOf(RepairSpec("Knapsack", "items", RepairSpecType.ADD))
            for (link in links) {
                assertEquals("knapsack", link.link.source.propertyName, "Reverse: source must be canonical 'knapsack'")
                assertEquals("items",    link.link.target.propertyName, "Reverse: target must be canonical 'items'")
            }
        }

        @Test
        fun `reverse ref Knapsack items links map objects to canonical direction`() {
            val links = linksOf(RepairSpec("Knapsack", "items", RepairSpecType.ADD))
            for (link in links) {
                assertEquals("target", link.link.source.objectName, "Reverse: canonical source object should be target (Item)")
                assertEquals("source", link.link.target.objectName, "Reverse: canonical target object should be source (Knapsack)")
                assertEquals("knapsack", link.link.source.propertyName)
                assertEquals("items", link.link.target.propertyName)
            }
        }

        @Test
        fun `link property orientation is independent of chosen endpoint`() {
            val forwardPairs = linksOf(RepairSpec("Item", "knapsack", RepairSpecType.REMOVE))
                .map { it.link.source.propertyName to it.link.target.propertyName }
                .toSet()
            val reversePairs = linksOf(RepairSpec("Knapsack", "items", RepairSpecType.REMOVE))
                .map { it.link.source.propertyName to it.link.target.propertyName }
                .toSet()

            assertEquals(
                setOf("knapsack" to "items"),
                forwardPairs,
                "Forward endpoint must still emit canonical (source,target) property pair"
            )
            assertEquals(
                setOf("knapsack" to "items"),
                reversePairs,
                "Reverse endpoint must emit the same canonical (source,target) property pair"
            )
        }

        @Test
        fun `REMOVE forward ref Item knapsack links have both property names set`() {
            val links = linksOf(RepairSpec("Item", "knapsack", RepairSpecType.REMOVE))
            assertTrue(links.isNotEmpty())
            for (link in links) {
                assertNotNull(link.link.source.propertyName, "source.propertyName null (modifier=${link.link.modifier})")
                assertNotNull(link.link.target.propertyName, "target.propertyName null (modifier=${link.link.modifier})")
            }
        }

        @Test
        fun `REMOVE reverse ref Knapsack items links have both property names set`() {
            val links = linksOf(RepairSpec("Knapsack", "items", RepairSpecType.REMOVE))
            assertTrue(links.isNotEmpty())
            for (link in links) {
                assertNotNull(link.link.source.propertyName, "source.propertyName null (modifier=${link.link.modifier})")
                assertNotNull(link.link.target.propertyName, "target.propertyName null (modifier=${link.link.modifier})")
            }
        }
    }
}

// ============================================================================
// New CPO variant tests (CREATE_LB_REPAIR_MULTI, DELETE_REPAIR_SINGLE/MULTI)
// ============================================================================

/**
 * Metamodel for multi-donor / delete-repair tests:
 *
 *   ClassA  -->  ClassB  (A.roles  2..4, B.members 0..2)
 *   Worker  -->  Project (W.project 0..*, P.worker  1..1)
 *   Team    -->  Player  (T.members 0..*, P.teams   2..2)
 */
class NewCpoVariantTests {

    private val classAData = ClassData(name = "ClassA", isAbstract = false)
    private val classBData = ClassData(name = "ClassB", isAbstract = false)

    /** A.roles 2..4 → B.members 0..2  (lower=2 → multi-donor CREATE repair) */
    private val rolesAssoc = AssociationData(
        source = AssociationEndData("ClassA", "roles",   MultiplicityData(lower = 2, upper = 4)),
        operator = "-->",
        target  = AssociationEndData("ClassB", "members", MultiplicityData(lower = 0, upper = 2))
    )
    private val metaAB = MetamodelData(
        path = "/t/ab.mm",
        classes = listOf(classAData, classBData),
        associations = listOf(rolesAssoc)
    )

    private val workerClass  = ClassData(name = "Worker",  isAbstract = false)
    private val projectClass = ClassData(name = "Project", isAbstract = false)
    private val workerProjectAssoc = AssociationData(
        source = AssociationEndData("Worker",  "project", MultiplicityData(lower = 0, upper = -1)),
        operator = "-->",
        target  = AssociationEndData("Project", "worker",  MultiplicityData(lower = 1, upper = 1))
    )
    private val metaWP = MetamodelData(
        path = "/t/wp.mm",
        classes = listOf(workerClass, projectClass),
        associations = listOf(workerProjectAssoc)
    )

    private val teamClass   = ClassData(name = "Team",   isAbstract = false)
    private val playerClass = ClassData(name = "Player", isAbstract = false)
    private val teamPlayerAssoc = AssociationData(
        source = AssociationEndData("Team",   "members", MultiplicityData(lower = 0, upper = -1)),
        operator = "-->",
        target  = AssociationEndData("Player", "teams",   MultiplicityData(lower = 2, upper = 2))
    )
    private val metaTP = MetamodelData(
        path = "/t/tp.mm",
        classes = listOf(teamClass, playerClass),
        associations = listOf(teamPlayerAssoc)
    )

    private val fixedWorkerClass  = ClassData(name = "FixedWorker",  isAbstract = false)
    private val fixedProjectClass = ClassData(name = "FixedProject", isAbstract = false)
    private val fixedWorkerProjectAssoc = AssociationData(
        source = AssociationEndData("FixedWorker",  "project", MultiplicityData(lower = 1, upper = 1)),
        operator = "-->",
        target  = AssociationEndData("FixedProject", "worker",  MultiplicityData(lower = 1, upper = 1))
    )
    private val metaFixedWP = MetamodelData(
        path = "/t/fixed-wp.mm",
        classes = listOf(fixedWorkerClass, fixedProjectClass),
        associations = listOf(fixedWorkerProjectAssoc)
    )

    private val managerClass = ClassData(name = "Manager", isAbstract = false)
    private val project2Class = ClassData(name = "Project2", isAbstract = false)
    private val task2Class = ClassData(name = "Task2", isAbstract = false)
    private val managerProjectAssoc = AssociationData(
        source = AssociationEndData("Manager", "projects", MultiplicityData(lower = 0, upper = -1)),
        operator = "-->",
        target  = AssociationEndData("Project2", "manager", MultiplicityData(lower = 1, upper = 1))
    )
    private val managerTaskAssoc = AssociationData(
        source = AssociationEndData("Manager", "tasks", MultiplicityData(lower = 0, upper = -1)),
        operator = "-->",
        target  = AssociationEndData("Task2", "manager", MultiplicityData(lower = 1, upper = -1))
    )
    private val metaManager = MetamodelData(
        path = "/t/manager.mm",
        classes = listOf(managerClass, project2Class, task2Class),
        associations = listOf(managerProjectAssoc, managerTaskAssoc)
    )

    @Nested
    inner class NewSpecsGeneratorTests {

        private val generator = SpecsGenerator()

        @Test
        fun `CREATE with lower=2 and bounded opposite emits both LB_REPAIR and LB_REPAIR_MULTI`() {
            val infoAB = MetamodelInfo(metaAB)
            val spec = MutationRuleSpec("ClassA", edge = "roles", action = MutationAction.CREATE)
            val result = generator.getRepairsForRuleSpec(spec, infoAB)
            val types = result["CREATE"]!!.map { it.type }.toSet()
            assertTrue(types.contains(RepairSpecType.CREATE_LB_REPAIR),       "Expected CREATE_LB_REPAIR")
            assertTrue(types.contains(RepairSpecType.CREATE_LB_REPAIR_MULTI), "Expected CREATE_LB_REPAIR_MULTI")
        }

        @Test
        fun `CREATE with lower=1 emits LB_REPAIR but NOT LB_REPAIR_MULTI`() {
            val garageClass = ClassData(name = "Garage2", isAbstract = false)
            val houseClass  = ClassData(name = "House9",  isAbstract = false)
            val assoc = AssociationData(
                source = AssociationEndData("House9",  "garages", MultiplicityData(lower = 1, upper = 3)),
                operator = "-->",
                target  = AssociationEndData("Garage2", "owner",   MultiplicityData(lower = 0, upper = 2))
            )
            val meta = MetamodelData(path = "/t/g9.mm", classes = listOf(houseClass, garageClass), associations = listOf(assoc))
            val info = MetamodelInfo(meta)
            val spec = MutationRuleSpec("House9", edge = "garages", action = MutationAction.CREATE)
            val result = generator.getRepairsForRuleSpec(spec, info)
            val types = result["CREATE"]!!.map { it.type }.toSet()
            assertTrue(types.contains(RepairSpecType.CREATE_LB_REPAIR),           "Expected CREATE_LB_REPAIR for lower=1")
            assertFalse(types.contains(RepairSpecType.CREATE_LB_REPAIR_MULTI),     "Expected NO MULTI for lower=1")
        }

        @Test
        fun `DELETE on class with k=l=1 back-ref emits DELETE_REPAIR_SINGLE but not MULTI`() {
            val infoWP = MetamodelInfo(metaWP)
            val spec = MutationRuleSpec("Worker", action = MutationAction.DELETE)
            val result = generator.getRepairsForRuleSpec(spec, infoWP)
            val types = result["DELETE"]!!.map { it.type }.toSet()
            assertTrue(types.contains(RepairSpecType.DELETE),               "Expected plain DELETE")
            assertTrue(types.contains(RepairSpecType.DELETE_REPAIR_SINGLE), "Expected DELETE_REPAIR_SINGLE for k=l=1")
            assertFalse(types.contains(RepairSpecType.DELETE_REPAIR_MULTI), "Expected NO DELETE_REPAIR_MULTI for k=l=1")
        }

        @Test
        fun `DELETE on class with k=l=2 back-ref emits both DELETE_REPAIR_SINGLE and DELETE_REPAIR_MULTI`() {
            val infoTP = MetamodelInfo(metaTP)
            val spec = MutationRuleSpec("Team", action = MutationAction.DELETE)
            val result = generator.getRepairsForRuleSpec(spec, infoTP)
            val types = result["DELETE"]!!.map { it.type }.toSet()
            assertTrue(types.contains(RepairSpecType.DELETE),               "Expected plain DELETE")
            assertTrue(types.contains(RepairSpecType.DELETE_REPAIR_SINGLE), "Expected DELETE_REPAIR_SINGLE for k=l=2")
            assertTrue(types.contains(RepairSpecType.DELETE_REPAIR_MULTI),  "Expected DELETE_REPAIR_MULTI for k=l=2")
        }

        @Test
        fun `DELETE on class with fixed source and fixed back-ref emits only plain DELETE`() {
            val infoFixedWP = MetamodelInfo(metaFixedWP)
            val spec = MutationRuleSpec("FixedWorker", action = MutationAction.DELETE)
            val result = generator.getRepairsForRuleSpec(spec, infoFixedWP)
            val types = result["DELETE"]!!.map { it.type }.toSet()
            assertEquals(setOf(RepairSpecType.DELETE), types,
                "Expected fixed-source delete to suppress repair variants")
        }

        @Test
        fun `DELETE on class with soft lower-bound (k=1, l unbounded) emits only plain DELETE`() {
            val assignClass = ClassData(name = "AssignX", isAbstract = false)
            val taskClass   = ClassData(name = "TaskX",   isAbstract = false)
            val assoc = AssociationData(
                source = AssociationEndData("AssignX", "task",        MultiplicityData(lower = 0, upper = -1)),
                operator = "-->",
                target  = AssociationEndData("TaskX",  "assignments", MultiplicityData(lower = 1, upper = -1))
            )
            val meta = MetamodelData(path = "/t/atx.mm", classes = listOf(assignClass, taskClass), associations = listOf(assoc))
            val info = MetamodelInfo(meta)
            val spec = MutationRuleSpec("AssignX", action = MutationAction.DELETE)
            val result = generator.getRepairsForRuleSpec(spec, info)
            val types = result["DELETE"]!!.map { it.type }.toSet()
            assertTrue(types.contains(RepairSpecType.DELETE),                "Expected plain DELETE")
            assertFalse(types.contains(RepairSpecType.DELETE_REPAIR_SINGLE), "Expected NO repair for soft lower-bound")
            assertFalse(types.contains(RepairSpecType.DELETE_REPAIR_MULTI),  "Expected NO repair for soft lower-bound")
        }

        @Test
        fun `DELETE on class with no back-ref produces only plain DELETE`() {
            // ClassA.roles is unidirectional (no back-ref from ClassB)
            val classBOnly = ClassData(name = "ClassBOnly", isAbstract = false)
            val classAOnly = ClassData(name = "ClassAOnly", isAbstract = false)
            val assoc = AssociationData(
                source = AssociationEndData("ClassAOnly", "things", MultiplicityData(lower = 0, upper = -1)),
                operator = "-->",
                target  = AssociationEndData("ClassBOnly", null,     MultiplicityData(lower = 0, upper = -1))
            )
            val meta = MetamodelData(path = "/t/unidirx.mm", classes = listOf(classAOnly, classBOnly), associations = listOf(assoc))
            val info = MetamodelInfo(meta)
            val spec = MutationRuleSpec("ClassAOnly", action = MutationAction.DELETE)
            val result = generator.getRepairsForRuleSpec(spec, info)
            val types = result["DELETE"]!!.map { it.type }.toSet()
            assertEquals(setOf(RepairSpecType.DELETE), types, "Expected only plain DELETE for unidirectional ref")
        }
    }

    @Nested
    inner class NewAstBuilderTests {

        @Test
        fun `CREATE_LB_REPAIR_MULTI with lower=2 produces 2 donors, 2 targets, newNode, 2 delete+2 create links, 2 guards`() {
            val info = MetamodelInfo(metaAB)
            val spec = RepairSpec("ClassA", "roles", RepairSpecType.CREATE_LB_REPAIR_MULTI)
            val ast  = MutationAstBuilder.build("name", spec, "/t/ab.mm", info)
            val stmt = ast.statements[0] as TypedMatchStatement

            val objects = stmt.pattern.elements.filterIsInstance<TypedPatternObjectInstanceElement>()
            assertEquals(5, objects.size, "Expected 5 objects: donor_1, donor_2, target_1, target_2, newNode")
            assertEquals(2, objects.count { it.objectInstance.name.startsWith("donor_") },  "Expected 2 donors")
            assertEquals(2, objects.count { it.objectInstance.name.startsWith("target_") }, "Expected 2 targets")
            assertEquals(1, objects.count { it.objectInstance.modifier == "create" },       "Expected 1 created node")

            val links = stmt.pattern.elements.filterIsInstance<TypedPatternLinkElement>()
            assertEquals(6, links.size, "Expected 6 links: 2 match + 2 delete + 2 create")
            assertEquals(2, links.count { it.link.modifier == null },     "Expected 2 match links")
            assertEquals(2, links.count { it.link.modifier == "delete" }, "Expected 2 delete links")
            assertEquals(2, links.count { it.link.modifier == "create" }, "Expected 2 create links")

            val wheres = stmt.pattern.elements.filterIsInstance<TypedPatternWhereClauseElement>()
            assertEquals(2, wheres.size, "Expected 2 lower-bound guards")
            wheres.forEach { w ->
                val expr = w.whereClause.expression as TypedBinaryExpression
                assertEquals(">", expr.operator)
                assertEquals("2", (expr.right as TypedIntLiteralExpression).value)
            }
        }

        @Test
        fun `CREATE_LB_REPAIR_MULTI donor and target variable names are indexed`() {
            val info = MetamodelInfo(metaAB)
            val spec = RepairSpec("ClassA", "roles", RepairSpecType.CREATE_LB_REPAIR_MULTI)
            val ast  = MutationAstBuilder.build("name", spec, "/t/ab.mm", info)
            val stmt = ast.statements[0] as TypedMatchStatement
            val names = stmt.pattern.elements
                .filterIsInstance<TypedPatternObjectInstanceElement>()
                .map { it.objectInstance.name }.toSet()
            assertTrue(names.contains("donor_1"))
            assertTrue(names.contains("donor_2"))
            assertTrue(names.contains("target_1"))
            assertTrue(names.contains("target_2"))
            assertTrue(names.contains("newNode"))
        }

        @Test
        fun `DELETE_REPAIR_SINGLE has merged delete node, neighbour match, other match, forbid and create links`() {
            val info = MetamodelInfo(metaWP)
            val spec = RepairSpec("Worker", "project", RepairSpecType.DELETE_REPAIR_SINGLE)
            val ast  = MutationAstBuilder.build("name", spec, "/t/wp.mm", info)
            val stmt = ast.statements[0] as TypedMatchStatement

            val objects = stmt.pattern.elements.filterIsInstance<TypedPatternObjectInstanceElement>()
            val deleteMarker  = objects.find { it.objectInstance.modifier == "delete" }
            val neighborMatch = objects.find { it.objectInstance.name == "neighbor_project" }
            val otherMatch    = objects.find { it.objectInstance.name == "other_project" }

            assertNotNull(deleteMarker, "Expected delete marker")
            assertEquals("Worker", deleteMarker.objectInstance.className, "Delete marker must carry className")
            assertNotNull(neighborMatch, "Expected neighbour match object")
            assertEquals("Project", neighborMatch.objectInstance.className)
            assertNotNull(otherMatch, "Expected other_project match object")
            assertEquals("Worker", otherMatch.objectInstance.className)

            val links = stmt.pattern.elements.filterIsInstance<TypedPatternLinkElement>()
            val modifiers = links.map { it.link.modifier }.toSet()
            assertTrue(modifiers.contains(null),      "Expected match link (node→neighbour)")
            assertTrue(modifiers.contains("forbid"),  "Expected forbid NAC link")
            assertTrue(modifiers.contains("create"),  "Expected create link")
            assertFalse(modifiers.contains("delete"), "DELETE_REPAIR_SINGLE must not have explicit delete link")
        }

        @Test
        fun `DELETE_REPAIR_SINGLE with unbounded ref has no where clause`() {
            val info = MetamodelInfo(metaWP)
            val spec = RepairSpec("Worker", "project", RepairSpecType.DELETE_REPAIR_SINGLE)
            val ast  = MutationAstBuilder.build("name", spec, "/t/wp.mm", info)
            val stmt = ast.statements[0] as TypedMatchStatement
            val wheres = stmt.pattern.elements.filterIsInstance<TypedPatternWhereClauseElement>()
            assertTrue(wheres.isEmpty(), "Expected no where clause when ref upper=-1")
        }

        @Test
        fun `DELETE_REPAIR_SINGLE with bounded ref emits upper-bound guard on other`() {
            val w3Class = ClassData(name = "Worker3",  isAbstract = false)
            val p3Class = ClassData(name = "Project3", isAbstract = false)
            val assoc = AssociationData(
                source = AssociationEndData("Worker3",  "project", MultiplicityData(lower = 0, upper = 3)),
                operator = "-->",
                target  = AssociationEndData("Project3", "worker",  MultiplicityData(lower = 1, upper = 1))
            )
            val meta = MetamodelData(path = "/t/w3p3.mm", classes = listOf(w3Class, p3Class), associations = listOf(assoc))
            val info = MetamodelInfo(meta)
            val spec = RepairSpec("Worker3", "project", RepairSpecType.DELETE_REPAIR_SINGLE)
            val ast  = MutationAstBuilder.build("name", spec, "/t/w3p3.mm", info)
            val stmt = ast.statements[0] as TypedMatchStatement
            val wheres = stmt.pattern.elements.filterIsInstance<TypedPatternWhereClauseElement>()
            assertEquals(1, wheres.size, "Expected one upper-bound guard")
            val expr = wheres[0].whereClause.expression as TypedBinaryExpression
            assertEquals("<", expr.operator)
            val ident = ((expr.left as TypedMemberCallExpression).expression as TypedMemberAccessExpression)
                .expression as TypedIdentifierExpression
            assertEquals("other_project", ident.name)
            assertEquals("3", (expr.right as TypedIntLiteralExpression).value)
        }

        @Test
        fun `DELETE_REPAIR_SINGLE preserves lower-bound guards for non-repaired references`() {
            val info = MetamodelInfo(metaManager)
            val spec = RepairSpec("Manager", "projects", RepairSpecType.DELETE_REPAIR_SINGLE)
            val ast = MutationAstBuilder.build("name", spec, "/t/manager.mm", info)
            val stmt = ast.statements[0] as TypedMatchStatement

            val objects = stmt.pattern.elements.filterIsInstance<TypedPatternObjectInstanceElement>()
            val names = objects.map { it.objectInstance.name }.toSet()
            assertTrue(names.contains("neighbor_projects"), "Expected repaired neighbour match")
            assertTrue(names.contains("other_projects"), "Expected replacement source match")
            assertTrue(names.contains("neighbor_tasks"), "Expected guard neighbour for non-repaired tasks ref")

            val links = stmt.pattern.elements.filterIsInstance<TypedPatternLinkElement>()
            assertTrue(
                links.any {
                    it.link.modifier == null &&
                        it.link.source.objectName == "node" &&
                        it.link.source.propertyName == "tasks" &&
                        it.link.target.objectName == "neighbor_tasks"
                },
                "Expected match link for guarded non-repaired tasks ref"
            )

            val wheres = stmt.pattern.elements.filterIsInstance<TypedPatternWhereClauseElement>()
            assertEquals(1, wheres.size, "Expected one lower-bound guard for non-repaired tasks ref")
            val expr = wheres[0].whereClause.expression as TypedBinaryExpression
            assertEquals(">", expr.operator)
            val ident = ((expr.left as TypedMemberCallExpression).expression as TypedMemberAccessExpression)
                .expression as TypedIdentifierExpression
            assertEquals("neighbor_tasks", ident.name)
            assertEquals("1", (expr.right as TypedIntLiteralExpression).value)
        }

        @Test
        fun `DELETE_REPAIR_MULTI with k=2 produces 2 neighbours, 2 others, 2 forbid and 2 create links`() {
            val info = MetamodelInfo(metaTP)
            val spec = RepairSpec("Team", "members", RepairSpecType.DELETE_REPAIR_MULTI)
            val ast  = MutationAstBuilder.build("name", spec, "/t/tp.mm", info)
            val stmt = ast.statements[0] as TypedMatchStatement

            val objects = stmt.pattern.elements.filterIsInstance<TypedPatternObjectInstanceElement>()
            // node (match) + node (delete) + 2 neighbours + 2 others = 6
            assertEquals(6, objects.size, "Expected 6 objects")
            assertEquals(2, objects.count { it.objectInstance.name.startsWith("neighbor_members_") }, "Expected 2 neighbours")
            assertEquals(2, objects.count { it.objectInstance.name.startsWith("other_members_") },    "Expected 2 others")

            val links = stmt.pattern.elements.filterIsInstance<TypedPatternLinkElement>()
            assertEquals(6, links.size, "Expected 6 links: 2 match + 2 forbid + 2 create")
            assertEquals(2, links.count { it.link.modifier == null },     "Expected 2 match links")
            assertEquals(2, links.count { it.link.modifier == "forbid" }, "Expected 2 forbid links")
            assertEquals(2, links.count { it.link.modifier == "create" }, "Expected 2 create links")

            val wheres = stmt.pattern.elements.filterIsInstance<TypedPatternWhereClauseElement>()
            assertTrue(wheres.isEmpty(), "Expected no guards when ref upper=-1")
        }

        @Test
        fun `DELETE_REPAIR_MULTI neighbour and other variable names are properly indexed`() {
            val info = MetamodelInfo(metaTP)
            val spec = RepairSpec("Team", "members", RepairSpecType.DELETE_REPAIR_MULTI)
            val ast  = MutationAstBuilder.build("name", spec, "/t/tp.mm", info)
            val stmt = ast.statements[0] as TypedMatchStatement
            val names = stmt.pattern.elements
                .filterIsInstance<TypedPatternObjectInstanceElement>()
                .map { it.objectInstance.name }.toSet()
            assertTrue(names.contains("neighbor_members_1"))
            assertTrue(names.contains("neighbor_members_2"))
            assertTrue(names.contains("other_members_1"))
            assertTrue(names.contains("other_members_2"))
        }
    }

    @Nested
    inner class NewNamingTests {

        @Test
        fun `fromRepairSpec for CREATE_LB_REPAIR_MULTI has LBREPAIR_MULTI suffix`() {
            val name = MutationRuleNameGenerator.fromRepairSpec(RepairSpec("Foo", "bar", RepairSpecType.CREATE_LB_REPAIR_MULTI))
            assertTrue(name.endsWith("_LBREPAIR_MULTI"), "Expected LBREPAIR_MULTI suffix, got: $name")
        }

        @Test
        fun `fromRepairSpec for DELETE_REPAIR_SINGLE has REPAIR_SG suffix`() {
            val name = MutationRuleNameGenerator.fromRepairSpec(RepairSpec("Foo", "bar", RepairSpecType.DELETE_REPAIR_SINGLE))
            assertTrue(name.endsWith("_REPAIR_SG"), "Expected REPAIR_SG suffix, got: $name")
        }

        @Test
        fun `fromRepairSpec for DELETE_REPAIR_MULTI has REPAIR_MN suffix`() {
            val name = MutationRuleNameGenerator.fromRepairSpec(RepairSpec("Foo", "bar", RepairSpecType.DELETE_REPAIR_MULTI))
            assertTrue(name.endsWith("_REPAIR_MN"), "Expected REPAIR_MN suffix, got: $name")
        }

        @Test
        fun `CREATE_LB_REPAIR_MULTI name contains className and refName`() {
            val name = MutationRuleNameGenerator.fromRepairSpec(
                RepairSpec("House", "garages", RepairSpecType.CREATE_LB_REPAIR_MULTI)
            )
            assertTrue(name.contains("House") && name.contains("garages"),
                "Expected name to contain className and refName; got: $name")
        }

        @Test
        fun `DELETE_REPAIR_SINGLE and MULTI names start with DELETE`() {
            val sg = MutationRuleNameGenerator.fromRepairSpec(RepairSpec("Node", "edges", RepairSpecType.DELETE_REPAIR_SINGLE))
            val mn = MutationRuleNameGenerator.fromRepairSpec(RepairSpec("Node", "edges", RepairSpecType.DELETE_REPAIR_MULTI))
            assertTrue(sg.startsWith("DELETE"), "DELETE_REPAIR_SINGLE name must start with DELETE; got: $sg")
            assertTrue(mn.startsWith("DELETE"), "DELETE_REPAIR_MULTI name must start with DELETE; got: $mn")
        }
    }

    @Nested
    inner class NewRuleGeneratorTests {

        @Test
        fun `generate with lower=2 ref produces both LB_REPAIR and LB_REPAIR_MULTI rules`() {
            val specs = listOf(MutationRuleSpec("ClassA", edge = "roles", action = MutationAction.CREATE))
            val names = MutationRuleGenerator.generate(metaAB, specs).map { it.name }.toSet()
            assertTrue(names.any { it.contains("LBREPAIR") && !it.contains("MULTI") },
                "Expected single-donor LBREPAIR rule; got: $names")
            assertTrue(names.any { it.contains("LBREPAIR_MULTI") },
                "Expected multi-donor LBREPAIR_MULTI rule; got: $names")
        }

        @Test
        fun `generate DELETE for Worker produces REPAIR_SG but not REPAIR_MN (k=l=1)`() {
            val names = MutationRuleGenerator.generate(
                metaWP, listOf(MutationRuleSpec("Worker", action = MutationAction.DELETE))
            ).map { it.name }.toSet()
            assertTrue(names.any { it.contains("REPAIR_SG") }, "Expected REPAIR_SG; got: $names")
            assertFalse(names.any { it.contains("REPAIR_MN") }, "Expected no REPAIR_MN for k=l=1; got: $names")
        }

        @Test
        fun `generate DELETE for Team produces both REPAIR_SG and REPAIR_MN (k=l=2)`() {
            val names = MutationRuleGenerator.generate(
                metaTP, listOf(MutationRuleSpec("Team", action = MutationAction.DELETE))
            ).map { it.name }.toSet()
            assertTrue(names.any { it.contains("REPAIR_SG") }, "Expected REPAIR_SG; got: $names")
            assertTrue(names.any { it.contains("REPAIR_MN") }, "Expected REPAIR_MN; got: $names")
        }

        @Test
        fun `generate DELETE for fixed-source class suppresses repair variants`() {
            val names = MutationRuleGenerator.generate(
                metaFixedWP, listOf(MutationRuleSpec("FixedWorker", action = MutationAction.DELETE))
            ).map { it.name }.toSet()
            assertTrue(names.contains("DELETE_FixedWorker"), "Expected plain DELETE_FixedWorker; got: $names")
            assertFalse(names.any { it.contains("REPAIR_SG") }, "Expected no REPAIR_SG for fixed source; got: $names")
            assertFalse(names.any { it.contains("REPAIR_MN") }, "Expected no REPAIR_MN for fixed source; got: $names")
        }

        @Test
        fun `all new variant rules have valid TypedAst with one statement`() {
            val allMutations =
                MutationRuleGenerator.generate(metaAB, listOf(MutationRuleSpec("ClassA", action = MutationAction.CREATE))) +
                MutationRuleGenerator.generate(metaWP, listOf(MutationRuleSpec("Worker", action = MutationAction.DELETE))) +
                MutationRuleGenerator.generate(metaTP, listOf(MutationRuleSpec("Team",   action = MutationAction.DELETE)))
            allMutations.forEach { m ->
                assertNotNull(m.typedAst, "Rule '${m.name}' has null typedAst")
                assertEquals(1, m.typedAst.statements.size, "Rule '${m.name}' should have 1 statement")
                assertTrue(m.typedAst.statements[0] is TypedMatchStatement,
                    "Rule '${m.name}' statement should be TypedMatchStatement")
            }
        }

        @Test
        fun `generate ALL for Worker includes plain DELETE alongside repair variants`() {
            val names = MutationRuleGenerator.generate(
                metaWP, listOf(MutationRuleSpec("Worker", action = MutationAction.ALL))
            ).map { it.name }.toSet()
            assertTrue(names.contains("DELETE_Worker"), "Expected plain DELETE_Worker rule; got: $names")
            assertTrue(names.any { it.contains("REPAIR_SG") }, "Expected REPAIR_SG rule; got: $names")
        }

        @Test
        fun `generate deduplicates across base and S-pass for new variants`() {
            val specs = listOf(MutationRuleSpec("ClassA", edge = "roles", action = MutationAction.CREATE))
            val refinements = listOf(
                com.mdeo.optimizer.config.RefinementConfig("ClassA", "roles", lower = 2, upper = 5)
            )
            val mutations = MutationRuleGenerator.generate(metaAB, specs, refinements)
            val names = mutations.map { it.name }
            assertEquals(names.distinct().size, names.size, "Expected no duplicate rule names; got: $names")
            assertTrue(names.any { it.startsWith("S_") && it.contains("LBREPAIR") },
                "Expected S_-prefixed LBREPAIR rule from refinement pass; got: $names")
        }

        @Test
        fun `generate CREATE for Sprint with required plan association`() {
            // Plan.sprints[0..*] *--> Sprint.plan[1]
            // This tests Task 1: ensure Sprint.plan is properly assigned when creating a Sprint
            val planClass = ClassData(name = "Plan", isAbstract = false)
            val sprintClass = ClassData(name = "Sprint", isAbstract = false)

            val planSprintsAssoc = AssociationData(
                source = AssociationEndData(
                    className = "Plan",
                    name = "sprints",
                    multiplicity = MultiplicityData.many()     // 0..*
                ),
                operator = "*-->",
                target = AssociationEndData(
                    className = "Sprint",
                    name = "plan",
                    multiplicity = MultiplicityData.single()   // 1..1
                )
            )

            val metaData = MetamodelData(
                path = "/project/scrum.mm",
                classes = listOf(planClass, sprintClass),
                associations = listOf(planSprintsAssoc)
            )

            // Generate CREATE operators for Sprint
            val specs = listOf(MutationRuleSpec("Sprint", action = MutationAction.CREATE))
            val mutations = MutationRuleGenerator.generate(metaData, specs)

            val names = mutations.map { it.name }.toSet()
            
            // Should have contextual CREATE rule only (not standalone)
            // because Sprint.plan has lower bound 1
            assertTrue(
                names.any { it.contains("Plan") && it.contains("sprints") },
                "Expected CREATE_Sprint_in_Plan_via_sprints; got: $names"
            )
            assertFalse(
                names.contains("CREATE_Sprint"),
                "Expected NO standalone CREATE_Sprint (violates Sprint.plan lower bound); got: $names"
            )

            // Verify the contextual rule has both container and link
            val contextualRule = mutations.find { it.name.contains("Plan") && it.name.contains("sprints") }
            assertNotNull(contextualRule)
            val stmt = contextualRule.typedAst.statements[0] as TypedMatchStatement
            val instances = stmt.pattern.elements.filterIsInstance<TypedPatternObjectInstanceElement>()
            val links = stmt.pattern.elements.filterIsInstance<TypedPatternLinkElement>()

            // Should have: container (Plan), newNode (Sprint)
            val containerInst = instances.find { it.objectInstance.className == "Plan" && it.objectInstance.modifier == null }
            val createdInst = instances.find { it.objectInstance.className == "Sprint" && it.objectInstance.modifier == "create" }
            assertNotNull(containerInst, "Expected to match Plan container")
            assertNotNull(createdInst, "Expected to create Sprint node")

            // Should have: create link between Plan and Sprint
            assertTrue(links.isNotEmpty(), "Expected at least one link in pattern")
            val createLink = links.find { it.link.modifier == "create" }
            assertNotNull(createLink, "Expected create link for Plan->sprints->Sprint")
        }

        @Test
        fun `generate CREATE for Sprint assigns an initial committed item`() {
            val planClass = ClassData(name = "Plan", isAbstract = false)
            val sprintClass = ClassData(name = "Sprint", isAbstract = false)
            val workItemClass = ClassData(name = "WorkItem", isAbstract = false)

            val planSprintsAssoc = AssociationData(
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
            )

            val sprintCommittedItemsAssoc = AssociationData(
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

            val metaData = MetamodelData(
                path = "/project/scrum.mm",
                classes = listOf(planClass, sprintClass, workItemClass),
                associations = listOf(planSprintsAssoc, sprintCommittedItemsAssoc)
            )

            val specs = listOf(MutationRuleSpec("Sprint", action = MutationAction.CREATE))
            val mutations = MutationRuleGenerator.generate(metaData, specs)

            val rule = mutations.find { it.name.contains("Plan") && it.name.contains("sprints") }
            assertNotNull(rule, "Expected contextual CREATE_Sprint_in_Plan_via_sprints rule")

            val stmt = requireNotNull(rule).typedAst.statements[0] as TypedMatchStatement
            val instances = stmt.pattern.elements.filterIsInstance<TypedPatternObjectInstanceElement>()
            val links = stmt.pattern.elements.filterIsInstance<TypedPatternLinkElement>()
            val whereClauses = stmt.pattern.elements.filterIsInstance<TypedPatternWhereClauseElement>()

            assertNotNull(instances.find { it.objectInstance.name == "container" && it.objectInstance.className == "Plan" })
            assertNotNull(instances.find { it.objectInstance.name == "newNode" && it.objectInstance.className == "Sprint" && it.objectInstance.modifier == "create" })
            assertNotNull(instances.find { it.objectInstance.name == "required_committedItems" && it.objectInstance.className == "WorkItem" })

            val committedLink = links.find { it.link.source.propertyName == "committedItems" }
            assertNotNull(committedLink, "Expected Sprint.committedItems create link")
            assertEquals("required_committedItems", requireNotNull(committedLink).link.target.objectName)

            assertTrue(
                whereClauses.any { clause ->
                    val expr = clause.whereClause.expression as TypedBinaryExpression
                    val memberCall = expr.left as TypedMemberCallExpression
                    val memberAccess = memberCall.expression as TypedMemberAccessExpression
                    val identifier = memberAccess.expression as TypedIdentifierExpression
                    expr.operator == "<" &&
                        memberCall.member == "size" &&
                        memberAccess.member == "isPlannedFor" &&
                        identifier.name == "required_committedItems"
                },
                "Expected guard on WorkItem.isPlannedFor upper bound"
            )
        }

        @Test
        fun `generate CREATE LB repair for Sprint keeps Plan context`() {
            val planClass = ClassData(name = "Plan", isAbstract = false)
            val sprintClass = ClassData(name = "Sprint", isAbstract = false)

            val planSprintsAssoc = AssociationData(
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
            )

            val sprintCommittedItemsAssoc = AssociationData(
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

            val workItemClass = ClassData(name = "WorkItem", isAbstract = false)
            val metaData = MetamodelData(
                path = "/project/scrum.mm",
                classes = listOf(planClass, sprintClass, workItemClass),
                associations = listOf(planSprintsAssoc, sprintCommittedItemsAssoc)
            )

            val specs = listOf(MutationRuleSpec("Sprint", edge = "committedItems", action = MutationAction.CREATE))
            val mutations = MutationRuleGenerator.generate(metaData, specs)
            val names = mutations.map { it.name }.toSet()

            assertTrue(
                names.any { it.contains("Plan") && it.contains("sprints") && it.contains("LBREPAIR") },
                "Expected contextual CREATE_Sprint_in_Plan_via_sprints_LBREPAIR; got: $names"
            )
            assertFalse(
                names.contains("CREATE_Sprint_committedItems_LBREPAIR"),
                "Expected no standalone CREATE_Sprint_committedItems_LBREPAIR; got: $names"
            )

            val contextualRule = mutations.find { it.name.contains("Plan") && it.name.contains("sprints") && it.name.contains("LBREPAIR") }
            assertNotNull(contextualRule)
            val stmt = contextualRule.typedAst.statements[0] as TypedMatchStatement
            val instances = stmt.pattern.elements.filterIsInstance<TypedPatternObjectInstanceElement>()
            assertNotNull(instances.find { it.objectInstance.name == "container" && it.objectInstance.className == "Plan" })
            assertNotNull(instances.find { it.objectInstance.name == "newNode" && it.objectInstance.className == "Sprint" && it.objectInstance.modifier == "create" })
        }
    }
}
