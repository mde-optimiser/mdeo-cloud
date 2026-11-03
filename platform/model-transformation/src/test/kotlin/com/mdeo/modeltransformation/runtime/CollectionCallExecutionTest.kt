package com.mdeo.modeltransformation.runtime

import com.mdeo.expression.ast.expressions.TypedExpression
import com.mdeo.metamodel.Metamodel
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.metamodel.data.ModelData
import com.mdeo.metamodel.data.ModelDataInstance
import com.mdeo.metamodel.data.ModelDataLink
import com.mdeo.metamodel.data.ModelDataPropertyValue
import com.mdeo.metamodel.data.merge
import com.mdeo.modeltransformation.ast.TypedAst
import com.mdeo.modeltransformation.ast.expressions.TypedExpressionSerializer
import com.mdeo.modeltransformation.ast.patterns.TypedPatternElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternElementSerializer
import com.mdeo.modeltransformation.ast.statements.TypedTransformationStatement
import com.mdeo.modeltransformation.ast.statements.TypedTransformationStatementSerializer
import com.mdeo.modeltransformation.compiler.CollectionValue
import com.mdeo.modeltransformation.compiler.VariableBinding
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
 * Execution of collections, `for … in`, assignments outside patterns, `if call` (stage 2 of
 * callable transformations) and optional element inputs that pin an object of the first pattern
 * (stage 3), on typed ASTs the transformation language's converter produced from
 * `COLLECTION_SCENARIOS` of `app/packages/service-model-transformation/test/fixtures.ts`
 * (resources `calls/`, kept up to date by that package's `engineFixtures.test.ts`).
 */
class CollectionCallExecutionTest {

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
     * Containers `a`, `b`, `c`; `orders` (a service on `a`) may move to the candidates of its
     * placement, `ordersDb` sits on `a`, `billing` on `b`. With [secondPlacement], `billing` has a
     * placement of its own that may move it to `c`.
     */
    private fun shopModel(candidates: List<String> = listOf("b"), secondPlacement: Boolean = false) = ModelData(
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
        ) + (if (secondPlacement) listOf(ModelDataInstance("billingPlacement", "Placement", emptyMap())) else emptyList()),
        links = listOf(
            ModelDataLink("orders", "host", "a", null),
            ModelDataLink("ordersDb", "host", "a", null),
            ModelDataLink("billing", "host", "b", null),
            ModelDataLink("placement", "component", "orders", null),
            ModelDataLink("feed", "reader", "orders", null),
            ModelDataLink("feed", "store", "ordersDb", null)
        ) + candidates.map { ModelDataLink("placement", "candidates", it, null) } +
            (if (secondPlacement) {
                listOf(
                    ModelDataLink("billingPlacement", "component", "billing", null),
                    ModelDataLink("billingPlacement", "candidates", "c", null)
                )
            } else {
                emptyList()
            })
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

    private fun ModelData.names(): List<String> =
        instances.mapNotNull { (it.properties["name"] as? ModelDataPropertyValue.StringValue)?.value }

    @ParameterizedTest
    @ValueSource(strings = ["tinker", "mdeo"])
    fun `collection outputs feed loops, local repairs and collection inputs`(backend: String) {
        graph(backend).use { graph ->
            val result = run(graph, "/optimize/scenarios/call-collecting.mt")

            assertIs<TransformationExecutionResult.Success>(result, result.toString())
            val after = graph.toModelData()
            assertEquals("b", after.hostOf("orders"), "moveComponent moved orders to b")
            assertEquals("on b", after.nameOf("orders"), "markComponent ran for every component on b")
            assertEquals("on b", after.nameOf("billing"))
            assertEquals("left", after.nameOf("ordersDb"), "renameAll ran over the collection input")
            assertEquals("candidate", after.nameOf("b"), "the loop over a set output assigned in a pattern")
            val created = after.instances.filter { it.name !in shopModel().instances.map { i -> i.name } }
            assertEquals(
                setOf("billing", "orders"),
                created.mapNotNull { (it.properties["name"] as? ModelDataPropertyValue.StringValue)?.value }.toSet(),
                "the loop over the set of names (taken before the renames) created one container per name"
            )
            assertEquals(2, created.size)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["tinker", "mdeo"])
    fun `a collection output holds what the callee added, in order and without duplicates in a set`(backend: String) {
        graph(backend).use { graph ->
            val engine = TransformationEngine.create(graph, transformation("/optimize/scenarios/call-collecting.mt"))
            val callee = engine.calleeEngine("/optimize/scenarios/components-on.mt")!!
            val host = VariableBinding.InstanceBinding(graph.createVertexRef(graph.nameRegistry.getVertexId("a")!!))
            val outcome = callee.executeCall(mapOf("host" to host))

            assertIs<TransformationExecutionResult.Success>(outcome.result)
            val components = (outcome.outputs.getValue("components") as VariableBinding.ValueBinding).value
            assertIs<CollectionValue>(components)
            assertFalse(components.isSet)
            assertEquals(
                setOf("orders", "ordersDb"),
                components.items.map { graph.nameRegistry.getName((it as com.mdeo.modeltransformation.graph.VertexRef).rawId) }.toSet()
            )
            val names = (outcome.outputs.getValue("names") as VariableBinding.ValueBinding).value
            assertIs<CollectionValue>(names)
            assertTrue(names.isSet)
            assertEquals(setOf("orders", "ordersDb"), names.items.toSet())
        }
    }

    @Test
    fun `a set keeps one of equal elements`() {
        val set = CollectionValue(isSet = true, listOf("x", "y", "x"))
        assertEquals(listOf("x", "y"), set.items)
        val list = CollectionValue(isSet = false, listOf("x", "y", "x"))
        assertEquals(listOf("x", "y", "x"), list.items)
    }

    @ParameterizedTest
    @ValueSource(strings = ["tinker", "mdeo"])
    fun `if call runs else after a callee that fails and then with the outputs after one that succeeds`(backend: String) {
        graph(backend).use { graph ->
            val result = run(graph, "/optimize/scenarios/call-if.mt")

            assertIs<TransformationExecutionResult.Success>(result, result.toString())
            val after = graph.toModelData()
            assertTrue("else ran" in after.names())
            assertFalse("then ran" in after.names())
            assertEquals("b", after.hostOf("orders"))
            assertEquals("moved here", after.nameOf("b"), "the output of the successful call was bound in then")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["tinker", "mdeo"])
    fun `if call takes the else branch of a failed move, here kill`(backend: String) {
        graph(backend, shopModel(candidates = emptyList())).use { graph ->
            val result = run(graph, "/optimize/scenarios/call-if.mt")

            assertIs<TransformationExecutionResult.Stopped>(result)
            assertTrue(result.isKill)
            assertEquals("a", graph.toModelData().hostOf("orders"), "the failed move changed nothing")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["tinker", "mdeo"])
    fun `kill in a callee of if call ends everything`(backend: String) {
        graph(backend).use { graph ->
            val result = run(graph, "/optimize/scenarios/call-if-killing.mt")

            assertIs<TransformationExecutionResult.Stopped>(result)
            assertTrue(result.isKill)
            val names = graph.toModelData().names()
            assertFalse("then ran" in names)
            assertFalse("else ran" in names)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["tinker", "mdeo"])
    fun `an optional element input pins the object of the first pattern when passed`(backend: String) {
        repeat(6) {
            graph(backend, shopModel(secondPlacement = true)).use { graph ->
                val result = run(graph, "/optimize/scenarios/call-pinned.mt")

                assertIs<TransformationExecutionResult.Success>(result, result.toString())
                val after = graph.toModelData()
                assertEquals("b", after.hostOf("orders"), "the placement of the service was pinned")
                assertEquals("b", after.hostOf("billing"), "the other placement was not used")
                assertEquals("pinned target", after.nameOf("b"))
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["tinker", "mdeo"])
    fun `the same file is an operator that matches the object freely`(backend: String) {
        val moved = mutableSetOf<String>()
        repeat(12) {
            graph(backend, shopModel(secondPlacement = true)).use { graph ->
                val result = run(graph, "/optimize/scenarios/move-placement.mt")

                assertIs<TransformationExecutionResult.Success>(result, result.toString())
                val after = graph.toModelData()
                val ordersMoved = after.hostOf("orders") == "b"
                val billingMoved = after.hostOf("billing") == "c"
                assertTrue(ordersMoved != billingMoved, "exactly one placement was applied")
                moved += if (ordersMoved) "orders" else "billing"
            }
        }
        assertTrue(moved.isNotEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["tinker", "mdeo"])
    fun `a null or absent pinning input leaves the object free, a passed one pins it`(backend: String) {
        graph(backend, shopModel(secondPlacement = true)).use { graph ->
            val engine = TransformationEngine.create(graph, transformation("/optimize/scenarios/call-pinned.mt"))
            val callee = engine.calleeEngine("/optimize/scenarios/move-placement.mt")
            assertNotNull(callee)
            val billingPlacement = graph.createVertexRef(graph.nameRegistry.getVertexId("billingPlacement")!!)
            val pinned = callee!!.executeCall(mapOf("placement" to VariableBinding.InstanceBinding(billingPlacement)))

            assertIs<TransformationExecutionResult.Success>(pinned.result)
            assertEquals("c", graph.toModelData().hostOf("billing"))
        }
        for (inputs in listOf(emptyMap(), mapOf("placement" to VariableBinding.ValueBinding(null)))) {
            graph(backend).use { graph ->
                val engine = TransformationEngine.create(graph, transformation("/optimize/scenarios/call-pinned.mt"))
                val outcome = engine.calleeEngine("/optimize/scenarios/move-placement.mt")!!.executeCall(inputs)

                assertIs<TransformationExecutionResult.Success>(outcome.result, outcome.result.toString())
                assertEquals("b", graph.toModelData().hostOf("orders"), "the only placement was matched freely")
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["tinker", "mdeo"])
    fun `loops run over many-valued references and filtered collections`(backend: String) {
        graph(backend, shopModel(candidates = listOf("b", "c"))).use { graph ->
            val result = run(graph, "/optimize/scenarios/call-looping.mt")

            assertIs<TransformationExecutionResult.Success>(result, result.toString())
            val after = graph.toModelData()
            assertEquals("filtered", after.nameOf("c"))
            assertEquals("candidate", after.nameOf("b"), "the second loop saw the renames of the first")
            assertTrue("seen 2" in after.names(), "the callee counted both candidates: ${after.names()}")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["tinker", "mdeo"])
    fun `an element output assigned outside a pattern is a bound element`(backend: String) {
        graph(backend, shopModel(candidates = listOf("b", "c"))).use { graph ->
            val engine = TransformationEngine.create(graph, transformation("/optimize/scenarios/call-looping.mt"))
            val callee = engine.calleeEngine("/optimize/scenarios/pick-candidate.mt")!!
            val placement = graph.createVertexRef(graph.nameRegistry.getVertexId("placement")!!)
            val outcome = callee.executeCall(mapOf("placement" to VariableBinding.InstanceBinding(placement)))

            assertIs<TransformationExecutionResult.Success>(outcome.result)
            val picked = outcome.outputs.getValue("picked")
            assertIs<VariableBinding.InstanceBinding>(picked)
            assertTrue(graph.nameRegistry.getName(picked.vertexRef!!.rawId) in setOf("b", "c"))
            val seen = outcome.outputs.getValue("seen") as VariableBinding.ValueBinding
            assertEquals(2, (seen.value as Number).toInt())
        }
    }
}
