package com.mdeo.modeltransformation.runtime

import com.mdeo.expression.ast.expressions.TypedBinaryExpression
import com.mdeo.expression.ast.expressions.TypedIdentifierExpression
import com.mdeo.expression.ast.expressions.TypedMemberAccessExpression
import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.expression.ast.types.ReturnType
import com.mdeo.metamodel.Metamodel
import com.mdeo.metamodel.data.ModelData
import com.mdeo.metamodel.data.ModelDataInstance
import com.mdeo.metamodel.data.ModelDataLink
import com.mdeo.metamodel.data.ModelDataPropertyValue
import com.mdeo.modeltransformation.ast.TypedAst
import com.mdeo.modeltransformation.ast.patterns.TypedPattern
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import kotlin.test.assertIs
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * A transformation written for an imported metamodel runs as an operator on a model of the
 * metamodel that imports it (layer 2 inside layer 3, stage 0 of callable transformations).
 *
 * The operator below is typed against `/arch/base.mm` (its `using` metamodel), but the model is a
 * `/arch/shop.mm` model, whose classes extend the base classes. The engine builds its type
 * registry from the model's merged metamodel, where every base class keeps the path of its own
 * file, so the operator's types resolve, and its class patterns match the subclasses of shop.mm.
 *
 * ```
 * // operator, using "base.mm"
 * match {
 *     comp: Component { }
 *     from: Container { }
 *     to: Container { }
 *     where from.name != to.name
 *     delete comp.host -- from
 *     create comp.host -- to
 * }
 * ```
 */
class LayeredMetamodelOperatorTest {

    private val base = "class${LayeredTestMetamodel.BASE_PATH}"

    // 0: string, 1: boolean, 2: Container (base.mm)
    private val types: List<ReturnType> = listOf(
        ClassTypeRef("builtin", "string", false),
        ClassTypeRef("builtin", "boolean", false),
        ClassTypeRef(base, "Container", false)
    )

    private fun instance(name: String, className: String? = null, modifier: String? = null) =
        TypedPatternObjectInstanceElement(objectInstance = TypedPatternObjectInstance(modifier, name, className, emptyList()))

    private fun link(modifier: String?, comp: String, container: String) = TypedPatternLinkElement(
        link = TypedPatternLink(modifier, TypedPatternLinkEnd(comp, "host"), TypedPatternLinkEnd(container, "components"))
    )

    private fun nameOf(instance: String) = TypedMemberAccessExpression(
        evalType = 0,
        expression = TypedIdentifierExpression(evalType = 2, name = instance, scope = 1),
        member = "name",
        isNullChaining = false
    )

    private val moveComponent = TypedAst(
        types = types,
        metamodelPath = LayeredTestMetamodel.BASE_PATH,
        statements = listOf(
            TypedMatchStatement(
                pattern = TypedPattern(
                    listOf(
                        instance("comp", "Component"),
                        instance("from", "Container"),
                        instance("to", "Container"),
                        TypedPatternWhereClauseElement(
                            whereClause = TypedWhereClause(TypedBinaryExpression(evalType = 1, operator = "!=", left = nameOf("from"), right = nameOf("to")))
                        ),
                        link("delete", "comp", "from"),
                        link("create", "comp", "to")
                    )
                )
            )
        )
    )

    private val model = ModelData(
        metamodelPath = LayeredTestMetamodel.SHOP_PATH,
        instances = listOf(
            ModelDataInstance("orders", "Service", mapOf("name" to ModelDataPropertyValue.StringValue("orders"))),
            ModelDataInstance("db", "Store", mapOf("name" to ModelDataPropertyValue.StringValue("db"))),
            ModelDataInstance("a", "Server", mapOf("name" to ModelDataPropertyValue.StringValue("a"))),
            ModelDataInstance("b", "Server", mapOf("name" to ModelDataPropertyValue.StringValue("b")))
        ),
        links = listOf(
            ModelDataLink("orders", "host", "a", "components"),
            ModelDataLink("orders", "store", "db", null)
        )
    )

    private fun graph(backend: String): ModelGraph {
        val metamodel = Metamodel.compile(LayeredTestMetamodel.shop)
        return when (backend) {
            "tinker" -> TinkerModelGraph.create(model, metamodel)
            else -> MdeoModelGraph.create(model, metamodel)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["tinker", "mdeo"])
    fun `a base metamodel operator moves a component of the extending metamodel`(backend: String) {
        graph(backend).use { graph ->
            val result = TransformationEngine.create(graph, moveComponent, deterministic = false).execute()

            assertIs<TransformationExecutionResult.Success>(result)
            val after = graph.toModelData()
            val host = after.links.single { it.sourceName == "orders" && it.sourceProperty == "host" }
            assertEquals("b", host.targetName)
            assertTrue(after.links.any { it.sourceName == "orders" && it.targetName == "db" }, "the layer-3 link stays")
        }
    }
}
