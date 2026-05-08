package com.mdeo.optimizer

import com.mdeo.expression.ast.TypedCallableBody
import com.mdeo.expression.ast.expressions.TypedBinaryExpression
import com.mdeo.expression.ast.expressions.TypedIdentifierExpression
import com.mdeo.expression.ast.expressions.TypedIntLiteralExpression
import com.mdeo.expression.ast.expressions.TypedMemberCallExpression
import com.mdeo.metamodel.SerializedModel
import com.mdeo.metamodel.data.*
import com.mdeo.modeltransformation.ast.TypedAst as TransformationTypedAst
import com.mdeo.modeltransformation.ast.TransformationOperator
import com.mdeo.modeltransformation.ast.patterns.TypedPattern
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLink
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLinkElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLinkEnd
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstance
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstanceElement
import com.mdeo.modeltransformation.ast.statements.TypedMatchStatement
import com.mdeo.modeltransformation.graph.mdeo.MdeoModelGraph
import com.mdeo.expression.ast.statements.TypedIfStatement
import com.mdeo.expression.ast.statements.TypedReturnStatement
import com.mdeo.expression.ast.statements.TypedVariableDeclarationStatement
import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.expression.ast.types.ReturnType
import com.mdeo.expression.ast.types.VoidType
import com.mdeo.metamodel.data.ModelData
import com.mdeo.optimizer.config.*
import com.mdeo.optimizer.evaluation.EvaluationResult
import com.mdeo.optimizer.evaluation.InitialSolutionResult
import com.mdeo.optimizer.evaluation.LocalMutationEvaluator
import com.mdeo.optimizer.evaluation.MutationEvaluator
import com.mdeo.optimizer.evaluation.NodeBatch
import com.mdeo.optimizer.evaluation.WorkerSolutionRef
import com.mdeo.optimizer.guidance.ScriptGuidanceFunction
import com.mdeo.optimizer.moea.DelegatingAlgorithmProvider
import com.mdeo.optimizer.moea.DelegatingProblem
import com.mdeo.optimizer.moea.EvaluationCoordinator
import com.mdeo.optimizer.moea.TerminationConditionAdapter
import com.mdeo.optimizer.operators.MutationStrategyFactory
import com.mdeo.optimizer.solution.Solution
import com.mdeo.script.ast.TypedAst as ScriptTypedAst
import com.mdeo.script.ast.TypedFunction
import com.mdeo.script.compiler.CompilationInput
import com.mdeo.script.compiler.ScriptCompiler
import com.mdeo.script.runtime.ExecutionEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * End-to-end test for the distributed (federated) optimization path.
 *
 * Uses the same House–Room problem as [OptimizationCorrectnessTest] but exercises
 * the [EvaluationCoordinator] + [DelegatingAlgorithmProvider] path (used in production
 * by the federated execution service) instead of the local [OptimizationOrchestrator]
 * path.
 *
 * Three in-process [LocalMutationEvaluator] instances stand in for remote worker nodes,
 * wired together by [MultiNodeMutationEvaluator] which routes tasks by nodeId —
 * mirroring what [FederatedMutationEvaluator] does across real WebSocket connections.
 */
class DistributedOptimizationTest {

    // ────────────────────────────────────────────────────────────────────────────
    // MultiNodeMutationEvaluator — in-process stand-in for FederatedMutationEvaluator
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Routes [MutationEvaluator] calls to the appropriate [LocalMutationEvaluator]
     * based on the [WorkerSolutionRef.nodeId] embedded in each task or reference.
     *
     * Initialization load is distributed evenly across all nodes, with the first
     * `count % nodes.size` nodes receiving one extra solution (matching the
     * FederatedMutationEvaluator.distributeCount strategy).
     */
    private class MultiNodeMutationEvaluator(
        private val nodes: List<LocalMutationEvaluator>,
        private val nodeIds: List<String>
    ) : MutationEvaluator {

        private val evaluatorByNodeId: Map<String, LocalMutationEvaluator> =
            nodeIds.zip(nodes).toMap()

        /**
         * Distributes [total] solutions across [nodeIds.size] nodes, round-robin style. 
         */
        private fun distributeCount(total: Int): List<Int> {
            val nodeCount = nodes.size
            val base = total / nodeCount
            val remainder = total % nodeCount
            return (0 until nodeCount).map { i -> if (i < remainder) base + 1 else base }
        }

        override suspend fun initialize(count: Int): List<InitialSolutionResult> {
            val counts = distributeCount(count)
            return counts.zip(nodes).flatMap { (nodeCount, evaluator) ->
                if (nodeCount > 0) evaluator.initialize(nodeCount) else emptyList()
            }
        }

        /**
         * Delegates each [NodeBatch] to the matching [LocalMutationEvaluator].
         */
        override suspend fun executeNodeBatches(batches: List<NodeBatch>): List<EvaluationResult> {
            return batches.flatMap { batch ->
                val evaluator = evaluatorByNodeId[batch.nodeId]
                    ?: error("No evaluator for nodeId: ${batch.nodeId}")
                evaluator.executeNodeBatches(listOf(batch))
            }
        }

        /**
         * Transfers solutions between in-process evaluators, mirroring the orchestrator push.
         */
        override suspend fun pushRebalanceSolutions(
            transfers: List<com.mdeo.optimizer.moea.RebalanceTransfer>
        ): Map<String, List<String>> {
            val discardsBySource = mutableMapOf<String, MutableList<String>>()
            for (transfer in transfers) {
                val srcEvaluator = evaluatorByNodeId[transfer.sourceNodeId]
                    ?: error("No evaluator for sourceNodeId: ${transfer.sourceNodeId}")
                val destEvaluator = evaluatorByNodeId[transfer.destinationNodeId]
                    ?: error("No evaluator for destinationNodeId: ${transfer.destinationNodeId}")
                for (solutionId in transfer.solutionIds) {
                    val ref = WorkerSolutionRef(nodeId = transfer.sourceNodeId, solutionId = solutionId)
                    val serializedModel = srcEvaluator.getSolutionData(ref)
                    destEvaluator.receiveSolution(solutionId, serializedModel)
                }
                discardsBySource.getOrPut(transfer.sourceNodeId) { mutableListOf() }.addAll(transfer.solutionIds)
            }
            return discardsBySource
        }

        override suspend fun getSolutionData(ref: WorkerSolutionRef): SerializedModel {
            val evaluator = evaluatorByNodeId[ref.nodeId]
                ?: error("No evaluator for nodeId: ${ref.nodeId}")
            return evaluator.getSolutionData(ref)
        }

        override suspend fun cleanup() {
            for (evaluator in nodes) {
                evaluator.cleanup()
            }
        }

        override fun getNodeIds(): Set<String> = nodeIds.toSet()
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Problem construction helpers (identical to OptimizationCorrectnessTest)
    // ────────────────────────────────────────────────────────────────────────────

    private fun buildMetamodelData(): MetamodelData = MetamodelData(
        path = "/metamodel.mm",
        classes = listOf(
            ClassData(name = "House", isAbstract = false),
            ClassData(name = "Room", isAbstract = false)
        ),
        enums = emptyList(),
        associations = listOf(
            AssociationData(
                source = AssociationEndData(
                    className = "House",
                    multiplicity = MultiplicityData(lower = 0, upper = -1),
                    name = "rooms"
                ),
                operator = "<-->",
                target = AssociationEndData(
                    className = "Room",
                    multiplicity = MultiplicityData(lower = 1, upper = 1),
                    name = "house"
                )
            )
        ),
        importedMetamodelPaths = emptyList()
    )

    private fun buildModelData(): ModelData = ModelData(
        metamodelPath = "../metamodel.mm",
        instances = listOf(ModelDataInstance(name = "house", className = "House", properties = emptyMap())),
        links = emptyList()
    )

    private fun buildTypes(): List<ReturnType> = listOf(
        VoidType(),
        ClassTypeRef(`package` = "builtin", type = "string", isNullable = false, typeArgs = emptyMap()),
        ClassTypeRef(`package` = "builtin", type = "double", isNullable = false, typeArgs = emptyMap()),
        ClassTypeRef(`package` = "builtin", type = "boolean", isNullable = false, typeArgs = emptyMap()),
        ClassTypeRef(`package` = "builtin", type = "Any", isNullable = true)
    )

    private fun buildScriptAst(): ScriptTypedAst {
        val types: List<ReturnType> = listOf(
            VoidType(),
            ClassTypeRef(`package` = "builtin", type = "string", isNullable = false, typeArgs = emptyMap()),
            ClassTypeRef(`package` = "builtin", type = "double", isNullable = false, typeArgs = emptyMap()),
            ClassTypeRef(`package` = "builtin", type = "boolean", isNullable = false, typeArgs = emptyMap()),
            ClassTypeRef(`package` = "builtin", type = "Any", isNullable = true),
            ClassTypeRef(`package` = "builtin", type = "int", isNullable = false, typeArgs = emptyMap()),
            ClassTypeRef(
                `package` = "builtin", type = "Collection", isNullable = false,
                typeArgs = mapOf(
                    "T" to ClassTypeRef(`package` = "class/metamodel.mm", type = "Room", isNullable = false, typeArgs = emptyMap())
                )
            ),
            ClassTypeRef(`package` = "class-container/metamodel.mm", type = "Room", isNullable = false, typeArgs = emptyMap())
        )

        val roomCountIdent = TypedIdentifierExpression(evalType = 5, name = "roomCount", scope = 3)
        val ten = TypedIntLiteralExpression(evalType = 5, value = "10")

        val roomCountDecl = TypedVariableDeclarationStatement(
            name = "roomCount",
            type = 5,
            initialValue = TypedMemberCallExpression(
                evalType = 5,
                expression = TypedMemberCallExpression(
                    evalType = 6,
                    expression = TypedIdentifierExpression(evalType = 7, name = "Room", scope = 1),
                    member = "all",
                    isNullChaining = false,
                    overload = "",
                    arguments = emptyList()
                ),
                member = "size",
                isNullChaining = false,
                overload = "",
                arguments = emptyList()
            )
        )

        val ifStatement = TypedIfStatement(
            condition = TypedBinaryExpression(evalType = 3, operator = ">", left = roomCountIdent, right = ten),
            thenBlock = listOf(
                TypedReturnStatement(
                    value = TypedBinaryExpression(evalType = 5, operator = "-", left = roomCountIdent, right = ten)
                )
            ),
            elseIfs = emptyList(),
            elseBlock = listOf(
                TypedReturnStatement(
                    value = TypedBinaryExpression(evalType = 5, operator = "-", left = ten, right = roomCountIdent)
                )
            )
        )

        return ScriptTypedAst(
            types = types,
            metamodelPath = "/metamodel.mm",
            imports = emptyList(),
            functions = listOf(
                TypedFunction(
                    name = "has10Rooms",
                    parameters = emptyList(),
                    returnType = 5,
                    body = TypedCallableBody(body = listOf(roomCountDecl, ifStatement))
                )
            )
        )
    }

    private fun buildTransformations(): Map<String, TransformationTypedAst> {
        val types = buildTypes()

        val addRoomAst = TransformationTypedAst(
            types = types,
            metamodelPath = "/metamodel.mm",
            statements = listOf(
                TypedMatchStatement(
                    pattern = TypedPattern(
                        elements = listOf(
                            TypedPatternObjectInstanceElement(
                                objectInstance = TypedPatternObjectInstance(
                                    modifier = null, name = "house", className = "House", properties = emptyList()
                                )
                            ),
                            TypedPatternObjectInstanceElement(
                                objectInstance = TypedPatternObjectInstance(
                                    modifier = "create", name = "room", className = "Room", properties = emptyList()
                                )
                            ),
                            TypedPatternLinkElement(
                                link = TypedPatternLink(
                                    modifier = "create",
                                    source = TypedPatternLinkEnd(objectName = "house", propertyName = "rooms"),
                                    target = TypedPatternLinkEnd(objectName = "room", propertyName = "house")
                                )
                            )
                        )
                    )
                )
            )
        )

        val deleteRoomAst = TransformationTypedAst(
            types = types,
            metamodelPath = "/metamodel.mm",
            statements = listOf(
                TypedMatchStatement(
                    pattern = TypedPattern(
                        elements = listOf(
                            TypedPatternObjectInstanceElement(
                                objectInstance = TypedPatternObjectInstance(
                                    modifier = null, name = "house", className = "House", properties = emptyList()
                                )
                            ),
                            TypedPatternObjectInstanceElement(
                                objectInstance = TypedPatternObjectInstance(
                                    modifier = "delete", name = "room", className = "Room", properties = emptyList()
                                )
                            ),
                            TypedPatternLinkElement(
                                link = TypedPatternLink(
                                    modifier = "delete",
                                    source = TypedPatternLinkEnd(objectName = "house", propertyName = "rooms"),
                                    target = TypedPatternLinkEnd(objectName = "room", propertyName = "house")
                                )
                            )
                        )
                    )
                )
            )
        )

        return mapOf(
            "/transformation/addRoom.mt" to addRoomAst,
            "/transformation/deleteRoom.mt" to deleteRoomAst
        )
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Test
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Runs the MOEA-native distributed path (EvaluationCoordinator + DelegatingAlgorithmProvider)
     * with three simulated worker nodes and verifies that the optimizer converges to a solution
     * with approximately 10 rooms (objective ≤ 2).
     */
    @Test
    fun `distributed optimizer with three nodes converges to approximately 10 rooms`() {
        val metamodelData = buildMetamodelData()
        val modelData = buildModelData()
        val transformations = buildTransformations()
        val scriptAst = buildScriptAst()

        val compiledProgram = ScriptCompiler().compile(
            CompilationInput(mapOf("/script/constraintsAndObjective.fn" to scriptAst)),
            metamodelData
        )
        val environment = ExecutionEnvironment(compiledProgram)
        val metamodel = compiledProgram.metamodel ?: error("No metamodel in compiled program")

        val objective = ScriptGuidanceFunction(
            environment.scriptProgramClass,
            compiledProgram.functionLookup["/script/constraintsAndObjective.fn"]!!["has10Rooms"]!!,
            System.out,
            "/script/constraintsAndObjective.fn::has10Rooms"
        )

        val solverConfig = SolverConfig(
            provider = SolverProvider.MOEA,
            algorithm = AlgorithmType.NSGAII,
            parameters = AlgorithmParameters(
                population = 50,
                variation = VariationType.MUTATION,
                mutation = MutationParameters(
                    step = MutationStepConfig.Fixed(n = 1),
                    strategy = MutationStrategy.RANDOM
                )
            ),
            termination = TerminationConfig(evolutions = 50),
            batches = 1
        )

        val mutationParams = solverConfig.parameters.mutation
        val mutationStrategy = MutationStrategyFactory.create(
            mutationParams,
            transformations.keys.sorted().mapIndexed { idx, path ->
                TransformationOperator(id = idx, ast = transformations[path]!!)
            }
        )

        val nodeIds = listOf("node-1", "node-2", "node-3")
        val nodeEvaluators = nodeIds.map { nodeId ->
            LocalMutationEvaluator(
                initialSolutionProvider = { Solution(MdeoModelGraph.create(modelData, metamodel)) },
                mutationStrategy = mutationStrategy,
                objectives = listOf(objective),
                constraints = emptyList(),
                metamodel = metamodel,
                nodeId = nodeId
            )
        }

        val multiNodeEvaluator = MultiNodeMutationEvaluator(
            nodes = nodeEvaluators,
            nodeIds = nodeIds
        )

        val coordinator = EvaluationCoordinator(multiNodeEvaluator)
        val problem = DelegatingProblem(
            numberOfObjectives = 1,
            numberOfConstraints = 0
        )
        val provider = DelegatingAlgorithmProvider(coordinator)
        val algorithm = provider.getAlgorithm(
            solverConfig.algorithm.name,
            provider.buildProperties(solverConfig),
            problem
        )
        val terminationCondition = TerminationConditionAdapter(solverConfig).create()

        try {
            runBlocking {
                withContext(Dispatchers.IO) {
                    algorithm.run(terminationCondition)
                }
            }

            val result = algorithm.result
            assertFalse(result.isEmpty, "Distributed optimizer must produce at least one solution")

            val bestObjective = result.minOf { it.getObjectiveValue(0) }
            assertTrue(
                bestObjective <= 2.0,
                "Best objective should be ≤ 2.0 (within 2 rooms of target 10), but got $bestObjective"
            )
        } finally {
            runBlocking { multiNodeEvaluator.cleanup() }
        }
    }
}
