package com.mdeo.optimizerexecution.routes

import com.mdeo.optimizer.worker.*
import com.mdeo.optimizerexecution.service.OrchestratorRegistry
import com.mdeo.optimizerexecution.worker.WorkerService
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import org.slf4j.LoggerFactory

/** Logger instance for worker HTTP and WebSocket route handlers. */
private val logger = LoggerFactory.getLogger("WorkerRoutes")

/** CBOR codec used to decode/encode [WorkerWsMessage] frames. */
@OptIn(ExperimentalSerializationApi::class)
private val cbor = Cbor { ignoreUnknownKeys = true }

/**
 * Registers worker API routes under `/api/worker`, `/ws/peer`, and `/ws/subprocess`.
 *
 * HTTP routes handle the initial allocation handshake, solution data retrieval, and cleanup.
 * The `/ws/peer` route accepts direct solution-push connections from peer subprocesses.
 * The `/ws/subprocess` route accepts reverse connections from worker subprocesses,
 * completing the [OrchestratorRegistry] deferred so the orchestrator-side [WorkerClient]
 * can start communicating with the subprocess directly.
 *
 * @param workerService The service managing worker-side execution state.
 * @param orchestratorRegistry The global registry for routing subprocess connections.
 */
@OptIn(ExperimentalSerializationApi::class)
fun Route.workerRoutes(workerService: WorkerService, orchestratorRegistry: OrchestratorRegistry) {
    route("/api/worker/executions") {
        allocateRoute(workerService)

        route("/{id}") {
            getSolutionDataRoute(workerService)
            cleanupRoute(workerService)
        }
    }

    metadataRoute(workerService)

    route("/ws/peer/executions/{id}") {
        peerWsRoute(workerService)
    }

    route("/ws/subprocess/executions/{executionId}/{nodeId}") {
        subprocessWsRoute(orchestratorRegistry)
    }
}

/**
 * GET `/api/worker/metadata` — returns thread count and supported algorithm backends.
 *
 * @param workerService The worker service from which metadata is derived.
 */
private fun Route.metadataRoute(workerService: WorkerService) {
    get("/api/worker/metadata") {
        val metadata = workerService.getMetadata()
        call.respond(HttpStatusCode.OK, metadata)
    }
}

/**
 * POST `/api/worker/executions` — allocates resources for a new optimization execution.
 *
 * @param workerService The worker service to delegate allocation to.
 */
private fun Route.allocateRoute(workerService: WorkerService) {
    post {
        val request = call.receive<WorkerAllocationRequest>()
        try {
            val response = workerService.allocate(request, orchestratorWsUrl = request.orchestratorWsUrl)
            call.respond(HttpStatusCode.Created, response)
        } catch (e: Exception) {
            logger.error("Allocation failed for execution {}", request.executionId, e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Allocation failed")))
        }
    }
}

/**
 * GET `/api/worker/executions/{id}/solutions/{solutionId}` — returns model data as CBOR.
 *
 * The orchestrator calls this to pre-fetch model data for solutions it needs to transfer
 * to another node during rebalancing.
 *
 * @param workerService The worker service to retrieve solution data from.
 */
@OptIn(ExperimentalSerializationApi::class)
private fun Route.getSolutionDataRoute(workerService: WorkerService) {
    get("/solutions/{solutionId}") {
        val executionId = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val solutionId = call.parameters["solutionId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        try {
            val modelData = workerService.getSolutionData(executionId, solutionId)
            val bytes = cbor.encodeToByteArray(modelData)
            call.respondBytes(bytes, ContentType.Application.Cbor)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to (e.message ?: "Not found")))
        } catch (e: Exception) {
            logger.error("Failed to get solution {} for execution {}", solutionId, executionId, e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Failed")))
        }
    }
}

/**
 * DELETE `/api/worker/executions/{id}` — cleans up an execution and frees resources.
 *
 * @param workerService The worker service to delegate cleanup to.
 */
private fun Route.cleanupRoute(workerService: WorkerService) {
    delete {
        val executionId = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
        try {
            workerService.cleanup(executionId)
            call.respond(HttpStatusCode.OK)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to (e.message ?: "Not found")))
        } catch (e: Exception) {
            logger.error("Failed to cleanup execution {}", executionId, e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Failed")))
        }
    }
}

/**
 * WebSocket `/ws/peer/executions/{id}` — peer-subprocess push session.
 *
 * Source worker subprocesses connect here to push relocated solution models directly
 * to this node's subprocess, bypassing the orchestrator entirely. Each
 * [SolutionPushRequest] frame is forwarded into the subprocess via a
 * [SubprocessChannelMessage.SolutionInjected] channel message.
 *
 * @param workerService The worker service that injects solutions into its subprocess.
 */
private fun Route.peerWsRoute(workerService: WorkerService) {
    webSocket {
        val executionId = call.parameters["id"]
        if (executionId == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Missing execution ID"))
            return@webSocket
        }
        workerService.handlePeerSession(executionId, this)
    }
}

/**
 * WebSocket `/ws/subprocess/executions/{executionId}/{nodeId}` — subprocess reverse connection.
 *
 * Worker subprocesses connect here after receiving an orchestrator WS URL during their
 * [Setup][com.mdeo.optimizerexecution.worker.WorkerSubprocessRequest.Setup] command.
 * The route completes the [OrchestratorRegistry] deferred for the matching key, making
 * the session available to the orchestrator-side [WorkerClient].
 *
 * The WebSocket is kept alive until the remote side disconnects or an error occurs.
 *
 * @param orchestratorRegistry The registry to complete the pending deferred on.
 */
private fun Route.subprocessWsRoute(orchestratorRegistry: OrchestratorRegistry) {
    webSocket {
        val executionId = call.parameters["executionId"]
        val nodeId = call.parameters["nodeId"]
        if (executionId == null || nodeId == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Missing executionId or nodeId"))
            return@webSocket
        }
        val key = OrchestratorRegistry.key(executionId, nodeId)
        if (!orchestratorRegistry.complete(key, this)) {
            logger.warn("No pending orchestrator for subprocess connection: {}", key)
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "No matching orchestrator"))
            return@webSocket
        }
        logger.info("Subprocess connected: {}", key)
        try {
            closeReason.await()
        } finally {
            logger.info("Subprocess disconnected: {}", key)
        }
    }
}
