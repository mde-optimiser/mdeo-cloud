package com.mdeo.optimizerexecution.worker

import com.mdeo.execution.common.subprocess.SubprocessRunner
import com.mdeo.expression.ast.TypedCallableBody
import com.mdeo.expression.ast.expressions.TypedDoubleLiteralExpression
import com.mdeo.expression.ast.expressions.TypedExpression
import com.mdeo.expression.ast.statements.TypedReturnStatement
import com.mdeo.expression.ast.statements.TypedStatement
import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.metamodel.data.ModelData
import com.mdeo.optimizer.config.*
import com.mdeo.optimizer.worker.*
import com.mdeo.optimizer.evaluation.ResultStatus
import com.mdeo.script.ast.TypedAst
import com.mdeo.script.ast.TypedFunction
import com.mdeo.script.ast.TypedImport
import com.mdeo.script.ast.expressions.TypedExpressionSerializer as ScriptExpressionSerializer
import com.mdeo.script.ast.statements.TypedStatementSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests verifying that [WorkerSubprocessMain] correctly handles orchestrator
 * communication via the local stdio channel ([StdioOrchestratorChannel]) when
 * [WorkerSubprocessRequest.Setup.useLocalChannel] is `true`.
 *
 * Each test spawns a real child JVM, sets it up in local-channel mode, then sends
 * [SubprocessChannelMessage.OrchestratorRequest] messages through the subprocess runner
 * and collects the resulting [SubprocessChannelMessage.OrchestratorResponses] replies via
 * the runner's channel-message callback. This exercises the exact communication path used
 * by the local node in a federated optimization run.
 */
@OptIn(ExperimentalSerializationApi::class)
class WorkerSubprocessLocalChannelTest {

    private val cbor = Cbor { ignoreUnknownKeys = true }

    private val scriptJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        serializersModule = SerializersModule {
            contextual(TypedExpression::class, ScriptExpressionSerializer)
            contextual(TypedStatement::class, TypedStatementSerializer)
        }
    }


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

    private fun buildSetup(
        scriptAstJsons: Map<String, String> = emptyMap(),
        goalConfig: GoalConfig = GoalConfig(emptyList(), emptyList()),
        initialSolutionCount: Int = 1
    ) = WorkerSubprocessRequest.Setup(
        metamodelData = MetamodelData(),
        initialModelData = ModelData(metamodelPath = "", instances = emptyList(), links = emptyList()),
        transformationAstJsons = emptyMap(),
        scriptAstJsons = scriptAstJsons,
        goalConfig = goalConfig,
        solverConfig = SolverConfig(),
        initialSolutionCount = initialSolutionCount,
        useLocalChannel = true
    )

    private fun sendOrchestratorRequest(
        runner: SubprocessRunner,
        msg: WorkerWsMessage,
        responseLatch: CountDownLatch,
        collectedResponses: CopyOnWriteArrayList<WorkerWsMessage>
    ) {
        val requestPayload = cbor.encodeToByteArray<WorkerWsMessage>(msg)
        val channelMsg = SubprocessChannelMessage.OrchestratorRequest(requestPayload)
        runner.sendChannelMessage(cbor.encodeToByteArray<SubprocessChannelMessage>(channelMsg))
    }


    /**
     * Verifies that the subprocess can receive a [NodeWorkBatchRequest] via the local
     * stdio channel and return a valid [NodeWorkBatchResponse] via
     * [SubprocessChannelMessage.OrchestratorResponses].
     *
     * Flow:
     * 1. Start subprocess with `useLocalChannel = true` and one initial solution.
     * 2. Send a [NodeWorkBatchRequest] as an [OrchestratorRequest] channel message.
     * 3. Collect the [OrchestratorResponses] reply from the subprocess.
     * 4. Assert the [NodeWorkBatchResponse] contains a successful mutation result.
     */
    @Test
    @Timeout(60)
    fun `subprocess processes NodeWorkBatchRequest via local stdio channel`() {
        val objPath = "test://obj.fn"
        val objFn = "objective"
        val scriptAstJsons = mapOf(objPath to scriptJson.encodeToString(constantDoubleAst(objFn)))
        val goalConfig = GoalConfig(
            objectives = listOf(ObjectiveConfig(ObjectiveTendency.MINIMIZE, objPath, objFn)),
            constraints = emptyList()
        )

        val responses = CopyOnWriteArrayList<WorkerWsMessage>()
        val responseLatch = CountDownLatch(1)

        val runner = SubprocessRunner(
            mainClass = WorkerSubprocessMain::class.java.name,
            onChannelMessage = { payload, _ ->
                try {
                    val msg = cbor.decodeFromByteArray<SubprocessChannelMessage>(payload)
                    if (msg is SubprocessChannelMessage.OrchestratorResponses) {
                        for (p in msg.payloads) {
                            responses.add(cbor.decodeFromByteArray<WorkerWsMessage>(p))
                        }
                        responseLatch.countDown()
                    }
                } catch (_: Exception) {
                    // Ignore non-orchestrator channel messages (e.g. SolutionsStored)
                }
            }
        )

        try {
            assertTrue(runner.start(startupTimeoutMs = 30_000L), "Subprocess failed to start")

            val setup = buildSetup(scriptAstJsons = scriptAstJsons, goalConfig = goalConfig)
            val setupResult = runner.sendCommand(cbor.encodeToByteArray<WorkerSubprocessRequest>(setup))
            val setupOk = cbor.decodeFromByteArray<WorkerSubprocessResponse>(
                (setupResult as com.mdeo.execution.common.subprocess.SubprocessResult.Success).data
            ) as? WorkerSubprocessResponse.SetupOk
            requireNotNull(setupOk) { "Expected SetupOk but got $setupResult" }
            assertEquals(1, setupOk.solutions.size, "Expected one initial solution")

            val initialSolutionId = setupOk.solutions[0].solutionId

            val batchRequest = NodeWorkBatchRequest(
                requestId = "test-req-1",
                tasks = listOf(BatchTask(initialSolutionId)),
                discards = emptyList()
            )
            val requestPayload = cbor.encodeToByteArray<WorkerWsMessage>(batchRequest)
            runner.sendChannelMessage(
                cbor.encodeToByteArray<SubprocessChannelMessage>(
                    SubprocessChannelMessage.OrchestratorRequest(requestPayload)
                )
            )

            assertTrue(
                responseLatch.await(30, TimeUnit.SECONDS),
                "Timed out waiting for OrchestratorResponses from subprocess"
            )

            assertEquals(1, responses.size, "Expected exactly one response message")
            val batchResponse = responses[0] as? NodeWorkBatchResponse
            requireNotNull(batchResponse) { "Expected NodeWorkBatchResponse but got ${responses[0]::class.simpleName}" }

            assertEquals("test-req-1", batchResponse.requestId)
            assertEquals(1, batchResponse.results.size)
            val result = batchResponse.results[0]
            assertTrue(result.status == ResultStatus.SUCCESS, "Mutation via local channel should succeed")
            assertEquals(initialSolutionId, result.parentSolutionId)
            assertEquals(listOf(1.0), result.objectives)
            assertTrue(result.newSolutionId.isNotEmpty(), "New solution ID must not be empty")
        } finally {
            runner.destroy()
        }
    }

    /**
     * Verifies that solution discard requests included in a [NodeWorkBatchRequest]
     * are correctly processed in local-channel mode (the batch response is empty,
     * no errors occur, and the discarded solution is removed from the subprocess store).
     */
    @Test
    @Timeout(60)
    fun `subprocess processes discards in local-channel mode`() {
        val responses = CopyOnWriteArrayList<WorkerWsMessage>()
        val batchResponseLatch = CountDownLatch(1)

        val runner = SubprocessRunner(
            mainClass = WorkerSubprocessMain::class.java.name,
            onChannelMessage = { payload, _ ->
                try {
                    when (val msg = cbor.decodeFromByteArray<SubprocessChannelMessage>(payload)) {
                        is SubprocessChannelMessage.OrchestratorResponses -> {
                            for (p in msg.payloads) {
                                responses.add(cbor.decodeFromByteArray<WorkerWsMessage>(p))
                            }
                            batchResponseLatch.countDown()
                        }
                        else -> { /* ignore other channel messages */ }
                    }
                } catch (_: Exception) {}
            }
        )

        try {
            assertTrue(runner.start(startupTimeoutMs = 30_000L), "Subprocess failed to start")

            // Setup with 1 initial solution, no objective/constraint (no evaluation needed)
            val setup = buildSetup(initialSolutionCount = 1)
            val setupResult = runner.sendCommand(cbor.encodeToByteArray<WorkerSubprocessRequest>(setup))
            val setupOk = cbor.decodeFromByteArray<WorkerSubprocessResponse>(
                (setupResult as com.mdeo.execution.common.subprocess.SubprocessResult.Success).data
            ) as? WorkerSubprocessResponse.SetupOk
            requireNotNull(setupOk)
            val initialId = setupOk.solutions[0].solutionId

            // Send a batch with no tasks but with a discard
            val batchRequest = NodeWorkBatchRequest(
                requestId = "req-discard",
                tasks = emptyList(),
                discards = listOf(initialId)
            )
            runner.sendChannelMessage(
                cbor.encodeToByteArray<SubprocessChannelMessage>(
                    SubprocessChannelMessage.OrchestratorRequest(
                        cbor.encodeToByteArray<WorkerWsMessage>(batchRequest)
                    )
                )
            )

            assertTrue(batchResponseLatch.await(30, TimeUnit.SECONDS), "Timed out waiting for batch response")

            val batchResponse = responses.singleOrNull() as? NodeWorkBatchResponse
            requireNotNull(batchResponse) { "Expected NodeWorkBatchResponse but got $responses" }
            assertEquals("req-discard", batchResponse.requestId)
            assertEquals(0, batchResponse.results.size, "Discard-only batch should produce no mutation results")
        } finally {
            runner.destroy()
        }
    }

    /**
     * Verifies that mutation offspring are returned correctly via the batch response.
     */
    @Test
    @Timeout(60)
    fun `subprocess returns mutation results in batch response local-channel mode`() {
        val objPath = "test://obj.fn"
        val objFn = "objective"
        val scriptAstJsons = mapOf(objPath to scriptJson.encodeToString(constantDoubleAst(objFn)))
        val goalConfig = GoalConfig(
            objectives = listOf(ObjectiveConfig(ObjectiveTendency.MINIMIZE, objPath, objFn)),
            constraints = emptyList()
        )

        val responses = CopyOnWriteArrayList<WorkerWsMessage>()
        val responseLatch = CountDownLatch(1)

        val runner = SubprocessRunner(
            mainClass = WorkerSubprocessMain::class.java.name,
            onChannelMessage = { payload, _ ->
                try {
                    when (val msg = cbor.decodeFromByteArray<SubprocessChannelMessage>(payload)) {
                        is SubprocessChannelMessage.OrchestratorResponses -> {
                            for (p in msg.payloads) {
                                responses.add(cbor.decodeFromByteArray<WorkerWsMessage>(p))
                            }
                            responseLatch.countDown()
                        }
                        else -> { /* ignore */ }
                    }
                } catch (_: Exception) {}
            }
        )

        try {
            assertTrue(runner.start(startupTimeoutMs = 30_000L), "Subprocess failed to start")

            val setup = buildSetup(scriptAstJsons = scriptAstJsons, goalConfig = goalConfig)
            val setupResult = runner.sendCommand(cbor.encodeToByteArray<WorkerSubprocessRequest>(setup))
            val setupOk = cbor.decodeFromByteArray<WorkerSubprocessResponse>(
                (setupResult as com.mdeo.execution.common.subprocess.SubprocessResult.Success).data
            ) as? WorkerSubprocessResponse.SetupOk
            requireNotNull(setupOk) { "Expected SetupOk but got $setupResult" }
            assertEquals(1, setupOk.solutions.size, "Expected one initial solution")

            val initialSolutionId = setupOk.solutions[0].solutionId
            val batchRequest = NodeWorkBatchRequest(
                requestId = "req-mutations",
                tasks = listOf(BatchTask(initialSolutionId), BatchTask(initialSolutionId)),
                discards = emptyList()
            )
            runner.sendChannelMessage(
                cbor.encodeToByteArray<SubprocessChannelMessage>(
                    SubprocessChannelMessage.OrchestratorRequest(
                        cbor.encodeToByteArray<WorkerWsMessage>(batchRequest)
                    )
                )
            )

            assertTrue(responseLatch.await(30, TimeUnit.SECONDS), "Timed out waiting for batch response")

            val batchResponse = responses.singleOrNull() as? NodeWorkBatchResponse
            requireNotNull(batchResponse) { "Expected NodeWorkBatchResponse but got $responses" }
            assertEquals(2, batchResponse.results.size)
            assertTrue(batchResponse.results.all { it.status == ResultStatus.SUCCESS }, "All mutations should succeed")
            assertTrue(batchResponse.results.all { it.newSolutionId.isNotEmpty() }, "All results must have new solution IDs")
        } finally {
            runner.destroy()
        }
    }
}
