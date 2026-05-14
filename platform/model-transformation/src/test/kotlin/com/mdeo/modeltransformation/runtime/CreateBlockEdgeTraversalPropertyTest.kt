package com.mdeo.modeltransformation.runtime

import com.mdeo.expression.ast.expressions.*
import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.modeltransformation.ast.TypedAst
import com.mdeo.modeltransformation.ast.patterns.*
import com.mdeo.modeltransformation.compiler.ExpressionCompilerRegistry
import com.mdeo.modeltransformation.graph.tinker.TinkerModelGraph
import com.mdeo.modeltransformation.compiler.registry.TypeRegistry
import com.mdeo.modeltransformation.compiler.registry.gremlinType
import com.mdeo.modeltransformation.runtime.match.MatchResult
import com.mdeo.modeltransformation.runtime.match.MatchExecutor
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Regression tests for property expressions in create blocks that traverse edges.
 *
 * Reproduces the bug where `addV().property(key, traversal)` was used directly
 * for traversal-based property values on created instances. TinkerPop does not
 * evaluate nested traversals in AddVertexStep, so complex expressions such as
 * `source.link.value` (which traverses an edge) were not evaluated but stored
 * as traversal objects, causing a runtime error.
 *
 * The fix uses a sideEffect-based approach for all traversal-based property
 * assignments on created instances.
 */
class CreateBlockEdgeTraversalPropertyTest {

    private lateinit var graph: TinkerGraph
    private lateinit var g: GraphTraversalSource
    private lateinit var engine: TransformationEngine
    private val executor = MatchExecutor()

    // Type indices in the types array
    private val sourceTypeIdx = 0   // EdgeSource
    private val targetTypeIdx = 1   // EdgeTarget
    private val intTypeIdx = 2      // int

    @BeforeEach
    fun setup() {
        graph = TinkerGraph.open()
        g = graph.traversal()

        val typeRegistry = TypeRegistry.GLOBAL

        // EdgeSource: has an outgoing "link" association to EdgeTarget
        val sourceType = gremlinType("test", "EdgeSource")
            .association("link", "link_edge", isOutgoing = true, isNullable = false)
            .build()
        typeRegistry.register(sourceType)

        // EdgeTarget: has a "value" graph property
        val targetType = gremlinType("test", "EdgeTarget")
            .graphProperty("value")
            .build()
        typeRegistry.register(targetType)

        val expressionRegistry = ExpressionCompilerRegistry.createDefaultRegistry()
        val statementRegistry = StatementExecutorRegistry.createDefaultRegistry()

        val sourceTypeRef = ClassTypeRef(`package` = "test", type = "EdgeSource", isNullable = false)
        val targetTypeRef = ClassTypeRef(`package` = "test", type = "EdgeTarget", isNullable = false)
        val intTypeRef = ClassTypeRef(`package` = "builtin", type = "int", isNullable = false)

        val ast = TypedAst(
            types = listOf(sourceTypeRef, targetTypeRef, intTypeRef),
            metamodelPath = "test://edge-traversal-test",
            statements = emptyList()
        )

        engine = TransformationEngine(
            modelGraph = TinkerModelGraph.wrap(graph),
            ast = ast,
            expressionCompilerRegistry = expressionRegistry,
            statementExecutorRegistry = statementRegistry
        )
    }

    @AfterEach
    fun tearDown() {
        graph.close()
    }

    /**
     * Reproduces the bug: property in create block is assigned via edge traversal.
     *
     * Pattern:
     *   match {
     *     src: EdgeSource {}
     *     create created: EdgeTarget {
     *       value = src.link.value
     *     }
     *   }
     *
     * `src.link.value` traverses an edge, producing a TraversalResult. Previously this
     * traversal was embedded directly inside AddVertexStep.property(), which does not
     * evaluate nested traversals. The fix uses a sideEffect to pre-evaluate the traversal.
     */
    @Test
    fun `create block can assign property using edge traversal from matched node`() {
        // Graph setup: source --link_edge--> target (target has value=42)
        val source = g.addV("EdgeSource").next()
        val target = g.addV("EdgeTarget").property("value", 42).next()
        g.addE("link_edge").from(source).to(target).next()

        // Pattern: match src, create new EdgeTarget with value = src.link.value
        val pattern = TypedPattern(
            elements = listOf(
                // match: src: EdgeSource {}
                TypedPatternObjectInstanceElement(
                    objectInstance = TypedPatternObjectInstance(
                        name = "src",
                        className = "EdgeSource",
                        modifier = null,
                        properties = emptyList()
                    )
                ),
                // create: created: EdgeTarget { value = src.link.value }
                TypedPatternObjectInstanceElement(
                    objectInstance = TypedPatternObjectInstance(
                        name = "created",
                        className = "EdgeTarget",
                        modifier = "create",
                        properties = listOf(
                            TypedPatternPropertyAssignment(
                                propertyName = "value",
                                operator = "=",
                                // src.link.value:
                                // TypedMemberAccessExpression(
                                //   member="value",
                                //   expression=TypedMemberAccessExpression(
                                //     member="link",
                                //     expression=TypedIdentifierExpression(name="src")
                                //   )
                                // )
                                value = TypedMemberAccessExpression(
                                    evalType = intTypeIdx,
                                    expression = TypedMemberAccessExpression(
                                        evalType = targetTypeIdx,
                                        expression = TypedIdentifierExpression(
                                            evalType = sourceTypeIdx,
                                            name = "src",
                                            scope = 1
                                        ),
                                        member = "link",
                                        isNullChaining = false
                                    ),
                                    member = "value",
                                    isNullChaining = false
                                )
                            )
                        )
                    )
                )
            )
        )

        val context = TransformationExecutionContext.empty()
        val result = executor.executeMatch(pattern, context, engine)

        assertTrue(result is MatchResult.Matched, "Match should succeed")
        result as MatchResult.Matched

        val createdId = result.instanceMappings["created"]?.rawId
        assertNotNull(createdId, "created should be mapped to a vertex")

        val createdVertex = g.V(createdId).next()
        val valueProp = createdVertex.property<Int>("value")
        assertTrue(valueProp.isPresent, "value property should exist on created vertex")
        assertEquals(42, valueProp.value(), "value should be copied from src.link.value")
    }

    /**
     * Verifies that multiple edge-traversal property assignments in a single create block
     * all resolve correctly.
     */
    @Test
    fun `create block can assign multiple properties using edge traversal`() {
        // EdgeTarget2 has both a value and a second property to copy
        val typeRegistry = TypeRegistry.GLOBAL
        val target2Type = gremlinType("test", "EdgeTarget2")
            .graphProperty("value")
            .graphProperty("score")
            .build()
        typeRegistry.register(target2Type)

        val sourceType2 = gremlinType("test", "EdgeSource2")
            .association("link", "link_edge2", isOutgoing = true, isNullable = false)
            .build()
        typeRegistry.register(sourceType2)

        val source2TypeRef = ClassTypeRef(`package` = "test", type = "EdgeSource2", isNullable = false)
        val target2TypeRef = ClassTypeRef(`package` = "test", type = "EdgeTarget2", isNullable = false)
        val intTypeRef = ClassTypeRef(`package` = "builtin", type = "int", isNullable = false)

        val ast2 = TypedAst(
            types = listOf(source2TypeRef, target2TypeRef, intTypeRef),
            metamodelPath = "test://edge-traversal-test2",
            statements = emptyList()
        )
        val engine2 = TransformationEngine(
            modelGraph = TinkerModelGraph.wrap(graph),
            ast = ast2,
            expressionCompilerRegistry = ExpressionCompilerRegistry.createDefaultRegistry(),
            statementExecutorRegistry = StatementExecutorRegistry.createDefaultRegistry()
        )

        val source = g.addV("EdgeSource2").next()
        val target = g.addV("EdgeTarget2").property("value", 10).property("score", 99).next()
        g.addE("link_edge2").from(source).to(target).next()

        val pattern = TypedPattern(
            elements = listOf(
                TypedPatternObjectInstanceElement(
                    objectInstance = TypedPatternObjectInstance(
                        name = "src",
                        className = "EdgeSource2",
                        modifier = null,
                        properties = emptyList()
                    )
                ),
                TypedPatternObjectInstanceElement(
                    objectInstance = TypedPatternObjectInstance(
                        name = "created",
                        className = "EdgeTarget2",
                        modifier = "create",
                        properties = listOf(
                            TypedPatternPropertyAssignment(
                                propertyName = "value",
                                operator = "=",
                                value = TypedMemberAccessExpression(
                                    evalType = intTypeIdx,
                                    expression = TypedMemberAccessExpression(
                                        evalType = 1, // target2
                                        expression = TypedIdentifierExpression(
                                            evalType = 0, // source2
                                            name = "src",
                                            scope = 1
                                        ),
                                        member = "link",
                                        isNullChaining = false
                                    ),
                                    member = "value",
                                    isNullChaining = false
                                )
                            ),
                            TypedPatternPropertyAssignment(
                                propertyName = "score",
                                operator = "=",
                                value = TypedMemberAccessExpression(
                                    evalType = intTypeIdx,
                                    expression = TypedMemberAccessExpression(
                                        evalType = 1, // target2
                                        expression = TypedIdentifierExpression(
                                            evalType = 0, // source2
                                            name = "src",
                                            scope = 1
                                        ),
                                        member = "link",
                                        isNullChaining = false
                                    ),
                                    member = "score",
                                    isNullChaining = false
                                )
                            )
                        )
                    )
                )
            )
        )

        val context = TransformationExecutionContext.empty()
        val result = executor.executeMatch(pattern, context, engine2)

        assertTrue(result is MatchResult.Matched, "Match should succeed")
        result as MatchResult.Matched

        val createdId = result.instanceMappings["created"]?.rawId
        assertNotNull(createdId, "created should be mapped to a vertex")

        val createdVertex = g.V(createdId).next()
        assertEquals(10, createdVertex.property<Int>("value").value(), "value should be 10")
        assertEquals(99, createdVertex.property<Int>("score").value(), "score should be 99")
    }
}
