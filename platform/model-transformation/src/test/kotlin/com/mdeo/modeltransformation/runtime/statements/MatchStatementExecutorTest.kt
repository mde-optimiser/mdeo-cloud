package com.mdeo.modeltransformation.runtime.statements

import com.mdeo.modeltransformation.ast.TypedAst
import com.mdeo.modeltransformation.ast.patterns.TypedPattern
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstance
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstanceElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLink
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLinkEnd
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLinkElement
import com.mdeo.modeltransformation.ast.statements.TypedMatchStatement
import com.mdeo.modeltransformation.ast.statements.TypedStopStatement
import com.mdeo.modeltransformation.compiler.ExpressionCompilerRegistry
import com.mdeo.modeltransformation.graph.tinker.TinkerModelGraph
import com.mdeo.modeltransformation.compiler.VariableBinding
import com.mdeo.modeltransformation.runtime.StatementExecutorRegistry
import com.mdeo.modeltransformation.runtime.TransformationEngine
import com.mdeo.modeltransformation.runtime.TransformationExecutionContext
import com.mdeo.modeltransformation.runtime.TransformationExecutionResult
import com.mdeo.modeltransformation.runtime.isFailure
import com.mdeo.modeltransformation.runtime.isSuccess
import com.mdeo.modeltransformation.runtime.testHasInstance
import com.mdeo.metamodel.Metamodel
import com.mdeo.metamodel.data.AssociationData
import com.mdeo.metamodel.data.AssociationEndData
import com.mdeo.metamodel.data.ClassData
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.metamodel.data.MultiplicityData
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MatchStatementExecutorTest {

    private lateinit var executor: MatchStatementExecutor
    private lateinit var graph: TinkerGraph
    private lateinit var engine: TransformationEngine
    private lateinit var context: TransformationExecutionContext

    @BeforeEach
    fun setUp() {
        executor = MatchStatementExecutor()
        graph = TinkerGraph.open()
        engine = TransformationEngine(
            modelGraph = TinkerModelGraph.wrap(graph),
            ast = TypedAst(types = emptyList(), metamodelPath = "test://model", statements = emptyList()),
            expressionCompilerRegistry = ExpressionCompilerRegistry.createDefaultRegistry(),
            statementExecutorRegistry = StatementExecutorRegistry.createDefaultRegistry()
        )
        context = TransformationExecutionContext.empty()
    }

    @AfterEach
    fun tearDown() {
        graph.close()
    }

    @Nested
    inner class CanExecuteTests {

        @Test
        fun `canExecute returns true for TypedMatchStatement`() {
            val statement = TypedMatchStatement(
                pattern = TypedPattern(elements = emptyList())
            )
            
            assertTrue(executor.canExecute(statement))
        }

        @Test
        fun `canExecute returns false for other statement types`() {
            val statement = TypedStopStatement(keyword = "stop")
            
            assertFalse(executor.canExecute(statement))
        }
    }

    @Nested
    inner class ExecutionTests {

        @Test
        fun `returns Success for empty pattern`() {
            val statement = TypedMatchStatement(
                pattern = TypedPattern(elements = emptyList())
            )
            
            val result = executor.execute(statement, context, engine)
            
            assertIs<TransformationExecutionResult.Success>(result)
        }

        @Test
        fun `returns Success when pattern matches`() {
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
                        )
                    )
                )
            )
            
            val result = executor.execute(statement, context, engine)
            
            assertTrue(result.isSuccess())
            assertIs<TransformationExecutionResult.Success>(result)
        }

        @Test
        fun `returns Failure when pattern does not match`() {
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
                        )
                    )
                )
            )
            
            val result = executor.execute(statement, context, engine)
            
            assertTrue(result.isFailure())
            assertIs<TransformationExecutionResult.Failure>(result)
        }

        @Test
        fun `updates context with matched bindings on success`() {
            val vertex = graph.addVertex("House")
            
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
                        )
                    )
                )
            )
            
            val result = executor.execute(statement, context, engine)
            
            assertIs<TransformationExecutionResult.Success>(result)
            assertEquals(vertex.id(), (context.variableScope.getVariable("house") as? VariableBinding.InstanceBinding)?.vertexId)
        }

        @Test
        fun `failure includes reason and location`() {
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
                        )
                    )
                )
            )
            
            val result = executor.execute(statement, context, engine)
            
            assertIs<TransformationExecutionResult.Failure>(result)
            assertEquals("match statement", result.failedAt)
        }
    }

    @Nested
    inner class MultipleMatchesTests {

        @Test
        fun `matches first vertex when multiple exist`() {
            graph.addVertex("House")
            graph.addVertex("House")
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
                        )
                    )
                )
            )
            
            val result = executor.execute(statement, context, engine)
            
            assertIs<TransformationExecutionResult.Success>(result)
        }
    }

    @Nested
    inner class CreateModifierTests {

        @Test
        fun `create-only pattern succeeds without graph match`() {
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
            
            val result = executor.execute(statement, context, engine)
            
            assertIs<TransformationExecutionResult.Success>(result)
        }

        @Test
        fun `creates vertex and tracks created node ID`() {
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
            
            val result = executor.execute(statement, context, engine)
            
            assertIs<TransformationExecutionResult.Success>(result)
            assertEquals(1, result.createdNodes.size)
            
            // Verify vertex exists in graph
            val g = engine.traversalSource
            assertEquals(1, g.V().hasLabel("House").count().next())
        }

        @Test
        fun `context contains created instance mapping`() {
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
            
            val result = executor.execute(statement, context, engine)
            
            assertIs<TransformationExecutionResult.Success>(result)
            assertTrue(context.testHasInstance("newHouse"))
        }
    }

    @Nested
    inner class DeleteModifierTests {

        @Test
        fun `deletes matched vertex and tracks deleted node ID`() {
            val vertex = graph.addVertex("House")
            
            val statement = TypedMatchStatement(
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
            
            val result = executor.execute(statement, context, engine)
            
            assertIs<TransformationExecutionResult.Success>(result)
            assertEquals(1, result.deletedNodes.size)
            
            // Verify vertex is deleted
            val g = engine.traversalSource
            assertEquals(0, g.V().hasLabel("House").count().next())
        }
    }

    @Nested
    inner class CreateEdgeTests {

        @Test
        fun `creates edge between matched vertices with existing relationship`() {
            val person = graph.addVertex("Person")
            val house = graph.addVertex("House")
            // Add an existing relationship so the match can find both vertices
            person.addEdge("`knows`_``", house)
            
            val statement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        TypedPatternObjectInstanceElement(
                            objectInstance = TypedPatternObjectInstance(
                                modifier = null,
                                name = "person",
                                className = "Person",
                                properties = emptyList()
                            )
                        ),
                        TypedPatternObjectInstanceElement(
                            objectInstance = TypedPatternObjectInstance(
                                modifier = null,
                                name = "house",
                                className = "House",
                                properties = emptyList()
                            )
                        ),
                        // Match on existing edge
                        TypedPatternLinkElement(
                            link = TypedPatternLink(
                                modifier = null,
                                source = TypedPatternLinkEnd(objectName = "person", propertyName = "knows"),
                                target = TypedPatternLinkEnd(objectName = "house", propertyName = null)
                            )
                        ),
                        // Create a new edge
                        TypedPatternLinkElement(
                            link = TypedPatternLink(
                                modifier = "create",
                                source = TypedPatternLinkEnd(objectName = "person", propertyName = "livesIn"),
                                target = TypedPatternLinkEnd(objectName = "house", propertyName = null)
                            )
                        )
                    )
                )
            )
            
            val result = executor.execute(statement, context, engine)
            
            assertIs<TransformationExecutionResult.Success>(result)
            
            // Verify new edge exists
            val g = engine.traversalSource
            assertEquals(1, g.V(person.id()).out("`livesIn`_``").hasId(house.id()).count().next())
        }
    }

    @Nested
    inner class DeleteEdgeTests {

        private val metamodelData = MetamodelData(
            classes = listOf(
                ClassData(name = "Person", isAbstract = false),
                ClassData(name = "House", isAbstract = false)
            ),
            associations = listOf(
                AssociationData(
                    source = AssociationEndData(
                        className = "Person",
                        name = "livesIn",
                        multiplicity = MultiplicityData.many()
                    ),
                    operator = "->",
                    target = AssociationEndData(
                        className = "House",
                        name = null,
                        multiplicity = MultiplicityData.single()
                    )
                )
            )
        )
        private val metamodel = Metamodel.compile(metamodelData)

        @Test
        fun `deletes edge between matched vertices`() {
            val g = TinkerGraph.open()
            val person = g.addVertex("Person")
            val house = g.addVertex("House")
            person.addEdge("`livesIn`_``", house)

            val deleteEngine = TransformationEngine(
                modelGraph = TinkerModelGraph.wrap(g, metamodel),
                ast = TypedAst(types = emptyList(), metamodelPath = "", statements = emptyList()),
                expressionCompilerRegistry = ExpressionCompilerRegistry.createDefaultRegistry(),
                statementExecutorRegistry = StatementExecutorRegistry.createDefaultRegistry()
            )

            val statement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        TypedPatternObjectInstanceElement(
                            objectInstance = TypedPatternObjectInstance(
                                modifier = null,
                                name = "person",
                                className = "Person",
                                properties = emptyList()
                            )
                        ),
                        TypedPatternObjectInstanceElement(
                            objectInstance = TypedPatternObjectInstance(
                                modifier = null,
                                name = "house",
                                className = "House",
                                properties = emptyList()
                            )
                        ),
                        TypedPatternLinkElement(
                            link = TypedPatternLink(
                                modifier = "delete",
                                source = TypedPatternLinkEnd(objectName = "person", propertyName = "livesIn"),
                                target = TypedPatternLinkEnd(objectName = "house", propertyName = null)
                            )
                        )
                    )
                )
            )

            val result = deleteEngine.executeStatement(statement, context)

            assertIs<TransformationExecutionResult.Success>(result)

            val traversal = deleteEngine.traversalSource
            assertEquals(0, traversal.E().hasLabel("`livesIn`_``").count().next())
            assertEquals(1, traversal.V().hasLabel("Person").count().next())
            assertEquals(1, traversal.V().hasLabel("House").count().next())

            g.close()
        }
    }
}
