package com.mdeo.optimizer

import com.mdeo.expression.ast.TypedCallableBody
import com.mdeo.expression.ast.expressions.TypedBinaryExpression
import com.mdeo.expression.ast.expressions.TypedIdentifierExpression
import com.mdeo.expression.ast.expressions.TypedIntLiteralExpression
import com.mdeo.expression.ast.expressions.TypedMemberCallExpression
import com.mdeo.metamodel.Metamodel
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
import com.mdeo.modeltransformation.graph.ModelGraph
import com.mdeo.modeltransformation.graph.tinker.TinkerModelGraph
import com.mdeo.expression.ast.statements.TypedIfStatement
import com.mdeo.expression.ast.statements.TypedReturnStatement
import com.mdeo.expression.ast.statements.TypedVariableDeclarationStatement
import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.expression.ast.types.ReturnType
import com.mdeo.expression.ast.types.VoidType
import com.mdeo.optimizer.config.*
import com.mdeo.optimizer.evaluation.LocalMutationEvaluator
import com.mdeo.optimizer.guidance.ScriptGuidanceFunction
import com.mdeo.optimizer.moea.getWorkerRef
import com.mdeo.optimizer.operators.MutationStrategyFactory
import com.mdeo.optimizer.operators.TransformationAttemptRunner
import com.mdeo.optimizer.solution.Solution
import com.mdeo.script.ast.TypedAst as ScriptTypedAst
import com.mdeo.script.ast.TypedFunction
import com.mdeo.script.compiler.CompilationInput
import com.mdeo.script.compiler.ScriptCompiler
import com.mdeo.script.runtime.ExecutionEnvironment
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * Correctness tests for the full optimization pipeline.
 *
 * Uses a scaled-down version of the House–Room optimization problem to verify
 * that the optimizer actually finds a good solution, not just that it runs.
 *
 * Problem setup:
 * - Metamodel: House with bidirectional `rooms` <--> `house` association to Room instances.
 * - Initial model: a single House with **0 rooms**.
 * - Mutations: addRoom (creates a Room linked to House) and deleteRoom (removes a Room).
 * - Objective: minimize |roomCount − 10| (the `has10Rooms` function).
 * - Expected result: the final Pareto front contains at least one solution with exactly 10 rooms
 *   (objective value = 0), or at most a tolerance of 2 rooms off target.
 *
 * Parameters are kept small (population=50, evolutions=50, ~2 500 evaluations) so the
 * test completes in a few seconds while still being meaningful.
 *
 * Each test is parameterized over two graph backends ([GraphBackend.TINKER] and [GraphBackend.MDEO])
 * to verify correctness regardless of the underlying graph implementation.
 */
class OptimizationCorrectnessTest {

    enum class GraphBackend {
        TINKER, MDEO;

        fun createModelGraph(modelData: ModelData, metamodel: Metamodel): ModelGraph = when (this) {
            TINKER -> TinkerModelGraph.create(modelData, metamodel)
            MDEO -> MdeoModelGraph.create(modelData, metamodel)
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Metamodel, model, transformations — identical to OptimizationPerformanceTest
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
                    multiplicity = MultiplicityData(lower = 0, upper = -1), // 0..* — a house has many rooms
                    name = "rooms"
                ),
                operator = "<-->",
                target = AssociationEndData(
                    className = "Room",
                    multiplicity = MultiplicityData(lower = 1, upper = 1),  // 1..1 — a room belongs to one house
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
    // Helper: count rooms in a solution's model graph
    // ────────────────────────────────────────────────────────────────────────────

    private fun roomCount(solution: Solution, metamodelPath: String, metamodel: Metamodel): Int {
        val model = solution.modelGraph.toModelData()
        return model.instances.count { it.className == "Room" }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Correctness tests
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Verifies that after applying addRoom N times, every Room is connected to the
     * House via a graph edge in BOTH directions, and that [Solution.deepCopy] preserves
     * all N edges.
     *
     * This guards against two failure modes:
     * - A single-valued House.rooms field overwriting the previous room on each add
     *   (wrong multiplicity → isMultiple=false → Set degenerates to a scalar).
     * - deepCopy losing rooms because it reads instance link-fields rather than graph edges.
     */
    @ParameterizedTest(name = "rooms are properly linked to house after addRoom [{0}]")
    @EnumSource(GraphBackend::class)
    fun `rooms are properly linked to house after addRoom`(backend: GraphBackend) {
        val metamodelData = buildMetamodelData()
        val modelData = buildModelData()
        val transformations = buildTransformations()

        val compiledProgram = ScriptCompiler().compile(
            CompilationInput(mapOf("/script/constraintsAndObjective.fn" to buildScriptAst())),
            metamodelData
        )
        val metamodel = compiledProgram.metamodel ?: error("No metamodel in compiled program")

        val solution = Solution(backend.createModelGraph(modelData, metamodel))
        val runner = TransformationAttemptRunner()
        val operatorByPath = transformations.keys.sorted().mapIndexed { idx, path ->
            path to TransformationOperator(id = idx, ast = transformations[path]!!)
        }.toMap()

        val n = 5
        repeat(n) { i ->
            assertTrue(
                runner.tryApply(solution, operatorByPath["/transformation/addRoom.mt"]!!).isApplied,
                "addRoom must succeed on iteration ${i + 1}"
            )
        }

        fun assertStructure(label: String, data: ModelData) {
            val rooms = data.instances.filter { it.className == "Room" }
            assertEquals(n, rooms.size, "[$label] expected $n Room instances")

            // Every edge must carry both ends: sourceProperty="rooms" on House side
            // and targetProperty="house" on Room side (both encoded in the edge label).
            val houseRoomEdges = data.links.filter { it.sourceProperty == "rooms" }
            assertEquals(
                n, houseRoomEdges.size,
                "[$label] expected $n house→room edges (sourceProperty=\"rooms\"), " +
                    "got ${houseRoomEdges.size}. Links: ${data.links}"
            )

            val roomHouseEdges = data.links.filter { it.targetProperty == "house" }
            assertEquals(
                n, roomHouseEdges.size,
                "[$label] expected $n room→house back-references (targetProperty=\"house\"), " +
                    "got ${roomHouseEdges.size}. Links: ${data.links}"
            )
        }

        assertStructure("live graph", solution.modelGraph.toModelData())

        val copy = solution.deepCopy()
        assertStructure("after deepCopy", copy.modelGraph.toModelData())
    }

    /**
     * Runs the optimizer with a small budget and verifies that at least one solution
     * in the final Pareto front has an objective value of 0, meaning it found a model
     * with exactly 10 rooms. Parameterized over both graph backends.
     */
    @ParameterizedTest(name = "optimizer converges to approximately 10 rooms [{0}]")
    @EnumSource(GraphBackend::class)
    fun `optimizer converges to approximately 10 rooms`(backend: GraphBackend) {
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

        val initialSolutionProvider: () -> Solution = {
            Solution(backend.createModelGraph(modelData, metamodel))
        }

        val config = OptimizationConfig(
            problem = ProblemConfig(
                metamodelPath = "/metamodel.mm",
                modelPath = "/model/model.m"
            ),
            goal = GoalConfig(
                objectives = listOf(
                    ObjectiveConfig(
                        type = ObjectiveTendency.MINIMIZE,
                        path = "/script/constraintsAndObjective.fn",
                        functionName = "has10Rooms"
                    )
                ),
                constraints = emptyList()
            ),
            search = SearchConfig(
                mutations = MutationsConfig(
                    usingPaths = listOf(
                        "/transformation/addRoom.mt",
                        "/transformation/deleteRoom.mt"
                    )
                )
            ),
            solver = SolverConfig(
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
        )

        val mutationStrategy = MutationStrategyFactory.create(
            config.solver.parameters.mutation,
            transformations.keys.sorted().mapIndexed { idx, path ->
                TransformationOperator(id = idx, ast = transformations[path]!!)
            }
        )
        val evaluator = LocalMutationEvaluator(
            initialSolutionProvider = initialSolutionProvider,
            mutationStrategy = mutationStrategy,
            objectives = listOf(objective),
            constraints = emptyList(),
            metamodel = metamodel
        )

        val orchestrator = OptimizationOrchestrator(
            config = config,
            evaluator = evaluator
        )

        val result = runBlocking { orchestrator.run().single() }

        val finalSolutions = result.getFinalSolutions()
        assertFalse(finalSolutions.isEmpty(), "Optimizer must produce at least one solution")

        val bestObjective = finalSolutions.minOf { it.objectives.first() }
        assertTrue(
            bestObjective == 0.0,
            "Best objective value should be 0.0 (exactly 10 rooms), but got $bestObjective"
        )

        runBlocking { evaluator.cleanup() }
    }

    /**
     * Verifies that all solutions in the final population have valid room counts,
     * i.e., every room count is a non-negative integer and the objective value
     * matches |roomCount - 10|. Parameterized over both graph backends.
     */
    @ParameterizedTest(name = "all final solutions have correct objective values [{0}]")
    @EnumSource(GraphBackend::class)
    fun `all final solutions have correct objective values`(backend: GraphBackend) {
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

        val initialSolutionProvider: () -> Solution = {
            Solution(backend.createModelGraph(modelData, metamodel))
        }

        val config = OptimizationConfig(
            problem = ProblemConfig(metamodelPath = "/metamodel.mm", modelPath = "/model/model.m"),
            goal = GoalConfig(
                objectives = listOf(
                    ObjectiveConfig(
                        type = ObjectiveTendency.MINIMIZE,
                        path = "/script/constraintsAndObjective.fn",
                        functionName = "has10Rooms"
                    )
                ),
                constraints = emptyList()
            ),
            search = SearchConfig(
                mutations = MutationsConfig(
                    usingPaths = listOf(
                        "/transformation/addRoom.mt",
                        "/transformation/deleteRoom.mt"
                    )
                )
            ),
            solver = SolverConfig(
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
        )

        val mutationStrategy = MutationStrategyFactory.create(
            config.solver.parameters.mutation,
            transformations.keys.sorted().mapIndexed { idx, path ->
                TransformationOperator(id = idx, ast = transformations[path]!!)
            }
        )
        val evaluator = LocalMutationEvaluator(
            initialSolutionProvider = initialSolutionProvider,
            mutationStrategy = mutationStrategy,
            objectives = listOf(objective),
            constraints = emptyList(),
            metamodel = metamodel
        )

        val orchestrator = OptimizationOrchestrator(
            config = config,
            evaluator = evaluator
        )

        val result = runBlocking { orchestrator.run().single() }

        val population = result.getRawPopulation()
        assertFalse(population.isEmpty, "Must have at least one final solution")

        val finalSolutionResults = result.getFinalSolutions()
        assertEquals(
            finalSolutionResults.size, population.size(),
            "getFinalSolutions() and getRawPopulation() must return the same number of entries"
        )

        // For each solution, re-compute the room count and verify it matches the stored objective
        for ((moeaSolution, solutionResult) in population.zip(finalSolutionResults)) {
            val ref = moeaSolution.getWorkerRef() ?: error("Solution missing WorkerSolutionRef")
            val solutionData = runBlocking { evaluator.getSolutionData(ref) }.toModelData(metamodel)
            val rooms = solutionData.instances.count { it.className == "Room" }
            assertTrue(rooms >= 0, "Room count must be non-negative, got $rooms")

            val expectedObjective = kotlin.math.abs(rooms - 10).toDouble()
            val reportedObjective = solutionResult.objectives.first()

            assertEquals(
                expectedObjective, reportedObjective, 1e-9,
                "Objective value for solution with $rooms rooms should be $expectedObjective, " +
                    "but the optimizer reported $reportedObjective"
            )
        }

        runBlocking { evaluator.cleanup() }
    }
}
