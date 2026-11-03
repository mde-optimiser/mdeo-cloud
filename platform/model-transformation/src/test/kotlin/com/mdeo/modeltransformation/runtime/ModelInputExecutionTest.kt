package com.mdeo.modeltransformation.runtime

import com.mdeo.expression.ast.expressions.TypedBinaryExpression
import com.mdeo.expression.ast.expressions.TypedExpression
import com.mdeo.expression.ast.expressions.TypedIdentifierExpression
import com.mdeo.expression.ast.expressions.TypedIntLiteralExpression
import com.mdeo.expression.ast.expressions.TypedMemberAccessExpression
import com.mdeo.expression.ast.expressions.TypedMemberCallExpression
import com.mdeo.expression.ast.expressions.TypedStringLiteralExpression
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
import com.mdeo.modeltransformation.ast.EdgeLabelUtils
import com.mdeo.modeltransformation.ast.TypedAst
import com.mdeo.modeltransformation.ast.TypedTransformationParameter
import com.mdeo.modeltransformation.ast.TypedTransformationSignature
import com.mdeo.modeltransformation.ast.patterns.TypedPattern
import com.mdeo.modeltransformation.ast.patterns.TypedPatternApplicationCondition
import com.mdeo.modeltransformation.ast.patterns.TypedPatternApplicationConditionElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLink
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLinkElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLinkEnd
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstance
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstanceElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternPropertyAssignment
import com.mdeo.modeltransformation.ast.patterns.TypedPatternWhereClauseElement
import com.mdeo.modeltransformation.ast.patterns.TypedWhereClause
import com.mdeo.modeltransformation.ast.statements.TypedCallStatement
import com.mdeo.modeltransformation.ast.statements.TypedForInStatement
import com.mdeo.modeltransformation.ast.statements.TypedForMatchStatement
import com.mdeo.modeltransformation.ast.statements.TypedIfCallStatement
import com.mdeo.modeltransformation.ast.statements.TypedMatchStatement
import com.mdeo.modeltransformation.ast.statements.TypedTransformationCallArgument
import com.mdeo.modeltransformation.ast.statements.TypedTransformationStatement
import com.mdeo.modeltransformation.graph.ModelGraph
import com.mdeo.modeltransformation.graph.mdeo.MdeoModelGraph
import com.mdeo.modeltransformation.graph.tinker.TinkerModelGraph
import org.apache.tinkerpop.gremlin.process.traversal.Traversal
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertIs

/**
 * Model inputs (`in donor: model`, `in donors: List<model>`) on both graph backends: objects of a
 * scope block `in donor { … }` match in the donor only, every other object in the implicit model
 * only; the donors are loaded for the run and leave no trace; model values pass by reference to
 * callees and are iterated by `for`; a run without model inputs emits the traversals it emitted
 * before the feature.
 *
 * The typed ASTs are built here as the transformation language's converter would emit them.
 * Metamodel: `Component { name } --host--> Container { name, weight, rank, kind: Kind }`.
 */
class ModelInputExecutionTest {

    // ---- metamodel and models ----

    private val metamodelData = MetamodelData(
        path = MM,
        classes = listOf(
            ClassData(
                name = "Component", isAbstract = false, extends = emptyList(),
                properties = listOf(
                    PropertyData(name = "name", primitiveType = "string", multiplicity = MultiplicityData.single())
                )
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

    private val metamodel = Metamodel.compile(metamodelData)

    private fun named(name: String, className: String, label: String = name) =
        ModelDataInstance(name, className, mapOf("name" to ModelDataPropertyValue.StringValue(label)))

    /**
     * A model of containers and of components (`component to container`), every instance named
     * by its own name.
     */
    private fun model(containers: List<String>, components: Map<String, String>) = ModelData(
        metamodelPath = MM,
        instances = containers.map { named(it, "Container") } + components.keys.map { named(it, "Component") },
        links = components.map { (component, container) -> ModelDataLink(component, "host", container, null) }
    )

    /** The implicit model: `x` on `a`, `y` on `b`. */
    private fun implicitModel() = model(listOf("a", "b"), mapOf("x" to "a", "y" to "b"))

    /** A donor with the same names: `x` on `b`, and `z` on `a`. */
    private fun donorModel() = model(listOf("a", "b"), mapOf("x" to "b", "z" to "a"))

    private fun graph(backend: String, data: ModelData): ModelGraph =
        if (backend == "tinker") TinkerModelGraph.create(data, metamodel) else MdeoModelGraph.create(data, metamodel)

    // ---- typed AST ----

    private val types: List<ReturnType> = listOf(
        VoidType(),                                                                   // 0
        builtin("string"),                                                           // 1 STRING
        builtin("boolean"),                                                          // 2 BOOLEAN
        builtin("int"),                                                              // 3 INT
        builtin("double"),                                                           // 4 DOUBLE
        builtin("model"),                                                            // 5 MODEL
        ClassTypeRef("builtin", "List", false, mapOf("T" to builtin("model"))),     // 6 MODEL_LIST
        ClassTypeRef("class$MM", "Component", false),                               // 7 COMPONENT
        ClassTypeRef("class$MM", "Container", false),                               // 8 CONTAINER
        ClassTypeRef("enum$MM", "Kind", false),                                     // 9 KIND
        ClassTypeRef("builtin", "Set", false, mapOf("T" to builtin("string")))      // 10 STRING_SET
    )

    private fun builtin(name: String) = ClassTypeRef("builtin", name, false)

    private fun ident(name: String, type: Int, scope: Int = 1) =
        TypedIdentifierExpression(evalType = type, name = name, scope = scope)

    private fun nameOf(name: String, type: Int = COMPONENT, scope: Int = 1) =
        TypedMemberAccessExpression(evalType = STRING, expression = ident(name, type, scope), member = "name", isNullChaining = false)

    private fun str(value: String) = TypedStringLiteralExpression(evalType = STRING, value = value)

    private fun int(value: Int) = TypedIntLiteralExpression(evalType = INT, value = value.toString())

    private fun binary(operator: String, left: TypedExpression, right: TypedExpression) =
        TypedBinaryExpression(evalType = BOOLEAN, operator = operator, left = left, right = right)

    private fun collectionCall(collection: TypedExpression, method: String, type: Int) = TypedMemberCallExpression(
        evalType = type, expression = collection, member = method, isNullChaining = false, overload = "", arguments = emptyList()
    )

    private fun obj(
        name: String,
        className: String?,
        model: String? = null,
        modifier: String? = null,
        vararg properties: TypedPatternPropertyAssignment
    ) = TypedPatternObjectInstanceElement(
        objectInstance = TypedPatternObjectInstance(modifier, name, className, properties.toList(), model)
    )

    private fun set(property: String, value: TypedExpression) = TypedPatternPropertyAssignment(property, "=", value)

    private fun equal(property: String, value: TypedExpression) = TypedPatternPropertyAssignment(property, "==", value)

    private fun host(component: String, container: String, modifier: String? = null) = TypedPatternLinkElement(
        link = TypedPatternLink(modifier, TypedPatternLinkEnd(component, "host"), TypedPatternLinkEnd(container, null))
    )

    private fun where(expression: TypedExpression) = TypedPatternWhereClauseElement(whereClause = TypedWhereClause(expression))

    private fun forbid(vararg elements: TypedPatternElement) = TypedPatternApplicationConditionElement(
        condition = TypedPatternApplicationCondition(negative = true, elements = elements.toList())
    )

    private fun match(vararg elements: TypedPatternElement) = TypedMatchStatement(pattern = TypedPattern(elements.toList()))

    private fun input(name: String, type: Int) = TypedTransformationParameter(name, "in", type)

    private fun modelInput(name: String) = TypedTransformationParameter(name, "in", MODEL, isModel = true)

    private fun modelListInput(name: String) =
        TypedTransformationParameter(name, "in", MODEL_LIST, collection = "List", isModel = true)

    private fun transformation(
        parameters: List<TypedTransformationParameter>,
        vararg statements: TypedTransformationStatement,
        callees: Map<String, TypedAst> = emptyMap()
    ) = TypedAst(types, MM, statements.toList(), TypedTransformationSignature("t.mt", parameters), callees)

    /**
     * `in donor: model`: the implicit component named like a donor component moves to the
     * container named like that donor component's host — a crossover taking the donor's hosting.
     */
    private fun takeHosting(model: String = "donor") = listOf(
        obj("dc", "Component", model),
        obj("dh", "Container", model),
        host("dc", "dh"),
        obj("c", "Component"),
        obj("h", "Container"),
        obj("old", "Container"),
        host("c", "old"),
        where(binary("&&", binary("==", nameOf("c"), nameOf("dc")), binary("==", nameOf("h", CONTAINER), nameOf("dh", CONTAINER)))),
        host("c", "old", "delete"),
        host("c", "h", "create")
    )

    private fun run(
        graph: ModelGraph,
        ast: TypedAst,
        models: Map<String, ModelArgument> = emptyMap(),
        arguments: Map<String, Any?> = emptyMap(),
        deterministic: Boolean = true
    ): TransformationExecutionResult =
        TransformationEngine.create(graph, ast, deterministic).execute(arguments, models)

    // ---- state of a graph ----

    private fun ModelData.hostOf(component: String): String? =
        links.singleOrNull { it.sourceName == component && it.sourceProperty == "host" }?.targetName

    private fun ModelData.labels(): List<String?> =
        instances.map { (it.properties["name"] as? ModelDataPropertyValue.StringValue)?.value }

    /** Everything of a graph a model input must not leave a trace in. */
    private data class GraphState(
        val instances: List<String>,
        val links: List<String>,
        val names: Map<Any, String>,
        val vertexIds: Set<Any>,
        val counts: List<Int>
    )

    private fun ModelGraph.state(withIds: Boolean = true): GraphState {
        val data = toModelData()
        val stats = statistics
        return GraphState(
            instances = data.instances.map { "${it.name}:${it.className}:${it.properties.toSortedMap()}" }.sorted(),
            links = data.links.map { "${it.sourceName}.${it.sourceProperty}->${it.targetName}" }.sorted(),
            names = if (withIds) nameRegistry.getAllMappings() else nameRegistry.getAllMappings().values.associateBy { it },
            vertexIds = if (withIds) traversal().V().id().toList().toSet() else emptySet(),
            counts = listOf(
                stats.vertexCount, stats.edgeCount,
                stats.verticesWithLabel("Component"), stats.verticesWithLabel("Container"),
                stats.edgesWithLabel(EdgeLabelUtils.computeEdgeLabel("host", null))
            )
        )
    }

    // ---- matching in donor slots ----

    @ParameterizedTest
    @ValueSource(strings = ["mdeo", "tinker"])
    fun `objects of a scope block match in the donor and the implicit model takes the change`(backend: String) {
        graph(backend, implicitModel()).use { graph ->
            graph(backend, donorModel()).use { donor ->
                val donorBefore = donor.state()
                val result = run(
                    graph, transformation(listOf(modelInput("donor")), match(*takeHosting().toTypedArray())),
                    mapOf("donor" to ModelArgument.of(donor))
                )

                assertIs<TransformationExecutionResult.Success>(result, result.toString())
                val after = graph.toModelData()
                assertEquals("b", after.hostOf("x"), "x moved to the container its donor counterpart is on")
                assertEquals("b", after.hostOf("y"))
                assertEquals(4, after.instances.size, "no donor object entered the implicit model")
                assertEquals(donorBefore, donor.state(), "the donor graph is not changed")
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["mdeo", "tinker"])
    fun `unscoped objects never match donor vertices and scoped objects never match implicit ones`(backend: String) {
        graph(backend, implicitModel()).use { graph ->
            graph(backend, donorModel()).use { donor ->
                val donors = mapOf("donor" to ModelArgument.of(donor))
                val implicitZ = transformation(listOf(modelInput("donor")), match(obj("c", "Component", null, null, equal("name", str("z")))))
                assertIs<TransformationExecutionResult.Failure>(run(graph, implicitZ, donors), "z exists in the donor only")

                val donorY = transformation(listOf(modelInput("donor")), match(obj("dc", "Component", "donor", null, equal("name", str("y")))))
                assertIs<TransformationExecutionResult.Failure>(run(graph, donorY, donors), "y exists in the implicit model only")

                val donorZ = transformation(
                    listOf(modelInput("donor")),
                    match(
                        obj("dc", "Component", "donor", null, equal("name", str("z"))),
                        obj("dh", "Container", "donor"),
                        host("dc", "dh"),
                        obj("made", "Container", null, "create", set("name", nameOf("dh", CONTAINER)))
                    )
                )
                assertIs<TransformationExecutionResult.Success>(run(graph, donorZ, donors))
                assertEquals(listOf("a", "a", "b", "x", "y"), graph.toModelData().labels().map { it!! }.sorted(),
                    "the created object is in the implicit model, named after the donor's host of z")
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["mdeo", "tinker"])
    fun `an application condition reads the donor`(backend: String) {
        graph(backend, implicitModel()).use { graph ->
            graph(backend, donorModel()).use { donor ->
                // rename an implicit component whose name no donor component has: y (the donor has x and z)
                val ast = transformation(
                    listOf(modelInput("donor")),
                    match(
                        obj("c", "Component", null, null, set("name", str("unique"))),
                        forbid(
                            obj("dc", "Component", "donor"),
                            where(binary("==", nameOf("dc", scope = 2), nameOf("c")))
                        )
                    )
                )
                val result = run(graph, ast, mapOf("donor" to ModelArgument.of(donor)))

                assertIs<TransformationExecutionResult.Success>(result, result.toString())
                assertEquals(listOf("a", "b", "unique", "x"), graph.toModelData().labels().map { it!! }.sorted())
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["mdeo", "tinker"])
    fun `a donor object bound by a for match is referenced in a scope block of its model`(backend: String) {
        // for match { in donor { dc: Component {} } } do {
        //     match { in donor { dc {}  dh: Container {}  dc.host -- dh }  create made: Container { name = dh.name } }
        // }
        val ast = transformation(
            listOf(modelInput("donor")),
            TypedForMatchStatement(
                pattern = TypedPattern(listOf(obj("dc", "Component", "donor"))),
                doBlock = listOf(
                    match(
                        obj("dc", null, "donor"),
                        obj("dh", "Container", "donor"),
                        host("dc", "dh"),
                        obj("made", "Container", null, "create", set("name", nameOf("dh", CONTAINER, scope = 3)))
                    )
                )
            )
        )
        graph(backend, implicitModel()).use { graph ->
            graph(backend, donorModel()).use { donor ->
                val result = run(graph, ast, mapOf("donor" to ModelArgument.of(donor)))

                assertIs<TransformationExecutionResult.Success>(result, result.toString())
                assertEquals(listOf("a", "a", "b", "b", "x", "y"), graph.toModelData().labels().map { it!! }.sorted(),
                    "one container per donor component, named after its donor host")
            }
        }
    }

    @Test
    fun `a donor of the other backend is loaded through its model data`() {
        graph("mdeo", implicitModel()).use { graph ->
            graph("tinker", donorModel()).use { donor ->
                val result = run(
                    graph, transformation(listOf(modelInput("donor")), match(*takeHosting().toTypedArray())),
                    mapOf("donor" to ModelArgument.of(donor))
                )
                assertIs<TransformationExecutionResult.Success>(result)
                assertEquals("b", graph.toModelData().hostOf("x"))
            }
        }
    }

    // ---- no trace ----

    @ParameterizedTest
    @ValueSource(strings = ["mdeo", "tinker"])
    fun `donors leave no trace after a successful run`(backend: String) {
        graph(backend, implicitModel()).use { graph ->
            graph(backend, donorModel()).use { donor ->
                // a read-only run, so that everything the graph holds can be compared
                val ast = transformation(
                    listOf(modelInput("donor")),
                    match(obj("dc", "Component", "donor"), obj("dh", "Container", "donor"), host("dc", "dh")),
                    match(obj("c", "Component"))
                )
                val before = graph.state()
                assertIs<TransformationExecutionResult.Success>(run(graph, ast, mapOf("donor" to ModelArgument.of(donor))))
                assertEquals(before, graph.state())
                assertTrue(graph.modelInputSlots.isEmpty())
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["mdeo", "tinker"])
    fun `donors leave no trace after a failed run`(backend: String) {
        graph(backend, implicitModel()).use { graph ->
            graph(backend, donorModel()).use { donor ->
                val ast = transformation(
                    listOf(modelInput("donor")),
                    match(obj("dc", "Component", "donor", null, equal("name", str("z")))),
                    match(obj("c", "Component", null, null, equal("name", str("nowhere"))))
                )
                val before = graph.state()
                assertIs<TransformationExecutionResult.Failure>(run(graph, ast, mapOf("donor" to ModelArgument.of(donor))))
                assertEquals(before, graph.state())
                assertTrue(graph.modelInputSlots.isEmpty())
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["mdeo", "tinker"])
    fun `donors leave no trace when the engine refuses to change a donor`(backend: String) {
        graph(backend, implicitModel()).use { graph ->
            graph(backend, donorModel()).use { donor ->
                val donors = mapOf("donor" to ModelArgument.of(donor))
                val before = graph.state()
                val donorBefore = donor.state()
                for (pattern in listOf(
                    match(obj("dc", "Component", "donor", null, set("name", str("changed")))),
                    match(obj("dc", "Component", "donor", "delete")),
                    match(obj("dn", "Component", "donor", "create")),
                    match(obj("dc", "Component", "donor"), obj("dh", "Container", "donor"), host("dc", "dh", "delete")),
                    match(obj("c", "Component"), obj("dh", "Container", "donor"), host("c", "dh"))
                )) {
                    assertThrows<IllegalStateException> { run(graph, transformation(listOf(modelInput("donor")), pattern), donors) }
                    assertEquals(before, graph.state())
                    assertTrue(graph.modelInputSlots.isEmpty())
                }
                assertEquals(donorBefore, donor.state())
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["mdeo", "tinker"])
    fun `donors leave no trace in a nondeterministic run that changes the model`(backend: String) {
        graph(backend, implicitModel()).use { graph ->
            graph(backend, donorModel()).use { donor ->
                val reference = graph(backend, implicitModel())
                // the same change without a donor, for comparison
                assertIs<TransformationExecutionResult.Success>(run(reference, transformation(
                    emptyList(),
                    match(obj("c", "Component", null, null, equal("name", str("x"))), obj("old", "Container"), host("c", "old"),
                        obj("h", "Container", null, null, equal("name", str("b"))), host("c", "old", "delete"), host("c", "h", "create"))
                ), deterministic = false))

                val result = run(
                    graph, transformation(listOf(modelInput("donor")), match(*takeHosting().toTypedArray())),
                    mapOf("donor" to ModelArgument.of(donor)), deterministic = false
                )
                assertIs<TransformationExecutionResult.Success>(result)
                // a Tinker graph is rebuilt with new vertex IDs before every match of a nondeterministic run
                assertEquals(reference.state(withIds = false), graph.state(withIds = false))
                reference.close()
            }
        }
    }

    // ---- names ----

    @ParameterizedTest
    @ValueSource(strings = ["mdeo", "tinker"])
    fun `donor names neither collide with nor are taken by implicit names`(backend: String) {
        graph(backend, implicitModel()).use { graph ->
            // the donor has an instance named like the object the transformation creates
            graph(backend, model(listOf("made"), emptyMap())).use { donor ->
                val ast = transformation(
                    listOf(modelInput("donor")),
                    match(obj("dm", "Container", "donor", null, equal("name", str("made")))),
                    match(obj("made", "Container", null, "create", set("name", str("new"))))
                )
                assertIs<TransformationExecutionResult.Success>(run(graph, ast, mapOf("donor" to ModelArgument.of(donor))))

                val after = graph.toModelData()
                assertEquals(setOf("a", "b", "x", "y", "made"), after.instances.map { it.name }.toSet(),
                    "the created object got its own name, as without the donor")
                assertEquals(setOf("a", "b", "x", "y", "made"), graph.nameRegistry.getAllMappings().values.toSet())
            }
        }
    }

    // ---- calls and loops ----

    @ParameterizedTest
    @ValueSource(strings = ["mdeo", "tinker"])
    fun `a call passes a model by reference and a callee without model inputs matches the implicit model only`(backend: String) {
        val callee = transformation(listOf(modelInput("source")), match(*takeHosting("source").toTypedArray()))
        // finds z in the implicit model only: there is none, although the caller's donor has one
        val finder = transformation(emptyList(), match(obj("c", "Component", null, null, equal("name", str("z")))))
        val caller = transformation(
            listOf(modelInput("donor")),
            TypedCallStatement(callee = "/take.mt", name = "take", arguments = listOf(
                TypedTransformationCallArgument("source", ident("donor", MODEL))
            )),
            TypedIfCallStatement(
                call = TypedCallStatement(callee = "/find.mt", name = "find"),
                thenBlock = listOf(match(obj("x2", "Container", null, "create", set("name", str("found"))))),
                elseBlock = listOf(match(obj("x3", "Container", null, "create", set("name", str("not found")))))
            ),
            callees = mapOf("/take.mt" to callee, "/find.mt" to finder)
        )
        graph(backend, implicitModel()).use { graph ->
            graph(backend, donorModel()).use { donor ->
                val counted = CountingModelGraph(donor)
                val result = run(graph, caller, mapOf("donor" to ModelArgument.of(counted)))

                assertIs<TransformationExecutionResult.Success>(result, result.toString())
                val after = graph.toModelData()
                assertEquals("b", after.hostOf("x"), "the callee matched in the caller's donor")
                assertTrue("not found" in after.labels(), "the callee without model inputs did not see the donor's z")
                assertEquals(1, counted.reads, "the donor was loaded once, for the caller")
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["mdeo", "tinker"])
    fun `for iterates a list of models and its methods count them`(backend: String) {
        val donors = ident("donors", MODEL_LIST)
        val ast = transformation(
            listOf(modelListInput("donors"), modelListInput("none")),
            TypedForInStatement(
                variable = "d",
                collection = donors,
                doBlock = listOf(
                    match(
                        obj("dc", "Component", "d"),
                        obj("made", "Container", null, "create", set("name", nameOf("dc", scope = 3)))
                    )
                )
            ),
            match(
                where(binary("&&", binary("==", collectionCall(donors, "size", INT), int(2)),
                    binary("&&", collectionCall(donors, "notEmpty", BOOLEAN), collectionCall(ident("none", MODEL_LIST), "isEmpty", BOOLEAN)))),
                obj("counted", "Container", null, "create", set("name", str("two")))
            )
        )

        graph(backend, implicitModel()).use { graph ->
            graph(backend, model(listOf("h"), mapOf("p" to "h"))).use { first ->
                graph(backend, model(listOf("h"), mapOf("q" to "h"))).use { second ->
                    val result = run(graph, ast, mapOf(
                        "donors" to ModelArgument.of(listOf(first, second)),
                        "none" to ModelArgument.of(emptyList())
                    ))

                    assertIs<TransformationExecutionResult.Success>(result, result.toString())
                    assertEquals(
                        listOf("a", "b", "p", "q", "two", "x", "y"), graph.toModelData().labels().map { it!! }.sorted(),
                        "one container per donor, named after its component, and the count of the donors"
                    )
                }
            }
        }
    }

    // ---- arguments of a top-level run ----

    @ParameterizedTest
    @ValueSource(strings = ["mdeo", "tinker"])
    fun `arguments are bound like call arguments`(backend: String) {
        val ast = transformation(
            listOf(
                input("weight", DOUBLE),
                input("kind", KIND),
                TypedTransformationParameter("labels", "in", STRING_SET, collection = "Set"),
                TypedTransformationParameter("rank", "in", INT, defaultValue = int(7))
            ),
            match(obj("made", "Container", null, "create",
                set("name", str("made")), set("weight", ident("weight", DOUBLE)),
                set("kind", ident("kind", KIND)), set("rank", ident("rank", INT)))),
            TypedForInStatement(
                variable = "label",
                collection = ident("labels", STRING_SET),
                doBlock = listOf(match(obj("l", "Container", null, "create", set("name", ident("label", STRING, 2)))))
            )
        )
        graph(backend, implicitModel()).use { graph ->
            val result = run(graph, ast, arguments = mapOf(
                "weight" to 2,
                "kind" to EnumLiteral("Kind", "LARGE"),
                "labels" to listOf("p", "q", "p")
            ))

            assertIs<TransformationExecutionResult.Success>(result, result.toString())
            val made = graph.toModelData().instances.single { (it.properties["name"] as? ModelDataPropertyValue.StringValue)?.value == "made" }
            assertEquals(2.0, (made.properties["weight"] as ModelDataPropertyValue.NumberValue).value)
            assertEquals("LARGE", (made.properties["kind"] as ModelDataPropertyValue.EnumValue).enumEntry)
            assertEquals(7, (made.properties["rank"] as ModelDataPropertyValue.NumberValue).value.toInt(), "the default")
            assertEquals(listOf("p", "q"), graph.toModelData().labels().filter { it == "p" || it == "q" }.map { it!! }.sorted(),
                "the set kept one of the equal labels")
        }
    }

    @Test
    fun `arguments that do not fit the inputs are rejected`() {
        val ast = transformation(
            listOf(input("rank", INT), TypedTransformationParameter("c", "in", COMPONENT, isElement = true), modelInput("donor")),
            match(obj("x", "Component"))
        )
        graph("tinker", implicitModel()).use { graph ->
            val engine = TransformationEngine.create(graph, ast)
            assertThrows<IllegalArgumentException> { engine.execute(mapOf("unknown" to 1)) }
            assertThrows<IllegalArgumentException> { engine.execute(mapOf("c" to "x")) }
            assertThrows<IllegalArgumentException> { engine.execute(mapOf("donor" to 1)) }
            assertThrows<IllegalArgumentException> { engine.execute(mapOf("rank" to 1.5)) }
            assertThrows<IllegalArgumentException> { engine.execute(mapOf("rank" to "one")) }
            assertThrows<IllegalArgumentException> { engine.execute(emptyMap(), mapOf("rank" to ModelArgument.of(graph))) }
            assertThrows<IllegalArgumentException> {
                engine.execute(emptyMap(), mapOf("donor" to ModelArgument.of(listOf(graph))))
            }
            val missing = engine.execute(mapOf("rank" to 1))
            assertIs<TransformationExecutionResult.Failure>(missing, "required inputs were not passed")
            assertTrue(missing.reason.contains("Required input"), missing.reason)
        }
    }

    // ---- no overhead without model inputs ----

    @ParameterizedTest
    @ValueSource(strings = ["mdeo", "tinker"])
    fun `a run without model inputs emits exactly the traversals it emitted before`(backend: String) {
        // read-only, so that vertex IDs and with them the traversals stay comparable
        val plain = transformation(
            emptyList(),
            match(
                obj("c", "Component", null, null, equal("name", str("x"))), obj("h", "Container"), host("c", "h"),
                forbid(obj("o", "Component"), host("o", "h"), where(binary("==", nameOf("o", scope = 2), str("y"))))
            )
        )
        val withDonor = transformation(
            listOf(modelInput("donor")),
            TypedCallStatement(callee = "/plain.mt", name = "plain"),
            callees = mapOf("/plain.mt" to plain)
        )
        graph(backend, implicitModel()).use { implicit ->
            graph(backend, donorModel()).use { donor ->
                val graph = RecordingModelGraph(implicit)
                fun record(block: () -> TransformationExecutionResult): List<String> {
                    graph.recorded.clear()
                    assertIs<TransformationExecutionResult.Success>(block())
                    return graph.recorded.toList()
                }

                val before = record { TransformationEngine.create(graph, plain).execute() }
                assertTrue(before.isNotEmpty())
                assertTrue(before.none { ModelGraph.MODEL_SLOT_KEY in it }, before.toString())
                assertEquals(before, record { TransformationEngine.create(graph, plain).execute(emptyMap(), emptyMap()) })

                // the same callee while the caller's donor is loaded: filtered to the implicit model
                val loaded = record { run(graph, withDonor, mapOf("donor" to ModelArgument.of(donor))) }
                assertTrue(loaded.any { ModelGraph.MODEL_SLOT_KEY in it }, loaded.toString())
                assertTrue(loaded.size == before.size)

                assertEquals(before, record { TransformationEngine.create(graph, plain).execute() },
                    "once the donor is unloaded the traversals are the original ones again")
            }
        }
    }

    /** A graph whose traversal sources record every root traversal they run, as a string of its steps. */
    private class RecordingModelGraph(private val delegate: ModelGraph) : ModelGraph by delegate {
        val recorded = mutableListOf<String>()

        override fun traversal(): GraphTraversalSource = delegate.traversal().withStrategies(Recorder(recorded))
    }

    /** Records each root traversal after all other strategies ran. */
    private class Recorder(private val sink: MutableList<String>) :
        AbstractTraversalStrategy<TraversalStrategy.VerificationStrategy>(),
        TraversalStrategy.VerificationStrategy {
        override fun apply(traversal: Traversal.Admin<*, *>) {
            if (traversal.isRoot) sink += traversal.toString()
        }
    }

    /** A donor that counts how often its model is read, so how often it is loaded. */
    private class CountingModelGraph(private val delegate: ModelGraph) : ModelGraph by delegate {
        var reads = 0

        override fun toModelData(): ModelData = delegate.toModelData().also { reads++ }
    }

    companion object {
        private const val MM = "/mm/arch.mm"
        private const val STRING = 1
        private const val BOOLEAN = 2
        private const val INT = 3
        private const val DOUBLE = 4
        private const val MODEL = 5
        private const val MODEL_LIST = 6
        private const val COMPONENT = 7
        private const val CONTAINER = 8
        private const val KIND = 9
        private const val STRING_SET = 10
    }
}
