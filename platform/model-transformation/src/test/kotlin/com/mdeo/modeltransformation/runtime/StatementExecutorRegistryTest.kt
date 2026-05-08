package com.mdeo.modeltransformation.runtime

import com.mdeo.modeltransformation.ast.TypedAst
import com.mdeo.modeltransformation.ast.statements.TypedMatchStatement
import com.mdeo.modeltransformation.ast.statements.TypedStopStatement
import com.mdeo.modeltransformation.ast.statements.TypedTransformationStatement
import com.mdeo.modeltransformation.ast.patterns.TypedPattern
import com.mdeo.modeltransformation.compiler.ExpressionCompilerRegistry
import com.mdeo.modeltransformation.graph.tinker.TinkerModelGraph
import com.mdeo.modeltransformation.compiler.VariableBinding
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertIs

class StatementExecutorRegistryTest {

    private lateinit var registry: StatementExecutorRegistry
    private lateinit var graph: TinkerGraph
    private lateinit var engine: TransformationEngine

    @BeforeEach
    fun setUp() {
        registry = StatementExecutorRegistry()
        graph = TinkerGraph.open()
        engine = TransformationEngine(
            modelGraph = TinkerModelGraph.wrap(graph),
            ast = TypedAst(types = emptyList(), metamodelPath = "test://model", statements = emptyList()),
            expressionCompilerRegistry = ExpressionCompilerRegistry.createDefaultRegistry(),
            statementExecutorRegistry = registry
        )
    }

    @AfterEach
    fun tearDown() {
        graph.close()
    }

    @Nested
    inner class RegistrationTests {

        @Test
        fun `empty registry has no executors`() {
            assertEquals(0, registry.executorCount())
        }

        @Test
        fun `register adds executor to registry`() {
            val executor = TestExecutor("match")
            registry.register(executor)
            
            assertEquals(1, registry.executorCount())
        }

        @Test
        fun `register returns registry for chaining`() {
            val result = registry
                .register(TestExecutor("match"))
                .register(TestExecutor("stop"))
            
            assertEquals(registry, result)
            assertEquals(2, registry.executorCount())
        }

        @Test
        fun `registerAll adds multiple executors`() {
            registry.registerAll(
                TestExecutor("match"),
                TestExecutor("stop"),
                TestExecutor("ifMatch")
            )
            
            assertEquals(3, registry.executorCount())
        }
    }

    @Nested
    inner class FindExecutorTests {

        @Test
        fun `findExecutor returns null when no executor registered`() {
            val statement = TypedStopStatement(keyword = "stop")
            
            val executor = registry.findExecutor(statement)
            
            assertNull(executor)
        }

        @Test
        fun `findExecutor finds matching executor`() {
            val stopExecutor = TestExecutor("stop")
            registry.register(stopExecutor)
            
            val statement = TypedStopStatement(keyword = "stop")
            val found = registry.findExecutor(statement)
            
            assertEquals(stopExecutor, found)
        }

        @Test
        fun `findExecutor returns first matching executor`() {
            val executor1 = TestExecutor("stop")
            val executor2 = TestExecutor("stop")
            registry.register(executor1).register(executor2)
            
            val statement = TypedStopStatement(keyword = "stop")
            val found = registry.findExecutor(statement)
            
            assertEquals(executor1, found)
        }

        @Test
        fun `findExecutor distinguishes statement types`() {
            val matchExecutor = TestExecutor("match")
            val stopExecutor = TestExecutor("stop")
            registry.register(matchExecutor).register(stopExecutor)
            
            val matchStatement = TypedMatchStatement(pattern = TypedPattern(emptyList()))
            val stopStatement = TypedStopStatement(keyword = "stop")
            
            assertEquals(matchExecutor, registry.findExecutor(matchStatement))
            assertEquals(stopExecutor, registry.findExecutor(stopStatement))
        }
    }

    @Nested
    inner class HasExecutorTests {

        @Test
        fun `hasExecutor returns false when no executor registered`() {
            val statement = TypedStopStatement(keyword = "stop")
            
            assertFalse(registry.hasExecutor(statement))
        }

        @Test
        fun `hasExecutor returns true when executor exists`() {
            registry.register(TestExecutor("stop"))
            
            val statement = TypedStopStatement(keyword = "stop")
            
            assertTrue(registry.hasExecutor(statement))
        }
    }

    @Nested
    inner class ExecuteTests {

        @Test
        fun `execute returns Failure when no executor found`() {
            val statement = TypedStopStatement(keyword = "stop")
            val context = TransformationExecutionContext.empty()
            
            val result = registry.execute(statement, context, engine)
            
            assertIs<TransformationExecutionResult.Failure>(result)
            assertTrue(result.reason.contains("No executor found"))
        }

        @Test
        fun `execute delegates to registered executor`() {
            val executor = TestExecutor("stop", successResult = true)
            registry.register(executor)
            
            val statement = TypedStopStatement(keyword = "stop")
            val context = TransformationExecutionContext.empty()
            
            val result = registry.execute(statement, context, engine)
            
            assertIs<TransformationExecutionResult.Success>(result)
            assertTrue(executor.executeCalled)
        }

        @Test
        fun `execute passes context to executor`() {
            val executor = TestExecutor("stop", successResult = true)
            registry.register(executor)
            
            val statement = TypedStopStatement(keyword = "stop")
            val context = TransformationExecutionContext.empty().testBindVariable("x", 42)
            
            registry.execute(statement, context, engine)
            
            assertEquals(42, (executor.lastContext?.variableScope?.getVariable("x") as? VariableBinding.ValueBinding)?.value)
        }

        @Test
        fun `execute passes engine to executor`() {
            val executor = TestExecutor("stop", successResult = true)
            registry.register(executor)
            
            val statement = TypedStopStatement(keyword = "stop")
            val context = TransformationExecutionContext.empty()
            
            registry.execute(statement, context, engine)
            
            assertEquals(engine, executor.lastEngine)
        }
    }

    /**
     * Test implementation of StatementExecutor for testing the registry.
     */
    private class TestExecutor(
        private val targetKind: String,
        private val successResult: Boolean = true
    ) : StatementExecutor {
        
        var executeCalled = false
        var lastContext: TransformationExecutionContext? = null
        var lastEngine: TransformationEngine? = null
        
        override fun canExecute(statement: TypedTransformationStatement): Boolean {
            return statement.kind == targetKind
        }
        
        override fun execute(
            statement: TypedTransformationStatement,
            context: TransformationExecutionContext,
            engine: TransformationEngine
        ): TransformationExecutionResult {
            executeCalled = true
            lastContext = context
            lastEngine = engine
            
            return if (successResult) {
                TransformationExecutionResult.Success.empty()
            } else {
                TransformationExecutionResult.Failure(reason = "Test failure", failedAt = null, isDeterministic = false, changesWereMade = false)
            }
        }
    }
}
