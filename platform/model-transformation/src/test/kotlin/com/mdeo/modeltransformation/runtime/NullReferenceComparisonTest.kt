package com.mdeo.modeltransformation.runtime

import com.mdeo.expression.ast.expressions.TypedBinaryExpression
import com.mdeo.expression.ast.expressions.TypedIdentifierExpression
import com.mdeo.expression.ast.expressions.TypedMemberAccessExpression
import com.mdeo.expression.ast.expressions.TypedNullLiteralExpression
import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.expression.ast.types.VoidType
import com.mdeo.metamodel.Metamodel
import com.mdeo.metamodel.data.AssociationData
import com.mdeo.metamodel.data.AssociationEndData
import com.mdeo.metamodel.data.ClassData
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.metamodel.data.ModelData
import com.mdeo.metamodel.data.ModelDataInstance
import com.mdeo.metamodel.data.ModelDataLink
import com.mdeo.metamodel.data.MultiplicityData
import com.mdeo.modeltransformation.ast.TypedAst
import com.mdeo.modeltransformation.ast.patterns.TypedPattern
import com.mdeo.modeltransformation.ast.patterns.TypedPatternElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLink
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLinkElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLinkEnd
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstance
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstanceElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternWhereClauseElement
import com.mdeo.modeltransformation.ast.patterns.TypedWhereClause
import com.mdeo.modeltransformation.ast.statements.TypedMatchStatement
import com.mdeo.modeltransformation.graph.ModelGraph
import com.mdeo.modeltransformation.graph.mdeo.MdeoModelGraph
import com.mdeo.modeltransformation.graph.tinker.TinkerModelGraph
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * `x.ref == null` and `x.ref != null` on a single-valued reference, as the language compiles them:
 * an unset reference compares equal to `null` instead of leaving nothing to compare.
 */
class NullReferenceComparisonTest {

    enum class Backend {
        MDEO, TINKER;

        fun graph(model: ModelData, metamodel: Metamodel): ModelGraph = when (this) {
            MDEO -> MdeoModelGraph.create(model, metamodel)
            TINKER -> TinkerModelGraph.create(model, metamodel)
        }
    }

    /** `Owner.mentor[?] --> Owner`. */
    private val metamodelData = MetamodelData(
        path = "/test.mm",
        classes = listOf(ClassData("Owner", false)),
        associations = listOf(
            AssociationData(AssociationEndData("Owner", "mentor", MultiplicityData(0, 1)), "-->", AssociationEndData("Owner", null, MultiplicityData(0, -1)))
        )
    )

    private val types = listOf(
        VoidType(),
        ClassTypeRef(`package` = "builtin", type = "string", isNullable = false),
        ClassTypeRef(`package` = "builtin", type = "double", isNullable = false),
        ClassTypeRef(`package` = "builtin", type = "boolean", isNullable = false),
        ClassTypeRef(`package` = "builtin", type = "Any", isNullable = true),
        ClassTypeRef(`package` = "class/test.mm", type = "Owner", isNullable = true),
        ClassTypeRef(`package` = "class/test.mm", type = "Owner", isNullable = false)
    )

    private fun owner(name: String) = TypedPatternObjectInstanceElement(
        objectInstance = TypedPatternObjectInstance(modifier = null, name = name, className = "Owner", properties = emptyList())
    )

    /** `where [name].mentor [operator] null`. */
    private fun mentorIs(name: String, operator: String) = TypedPatternWhereClauseElement(
        whereClause = TypedWhereClause(
            expression = TypedBinaryExpression(
                evalType = 3,
                operator = operator,
                left = TypedMemberAccessExpression(
                    evalType = 5,
                    expression = TypedIdentifierExpression(evalType = 6, name = name, scope = 1),
                    member = "mentor",
                    isNullChaining = false
                ),
                right = TypedNullLiteralExpression(evalType = 4)
            )
        )
    )

    private fun createMentor(source: String, target: String) = TypedPatternLinkElement(
        link = TypedPatternLink(
            modifier = "create",
            source = TypedPatternLinkEnd(objectName = source, propertyName = "mentor"),
            target = TypedPatternLinkEnd(objectName = target, propertyName = null)
        )
    )

    private fun run(backend: Backend, model: ModelData, elements: List<TypedPatternElement>): Pair<TransformationExecutionResult, ModelData> {
        val ast = TypedAst(types = types, metamodelPath = "/test.mm", statements = listOf(TypedMatchStatement(pattern = TypedPattern(elements = elements))))
        return backend.graph(model, Metamodel.compile(metamodelData)).use { graph ->
            TransformationEngine.create(graph, ast, deterministic = false).execute() to graph.toModelData()
        }
    }

    private fun model(vararg mentors: Pair<String, String>) = ModelData(
        "/test.mm",
        listOf("a", "b", "c").map { ModelDataInstance(it, "Owner", emptyMap()) },
        mentors.map { (s, t) -> ModelDataLink(s, "mentor", t, null) }
    )

    @ParameterizedTest
    @EnumSource(Backend::class)
    fun `an unset reference equals null`(backend: Backend) {
        repeat(20) {
            val (result, after) = run(backend, model("a" to "b"), listOf(owner("source"), owner("target"), mentorIs("source", "=="), createMentor("source", "target")))
            assertIs<TransformationExecutionResult.Success>(result)
            // a already has a mentor, so only b or c can have gained one
            assertEquals(1, after.links.count { it.sourceName == "a" })
            assertEquals(2, after.links.size)
        }
    }

    @ParameterizedTest
    @EnumSource(Backend::class)
    fun `a set reference is not null`(backend: Backend) {
        assertIs<TransformationExecutionResult.Failure>(run(backend, model(), listOf(owner("source"), mentorIs("source", "!="))).first)
        assertIs<TransformationExecutionResult.Success>(run(backend, model("c" to "a"), listOf(owner("source"), mentorIs("source", "!="))).first)
        assertIs<TransformationExecutionResult.Failure>(
            run(backend, model("a" to "b", "b" to "c", "c" to "a"), listOf(owner("source"), mentorIs("source", "=="))).first
        )
    }
}
