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
import com.mdeo.modeltransformation.graph.ModelGraph
import com.mdeo.modeltransformation.graph.mdeo.MdeoModelGraph
import com.mdeo.modeltransformation.graph.tinker.TinkerModelGraph
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.`__` as AnonymousTraversal
import org.apache.tinkerpop.gremlin.process.traversal.strategy.verification.VerificationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertIs

/**
 * The language traps the Palladio domain project's group operators ran into: two collection
 * lambdas in one expression, `isEmpty()`/`notEmpty()` as booleans, injectivity of bound objects,
 * a `where` that reads an input its pattern does not reference, `to`/`indexOf`/`includes` of the
 * script language and exponent float literals. The typed ASTs come from `TRAP_SCENARIOS` of
 * `app/packages/service-model-transformation/test/fixtures.ts` (resources `calls/`).
 *
 * Every traversal runs under [NoLambdaSteps]: the engine compiles to pure Gremlin, so that it also
 * runs against remote graph databases, and a lambda step anywhere in a traversal or its child
 * traversals fails the test.
 */
class LanguageTrapsExecutionTest {

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

    /** Containers `a`, `b`, `c`; the service `orders` and the component `ordersDb` on `a`, `billing` on `b`. */
    private fun shopModel() = ModelData(
        metamodelPath = "/metamodel/shop.mm",
        instances = listOf(
            named("a", "Container"),
            named("b", "Container"),
            named("c", "Container"),
            named("orders", "Service"),
            named("ordersDb", "Component"),
            named("billing", "Component")
        ),
        links = listOf(
            ModelDataLink("orders", "host", "a", null),
            ModelDataLink("ordersDb", "host", "a", null),
            ModelDataLink("billing", "host", "b", null)
        )
    )

    /** A graph whose traversals reject lambda steps. */
    private class LambdaFreeGraph(private val inner: ModelGraph) : ModelGraph by inner {
        override fun traversal(): GraphTraversalSource = inner.traversal().withStrategies(NoLambdaSteps())
    }

    private fun graph(backend: String): ModelGraph {
        val metamodel = Metamodel.compile(metamodelData)
        val model = shopModel()
        val inner = if (backend == "tinker") TinkerModelGraph.create(model, metamodel) else MdeoModelGraph.create(model, metamodel)
        return LambdaFreeGraph(inner)
    }

    private fun run(backend: String, path: String): List<String> = graph(backend).use { graph ->
        val result = TransformationEngine.create(graph, transformation(path), deterministic = false).execute()
        assertIs<TransformationExecutionResult.Success>(result, result.toString())
        graph.toModelData().instances
            .mapNotNull { (it.properties["name"] as? ModelDataPropertyValue.StringValue)?.value }
            .filter { it !in setOf("a", "b", "c", "orders", "ordersDb", "billing") }
            .sorted()
    }

    @ParameterizedTest
    @ValueSource(strings = ["tinker", "mdeo"])
    fun `two collection lambdas in one expression`(backend: String) {
        assertEquals(
            listOf("on b billing", "only a orders", "only a ordersDb"),
            run(backend, "/optimize/scenarios/call-trap-lambdas.mt")
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["tinker", "mdeo"])
    fun `isEmpty and notEmpty are booleans`(backend: String) {
        assertEquals(listOf("empty false false"), run(backend, "/optimize/scenarios/call-trap-empty.mt"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["tinker", "mdeo"])
    fun `bound objects take part in injective matching, for objects of exactly their class`(backend: String) {
        // matesOf(seed = ordersDb): the Component mate is never ordersDb itself; matesOf(seed =
        // orders): orders is a Service, so a Component object may match it, as two pattern
        // objects of a class and its subclass may match one element (the pairs below)
        assertEquals(
            listOf("mates 1 2", "pair orders orders", "pair orders ordersDb", "svc mate orders", "svc mate ordersDb"),
            run(backend, "/optimize/scenarios/call-trap-injective.mt")
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["tinker", "mdeo"])
    fun `a where clause reads an input the pattern does not reference`(backend: String) {
        assertEquals(listOf("others 2 1 1"), run(backend, "/optimize/scenarios/call-trap-unreferenced.mt"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["tinker", "mdeo"])
    fun `to, indexOf and includes as in the script language`(backend: String) {
        assertEquals(
            listOf("includes orders", "includes ordersDb", "range 4 3 1 2 3", "step 2", "step 3", "step 4"),
            run(backend, "/optimize/scenarios/call-trap-functions.mt")
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["tinker", "mdeo"])
    fun `float literals with an exponent`(backend: String) {
        assertEquals(listOf("literals ok"), run(backend, "/optimize/scenarios/call-trap-literals.mt"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["tinker", "mdeo"])
    fun `properties of a referenced object resolve against its class`(backend: String) {
        // Access.shape and Call.shape have different field positions; a reference (`rc { }`)
        // has no class in the pattern, and its properties used to resolve by name across the
        // metamodel: `shape` of a Call read and wrote Call.built
        val shapes = json.decodeFromString(MetamodelData.serializer(), resource("metamodel__shapes.mm.json"))
        val model = ModelData(
            metamodelPath = "/metamodel/shapes.mm",
            instances = listOf(
                ModelDataInstance("access", "Access", mapOf("shape" to ModelDataPropertyValue.StringValue("plain"))),
                ModelDataInstance("call", "Call", mapOf(
                    "built" to ModelDataPropertyValue.StringValue("plain"),
                    "shape" to ModelDataPropertyValue.StringValue("composed")
                ))
            ),
            links = emptyList()
        )
        val metamodel = Metamodel.compile(shapes)
        val inner = if (backend == "tinker") TinkerModelGraph.create(model, metamodel) else MdeoModelGraph.create(model, metamodel)
        LambdaFreeGraph(inner).use { graph ->
            val result = TransformationEngine.create(
                graph, transformation("/optimize/scenarios/call-trap-reference-properties.mt"), deterministic = false
            ).execute()
            assertIs<TransformationExecutionResult.Success>(result, result.toString())
            val call = graph.toModelData().instances.single { it.name == "call" }
            assertEquals(ModelDataPropertyValue.StringValue("redirect"), call.properties["shape"])
            assertEquals(ModelDataPropertyValue.StringValue("composed"), call.properties["built"])
            val access = graph.toModelData().instances.single { it.name == "access" }
            assertEquals(ModelDataPropertyValue.StringValue("plain"), access.properties["shape"])
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["tinker", "mdeo"])
    fun `numeric properties and list elements take the property's type without a lambda step`(backend: String) {
        // computed values and list elements are cast to the property's int/long/float/double (by
        // `asNumber`, not a lambda step), on created and on matched objects; also empty lists and a
        // null list element
        val numbers = json.decodeFromString(MetamodelData.serializer(), resource("metamodel__numbers.mm.json"))
        fun num(v: Double) = ModelDataPropertyValue.NumberValue(v)
        fun nums(vararg v: Double) = ModelDataPropertyValue.ListValue(v.map { num(it) })
        fun bag(name: String, vararg props: Pair<String, ModelDataPropertyValue>) =
            ModelDataInstance(name, "Bag", mapOf("name" to ModelDataPropertyValue.StringValue(name)) + props)
        val model = ModelData(
            metamodelPath = "/metamodel/numbers.mm",
            instances = listOf(
                bag(
                    "seed", "i" to num(3.0), "l" to num(4.0), "f" to num(1.5), "d" to num(2.5),
                    "ints" to nums(1.0), "longs" to nums(1.0), "floats" to nums(1.0), "doubles" to nums(1.0)
                ),
                bag("other", "i" to num(0.0), "l" to num(0.0), "f" to num(0.0), "d" to num(0.0))
            ),
            links = emptyList()
        )
        val metamodel = Metamodel.compile(numbers)
        val fields = metamodel.metadata.classes.getValue("Bag").propertyFields
        val inner = if (backend == "tinker") TinkerModelGraph.create(model, metamodel) else MdeoModelGraph.create(model, metamodel)
        LambdaFreeGraph(inner).use { graph ->
            val result = TransformationEngine.create(
                graph, transformation("/optimize/scenarios/call-trap-numeric-lists.mt"), deterministic = false
            ).execute()
            assertIs<TransformationExecutionResult.Success>(result, result.toString())
            fun key(property: String) = "prop_${fields.getValue(property).fieldIndex}"
            fun raw(name: String, property: String): List<Any?> =
                graph.traversal().V().has(key("name"), name).properties<Any>(key(property)).value<Any>().toList()
            val made = mapOf(
                "i" to listOf(4), "l" to listOf(3L), "f" to listOf(3.0f), "d" to listOf(3.0), "maybe" to listOf(),
                "ints" to listOf(3, 2), "longs" to listOf(3L, 5L), "floats" to listOf(3.0f, 2.0f), "doubles" to listOf(3.0, 2.0)
            )
            val empty = mapOf(
                "i" to listOf(0), "l" to listOf(0L), "f" to listOf(0.0f), "d" to listOf(0.0), "maybe" to listOf(),
                "ints" to listOf(), "longs" to listOf(), "floats" to listOf(), "doubles" to listOf<Any?>()
            )
            // the null element (unset `other.maybe`) adds no value
            val seed = mapOf(
                "l" to listOf(6L), "d" to listOf(3.0), "maybe" to listOf(),
                "ints" to listOf(7, 3), "longs" to listOf(), "floats" to listOf(3.0f), "doubles" to listOf<Any?>(1.0, 3.0)
            )
            for ((name, expected) in listOf("made" to made, "empty" to empty, "seed" to seed)) {
                assertEquals(expected, expected.keys.associateWith { raw(name, it) }, name)
            }
        }
    }

    private fun num(v: Double) = ModelDataPropertyValue.NumberValue(v)
    private fun nums(vararg v: Double) = ModelDataPropertyValue.ListValue(v.map { num(it) })
    /** A `numbers.mm` instance; the required numbers are 0 unless given. */
    private fun bag(name: String, className: String, vararg props: Pair<String, ModelDataPropertyValue>) =
        ModelDataInstance(
            name, className,
            mapOf("name" to ModelDataPropertyValue.StringValue(name)) + listOf("i", "l", "f", "d").associateWith { num(0.0) } + props
        )

    /**
     * Runs [path] on a `numbers.mm` model of [instances] and returns, per instance and property,
     * the raw graph values (their Java types are the cast's result).
     */
    private fun runNumbers(
        backend: String,
        path: String,
        instances: List<ModelDataInstance>,
        properties: List<String>
    ): Map<String, Map<String, List<Any?>>> {
        val numbers = json.decodeFromString(MetamodelData.serializer(), resource("metamodel__numbers.mm.json"))
        val model = ModelData(metamodelPath = "/metamodel/numbers.mm", instances = instances, links = emptyList())
        val metamodel = Metamodel.compile(numbers)
        val inner = if (backend == "tinker") TinkerModelGraph.create(model, metamodel) else MdeoModelGraph.create(model, metamodel)
        return LambdaFreeGraph(inner).use { graph ->
            val result = TransformationEngine.create(graph, transformation(path), deterministic = false).execute()
            assertIs<TransformationExecutionResult.Success>(result, result.toString())
            fun key(className: String, property: String) =
                "prop_${metamodel.metadata.classes.getValue(className).propertyFields.getValue(property).fieldIndex}"
            fun vertex(name: String, className: String) =
                graph.traversal().V().hasLabel(className).has(key(className, "name"), name)
            val names = instances.map { it.name to it.className } + ("made" to "Bag")
            names.filter { (name, className) -> vertex(name, className).hasNext() }.associate { (name, className) ->
                name to properties.associateWith { property ->
                    vertex(name, className).properties<Any>(key(className, property)).value<Any>().toList()
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["tinker", "mdeo"])
    fun `a reference casts numeric values against its bound element's class`(backend: String) {
        // `target { }` (an element input of the subclass Crate) and `later { }` (an object of an
        // earlier match) have no class in the pattern; their computed values take the property's
        // type as for an object with a class (the loader stores every number as a double)
        val properties = listOf("i", "l", "f", "d", "maybe", "ints", "longs", "floats", "doubles")
        val values = runNumbers(
            backend, "/optimize/scenarios/call-trap-reference-cast.mt",
            listOf(
                bag("seed", "Crate", "i" to num(3.0), "l" to num(3.0), "f" to num(3.0), "d" to num(3.0)),
                bag("later", "Bag", "i" to num(1.0), "l" to num(1.0), "f" to num(1.0), "d" to num(1.0)),
                bag("other", "Bag", "i" to num(4.0))
            ),
            properties
        )
        assertEquals(
            mapOf(
                "i" to listOf(5), "l" to listOf(4L), "f" to listOf(4.0f), "d" to listOf(4.0), "maybe" to listOf(4.0),
                "ints" to listOf(4, 2), "longs" to listOf(4L), "floats" to listOf(4.0f), "doubles" to listOf<Any?>(4.0)
            ),
            values.getValue("seed")
        )
        // the properties `later { }` assigns (the others keep the backend's loaded types)
        val later = mapOf("i" to listOf(12), "l" to listOf(4L), "ints" to listOf(4), "doubles" to listOf<Any?>(4.0, 1.0))
        assertEquals(later, values.getValue("later").filterKeys { it in later })
    }

    @ParameterizedTest
    @ValueSource(strings = ["tinker", "mdeo"])
    fun `assigning null unsets the property`(backend: String) {
        // an unset optional property (`maybe = other.maybe`), the literal `null` and a null branch
        // of a ternary unset `maybe` (a value branch sets it); an unset list (`doubles = other.doubles`) empties `doubles`;
        // on matched, created and referenced (element input) objects
        val values = runNumbers(
            backend, "/optimize/scenarios/call-trap-null-assign.mt",
            listOf(
                bag("seed", "Bag", "maybe" to num(7.5), "doubles" to nums(1.0, 2.0)),
                bag("other", "Bag"),
                bag("lit", "Bag", "maybe" to num(1.0)),
                bag("cond", "Bag", "maybe" to num(1.0)),
                bag("kept", "Bag", "maybe" to num(1.0)),
                bag("bound", "Crate", "maybe" to num(3.0), "doubles" to nums(5.0)),
                bag("sized", "Bag", "ints" to nums(1.0, 2.0))
            ),
            listOf("maybe", "doubles")
        )
        val unset = mapOf("maybe" to listOf<Any?>(), "doubles" to listOf())
        assertEquals(
            mapOf(
                "seed" to unset, "other" to unset, "lit" to unset, "cond" to unset,
                "kept" to mapOf("maybe" to listOf<Any?>(2.5), "doubles" to listOf()),
                "bound" to unset, "made" to unset,
                // a nullable value that ends in a barrier (`size()`), cast to the property's double
                "sized" to mapOf("maybe" to listOf<Any?>(2.0), "doubles" to listOf())
            ),
            values
        )
    }

    @Test
    fun `the graph of these tests rejects lambda steps`() {
        graph("tinker").use { graph ->
            assertThrows<VerificationException> {
                graph.traversal().inject<Any>(1).map<Any> { it.get() }.toList()
            }
            assertThrows<VerificationException> {
                graph.traversal().inject<Any>(1).sideEffect(AnonymousTraversal.map<Any, Any> { it.get() }).toList()
            }
        }
    }
}
