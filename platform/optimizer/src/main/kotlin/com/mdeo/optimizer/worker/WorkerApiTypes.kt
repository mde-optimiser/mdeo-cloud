package com.mdeo.optimizer.worker

import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.metamodel.data.ModelData
import com.mdeo.optimizer.config.GoalConfig
import com.mdeo.optimizer.config.GraphBackendType
import com.mdeo.optimizer.config.SolverConfig
import kotlinx.serialization.Serializable

/**
 * Request sent by the orchestrator to allocate a worker for a new optimization run.
 *
 * Contains everything the worker needs to set up its local evaluation environment:
 * the metamodel, an initial model, pre-serialized transformation and script ASTs,
 * goal/solver configuration, and how many initial solutions to generate.
 *
 * @param executionId Unique identifier for the optimization execution.
 * @param metamodelData The metamodel that governs model structure.
 * @param initialModelData The seed model from which initial solutions derive.
 * @param transformationAstJsons Map of transformation path to its JSON-serialized
 *        [com.mdeo.modeltransformation.ast.TypedAst]. Workers deserialize these with
 *        the appropriate serializer modules on their side.
 * @param scriptAstJsons Map of script path to its JSON-serialized
 *        [com.mdeo.script.ast.TypedAst]. Workers deserialize these with the
 *        appropriate contextual serializer modules on their side.
 * @param goalConfig Objective and constraint definitions for fitness evaluation.
 * @param solverConfig Solver parameters (algorithm, population size, etc.).
 * @param initialSolutionCount Number of initial solutions the worker should generate
 *        and evaluate during allocation.
 * @param threadsPerNode Maximum threads this worker node should use, or `null` for no limit
 *        (falls back to the worker's own [com.mdeo.optimizerexecution.config.AppConfig.workerThreads]).
 * @param orchestratorWsUrl WebSocket URL for the worker's subprocess to connect back to the
 *        orchestrator. Mutually exclusive with [useLocalChannel].
 * @param useLocalChannel When `true`, the subprocess uses the existing stdin/stdout pipe to
 *        communicate with the orchestrator instead of opening a WebSocket connection.
 *        More efficient for the local node where subprocess and orchestrator share a host.
 *        Mutually exclusive with [orchestratorWsUrl].
 */
@Serializable
data class WorkerAllocationRequest(
    val executionId: String,
    val metamodelData: MetamodelData,
    val initialModelData: ModelData,
    val transformationAstJsons: Map<String, String>,
    val scriptAstJsons: Map<String, String>,
    val goalConfig: GoalConfig,
    val solverConfig: SolverConfig,
    val initialSolutionCount: Int,
    val threadsPerNode: Int,
    val orchestratorWsUrl: String? = null,
    val useLocalChannel: Boolean = false,
    val graphBackendType: GraphBackendType = GraphBackendType.MDEO
)

/**
 * Response returned by a worker after successful allocation.
 *
 * @param initialSolutions Evaluated initial solutions produced by the worker.
 * @param threadCount Number of threads the worker has allocated for this execution.
 */
@Serializable
data class WorkerAllocationResponse(
    val initialSolutions: List<InitialSolutionData>,
    val threadCount: Int
)

/**
 * Compact representation of a solution created during initialization.
 *
 * Fitness evaluation is performed separately via evaluation tasks dispatched
 * through the same batch mechanism as mutation tasks, providing consistent
 * timeout protection for all evaluation work.
 *
 * @param solutionId Unique identifier for this solution within the execution.
 */
@Serializable
data class InitialSolutionData(
    val solutionId: String
)

/**
 * A single mutation task within a [com.mdeo.optimizer.worker.NodeWorkBatchRequest].
 *
 * @param solutionId Identifier of the parent solution to mutate.
 */
@Serializable
data class BatchTask(
    val solutionId: String
)

/**
 * A single evaluation-only task within a [com.mdeo.optimizer.worker.NodeWorkBatchRequest].
 *
 * Unlike [BatchTask], this evaluates an existing solution without mutating it first.
 * Used for initial population fitness evaluation.
 *
 * @param solutionId Identifier of the solution to evaluate.
 */
@Serializable
data class BatchEvaluationTask(
    val solutionId: String
)

/**
 * Result of a single mutation-and-evaluation task.
 *
 * @param parentSolutionId Identifier of the solution that was mutated.
 * @param newSolutionId Identifier assigned to the newly created offspring solution.
 * @param objectives Objective values of the new solution.
 * @param constraints Constraint values of the new solution.
 * @param succeeded Whether the mutation and evaluation completed without error.
 * @param executedTransformations Number of transformation operators actually tried during
 *   mutation (successful + failed attempts, excluding pre-skipped ones). Zero for failed
 *   mutations or evaluation-only tasks.
 * @param skippedOperatorSlots Number of operator slots pre-skipped because they are known to
 *   deterministically fail on the parent's current model state.
 * @param errorMessage When non-null, indicates that a guidance function (objective or constraint)
 *   threw an exception. The orchestrator must treat this as a fatal evaluation failure.
 */
@Serializable
data class BatchResult(
    val parentSolutionId: String,
    val newSolutionId: String,
    val objectives: List<Double>,
    val constraints: List<Double>,
    val succeeded: Boolean,
    val executedTransformations: Int = 0,
    val skippedOperatorSlots: Int = 0,
    val errorMessage: String? = null
)

/**
 * Metadata describing a worker node's capabilities and resource availability.
 *
 * Returned by the `GET /api/worker/metadata` endpoint so that orchestrators can make
 * informed decisions about node selection and thread-budget allocation without relying
 * on local configuration estimates.
 *
 * @param threadCount Number of worker threads available on this node for evaluation.
 * @param supportedBackends Algorithm backend identifiers supported by this node
 *        (e.g. `["NSGAII", "SPEA2", "IBEA"]`).
 */
@Serializable
data class WorkerMetadata(
    val threadCount: Int,
    val supportedBackends: List<String>
)
