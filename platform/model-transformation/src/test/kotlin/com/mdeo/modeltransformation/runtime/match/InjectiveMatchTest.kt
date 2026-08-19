package com.mdeo.modeltransformation.runtime.match

import com.mdeo.metamodel.data.AssociationData
import com.mdeo.metamodel.data.AssociationEndData
import com.mdeo.metamodel.data.ClassData
import com.mdeo.metamodel.Metamodel
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.metamodel.data.ModelData
import com.mdeo.metamodel.data.ModelDataInstance
import com.mdeo.metamodel.data.MultiplicityData
import com.mdeo.modeltransformation.ast.TypedAst
import com.mdeo.modeltransformation.ast.patterns.TypedPattern
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstance
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstanceElement
import com.mdeo.modeltransformation.ast.statements.TypedMatchStatement
import com.mdeo.modeltransformation.compiler.ExpressionCompilerRegistry
import com.mdeo.modeltransformation.graph.ModelGraph
import com.mdeo.modeltransformation.graph.tinker.TinkerModelGraph
import com.mdeo.modeltransformation.runtime.StatementExecutorRegistry
import com.mdeo.modeltransformation.runtime.TransformationEngine
import com.mdeo.modeltransformation.runtime.TransformationExecutionContext
import com.mdeo.modeltransformation.runtime.TransformationExecutionResult
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for injective match semantics in [MatchExecutor].
 *
 * Injective matching ensures that distinct pattern variables of type-compatible
 * classes bind to distinct graph vertices. This is implemented by adding
 * `where(stepLabel_a, P.neq(stepLabel_b))` constraints for all pairs of
 * matchable+delete instances whose types are compatible (same type, or one is
 * a subtype of the other via [com.mdeo.modeltransformation.compiler.registry.TypeRegistry.isSubtypeOf]).
 */
class InjectiveMatchTest {

    private val context = TransformationExecutionContext.empty()

    private val openGraphs = mutableListOf<ModelGraph>()

    @AfterEach
    fun tearDown() {
        openGraphs.forEach { it.close() }
        openGraphs.clear()
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun buildSimpleMetamodel(): MetamodelData = MetamodelData(
        path = "/metamodel.mm",
        classes = listOf(
            ClassData(name = "House", isAbstract = false),
            ClassData(name = "Room", isAbstract = false)
        ),
        enums = emptyList(),
        associations = listOf(
            AssociationData(
                source = AssociationEndData(
                    className = "House",
                    multiplicity = MultiplicityData(lower = 1, upper = 1),
                    name = "rooms"
                ),
                operator = "<-->",
                target = AssociationEndData(
                    className = "Room",
                    multiplicity = MultiplicityData(lower = 1, upper = 1),
                    name = "house"
                )
            )
        ),
        importedMetamodelPaths = emptyList()
    )

    private fun buildSubtypeMetamodel(): MetamodelData = MetamodelData(
        path = "/metamodel.mm",
        classes = listOf(
            ClassData(name = "House", isAbstract = false),
            ClassData(name = "Room", isAbstract = false),
            ClassData(name = "LargeRoom", isAbstract = false, extends = listOf("Room"))
        ),
        enums = emptyList(),
        associations = listOf(
            AssociationData(
                source = AssociationEndData(
                    className = "House",
                    multiplicity = MultiplicityData(lower = 1, upper = 1),
                    name = "rooms"
                ),
                operator = "<-->",
                target = AssociationEndData(
                    className = "Room",
                    multiplicity = MultiplicityData(lower = 1, upper = 1),
                    name = "house"
                )
            )
        ),
        importedMetamodelPaths = emptyList()
    )

    /**
     * Creates a [TransformationEngine] backed by a [TinkerModelGraph] whose vertex set is
     * determined by [instances] — a list of (instanceName, className) pairs. No properties
     * or edges are created; this is sufficient for injective-match tests which only care
     * about vertex identity.
     */
    private fun createEngine(
        instances: List<Pair<String, String>>,
        metamodelData: MetamodelData = buildSimpleMetamodel()
    ): TransformationEngine {
        val metamodel = Metamodel.compile(metamodelData)
        val modelData = ModelData(
            metamodelPath = metamodelData.path,
            instances = instances.map { (name, cls) ->
                ModelDataInstance(name = name, className = cls, properties = emptyMap())
            },
            links = emptyList()
        )
        val graph = TinkerModelGraph.create(modelData, metamodel)
        openGraphs.add(graph)
        return TransformationEngine(
            modelGraph = graph,
            ast = TypedAst(types = emptyList(), metamodelPath = metamodelData.path, statements = emptyList()),
            expressionCompilerRegistry = ExpressionCompilerRegistry.createDefaultRegistry(),
            statementExecutorRegistry = StatementExecutorRegistry.createDefaultRegistry()
        )
    }

    private fun matchInstance(name: String, className: String): TypedPatternObjectInstanceElement =
        TypedPatternObjectInstanceElement(
            objectInstance = TypedPatternObjectInstance(
                modifier = null,
                name = name,
                className = className,
                properties = emptyList()
            )
        )

    private fun deleteInstance(name: String, className: String): TypedPatternObjectInstanceElement =
        TypedPatternObjectInstanceElement(
            objectInstance = TypedPatternObjectInstance(
                modifier = "delete",
                name = name,
                className = className,
                properties = emptyList()
            )
        )

    // ========================================================================
    // 1. Basic injective semantics – two nodes, same type
    // ========================================================================

    @Nested
    inner class BasicInjectiveSameType {

        @Test
        fun `two rooms exist - match succeeds with distinct bindings`() {
            val engine = createEngine(listOf("r1" to "Room", "r2" to "Room"))
            val pattern = TypedPattern(
                elements = listOf(
                    matchInstance("a", "Room"),
                    matchInstance("b", "Room")
                )
            )

            val matchExecutor = MatchExecutor()
            val result = matchExecutor.executeMatch(pattern, context, engine)

            assertIs<MatchResult.Matched>(result)
            val idA = result.instanceMappings["a"]?.rawId
            val idB = result.instanceMappings["b"]?.rawId
            assertNotEquals(idA, idB, "Injective match should bind a and b to different vertices")
        }
    }

    // ========================================================================
    // 2. Only one node of a type – match fails
    // ========================================================================

    @Nested
    inner class SingleNodeFails {

        @Test
        fun `one room - matching two rooms fails`() {
            val engine = createEngine(listOf("r1" to "Room"))
            val statement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        matchInstance("a", "Room"),
                        matchInstance("b", "Room")
                    )
                )
            )

            val result = engine.executeStatement(statement, context)
            assertIs<TransformationExecutionResult.Failure>(result)
        }
    }

    // ========================================================================
    // 3. Injective semantics with delete modifier
    // ========================================================================

    @Nested
    inner class DeleteModifierInjective {

        @Test
        fun `matchable and delete instance bind to different vertices`() {
            val engine = createEngine(listOf("r1" to "Room", "r2" to "Room"))
            val pattern = TypedPattern(
                elements = listOf(
                    matchInstance("a", "Room"),
                    deleteInstance("b", "Room")
                )
            )

            val matchExecutor = MatchExecutor()
            val result = matchExecutor.executeMatch(pattern, context, engine)

            assertIs<MatchResult.Matched>(result)
            val idA = result.instanceMappings["a"]?.rawId
            val idB = result.instanceMappings["b"]?.rawId
            assertNotEquals(idA, idB, "Matchable and delete instances should bind to different vertices")
        }

        @Test
        fun `one room - matchable and delete of same type fails`() {
            val engine = createEngine(listOf("r1" to "Room"))
            val statement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        matchInstance("a", "Room"),
                        deleteInstance("b", "Room")
                    )
                )
            )

            val result = engine.executeStatement(statement, context)
            assertIs<TransformationExecutionResult.Failure>(result)
        }
    }

    // ========================================================================
    // 4. Different types – no constraint needed
    // ========================================================================

    @Nested
    inner class DifferentTypesNoConstraint {

        @Test
        fun `house and room match independently without neq constraint`() {
            val engine = createEngine(listOf("h1" to "House", "r1" to "Room"))
            val statement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        matchInstance("h", "House"),
                        matchInstance("r", "Room")
                    )
                )
            )

            val result = engine.executeStatement(statement, context)
            assertIs<TransformationExecutionResult.Success>(result)
        }
    }

    // ========================================================================
    // 5. Subtype compatibility – injective constraints apply with subtype metamodel
    // ========================================================================

    @Nested
    inner class SubtypeCompatibility {

        @Test
        fun `two LargeRoom vertices with subtype metamodel - instances bind distinctly`() {
            val engine = createEngine(
                listOf("lr1" to "LargeRoom", "lr2" to "LargeRoom"),
                buildSubtypeMetamodel()
            )
            val pattern = TypedPattern(
                elements = listOf(
                    matchInstance("a", "LargeRoom"),
                    matchInstance("b", "LargeRoom")
                )
            )

            val matchExecutor = MatchExecutor()
            val result = matchExecutor.executeMatch(pattern, context, engine)

            assertIs<MatchResult.Matched>(result)
            val idA = result.instanceMappings["a"]?.rawId
            val idB = result.instanceMappings["b"]?.rawId
            assertNotEquals(idA, idB, "Two LargeRoom instances should bind to different vertices")
        }

        @Test
        fun `two Room vertices with subtype metamodel - injective still enforced`() {
            val engine = createEngine(
                listOf("r1" to "Room", "r2" to "Room"),
                buildSubtypeMetamodel()
            )
            val pattern = TypedPattern(
                elements = listOf(
                    matchInstance("a", "Room"),
                    matchInstance("b", "Room")
                )
            )

            val matchExecutor = MatchExecutor()
            val result = matchExecutor.executeMatch(pattern, context, engine)

            assertIs<MatchResult.Matched>(result)
            val idA = result.instanceMappings["a"]?.rawId
            val idB = result.instanceMappings["b"]?.rawId
            assertNotEquals(idA, idB, "Injective constraint should be enforced with subtype metamodel")
        }
    }

    // ========================================================================
    // 6. One node, subtype scenario – match fails
    // ========================================================================

    @Nested
    inner class SubtypeSingleNodeFails {

        @Test
        fun `only one LargeRoom - two LargeRoom instances cannot both match`() {
            val engine = createEngine(
                listOf("lr1" to "LargeRoom"),
                buildSubtypeMetamodel()
            )
            val statement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        matchInstance("a", "LargeRoom"),
                        matchInstance("b", "LargeRoom")
                    )
                )
            )

            val result = engine.executeStatement(statement, context)
            assertIs<TransformationExecutionResult.Failure>(result)
        }
    }

    // ========================================================================
    // 7. Three nodes of same type – full injective
    // ========================================================================

    @Nested
    inner class ThreeNodesSameType {

        @Test
        fun `three rooms - all three instances bind to different vertices`() {
            val engine = createEngine(listOf("r1" to "Room", "r2" to "Room", "r3" to "Room"))
            val pattern = TypedPattern(
                elements = listOf(
                    matchInstance("a", "Room"),
                    matchInstance("b", "Room"),
                    matchInstance("c", "Room")
                )
            )

            val matchExecutor = MatchExecutor()
            val results = matchExecutor.executeMatchAll(pattern, context, engine)

            assertTrue(results.isNotEmpty(), "Should find at least one match")

            val first = results.first()
            val idA = first.instanceMappings["a"]?.rawId
            val idB = first.instanceMappings["b"]?.rawId
            val idC = first.instanceMappings["c"]?.rawId
            assertNotEquals(idA, idB)
            assertNotEquals(idA, idC)
            assertNotEquals(idB, idC)
        }
    }

    // ========================================================================
    // 8. Three nodes of same type – only two available
    // ========================================================================

    @Nested
    inner class ThreeInstancesTwoVertices {

        @Test
        fun `two rooms - matching three Room instances fails`() {
            val engine = createEngine(listOf("r1" to "Room", "r2" to "Room"))
            val statement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        matchInstance("a", "Room"),
                        matchInstance("b", "Room"),
                        matchInstance("c", "Room")
                    )
                )
            )

            val result = engine.executeStatement(statement, context)
            assertIs<TransformationExecutionResult.Failure>(result)
        }
    }

    // ========================================================================
    // 9. Injective with executeMatchAll – permutation count
    // ========================================================================

    @Nested
    inner class MatchAllPermutations {

        @Test
        fun `three rooms - executeMatchAll returns 6 distinct pairs`() {
            val engine = createEngine(listOf("r1" to "Room", "r2" to "Room", "r3" to "Room"))
            val pattern = TypedPattern(
                elements = listOf(
                    matchInstance("a", "Room"),
                    matchInstance("b", "Room")
                )
            )

            val matchExecutor = MatchExecutor()
            val results = matchExecutor.executeMatchAll(pattern, context, engine)

            // 3 rooms, pick 2 with order: P(3,2) = 6
            assertEquals(6, results.size, "Should have 6 permutations of distinct pairs from 3 rooms")

            // Verify all pairs are distinct
            for (matched in results) {
                val idA = matched.instanceMappings["a"]?.rawId
                val idB = matched.instanceMappings["b"]?.rawId
                assertNotEquals(idA, idB, "Each match should bind a and b to different vertices")
            }

            // Verify all 6 pairs are unique combinations
            val pairs = results.map { it.instanceMappings["a"]?.rawId to it.instanceMappings["b"]?.rawId }.toSet()
            assertEquals(6, pairs.size, "All 6 pairs should be distinct")
        }
    }

    // ========================================================================
    // 10. No injective constraint for incompatible types
    // ========================================================================

    @Nested
    inner class IncompatibleTypesNoConstraint {

        @Test
        fun `house and room have no inheritance - no neq constraint`() {
            val engine = createEngine(listOf("h1" to "House", "r1" to "Room"))
            val statement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        matchInstance("h", "House"),
                        matchInstance("r", "Room")
                    )
                )
            )

            val result = engine.executeStatement(statement, context)
            assertIs<TransformationExecutionResult.Success>(result)
        }

        @Test
        fun `single house and single room - both match since types are incompatible`() {
            val engine = createEngine(listOf("h1" to "House", "r1" to "Room"))
            val pattern = TypedPattern(
                elements = listOf(
                    matchInstance("h", "House"),
                    matchInstance("r", "Room")
                )
            )

            val matchExecutor = MatchExecutor()
            val result = matchExecutor.executeMatch(pattern, context, engine)

            assertIs<MatchResult.Matched>(result)
        }
    }

    // ========================================================================
    // 11. Forbid instances are separate from injective core
    // ========================================================================

    @Nested
    inner class ForbidSeparateFromInjective {

        @Test
        fun `forbid does not share injective constraint with matchable`() {
            val engine = createEngine(listOf("r1" to "Room", "r2" to "Room"))

            // Match a:Room and forbid { b:Room } (a condition detached from the match).
            // The block should detect that a Room exists independently,
            // causing the match to FAIL (because a Room IS forbidden and one exists).
            val statement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        matchInstance("a", "Room"),
                        forbidBlock(matchInstance("b", "Room"))
                    )
                )
            )

            val result = engine.executeStatement(statement, context)
            // With 2 rooms present and a detached `forbid { b : Room {} }`,
            // the block finds a Room vertex, so the match fails.
            assertIs<TransformationExecutionResult.Failure>(result)
        }

        @Test
        fun `forbid room absent - match succeeds`() {
            val engine = createEngine(listOf("h1" to "House"))
            val statement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        matchInstance("h", "House"),
                        forbidBlock(matchInstance("fr", "Room"))
                    )
                )
            )

            val result = engine.executeStatement(statement, context)
            assertIs<TransformationExecutionResult.Success>(result)
        }
    }
}
