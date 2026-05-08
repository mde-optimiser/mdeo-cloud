package com.mdeo.optimizer

import com.mdeo.expression.ast.TypedCallableBody
import com.mdeo.expression.ast.expressions.TypedBinaryExpression
import com.mdeo.expression.ast.expressions.TypedIdentifierExpression
import com.mdeo.expression.ast.expressions.TypedIntLiteralExpression
import com.mdeo.expression.ast.expressions.TypedMemberCallExpression
import com.mdeo.metamodel.data.AssociationData
import com.mdeo.metamodel.data.AssociationEndData
import com.mdeo.metamodel.data.ClassData
import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.metamodel.data.MultiplicityData
import com.mdeo.expression.ast.types.ReturnType
import com.mdeo.expression.ast.types.VoidType
import com.mdeo.expression.ast.statements.TypedIfStatement
import com.mdeo.expression.ast.statements.TypedReturnStatement
import com.mdeo.expression.ast.statements.TypedVariableDeclarationStatement
import com.mdeo.modeltransformation.ast.TypedAst as TransformationTypedAst
import com.mdeo.modeltransformation.ast.TransformationOperator
import com.mdeo.metamodel.data.ModelData
import com.mdeo.metamodel.data.ModelDataInstance
import com.mdeo.modeltransformation.ast.patterns.TypedPattern
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLink
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLinkElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLinkEnd
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstance
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstanceElement
import com.mdeo.modeltransformation.ast.statements.TypedMatchStatement
import com.mdeo.optimizer.config.AlgorithmParameters
import com.mdeo.optimizer.config.AlgorithmType
import com.mdeo.optimizer.config.GoalConfig
import com.mdeo.optimizer.config.MutationParameters
import com.mdeo.optimizer.config.MutationStepConfig
import com.mdeo.optimizer.config.MutationStrategy
import com.mdeo.optimizer.config.MutationsConfig
import com.mdeo.optimizer.config.ObjectiveConfig
import com.mdeo.optimizer.config.ObjectiveTendency
import com.mdeo.optimizer.config.OptimizationConfig
import com.mdeo.optimizer.config.ProblemConfig
import com.mdeo.optimizer.config.SearchConfig
import com.mdeo.optimizer.config.SolverConfig
import com.mdeo.optimizer.config.SolverProvider
import com.mdeo.optimizer.config.TerminationConfig
import com.mdeo.optimizer.config.VariationType
import com.mdeo.modeltransformation.graph.ModelGraph
import com.mdeo.optimizer.evaluation.LocalMutationEvaluator
import com.mdeo.optimizer.guidance.ScriptGuidanceFunction
import com.mdeo.optimizer.operators.MutationStrategyFactory
import com.mdeo.optimizer.solution.Solution
import com.mdeo.script.ast.TypedAst as ScriptTypedAst
import com.mdeo.script.ast.TypedFunction
import com.mdeo.script.compiler.CompilationInput
import com.mdeo.script.compiler.ScriptCompiler
import com.mdeo.metamodel.Metamodel
import com.mdeo.script.runtime.ExecutionEnvironment
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Abstract base for end-to-end performance tests of the full optimization pipeline.
 *
 * - Metamodel: House–Room with bidirectional association
 * - Model: single House instance
 * - Transformations: addRoom.mt, deleteRoom.mt
 * - Script: constraintsAndObjective.fn (has100Rooms objective)
 * - Solver: NSGAII, population=400, evolutions=500, batches=1
 *
 * Subclasses supply the concrete [ModelGraph] backend via [createModelGraph].
 *
 * These tests are tagged ["performance"] and are **excluded from normal test runs**.
 * Run with `-Pperformance` Gradle flag or via the profile_optimization.sh script.
 */
@Tag("performance")
abstract class OptimizationPerformanceTestBase {

    /**
     * Human-readable name of the backing graph implementation, used in the log line. 
     */
    protected abstract val backendName: String

    /**
     * Factory for the concrete graph backend under test. 
     */
    protected abstract fun createModelGraph(modelData: ModelData, metamodel: Metamodel): ModelGraph

    /**
     * Builds the ScriptTypedAst for constraintsAndObjective.fn.
     *
     * Equivalent script (pseudo-code):
     *   fun has100Rooms(): int {
     *     val roomCount: int = Room.all().size()
     *     if (roomCount > 100) { return roomCount - 100 }
     *     else                 { return 100 - roomCount }
     *   }
     *
     * Type index table:
     *   0 = VoidType
     *   1 = builtin.string
     *   2 = builtin.double
     *   3 = builtin.boolean
     *   4 = builtin.Any?
     *   5 = builtin.int
     *   6 = builtin.Collection<class/metamodel.mm.Room>
     *   7 = class-container/metamodel.mm.Room
     */
    protected fun buildScriptAst(): ScriptTypedAst {
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
        val target = TypedIntLiteralExpression(evalType = 5, value = "100")

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
            condition = TypedBinaryExpression(evalType = 3, operator = ">", left = roomCountIdent, right = target),
            thenBlock = listOf(
                TypedReturnStatement(
                    value = TypedBinaryExpression(evalType = 5, operator = "-", left = roomCountIdent, right = target)
                )
            ),
            elseIfs = emptyList(),
            elseBlock = listOf(
                TypedReturnStatement(
                    value = TypedBinaryExpression(evalType = 5, operator = "-", left = target, right = roomCountIdent)
                )
            )
        )

        return ScriptTypedAst(
            types = types,
            metamodelPath = "/metamodel.mm",
            imports = emptyList(),
            functions = listOf(
                TypedFunction(
                    name = "has100Rooms",
                    parameters = emptyList(),
                    returnType = 5,
                    body = TypedCallableBody(body = listOf(roomCountDecl, ifStatement))
                )
            )
        )
    }

    protected fun buildMetamodelData(): MetamodelData = MetamodelData(
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

    protected fun buildModelData(): ModelData = ModelData(
        metamodelPath = "../metamodel.mm",
        instances = listOf(
            ModelDataInstance(
                name = "house",
                className = "House",
                properties = emptyMap()
            )
        ),
        links = emptyList()
    )

    private fun buildBaseTypes(): List<ReturnType> = listOf(
        VoidType(),
        ClassTypeRef(`package` = "builtin", type = "string", isNullable = false, typeArgs = emptyMap()),
        ClassTypeRef(`package` = "builtin", type = "double", isNullable = false, typeArgs = emptyMap()),
        ClassTypeRef(`package` = "builtin", type = "boolean", isNullable = false, typeArgs = emptyMap()),
        ClassTypeRef(`package` = "builtin", type = "Any", isNullable = true)
    )

    protected fun buildTransformations(): Map<String, TransformationTypedAst> {
        val types = buildBaseTypes()

        val addRoomAst = TransformationTypedAst(
            types = types,
            metamodelPath = "/metamodel.mm",
            statements = listOf(
                TypedMatchStatement(
                    pattern = TypedPattern(
                        elements = listOf(
                            TypedPatternObjectInstanceElement(
                                objectInstance = TypedPatternObjectInstance(
                                    modifier = null,
                                    name = "house",
                                    className = "House",
                                    properties = emptyList()
                                )
                            ),
                            TypedPatternObjectInstanceElement(
                                objectInstance = TypedPatternObjectInstance(
                                    modifier = "create",
                                    name = "room",
                                    className = "Room",
                                    properties = emptyList()
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
                                    modifier = null,
                                    name = "house",
                                    className = "House",
                                    properties = emptyList()
                                )
                            ),
                            TypedPatternObjectInstanceElement(
                                objectInstance = TypedPatternObjectInstance(
                                    modifier = "delete",
                                    name = "room",
                                    className = "Room",
                                    properties = emptyList()
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

    protected fun buildOptimizationConfig(): OptimizationConfig = OptimizationConfig(
        problem = ProblemConfig(
            metamodelPath = "/metamodel.mm",
            modelPath = "/model/model.m"
        ),
        goal = GoalConfig(
            objectives = listOf(
                ObjectiveConfig(
                    type = ObjectiveTendency.MINIMIZE,
                    path = "/script/constraintsAndObjective.fn",
                    functionName = "has100Rooms"
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
                population = 400,
                variation = VariationType.MUTATION,
                mutation = MutationParameters(
                    step = MutationStepConfig.Fixed(n = 1),
                    strategy = MutationStrategy.RANDOM
                )
            ),
            termination = TerminationConfig(evolutions = 500),
            batches = 1
        )
    )

    @Test
    fun `full optimization end to end`() {
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
            compiledProgram.functionLookup["/script/constraintsAndObjective.fn"]!!["has100Rooms"]!!,
            System.out,
            "/script/constraintsAndObjective.fn::has100Rooms"
        )

        val initialSolutionProvider: () -> Solution = {
            val modelGraph = createModelGraph(modelData, metamodel)
            Solution(modelGraph)
        }

        val config = buildOptimizationConfig()

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

        val startTime = System.currentTimeMillis()
        runBlocking {
            orchestrator.run()
        }
        runBlocking { evaluator.cleanup() }
        val elapsedMs = System.currentTimeMillis() - startTime
        val elapsedSec = elapsedMs / 1000.0

        println(
            "Optimization completed ($backendName): elapsed=${elapsedMs}ms (${String.format("%.2f", elapsedSec)}s), " +
                "population=400, evolutions=500"
        )
    }
}
