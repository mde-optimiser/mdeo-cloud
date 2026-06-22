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
 * Tests for binary expression property assignments in create blocks.
 * 
 * This test reproduces the bug where binary expressions like "house.address + 'test'"
 * don't work correctly in property assignments.
 */
class BinaryExpressionPropertyAssignmentTest {
    
    private lateinit var graph: TinkerGraph
    private lateinit var g: GraphTraversalSource
    private lateinit var engine: TransformationEngine
    private val executor = MatchExecutor()
    
    @BeforeEach
    fun setup() {
        graph = TinkerGraph.open()
        g = graph.traversal()
        
        val expressionRegistry = ExpressionCompilerRegistry.createDefaultRegistry()
        val statementRegistry = StatementExecutorRegistry.createDefaultRegistry()

        val metamodelData = com.mdeo.metamodel.data.MetamodelData(
            classes = listOf(
                com.mdeo.metamodel.data.ClassData(
                    name = "__GraphNode",
                    isAbstract = false,
                    properties = listOf(
                        com.mdeo.metamodel.data.PropertyData(name = "address", primitiveType = "string", multiplicity = com.mdeo.metamodel.data.MultiplicityData.single()),
                        com.mdeo.metamodel.data.PropertyData(name = "value", primitiveType = "int", multiplicity = com.mdeo.metamodel.data.MultiplicityData.single())
                    )
                ),
                com.mdeo.metamodel.data.ClassData(
                    name = "House",
                    isAbstract = false,
                    extends = listOf("__GraphNode"),
                    properties = listOf(
                        com.mdeo.metamodel.data.PropertyData(name = "weight", primitiveType = "float", multiplicity = com.mdeo.metamodel.data.MultiplicityData.single())
                    )
                ),
                com.mdeo.metamodel.data.ClassData(
                    name = "Room",
                    isAbstract = false,
                    extends = listOf("__GraphNode"),
                    properties = listOf(
                        com.mdeo.metamodel.data.PropertyData(name = "category", primitiveType = "string", multiplicity = com.mdeo.metamodel.data.MultiplicityData.single()),
                        com.mdeo.metamodel.data.PropertyData(name = "weight", primitiveType = "float", multiplicity = com.mdeo.metamodel.data.MultiplicityData.single())
                    )
                )
            )
        )
        val metamodel = com.mdeo.metamodel.Metamodel.compile(metamodelData)

        engine = TransformationEngine(
            modelGraph = TinkerModelGraph.wrap(graph, metamodel),
            ast = TypedAst(types = emptyList(), metamodelPath = "test://model", statements = emptyList()), // Dummy AST
            expressionCompilerRegistry = expressionRegistry,
            statementExecutorRegistry = statementRegistry
        )

        // Set up a type registry with __GraphNode, House and Room types
        val typeRegistry = TypeRegistry.GLOBAL
        
        // Register __GraphNode with the properties that will be accessed in tests
        val graphNodeType = gremlinType("builtin", "__GraphNode")
            .graphProperty("address", engine.resolvePropertyGraphKey("__GraphNode", "address"))
            .graphProperty("value", engine.resolvePropertyGraphKey("__GraphNode", "value"))
            .build()
        typeRegistry.register(graphNodeType)
        
        val houseType = gremlinType("builtin", "House")
            .extends("builtin", "__GraphNode")
            .graphProperty("address", engine.resolvePropertyGraphKey("House", "address"))
            .graphProperty("value", engine.resolvePropertyGraphKey("House", "value"))
            .graphProperty("weight", engine.resolvePropertyGraphKey("House", "weight"))
            .build()
        val roomType = gremlinType("builtin", "Room")
            .extends("builtin", "__GraphNode")
            .graphProperty("category", engine.resolvePropertyGraphKey("Room", "category"))
            .graphProperty("value", engine.resolvePropertyGraphKey("Room", "value"))
            .graphProperty("weight", engine.resolvePropertyGraphKey("Room", "weight"))
            .build()
        typeRegistry.register(houseType)
        typeRegistry.register(roomType)
        
        // Set up the types array that would normally come from a TypedAst
        // We need this for the expression compilers to resolve types
        val stringType = ClassTypeRef(`package` = "builtin", type = "string", isNullable = false)
        val intType = ClassTypeRef(`package` = "builtin", type = "int", isNullable = false)
        val graphNodeTypeRef = ClassTypeRef(`package` = "builtin", type = "__GraphNode", isNullable = false)
        val floatType = ClassTypeRef(`package` = "builtin", type = "float", isNullable = false)
        val houseTypeRef = ClassTypeRef(`package` = "builtin", type = "House", isNullable = false)
        
        // Use reflection to set the types field since it has a private setter
        val typesField = TransformationEngine::class.java.getDeclaredField("types")
        typesField.isAccessible = true
        typesField.set(engine, listOf(graphNodeTypeRef, stringType, graphNodeTypeRef, intType, floatType, houseTypeRef))
    }
    
    @AfterEach
    fun tearDown() {
        graph.close()
    }
    
    @Test
    fun `string concatenation with member access and literal should work in property assignment`() {
        // Create a House with an address property
        g.addV("House").property(engine.resolvePropertyGraphKey("House", "address"), "123 Main St").next()
        
        // Create a pattern that matches the house and creates a room with category = house.address + "test"
        val pattern = TypedPattern(
            elements = listOf(
                // Match: house: House {}
                TypedPatternObjectInstanceElement(
                    objectInstance = TypedPatternObjectInstance(
                        name = "house",
                        className = "House",
                        modifier = null,
                        properties = emptyList()
                    )
                ),
                // Create: newRoom: Room { category = house.address + "test" }
                TypedPatternObjectInstanceElement(
                    objectInstance = TypedPatternObjectInstance(
                        name = "newRoom",
                        className = "Room",
                        modifier = "create",
                        properties = listOf(
                            TypedPatternPropertyAssignment(
                                propertyName = "category",
                                operator = "=",
                                value = TypedBinaryExpression(
                                    evalType = 1, // string
                                    operator = "+",
                                    left = TypedMemberAccessExpression(
                                        evalType = 1, // string
                                        expression = TypedIdentifierExpression(
                                            evalType = 0, // graph node
                                            name = "house",
                                            scope = 1 // MT scope level (matches real typed ASTs)
                                        ),
                                        member = "address",
                                        isNullChaining = false
                                    ),
                                    right = TypedStringLiteralExpression(
                                        evalType = 1,
                                        value = "test"
                                    )
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
        
        // Get the created room vertex
        val roomId = result.instanceMappings["newRoom"]?.rawId
        assertNotNull(roomId, "newRoom should be mapped to a vertex")
        
        val room = g.V(roomId).next()
        
        // The room should have the "category" property set to house.address + "test"
        val categoryProperty = room.property<String>(engine.resolvePropertyGraphKey("Room", "category"))
        assertTrue(categoryProperty.isPresent, "category property should exist")
        assertEquals("123 Main Sttest", categoryProperty.value(), "category should be set to house.address + 'test'")
    }
    
    @Test
    fun `arithmetic binary expression with member access should work in property assignment`() {
        // Create a House with a value property
        g.addV("House").property(engine.resolvePropertyGraphKey("House", "value"), 100).next()
        
        // Create a pattern that matches the house and creates a room with value = house.value * 10
        val pattern = TypedPattern(
            elements = listOf(
                // Match: house: House {}
                TypedPatternObjectInstanceElement(
                    objectInstance = TypedPatternObjectInstance(
                        name = "house",
                        className = "House",
                        modifier = null,
                        properties = emptyList()
                    )
                ),
                // Create: newRoom: Room { value = house.value * 10 }
                TypedPatternObjectInstanceElement(
                    objectInstance = TypedPatternObjectInstance(
                        name = "newRoom",
                        className = "Room",
                        modifier = "create",
                        properties = listOf(
                            TypedPatternPropertyAssignment(
                                propertyName = "value",
                                operator = "=",
                                value = TypedBinaryExpression(
                                    evalType = 3, // int
                                    operator = "*",
                                    left = TypedMemberAccessExpression(
                                        evalType = 3, // int
                                        expression = TypedIdentifierExpression(
                                            evalType = 0, // graph node
                                            name = "house",
                                            scope = 1 // MT scope level (matches real typed ASTs)
                                        ),
                                        member = "value",
                                        isNullChaining = false
                                    ),
                                    right = TypedIntLiteralExpression(
                                        evalType = 3,
                                        value = "10"
                                    )
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
        
        // Get the created room vertex
        val roomId = result.instanceMappings["newRoom"]?.rawId
        assertNotNull(roomId, "newRoom should be mapped to a vertex")
        
        val room = g.V(roomId).next()
        
        // The room should have the "value" property set to house.value * 10
        val valueProperty = room.property<Int>(engine.resolvePropertyGraphKey("Room", "value"))
        assertTrue(valueProperty.isPresent, "value property should exist")
        assertEquals(1000, valueProperty.value(), "value should be set to house.value * 10")
    }
    
    @Test
    fun `addition binary expression with two literals should work in property assignment`() {
        // Test the simple case: room.value = 100 * 10 (two literals)
        val pattern = TypedPattern(
            elements = listOf(
                // Create: newRoom: Room { value = 100 * 10 }
                TypedPatternObjectInstanceElement(
                    objectInstance = TypedPatternObjectInstance(
                        name = "newRoom",
                        className = "Room",
                        modifier = "create",
                        properties = listOf(
                            TypedPatternPropertyAssignment(
                                propertyName = "value",
                                operator = "=",
                                value = TypedBinaryExpression(
                                    evalType = 3, // int
                                    operator = "*",
                                    left = TypedIntLiteralExpression(
                                        evalType = 3,
                                        value = "100"
                                    ),
                                    right = TypedIntLiteralExpression(
                                        evalType = 3,
                                        value = "10"
                                    )
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
        
        // Get the created room vertex
        val roomId = result.instanceMappings["newRoom"]?.rawId
        assertNotNull(roomId, "newRoom should be mapped to a vertex")
        
        val room = g.V(roomId).next()
        
        // The room should have the "value" property set to 1000
        val valueProperty = room.property<Int>(engine.resolvePropertyGraphKey("Room", "value"))
        assertTrue(valueProperty.isPresent, "value property should exist")
        assertEquals(1000, valueProperty.value(), "value should be set to 100 * 10")
    }

    @Test
    fun `integer addition binary expression should be assigned as Int`() {
        // Create a House with a value property
        g.addV("House").property(engine.resolvePropertyGraphKey("House", "value"), 100).next()

        // Create a pattern that matches the house and creates a room with value = house.value + 10
        val pattern = TypedPattern(
            elements = listOf(
                // Match: house: House {}
                TypedPatternObjectInstanceElement(
                    objectInstance = TypedPatternObjectInstance(
                        name = "house",
                        className = "House",
                        modifier = null,
                        properties = emptyList()
                    )
                ),
                // Create: newRoom: Room { value = house.value + 10 }
                TypedPatternObjectInstanceElement(
                    objectInstance = TypedPatternObjectInstance(
                        name = "newRoom",
                        className = "Room",
                        modifier = "create",
                        properties = listOf(
                            TypedPatternPropertyAssignment(
                                propertyName = "value",
                                operator = "=",
                                value = TypedBinaryExpression(
                                    evalType = 3, // int
                                    operator = "+",
                                    left = TypedMemberAccessExpression(
                                        evalType = 3, // int
                                        expression = TypedIdentifierExpression(
                                            evalType = 0, // graph node
                                            name = "house",
                                            scope = 1 // MT scope level
                                        ),
                                        member = "value",
                                        isNullChaining = false
                                    ),
                                    right = TypedIntLiteralExpression(
                                        evalType = 3,
                                        value = "10"
                                    )
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

        val roomId = result.instanceMappings["newRoom"]?.rawId
        assertNotNull(roomId, "newRoom should be mapped to a vertex")

        val room = g.V(roomId).next()

        val valueProperty = room.property<Any>(engine.resolvePropertyGraphKey("Room", "value"))
        assertTrue(valueProperty.isPresent, "value property should exist")
        assertTrue(valueProperty.value() is Int, "value should be of type Int")
        assertEquals(110, valueProperty.value(), "value should be set to house.value + 10")
    }

    @Test
    fun `float addition binary expression should be assigned as Float`() {
        // Create a House with a weight property
        g.addV("House").property(engine.resolvePropertyGraphKey("House", "weight"), 10.5f).next()

        // Create a pattern that matches the house and creates a room with weight = house.weight + 5.5
        val pattern = TypedPattern(
            elements = listOf(
                // Match: house: House {}
                TypedPatternObjectInstanceElement(
                    objectInstance = TypedPatternObjectInstance(
                        name = "house",
                        className = "House",
                        modifier = null,
                        properties = emptyList()
                    )
                ),
                // Create: newRoom: Room { weight = house.weight + 5.5 }
                TypedPatternObjectInstanceElement(
                    objectInstance = TypedPatternObjectInstance(
                        name = "newRoom",
                        className = "Room",
                        modifier = "create",
                        properties = listOf(
                            TypedPatternPropertyAssignment(
                                propertyName = "weight",
                                operator = "=",
                                value = TypedBinaryExpression(
                                    evalType = 4, // float
                                    operator = "+",
                                    left = TypedMemberAccessExpression(
                                        evalType = 4, // float
                                        expression = TypedIdentifierExpression(
                                            evalType = 5, // House
                                            name = "house",
                                            scope = 1 // MT scope level
                                        ),
                                        member = "weight",
                                        isNullChaining = false
                                    ),
                                    right = TypedFloatLiteralExpression(
                                        evalType = 4,
                                        value = "5.5"
                                    )
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

        val roomId = result.instanceMappings["newRoom"]?.rawId
        assertNotNull(roomId, "newRoom should be mapped to a vertex")

        val room = g.V(roomId).next()

        val weightProperty = room.property<Any>(engine.resolvePropertyGraphKey("Room", "weight"))
        assertTrue(weightProperty.isPresent, "weight property should exist")
        assertTrue(weightProperty.value() is Float, "weight should be of type Float")
        assertEquals(16.0f, weightProperty.value(), "weight should be set to house.weight + 5.5")
    }
}
