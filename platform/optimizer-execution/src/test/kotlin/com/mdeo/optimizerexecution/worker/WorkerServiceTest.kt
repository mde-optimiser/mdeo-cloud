package com.mdeo.optimizerexecution.worker

import com.mdeo.execution.common.config.configureSerialization
import com.mdeo.expression.ast.TypedCallableBody
import com.mdeo.expression.ast.expressions.TypedBooleanLiteralExpression
import com.mdeo.expression.ast.expressions.TypedDoubleLiteralExpression
import com.mdeo.expression.ast.expressions.TypedExpression
import com.mdeo.expression.ast.statements.TypedReturnStatement
import com.mdeo.expression.ast.statements.TypedStatement
import com.mdeo.expression.ast.statements.TypedWhileStatement
import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.metamodel.data.ModelData
import com.mdeo.optimizer.config.*
import com.mdeo.optimizer.worker.BatchTask
import com.mdeo.optimizer.worker.NodeWorkBatchRequest
import com.mdeo.optimizer.worker.NodeWorkBatchResponse
import com.mdeo.optimizer.worker.WorkerAllocationRequest
import com.mdeo.optimizerexecution.routes.workerRoutes
import com.mdeo.optimizerexecution.service.OrchestratorRegistry
import com.mdeo.optimizerexecution.worker.LocalWorkerClient
import com.mdeo.script.ast.TypedAst
import com.mdeo.script.ast.TypedFunction
import com.mdeo.script.ast.TypedImport
import com.mdeo.script.ast.expressions.TypedExpressionSerializer as ScriptExpressionSerializer
import com.mdeo.script.ast.statements.TypedStatementSerializer
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for [WorkerService] that verify subprocess-based mutation execution,
 * including normal operation and per-mutation timeout enforcement.
 *
 * Each test starts a real [WorkerSubprocessMain] child JVM, sends work via the service,
 * and verifies the returned results.
 */
class WorkerServiceTest {

    private val scriptJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        serializersModule = SerializersModule {
            contextual(TypedExpression::class, ScriptExpressionSerializer)
            contextual(TypedStatement::class, TypedStatementSerializer)
        }
    }


    /**
     * Builds a script AST with a single function that always returns 1.0.
     */
    private fun constantDoubleAst(functionName: String): TypedAst {
        val doubleTypeIdx = 0
        return TypedAst(
            types = listOf(ClassTypeRef("builtin", "double", false)),
            metamodelPath = null,
            imports = emptyList<TypedImport>(),
            functions = listOf(
                TypedFunction(
                    name = functionName,
                    parameters = emptyList(),
                    returnType = doubleTypeIdx,
                    body = TypedCallableBody(
                        listOf(
                            TypedReturnStatement(
                                value = TypedDoubleLiteralExpression(evalType = doubleTypeIdx, value = "1.0")
                            )
                        )
                    )
                )
            )
        )
    }

    /**
     * Builds a script AST with a single function that loops forever (`while (true) {}`).
     * Used to trigger a per-mutation timeout.
     */
    private fun infiniteLoopAst(functionName: String): TypedAst {
        val boolTypeIdx = 0
        val doubleTypeIdx = 1
        return TypedAst(
            types = listOf(
                ClassTypeRef("builtin", "boolean", false),
                ClassTypeRef("builtin", "double", false)
            ),
            metamodelPath = null,
            imports = emptyList<TypedImport>(),
            functions = listOf(
                TypedFunction(
                    name = functionName,
                    parameters = emptyList(),
                    returnType = doubleTypeIdx,
                    body = TypedCallableBody(
                        listOf(
                            TypedWhileStatement(
                                condition = TypedBooleanLiteralExpression(evalType = boolTypeIdx, value = true),
                                body = emptyList()
                            ),
                            TypedReturnStatement(
                                value = TypedDoubleLiteralExpression(evalType = doubleTypeIdx, value = "0.0")
                            )
                        )
                    )
                )
            )
        )
    }


    private fun buildRequest(
        executionId: String = "test-exec",
        scriptAstJsons: Map<String, String> = emptyMap(),
        goalConfig: GoalConfig = GoalConfig(emptyList(), emptyList()),
        initialSolutionCount: Int = 1
    ) = com.mdeo.optimizer.worker.WorkerAllocationRequest(
        executionId = executionId,
        metamodelData = MetamodelData(),
        initialModelData = ModelData(metamodelPath = "", instances = emptyList(), links = emptyList()),
        transformationAstJsons = emptyMap(),
        scriptAstJsons = scriptAstJsons,
        goalConfig = goalConfig,
        solverConfig = SolverConfig(),
        initialSolutionCount = initialSolutionCount,
        useLocalChannel = true,
        threadsPerNode = 1
    )


    /**
     * Verifies that allocation starts a subprocess, generates initial solutions,
     * and that subsequent mutations succeed and return valid fitness values.
     *
     * Uses local-channel mode so that orchestrator messages travel over the subprocess
     * stdin/stdout pipe rather than a WebSocket connection.
     */
    @Test
    @Timeout(60)
    fun `allocation and mutation succeed`() = runBlocking {
        val objPath = "test://obj.fn"
        val objFn = "objective"
        val scriptAstJsons = mapOf(objPath to scriptJson.encodeToString(constantDoubleAst(objFn)))
        val goalConfig = GoalConfig(
            objectives = listOf(ObjectiveConfig(ObjectiveTendency.MINIMIZE, objPath, objFn)),
            constraints = emptyList()
        )

        val service = WorkerService(workerThreads = 1, scriptTimeoutMs = 10_000L, transformationTimeoutMs = 10_000L)
        try {
            val allocResponse = service.allocate(buildRequest(scriptAstJsons = scriptAstJsons, goalConfig = goalConfig))

            assertEquals(1, allocResponse.initialSolutions.size)

            val parentId = allocResponse.initialSolutions[0].solutionId
            val batchRequest = NodeWorkBatchRequest(
                requestId = "req-1",
                tasks = listOf(BatchTask(parentId)),
                discards = emptyList()
            )

            val responses = service.dispatchToSubprocess("test-exec", batchRequest)
            val batchResponse = responses.filterIsInstance<NodeWorkBatchResponse>().firstOrNull()
            requireNotNull(batchResponse) { "Expected NodeWorkBatchResponse, got: $responses" }

            assertEquals(1, batchResponse.results.size)
            val result = batchResponse.results[0]
            assertTrue(result.succeeded, "Mutation should succeed")
            assertEquals(parentId, result.parentSolutionId)
            assertEquals(listOf(1.0), result.objectives)
            assertTrue(result.newSolutionId.isNotEmpty())
        } finally {
            try {
                service.cleanup("test-exec")
            } catch (_: Exception) {
                service.close()
            }
        }
    }

    /**
     * Verifies that a per-mutation timeout kills an infinite-loop constraint script.
     *
     * The constraint is defined as `while (true) {}` (loops forever). Allocation is done
     * with [initialSolutionCount] = 1 and initialization does not invoke guidance functions,
     * so setup completes normally. Mutation then triggers the infinite loop. The child-process
     * watchdog fires, the subprocess halts, and [dispatchToSubprocess] returns an empty
     * list (no valid response) once the process-exit callback cancels the pending deferred.
     */
    @Test
    @Timeout(60)
    @OptIn(ExperimentalSerializationApi::class)
    fun `mutation times out on infinite-loop constraint`() = runBlocking {
        val conPath = "test://con.fn"
        val conFn = "constraint"
        val scriptAstJsons = mapOf(conPath to scriptJson.encodeToString(infiniteLoopAst(conFn)))
        val goalConfig = GoalConfig(
            objectives = emptyList(),
            constraints = listOf(ConstraintConfig(conPath, conFn))
        )

        val service = WorkerService(workerThreads = 1, scriptTimeoutMs = 500L, transformationTimeoutMs = 500L)
        try {
            val allocResponse = service.allocate(
                buildRequest(
                    scriptAstJsons = scriptAstJsons,
                    goalConfig = goalConfig,
                    initialSolutionCount = 1
                )
            )
            assertEquals(1, allocResponse.initialSolutions.size)

            val seedId = allocResponse.initialSolutions[0].solutionId
            val mutateBatch = NodeWorkBatchRequest(
                requestId = "mutate-req",
                tasks = listOf(BatchTask(seedId)),
                discards = emptyList()
            )
            val responses = service.dispatchToSubprocess("test-exec", mutateBatch)

            assertTrue(responses.isEmpty(), "Expected empty response when subprocess times out, got: $responses")
        } finally {
            try {
                service.cleanup("test-exec")
            } catch (_: Exception) {
                service.close()
            }
        }
    }

    /**
     * Regression test for the "execution stays RUNNING forever after timeout" bug.
     *
     * Verifies that when a subprocess times out during mutation in local-channel mode and
     * sends a [com.mdeo.optimizer.worker.WorkerShutdownNotice] over the WebSocket,
     * [WorkerClient.executeNodeBatch] throws [WorkerShutdownException] promptly — not after
     * the 600-second [WorkerClient.OPERATION_TIMEOUT_MS].
     *
     * The @Timeout(30) guard catches any regression where pending requests are not drained
     * on [WorkerShutdownNotice], forcing callers to wait for the full operation timeout.
     *
     * Flow:
     * 1. Start a real embedded Ktor/Netty server with the [WorkerService].
     * 2. Allocate a subprocess with an infinite-loop constraint and 0 initial solutions.
     * 3. Trigger mutation via [WorkerClient] (WS path) — the infinite-loop script fires.
     * 4. Assert that [WorkerShutdownException] is thrown before the 30-second test timeout.
     */
    @Test
    @Timeout(30)
    @OptIn(ExperimentalSerializationApi::class)
    fun `subprocess timeout propagates WorkerShutdownException via WorkerClient`() = runBlocking {
        val port = java.net.ServerSocket(0).use { it.localPort }
        val executionId = "timeout-ws-test"
        val workerService = WorkerService(
            workerThreads = 1,
            scriptTimeoutMs = 500L,
            transformationTimeoutMs = 500L,
            serverPort = port
        )
        val orchestratorRegistry = OrchestratorRegistry()
        val server = embeddedServer(Netty, port = port) {
            install(WebSockets)
            configureSerialization()
            routing {
                workerRoutes(workerService, orchestratorRegistry)
            }
        }
        server.start()

        val scope = CoroutineScope(Dispatchers.Default + Job())
        val client = LocalWorkerClient(
            nodeId = "0",
            baseUrl = "http://localhost:$port",
            workerService = workerService,
            scope = scope
        )
        try {
            val conPath = "test://con.fn"
            val conFn = "constraint"
            val scriptAstJsons = mapOf(conPath to scriptJson.encodeToString(infiniteLoopAst(conFn)))
            val goalConfig = GoalConfig(
                objectives = emptyList(),
                constraints = listOf(ConstraintConfig(conPath, conFn))
            )
            val allocRequest = WorkerAllocationRequest(
                executionId = executionId,
                metamodelData = MetamodelData(),
                initialModelData = ModelData(metamodelPath = "", instances = emptyList(), links = emptyList()),
                transformationAstJsons = emptyMap(),
                scriptAstJsons = scriptAstJsons,
                goalConfig = goalConfig,
                solverConfig = SolverConfig(),
                initialSolutionCount = 1,
                useLocalChannel = true,
                threadsPerNode = 1
            )
            val allocResponse = client.allocate(allocRequest)
            val seedId = allocResponse.initialSolutions.first().solutionId

            val exception = assertThrows<WorkerShutdownException> {
                runBlocking {
                    client.executeNodeBatch(
                        executionId,
                        tasks = listOf(BatchTask(seedId)),
                        evaluationTasks = emptyList(),
                        discards = emptyList()
                    )
                }
            }
            assertTrue(exception.message != null, "WorkerShutdownException should carry a reason message")
        } finally {
            scope.cancel()
            runCatching { workerService.cleanup(executionId) }
            runCatching { workerService.close() }
            server.stop(gracePeriodMillis = 0, timeoutMillis = 0)
        }
    }
}
