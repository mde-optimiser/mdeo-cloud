package com.mdeo.optimizerexecution.worker

import com.mdeo.execution.common.config.configureSerialization
import com.mdeo.expression.ast.TypedCallableBody
import com.mdeo.expression.ast.expressions.TypedDoubleLiteralExpression
import com.mdeo.expression.ast.expressions.TypedExpression
import com.mdeo.expression.ast.expressions.TypedIdentifierExpression
import com.mdeo.expression.ast.expressions.TypedMemberAccessExpression
import com.mdeo.expression.ast.expressions.TypedMemberCallExpression
import com.mdeo.expression.ast.expressions.TypedStringLiteralExpression
import com.mdeo.expression.ast.statements.TypedReturnStatement
import com.mdeo.expression.ast.statements.TypedStatement
import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.expression.ast.types.ReturnType
import com.mdeo.expression.ast.types.VoidType
import com.mdeo.metamodel.Metamodel
import com.mdeo.metamodel.data.AssociationData
import com.mdeo.metamodel.data.AssociationEndData
import com.mdeo.metamodel.data.ClassData
import com.mdeo.metamodel.data.EnumData
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.metamodel.data.ModelData
import com.mdeo.metamodel.data.ModelDataInstance
import com.mdeo.metamodel.data.ModelDataLink
import com.mdeo.metamodel.data.ModelDataPropertyValue
import com.mdeo.metamodel.data.MultiplicityData
import com.mdeo.metamodel.data.PropertyData
import com.mdeo.modeltransformation.ast.TypedTransformationParameter
import com.mdeo.modeltransformation.ast.TypedTransformationSignature
import com.mdeo.modeltransformation.ast.expressions.TypedExpressionSerializer as TransformationExpressionSerializer
import com.mdeo.modeltransformation.ast.patterns.TypedPattern
import com.mdeo.modeltransformation.ast.patterns.TypedPatternElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternElementSerializer
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstance
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstanceElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternPropertyAssignment
import com.mdeo.modeltransformation.ast.statements.TypedMatchStatement
import com.mdeo.modeltransformation.ast.statements.TypedTransformationStatement
import com.mdeo.modeltransformation.ast.statements.TypedTransformationStatementSerializer
import com.mdeo.optimizer.config.AlgorithmParameters
import com.mdeo.optimizer.config.ConfigLiteral
import com.mdeo.optimizer.config.CrossoverParameters
import com.mdeo.optimizer.config.GoalConfig
import com.mdeo.optimizer.config.MutationParameters
import com.mdeo.optimizer.config.MutationStepConfig
import com.mdeo.optimizer.config.ObjectiveConfig
import com.mdeo.optimizer.config.ObjectiveTendency
import com.mdeo.optimizer.config.OperatorRef
import com.mdeo.optimizer.config.SolverConfig
import com.mdeo.optimizer.config.VariationType
import com.mdeo.optimizer.moea.DelegatingCrossoverVariation
import com.mdeo.optimizer.moea.EvaluationCoordinator
import com.mdeo.optimizer.moea.getWorkerRef
import com.mdeo.optimizer.worker.WorkerAllocationRequest
import com.mdeo.optimizerexecution.routes.workerRoutes
import com.mdeo.optimizerexecution.service.OrchestratorRegistry
import com.mdeo.modeltransformation.ast.TypedAst as TransformationTypedAst
import com.mdeo.script.ast.TypedAst as ScriptTypedAst
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
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.moeaframework.core.Solution
import org.moeaframework.core.population.Population
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Crossover across real worker nodes (spec D.5, G.4): two [WorkerService]s, each with its own
 * server and subprocess, driven by a [FederatedMutationEvaluator] through an
 * [EvaluationCoordinator].
 *
 * The donor of each crossover child lives on the other node: it is pushed to the child's node
 * as a replica (by the donor's node, over the peer endpoint), used by the crossover operator
 * there, and dropped after the batch while its own node keeps it. The operator arguments travel
 * with the operator definitions of the allocation and reach the engine on the workers.
 */
class CrossoverFederationTest {

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

    private val scriptJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        serializersModule = SerializersModule {
            contextual(TypedExpression::class, ScriptExpressionSerializer)
            contextual(TypedStatement::class, TypedStatementSerializer)
        }
    }

    // ---- problem ----

    private val metamodelData = MetamodelData(
        path = MM,
        classes = listOf(
            ClassData(
                name = "Component", isAbstract = false, extends = emptyList(),
                properties = listOf(PropertyData(name = "name", primitiveType = "string", multiplicity = MultiplicityData.single()))
            ),
            ClassData(
                name = "Container", isAbstract = false, extends = emptyList(),
                properties = listOf(
                    PropertyData(name = "name", primitiveType = "string", multiplicity = MultiplicityData.single()),
                    PropertyData(name = "weight", primitiveType = "double", multiplicity = MultiplicityData.optional()),
                    PropertyData(name = "rank", primitiveType = "int", multiplicity = MultiplicityData.optional()),
                    PropertyData(name = "kind", enumType = "Kind", multiplicity = MultiplicityData.optional())
                )
            )
        ),
        enums = listOf(EnumData(name = "Kind", entries = listOf("SMALL", "LARGE"))),
        associations = listOf(
            AssociationData(
                source = AssociationEndData(className = "Component", name = "host", multiplicity = MultiplicityData.single()),
                operator = "-->",
                target = AssociationEndData(className = "Container", multiplicity = MultiplicityData.many())
            )
        )
    )

    private fun named(name: String, className: String) =
        ModelDataInstance(name, className, mapOf("name" to ModelDataPropertyValue.StringValue(name)))

    private val startModel = ModelData(
        metamodelPath = MM,
        instances = listOf(named("a", "Container"), named("c1", "Component"), named("c2", "Component")),
        links = listOf(ModelDataLink("c1", "host", "a", null), ModelDataLink("c2", "host", "a", null))
    )

    private val types: List<ReturnType> = listOf(
        VoidType(),
        ClassTypeRef("builtin", "string", false),
        ClassTypeRef("builtin", "double", false),
        ClassTypeRef("builtin", "int", false),
        ClassTypeRef("builtin", "model", false),
        ClassTypeRef("class$MM", "Component", false),
        ClassTypeRef("enum$MM", "Kind", false),
        ClassTypeRef("builtin", "Set", false, mapOf("T" to ClassTypeRef("builtin", "string", false)))
    )

    private fun create(vararg properties: TypedPatternPropertyAssignment) = TypedMatchStatement(
        pattern = TypedPattern(
            listOf(TypedPatternObjectInstanceElement(objectInstance = TypedPatternObjectInstance("create", "made", "Container", properties.toList())))
        )
    )

    private fun set(property: String, value: TypedExpression) = TypedPatternPropertyAssignment(property, "=", value)

    /** Mutation: creates a container `tagged` with `weight`, `kind` and `rank = labels.size()` from its inputs. */
    private val tag = TransformationTypedAst(
        types, MM,
        listOf(
            create(
                set("name", TypedStringLiteralExpression(evalType = 1, value = "tagged")),
                set("weight", TypedIdentifierExpression(evalType = 2, name = "weight", scope = 1)),
                set("kind", TypedIdentifierExpression(evalType = 6, name = "kind", scope = 1)),
                set("rank", TypedMemberCallExpression(
                    evalType = 3, expression = TypedIdentifierExpression(evalType = 7, name = "labels", scope = 1),
                    member = "size", isNullChaining = false, overload = "", arguments = emptyList()
                ))
            )
        ),
        TypedTransformationSignature(
            "tag.mt",
            listOf(
                TypedTransformationParameter("weight", "in", 2),
                TypedTransformationParameter("kind", "in", 6),
                TypedTransformationParameter("labels", "in", 7, collection = "Set")
            )
        )
    )

    /** Crossover (`in donor: model`): creates a container named after a donor component. */
    private val copyNames = TransformationTypedAst(
        types, MM,
        listOf(
            TypedMatchStatement(
                pattern = TypedPattern(
                    listOf(
                        TypedPatternObjectInstanceElement(objectInstance = TypedPatternObjectInstance(null, "dc", "Component", emptyList(), "donor")),
                        TypedPatternObjectInstanceElement(
                            objectInstance = TypedPatternObjectInstance(
                                "create", "made", "Container",
                                listOf(set("name", TypedMemberAccessExpression(
                                    evalType = 1, expression = TypedIdentifierExpression(evalType = 5, name = "dc", scope = 1),
                                    member = "name", isNullChaining = false
                                )))
                            )
                        )
                    )
                )
            )
        ),
        TypedTransformationSignature("copy-names.mt", listOf(TypedTransformationParameter("donor", "in", 4, isModel = true)))
    )

    private val tagArguments = mapOf(
        "weight" to ConfigLiteral.IntValue(3),
        "kind" to ConfigLiteral.EnumValue("Kind", "LARGE"),
        "labels" to ConfigLiteral.ListValue(listOf("p", "q", "p").map { ConfigLiteral.StringValue(it) })
    )

    private fun constantObjective(): ScriptTypedAst = ScriptTypedAst(
        types = listOf(ClassTypeRef("builtin", "double", false)),
        metamodelPath = null,
        imports = emptyList<TypedImport>(),
        functions = listOf(
            TypedFunction(
                name = "objective",
                parameters = emptyList(),
                returnType = 0,
                body = TypedCallableBody(listOf(TypedReturnStatement(value = TypedDoubleLiteralExpression(evalType = 0, value = "1.0"))))
            )
        )
    )

    private fun allocationRequest(executionId: String) = WorkerAllocationRequest(
        executionId = executionId,
        metamodelData = metamodelData,
        initialModelData = startModel,
        transformationAstJsons = mapOf(
            TAG to transformationJson.encodeToString(TransformationTypedAst.serializer(), tag),
            COPY_NAMES to transformationJson.encodeToString(TransformationTypedAst.serializer(), copyNames)
        ),
        scriptAstJsons = mapOf(OBJECTIVE to scriptJson.encodeToString(ScriptTypedAst.serializer(), constantObjective())),
        goalConfig = GoalConfig(listOf(ObjectiveConfig(ObjectiveTendency.MINIMIZE, OBJECTIVE, "objective")), emptyList()),
        solverConfig = SolverConfig(
            parameters = AlgorithmParameters(
                population = 2,
                variation = VariationType.GENETIC,
                mutation = MutationParameters(step = MutationStepConfig.Fixed(1)),
                crossover = CrossoverParameters(rate = 1.0, parents = 2)
            )
        ),
        initialSolutionCount = 1,
        threadsPerNode = 2,
        mutationOperators = listOf(OperatorRef(TAG, tagArguments)),
        crossoverOperators = listOf(OperatorRef(COPY_NAMES))
    )

    @Test
    @OptIn(ExperimentalSerializationApi::class)
    fun `operator arguments survive the allocation and setup encodings`() {
        val request = allocationRequest("encoding")
        val json = Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString(WorkerAllocationRequest.serializer(), json.encodeToString(WorkerAllocationRequest.serializer(), request))
        assertEquals(request.mutationOperators, decoded.mutationOperators, "the HTTP allocation of a remote node")
        assertEquals(request.crossoverOperators, decoded.crossoverOperators)

        val cbor = Cbor { ignoreUnknownKeys = true }
        val setup = WorkerSubprocessRequest.Setup(
            metamodelData = request.metamodelData,
            initialModelData = request.initialModelData,
            transformationAstJsons = request.transformationAstJsons,
            scriptAstJsons = request.scriptAstJsons,
            goalConfig = request.goalConfig,
            solverConfig = request.solverConfig,
            initialSolutionCount = 1,
            mutationOperators = request.mutationOperators,
            crossoverOperators = request.crossoverOperators
        )
        val decodedSetup = cbor.decodeFromByteArray<WorkerSubprocessRequest>(cbor.encodeToByteArray<WorkerSubprocessRequest>(setup))
            as WorkerSubprocessRequest.Setup
        assertEquals(request.mutationOperators, decodedSetup.mutationOperators, "the subprocess setup")
        assertEquals(request.solverConfig, decodedSetup.solverConfig)
    }

    @Test
    @Timeout(180)
    fun `a donor on another node is replicated, used and dropped, and arguments reach the workers`() = runBlocking {
        val executionId = "crossover-federation"
        val nodes = (0..1).map { startNode() }
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val clients = nodes.mapIndexed { i, node ->
            LocalWorkerClient(nodeId = i.toString(), baseUrl = "http://localhost:${node.port}", workerService = node.service, scope = scope)
        }
        val evaluator = FederatedMutationEvaluator(executionId, clients, allocationRequest(executionId), mapOf("0" to 2, "1" to 2))
        try {
            val coordinator = EvaluationCoordinator(evaluator)
            val parents = List(2) { Solution(0, 1, 0) }
            coordinator.batchEvaluateAndUpdate(parents)
            val (p0, p1) = parents.map { it.getWorkerRef()!! }
            assertEquals(listOf("0", "1"), listOf(p0.nodeId, p1.nodeId), "one parent per node")

            coordinator.prepareIteration(Population(parents))
            val children = DelegatingCrossoverVariation(2, rate = 1.0).evolve(parents.toTypedArray())
            coordinator.batchEvaluateAndUpdate(children.toList())

            val (c0, c1) = children.map { requireNotNull(it.getWorkerRef()) { "every child was made" } }
            assertEquals(listOf("0", "1"), listOf(c0.nodeId, c1.nodeId), "a child lives on its primary's node")
            val crossovers = coordinator.getLastCrossoverInfo()
            assertEquals(2, crossovers.attempted)
            assertEquals(2, crossovers.succeeded, "both crossovers read their donor on the child's node")
            assertEquals(mapOf(0 to 2), crossovers.perOperator)
            assertEquals(2, coordinator.getLastReplicatedCount())

            val metamodel = Metamodel.compile(metamodelData)
            for (child in listOf(c0, c1)) {
                val data = evaluator.getSolutionData(child).toModelData(metamodel)
                val containers = data.instances.filter { it.className == "Container" }
                val names = containers.map { (it.properties["name"] as ModelDataPropertyValue.StringValue).value }
                assertTrue(names.any { it == "c1" || it == "c2" }, "the crossover copied a donor component name: $names")
                val tagged = containers.filter { (it.properties["name"] as ModelDataPropertyValue.StringValue).value == "tagged" }
                assertEquals(2, tagged.size, "the parent's initial mutation and the child's mutation: $names")
                for (made in tagged) {
                    assertEquals(3.0, (made.properties["weight"] as ModelDataPropertyValue.NumberValue).value, "int 3 as double")
                    assertEquals("LARGE", (made.properties["kind"] as ModelDataPropertyValue.EnumValue).enumEntry)
                    assertEquals(2, (made.properties["rank"] as ModelDataPropertyValue.NumberValue).value.toInt(), "{p, q}")
                }
            }

            assertFalse(p1.solutionId in clients[0].getSolutionDataBatch(executionId, listOf(p1.solutionId)), "node 0 dropped its replica")
            assertFalse(p0.solutionId in clients[1].getSolutionDataBatch(executionId, listOf(p0.solutionId)), "node 1 dropped its replica")
            assertTrue(p0.solutionId in clients[0].getSolutionDataBatch(executionId, listOf(p0.solutionId)), "node 0 kept its parent")
            assertTrue(p1.solutionId in clients[1].getSolutionDataBatch(executionId, listOf(p1.solutionId)), "node 1 kept its parent")
            assertEquals(setOf(p0, p1, c0, c1), coordinator.getAllKnownRefs(), "replicas are never tracked")
        } finally {
            runCatching { evaluator.cleanup() }
            scope.cancel()
            nodes.forEach { it.stop() }
        }
    }

    /** A worker node: a [WorkerService] behind its own server (for the peer endpoint replicas are pushed to). */
    private class Node(val port: Int, val service: WorkerService, val server: EmbeddedServer<*, *>) {
        fun stop() {
            runCatching { runBlocking { service.close() } }
            server.stop(gracePeriodMillis = 0, timeoutMillis = 0)
        }
    }

    private fun startNode(): Node {
        val port = java.net.ServerSocket(0).use { it.localPort }
        val service = WorkerService(workerThreads = 2, scriptTimeoutMs = 30_000L, transformationTimeoutMs = 30_000L, serverPort = port)
        val server = embeddedServer(Netty, port = port) {
            install(WebSockets)
            configureSerialization()
            routing { workerRoutes(service, OrchestratorRegistry()) }
        }
        server.start()
        return Node(port, service, server)
    }

    private companion object {
        const val MM = "/mm/arch.mm"
        const val TAG = "/ops/tag.mt"
        const val COPY_NAMES = "/ops/copy-names.mt"
        const val OBJECTIVE = "/goal.fn"
    }
}
