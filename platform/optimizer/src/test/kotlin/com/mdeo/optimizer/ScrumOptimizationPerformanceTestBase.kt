package com.mdeo.optimizer

import com.mdeo.expression.ast.TypedCallableBody
import com.mdeo.expression.ast.expressions.TypedBinaryExpression
import com.mdeo.expression.ast.expressions.TypedDoubleLiteralExpression
import com.mdeo.expression.ast.expressions.TypedFunctionCallExpression
import com.mdeo.expression.ast.expressions.TypedIdentifierExpression
import com.mdeo.expression.ast.expressions.TypedIntLiteralExpression
import com.mdeo.expression.ast.expressions.TypedMemberAccessExpression
import com.mdeo.expression.ast.expressions.TypedMemberCallExpression
import com.mdeo.expression.ast.expressions.TypedCallArgument
import com.mdeo.expression.ast.expressions.TypedNullLiteralExpression
import com.mdeo.expression.ast.statements.TypedAssignmentStatement
import com.mdeo.expression.ast.statements.TypedForStatement
import com.mdeo.expression.ast.statements.TypedIfStatement
import com.mdeo.expression.ast.statements.TypedReturnStatement
import com.mdeo.expression.ast.statements.TypedVariableDeclarationStatement
import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.expression.ast.types.LambdaType
import com.mdeo.expression.ast.types.Parameter
import com.mdeo.expression.ast.types.ReturnType
import com.mdeo.expression.ast.types.VoidType
import com.mdeo.metamodel.Metamodel
import com.mdeo.metamodel.data.AssociationData
import com.mdeo.metamodel.data.AssociationEndData
import com.mdeo.metamodel.data.ClassData
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.metamodel.data.ModelData
import com.mdeo.metamodel.data.ModelDataInstance
import com.mdeo.metamodel.data.ModelDataLink
import com.mdeo.metamodel.data.ModelDataPropertyValue
import com.mdeo.metamodel.data.MultiplicityData
import com.mdeo.metamodel.data.PropertyData
import com.mdeo.modeltransformation.ast.TypedAst as TransformationTypedAst
import com.mdeo.modeltransformation.ast.TransformationOperator
import com.mdeo.modeltransformation.ast.patterns.TypedPattern
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLink
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLinkElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLinkEnd
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstance
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstanceElement
import com.mdeo.modeltransformation.ast.statements.TypedMatchStatement
import com.mdeo.modeltransformation.graph.ModelGraph
import com.mdeo.optimizer.config.AlgorithmParameters
import com.mdeo.optimizer.config.AlgorithmType
import com.mdeo.optimizer.config.GoalConfig
import com.mdeo.optimizer.config.MutationParameters
import com.mdeo.optimizer.config.MutationStepConfig
import com.mdeo.optimizer.config.MutationStrategy
import com.mdeo.optimizer.config.MutationsConfig
import com.mdeo.optimizer.config.ConstraintConfig
import com.mdeo.optimizer.config.ObjectiveConfig
import com.mdeo.optimizer.config.ObjectiveTendency
import com.mdeo.optimizer.config.OptimizationConfig
import com.mdeo.optimizer.config.ProblemConfig
import com.mdeo.optimizer.config.SearchConfig
import com.mdeo.optimizer.config.SolverConfig
import com.mdeo.optimizer.config.SolverProvider
import com.mdeo.optimizer.config.TerminationConfig
import com.mdeo.optimizer.config.VariationType
import com.mdeo.optimizer.evaluation.LocalMutationEvaluator
import com.mdeo.optimizer.guidance.ScriptGuidanceFunction
import com.mdeo.optimizer.operators.MutationStrategyFactory
import com.mdeo.optimizer.solution.Solution
import com.mdeo.script.ast.TypedAst as ScriptTypedAst
import com.mdeo.script.ast.TypedFunction
import com.mdeo.script.ast.TypedImport
import com.mdeo.script.ast.TypedParameter
import com.mdeo.script.ast.expressions.TypedLambdaExpression
import com.mdeo.script.compiler.CompilationInput
import com.mdeo.script.compiler.ScriptCompiler
import com.mdeo.script.runtime.ExecutionEnvironment
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Abstract base for end-to-end performance tests of the full optimization pipeline
 * using the Scrum sprint planning project.
 *
 * - Metamodel: Sprint, Stakeholder, WorkItem with associations
 * - Model: 5 stakeholders, 119 work items with importance/effort properties
 * - Transformations: createSprint, addItemToSprint, deleteSprint, moveItemBetweenSprints
 * - Scripts: constraints.fn (2 functions), objectives.fn (2 functions), stddev.fn (helper)
 * - Solver: NSGAII, population=80, evolutions=500, batches=1
 *
 * Subclasses supply the concrete [ModelGraph] backend via [createModelGraph].
 *
 * These tests are tagged ["performance"] and are **excluded from normal test runs**.
 * Run with `-Pperformance` Gradle flag or via the profile_optimization.sh script.
 */
@Tag("performance")
abstract class ScrumOptimizationPerformanceTestBase {

    protected abstract val backendName: String

    protected abstract fun createModelGraph(modelData: ModelData, metamodel: Metamodel): ModelGraph

    private fun buildMetamodelData(): MetamodelData = MetamodelData(
        path = "/metamodel.mm",
        classes = listOf(
            ClassData(name = "Sprint", isAbstract = false, extends = emptyList(), properties = emptyList()),
            ClassData(name = "Stakeholder", isAbstract = false, extends = emptyList(), properties = emptyList()),
            ClassData(name = "WorkItem", isAbstract = false, extends = emptyList(), properties = listOf(PropertyData(name = "importance", primitiveType = "int", multiplicity = MultiplicityData(lower = 1, upper = 1)), PropertyData(name = "effort", primitiveType = "int", multiplicity = MultiplicityData(lower = 1, upper = 1))))
        ),
        enums = emptyList(),
        associations = listOf(
            AssociationData(
                source = AssociationEndData(className = "Stakeholder", multiplicity = MultiplicityData(lower = 1, upper = -1), name = "workitems"),
                operator = "<-->",
                target = AssociationEndData(className = "WorkItem", multiplicity = MultiplicityData(lower = 1, upper = 1), name = "stakeholder")
            ),
            AssociationData(
                source = AssociationEndData(className = "Sprint", multiplicity = MultiplicityData(lower = 1, upper = -1), name = "committedItems"),
                operator = "<-->",
                target = AssociationEndData(className = "WorkItem", multiplicity = MultiplicityData(lower = 1, upper = 1), name = "isPlannedFor")
            )
        ),
        importedMetamodelPaths = emptyList()
    )

    private fun buildModelData(): ModelData = ModelData(
        metamodelPath = "../metamodel.mm",
        instances = listOf(
            ModelDataInstance(name = "stakeholder0", className = "Stakeholder", properties = emptyMap()),
            ModelDataInstance(name = "stakeholder1", className = "Stakeholder", properties = emptyMap()),
            ModelDataInstance(name = "stakeholder2", className = "Stakeholder", properties = emptyMap()),
            ModelDataInstance(name = "stakeholder3", className = "Stakeholder", properties = emptyMap()),
            ModelDataInstance(name = "stakeholder4", className = "Stakeholder", properties = emptyMap()),
            ModelDataInstance(name = "workitem0", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(8.0), "effort" to ModelDataPropertyValue.NumberValue(5.0))),
            ModelDataInstance(name = "workitem1", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(8.0), "effort" to ModelDataPropertyValue.NumberValue(3.0))),
            ModelDataInstance(name = "workitem2", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(3.0), "effort" to ModelDataPropertyValue.NumberValue(8.0))),
            ModelDataInstance(name = "workitem3", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(5.0), "effort" to ModelDataPropertyValue.NumberValue(1.0))),
            ModelDataInstance(name = "workitem4", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(8.0), "effort" to ModelDataPropertyValue.NumberValue(1.0))),
            ModelDataInstance(name = "workitem5", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(5.0), "effort" to ModelDataPropertyValue.NumberValue(8.0))),
            ModelDataInstance(name = "workitem6", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(3.0), "effort" to ModelDataPropertyValue.NumberValue(3.0))),
            ModelDataInstance(name = "workitem7", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(5.0), "effort" to ModelDataPropertyValue.NumberValue(3.0))),
            ModelDataInstance(name = "workitem8", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(5.0), "effort" to ModelDataPropertyValue.NumberValue(8.0))),
            ModelDataInstance(name = "workitem9", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(5.0), "effort" to ModelDataPropertyValue.NumberValue(5.0))),
            ModelDataInstance(name = "workitem10", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(8.0), "effort" to ModelDataPropertyValue.NumberValue(3.0))),
            ModelDataInstance(name = "workitem11", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(5.0), "effort" to ModelDataPropertyValue.NumberValue(5.0))),
            ModelDataInstance(name = "workitem12", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(8.0), "effort" to ModelDataPropertyValue.NumberValue(3.0))),
            ModelDataInstance(name = "workitem13", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(3.0), "effort" to ModelDataPropertyValue.NumberValue(3.0))),
            ModelDataInstance(name = "workitem14", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(3.0), "effort" to ModelDataPropertyValue.NumberValue(3.0))),
            ModelDataInstance(name = "workitem15", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(8.0), "effort" to ModelDataPropertyValue.NumberValue(8.0))),
            ModelDataInstance(name = "workitem16", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(3.0), "effort" to ModelDataPropertyValue.NumberValue(1.0))),
            ModelDataInstance(name = "workitem17", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(1.0), "effort" to ModelDataPropertyValue.NumberValue(3.0))),
            ModelDataInstance(name = "workitem18", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(8.0), "effort" to ModelDataPropertyValue.NumberValue(1.0))),
            ModelDataInstance(name = "workitem19", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(8.0), "effort" to ModelDataPropertyValue.NumberValue(5.0))),
            ModelDataInstance(name = "workitem20", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(1.0), "effort" to ModelDataPropertyValue.NumberValue(3.0))),
            ModelDataInstance(name = "workitem21", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(1.0), "effort" to ModelDataPropertyValue.NumberValue(1.0))),
            ModelDataInstance(name = "workitem22", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(3.0), "effort" to ModelDataPropertyValue.NumberValue(5.0))),
            ModelDataInstance(name = "workitem23", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(3.0), "effort" to ModelDataPropertyValue.NumberValue(1.0))),
            ModelDataInstance(name = "workitem24", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(3.0), "effort" to ModelDataPropertyValue.NumberValue(1.0))),
            ModelDataInstance(name = "workitem25", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(1.0), "effort" to ModelDataPropertyValue.NumberValue(3.0))),
            ModelDataInstance(name = "workitem26", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(1.0), "effort" to ModelDataPropertyValue.NumberValue(5.0))),
            ModelDataInstance(name = "workitem27", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(1.0), "effort" to ModelDataPropertyValue.NumberValue(1.0))),
            ModelDataInstance(name = "workitem28", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(1.0), "effort" to ModelDataPropertyValue.NumberValue(8.0))),
            ModelDataInstance(name = "workitem29", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(5.0), "effort" to ModelDataPropertyValue.NumberValue(3.0))),
            ModelDataInstance(name = "workitem30", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(8.0), "effort" to ModelDataPropertyValue.NumberValue(5.0))),
            ModelDataInstance(name = "workitem31", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(3.0), "effort" to ModelDataPropertyValue.NumberValue(8.0))),
            ModelDataInstance(name = "workitem32", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(3.0), "effort" to ModelDataPropertyValue.NumberValue(3.0))),
            ModelDataInstance(name = "workitem33", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(8.0), "effort" to ModelDataPropertyValue.NumberValue(1.0))),
            ModelDataInstance(name = "workitem34", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(5.0), "effort" to ModelDataPropertyValue.NumberValue(1.0))),
            ModelDataInstance(name = "workitem35", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(8.0), "effort" to ModelDataPropertyValue.NumberValue(3.0))),
            ModelDataInstance(name = "workitem36", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(8.0), "effort" to ModelDataPropertyValue.NumberValue(1.0))),
            ModelDataInstance(name = "workitem37", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(8.0), "effort" to ModelDataPropertyValue.NumberValue(5.0))),
            ModelDataInstance(name = "workitem38", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(3.0), "effort" to ModelDataPropertyValue.NumberValue(1.0))),
            ModelDataInstance(name = "workitem39", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(8.0), "effort" to ModelDataPropertyValue.NumberValue(3.0))),
            ModelDataInstance(name = "workitem40", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(8.0), "effort" to ModelDataPropertyValue.NumberValue(3.0))),
            ModelDataInstance(name = "workitem41", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(3.0), "effort" to ModelDataPropertyValue.NumberValue(1.0))),
            ModelDataInstance(name = "workitem42", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(5.0), "effort" to ModelDataPropertyValue.NumberValue(1.0))),
            ModelDataInstance(name = "workitem43", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(3.0), "effort" to ModelDataPropertyValue.NumberValue(3.0))),
            ModelDataInstance(name = "workitem44", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(3.0), "effort" to ModelDataPropertyValue.NumberValue(8.0))),
            ModelDataInstance(name = "workitem45", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(5.0), "effort" to ModelDataPropertyValue.NumberValue(8.0))),
            ModelDataInstance(name = "workitem46", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(8.0), "effort" to ModelDataPropertyValue.NumberValue(5.0))),
            ModelDataInstance(name = "workitem47", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(5.0), "effort" to ModelDataPropertyValue.NumberValue(8.0))),
            ModelDataInstance(name = "workitem48", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(5.0), "effort" to ModelDataPropertyValue.NumberValue(8.0))),
            ModelDataInstance(name = "workitem49", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(3.0), "effort" to ModelDataPropertyValue.NumberValue(1.0))),
            ModelDataInstance(name = "workitem50", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(1.0), "effort" to ModelDataPropertyValue.NumberValue(8.0))),
            ModelDataInstance(name = "workitem51", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(1.0), "effort" to ModelDataPropertyValue.NumberValue(5.0))),
            ModelDataInstance(name = "workitem52", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(3.0), "effort" to ModelDataPropertyValue.NumberValue(1.0))),
            ModelDataInstance(name = "workitem53", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(1.0), "effort" to ModelDataPropertyValue.NumberValue(1.0))),
            ModelDataInstance(name = "workitem54", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(8.0), "effort" to ModelDataPropertyValue.NumberValue(3.0))),
            ModelDataInstance(name = "workitem55", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(1.0), "effort" to ModelDataPropertyValue.NumberValue(8.0))),
            ModelDataInstance(name = "workitem56", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(5.0), "effort" to ModelDataPropertyValue.NumberValue(8.0))),
            ModelDataInstance(name = "workitem57", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(5.0), "effort" to ModelDataPropertyValue.NumberValue(8.0))),
            ModelDataInstance(name = "workitem58", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(8.0), "effort" to ModelDataPropertyValue.NumberValue(5.0))),
            ModelDataInstance(name = "workitem59", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(8.0), "effort" to ModelDataPropertyValue.NumberValue(5.0))),
            ModelDataInstance(name = "workitem60", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(3.0), "effort" to ModelDataPropertyValue.NumberValue(1.0))),
            ModelDataInstance(name = "workitem61", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(5.0), "effort" to ModelDataPropertyValue.NumberValue(1.0))),
            ModelDataInstance(name = "workitem62", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(1.0), "effort" to ModelDataPropertyValue.NumberValue(1.0))),
            ModelDataInstance(name = "workitem63", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(1.0), "effort" to ModelDataPropertyValue.NumberValue(8.0))),
            ModelDataInstance(name = "workitem64", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(5.0), "effort" to ModelDataPropertyValue.NumberValue(8.0))),
            ModelDataInstance(name = "workitem65", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(5.0), "effort" to ModelDataPropertyValue.NumberValue(5.0))),
            ModelDataInstance(name = "workitem66", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(3.0), "effort" to ModelDataPropertyValue.NumberValue(8.0))),
            ModelDataInstance(name = "workitem67", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(5.0), "effort" to ModelDataPropertyValue.NumberValue(5.0))),
            ModelDataInstance(name = "workitem68", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(5.0), "effort" to ModelDataPropertyValue.NumberValue(5.0))),
            ModelDataInstance(name = "workitem69", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(1.0), "effort" to ModelDataPropertyValue.NumberValue(3.0))),
            ModelDataInstance(name = "workitem70", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(5.0), "effort" to ModelDataPropertyValue.NumberValue(1.0))),
            ModelDataInstance(name = "workitem71", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(1.0), "effort" to ModelDataPropertyValue.NumberValue(5.0))),
            ModelDataInstance(name = "workitem72", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(8.0), "effort" to ModelDataPropertyValue.NumberValue(1.0))),
            ModelDataInstance(name = "workitem73", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(1.0), "effort" to ModelDataPropertyValue.NumberValue(1.0))),
            ModelDataInstance(name = "workitem74", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(1.0), "effort" to ModelDataPropertyValue.NumberValue(3.0))),
            ModelDataInstance(name = "workitem75", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(5.0), "effort" to ModelDataPropertyValue.NumberValue(1.0))),
            ModelDataInstance(name = "workitem76", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(8.0), "effort" to ModelDataPropertyValue.NumberValue(5.0))),
            ModelDataInstance(name = "workitem77", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(3.0), "effort" to ModelDataPropertyValue.NumberValue(3.0))),
            ModelDataInstance(name = "workitem78", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(1.0), "effort" to ModelDataPropertyValue.NumberValue(1.0))),
            ModelDataInstance(name = "workitem79", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(5.0), "effort" to ModelDataPropertyValue.NumberValue(5.0))),
            ModelDataInstance(name = "workitem80", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(8.0), "effort" to ModelDataPropertyValue.NumberValue(1.0))),
            ModelDataInstance(name = "workitem81", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(5.0), "effort" to ModelDataPropertyValue.NumberValue(1.0))),
            ModelDataInstance(name = "workitem82", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(5.0), "effort" to ModelDataPropertyValue.NumberValue(3.0))),
            ModelDataInstance(name = "workitem83", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(3.0), "effort" to ModelDataPropertyValue.NumberValue(8.0))),
            ModelDataInstance(name = "workitem84", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(1.0), "effort" to ModelDataPropertyValue.NumberValue(3.0))),
            ModelDataInstance(name = "workitem85", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(5.0), "effort" to ModelDataPropertyValue.NumberValue(3.0))),
            ModelDataInstance(name = "workitem86", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(5.0), "effort" to ModelDataPropertyValue.NumberValue(3.0))),
            ModelDataInstance(name = "workitem87", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(1.0), "effort" to ModelDataPropertyValue.NumberValue(5.0))),
            ModelDataInstance(name = "workitem88", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(3.0), "effort" to ModelDataPropertyValue.NumberValue(3.0))),
            ModelDataInstance(name = "workitem89", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(5.0), "effort" to ModelDataPropertyValue.NumberValue(3.0))),
            ModelDataInstance(name = "workitem90", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(3.0), "effort" to ModelDataPropertyValue.NumberValue(1.0))),
            ModelDataInstance(name = "workitem91", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(3.0), "effort" to ModelDataPropertyValue.NumberValue(8.0))),
            ModelDataInstance(name = "workitem92", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(8.0), "effort" to ModelDataPropertyValue.NumberValue(5.0))),
            ModelDataInstance(name = "workitem93", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(8.0), "effort" to ModelDataPropertyValue.NumberValue(1.0))),
            ModelDataInstance(name = "workitem94", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(8.0), "effort" to ModelDataPropertyValue.NumberValue(3.0))),
            ModelDataInstance(name = "workitem95", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(1.0), "effort" to ModelDataPropertyValue.NumberValue(5.0))),
            ModelDataInstance(name = "workitem96", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(8.0), "effort" to ModelDataPropertyValue.NumberValue(5.0))),
            ModelDataInstance(name = "workitem97", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(3.0), "effort" to ModelDataPropertyValue.NumberValue(1.0))),
            ModelDataInstance(name = "workitem98", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(5.0), "effort" to ModelDataPropertyValue.NumberValue(3.0))),
            ModelDataInstance(name = "workitem99", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(5.0), "effort" to ModelDataPropertyValue.NumberValue(3.0))),
            ModelDataInstance(name = "workitem100", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(8.0), "effort" to ModelDataPropertyValue.NumberValue(1.0))),
            ModelDataInstance(name = "workitem101", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(3.0), "effort" to ModelDataPropertyValue.NumberValue(8.0))),
            ModelDataInstance(name = "workitem102", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(3.0), "effort" to ModelDataPropertyValue.NumberValue(5.0))),
            ModelDataInstance(name = "workitem103", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(5.0), "effort" to ModelDataPropertyValue.NumberValue(1.0))),
            ModelDataInstance(name = "workitem104", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(5.0), "effort" to ModelDataPropertyValue.NumberValue(3.0))),
            ModelDataInstance(name = "workitem105", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(8.0), "effort" to ModelDataPropertyValue.NumberValue(8.0))),
            ModelDataInstance(name = "workitem106", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(1.0), "effort" to ModelDataPropertyValue.NumberValue(3.0))),
            ModelDataInstance(name = "workitem107", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(8.0), "effort" to ModelDataPropertyValue.NumberValue(8.0))),
            ModelDataInstance(name = "workitem108", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(5.0), "effort" to ModelDataPropertyValue.NumberValue(3.0))),
            ModelDataInstance(name = "workitem109", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(3.0), "effort" to ModelDataPropertyValue.NumberValue(1.0))),
            ModelDataInstance(name = "workitem110", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(8.0), "effort" to ModelDataPropertyValue.NumberValue(3.0))),
            ModelDataInstance(name = "workitem111", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(5.0), "effort" to ModelDataPropertyValue.NumberValue(3.0))),
            ModelDataInstance(name = "workitem112", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(8.0), "effort" to ModelDataPropertyValue.NumberValue(3.0))),
            ModelDataInstance(name = "workitem113", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(1.0), "effort" to ModelDataPropertyValue.NumberValue(3.0))),
            ModelDataInstance(name = "workitem114", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(1.0), "effort" to ModelDataPropertyValue.NumberValue(8.0))),
            ModelDataInstance(name = "workitem115", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(5.0), "effort" to ModelDataPropertyValue.NumberValue(3.0))),
            ModelDataInstance(name = "workitem116", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(3.0), "effort" to ModelDataPropertyValue.NumberValue(3.0))),
            ModelDataInstance(name = "workitem117", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(3.0), "effort" to ModelDataPropertyValue.NumberValue(1.0))),
            ModelDataInstance(name = "workitem118", className = "WorkItem", properties = mapOf("importance" to ModelDataPropertyValue.NumberValue(3.0), "effort" to ModelDataPropertyValue.NumberValue(8.0)))
        ),
        links = listOf(
            ModelDataLink(sourceName = "stakeholder0", sourceProperty = "workitems", targetName = "workitem0", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder0", sourceProperty = "workitems", targetName = "workitem1", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder0", sourceProperty = "workitems", targetName = "workitem2", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder0", sourceProperty = "workitems", targetName = "workitem3", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder0", sourceProperty = "workitems", targetName = "workitem4", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder0", sourceProperty = "workitems", targetName = "workitem5", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder0", sourceProperty = "workitems", targetName = "workitem6", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder0", sourceProperty = "workitems", targetName = "workitem7", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder0", sourceProperty = "workitems", targetName = "workitem8", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder0", sourceProperty = "workitems", targetName = "workitem9", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder0", sourceProperty = "workitems", targetName = "workitem10", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder0", sourceProperty = "workitems", targetName = "workitem11", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder0", sourceProperty = "workitems", targetName = "workitem12", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder0", sourceProperty = "workitems", targetName = "workitem13", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder1", sourceProperty = "workitems", targetName = "workitem14", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder1", sourceProperty = "workitems", targetName = "workitem15", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder1", sourceProperty = "workitems", targetName = "workitem16", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder1", sourceProperty = "workitems", targetName = "workitem17", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder1", sourceProperty = "workitems", targetName = "workitem18", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder1", sourceProperty = "workitems", targetName = "workitem19", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder1", sourceProperty = "workitems", targetName = "workitem20", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder1", sourceProperty = "workitems", targetName = "workitem21", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder1", sourceProperty = "workitems", targetName = "workitem22", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder1", sourceProperty = "workitems", targetName = "workitem23", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder1", sourceProperty = "workitems", targetName = "workitem24", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder1", sourceProperty = "workitems", targetName = "workitem25", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder1", sourceProperty = "workitems", targetName = "workitem26", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder1", sourceProperty = "workitems", targetName = "workitem27", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder1", sourceProperty = "workitems", targetName = "workitem28", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder1", sourceProperty = "workitems", targetName = "workitem29", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder1", sourceProperty = "workitems", targetName = "workitem30", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder1", sourceProperty = "workitems", targetName = "workitem31", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder1", sourceProperty = "workitems", targetName = "workitem32", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder1", sourceProperty = "workitems", targetName = "workitem33", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder1", sourceProperty = "workitems", targetName = "workitem34", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder1", sourceProperty = "workitems", targetName = "workitem35", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder1", sourceProperty = "workitems", targetName = "workitem36", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder1", sourceProperty = "workitems", targetName = "workitem37", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder1", sourceProperty = "workitems", targetName = "workitem38", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder1", sourceProperty = "workitems", targetName = "workitem39", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder1", sourceProperty = "workitems", targetName = "workitem40", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder1", sourceProperty = "workitems", targetName = "workitem41", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder1", sourceProperty = "workitems", targetName = "workitem42", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder2", sourceProperty = "workitems", targetName = "workitem43", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder2", sourceProperty = "workitems", targetName = "workitem44", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder2", sourceProperty = "workitems", targetName = "workitem45", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder2", sourceProperty = "workitems", targetName = "workitem46", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder2", sourceProperty = "workitems", targetName = "workitem47", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder2", sourceProperty = "workitems", targetName = "workitem48", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder2", sourceProperty = "workitems", targetName = "workitem49", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder2", sourceProperty = "workitems", targetName = "workitem50", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder2", sourceProperty = "workitems", targetName = "workitem51", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder2", sourceProperty = "workitems", targetName = "workitem52", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder2", sourceProperty = "workitems", targetName = "workitem53", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder2", sourceProperty = "workitems", targetName = "workitem54", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder2", sourceProperty = "workitems", targetName = "workitem55", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder2", sourceProperty = "workitems", targetName = "workitem56", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder2", sourceProperty = "workitems", targetName = "workitem57", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder2", sourceProperty = "workitems", targetName = "workitem58", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder2", sourceProperty = "workitems", targetName = "workitem59", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder2", sourceProperty = "workitems", targetName = "workitem60", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder2", sourceProperty = "workitems", targetName = "workitem61", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder2", sourceProperty = "workitems", targetName = "workitem62", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder2", sourceProperty = "workitems", targetName = "workitem63", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder2", sourceProperty = "workitems", targetName = "workitem64", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder2", sourceProperty = "workitems", targetName = "workitem65", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder2", sourceProperty = "workitems", targetName = "workitem66", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder2", sourceProperty = "workitems", targetName = "workitem67", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder2", sourceProperty = "workitems", targetName = "workitem68", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder2", sourceProperty = "workitems", targetName = "workitem69", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder2", sourceProperty = "workitems", targetName = "workitem70", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder2", sourceProperty = "workitems", targetName = "workitem71", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder2", sourceProperty = "workitems", targetName = "workitem72", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder3", sourceProperty = "workitems", targetName = "workitem73", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder3", sourceProperty = "workitems", targetName = "workitem74", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder3", sourceProperty = "workitems", targetName = "workitem75", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder3", sourceProperty = "workitems", targetName = "workitem76", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder3", sourceProperty = "workitems", targetName = "workitem77", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder3", sourceProperty = "workitems", targetName = "workitem78", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder3", sourceProperty = "workitems", targetName = "workitem79", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder3", sourceProperty = "workitems", targetName = "workitem80", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder3", sourceProperty = "workitems", targetName = "workitem81", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder3", sourceProperty = "workitems", targetName = "workitem82", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder3", sourceProperty = "workitems", targetName = "workitem83", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder3", sourceProperty = "workitems", targetName = "workitem84", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder3", sourceProperty = "workitems", targetName = "workitem85", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder3", sourceProperty = "workitems", targetName = "workitem86", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder3", sourceProperty = "workitems", targetName = "workitem87", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder3", sourceProperty = "workitems", targetName = "workitem88", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder3", sourceProperty = "workitems", targetName = "workitem89", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder3", sourceProperty = "workitems", targetName = "workitem90", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder3", sourceProperty = "workitems", targetName = "workitem91", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder3", sourceProperty = "workitems", targetName = "workitem92", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder3", sourceProperty = "workitems", targetName = "workitem93", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder3", sourceProperty = "workitems", targetName = "workitem94", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder3", sourceProperty = "workitems", targetName = "workitem95", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder3", sourceProperty = "workitems", targetName = "workitem96", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder3", sourceProperty = "workitems", targetName = "workitem97", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder3", sourceProperty = "workitems", targetName = "workitem98", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder3", sourceProperty = "workitems", targetName = "workitem99", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder3", sourceProperty = "workitems", targetName = "workitem100", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder3", sourceProperty = "workitems", targetName = "workitem101", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder3", sourceProperty = "workitems", targetName = "workitem102", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder3", sourceProperty = "workitems", targetName = "workitem103", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder3", sourceProperty = "workitems", targetName = "workitem104", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder3", sourceProperty = "workitems", targetName = "workitem105", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder3", sourceProperty = "workitems", targetName = "workitem106", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder3", sourceProperty = "workitems", targetName = "workitem107", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder3", sourceProperty = "workitems", targetName = "workitem108", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder3", sourceProperty = "workitems", targetName = "workitem109", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder3", sourceProperty = "workitems", targetName = "workitem110", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder3", sourceProperty = "workitems", targetName = "workitem111", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder3", sourceProperty = "workitems", targetName = "workitem112", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder3", sourceProperty = "workitems", targetName = "workitem113", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder3", sourceProperty = "workitems", targetName = "workitem114", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder3", sourceProperty = "workitems", targetName = "workitem115", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder3", sourceProperty = "workitems", targetName = "workitem116", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder4", sourceProperty = "workitems", targetName = "workitem117", targetProperty = "stakeholder"),
            ModelDataLink(sourceName = "stakeholder4", sourceProperty = "workitems", targetName = "workitem118", targetProperty = "stakeholder")
        )
    )

    private fun buildTransformations(): Map<String, TransformationTypedAst> {
        val createSprintAst = TransformationTypedAst(
            types = listOf(
            VoidType(),
            ClassTypeRef(`package` = "builtin", type = "string", isNullable = false, typeArgs = emptyMap()),
            ClassTypeRef(`package` = "builtin", type = "double", isNullable = false, typeArgs = emptyMap()),
            ClassTypeRef(`package` = "builtin", type = "boolean", isNullable = false, typeArgs = emptyMap()),
            ClassTypeRef(`package` = "builtin", type = "Any", isNullable = true, typeArgs = emptyMap()),
        ),
            metamodelPath = "/metamodel.mm",
            statements = listOf(
            TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        TypedPatternObjectInstanceElement(
                        objectInstance = TypedPatternObjectInstance(
                            modifier = "create", name = "newSprint", className = "Sprint",
                            properties = emptyList()
                        )
                    ),
                        TypedPatternObjectInstanceElement(
                        objectInstance = TypedPatternObjectInstance(
                            modifier = null, name = "workitem", className = "WorkItem",
                            properties = emptyList()
                        )
                    ),
                        TypedPatternObjectInstanceElement(
                        objectInstance = TypedPatternObjectInstance(
                            modifier = null, name = "newStakeholder", className = "Stakeholder",
                            properties = emptyList()
                        )
                    ),
                        TypedPatternObjectInstanceElement(
                        objectInstance = TypedPatternObjectInstance(
                            modifier = "forbid", name = "newSprint1", className = "Sprint",
                            properties = emptyList()
                        )
                    ),
                        TypedPatternLinkElement(
                        link = TypedPatternLink(
                            modifier = "create",
                            source = TypedPatternLinkEnd(objectName = "newSprint", propertyName = "committedItems"),
                            target = TypedPatternLinkEnd(objectName = "workitem", propertyName = "isPlannedFor")
                        )
                    ),
                        TypedPatternLinkElement(
                        link = TypedPatternLink(
                            modifier = "forbid",
                            source = TypedPatternLinkEnd(objectName = "newSprint1", propertyName = "committedItems"),
                            target = TypedPatternLinkEnd(objectName = "workitem", propertyName = "isPlannedFor")
                        )
                    ),
                        TypedPatternLinkElement(
                        link = TypedPatternLink(
                            modifier = null,
                            source = TypedPatternLinkEnd(objectName = "newStakeholder", propertyName = "workitems"),
                            target = TypedPatternLinkEnd(objectName = "workitem", propertyName = "stakeholder")
                        )
                    )
                    )
                )
            )
            )
        )

        val addItemToSprintAst = TransformationTypedAst(
            types = listOf(
            VoidType(),
            ClassTypeRef(`package` = "builtin", type = "string", isNullable = false, typeArgs = emptyMap()),
            ClassTypeRef(`package` = "builtin", type = "double", isNullable = false, typeArgs = emptyMap()),
            ClassTypeRef(`package` = "builtin", type = "boolean", isNullable = false, typeArgs = emptyMap()),
            ClassTypeRef(`package` = "builtin", type = "Any", isNullable = true, typeArgs = emptyMap()),
        ),
            metamodelPath = "/metamodel.mm",
            statements = listOf(
            TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        TypedPatternObjectInstanceElement(
                        objectInstance = TypedPatternObjectInstance(
                            modifier = null, name = "sprint1", className = "Sprint",
                            properties = emptyList()
                        )
                    ),
                        TypedPatternObjectInstanceElement(
                        objectInstance = TypedPatternObjectInstance(
                            modifier = null, name = "workItem", className = "WorkItem",
                            properties = emptyList()
                        )
                    ),
                        TypedPatternObjectInstanceElement(
                        objectInstance = TypedPatternObjectInstance(
                            modifier = "forbid", name = "sprint2", className = "Sprint",
                            properties = emptyList()
                        )
                    ),
                        TypedPatternLinkElement(
                        link = TypedPatternLink(
                            modifier = "create",
                            source = TypedPatternLinkEnd(objectName = "sprint1", propertyName = "committedItems"),
                            target = TypedPatternLinkEnd(objectName = "workItem", propertyName = "isPlannedFor")
                        )
                    ),
                        TypedPatternLinkElement(
                        link = TypedPatternLink(
                            modifier = "forbid",
                            source = TypedPatternLinkEnd(objectName = "sprint1", propertyName = "committedItems"),
                            target = TypedPatternLinkEnd(objectName = "workItem", propertyName = "isPlannedFor")
                        )
                    ),
                        TypedPatternLinkElement(
                        link = TypedPatternLink(
                            modifier = "forbid",
                            source = TypedPatternLinkEnd(objectName = "sprint2", propertyName = "committedItems"),
                            target = TypedPatternLinkEnd(objectName = "workItem", propertyName = "isPlannedFor")
                        )
                    )
                    )
                )
            )
            )
        )

        val deleteSprintAst = TransformationTypedAst(
            types = listOf(
            VoidType(),
            ClassTypeRef(`package` = "builtin", type = "string", isNullable = false, typeArgs = emptyMap()),
            ClassTypeRef(`package` = "builtin", type = "double", isNullable = false, typeArgs = emptyMap()),
            ClassTypeRef(`package` = "builtin", type = "boolean", isNullable = false, typeArgs = emptyMap()),
            ClassTypeRef(`package` = "builtin", type = "Any", isNullable = true, typeArgs = emptyMap()),
        ),
            metamodelPath = "/metamodel.mm",
            statements = listOf(
            TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        TypedPatternObjectInstanceElement(
                        objectInstance = TypedPatternObjectInstance(
                            modifier = "delete", name = "newSprint", className = "Sprint",
                            properties = emptyList()
                        )
                    ),
                        TypedPatternObjectInstanceElement(
                        objectInstance = TypedPatternObjectInstance(
                            modifier = "forbid", name = "newWorkItem", className = "WorkItem",
                            properties = emptyList()
                        )
                    ),
                        TypedPatternLinkElement(
                        link = TypedPatternLink(
                            modifier = "forbid",
                            source = TypedPatternLinkEnd(objectName = "newSprint", propertyName = "committedItems"),
                            target = TypedPatternLinkEnd(objectName = "newWorkItem", propertyName = "isPlannedFor")
                        )
                    )
                    )
                )
            )
            )
        )

        val moveItemBetweenSprintsAst = TransformationTypedAst(
            types = listOf(
            VoidType(),
            ClassTypeRef(`package` = "builtin", type = "string", isNullable = false, typeArgs = emptyMap()),
            ClassTypeRef(`package` = "builtin", type = "double", isNullable = false, typeArgs = emptyMap()),
            ClassTypeRef(`package` = "builtin", type = "boolean", isNullable = false, typeArgs = emptyMap()),
            ClassTypeRef(`package` = "builtin", type = "Any", isNullable = true, typeArgs = emptyMap()),
        ),
            metamodelPath = "/metamodel.mm",
            statements = listOf(
            TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        TypedPatternObjectInstanceElement(
                        objectInstance = TypedPatternObjectInstance(
                            modifier = null, name = "sprint1", className = "Sprint",
                            properties = emptyList()
                        )
                    ),
                        TypedPatternObjectInstanceElement(
                        objectInstance = TypedPatternObjectInstance(
                            modifier = null, name = "sprint2", className = "Sprint",
                            properties = emptyList()
                        )
                    ),
                        TypedPatternObjectInstanceElement(
                        objectInstance = TypedPatternObjectInstance(
                            modifier = null, name = "workItem", className = "WorkItem",
                            properties = emptyList()
                        )
                    ),
                        TypedPatternLinkElement(
                        link = TypedPatternLink(
                            modifier = "create",
                            source = TypedPatternLinkEnd(objectName = "sprint1", propertyName = "committedItems"),
                            target = TypedPatternLinkEnd(objectName = "workItem", propertyName = "isPlannedFor")
                        )
                    ),
                        TypedPatternLinkElement(
                        link = TypedPatternLink(
                            modifier = "delete",
                            source = TypedPatternLinkEnd(objectName = "sprint2", propertyName = "committedItems"),
                            target = TypedPatternLinkEnd(objectName = "workItem", propertyName = "isPlannedFor")
                        )
                    )
                    )
                )
            )
            )
        )

        return mapOf(
            "/transformation/createSprint.mt" to createSprintAst,
            "/transformation/addItemToSprint.mt" to addItemToSprintAst,
            "/transformation/deleteSprint.mt" to deleteSprintAst,
            "/transformation/moveItemBetweenSprints.mt" to moveItemBetweenSprintsAst
        )
    }

    private fun buildConstraintsAst(): ScriptTypedAst {
        val types: List<ReturnType> = listOf(
            VoidType(),
            ClassTypeRef(`package` = "builtin", type = "string", isNullable = false, typeArgs = emptyMap()),
            ClassTypeRef(`package` = "builtin", type = "double", isNullable = false, typeArgs = emptyMap()),
            ClassTypeRef(`package` = "builtin", type = "boolean", isNullable = false, typeArgs = emptyMap()),
            ClassTypeRef(`package` = "builtin", type = "Any", isNullable = true, typeArgs = emptyMap()),
            ClassTypeRef(`package` = "builtin", type = "Collection", isNullable = false, typeArgs = mapOf("T" to ClassTypeRef(`package` = "class/metamodel.mm", type = "WorkItem", isNullable = false, typeArgs = emptyMap()))),
            ClassTypeRef(`package` = "class-container/metamodel.mm", type = "WorkItem", isNullable = false, typeArgs = emptyMap()),
            ClassTypeRef(`package` = "builtin", type = "int", isNullable = false, typeArgs = emptyMap()),
            ClassTypeRef(`package` = "class/metamodel.mm", type = "Sprint", isNullable = false, typeArgs = emptyMap()),
            ClassTypeRef(`package` = "class/metamodel.mm", type = "WorkItem", isNullable = false, typeArgs = emptyMap()),
            LambdaType(returnType = ClassTypeRef(`package` = "builtin", type = "boolean", isNullable = false, typeArgs = emptyMap()), parameters = listOf(Parameter(name = "param0", type = ClassTypeRef(`package` = "class/metamodel.mm", type = "WorkItem", isNullable = false, typeArgs = emptyMap()))), isNullable = false),
            ClassTypeRef(`package` = "builtin", type = "Collection", isNullable = false, typeArgs = mapOf("T" to ClassTypeRef(`package` = "class/metamodel.mm", type = "Sprint", isNullable = false, typeArgs = emptyMap()))),
            ClassTypeRef(`package` = "class-container/metamodel.mm", type = "Sprint", isNullable = false, typeArgs = emptyMap()),
            LambdaType(returnType = ClassTypeRef(`package` = "builtin", type = "int", isNullable = false, typeArgs = emptyMap()), parameters = listOf(Parameter(name = "param0", type = ClassTypeRef(`package` = "class/metamodel.mm", type = "WorkItem", isNullable = false, typeArgs = emptyMap()))), isNullable = false),
            ClassTypeRef(`package` = "builtin", type = "Collection", isNullable = false, typeArgs = mapOf("T" to ClassTypeRef(`package` = "builtin", type = "int", isNullable = false, typeArgs = emptyMap()))),
            ClassTypeRef(`package` = "builtin", type = "long", isNullable = false, typeArgs = emptyMap()),
            ClassTypeRef(`package` = "builtin", type = "List", isNullable = false, typeArgs = mapOf("T" to ClassTypeRef(`package` = "class/metamodel.mm", type = "WorkItem", isNullable = false, typeArgs = emptyMap()))),
            LambdaType(returnType = ClassTypeRef(`package` = "builtin", type = "boolean", isNullable = false, typeArgs = emptyMap()), parameters = listOf(Parameter(name = "param0", type = ClassTypeRef(`package` = "class/metamodel.mm", type = "Sprint", isNullable = false, typeArgs = emptyMap()))), isNullable = false),
        )
        return ScriptTypedAst(
            types = types,
            metamodelPath = "/metamodel.mm",
            imports = emptyList(),
            functions = listOf(
            TypedFunction(
                name = "hasNoUnassignedWorkItems",
                parameters = emptyList(),
                returnType = 2,
                body = TypedCallableBody(body = listOf(
                    TypedVariableDeclarationStatement(
                        name = "workItems", type = 5,
                        initialValue = TypedMemberCallExpression(
                            evalType = 5, expression = TypedIdentifierExpression(evalType = 6, name = "WorkItem", scope = 1),
                            member = "all", isNullChaining = false, overload = "",
                            arguments = emptyList()
                        )
                    ),
                    TypedVariableDeclarationStatement(
                        name = "unassignedWorkItems", type = 7,
                        initialValue = TypedMemberCallExpression(
                            evalType = 7, expression = TypedMemberCallExpression(
                                evalType = 5, expression = TypedIdentifierExpression(evalType = 5, name = "workItems", scope = 3),
                                member = "filter", isNullChaining = false, overload = "",
                                arguments = listOf(
                                    TypedCallArgument(value = TypedLambdaExpression(
                                        evalType = 10, parameters = listOf("wi"),
                                        body = TypedCallableBody(body = listOf(
                                                TypedReturnStatement(value = TypedBinaryExpression(
                                                        evalType = 3, operator = "==",
                                                        left = TypedMemberAccessExpression(evalType = 8, expression = TypedIdentifierExpression(evalType = 9, name = "wi", scope = 4), member = "isPlannedFor", isNullChaining = false), right = TypedNullLiteralExpression(evalType = 4)
                                                    )),
                                            ))
                                    ), parameterType = 10),
                                )
                            ),
                            member = "size", isNullChaining = false, overload = "",
                            arguments = emptyList()
                        )
                    ),
                    TypedReturnStatement(value = TypedIdentifierExpression(evalType = 7, name = "unassignedWorkItems", scope = 3)),
                ))
            ),
            TypedFunction(
                name = "hasTheAllowedMaximalNumberOfSprints",
                parameters = emptyList(),
                returnType = 2,
                body = TypedCallableBody(body = listOf(
                    TypedVariableDeclarationStatement(
                        name = "sprints", type = 11,
                        initialValue = TypedMemberCallExpression(
                            evalType = 11, expression = TypedIdentifierExpression(evalType = 12, name = "Sprint", scope = 1),
                            member = "all", isNullChaining = false, overload = "",
                            arguments = emptyList()
                        )
                    ),
                    TypedVariableDeclarationStatement(
                        name = "workItems", type = 5,
                        initialValue = TypedMemberCallExpression(
                            evalType = 5, expression = TypedIdentifierExpression(evalType = 6, name = "WorkItem", scope = 1),
                            member = "all", isNullChaining = false, overload = "",
                            arguments = emptyList()
                        )
                    ),
                    TypedVariableDeclarationStatement(
                        name = "totalEffort", type = 2,
                        initialValue = TypedMemberCallExpression(
                            evalType = 2, expression = TypedMemberCallExpression(
                                evalType = 14, expression = TypedIdentifierExpression(evalType = 5, name = "workItems", scope = 3),
                                member = "map", isNullChaining = false, overload = "",
                                arguments = listOf(
                                    TypedCallArgument(value = TypedLambdaExpression(
                                        evalType = 13, parameters = listOf("wi"),
                                        body = TypedCallableBody(body = listOf(
                                                TypedReturnStatement(value = TypedMemberAccessExpression(evalType = 7, expression = TypedIdentifierExpression(evalType = 9, name = "wi", scope = 4), member = "effort", isNullChaining = false)),
                                            ))
                                    ), parameterType = 13),
                                )
                            ),
                            member = "sum", isNullChaining = false, overload = "",
                            arguments = emptyList()
                        )
                    ),
                    TypedVariableDeclarationStatement(
                        name = "maximumVelocity", type = 7,
                        initialValue = TypedIntLiteralExpression(evalType = 7, value = "25")
                    ),
                    TypedVariableDeclarationStatement(
                        name = "desiredSprints", type = 2,
                        initialValue = TypedBinaryExpression(
                            evalType = 2, operator = "/",
                            left = TypedIdentifierExpression(evalType = 2, name = "totalEffort", scope = 3), right = TypedIdentifierExpression(evalType = 7, name = "maximumVelocity", scope = 3)
                        )
                    ),
                    TypedIfStatement(
                        condition = TypedBinaryExpression(
                            evalType = 3, operator = ">",
                            left = TypedBinaryExpression(
                                evalType = 2, operator = "-",
                                left = TypedIdentifierExpression(evalType = 2, name = "desiredSprints", scope = 3), right = TypedMemberCallExpression(
                                    evalType = 15, expression = TypedIdentifierExpression(evalType = 2, name = "desiredSprints", scope = 3),
                                    member = "floor", isNullChaining = false, overload = "",
                                    arguments = emptyList()
                                )
                            ), right = TypedDoubleLiteralExpression(evalType = 2, value = "0.5")
                        ),
                        thenBlock = listOf(
                            TypedAssignmentStatement(left = TypedIdentifierExpression(evalType = 2, name = "desiredSprints", scope = 3), right = TypedMemberCallExpression(
                                    evalType = 15, expression = TypedIdentifierExpression(evalType = 2, name = "desiredSprints", scope = 3),
                                    member = "ceil", isNullChaining = false, overload = "",
                                    arguments = emptyList()
                                )),
                        ),
                        elseIfs = emptyList(),
                        elseBlock = listOf(
                            TypedAssignmentStatement(left = TypedIdentifierExpression(evalType = 2, name = "desiredSprints", scope = 3), right = TypedMemberCallExpression(
                                    evalType = 15, expression = TypedIdentifierExpression(evalType = 2, name = "desiredSprints", scope = 3),
                                    member = "floor", isNullChaining = false, overload = "",
                                    arguments = emptyList()
                                )),
                        )
                    ),
                    TypedVariableDeclarationStatement(
                        name = "nonEmptySprints", type = 7,
                        initialValue = TypedMemberCallExpression(
                            evalType = 7, expression = TypedMemberCallExpression(
                                evalType = 11, expression = TypedIdentifierExpression(evalType = 11, name = "sprints", scope = 3),
                                member = "filter", isNullChaining = false, overload = "",
                                arguments = listOf(
                                    TypedCallArgument(value = TypedLambdaExpression(
                                        evalType = 17, parameters = listOf("sprint"),
                                        body = TypedCallableBody(body = listOf(
                                                TypedReturnStatement(value = TypedBinaryExpression(
                                                        evalType = 3, operator = ">",
                                                        left = TypedMemberCallExpression(
                                                            evalType = 7, expression = TypedMemberAccessExpression(evalType = 16, expression = TypedIdentifierExpression(evalType = 8, name = "sprint", scope = 4), member = "committedItems", isNullChaining = false),
                                                            member = "size", isNullChaining = false, overload = "",
                                                            arguments = emptyList()
                                                        ), right = TypedIntLiteralExpression(evalType = 7, value = "0")
                                                    )),
                                            ))
                                    ), parameterType = 17),
                                )
                            ),
                            member = "size", isNullChaining = false, overload = "",
                            arguments = emptyList()
                        )
                    ),
                    TypedIfStatement(
                        condition = TypedBinaryExpression(
                            evalType = 3, operator = ">",
                            left = TypedIdentifierExpression(evalType = 7, name = "nonEmptySprints", scope = 3), right = TypedIdentifierExpression(evalType = 2, name = "desiredSprints", scope = 3)
                        ),
                        thenBlock = listOf(
                            TypedReturnStatement(value = TypedBinaryExpression(
                                    evalType = 2, operator = "-",
                                    left = TypedIdentifierExpression(evalType = 2, name = "desiredSprints", scope = 3), right = TypedIdentifierExpression(evalType = 7, name = "nonEmptySprints", scope = 3)
                                )),
                        ),
                        elseIfs = emptyList(),
                        elseBlock = emptyList()
                    ),
                    TypedReturnStatement(value = TypedIntLiteralExpression(evalType = 7, value = "0")),
                ))
            )
            )
        )
    }

    private fun buildObjectivesAst(): ScriptTypedAst {
        val types: List<ReturnType> = listOf(
            VoidType(),
            ClassTypeRef(`package` = "builtin", type = "string", isNullable = false, typeArgs = emptyMap()),
            ClassTypeRef(`package` = "builtin", type = "double", isNullable = false, typeArgs = emptyMap()),
            ClassTypeRef(`package` = "builtin", type = "boolean", isNullable = false, typeArgs = emptyMap()),
            ClassTypeRef(`package` = "builtin", type = "Any", isNullable = true, typeArgs = emptyMap()),
            ClassTypeRef(`package` = "builtin", type = "Collection", isNullable = false, typeArgs = mapOf("T" to ClassTypeRef(`package` = "class/metamodel.mm", type = "Sprint", isNullable = false, typeArgs = emptyMap()))),
            ClassTypeRef(`package` = "class-container/metamodel.mm", type = "Sprint", isNullable = false, typeArgs = emptyMap()),
            ClassTypeRef(`package` = "builtin", type = "Collection", isNullable = false, typeArgs = mapOf("T" to ClassTypeRef(`package` = "class/metamodel.mm", type = "Stakeholder", isNullable = false, typeArgs = emptyMap()))),
            ClassTypeRef(`package` = "class-container/metamodel.mm", type = "Stakeholder", isNullable = false, typeArgs = emptyMap()),
            ClassTypeRef(`package` = "builtin", type = "Collection", isNullable = false, typeArgs = mapOf("T" to ClassTypeRef(`package` = "builtin", type = "double", isNullable = false, typeArgs = emptyMap()))),
            ClassTypeRef(`package` = "builtin", type = "int", isNullable = false, typeArgs = emptyMap()),
            ClassTypeRef(`package` = "class/metamodel.mm", type = "WorkItem", isNullable = false, typeArgs = emptyMap()),
            LambdaType(returnType = ClassTypeRef(`package` = "builtin", type = "int", isNullable = false, typeArgs = emptyMap()), parameters = listOf(Parameter(name = "param0", type = ClassTypeRef(`package` = "class/metamodel.mm", type = "WorkItem", isNullable = false, typeArgs = emptyMap()))), isNullable = false),
            ClassTypeRef(`package` = "builtin", type = "Collection", isNullable = false, typeArgs = mapOf("T" to ClassTypeRef(`package` = "builtin", type = "int", isNullable = false, typeArgs = emptyMap()))),
            ClassTypeRef(`package` = "class/metamodel.mm", type = "Stakeholder", isNullable = false, typeArgs = emptyMap()),
            LambdaType(returnType = ClassTypeRef(`package` = "builtin", type = "boolean", isNullable = false, typeArgs = emptyMap()), parameters = listOf(Parameter(name = "param0", type = ClassTypeRef(`package` = "class/metamodel.mm", type = "WorkItem", isNullable = false, typeArgs = emptyMap()))), isNullable = false),
            ClassTypeRef(`package` = "builtin", type = "Collection", isNullable = false, typeArgs = mapOf("T" to ClassTypeRef(`package` = "class/metamodel.mm", type = "WorkItem", isNullable = false, typeArgs = emptyMap()))),
            ClassTypeRef(`package` = "builtin", type = "List", isNullable = false, typeArgs = mapOf("T" to ClassTypeRef(`package` = "class/metamodel.mm", type = "WorkItem", isNullable = false, typeArgs = emptyMap()))),
            ClassTypeRef(`package` = "class/metamodel.mm", type = "Sprint", isNullable = false, typeArgs = emptyMap()),
            LambdaType(returnType = ClassTypeRef(`package` = "builtin", type = "double", isNullable = false, typeArgs = emptyMap()), parameters = listOf(Parameter(name = "param0", type = ClassTypeRef(`package` = "class/metamodel.mm", type = "Sprint", isNullable = false, typeArgs = emptyMap()))), isNullable = false),
            LambdaType(returnType = ClassTypeRef(`package` = "builtin", type = "double", isNullable = false, typeArgs = emptyMap()), parameters = listOf(Parameter(name = "param0", type = ClassTypeRef(`package` = "class/metamodel.mm", type = "Stakeholder", isNullable = false, typeArgs = emptyMap()))), isNullable = false),
        )
        return ScriptTypedAst(
            types = types,
            metamodelPath = "/metamodel.mm",
            imports = listOf(TypedImport(name = "stddev", ref = "stddev", uri = "/script/stddev.fn")),
            functions = listOf(
            TypedFunction(
                name = "minimiseCustomerSatisfactionIndex",
                parameters = emptyList(),
                returnType = 2,
                body = TypedCallableBody(body = listOf(
                    TypedVariableDeclarationStatement(
                        name = "sprints", type = 5,
                        initialValue = TypedMemberCallExpression(
                            evalType = 5, expression = TypedIdentifierExpression(evalType = 6, name = "Sprint", scope = 1),
                            member = "all", isNullChaining = false, overload = "",
                            arguments = emptyList()
                        )
                    ),
                    TypedVariableDeclarationStatement(
                        name = "stakeholders", type = 7,
                        initialValue = TypedMemberCallExpression(
                            evalType = 7, expression = TypedIdentifierExpression(evalType = 8, name = "Stakeholder", scope = 1),
                            member = "all", isNullChaining = false, overload = "",
                            arguments = emptyList()
                        )
                    ),
                    TypedVariableDeclarationStatement(
                        name = "stakeholderImportanceSprintDeviation", type = 9,
                        initialValue = TypedMemberCallExpression(
                            evalType = 9, expression = TypedIdentifierExpression(evalType = 7, name = "stakeholders", scope = 3),
                            member = "map", isNullChaining = false, overload = "",
                            arguments = listOf(
                                TypedCallArgument(value = TypedLambdaExpression(
                                    evalType = 20, parameters = listOf("stakeholder"),
                                    body = TypedCallableBody(body = listOf(
                                            TypedVariableDeclarationStatement(
                                                name = "effortAcrossSprints", type = 9,
                                                initialValue = TypedMemberCallExpression(
                                                    evalType = 9, expression = TypedIdentifierExpression(evalType = 5, name = "sprints", scope = 3),
                                                    member = "map", isNullChaining = false, overload = "",
                                                    arguments = listOf(
                                                        TypedCallArgument(value = TypedLambdaExpression(
                                                            evalType = 19, parameters = listOf("sprint"),
                                                            body = TypedCallableBody(body = listOf(
                                                                    TypedReturnStatement(value = TypedMemberCallExpression(
                                                                            evalType = 2, expression = TypedMemberCallExpression(
                                                                                evalType = 13, expression = TypedMemberCallExpression(
                                                                                    evalType = 16, expression = TypedMemberAccessExpression(evalType = 17, expression = TypedIdentifierExpression(evalType = 18, name = "sprint", scope = 6), member = "committedItems", isNullChaining = false),
                                                                                    member = "filter", isNullChaining = false, overload = "",
                                                                                    arguments = listOf(
                                                                                        TypedCallArgument(value = TypedLambdaExpression(
                                                                                            evalType = 15, parameters = listOf("item"),
                                                                                            body = TypedCallableBody(body = listOf(
                                                                                                    TypedReturnStatement(value = TypedBinaryExpression(
                                                                                                            evalType = 3, operator = "===",
                                                                                                            left = TypedMemberAccessExpression(evalType = 14, expression = TypedIdentifierExpression(evalType = 11, name = "item", scope = 8), member = "stakeholder", isNullChaining = false), right = TypedIdentifierExpression(evalType = 14, name = "stakeholder", scope = 4)
                                                                                                        )),
                                                                                                ))
                                                                                        ), parameterType = 15),
                                                                                    )
                                                                                ),
                                                                                member = "map", isNullChaining = false, overload = "",
                                                                                arguments = listOf(
                                                                                    TypedCallArgument(value = TypedLambdaExpression(
                                                                                        evalType = 12, parameters = listOf("item"),
                                                                                        body = TypedCallableBody(body = listOf(
                                                                                                TypedReturnStatement(value = TypedMemberAccessExpression(evalType = 10, expression = TypedIdentifierExpression(evalType = 11, name = "item", scope = 8), member = "importance", isNullChaining = false)),
                                                                                            ))
                                                                                    ), parameterType = 12),
                                                                                )
                                                                            ),
                                                                            member = "sum", isNullChaining = false, overload = "",
                                                                            arguments = emptyList()
                                                                        )),
                                                                ))
                                                        ), parameterType = 19),
                                                    )
                                                )
                                            ),
                                            TypedReturnStatement(value = TypedFunctionCallExpression(evalType = 2, name = "stddev", overload = "", arguments = listOf(
                                                        TypedCallArgument(value = TypedIdentifierExpression(evalType = 9, name = "effortAcrossSprints", scope = 5), parameterType = 9),
                                                    ))),
                                        ))
                                ), parameterType = 20),
                            )
                        )
                    ),
                    TypedVariableDeclarationStatement(
                        name = "importanceStandardDeviation", type = 2,
                        initialValue = TypedFunctionCallExpression(evalType = 2, name = "stddev", overload = "", arguments = listOf(
                                TypedCallArgument(value = TypedIdentifierExpression(evalType = 9, name = "stakeholderImportanceSprintDeviation", scope = 3), parameterType = 9),
                            ))
                    ),
                    TypedReturnStatement(value = TypedIdentifierExpression(evalType = 2, name = "importanceStandardDeviation", scope = 3)),
                ))
            ),
            TypedFunction(
                name = "minimiseSprintEffortDeviation",
                parameters = emptyList(),
                returnType = 2,
                body = TypedCallableBody(body = listOf(
                    TypedVariableDeclarationStatement(
                        name = "sprints", type = 5,
                        initialValue = TypedMemberCallExpression(
                            evalType = 5, expression = TypedIdentifierExpression(evalType = 6, name = "Sprint", scope = 1),
                            member = "all", isNullChaining = false, overload = "",
                            arguments = emptyList()
                        )
                    ),
                    TypedVariableDeclarationStatement(
                        name = "fitness", type = 9,
                        initialValue = TypedMemberCallExpression(
                            evalType = 9, expression = TypedIdentifierExpression(evalType = 5, name = "sprints", scope = 3),
                            member = "map", isNullChaining = false, overload = "",
                            arguments = listOf(
                                TypedCallArgument(value = TypedLambdaExpression(
                                    evalType = 19, parameters = listOf("sprint"),
                                    body = TypedCallableBody(body = listOf(
                                            TypedReturnStatement(value = TypedMemberCallExpression(
                                                    evalType = 2, expression = TypedMemberCallExpression(
                                                        evalType = 13, expression = TypedMemberAccessExpression(evalType = 17, expression = TypedIdentifierExpression(evalType = 18, name = "sprint", scope = 4), member = "committedItems", isNullChaining = false),
                                                        member = "map", isNullChaining = false, overload = "",
                                                        arguments = listOf(
                                                            TypedCallArgument(value = TypedLambdaExpression(
                                                                evalType = 12, parameters = listOf("item"),
                                                                body = TypedCallableBody(body = listOf(
                                                                        TypedReturnStatement(value = TypedMemberAccessExpression(evalType = 10, expression = TypedIdentifierExpression(evalType = 11, name = "item", scope = 6), member = "effort", isNullChaining = false)),
                                                                    ))
                                                            ), parameterType = 12),
                                                        )
                                                    ),
                                                    member = "sum", isNullChaining = false, overload = "",
                                                    arguments = emptyList()
                                                )),
                                        ))
                                ), parameterType = 19),
                            )
                        )
                    ),
                    TypedVariableDeclarationStatement(
                        name = "effortStandardDeviation", type = 2,
                        initialValue = TypedFunctionCallExpression(evalType = 2, name = "stddev", overload = "", arguments = listOf(
                                TypedCallArgument(value = TypedIdentifierExpression(evalType = 9, name = "fitness", scope = 3), parameterType = 9),
                            ))
                    ),
                    TypedReturnStatement(value = TypedIdentifierExpression(evalType = 2, name = "effortStandardDeviation", scope = 3)),
                ))
            )
            )
        )
    }

    private fun buildStddevAst(): ScriptTypedAst {
        val types: List<ReturnType> = listOf(
            VoidType(),
            ClassTypeRef(`package` = "builtin", type = "string", isNullable = false, typeArgs = emptyMap()),
            ClassTypeRef(`package` = "builtin", type = "double", isNullable = false, typeArgs = emptyMap()),
            ClassTypeRef(`package` = "builtin", type = "boolean", isNullable = false, typeArgs = emptyMap()),
            ClassTypeRef(`package` = "builtin", type = "Any", isNullable = true, typeArgs = emptyMap()),
            ClassTypeRef(`package` = "builtin", type = "Collection", isNullable = false, typeArgs = mapOf("T" to ClassTypeRef(`package` = "builtin", type = "double", isNullable = false, typeArgs = emptyMap()))),
            ClassTypeRef(`package` = "builtin", type = "int", isNullable = false, typeArgs = emptyMap()),
            ClassTypeRef(`package` = "builtin", type = "List", isNullable = false, typeArgs = mapOf("T" to ClassTypeRef(`package` = "builtin", type = "double", isNullable = false, typeArgs = emptyMap()))),
        )
        return ScriptTypedAst(
            types = types,
            
            imports = emptyList(),
            functions = listOf(
            TypedFunction(
                name = "mean",
                parameters = listOf(TypedParameter(name = "values", type = 5)),
                returnType = 2,
                body = TypedCallableBody(body = listOf(
                    TypedVariableDeclarationStatement(
                        name = "sum", type = 2,
                        initialValue = TypedIntLiteralExpression(evalType = 6, value = "0")
                    ),
                    TypedForStatement(
                        variableName = "entry", variableType = 2,
                        iterable = TypedIdentifierExpression(evalType = 5, name = "values", scope = 2),
                        body = listOf(
                            TypedAssignmentStatement(left = TypedIdentifierExpression(evalType = 2, name = "sum", scope = 3), right = TypedBinaryExpression(
                                    evalType = 2, operator = "+",
                                    left = TypedIdentifierExpression(evalType = 2, name = "sum", scope = 3), right = TypedIdentifierExpression(evalType = 2, name = "entry", scope = 4)
                                )),
                        )
                    ),
                    TypedReturnStatement(value = TypedBinaryExpression(
                            evalType = 2, operator = "/",
                            left = TypedIdentifierExpression(evalType = 2, name = "sum", scope = 3), right = TypedMemberCallExpression(
                                evalType = 6, expression = TypedIdentifierExpression(evalType = 5, name = "values", scope = 2),
                                member = "size", isNullChaining = false, overload = "",
                                arguments = emptyList()
                            )
                        )),
                ))
            ),
            TypedFunction(
                name = "mean2",
                parameters = listOf(TypedParameter(name = "values", type = 7)),
                returnType = 2,
                body = TypedCallableBody(body = listOf(
                    TypedVariableDeclarationStatement(
                        name = "sum", type = 2,
                        initialValue = TypedIntLiteralExpression(evalType = 6, value = "0")
                    ),
                    TypedForStatement(
                        variableName = "entry", variableType = 2,
                        iterable = TypedIdentifierExpression(evalType = 7, name = "values", scope = 2),
                        body = listOf(
                            TypedAssignmentStatement(left = TypedIdentifierExpression(evalType = 2, name = "sum", scope = 3), right = TypedBinaryExpression(
                                    evalType = 2, operator = "+",
                                    left = TypedIdentifierExpression(evalType = 2, name = "sum", scope = 3), right = TypedIdentifierExpression(evalType = 2, name = "entry", scope = 4)
                                )),
                        )
                    ),
                    TypedReturnStatement(value = TypedBinaryExpression(
                            evalType = 2, operator = "/",
                            left = TypedIdentifierExpression(evalType = 2, name = "sum", scope = 3), right = TypedMemberCallExpression(
                                evalType = 6, expression = TypedIdentifierExpression(evalType = 7, name = "values", scope = 2),
                                member = "size", isNullChaining = false, overload = "",
                                arguments = emptyList()
                            )
                        )),
                ))
            ),
            TypedFunction(
                name = "stddev",
                parameters = listOf(TypedParameter(name = "values", type = 5)),
                returnType = 2,
                body = TypedCallableBody(body = listOf(
                    TypedIfStatement(
                        condition = TypedBinaryExpression(
                            evalType = 3, operator = "<=",
                            left = TypedMemberCallExpression(
                                evalType = 6, expression = TypedIdentifierExpression(evalType = 5, name = "values", scope = 2),
                                member = "size", isNullChaining = false, overload = "",
                                arguments = emptyList()
                            ), right = TypedIntLiteralExpression(evalType = 6, value = "1")
                        ),
                        thenBlock = listOf(
                            TypedReturnStatement(value = TypedIntLiteralExpression(evalType = 6, value = "0")),
                        ),
                        elseIfs = emptyList(),
                        elseBlock = emptyList()
                    ),
                    TypedVariableDeclarationStatement(
                        name = "avg", type = 2,
                        initialValue = TypedFunctionCallExpression(evalType = 2, name = "mean", overload = "", arguments = listOf(
                                TypedCallArgument(value = TypedIdentifierExpression(evalType = 5, name = "values", scope = 2), parameterType = 5),
                            ))
                    ),
                    TypedVariableDeclarationStatement(
                        name = "sum", type = 2,
                        initialValue = TypedIntLiteralExpression(evalType = 6, value = "0")
                    ),
                    TypedForStatement(
                        variableName = "entry", variableType = 2,
                        iterable = TypedIdentifierExpression(evalType = 5, name = "values", scope = 2),
                        body = listOf(
                            TypedVariableDeclarationStatement(
                                name = "diff", type = 2,
                                initialValue = TypedBinaryExpression(
                                    evalType = 2, operator = "-",
                                    left = TypedIdentifierExpression(evalType = 2, name = "entry", scope = 4), right = TypedIdentifierExpression(evalType = 2, name = "avg", scope = 3)
                                )
                            ),
                            TypedAssignmentStatement(left = TypedIdentifierExpression(evalType = 2, name = "sum", scope = 3), right = TypedBinaryExpression(
                                    evalType = 2, operator = "+",
                                    left = TypedIdentifierExpression(evalType = 2, name = "sum", scope = 3), right = TypedBinaryExpression(
                                        evalType = 2, operator = "*",
                                        left = TypedIdentifierExpression(evalType = 2, name = "diff", scope = 5), right = TypedIdentifierExpression(evalType = 2, name = "diff", scope = 5)
                                    )
                                )),
                        )
                    ),
                    TypedVariableDeclarationStatement(
                        name = "variance", type = 2,
                        initialValue = TypedBinaryExpression(
                            evalType = 2, operator = "/",
                            left = TypedIdentifierExpression(evalType = 2, name = "sum", scope = 3), right = TypedBinaryExpression(
                                evalType = 6, operator = "-",
                                left = TypedMemberCallExpression(
                                    evalType = 6, expression = TypedIdentifierExpression(evalType = 5, name = "values", scope = 2),
                                    member = "size", isNullChaining = false, overload = "",
                                    arguments = emptyList()
                                ), right = TypedIntLiteralExpression(evalType = 6, value = "1")
                            )
                        )
                    ),
                    TypedReturnStatement(value = TypedMemberCallExpression(
                            evalType = 2, expression = TypedIdentifierExpression(evalType = 2, name = "variance", scope = 3),
                            member = "sqrt", isNullChaining = false, overload = "",
                            arguments = emptyList()
                        )),
                ))
            )
            )
        )
    }

    private fun buildOptimizationConfig(): OptimizationConfig = OptimizationConfig(
        problem = ProblemConfig(
            metamodelPath = "/metamodel.mm",
            modelPath = "/model/model.m"
        ),
        goal = GoalConfig(
            objectives = listOf(
                ObjectiveConfig(
                    type = ObjectiveTendency.MINIMIZE,
                    path = "/script/objectives.fn",
                    functionName = "minimiseCustomerSatisfactionIndex"
                ),
                ObjectiveConfig(
                    type = ObjectiveTendency.MINIMIZE,
                    path = "/script/objectives.fn",
                    functionName = "minimiseSprintEffortDeviation"
                )
            ),
            constraints = listOf(
                ConstraintConfig(
                    path = "/script/constraints.fn",
                    functionName = "hasNoUnassignedWorkItems"
                ),
                ConstraintConfig(
                    path = "/script/constraints.fn",
                    functionName = "hasTheAllowedMaximalNumberOfSprints"
                )
            )
        ),
        search = SearchConfig(
            mutations = MutationsConfig(
                usingPaths = listOf(
                    "/transformation/createSprint.mt",
                    "/transformation/addItemToSprint.mt",
                    "/transformation/deleteSprint.mt",
                    "/transformation/moveItemBetweenSprints.mt"
                )
            )
        ),
        solver = SolverConfig(
            provider = SolverProvider.MOEA,
            algorithm = AlgorithmType.NSGAII,
            parameters = AlgorithmParameters(
                population = 40,
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
    fun `full scrum optimization end to end`() {
        val metamodelData = buildMetamodelData()
        val modelData = buildModelData()
        val transformations = buildTransformations()

        val compiledProgram = ScriptCompiler().compile(
            CompilationInput(mapOf(
                "/script/stddev.fn" to buildStddevAst(),
                "/script/constraints.fn" to buildConstraintsAst(),
                "/script/objectives.fn" to buildObjectivesAst()
            )),
            metamodelData
        )
        val environment = ExecutionEnvironment(compiledProgram)
        val metamodel = compiledProgram.metamodel ?: error("No metamodel in compiled program")

        val config = buildOptimizationConfig()

        val objectives = listOf("minimiseCustomerSatisfactionIndex", "minimiseSprintEffortDeviation").map { fnName ->
            ScriptGuidanceFunction(
                environment.scriptProgramClass,
                compiledProgram.functionLookup["/script/objectives.fn"]!![fnName]!!,
                System.out,
                "/script/objectives.fn::$fnName"
            )
        }
        val constraints = listOf("hasNoUnassignedWorkItems", "hasTheAllowedMaximalNumberOfSprints").map { fnName ->
            ScriptGuidanceFunction(
                environment.scriptProgramClass,
                compiledProgram.functionLookup["/script/constraints.fn"]!![fnName]!!,
                System.out,
                "/script/constraints.fn::$fnName"
            )
        }

        val initialSolutionProvider: () -> Solution = {
            val modelGraph = createModelGraph(modelData, metamodel)
            Solution(modelGraph)
        }

        val mutationStrategy = MutationStrategyFactory.create(
            config.solver.parameters.mutation,
            transformations.keys.sorted().mapIndexed { idx, path ->
                TransformationOperator(id = idx, ast = transformations[path]!!)
            }
        )
        val evaluator = LocalMutationEvaluator(
            initialSolutionProvider = initialSolutionProvider,
            mutationStrategy = mutationStrategy,
            objectives = objectives,
            constraints = constraints,
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
            "Scrum optimization completed ($backendName): elapsed=${elapsedMs}ms (${String.format("%.2f", elapsedSec)}s), " +
                "population=80, evolutions=500"
        )
    }
}
