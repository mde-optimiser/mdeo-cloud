package com.mdeo.modeltransformation.runtime.statements

import com.mdeo.expression.ast.expressions.*
import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.modeltransformation.ast.TypedAst
import com.mdeo.modeltransformation.ast.patterns.*
import com.mdeo.modeltransformation.ast.statements.TypedMatchStatement
import com.mdeo.modeltransformation.compiler.ExpressionCompilerRegistry
import com.mdeo.modeltransformation.graph.tinker.TinkerModelGraph
import com.mdeo.modeltransformation.compiler.VariableBinding
import com.mdeo.modeltransformation.compiler.registry.TypeRegistry
import com.mdeo.modeltransformation.compiler.registry.gremlinType
import com.mdeo.modeltransformation.runtime.StatementExecutorRegistry
import com.mdeo.modeltransformation.runtime.TransformationEngine
import com.mdeo.modeltransformation.runtime.TransformationExecutionContext
import com.mdeo.modeltransformation.runtime.TransformationExecutionResult
import com.mdeo.modeltransformation.runtime.isFailure
import com.mdeo.modeltransformation.runtime.isSuccess
import com.mdeo.modeltransformation.runtime.testHasInstance
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.`__` as AnonymousTraversal
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for variables being accessible across multiple match statements.
 *
 * These tests verify that:
 * 1. Variables from a first match are visible in subsequent matches
 * 2. Relations can be created between objects from different matches  
 * 3. New objects in later matches can reference variables from previous matches
 *
 * Bug #1: Second match doesn't create relation when both source and target
 *         come from a previous match (create house -- newRoom fails)
 *
 * Bug #2: NoClassDefFoundError for CompilationException$Companion when
 *         creating new objects that reference variables from outer scope
 */
class CrossMatchVariableTest {

    private lateinit var graph: TinkerGraph
    private lateinit var engine: TransformationEngine
    private lateinit var context: TransformationExecutionContext

    @BeforeEach
    fun setUp() {
        graph = TinkerGraph.open()
        
        // Set up type registry with House and Room types
        val typeRegistry = TypeRegistry.GLOBAL
        
        val graphNodeType = gremlinType("builtin", "__GraphNode")
            .graphProperty("address")
            .graphProperty("category")
            .graphProperty("value")
            .build()
        typeRegistry.register(graphNodeType)
        
        val houseType = gremlinType("builtin", "House")
            .extends("builtin", "__GraphNode")
            .graphProperty("address")
            .build()
        typeRegistry.register(houseType)
        
        val roomType = gremlinType("builtin", "Room")
            .extends("builtin", "__GraphNode")
            .graphProperty("category")
            .graphProperty("value")
            .build()
        typeRegistry.register(roomType)
        
        val expressionRegistry = ExpressionCompilerRegistry.createDefaultRegistry()
        val statementRegistry = StatementExecutorRegistry.createDefaultRegistry()
        
        // Define types array that would come from a TypedAst
        val stringType = ClassTypeRef(`package` = "builtin", type = "string", isNullable = false)
        val intType = ClassTypeRef(`package` = "builtin", type = "int", isNullable = false)
        val houseTypeRef = ClassTypeRef(`package` = "builtin", type = "House", isNullable = false)
        val roomTypeRef = ClassTypeRef(`package` = "builtin", type = "Room", isNullable = false)
        
        // Create AST with empty statements - we'll execute statements individually
        val ast = TypedAst(
            types = listOf(stringType, intType, houseTypeRef, roomTypeRef),
            metamodelPath = "test://model",
            statements = emptyList()
        )
        
        engine = TransformationEngine(
            modelGraph = TinkerModelGraph.wrap(graph),
            ast = ast,
            expressionCompilerRegistry = expressionRegistry,
            statementExecutorRegistry = statementRegistry
        )
        
        context = TransformationExecutionContext.empty()
    }

    @AfterEach
    fun tearDown() {
        graph.close()
    }

    @Nested
    inner class Bug1_RelationNotCreated {

        /**
         * Reproduces Bug #1:
         * 
         * match {
         *     house: House {}
         *     create newRoom: Room { category = "test", value = 100 }
         * }
         * match {
         *     create house -- newRoom
         * }
         * 
         * The second match should create the relation but doesn't because
         * the context from the first match is not passed to handleEmptyPattern.
         */
        @Test
        fun `second match creates relation between variables from first match`() {
            // Set up: Create a House in the graph
            val houseVertex = graph.addVertex("House")
            houseVertex.property("address", "123 Main St")
            
            // First match statement: match house, create newRoom
            val firstMatch = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        // Match: house: House {}
                        TypedPatternObjectInstanceElement(
                            objectInstance = TypedPatternObjectInstance(
                                modifier = null,
                                name = "house",
                                className = "House",
                                properties = emptyList()
                            )
                        ),
                        // Create: newRoom: Room { category = "test", value = 100 }
                        TypedPatternObjectInstanceElement(
                            objectInstance = TypedPatternObjectInstance(
                                modifier = "create",
                                name = "newRoom",
                                className = "Room",
                                properties = listOf(
                                    TypedPatternPropertyAssignment(
                                        propertyName = "category",
                                        operator = "=",
                                        value = TypedStringLiteralExpression(
                                            evalType = 0,
                                            value = "test"
                                        )
                                    ),
                                    TypedPatternPropertyAssignment(
                                        propertyName = "value",
                                        operator = "=",
                                        value = TypedIntLiteralExpression(
                                            evalType = 1,
                                            value = "100"
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
            )
            
            // Execute first match
            val firstResult = engine.executeStatement(firstMatch, context)
            assertIs<TransformationExecutionResult.Success>(firstResult)
            
            // Verify first match created the room
            assertTrue(context.testHasInstance("house"), "house should be bound")
            assertTrue(context.testHasInstance("newRoom"), "newRoom should be bound")
            assertEquals(1L, graph.traversal().V().hasLabel("Room").count().next())
            
            // Second match statement: create link between house and newRoom
            // Note: This is a create-only pattern (no matchable elements)
            val secondMatch = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        // Create: house -- newRoom (link from house.rooms to newRoom.house)
                        TypedPatternLinkElement(
                            link = TypedPatternLink(
                                modifier = "create",
                                source = TypedPatternLinkEnd(
                                    objectName = "house",
                                    propertyName = "rooms"
                                ),
                                target = TypedPatternLinkEnd(
                                    objectName = "newRoom",
                                    propertyName = "house"
                                )
                            )
                        )
                    )
                )
            )
            
            // Execute second match - THIS IS WHERE THE BUG MANIFESTS
            val secondResult = engine.executeStatement(secondMatch, context)
            
            // BUG: The result should be Success but it fails because
            // the context is not passed to handleEmptyPattern
            assertIs<TransformationExecutionResult.Success>(secondResult, 
                "Second match should succeed - context should contain house and newRoom")
            
            // Verify the edge was created
            val houseId = (context.variableScope.getVariable("house") as? VariableBinding.InstanceBinding)?.vertexId
            val roomId = (context.variableScope.getVariable("newRoom") as? VariableBinding.InstanceBinding)?.vertexId
            
            // Check edge exists between house and room
            val edgeCount = graph.traversal().V(houseId)
                .outE("`rooms`_`house`")
                .where(AnonymousTraversal.inV().hasId(roomId))
                .count().next()
            
            assertEquals(1L, edgeCount, "Edge should be created between house and newRoom")
        }
    }

    @Nested
    inner class Bug2_CompilationExceptionCompanion {

        /**
         * Reproduces Bug #2:
         *
         * match {
         *     house: House {}
         *     create newRoom: Room { category = house.address + "test", value = 100 * 10 }
         * }
         * match {
         *     create house -- newRoom
         *     create example: House { address = house.address + "test" }
         * }
         *
         * The second match causes NoClassDefFoundError for CompilationException$Companion
         * because the IdentifierCompiler tries to resolve "house" but:
         * 1. matchedInstanceNames is empty (not passed from context)
         * 2. transformationContext is null (not passed in buildCompilationContext)
         * 3. When it fails, it tries to throw CompilationException.unresolvedVariable
         *    but the companion object initialization fails
         */
        @Test
        fun `second match can create objects that reference variables from first match`() {
            // Set up: Create a House in the graph
            val houseVertex = graph.addVertex("House")
            houseVertex.property("address", "123 Main St")
            
            // First match statement: match house, create newRoom with property access
            val firstMatch = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        TypedPatternObjectInstanceElement(
                            objectInstance = TypedPatternObjectInstance(
                                modifier = null,
                                name = "house",
                                className = "House",
                                properties = emptyList()
                            )
                        ),
                        TypedPatternObjectInstanceElement(
                            objectInstance = TypedPatternObjectInstance(
                                modifier = "create",
                                name = "newRoom",
                                className = "Room",
                                properties = listOf(
                                    TypedPatternPropertyAssignment(
                                        propertyName = "category",
                                        operator = "=",
                                        // house.address + "test"
                                        value = TypedBinaryExpression(
                                            evalType = 0,
                                            operator = "+",
                                            left = TypedMemberAccessExpression(
                                                evalType = 0,
                                                expression = TypedIdentifierExpression(
                                                    evalType = 2, // House type
                                                    name = "house",
                                                    scope = 1 // MT scope
                                                ),
                                                member = "address",
                                                isNullChaining = false
                                            ),
                                            right = TypedStringLiteralExpression(
                                                evalType = 0,
                                                value = "test"
                                            )
                                        )
                                    ),
                                    TypedPatternPropertyAssignment(
                                        propertyName = "value",
                                        operator = "=",
                                        // 100 * 10
                                        value = TypedBinaryExpression(
                                            evalType = 1,
                                            operator = "*",
                                            left = TypedIntLiteralExpression(
                                                evalType = 1,
                                                value = "100"
                                            ),
                                            right = TypedIntLiteralExpression(
                                                evalType = 1,
                                                value = "10"
                                            )
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
            )
            
            // Execute first match
            val firstResult = engine.executeStatement(firstMatch, context)
            assertIs<TransformationExecutionResult.Success>(firstResult)
            
            // Verify first match results
            assertTrue(context.testHasInstance("house"))
            assertTrue(context.testHasInstance("newRoom"))
            
            val roomId = (context.variableScope.getVariable("newRoom") as? VariableBinding.InstanceBinding)?.vertexId
            val category = graph.traversal().V(roomId).values<String>("category").next()
            assertEquals("123 Main Sttest", category, "Category should be house.address + 'test'")
            
            val value = graph.traversal().V(roomId).values<Number>("value").next()
            assertEquals(1000, value.toInt(), "Value should be 100 * 10")
            
            // Second match: create-only pattern with both link and object creation
            // that references variables from outer scope
            val secondMatch = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        // Create link: house -- newRoom
                        TypedPatternLinkElement(
                            link = TypedPatternLink(
                                modifier = "create",
                                source = TypedPatternLinkEnd(
                                    objectName = "house",
                                    propertyName = "rooms"
                                ),
                                target = TypedPatternLinkEnd(
                                    objectName = "newRoom",
                                    propertyName = "house"
                                )
                            )
                        ),
                        // Create object: example: House { address = house.address + "test" }
                        TypedPatternObjectInstanceElement(
                            objectInstance = TypedPatternObjectInstance(
                                modifier = "create",
                                name = "example",
                                className = "House",
                                properties = listOf(
                                    TypedPatternPropertyAssignment(
                                        propertyName = "address",
                                        operator = "=",
                                        // This expression references "house" from first match
                                        // BUG: This causes NoClassDefFoundError
                                        value = TypedBinaryExpression(
                                            evalType = 0,
                                            operator = "+",
                                            left = TypedMemberAccessExpression(
                                                evalType = 0,
                                                expression = TypedIdentifierExpression(
                                                    evalType = 2, // House type
                                                    name = "house",
                                                    scope = 1 // MT scope
                                                ),
                                                member = "address",
                                                isNullChaining = false
                                            ),
                                            right = TypedStringLiteralExpression(
                                                evalType = 0,
                                                value = "test"
                                            )
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
            )
            
            // Execute second match - THIS IS WHERE BUG #2 MANIFESTS
            // It throws: java.lang.NoClassDefFoundError: 
            //   com/mdeo/modeltransformation/compiler/CompilationException$Companion
            val secondResult = engine.executeStatement(secondMatch, context)
            
            assertIs<TransformationExecutionResult.Success>(secondResult,
                "Second match should succeed - should resolve 'house' from context")
            
            // Verify edge was created
            val houseId = (context.variableScope.getVariable("house") as? VariableBinding.InstanceBinding)?.vertexId
            val newRoomId = (context.variableScope.getVariable("newRoom") as? VariableBinding.InstanceBinding)?.vertexId
            val edgeCount = graph.traversal().V(houseId)
                .outE("`rooms`_`house`")
                .where(AnonymousTraversal.inV().hasId(newRoomId))
                .count().next()
            assertEquals(1L, edgeCount, "Edge should exist between house and newRoom")
            
            // Verify example house was created with correct address
            val exampleId = (context.variableScope.getVariable("example") as? VariableBinding.InstanceBinding)?.vertexId
            assertNotNull(exampleId, "example should be bound in context")
            
            val exampleAddress = graph.traversal().V(exampleId).values<String>("address").next()
            assertEquals("123 Main Sttest", exampleAddress, 
                "example.address should be house.address + 'test'")
        }
    }

    @Nested
    inner class VariableVisibilityTests {

        @Test
        fun `instances from first match are visible in second match context`() {
            val houseVertex = graph.addVertex("House")
            houseVertex.property("address", "Test Address")
            
            // First match
            val firstMatch = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        TypedPatternObjectInstanceElement(
                            objectInstance = TypedPatternObjectInstance(
                                modifier = null,
                                name = "myHouse",
                                className = "House",
                                properties = emptyList()
                            )
                        )
                    )
                )
            )
            
            val firstResult = engine.executeStatement(firstMatch, context)
            assertIs<TransformationExecutionResult.Success>(firstResult)
            
            assertTrue(context.testHasInstance("myHouse"), "myHouse should be in context")
            assertEquals(houseVertex.id(), (context.variableScope.getVariable("myHouse") as? VariableBinding.InstanceBinding)?.vertexId)
            
            // Second match - create only pattern that uses myHouse
            val secondMatch = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        TypedPatternObjectInstanceElement(
                            objectInstance = TypedPatternObjectInstance(
                                modifier = "create",
                                name = "aRoom",
                                className = "Room",
                                properties = listOf(
                                    TypedPatternPropertyAssignment(
                                        propertyName = "category",
                                        operator = "=",
                                        // Reference myHouse.address from first match
                                        value = TypedMemberAccessExpression(
                                            evalType = 0,
                                            expression = TypedIdentifierExpression(
                                                evalType = 2,
                                                name = "myHouse",
                                                scope = 1
                                            ),
                                            member = "address",
                                            isNullChaining = false
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
            )
            
            val secondResult = engine.executeStatement(secondMatch, context)
            
            assertIs<TransformationExecutionResult.Success>(secondResult,
                "Second match should succeed - myHouse should be accessible")
            
            val roomId = (context.variableScope.getVariable("aRoom") as? VariableBinding.InstanceBinding)?.vertexId
            assertNotNull(roomId, "aRoom should be bound in context")
            
            val vertex = graph.traversal().V(roomId).next()
            val props = vertex.properties<Any>().asSequence().map { "${it.key()}=${it.value()}" }.toList()
            
            // Check if category property exists before asserting
            val hasCategory = graph.traversal().V(roomId).has("category").hasNext()
            assertTrue(hasCategory, "Room should have 'category' property. Vertex properties: $props")
            
            val category = graph.traversal().V(roomId).values<String>("category").next()
            assertEquals("Test Address", category, 
                "Room category should be set from myHouse.address")
        }
    }
}
