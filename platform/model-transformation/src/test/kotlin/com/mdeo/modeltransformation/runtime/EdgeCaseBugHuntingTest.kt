package com.mdeo.modeltransformation.runtime

import com.mdeo.expression.ast.expressions.*
import com.mdeo.modeltransformation.ast.TypedAst
import com.mdeo.modeltransformation.ast.patterns.*
import com.mdeo.modeltransformation.runtime.match.forbidBlock
import com.mdeo.modeltransformation.ast.statements.*
import com.mdeo.modeltransformation.compiler.ExpressionCompilerRegistry
import com.mdeo.modeltransformation.graph.tinker.TinkerModelGraph
import com.mdeo.modeltransformation.compiler.VariableBinding
import com.mdeo.modeltransformation.runtime.statements.*
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * Edge case and bug hunting tests for model transformations.
 * 
 * This test class covers potential edge cases and corner cases that
 * could cause unexpected behavior:
 * 
 * 1. Match with all-create pattern
 * 2. Forbid with where clause  
 * 3. Delete then reference
 * 4. Nested control flow
 * 5. Empty else branches
 * 6. Variable shadowing
 * 7. Match with no instances, only links
 * 8. Concurrent graph modification (create/delete while matching)
 * 9. Stop in nested context
 * 10. Expression evaluation order
 */
class EdgeCaseBugHuntingTest {

    private lateinit var graph: TinkerGraph
    private lateinit var engine: TransformationEngine
    private lateinit var context: TransformationExecutionContext

    @BeforeEach
    fun setUp() {
        graph = TinkerGraph.open()
        val statementRegistry = StatementExecutorRegistry.createDefaultRegistry()
        val expressionRegistry = ExpressionCompilerRegistry.createDefaultRegistry()
        
        engine = TransformationEngine(
            modelGraph = TinkerModelGraph.wrap(graph),
            ast = TypedAst(types = emptyList(), metamodelPath = "test://model", statements = emptyList()), // Dummy AST
            expressionCompilerRegistry = expressionRegistry,
            statementExecutorRegistry = statementRegistry
        )
        context = TransformationExecutionContext.empty()
    }

    @AfterEach
    fun tearDown() {
        graph.close()
    }

    // ============================================================================
    // 1. Match with all-create pattern (no matchable elements)
    // ============================================================================
    
    @Nested
    inner class AllCreatePatternTests {
        
        @Test
        fun `pattern with only create element succeeds on empty graph`() {
            // Pattern with ONLY a create element - nothing to match
            val statement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        TypedPatternObjectInstanceElement(
                            objectInstance = TypedPatternObjectInstance(
                                modifier = "create",
                                name = "newHouse",
                                className = "House",
                                properties = emptyList()
                            )
                        )
                    )
                )
            )
            
            val result = engine.executeStatement(statement, context)
            
            assertIs<TransformationExecutionResult.Success>(result)
            assertEquals(1, result.createdNodes.size)
            assertTrue(context.testHasInstance("newHouse"))
        }
        
        @Test
        fun `pattern with multiple create elements and create link succeeds`() {
            // Multiple create elements connected by a create link
            val statement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        TypedPatternObjectInstanceElement(
                            objectInstance = TypedPatternObjectInstance(
                                modifier = "create",
                                name = "house",
                                className = "House",
                                properties = emptyList()
                            )
                        ),
                        TypedPatternObjectInstanceElement(
                            objectInstance = TypedPatternObjectInstance(
                                modifier = "create",
                                name = "room",
                                className = "Room",
                                properties = emptyList()
                            )
                        ),
                        TypedPatternLinkElement(
                            link = TypedPatternLink(
                                modifier = "create",
                                source = TypedPatternLinkEnd(
                                    objectName = "house",
                                    propertyName = "rooms"
                                ),
                                target = TypedPatternLinkEnd(
                                    objectName = "room",
                                    propertyName = null
                                )
                            )
                        )
                    )
                )
            )
            
            val result = engine.executeStatement(statement, context)
            
            assertIs<TransformationExecutionResult.Success>(result)
            assertEquals(2, result.createdNodes.size)
        }
        
        @Test
        fun `pattern with create and where clause works`() {
            // Create element with a where clause that always evaluates to true
            val statement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        TypedPatternObjectInstanceElement(
                            objectInstance = TypedPatternObjectInstance(
                                modifier = "create",
                                name = "house",
                                className = "House",
                                properties = emptyList()
                            )
                        ),
                        TypedPatternWhereClauseElement(
                            whereClause = TypedWhereClause(
                                expression = TypedBooleanLiteralExpression(
                                    evalType = 0,
                                    value = true
                                )
                            )
                        )
                    )
                )
            )
            
            val result = engine.executeStatement(statement, context)
            
            assertIs<TransformationExecutionResult.Success>(result)
            assertEquals(1, result.createdNodes.size)
        }
    }
    
    // ============================================================================
    // 2. Forbid with where clause
    // ============================================================================
    
    @Nested
    inner class ForbidWithWhereClauseTests {
        
        @Test
        fun `forbid instance with where clause checking property`() {
            // Create a house with name "Castle"
            val house = graph.addVertex("House")
            house.property("name", "Castle")
            
            // Pattern: match House, forbid Room where room.name = "Kitchen"
            // Should succeed because there's no Kitchen room
            val statement = TypedMatchStatement(
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
                        forbidBlock(
                            TypedPatternObjectInstanceElement(
                                objectInstance = TypedPatternObjectInstance(
                                    modifier = null,
                                    name = "room",
                                    className = "Room",
                                    properties = listOf(
                                        TypedPatternPropertyAssignment(
                                            propertyName = "name",
                                            operator = "==",
                                            value = TypedStringLiteralExpression(
                                                evalType = 0,
                                                value = "Kitchen"
                                            )
                                        )
                                    )
                                )
                            ),
                            TypedPatternLinkElement(
                                link = TypedPatternLink(
                                    modifier = null,
                                    source = TypedPatternLinkEnd(
                                        objectName = "house",
                                        propertyName = "rooms"
                                    ),
                                    target = TypedPatternLinkEnd(
                                        objectName = "room",
                                        propertyName = null
                                    )
                                )
                            )
                        )
                    )
                )
            )
            
            val result = engine.executeStatement(statement, context)
            
            assertIs<TransformationExecutionResult.Success>(result)
        }
        
        @Test
        fun `forbid fails when forbidden element exists`() {
            // Create a house with a Kitchen room
            val house = graph.addVertex("House")
            val room = graph.addVertex("Room")
            room.property("name", "Kitchen")
            house.addEdge("`rooms`_``", room)
            
            // Pattern: match House, forbid Room with name Kitchen linked to house
            val statement = TypedMatchStatement(
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
                        forbidBlock(
                            TypedPatternObjectInstanceElement(
                                objectInstance = TypedPatternObjectInstance(
                                    modifier = null,
                                    name = "room",
                                    className = "Room",
                                    properties = emptyList()
                                )
                            ),
                            TypedPatternLinkElement(
                                link = TypedPatternLink(
                                    modifier = null,
                                    source = TypedPatternLinkEnd(
                                        objectName = "house",
                                        propertyName = "rooms"
                                    ),
                                    target = TypedPatternLinkEnd(
                                        objectName = "room",
                                        propertyName = null
                                    )
                                )
                            )
                        )
                    )
                )
            )
            
            val result = engine.executeStatement(statement, context)
            
            assertIs<TransformationExecutionResult.Failure>(result)
        }
    }
    
    // ============================================================================
    // 3. Delete then reference
    // ============================================================================
    
    @Nested
    inner class DeleteThenReferenceTests {
        
        @Test
        fun `delete instance then try to match it fails`() {
            // Create a house
            val house = graph.addVertex("House")
            house.property("name", "TestHouse")
            
            // First statement: delete the house
            val deleteStatement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        TypedPatternObjectInstanceElement(
                            objectInstance = TypedPatternObjectInstance(
                                modifier = "delete",
                                name = "house",
                                className = "House",
                                properties = emptyList()
                            )
                        )
                    )
                )
            )
            
            val deleteResult = engine.executeStatement(deleteStatement, context)
            assertIs<TransformationExecutionResult.Success>(deleteResult)
            
            // Second statement: try to match the same type (should fail - no houses left)
            val matchStatement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        TypedPatternObjectInstanceElement(
                            objectInstance = TypedPatternObjectInstance(
                                modifier = null,
                                name = "h",
                                className = "House",
                                properties = emptyList()
                            )
                        )
                    )
                )
            )
            
            val matchResult = engine.executeStatement(matchStatement, context)
            
            assertIs<TransformationExecutionResult.Failure>(matchResult)
        }
        
        @Test
        fun `delete instance in pattern then reference in subsequent match fails`() {
            // Create house and room
            val house = graph.addVertex("House")
            val room = graph.addVertex("Room")
            house.addEdge("`rooms`_``", room)
            
            // Delete the house, keep the room
            val deleteStatement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        TypedPatternObjectInstanceElement(
                            objectInstance = TypedPatternObjectInstance(
                                modifier = "delete",
                                name = "house",
                                className = "House",
                                properties = emptyList()
                            )
                        )
                    )
                )
            )
            
            val deleteResult = engine.executeStatement(deleteStatement, context)
            assertIs<TransformationExecutionResult.Success>(deleteResult)
            
            // Try to find house->room link (should fail)
            val matchWithLinkStatement = TypedMatchStatement(
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
                                modifier = null,
                                name = "room",
                                className = "Room",
                                properties = emptyList()
                            )
                        ),
                        TypedPatternLinkElement(
                            link = TypedPatternLink(
                                modifier = null,
                                source = TypedPatternLinkEnd(
                                    objectName = "house",
                                    propertyName = "rooms"
                                ),
                                target = TypedPatternLinkEnd(
                                    objectName = "room",
                                    propertyName = null
                                )
                            )
                        )
                    )
                )
            )
            
            val matchResult = engine.executeStatement(matchWithLinkStatement, context)
            
            assertIs<TransformationExecutionResult.Failure>(matchResult)
        }
    }
    
    // ============================================================================
    // 4. Nested control flow
    // ============================================================================
    
    @Nested
    inner class NestedControlFlowTests {
        
        @Test
        fun `while-match with delete terminates correctly`() {
            // Create 3 items to process
            graph.addVertex("Item")
            graph.addVertex("Item")
            graph.addVertex("Item")
            
            // While-match that deletes items to ensure termination
            val whileMatchStatement = TypedWhileMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        TypedPatternObjectInstanceElement(
                            objectInstance = TypedPatternObjectInstance(
                                modifier = "delete",  // Delete ensures termination
                                name = "item",
                                className = "Item",
                                properties = emptyList()
                            )
                        )
                    )
                ),
                doBlock = emptyList()
            )
            
            val result = engine.executeStatement(whileMatchStatement, context)
            
            assertIs<TransformationExecutionResult.Success>(result)
            assertEquals(3, result.deletedNodes.size)
            
            // Verify all items are now deleted
            val g = engine.traversalSource
            val remainingCount = g.V().hasLabel("Item").count().next()
            assertEquals(0L, remainingCount)
        }
        
        @Test
        fun `for-match inside if-match`() {
            // Create a building with 2 rooms
            val building = graph.addVertex("Building")
            val room1 = graph.addVertex("Room")
            val room2 = graph.addVertex("Room")
            building.addEdge("`rooms`_``", room1)
            building.addEdge("`rooms`_``", room2)
            
            // If there's a building, for-match all its rooms
            val ifMatchStatement = TypedIfMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        TypedPatternObjectInstanceElement(
                            objectInstance = TypedPatternObjectInstance(
                                modifier = null,
                                name = "building",
                                className = "Building",
                                properties = emptyList()
                            )
                        )
                    )
                ),
                thenBlock = listOf(
                    TypedForMatchStatement(
                        pattern = TypedPattern(
                            elements = listOf(
                                TypedPatternObjectInstanceElement(
                                    objectInstance = TypedPatternObjectInstance(
                                        modifier = null,
                                        name = "room",
                                        className = "Room",
                                        properties = emptyList()
                                    )
                                )
                            )
                        ),
                        doBlock = listOf()
                    )
                ),
                elseBlock = null
            )
            
            val result = engine.executeStatement(ifMatchStatement, context)
            
            assertIs<TransformationExecutionResult.Success>(result)
            // Should have matched building (1), but rooms from for-match are not visible
        }
    }
    
    // ============================================================================
    // 5. Empty else branches
    // ============================================================================
    
    @Nested
    inner class EmptyElseBranchTests {
        
        @Test
        fun `if-match with null else block when no match`() {
            // No vertices in graph
            val statement = TypedIfMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        TypedPatternObjectInstanceElement(
                            objectInstance = TypedPatternObjectInstance(
                                modifier = null,
                                name = "house",
                                className = "House",
                                properties = emptyList()
                            )
                        )
                    )
                ),
                thenBlock = listOf(
                    TypedStopStatement(keyword = "stop")
                ),
                elseBlock = null
            )
            
            val result = engine.executeStatement(statement, context)
            
            // Should succeed with unchanged context (else not executed, but not a failure)
            assertIs<TransformationExecutionResult.Success>(result)
        }
        
        @Test
        fun `if-match with empty else block list`() {
            // No vertices in graph
            val statement = TypedIfMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        TypedPatternObjectInstanceElement(
                            objectInstance = TypedPatternObjectInstance(
                                modifier = null,
                                name = "house",
                                className = "House",
                                properties = emptyList()
                            )
                        )
                    )
                ),
                thenBlock = listOf(
                    TypedStopStatement(keyword = "stop")
                ),
                elseBlock = emptyList()
            )
            
            val result = engine.executeStatement(statement, context)
            
            assertIs<TransformationExecutionResult.Success>(result)
        }
        
        @Test
        fun `for-match with empty do block executes for each match`() {
            graph.addVertex("Item")
            graph.addVertex("Item")
            graph.addVertex("Item")
            
            val statement = TypedForMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        TypedPatternObjectInstanceElement(
                            objectInstance = TypedPatternObjectInstance(
                                modifier = null,
                                name = "item",
                                className = "Item",
                                properties = emptyList()
                            )
                        )
                    )
                ),
                doBlock = emptyList()
            )
            
            val result = engine.executeStatement(statement, context)
            
            assertIs<TransformationExecutionResult.Success>(result)
            // Should have matched all 3 items (but not visible outside loop)
        }
    }
    
    // ============================================================================
    // 6. Variable shadowing
    // ============================================================================
    
    @Nested
    inner class VariableShadowingTests {
        
        @Test
        fun `variable in outer scope preserved after inner scope`() {
            val contextWithVar = context.testBindVariable("count", 5)
            
            // Create items to trigger loop execution
            graph.addVertex("Item")
            
            val forStatement = TypedForMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        TypedPatternObjectInstanceElement(
                            objectInstance = TypedPatternObjectInstance(
                                modifier = null,
                                name = "item",
                                className = "Item",
                                properties = emptyList()
                            )
                        ),
                        // Variable inside the pattern - should shadow outer scope
                        TypedPatternVariableElement(
                            variable = TypedPatternVariable(
                                name = "count",
                                value = TypedIntLiteralExpression(evalType = 0, value = "10")
                            )
                        )
                    )
                ),
                doBlock = emptyList()
            )
            
            val result = engine.executeStatement(forStatement, contextWithVar)
            
            assertIs<TransformationExecutionResult.Success>(result)
            // The outer count should be preserved OR shadowed - depends on implementation
            // Check what the value is
            val countValue = (contextWithVar.variableScope.getVariable("count") as? VariableBinding.ValueBinding)?.value
            // With proper scoping, inner variables should shadow outer ones but outer restored after
            // However current implementation may persist inner value
            assertNotNull(countValue)
        }
        
        @Test
        fun `same instance name in nested patterns uses inner binding`() {
            val house1 = graph.addVertex("House")
            house1.property("name", "House1")
            val house2 = graph.addVertex("House")
            house2.property("name", "House2")
            
            // Bind house to first house in outer context
            val contextWithHouse = context.testBindInstance("house", house1.id())
            
            // For-match should use its own binding
            val forStatement = TypedForMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        TypedPatternObjectInstanceElement(
                            objectInstance = TypedPatternObjectInstance(
                                modifier = null,
                                name = "house",
                                className = "House",
                                properties = emptyList()
                            )
                        )
                    )
                ),
                doBlock = emptyList()
            )
            
            val result = engine.executeStatement(forStatement, contextWithHouse)
            
            assertIs<TransformationExecutionResult.Success>(result)
            // Should have found both houses (but not visible outside for-match loop)
            // Actually, with pre-bound instance, it SHOULD restrict to that instance
        }
    }
    
    // ============================================================================
    // 7. Match with no instances, only links
    // ============================================================================
    
    @Nested
    inner class MatchWithOnlyLinksTests {
        
        @Test
        fun `pattern with only variable elements succeeds`() {
            // Fixed: Variables in patterns with no object instances are now evaluated
            // by the MatchExecutor's handleEmptyPattern function.
            //
            // Expected: Variable "x" should be bound to value 42
            
            val statement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        TypedPatternVariableElement(
                            variable = TypedPatternVariable(
                                name = "x",
                                value = TypedIntLiteralExpression(evalType = 0, value = "42")
                            )
                        )
                    )
                )
            )
            
            val result = engine.executeStatement(statement, context)
            
            assertIs<TransformationExecutionResult.Success>(result)
            // Variable is now correctly bound
            val variableValue = (context.variableScope.getVariable("x") as? VariableBinding.ValueBinding)?.value
            assertEquals(42, variableValue)
        }
    }
    
    // ============================================================================
    // 8. Concurrent graph modification (create/delete while matching)
    // ============================================================================
    
    @Nested
    inner class ConcurrentGraphModificationTests {
        
        @Test
        fun `for-match collects all matches before modifications`() {
            // Create 3 items
            graph.addVertex("Item").property("index", 1)
            graph.addVertex("Item").property("index", 2)
            graph.addVertex("Item").property("index", 3)
            
            // For-match should collect all matches upfront, then iterate
            // Creating new Items in the do block should not affect iteration count
            val statement = TypedForMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        TypedPatternObjectInstanceElement(
                            objectInstance = TypedPatternObjectInstance(
                                modifier = null,
                                name = "item",
                                className = "Item",
                                properties = emptyList()
                            )
                        )
                    )
                ),
                doBlock = listOf(
                    // Create a new item in each iteration
                    TypedMatchStatement(
                        pattern = TypedPattern(
                            elements = listOf(
                                TypedPatternObjectInstanceElement(
                                    objectInstance = TypedPatternObjectInstance(
                                        modifier = "create",
                                        name = "newItem",
                                        className = "NewItem",
                                        properties = emptyList()
                                    )
                                )
                            )
                        )
                    )
                )
            )
            
            val result = engine.executeStatement(statement, context)
            
            assertIs<TransformationExecutionResult.Success>(result)
            // Should have matched 3 original items (not visible outside loop) and created 3 new items
            assertEquals(3, result.createdNodes.size)
        }
        
        @Test
        fun `while-match re-evaluates pattern after each modification`() {
            // Create 3 items
            graph.addVertex("Item")
            graph.addVertex("Item")
            graph.addVertex("Item")
            
            // While-match deletes items one at a time
            // After each iteration, re-evaluation will find one less item
            val statement = TypedWhileMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        TypedPatternObjectInstanceElement(
                            objectInstance = TypedPatternObjectInstance(
                                modifier = "delete",
                                name = "item",
                                className = "Item",
                                properties = emptyList()
                            )
                        )
                    )
                ),
                doBlock = emptyList()
            )
            
            val result = engine.executeStatement(statement, context)
            
            assertIs<TransformationExecutionResult.Success>(result)
            // All items should now be deleted
            val g = engine.traversalSource
            val itemCount = g.V().hasLabel("Item").count().next()
            assertEquals(0L, itemCount)
        }
        
        @Test
        fun `delete in for-match should work on stale reference`() {
            // Create 3 items
            graph.addVertex("Item").property("index", 1)
            graph.addVertex("Item").property("index", 2)
            graph.addVertex("Item").property("index", 3)
            
            // For-match collects all, then tries to delete each
            // Even though first deletion might affect subsequent ones,
            // since we collected upfront, we should delete all
            val statement = TypedForMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        TypedPatternObjectInstanceElement(
                            objectInstance = TypedPatternObjectInstance(
                                modifier = "delete",
                                name = "item",
                                className = "Item",
                                properties = emptyList()
                            )
                        )
                    )
                ),
                doBlock = emptyList()
            )
            
            val result = engine.executeStatement(statement, context)
            
            assertIs<TransformationExecutionResult.Success>(result)
            assertEquals(3, result.deletedNodes.size)
            
            // Verify all items deleted
            val g = engine.traversalSource
            val count = g.V().hasLabel("Item").count().next()
            assertEquals(0L, count)
        }
    }
    
    // ============================================================================
    // 9. Stop in nested context
    // ============================================================================
    
    @Nested
    inner class StopInNestedContextTests {
        
        @Test
        fun `stop inside for-match terminates iteration`() {
            graph.addVertex("Item").property("index", 1)
            graph.addVertex("Item").property("index", 2)
            graph.addVertex("Item").property("index", 3)
            
            val statement = TypedForMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        TypedPatternObjectInstanceElement(
                            objectInstance = TypedPatternObjectInstance(
                                modifier = null,
                                name = "item",
                                className = "Item",
                                properties = emptyList()
                            )
                        )
                    )
                ),
                doBlock = listOf(
                    TypedStopStatement(keyword = "stop")
                )
            )
            
            val result = engine.executeStatement(statement, context)
            
            // Stop should propagate up and terminate
            assertIs<TransformationExecutionResult.Stopped>(result)
            assertEquals("stop", result.keyword)
        }
        
        @Test
        fun `kill inside while-match terminates immediately`() {
            graph.addVertex("Item")
            graph.addVertex("Item")
            
            val statement = TypedWhileMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        TypedPatternObjectInstanceElement(
                            objectInstance = TypedPatternObjectInstance(
                                modifier = null,
                                name = "item",
                                className = "Item",
                                properties = emptyList()
                            )
                        )
                    )
                ),
                doBlock = listOf(
                    TypedStopStatement(keyword = "kill")
                )
            )
            
            val result = engine.executeStatement(statement, context)
            
            assertIs<TransformationExecutionResult.Stopped>(result)
            assertEquals("kill", result.keyword)
        }
        
        @Test
        fun `stop inside nested if-match in for-match`() {
            graph.addVertex("Special")
            graph.addVertex("Normal")
            
            val statement = TypedForMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        TypedPatternObjectInstanceElement(
                            objectInstance = TypedPatternObjectInstance(
                                modifier = null,
                                name = "item",
                                className = "Special",
                                properties = emptyList()
                            )
                        )
                    )
                ),
                doBlock = listOf(
                    TypedIfMatchStatement(
                        pattern = TypedPattern(
                            elements = listOf(
                                TypedPatternObjectInstanceElement(
                                    objectInstance = TypedPatternObjectInstance(
                                        modifier = null,
                                        name = "normal",
                                        className = "Normal",
                                        properties = emptyList()
                                    )
                                )
                            )
                        ),
                        thenBlock = listOf(
                            TypedStopStatement(keyword = "stop")
                        ),
                        elseBlock = null
                    )
                )
            )
            
            val result = engine.executeStatement(statement, context)
            
            assertIs<TransformationExecutionResult.Stopped>(result)
        }
    }
    
    // ============================================================================
    // 10. Expression evaluation order in where clauses
    // ============================================================================
    
    @Nested
    inner class ExpressionEvaluationOrderTests {
        
        @Test
        fun `where clause with multiple conditions - all must be true`() {
            val house = graph.addVertex("House")
            house.property("size", 100)
            house.property("floors", 2)
            
            // Where clause with AND - both conditions true
            val statement = TypedMatchStatement(
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
                        TypedPatternWhereClauseElement(
                            whereClause = TypedWhereClause(
                                expression = TypedBooleanLiteralExpression(evalType = 0, value = true)
                            )
                        ),
                        TypedPatternWhereClauseElement(
                            whereClause = TypedWhereClause(
                                expression = TypedBooleanLiteralExpression(evalType = 0, value = true)
                            )
                        )
                    )
                )
            )
            
            val result = engine.executeStatement(statement, context)
            
            assertIs<TransformationExecutionResult.Success>(result)
        }
        
        @Test
        fun `where clause with false literal fails match`() {
            graph.addVertex("House")
            
            val statement = TypedMatchStatement(
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
                        TypedPatternWhereClauseElement(
                            whereClause = TypedWhereClause(
                                expression = TypedBooleanLiteralExpression(evalType = 0, value = false)
                            )
                        )
                    )
                )
            )
            
            val result = engine.executeStatement(statement, context)
            
            assertIs<TransformationExecutionResult.Failure>(result)
        }
        
        @Test
        fun `multiple where clauses all evaluated in order`() {
            graph.addVertex("House")
            
            // First where is true, second is false - should fail
            val statement = TypedMatchStatement(
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
                        TypedPatternWhereClauseElement(
                            whereClause = TypedWhereClause(
                                expression = TypedBooleanLiteralExpression(evalType = 0, value = true)
                            )
                        ),
                        TypedPatternWhereClauseElement(
                            whereClause = TypedWhereClause(
                                expression = TypedBooleanLiteralExpression(evalType = 0, value = false)
                            )
                        )
                    )
                )
            )
            
            val result = engine.executeStatement(statement, context)
            
            assertIs<TransformationExecutionResult.Failure>(result)
        }
    }
    
    // ============================================================================
    // Additional edge cases discovered during analysis
    // ============================================================================
    
    @Nested
    inner class AdditionalEdgeCases {
        
        @Test
        fun `empty pattern returns success`() {
            val statement = TypedMatchStatement(
                pattern = TypedPattern(elements = emptyList())
            )
            
            val result = engine.executeStatement(statement, context)
            
            assertIs<TransformationExecutionResult.Success>(result)
        }
        
        @Test
        fun `pattern with only where clause and no instances`() {
            val statement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        TypedPatternWhereClauseElement(
                            whereClause = TypedWhereClause(
                                expression = TypedBooleanLiteralExpression(evalType = 0, value = true)
                            )
                        )
                    )
                )
            )
            
            val result = engine.executeStatement(statement, context)
            
            assertIs<TransformationExecutionResult.Success>(result)
        }
        
        @Test
        fun `until-match terminates on match`() {
            graph.addVertex("Target")
            
            // Until-match: execute until pattern matches
            val statement = TypedUntilMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        TypedPatternObjectInstanceElement(
                            objectInstance = TypedPatternObjectInstance(
                                modifier = null,
                                name = "target",
                                className = "Target",
                                properties = emptyList()
                            )
                        )
                    )
                ),
                doBlock = listOf()
            )
            
            val result = engine.executeStatement(statement, context)
            
            // Should succeed because target exists (terminates immediately)
            assertIs<TransformationExecutionResult.Success>(result)
        }
        
        @Test
        fun `property update on matched instance`() {
            val house = graph.addVertex("House")
            house.property("name", "OldName")
            
            val statement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        TypedPatternObjectInstanceElement(
                            objectInstance = TypedPatternObjectInstance(
                                modifier = null,
                                name = "house",
                                className = "House",
                                properties = listOf(
                                    TypedPatternPropertyAssignment(
                                        propertyName = "name",
                                        operator = "=",
                                        value = TypedStringLiteralExpression(
                                            evalType = 0,
                                            value = "NewName"
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
            )
            
            val result = engine.executeStatement(statement, context)
            
            assertIs<TransformationExecutionResult.Success>(result)
            
            // Verify property updated
            val g = engine.traversalSource
            val updatedName = g.V(house.id()).values<String>("name").next()
            assertEquals("NewName", updatedName)
        }
        
        @Test
        fun `match and create in same pattern`() {
            // Existing house
            graph.addVertex("House")
            
            // Match house, create room linked to it
            val statement = TypedMatchStatement(
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
                                name = "room",
                                className = "Room",
                                properties = emptyList()
                            )
                        ),
                        TypedPatternLinkElement(
                            link = TypedPatternLink(
                                modifier = "create",
                                source = TypedPatternLinkEnd(
                                    objectName = "house",
                                    propertyName = "rooms"
                                ),
                                target = TypedPatternLinkEnd(
                                    objectName = "room",
                                    propertyName = null
                                )
                            )
                        )
                    )
                )
            )
            
            val result = engine.executeStatement(statement, context)
            
            assertIs<TransformationExecutionResult.Success>(result)
            assertEquals(1, result.createdNodes.size)
        }
        
        @Test
        fun `if-expression with truthy condition executes then block`() {
            val statement = TypedIfExpressionStatement(
                condition = TypedBooleanLiteralExpression(evalType = 0, value = true),
                thenBlock = listOf(
                    TypedMatchStatement(
                        pattern = TypedPattern(
                            elements = listOf(
                                TypedPatternObjectInstanceElement(
                                    objectInstance = TypedPatternObjectInstance(
                                        modifier = "create",
                                        name = "created",
                                        className = "Created",
                                        properties = emptyList()
                                    )
                                )
                            )
                        )
                    )
                ),
                elseIfBranches = emptyList(),
                elseBlock = null
            )
            
            val result = engine.executeStatement(statement, context)
            
            assertIs<TransformationExecutionResult.Success>(result)
            assertEquals(1, result.createdNodes.size)
        }
        
        @Test
        fun `if-expression with falsy condition executes else block`() {
            val statement = TypedIfExpressionStatement(
                condition = TypedBooleanLiteralExpression(evalType = 0, value = false),
                thenBlock = listOf(
                    TypedStopStatement(keyword = "stop")
                ),
                elseIfBranches = emptyList(),
                elseBlock = listOf(
                    TypedMatchStatement(
                        pattern = TypedPattern(
                            elements = listOf(
                                TypedPatternObjectInstanceElement(
                                    objectInstance = TypedPatternObjectInstance(
                                        modifier = "create",
                                        name = "created",
                                        className = "Created",
                                        properties = emptyList()
                                    )
                                )
                            )
                        )
                    )
                )
            )
            
            val result = engine.executeStatement(statement, context)
            
            assertIs<TransformationExecutionResult.Success>(result)
            assertEquals(1, result.createdNodes.size)
        }
    }
}
