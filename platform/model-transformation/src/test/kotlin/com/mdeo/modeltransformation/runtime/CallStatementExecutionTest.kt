package com.mdeo.modeltransformation.runtime

import com.mdeo.expression.ast.expressions.TypedExpression
import com.mdeo.modeltransformation.ast.expressions.TypedExpressionSerializer
import com.mdeo.metamodel.Metamodel
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.metamodel.data.ModelData
import com.mdeo.metamodel.data.ModelDataInstance
import com.mdeo.metamodel.data.ModelDataLink
import com.mdeo.metamodel.data.ModelDataPropertyValue
import com.mdeo.metamodel.data.merge
import com.mdeo.modeltransformation.ast.TypedAst
import com.mdeo.modeltransformation.ast.patterns.TypedPatternElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternElementSerializer
import com.mdeo.modeltransformation.ast.statements.TypedCallStatement
import com.mdeo.modeltransformation.ast.statements.TypedTransformationStatement
import com.mdeo.modeltransformation.ast.statements.TypedTransformationStatementSerializer
import com.mdeo.modeltransformation.graph.ModelGraph
import com.mdeo.modeltransformation.graph.mdeo.MdeoModelGraph
import com.mdeo.modeltransformation.graph.tinker.TinkerModelGraph
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertIs

/**
 * Execution of callable transformations (`call name(...) -> (...)`), on typed ASTs that the
 * transformation language's converter produced from the `.mt` sources of
 * `app/packages/service-model-transformation/test/fixtures.ts` (resources `calls/`, kept up to
 * date by that package's `engineFixtures.test.ts`).
 *
 * The metamodel is layered: `shop.mm` (layer 3: services, copy feeds) imports `base.mm` (layer 2:
 * components on containers, placements). The callees `moveComponent` and `noPlacement` are
 * written for `base.mm` and run inside `shop.mm` operators on a `shop.mm` model.
 */
class CallStatementExecutionTest {

    private val json = Json {
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            contextual(TypedExpression::class, TypedExpressionSerializer)
            contextual(TypedTransformationStatement::class, TypedTransformationStatementSerializer)
            contextual(TypedPatternElement::class, TypedPatternElementSerializer)
        }
    }

    private fun resource(name: String): String =
        javaClass.getResource("/calls/$name")?.readText() ?: error("missing test resource calls/$name")

    private fun transformation(path: String): TypedAst =
        json.decodeFromString(TypedAst.serializer(), resource(path.trimStart('/').replace("/", "__") + ".json"))

    private val metamodelData: MetamodelData = MetamodelData.merge(
        json.decodeFromString(MetamodelData.serializer(), resource("metamodel__shop.mm.json")),
        listOf(json.decodeFromString(MetamodelData.serializer(), resource("metamodel__base.mm.json")))
    )

    private fun named(name: String, className: String) =
        ModelDataInstance(name, className, mapOf("name" to ModelDataPropertyValue.StringValue(name)))

    /**
     * Three containers; `orders` (a service on `a`) reads a copy kept by `ordersDb` (on `a`) and
     * may move to `b`; `billing` sits on `b`.
     */
    private fun shopModel(storeHost: String = "a", candidates: List<String> = listOf("b")) = ModelData(
        metamodelPath = "/metamodel/shop.mm",
        instances = listOf(
            named("a", "Container"),
            named("b", "Container"),
            named("c", "Container"),
            named("orders", "Service"),
            named("ordersDb", "Component"),
            named("billing", "Component"),
            ModelDataInstance("placement", "Placement", emptyMap()),
            ModelDataInstance("feed", "CopyFeed", emptyMap())
        ),
        links = listOf(
            ModelDataLink("orders", "host", "a", null),
            ModelDataLink("ordersDb", "host", storeHost, null),
            ModelDataLink("billing", "host", "b", null),
            ModelDataLink("placement", "component", "orders", null),
            ModelDataLink("feed", "reader", "orders", null),
            ModelDataLink("feed", "store", "ordersDb", null)
        ) + candidates.map { ModelDataLink("placement", "candidates", it, null) }
    )

    private fun graph(backend: String, model: ModelData = shopModel()): ModelGraph {
        val metamodel = Metamodel.compile(metamodelData)
        return if (backend == "tinker") TinkerModelGraph.create(model, metamodel) else MdeoModelGraph.create(model, metamodel)
    }

    private fun run(graph: ModelGraph, path: String): TransformationExecutionResult =
        TransformationEngine.create(graph, transformation(path), deterministic = false).execute()

    private fun ModelData.hostOf(component: String): String? =
        links.singleOrNull { it.sourceName == component && it.sourceProperty == "host" }?.targetName

    private fun ModelData.nameOf(instance: String): String? =
        (instances.single { it.name == instance }.properties["name"] as? ModelDataPropertyValue.StringValue)?.value

    @Test
    fun `the bundle holds every callee under its path`() {
        val ast = transformation("/optimize/move-with-stores.mt")
        assertEquals(
            setOf("/optimize/generic/move-component.mt", "/optimize/sync/follow-reader.mt"),
            ast.callees.keys
        )
        val calls = ast.statements.filterIsInstance<TypedCallStatement>()
        assertEquals(listOf("/optimize/generic/move-component.mt", "/optimize/sync/follow-reader.mt"), calls.map { it.callee })
        assertEquals("/metamodel/base.mm", ast.callees.getValue("/optimize/generic/move-component.mt").metamodelPath)
    }

    @ParameterizedTest
    @ValueSource(strings = ["tinker", "mdeo"])
    fun `a layer-3 wrapper moves a component and its copy store follows`(backend: String) {
        graph(backend).use { graph ->
            val result = run(graph, "/optimize/move-with-stores.mt")

            assertIs<TransformationExecutionResult.Success>(result)
            assertTrue(result.edgesModified)
            val after = graph.toModelData()
            assertEquals("b", after.hostOf("orders"), "moveComponent moved the reader")
            assertEquals("b", after.hostOf("ordersDb"), "followReader moved the store with its reader")
            assertEquals("b", after.hostOf("billing"))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["tinker", "mdeo"])
    fun `a sync that finds nothing to repair succeeds`(backend: String) {
        // the store already sits on the reader's target, so the for-match of followReader has no match
        graph(backend, shopModel(storeHost = "b")).use { graph ->
            val result = run(graph, "/optimize/move-with-stores.mt")

            assertIs<TransformationExecutionResult.Success>(result)
            val after = graph.toModelData()
            assertEquals("b", after.hostOf("orders"))
            assertEquals("b", after.hostOf("ordersDb"))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["tinker", "mdeo"])
    fun `a callee that does not match fails the call and the caller`(backend: String) {
        graph(backend).use { graph ->
            val result = run(graph, "/optimize/scenarios/call-failing.mt")
            // the caller names the callee by the alias of its import
            val alias = transformation("/optimize/scenarios/call-failing.mt").statements.filterIsInstance<TypedCallStatement>().single().name

            assertIs<TransformationExecutionResult.Failure>(result)
            assertEquals("call $alias", result.failedAt)
            assertTrue(result.reason.contains(alias), result.reason)
            assertFalse(result.isDeterministic, "the caller's first match ran before the call")
            val names = graph.toModelData().instances.mapNotNull { (it.properties["name"] as? ModelDataPropertyValue.StringValue)?.value }
            assertTrue("before" in names)
            assertFalse("after" in names, "the statement after the failing call does not run")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["tinker", "mdeo"])
    fun `stop in a callee returns from the callee only`(backend: String) {
        val model = shopModel().let { m ->
            m.copy(instances = m.instances.map { if (it.name == "c") named("skip", "Container") else it })
        }
        graph(backend, model).use { graph ->
            val result = run(graph, "/optimize/scenarios/call-stopping.mt")

            assertIs<TransformationExecutionResult.Success>(result)
            val after = graph.toModelData()
            assertEquals("after", after.nameOf("skip"), "the caller went on after the callee's stop, with renamed = false")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["tinker", "mdeo"])
    fun `kill in a callee ends the caller`(backend: String) {
        graph(backend).use { graph ->
            val result = run(graph, "/optimize/scenarios/call-killing.mt")

            assertIs<TransformationExecutionResult.Stopped>(result)
            assertTrue(result.isKill)
            val names = graph.toModelData().instances.mapNotNull { (it.properties["name"] as? ModelDataPropertyValue.StringValue)?.value }
            assertTrue("killed" in names, "the engine has no rollback; the optimizer discards the graph")
            assertFalse("unreachable" in names)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["tinker", "mdeo"])
    fun `a callee that matches nothing succeeds with a null output`(backend: String) {
        graph(backend).use { graph ->
            val result = run(graph, "/optimize/scenarios/call-finding.mt")

            assertIs<TransformationExecutionResult.Success>(result)
            val names = graph.toModelData().instances.mapNotNull { (it.properties["name"] as? ModelDataPropertyValue.StringValue)?.value }
            assertTrue("none found" in names, "the caller's match saw found == null and count == 0")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["tinker", "mdeo"])
    fun `a call chain passes outputs through a callee that calls others`(backend: String) {
        graph(backend).use { graph ->
            val ast = transformation("/optimize/scenarios/call-nested.mt")
            assertEquals(
                setOf(
                    "/optimize/scenarios/move-and-sync.mt",
                    "/optimize/generic/move-component.mt",
                    "/optimize/sync/follow-reader.mt"
                ),
                ast.callees.keys
            )
            val result = TransformationEngine.create(graph, ast, deterministic = false).execute()

            assertIs<TransformationExecutionResult.Success>(result, result.toString())
            val after = graph.toModelData()
            assertEquals("chosen", after.nameOf("b"), "the output of the inner call came out of the chain")
            assertEquals("b", after.hostOf("orders"))
            assertEquals("b", after.hostOf("ordersDb"))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["tinker", "mdeo"])
    fun `a callee returns a node it created`(backend: String) {
        graph(backend).use { graph ->
            val result = run(graph, "/optimize/scenarios/call-making.mt")

            assertIs<TransformationExecutionResult.Success>(result, result.toString())
            assertEquals(1, result.createdNodes.size, "the callee's creation is part of the call's result")
            val after = graph.toModelData()
            val made = after.instances.single { (it.properties["name"] as? ModelDataPropertyValue.StringValue)?.value == "fresh and used" }
            assertEquals(made.name, after.hostOf("orders"), "the caller linked the service to the created container")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["tinker", "mdeo"])
    fun `a callable transformation runs on its own with the defaults of its inputs`(backend: String) {
        graph(backend).use { graph ->
            val result = run(graph, "/optimize/generic/move-component.mt")

            assertIs<TransformationExecutionResult.Success>(result)
            assertEquals("b", graph.toModelData().hostOf("orders"))
        }
    }

    @Test
    fun `a transformation with a required input does not run on its own`() {
        graph("tinker").use { graph ->
            val result = run(graph, "/optimize/sync/follow-reader.mt")

            assertIs<TransformationExecutionResult.Failure>(result)
            assertTrue(result.reason.contains("Required input 'moved'"), result.reason)
            assertFalse(result.changesWereMade)
        }
    }

    @Test
    fun `the outputs of a call are the callee's assigned nodes and values`() {
        graph("tinker").use { graph ->
            val engine = TransformationEngine.create(graph, transformation("/optimize/move-with-stores.mt"))
            val callee = engine.calleeEngine("/optimize/generic/move-component.mt")
            assertNotNull(callee)
            val outcome = callee!!.executeCall(emptyMap())

            assertIs<TransformationExecutionResult.Success>(outcome.result)
            val moved = outcome.outputs.getValue("moved")
            assertIs<com.mdeo.modeltransformation.compiler.VariableBinding.InstanceBinding>(moved)
            assertNotNull(moved.vertexRef)
            assertIs<com.mdeo.modeltransformation.compiler.VariableBinding.InstanceBinding>(outcome.outputs.getValue("source"))
            assertIs<com.mdeo.modeltransformation.compiler.VariableBinding.InstanceBinding>(outcome.outputs.getValue("target"))
            val choices = outcome.outputs.getValue("choices")
            assertIs<com.mdeo.modeltransformation.compiler.VariableBinding.ValueBinding>(choices)
            assertEquals(1, (choices.value as Number).toInt())
        }
    }
}
