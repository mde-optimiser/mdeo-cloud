package com.mdeo.modeltransformation.runtime

import com.mdeo.expression.ast.types.ClassTypeRef
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertIs

class TransformationEngineTest {

    private lateinit var graph: TinkerGraph
    private lateinit var engine: TransformationEngine
    private lateinit var registry: StatementExecutorRegistry

    @BeforeEach
    fun setUp() {
        graph = TinkerGraph.open()
        registry = StatementExecutorRegistry()
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
    inner class ExecuteAstTests {

        @Test
        fun `execute empty AST returns Success`() {
            val ast = TypedAst(
                types = emptyList(),
                metamodelPath = "test://model",
                statements = emptyList()
            )
            
            engine = TransformationEngine(
                modelGraph = TinkerModelGraph.wrap(graph),
                ast = ast,
                expressionCompilerRegistry = ExpressionCompilerRegistry.createDefaultRegistry(),
                statementExecutorRegistry = registry
            )
            
            val result = engine.execute()
            
            assertIs<TransformationExecutionResult.Success>(result)
        }

        @Test
        fun `execute stores types from AST`() {
            val intType = ClassTypeRef(`package` = "builtin", type = "int", isNullable = false)
            val stringType = ClassTypeRef(`package` = "builtin", type = "string", isNullable = false)
            val ast = TypedAst(
                types = listOf(intType, stringType),
                metamodelPath = "test://model",
                statements = emptyList()
            )
            
            engine = TransformationEngine(
                modelGraph = TinkerModelGraph.wrap(graph),
                ast = ast,
                expressionCompilerRegistry = ExpressionCompilerRegistry.createDefaultRegistry(),
                statementExecutorRegistry = registry
            )
            
            engine.execute()
            
            assertEquals(intType, engine.getType(0))
            assertEquals(stringType, engine.getType(1))
        }

        @Test
        fun `execute with single successful statement`() {
            registry.register(SuccessExecutor())
            
            val ast = TypedAst(
                types = emptyList(),
                metamodelPath = "test://model",
                statements = listOf(TypedStopStatement(keyword = "stop"))
            )
            
            engine = TransformationEngine(
                modelGraph = TinkerModelGraph.wrap(graph),
                ast = ast,
                expressionCompilerRegistry = ExpressionCompilerRegistry.createDefaultRegistry(),
                statementExecutorRegistry = registry
            )
            
            val result = engine.execute()
            
            assertIs<TransformationExecutionResult.Success>(result)
        }



        @Test
        fun `execute stops on first failure`() {
            val countingExecutor = CountingExecutor()
            registry.register(countingExecutor)
            countingExecutor.failOnCall = 2
            
            val ast = TypedAst(
                types = emptyList(),
                metamodelPath = "test://model",
                statements = listOf(
                    TypedStopStatement(keyword = "stop"),
                    TypedStopStatement(keyword = "stop"),
                    TypedStopStatement(keyword = "stop")
                )
            )
            
            engine = TransformationEngine(
                modelGraph = TinkerModelGraph.wrap(graph),
                ast = ast,
                expressionCompilerRegistry = ExpressionCompilerRegistry.createDefaultRegistry(),
                statementExecutorRegistry = registry
            )
            
            val result = engine.execute()
            
            assertIs<TransformationExecutionResult.Failure>(result)
            assertEquals(2, countingExecutor.callCount)
        }

        @Test
        fun `execute stops on Stopped result`() {
            registry.register(StoppedExecutor())
            
            val ast = TypedAst(
                types = emptyList(),
                metamodelPath = "test://model",
                statements = listOf(
                    TypedStopStatement(keyword = "stop"),
                    TypedStopStatement(keyword = "stop")
                )
            )
            
            engine = TransformationEngine(
                modelGraph = TinkerModelGraph.wrap(graph),
                ast = ast,
                expressionCompilerRegistry = ExpressionCompilerRegistry.createDefaultRegistry(),
                statementExecutorRegistry = registry
            )
            
            val result = engine.execute()
            
            assertIs<TransformationExecutionResult.Stopped>(result)
        }
    }

    @Nested
    inner class ExecuteStatementTests {

        @Test
        fun `executeStatement returns Failure when no executor found`() {
            val statement = TypedStopStatement(keyword = "stop")
            val context = TransformationExecutionContext.empty()
            
            val result = engine.executeStatement(statement, context)
            
            assertIs<TransformationExecutionResult.Failure>(result)
        }

        @Test
        fun `executeStatement delegates to registry`() {
            val executor = SuccessExecutor()
            registry.register(executor)
            
            val statement = TypedStopStatement(keyword = "stop")
            val context = TransformationExecutionContext.empty()
            
            val result = engine.executeStatement(statement, context)
            
            assertIs<TransformationExecutionResult.Success>(result)
            assertTrue(executor.called)
        }
    }

    @Nested
    inner class ExecuteBlockTests {

        @Test
        fun `executeBlock with empty list returns Success`() {
            val context = TransformationExecutionContext.empty()
            
            val result = engine.executeBlock(emptyList(), context)
            
            assertIs<TransformationExecutionResult.Success>(result)
        }

        @Test
        fun `executeBlock executes all statements`() {
            val countingExecutor = CountingExecutor()
            registry.register(countingExecutor)
            
            val statements = listOf(
                TypedStopStatement(keyword = "stop"),
                TypedStopStatement(keyword = "stop"),
                TypedStopStatement(keyword = "stop")
            )
            
            engine.executeBlock(statements, TransformationExecutionContext.empty())
            
            assertEquals(3, countingExecutor.callCount)
        }

        @Test
        fun `executeBlock stops on failure`() {
            val countingExecutor = CountingExecutor()
            countingExecutor.failOnCall = 2
            registry.register(countingExecutor)
            
            val statements = listOf(
                TypedStopStatement(keyword = "stop"),
                TypedStopStatement(keyword = "stop"),
                TypedStopStatement(keyword = "stop")
            )
            
            val result = engine.executeBlock(statements, TransformationExecutionContext.empty())
            
            assertIs<TransformationExecutionResult.Failure>(result)
            assertEquals(2, countingExecutor.callCount)
        }


    }

    @Nested
    inner class TypeResolutionTests {

        @Test
        fun `getType returns correct type`() {
            val intType = ClassTypeRef(`package` = "builtin", type = "int", isNullable = false)
            val booleanType = ClassTypeRef(`package` = "builtin", type = "boolean", isNullable = false)
            val stringType = ClassTypeRef(`package` = "builtin", type = "string", isNullable = false)
            val ast = TypedAst(
                types = listOf(intType, booleanType, stringType),
                metamodelPath = "test://model",
                statements = emptyList()
            )
            engine = TransformationEngine(
                modelGraph = TinkerModelGraph.wrap(graph),
                ast = ast,
                expressionCompilerRegistry = ExpressionCompilerRegistry.createDefaultRegistry(),
                statementExecutorRegistry = registry
            )
            engine.execute()
            
            assertEquals(intType, engine.getType(0))
            assertEquals(booleanType, engine.getType(1))
            assertEquals(stringType, engine.getType(2))
        }

        @Test
        fun `getTypeOrNull returns null for invalid index`() {
            val intType = ClassTypeRef(`package` = "builtin", type = "int", isNullable = false)
            val ast = TypedAst(
                types = listOf(intType),
                metamodelPath = "test://model",
                statements = emptyList()
            )
            engine = TransformationEngine(
                modelGraph = TinkerModelGraph.wrap(graph),
                ast = ast,
                expressionCompilerRegistry = ExpressionCompilerRegistry.createDefaultRegistry(),
                statementExecutorRegistry = registry
            )
            engine.execute()
            
            assertNull(engine.getTypeOrNull(99))
        }
    }

    @Nested
    inner class CompanionObjectTests {

        @Test
        fun `create returns configured engine`() {
            val ast = TypedAst(types = emptyList(), metamodelPath = "test://model", statements = emptyList())
            val newEngine = TransformationEngine.create(modelGraph = TinkerModelGraph.wrap(graph), ast = ast)
            
            // Should have all default executors registered
            assertTrue(newEngine.statementExecutorRegistry.executorCount() > 0)
            // Specifically check for the standard executors
            assertTrue(newEngine.statementExecutorRegistry.hasExecutor(
                TypedStopStatement(keyword = "stop")
            ))
        }
    }

    // Test executor implementations
    
    private class SuccessExecutor : StatementExecutor {
        var called = false
        
        override fun canExecute(statement: TypedTransformationStatement) = true
        
        override fun execute(
            statement: TypedTransformationStatement,
            context: TransformationExecutionContext,
            engine: TransformationEngine
        ): TransformationExecutionResult {
            called = true
            return TransformationExecutionResult.Success.empty()
        }
    }
    

    
    private class CountingExecutor : StatementExecutor {
        var callCount = 0
        var failOnCall = -1
        
        override fun canExecute(statement: TypedTransformationStatement) = true
        
        override fun execute(
            statement: TypedTransformationStatement,
            context: TransformationExecutionContext,
            engine: TransformationEngine
        ): TransformationExecutionResult {
            callCount++
            return if (callCount == failOnCall) {
                TransformationExecutionResult.Failure(reason = "Failed on call $callCount", failedAt = null, isDeterministic = false, changesWereMade = false)
            } else {
                TransformationExecutionResult.Success.empty()
            }
        }
    }
    
    private class ContextModifyingExecutor : StatementExecutor {
        override fun canExecute(statement: TypedTransformationStatement) = true
        
        override fun execute(
            statement: TypedTransformationStatement,
            context: TransformationExecutionContext,
            engine: TransformationEngine
        ): TransformationExecutionResult {
            val counter = ((context.variableScope.getVariable("counter") as? VariableBinding.ValueBinding)?.value as? Int) ?: 0
            context.testBindVariable("counter", counter + 1)
            return TransformationExecutionResult.Success.empty()
        }
    }
    
    private class StoppedExecutor : StatementExecutor {
        override fun canExecute(statement: TypedTransformationStatement) = true
        
        override fun execute(
            statement: TypedTransformationStatement,
            context: TransformationExecutionContext,
            engine: TransformationEngine
        ): TransformationExecutionResult {
            return TransformationExecutionResult.Stopped("stop")
        }
    }
}
