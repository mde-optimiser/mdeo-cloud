package com.mdeo.optimizerexecution.service

import com.mdeo.common.model.ExecutionState
import com.mdeo.execution.common.routes.FileEntry
import com.mdeo.execution.common.service.ExecutionServiceWithFileTree
import com.mdeo.expression.ast.expressions.TypedExpression
import com.mdeo.expression.ast.statements.TypedStatement
import com.mdeo.metamodel.Metamodel
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.expression.ast.types.ReturnType
import com.mdeo.modeltransformation.ast.TypedAst as TransformationTypedAst
import com.mdeo.metamodel.data.ModelData
import com.mdeo.modeltransformation.ast.expressions.TypedExpressionSerializer as TransformationExpressionSerializer
import com.mdeo.modeltransformation.ast.patterns.TypedPatternElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternElementSerializer
import com.mdeo.modeltransformation.ast.statements.TypedTransformationStatement
import com.mdeo.modeltransformation.ast.statements.TypedTransformationStatementSerializer
import com.mdeo.optimizer.OptimizationOrchestrator
import com.mdeo.optimizer.config.*
import com.mdeo.optimizer.evaluation.MutationEvaluator
import com.mdeo.optimizer.evaluation.WorkerSolutionRef
import com.mdeo.optimizer.moea.SearchResult
import com.mdeo.optimizer.moea.SolutionResult
import com.mdeo.optimizer.moea.getWorkerRef
import com.mdeo.optimizer.metrics.OptimizationMetricsCollector
import com.mdeo.optimizer.rulegen.MutationRuleGenerator
import com.mdeo.optimizer.worker.WorkerAllocationRequest
import com.mdeo.optimizerexecution.config.AppConfig
import com.mdeo.optimizerexecution.database.OptimizerExecutionsTable
import com.mdeo.optimizerexecution.database.OptimizerResultFilesTable
import com.mdeo.optimizerexecution.worker.FederatedMutationEvaluator
import com.mdeo.optimizerexecution.worker.LocalWorkerClient
import com.mdeo.optimizerexecution.worker.RemoteWorkerClient
import com.mdeo.optimizerexecution.worker.WorkerClient
import com.mdeo.optimizerexecution.worker.WorkerService
import com.mdeo.script.ast.TypedAst as ScriptTypedAst
import com.mdeo.script.ast.expressions.TypedExpressionSerializer as ScriptExpressionSerializer
import com.mdeo.script.ast.statements.TypedStatementSerializer

import com.mdeo.metamodel.Model
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.*
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

/**
 * Service for executing optimization runs.
 *
 * Implements the full optimization lifecycle:
 * 1. Fetch all transformation typed ASTs at the start
 * 2. Fetch all script typed ASTs for objectives/constraints at the start
 * 3. Compile all scripts together with metamodel at the start
 * 4. Load the initial model into a graph backend
 * 5. Wire guidance functions, mutation strategies, and the search algorithm
 * 6. Run the evolutionary optimization
 * 7. Store results and update execution state
 *
 * Execution is asynchronous: [createAndStartExecution] returns as soon as the config is
 * parsed and the execution record is created.  The optimization itself runs in a background
 * coroutine bound to [executionScope].
 *
 * @param apiClient Client for backend API communication
 * @param executionScope Scope in which background execution coroutines are launched
 * @param appConfig Application configuration including multi-node peer settings and default timeouts.
 */
class OptimizerExecutionService(
    private val apiClient: OptimizerApiClient,
    private val executionScope: CoroutineScope,
    private val appConfig: AppConfig,
    private val orchestratorRegistry: OrchestratorRegistry = OrchestratorRegistry(),
    private val workerService: WorkerService? = null
) : ExecutionServiceWithFileTree {
    /**
     * Logger instance for this service. 
     */
    private val logger = LoggerFactory.getLogger(OptimizerExecutionService::class.java)
    /**
     * Pretty-printing JSON codec for general serialisation. 
     */
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /**
     * JSON codec for transformation typed ASTs with contextual serializers. 
     */
    private val transformationJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        serializersModule = SerializersModule {
            contextual(TypedExpression::class, TransformationExpressionSerializer)
            contextual(TypedTransformationStatement::class, TypedTransformationStatementSerializer)
            contextual(TypedPatternElement::class, TypedPatternElementSerializer)
        }
    }

    /**
     * JSON codec for script typed ASTs with contextual serializers. 
     */
    private val scriptJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        serializersModule = SerializersModule {
            contextual(TypedExpression::class, ScriptExpressionSerializer)
            contextual(TypedStatement::class, TypedStatementSerializer)
        }
    }

    companion object {
        /**
         * Maximum length of a config file path accepted by this service. 
         */
        private const val MAX_PATH_LENGTH = 1000
        /**
         * Directory prefix for result files stored under an execution. 
         */
        private const val RESULTS_DIR = "results"
        /**
         * File path for the markdown summary stored alongside solution files. 
         */
        private const val SUMMARY_FILE = "summary.md"
        /**
         * File path for the JSON execution report stored alongside solution files.
         */
        private const val REPORT_FILE = "report.json"
        /**
         * MIME type used for JSON result files. 
         */
        private const val MIME_TYPE_JSON = "application/json"
        /**
         * MIME type used for Markdown result files. 
         */
        private const val MIME_TYPE_MARKDOWN = "text/markdown"
    }

    /**
     * Creates a new execution record and launches the optimization in a background coroutine.
     *
     * @param executionId Unique identifier for the new execution.
     * @param projectId Project that owns the execution.
     * @param filePath Path to the config file that triggered the execution.
     * @param data Raw JSON configuration payload.
     * @param jwtToken Bearer token for backend API authentication.
     * @return A human-readable description of the started optimization.
     */
    override suspend fun createAndStartExecution(
        executionId: UUID,
        projectId: UUID,
        filePath: String,
        data: JsonElement,
        jwtToken: String
    ): String = withContext(Dispatchers.IO) {
        val config = parseOptimizationConfig(data)
        validateConfig(config)

        createExecutionRecord(executionId, projectId, filePath, data)
        logger.info("Created optimizer execution $executionId for project $projectId")

        executionScope.launch(Dispatchers.Default) {
            try {
                runOptimization(executionId, projectId, config, jwtToken)
            } catch (e: CancellationException) {
                logger.info("Optimizer execution $executionId was cancelled")
            } catch (e: Throwable) {
                handleUnexpectedError(executionId, e, jwtToken)
            }
        }

        "Optimization: ${config.solver.algorithm} on ${config.problem.modelPath}"
    }

    /**
     * Marks the execution as cancelled in the database.
     *
     * @param executionId The execution to cancel.
     */
    override suspend fun cancelExecution(executionId: UUID) = withContext(Dispatchers.IO) {
        transaction {
            OptimizerExecutionsTable.update({
                OptimizerExecutionsTable.id eq executionId.toKotlinUuid()
            }) {
                it[state] = ExecutionState.CANCELLED
                it[progress] = "Cancelled by user"
                it[completedAt] = Instant.now()
            }
        }
        logger.info("Cancelled optimizer execution $executionId")
    }

    /**
     * Deletes the execution record and all associated result files from the database.
     *
     * @param executionId The execution to delete.
     */
    override suspend fun deleteExecution(executionId: UUID) = withContext(Dispatchers.IO) {
        transaction {
            OptimizerResultFilesTable.deleteWhere {
                OptimizerResultFilesTable.executionId eq executionId.toKotlinUuid()
            }
            OptimizerExecutionsTable.deleteWhere { id eq executionId.toKotlinUuid() }
        }
        logger.info("Deleted optimizer execution $executionId")
    }

    /**
     * Returns a markdown summary for the given execution, or `null` if the execution does not exist.
     *
     * @param executionId The execution to summarise.
     * @return A markdown-formatted summary string, or `null` if not found.
     */
    override suspend fun getSummary(executionId: UUID): String? = withContext(Dispatchers.IO) {
        val execution = findExecution(executionId) ?: return@withContext null
        buildSummary(execution)
    }

    /**
     * Returns the list of result files for the given execution, optionally filtered by path prefix.
     *
     * @param executionId The execution whose result files to list.
     * @param path Optional path prefix to filter by, or `null` for all files.
     * @return A list of [FileEntry] instances, or `null` if the execution does not exist.
     */
    override suspend fun getFileTree(executionId: UUID, path: String?): List<FileEntry>? {
        return withContext(Dispatchers.IO) {
            findExecution(executionId) ?: return@withContext null

            val files = transaction {
                OptimizerResultFilesTable.selectAll()
                    .where { OptimizerResultFilesTable.executionId eq executionId.toKotlinUuid() }
                    .map { row ->
                        FileEntry(row[OptimizerResultFilesTable.filePath], FileEntry.TYPE_FILE)
                    }
                    .filter { it.name != SUMMARY_FILE }
            }

            if (path.isNullOrBlank()) files
            else files.filter { it.name.startsWith(path) }
        }
    }

    /**
     * Returns the content of a specific result file for the given execution.
     *
     * @param executionId The execution that owns the result file.
     * @param filePath The path of the result file to retrieve.
     * @return The file content string, or `null` if not found.
     */
    override suspend fun getFileContents(executionId: UUID, filePath: String): String? {
        return withContext(Dispatchers.IO) {
            transaction {
                OptimizerResultFilesTable.selectAll()
                    .where {
                        (OptimizerResultFilesTable.executionId eq executionId.toKotlinUuid()) and
                            (OptimizerResultFilesTable.filePath eq filePath)
                    }
                    .firstOrNull()
                    ?.get(OptimizerResultFilesTable.content)
            }
        }
    }

    /**
     * Full optimization pipeline: fetches metamodel, model, transformation ASTs, script ASTs,
     * and runs the optimizer in federated mode (always via worker subprocess nodes).
     * Updates execution state at each phase; sets state to FAILED on any error.
     *
     * Script compilation happens inside each worker subprocess — the orchestrator only
     * fetches and forwards the typed ASTs.
     *
     * @param executionId The execution record to drive.
     * @param projectId Project that owns the execution.
     * @param config Parsed optimization configuration.
     * @param jwtToken Bearer token for backend API authentication.
     */
    private suspend fun runOptimization(
        executionId: UUID,
        projectId: UUID,
        config: OptimizationConfig,
        jwtToken: String
    ) {
        updateState(executionId, ExecutionState.INITIALIZING, "Fetching metamodel...", jwtToken)
        val metamodelData = fetchMetamodelData(executionId, projectId, config.problem.metamodelPath, jwtToken)
            ?: return
        val metamodel = Metamodel.compile(metamodelData)

        updateState(executionId, ExecutionState.INITIALIZING, "Fetching model...", jwtToken)
        val modelData = fetchModelData(executionId, projectId, config.problem.modelPath, jwtToken)
            ?: return

        updateState(
            executionId, ExecutionState.INITIALIZING,
            "Fetching ${config.search.mutations.usingPaths.size} transformation(s)...", jwtToken
        )
        val fetchedTransformations = fetchAllTransformations(
            executionId, projectId, config.search.mutations.usingPaths, jwtToken
        ) ?: return

        val transformations: Map<String, TransformationTypedAst> =
            if (config.search.mutations.generate.isNotEmpty()) {
                val generated = MutationRuleGenerator.generate(
                    metamodelData = metamodelData,
                    specs = config.search.mutations.generate,
                    metamodelPath = config.problem.metamodelPath
                )
                logger.info(
                    "Auto-generated {} mutation rule(s) from {} spec(s)",
                    generated.size, config.search.mutations.generate.size
                )
                updateState(
                    executionId, ExecutionState.INITIALIZING,
                    "Auto-generated ${generated.size} mutation rule(s)...", jwtToken
                )
                val merged = LinkedHashMap<String, TransformationTypedAst>()
                generated.forEach { m -> merged[m.name] = m.typedAst }
                merged.putAll(fetchedTransformations)
                merged
            } else {
                fetchedTransformations
            }

        val scriptPaths = collectScriptPaths(config.goal)
        updateState(
            executionId, ExecutionState.INITIALIZING,
            "Fetching ${scriptPaths.size} script file(s)...", jwtToken
        )
        val scriptAsts = fetchAllScripts(executionId, projectId, scriptPaths, jwtToken)
            ?: return

        val federated = createFederatedEvaluator(
            executionId, config, metamodelData, modelData,
            transformations, scriptAsts
        )
        updateState(
            executionId, ExecutionState.RUNNING,
            "Running federated ${config.solver.algorithm} optimizer across ${federated.workerCount} nodes...",
            jwtToken
        )
        runWithEvaluator(executionId, config, metamodel, federated, jwtToken)
    }

    /**
     * Runs the optimization using the supplied [MutationEvaluator].
     *
     * Both local and federated modes converge here: the [OptimizationOrchestrator]
     * drives the MOEA-native delegating algorithms, routing all mutation and evaluation
     * work through the [evaluator].
     *
     * For federated evaluators whose workers hold WebSocket connections, wrap the call
     * in a [coroutineScope] so that child coroutines (e.g. reading loops) are cancelled
     * when the algorithm finishes or is cancelled.
     *
     * @param executionId The execution record to drive.
     * @param config Parsed optimization configuration.
     * @param evaluator The mutation evaluator (local or federated).
     * @param jwtToken Bearer token for backend API authentication.
     */
    private suspend fun runWithEvaluator(
        executionId: UUID,
        config: OptimizationConfig,
        metamodel: Metamodel,
        evaluator: MutationEvaluator,
        jwtToken: String
    ) {
        val orchestrator = OptimizationOrchestrator(config = config, evaluator = evaluator)

        val startTimeMs = System.currentTimeMillis()
        try {
            val result = try {
                orchestrator.run { generation ->
                    val approxEvaluations = generation * config.solver.parameters.population
                    // Fire-and-forget: state persistence and backend notification must not
                    // block the optimization loop from advancing to the next generation.
                    executionScope.launch {
                        updateProgress(
                            executionId,
                            "Generation $generation (~$approxEvaluations evaluations)",
                            jwtToken
                        )
                    }
                    checkCancelled(executionId)
                }
            } catch (e: CancellationException) {
                logger.info("Optimizer execution $executionId stopped: ${e.message}")
                return
            } catch (e: Throwable) {
                logger.error("Optimization failed", e)
                storeError(executionId, e.message)
                updateState(executionId, ExecutionState.FAILED, "Optimization failed: ${e.message}", jwtToken)
                return
            }

            val durationMs = System.currentTimeMillis() - startTimeMs
            storeResults(executionId, config, result, metamodel, evaluator, durationMs)
            updateState(executionId, ExecutionState.COMPLETED, "Completed successfully", jwtToken)
            logger.info("Optimizer execution $executionId completed")
        } finally {
            evaluator.cleanup()
        }
    }


    /**
     * Creates a [FederatedMutationEvaluator] wrapping WebSocket connections to worker peers.
     *
     * Worker node count and per-node thread allocation are constrained by
     * [RuntimeConfig.ResourcesConfig] from the optimization config.
     *
     * Note: the returned evaluator's worker clients are scoped to the current coroutine.
     * The caller must ensure the coroutineScope outlives the evaluator's usage.
     *
     * @param executionId The execution identifier.
     * @param config Parsed optimization configuration.
     * @param metamodelData The metamodel data to send to workers.
     * @param modelData The initial model data to send to workers.
     * @param transformations Map of transformation path to typed AST.
     * @param scriptAsts Map of script path to typed AST.
     * @return A [FederatedMutationEvaluator] ready for use.
     */
    private suspend fun createFederatedEvaluator(
        executionId: UUID,
        config: OptimizationConfig,
        metamodelData: MetamodelData,
        modelData: ModelData,
        transformations: Map<String, TransformationTypedAst>,
        scriptAsts: Map<String, ScriptTypedAst>
    ): FederatedMutationEvaluator {
        val resources = config.runtime.resources
        val requiredBackend = config.runtime.backend ?: GraphBackendType.MDEO
        val workerAndBudgets = createWorkerClients(executionScope, resources, requiredBackend)
        val workers = workerAndBudgets.map { it.first }
        val workerThreadBudgets = workerAndBudgets.associate { (client, budget) -> client.nodeId to budget }
        val allocationRequest = buildAllocationRequest(
            executionId, config, metamodelData, modelData, transformations, scriptAsts,
        )
        return FederatedMutationEvaluator(executionId.toString(), workers, allocationRequest, workerThreadBudgets)
    }

    /**
     * Builds a [WorkerAllocationRequest] containing all resources workers need to set up
     * their local evaluation environments.
     *
     * Transformation and script typed ASTs are serialized to JSON strings using the
     * appropriate contextual serializer modules.
     *
     * @param executionId The execution identifier.
     * @param config Parsed optimization configuration.
     * @param metamodelData The metamodel data.
     * @param modelData The initial model data.
     * @param transformations Map of transformation path to typed AST.
     * @param scriptAsts Map of script path to typed AST.
     * @return A fully populated [WorkerAllocationRequest].
     */
    private fun buildAllocationRequest(
        executionId: UUID,
        config: OptimizationConfig,
        metamodelData: MetamodelData,
        modelData: ModelData,
        transformations: Map<String, TransformationTypedAst>,
        scriptAsts: Map<String, ScriptTypedAst>,
    ): WorkerAllocationRequest {
        val transformationAstJsons = transformations.mapValues { (_, ast) ->
            transformationJson.encodeToString(TransformationTypedAst.serializer(), ast)
        }
        val scriptAstJsons = scriptAsts.mapValues { (_, ast) ->
            scriptJson.encodeToString(ScriptTypedAst.serializer(), ast)
        }
        return WorkerAllocationRequest(
            executionId = executionId.toString(),
            metamodelData = metamodelData,
            initialModelData = modelData,
            transformationAstJsons = transformationAstJsons,
            scriptAstJsons = scriptAstJsons,
            goalConfig = config.goal,
            solverConfig = config.solver,
            initialSolutionCount = config.solver.parameters.population,
            threadsPerNode = -1,
            graphBackendType = config.runtime.backend ?: GraphBackendType.MDEO
        )
    }

    /**
     * Creates [WorkerClient] instances constrained by the given [RuntimeConfig.ResourcesConfig].
     *
     * Node selection order: the local node (if it has threads) is added first, followed by
     * configured peers, up to the [ResourcesConfig.nodes] cap. For peer nodes the actual thread
     * count is fetched via `GET /api/worker/metadata` so that node selection uses real capacity
     * instead of local config estimates. The global [ResourcesConfig.threads] cap limits the
     * total number of threads allocated across all nodes; once exhausted, no further nodes are
     * added. Peers that cannot be reached for metadata are skipped with a warning.
     *
     * When [resources] is `null`, all available nodes are used without any thread caps
     * (backward-compatible behaviour).
     *
     * @param scope Coroutine scope that owns the WebSocket reading loops of all returned clients.
     * @param resources Optional resource constraints from the optimization config.
     * @return A list of (WorkerClient, allocatedThreadBudget) pairs, one per participating node.
     *         The integer is the exact number of threads allocated to that node after applying
     *         the global [ResourcesConfig.threads] cap, the per-node [ResourcesConfig.threadsPerNode]
     *         limit, and the node's own capacity.
     */
    private suspend fun createWorkerClients(
        scope: CoroutineScope,
        resources: RuntimeConfig.ResourcesConfig? = null,
        requiredBackend: GraphBackendType = GraphBackendType.MDEO
    ): List<Pair<WorkerClient, Int>> {
        val clients = mutableListOf<Pair<WorkerClient, Int>>()
        var nextId = 0
        val maxNodes = resources?.nodes ?: Int.MAX_VALUE
        val maxTotalThreads = resources?.threads ?: Int.MAX_VALUE
        var remainingThreads = maxTotalThreads
        val orchestratorWsBase = appConfig.nodeUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://")

        if (appConfig.workerThreads > 0 && clients.size < maxNodes) {
            val svc = requireNotNull(workerService) { "workerService must be provided when appConfig.workerThreads > 0" }
            val localMetadata = svc.getMetadata()
            if (requiredBackend in localMetadata.supportedBackends) {
                val localThreads = minOf(
                    appConfig.workerThreads,
                    resources?.threadsPerNode ?: Int.MAX_VALUE,
                    remainingThreads
                )
                if (localThreads > 0) {
                    clients.add(LocalWorkerClient(nodeId = nextId.toString(), baseUrl = appConfig.nodeUrl, workerService = svc, scope = scope) to localThreads)
                    nextId++
                    remainingThreads -= localThreads
                }
            } else {
                logger.warn(
                    "Local node does not support required backend {}; skipping",
                    requiredBackend
                )
            }
        }

        // Collect metadata for all peers and sort by usable threads descending
        // so we use the fewest nodes possible in asymmetric setups
        data class PeerInfo(val url: String, val threads: Int)
        val peerInfos = appConfig.peers.mapNotNull { url ->
            val probe = RemoteWorkerClient(
                nodeId = "probe",
                baseUrl = url,
                scope = scope,
                registry = orchestratorRegistry,
                wsBaseUrl = orchestratorWsBase
            )
            val info = try {
                val metadata = probe.getMetadata()
                if (requiredBackend !in metadata.supportedBackends) {
                    logger.warn(
                        "Peer {} does not support required backend {}; skipping",
                        url, requiredBackend
                    )
                    probe.close()
                    return@mapNotNull null
                }
                val usable = resources?.threadsPerNode?.let { minOf(it, metadata.threadCount) } ?: metadata.threadCount
                PeerInfo(url, usable)
            } catch (e: Exception) {
                logger.warn(
                    "Could not fetch metadata from peer {} ({}); using local workerThreads as estimate: {}",
                    url, e.message
                )
                val estimate = resources?.threadsPerNode ?: appConfig.workerThreads
                PeerInfo(url, estimate)
            } finally {
                probe.close()
            }
            info
        }.sortedByDescending { it.threads }

        for (peer in peerInfos) {
            if (clients.size >= maxNodes || remainingThreads <= 0) break
            val allocated = minOf(peer.threads, remainingThreads)
            if (allocated <= 0) continue
            val peerClient = RemoteWorkerClient(
                nodeId = nextId.toString(),
                baseUrl = peer.url,
                scope = scope,
                registry = orchestratorRegistry,
                wsBaseUrl = orchestratorWsBase
            )
            clients.add(peerClient to allocated)
            nextId++
            remainingThreads -= allocated
        }

        return clients
    }



    /**
     * Fetches solution model data from the evaluator and persists the results.
     *
     * For each MOEA solution in the final population, the [WorkerSolutionRef] attribute
     * is used to retrieve the full model data from the owning evaluator (local or federated).
     * Results are stored alongside a markdown summary in the result-files table.
     *
     * @param executionId The execution whose results are being stored.
     * @param config The optimization configuration (used to resolve backend and resources).
     * @param result The [SearchResult] from the completed algorithm.
     * @param metamodel The metamodel used to reconstruct solution model data.
     * @param evaluator The mutation evaluator used to fetch solution model data.
     */
    private suspend fun storeResults(
        executionId: UUID,
        config: OptimizationConfig,
        result: SearchResult,
        metamodel: Metamodel,
        evaluator: MutationEvaluator,
        durationMs: Long
    ) {
        val rawSolutions = result.getFinalSolutions()
        val objectiveTendencies = config.goal.objectives.map { it.type }
        val solutions = rawSolutions.map { sol ->
            sol.copy(objectives = sol.objectives.mapIndexed { i, v ->
                if (objectiveTendencies.getOrNull(i) == ObjectiveTendency.MAXIMIZE) -v else v
            })
        }
        val backend = config.runtime.backend ?: GraphBackendType.MDEO
        val nodeThreadCounts = (evaluator as FederatedMutationEvaluator).getWorkerThreadCounts()
        val summaryContent = buildResultSummary(solutions, result.getMetrics(), nodeThreadCounts, backend)

        val moeaSolutions = result.getRawPopulation().toList()
        val solutionModelJsons = moeaSolutions.mapNotNull { sol ->
            val ref = sol.getWorkerRef() ?: return@mapNotNull null
            val modelData = evaluator.getSolutionData(ref).toModelData(metamodel)
            json.encodeToString(modelData)
        }

        val reportContent = buildJsonReport(solutions, result.getMetrics(), durationMs)

        transaction {
            OptimizerResultFilesTable.insert {
                it[id] = Uuid.random()
                it[OptimizerResultFilesTable.executionId] = executionId.toKotlinUuid()
                it[filePath] = SUMMARY_FILE
                it[content] = summaryContent
                it[mimeType] = MIME_TYPE_MARKDOWN
            }

            OptimizerResultFilesTable.insert {
                it[id] = Uuid.random()
                it[OptimizerResultFilesTable.executionId] = executionId.toKotlinUuid()
                it[filePath] = REPORT_FILE
                it[content] = reportContent
                it[mimeType] = MIME_TYPE_JSON
            }

            solutionModelJsons.forEachIndexed { index, jsonContent ->
                OptimizerResultFilesTable.insert {
                    it[id] = Uuid.random()
                    it[OptimizerResultFilesTable.executionId] = executionId.toKotlinUuid()
                    it[filePath] = "$RESULTS_DIR/solution_$index.m_gen"
                    it[content] = jsonContent
                    it[mimeType] = MIME_TYPE_JSON
                }
            }
        }
    }


    /**
     * Fetches metamodel data from the backend API.
     * Sets execution state to FAILED and returns null when the fetch fails.
     *
     * @param executionId Execution to update on failure.
     * @param projectId Project context.
     * @param metamodelPath Path to the metamodel file.
     * @param jwtToken Authentication token.
     * @return The metamodel data, or `null` on failure.
     */
    private suspend fun fetchMetamodelData(
        executionId: UUID, projectId: UUID, metamodelPath: String, jwtToken: String
    ): MetamodelData? {
        val data = apiClient.getMetamodelData(projectId.toString(), metamodelPath, jwtToken)
        if (data == null) {
            val msg = "Failed to fetch metamodel: $metamodelPath"
            storeError(executionId, msg)
            updateState(executionId, ExecutionState.FAILED, msg, jwtToken)
        }
        return data
    }

    /**
     * Fetches model data from the backend API.
     * Sets execution state to FAILED and returns null when the fetch fails.
     *
     * @param executionId Execution to update on failure.
     * @param projectId Project context.
     * @param modelPath Path to the model file.
     * @param jwtToken Authentication token.
     * @return The model data, or `null` on failure.
     */
    private suspend fun fetchModelData(
        executionId: UUID, projectId: UUID, modelPath: String, jwtToken: String
    ): ModelData? {
        val data = apiClient.getModelData(projectId.toString(), modelPath, jwtToken)
        if (data == null) {
            val msg = "Failed to fetch model: $modelPath"
            storeError(executionId, msg)
            updateState(executionId, ExecutionState.FAILED, msg, jwtToken)
        }
        return data
    }

    /**
     * Fetches all transformation typed ASTs for the given paths from the backend API.
     * Fails fast: returns null and sets execution state to FAILED if any path cannot be resolved.
     *
     * @param executionId Execution to update on failure.
     * @param projectId Project context.
     * @param usingPaths Ordered list of transformation file paths to fetch.
     * @param jwtToken Authentication token.
     * @return Map from path to typed AST, or `null` if any fetch failed.
     */
    private suspend fun fetchAllTransformations(
        executionId: UUID,
        projectId: UUID,
        usingPaths: List<String>,
        jwtToken: String
    ): Map<String, TransformationTypedAst>? {
        val result = mutableMapOf<String, TransformationTypedAst>()
        for (path in usingPaths) {
            val ast = apiClient.getTransformationTypedAst(projectId.toString(), path, jwtToken)
            if (ast == null) {
                val msg = "Failed to fetch transformation: $path"
                storeError(executionId, msg)
                updateState(executionId, ExecutionState.FAILED, msg, jwtToken)
                return null
            }
            result[path] = ast
        }
        return result
    }

    /**
     * Fetches all script typed ASTs for the given paths from the backend API,
     * including transitive dependencies discovered via each AST's [imports] field.
     * Uses BFS traversal to handle arbitrarily deep import chains while avoiding
     * duplicate fetches and infinite loops from circular imports.
     *
     * Fails fast: returns null and sets execution state to FAILED if any path cannot be resolved.
     *
     * @param executionId Execution to update on failure.
     * @param projectId Project context.
     * @param scriptPaths Set of root script file paths to fetch.
     * @param jwtToken Authentication token.
     * @return Map from path to typed AST (including all transitive dependencies),
     *         or `null` if any fetch failed.
     */
    private suspend fun fetchAllScripts(
        executionId: UUID,
        projectId: UUID,
        scriptPaths: Set<String>,
        jwtToken: String
    ): Map<String, ScriptTypedAst>? {
        val result = mutableMapOf<String, ScriptTypedAst>()
        val visited = mutableSetOf<String>()
        val pending = mutableSetOf<String>().also { it.addAll(scriptPaths) }
        while (pending.isNotEmpty()) {
            val path = pending.first()
            pending.remove(path)
            if (visited.contains(path)) continue
            visited.add(path)
            val ast = apiClient.getScriptTypedAst(projectId.toString(), path, jwtToken)
            if (ast == null) {
                val msg = "Failed to fetch script: $path"
                storeError(executionId, msg)
                updateState(executionId, ExecutionState.FAILED, msg, jwtToken)
                return null
            }
            result[path] = ast
            for (import in ast.imports) {
                if (!visited.contains(import.uri) && !pending.contains(import.uri)) {
                    pending.add(import.uri)
                }
            }
        }
        return result
    }

    /**
     * Collects the unique set of script file paths referenced by objective and constraint configs.
     *
     * @param goal The goal configuration containing objectives and constraints.
     * @return Deduplicated set of script file paths.
     */
    private fun collectScriptPaths(goal: GoalConfig): Set<String> {
        val paths = mutableSetOf<String>()
        goal.objectives.forEach { paths.add(it.path) }
        goal.constraints.forEach { paths.add(it.path) }
        return paths
    }

    /**
     * Builds a markdown result summary for the given Pareto-front solutions.
     *
     * The summary includes a node allocation section (backend and per-node thread counts)
     * followed by the Pareto front table, solution model links, and performance metrics.
     *
     * @param solutions List of solution results from the final population.
     * @param metricsCollector Collected per-generation performance metrics.
     * @param nodeThreadCounts Map from node ID to the number of threads allocated on that node.
     * @param backend The graph backend used during optimization.
     * @return Markdown-formatted summary string.
     */
    private fun buildResultSummary(
        solutions: List<com.mdeo.optimizer.moea.SolutionResult>,
        metricsCollector: OptimizationMetricsCollector,
        nodeThreadCounts: Map<String, Int>,
        backend: GraphBackendType
    ): String {
        return buildString {
            append("## Optimization Results\n\n")
            append("**Solutions found:** ${solutions.size}\n\n")

            if (solutions.isNotEmpty()) {
                append("### Pareto Front\n\n")
                append("| Solution | Objectives | Constraints |\n")
                append("|----------|-----------|-------------|\n")
                solutions.forEachIndexed { index, sol ->
                    val objStr = sol.objectives.joinToString(", ") { "%.4f".format(it) }
                    val conStr = if (sol.constraints.isEmpty()) {
                        "—"
                    } else {
                        sol.constraints.joinToString(", ") { "%.4f".format(it) }
                    }
                    append("| $index | $objStr | $conStr |\n")
                }

                append("\n### Solution Models\n\n")
                solutions.indices.forEach { index ->
                    append("![Solution $index]($RESULTS_DIR/solution_$index.m_gen)\n")
                }
            }

            append("\n### Execution Resources\n\n")
            append("- **Backend:** ${backend.name}\n")
            nodeThreadCounts.entries
                .sortedBy { it.key }
                .forEach { (nodeId, threads) ->
                    append("- **Node $nodeId:** $threads thread${if (threads == 1) { "" } else { "s" }}\n")
                }
            append("\n")

            val generations = metricsCollector.generations
            if (generations.isNotEmpty()) {
                append("\n### Metrics\n\n")

                append("#### Total Models\n\n")
                append(buildPlotBlock(
                    xTitle = "Generation",
                    yTitle = "Models",
                    traces = listOf(
                        PlotTrace("Total", generations.map { it.generation }, generations.map { it.totalModels })
                    )
                ))

                append("#### Transformations per Generation\n\n")
                append(buildPlotBlock(
                    xTitle = "Generation",
                    yTitle = "Transformations",
                    traces = listOf(
                        PlotTrace("Executed", generations.map { it.generation }, generations.map { it.executedTransformations }),
                        PlotTrace("Skipped", generations.map { it.generation }, generations.map { it.skippedOperatorSlots })
                    ),
                    stackedBars = true
                ))

                append("#### Iteration Time\n\n")
                append(buildPlotBlock(
                    xTitle = "Generation",
                    yTitle = "Time (ms)",
                    traces = listOf(
                        PlotTrace("Time", generations.map { it.generation }, generations.map { it.iterationTimeMs })
                    )
                ))

                append("#### Rebalanced Solutions per Generation\n\n")
                append(buildPlotBlock(
                    xTitle = "Generation",
                    yTitle = "Solutions transferred",
                    traces = listOf(
                        PlotTrace("Rebalanced", generations.map { it.generation }, generations.map { it.rebalancedSolutions })
                    )
                ))

                val allNodeIds = generations.flatMap { it.perNode.keys }.toSortedSet()
                if (allNodeIds.size > 1) {
                    append("#### Total Models per Node\n\n")
                    append(buildPlotBlock(
                        xTitle = "Generation",
                        yTitle = "Models",
                        traces = allNodeIds.map { nodeId ->
                            PlotTrace(
                                "Node $nodeId",
                                generations.map { it.generation },
                                generations.map { it.perNode[nodeId]?.totalModels ?: 0 }
                            )
                        }
                    ))

                    append("#### Transformations per Generation per Node\n\n")
                    append(buildPlotBlock(
                        xTitle = "Generation",
                        yTitle = "Transformations",
                        traces = allNodeIds.map { nodeId ->
                            PlotTrace(
                                "Node $nodeId",
                                generations.map { it.generation },
                                generations.map { it.perNode[nodeId]?.executedTransformations ?: 0 }
                            )
                        }
                    ))
                }
            }
        }
    }

    /**
     * Builds a JSON execution report containing duration, Pareto front models, and graph data.
     *
     * @param solutions The final Pareto-optimal solutions.
     * @param metricsCollector Per-generation metrics collected during the run.
     * @param durationMs Wall-clock duration of the optimization in milliseconds.
     * @return JSON string representing the report.
     */
    private fun buildJsonReport(
        solutions: List<SolutionResult>,
        metricsCollector: OptimizationMetricsCollector,
        durationMs: Long
    ): String {
        val report = buildJsonObject {
            put("durationMs", durationMs)
            putJsonArray("paretoFront") {
                solutions.forEachIndexed { index, sol ->
                    addJsonObject {
                        put("path", "$RESULTS_DIR/solution_$index.m_gen")
                        putJsonArray("objectives") { sol.objectives.forEach { add(it) } }
                        putJsonArray("constraints") { sol.constraints.forEach { add(it) } }
                    }
                }
            }
            putJsonObject("graphData") {
                putJsonArray("generations") {
                    metricsCollector.generations.forEach { gen ->
                        addJsonObject {
                            put("generation", gen.generation)
                            put("totalModels", gen.totalModels)
                            put("executedTransformations", gen.executedTransformations)
                            put("skippedOperatorSlots", gen.skippedOperatorSlots)
                            put("rebalancedSolutions", gen.rebalancedSolutions)
                            put("iterationTimeMs", gen.iterationTimeMs)
                            putJsonObject("perNode") {
                                gen.perNode.forEach { (nodeId, nodeMetrics) ->
                                    putJsonObject(nodeId) {
                                        put("totalModels", nodeMetrics.totalModels)
                                        put("executedTransformations", nodeMetrics.executedTransformations)
                                        put("skippedOperatorSlots", nodeMetrics.skippedOperatorSlots)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return json.encodeToString(JsonElement.serializer(), report)
    }

    /**
     * Data holder for a single plot trace in a generated chart.
     *
     * @param name Display name shown in the chart legend.
     * @param x X-axis data points.
     * @param y Y-axis data points.
     */
    private data class PlotTrace(val name: String, val x: List<Number>, val y: List<Number>)

    /**
     * Builds a fenced code block containing chart JSON in the custom `plot` format.
     *
     * @param xTitle Label for the x axis.
     * @param yTitle Label for the y axis.
     * @param traces Ordered list of traces to render in the chart.
     * @param stackedBars When true, renders as a stacked bar chart instead of line chart.
     * @return The rendered ` ```plot ``` ` code block string.
     */
    private fun buildPlotBlock(
        xTitle: String,
        yTitle: String,
        traces: List<PlotTrace>,
        stackedBars: Boolean = false
    ): String {
        val data = if (stackedBars) {
            traces.joinToString(",") { trace ->
                """{"x":${trace.x},"y":${trace.y},"type":"bar","name":"${trace.name}"}"""
            }
        } else {
            traces.joinToString(",") { trace ->
                """{"x":${trace.x},"y":${trace.y},"type":"scatter","mode":"lines","name":"${trace.name}"}"""
            }
        }
        val barMode = if (stackedBars) ""","barmode":"stack"""" else ""
        return buildString {
            append("```plot\n")
            append("""{"data":[$data],"layout":{"xaxis":{"title":"$xTitle"},"yaxis":{"title":"$yTitle"},"margin":{"l":50,"r":30,"t":20,"b":40}$barMode}}""")
            append("\n```\n\n")
        }
    }


    /**
     * Deserialises the raw JSON request payload into an [OptimizationConfig].
     *
     * @param data The JSON element from the execution request.
     * @return The parsed [OptimizationConfig].
     */
    private fun parseOptimizationConfig(data: JsonElement): OptimizationConfig {
        return json.decodeFromJsonElement(OptimizationConfig.serializer(), data)
    }

    /**
     * Validates the parsed configuration, throwing [IllegalArgumentException] on any violation.
     *
     * @param config The configuration to validate.
     */
    private fun validateConfig(config: OptimizationConfig) {
        require(config.problem.metamodelPath.isNotBlank()) { "metamodelPath cannot be empty" }
        require(config.problem.modelPath.isNotBlank()) { "modelPath cannot be empty" }
        require(config.goal.objectives.isNotEmpty()) { "At least one objective is required" }
        require(config.search.mutations.usingPaths.isNotEmpty() || config.search.mutations.generate.isNotEmpty()) {
            "At least one mutation source is required: specify usingPaths or generate in search.mutations"
        }
        require(!config.problem.metamodelPath.contains("..")) { "Path traversal not allowed" }
        require(!config.problem.modelPath.contains("..")) { "Path traversal not allowed" }
    }


    /**
     * Inserts a new execution record in the database with state [ExecutionState.SUBMITTED].
     *
     * @param executionId Unique identifier for this execution.
     * @param projectId Project that owns the execution.
     * @param filePath Path to the config file that triggered the execution.
     * @param data The raw JSON config data, stored for debugging.
     */
    private fun createExecutionRecord(
        executionId: UUID, projectId: UUID, filePath: String, data: JsonElement
    ) {
        val now = Instant.now()
        transaction {
            OptimizerExecutionsTable.insert {
                it[id] = executionId.toKotlinUuid()
                it[OptimizerExecutionsTable.projectId] = projectId.toKotlinUuid()
                it[configPath] = filePath
                it[state] = ExecutionState.SUBMITTED
                it[progress] = null
                it[createdAt] = now
                it[startedAt] = null
                it[completedAt] = null
                it[configData] = data.toString()
            }
        }
    }

    /**
     * Updates the execution state in the database and notifies the backend progress API.
     * Also sets [startedAt] or [completedAt] timestamps as appropriate.
     *
     * @param executionId The execution to update.
     * @param state New [ExecutionState] string.
     * @param progressText Human-readable progress message, or `null` to clear.
     * @param jwtToken Authentication token for the backend API call.
     */
    private suspend fun updateState(
        executionId: UUID, state: String, progressText: String?, jwtToken: String
    ) {
        val now = Instant.now()
        val terminalStates = listOf(ExecutionState.COMPLETED, ExecutionState.FAILED, ExecutionState.CANCELLED)
        val updated = transaction {
            OptimizerExecutionsTable.update({
                (OptimizerExecutionsTable.id eq executionId.toKotlinUuid()) and
                    (OptimizerExecutionsTable.state notInList terminalStates)
            }) {
                it[OptimizerExecutionsTable.state] = state
                it[progress] = progressText
                when (state) {
                    ExecutionState.INITIALIZING, ExecutionState.RUNNING -> {
                        it[startedAt] = now
                    }
                    ExecutionState.COMPLETED, ExecutionState.FAILED, ExecutionState.CANCELLED -> {
                        it[completedAt] = now
                    }
                }
            }
        }
        if (updated > 0) {
            apiClient.updateExecutionState(executionId.toString(), state, progressText, jwtToken)
        }
    }

    /**
     * Updates only the progress text for a running execution, without changing the state.
     * Skips the update if the execution is already in a terminal state.
     *
     * @param executionId The execution to update.
     * @param progressText Human-readable progress message.
     * @param jwtToken Authentication token for the backend API call.
     */
    private suspend fun updateProgress(
        executionId: UUID, progressText: String, jwtToken: String
    ) {
        val terminalStates = listOf(ExecutionState.COMPLETED, ExecutionState.FAILED, ExecutionState.CANCELLED)
        val updated = transaction {
            OptimizerExecutionsTable.update({
                (OptimizerExecutionsTable.id eq executionId.toKotlinUuid()) and
                    (OptimizerExecutionsTable.state notInList terminalStates)
            }) {
                it[progress] = progressText
            }
        }
        if (updated > 0) {
            apiClient.updateExecutionState(executionId.toString(), ExecutionState.RUNNING, progressText, jwtToken)
        }
    }

    /**
     * Checks whether the execution was cancelled or deleted since the last generation.
     * Throws [kotlinx.coroutines.CancellationException] to abort the optimization loop when
     * the execution record is missing (deleted) or its state is [ExecutionState.CANCELLED].
     *
     * @param executionId The execution to inspect.
     */
    private suspend fun checkCancelled(executionId: UUID) {
        val state = withContext(Dispatchers.IO) {
            transaction {
                OptimizerExecutionsTable.selectAll()
                    .where { OptimizerExecutionsTable.id eq executionId.toKotlinUuid() }
                    .firstOrNull()
                    ?.get(OptimizerExecutionsTable.state)
            }
        }
        if (state == null || state == ExecutionState.CANCELLED) {
            logger.info("Optimizer execution $executionId was cancelled or deleted — stopping")
            throw kotlinx.coroutines.CancellationException("Optimization was cancelled or deleted")
        }
    }

    /**
     * Persists the exception message and sets the execution state to FAILED.
     *
     * @param executionId The failed execution.
     * @param e The unexpected exception.
     * @param jwtToken Authentication token for the state update API call.
     */
    private suspend fun handleUnexpectedError(executionId: UUID, e: Throwable, jwtToken: String) {
        logger.error("Unexpected error during optimization", e)
        transaction {
            OptimizerExecutionsTable.update({
                OptimizerExecutionsTable.id eq executionId.toKotlinUuid()
            }) {
                it[error] = e.message
            }
        }
        updateState(
            executionId, ExecutionState.FAILED,
            "Unexpected error: ${e.message}", jwtToken
        )
    }

    /**
     * Looks up an execution record by its ID.
     *
     * @param executionId The execution to look up.
     * @return The database row, or `null` if not found.
     */
    private fun findExecution(executionId: UUID) = transaction {
        OptimizerExecutionsTable.selectAll()
            .where { OptimizerExecutionsTable.id eq executionId.toKotlinUuid() }
            .firstOrNull()
    }

    /**
     * Persists an error message for the given execution, using a first-write-wins strategy.
     *
     * The update is conditional: it only applies when the execution row is still in a
     * non-terminal state (i.e. not yet [ExecutionState.COMPLETED], [ExecutionState.FAILED],
     * or [ExecutionState.CANCELLED]).  This guarantees that the first error recorded
     * wins — subsequent failures (e.g. workers failing after the root cause was already
     * captured) cannot overwrite the original, more meaningful message.
     *
     * @param executionId The UUID of the execution to update.
     * @param message The error message to store, or `null` to clear it.
     */
    private fun storeError(executionId: UUID, message: String?) {
        val terminalStates = listOf(ExecutionState.COMPLETED, ExecutionState.FAILED, ExecutionState.CANCELLED)
        transaction {
            OptimizerExecutionsTable.update({
                (OptimizerExecutionsTable.id eq executionId.toKotlinUuid()) and
                    (OptimizerExecutionsTable.state notInList terminalStates)
            }) {
                it[error] = message
            }
        }
    }

    /**
     * Builds a markdown summary for the given execution record.
     * Returns stored summary markdown for completed executions, or a short failure/state string.
     *
     * @param execution The database row for the execution.
     * @return Markdown-formatted summary string.
     */
    private fun buildSummary(execution: org.jetbrains.exposed.v1.core.ResultRow): String {
        val state = execution[OptimizerExecutionsTable.state]
        val error = execution[OptimizerExecutionsTable.error]

        return when (state) {
            ExecutionState.COMPLETED -> {
                val summaryContent = transaction {
                    OptimizerResultFilesTable.selectAll()
                        .where {
                            (OptimizerResultFilesTable.executionId eq
                                execution[OptimizerExecutionsTable.id]) and
                                (OptimizerResultFilesTable.filePath eq SUMMARY_FILE)
                        }
                        .firstOrNull()
                        ?.get(OptimizerResultFilesTable.content)
                }
                summaryContent ?: "## Optimization Completed\n\nNo summary available."
            }
            ExecutionState.FAILED -> {
                buildString {
                    append("## Optimization Failed\n\n")
                    append(error ?: "Unknown error")
                }
            }
            else -> {
                "Execution is in state: $state"
            }
        }
    }
}
