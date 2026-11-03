package com.mdeo.optimizer.crossover

import com.mdeo.expression.ast.expressions.TypedBinaryExpression
import com.mdeo.expression.ast.expressions.TypedExpression
import com.mdeo.expression.ast.expressions.TypedIdentifierExpression
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
import com.mdeo.modeltransformation.ast.TypedAst
import com.mdeo.modeltransformation.ast.TypedTransformationParameter
import com.mdeo.modeltransformation.ast.TypedTransformationSignature
import com.mdeo.modeltransformation.ast.patterns.TypedPattern
import com.mdeo.modeltransformation.ast.patterns.TypedPatternElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLink
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLinkElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLinkEnd
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstance
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstanceElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternPropertyAssignment
import com.mdeo.modeltransformation.ast.patterns.TypedPatternWhereClauseElement
import com.mdeo.modeltransformation.ast.patterns.TypedWhereClause
import com.mdeo.modeltransformation.ast.statements.TypedForInStatement
import com.mdeo.modeltransformation.ast.statements.TypedMatchStatement
import com.mdeo.modeltransformation.graph.ModelGraph
import com.mdeo.modeltransformation.graph.mdeo.MdeoModelGraph
import com.mdeo.optimizer.guidance.GuidanceFunction
import com.mdeo.optimizer.solution.Solution

/**
 * A small problem for crossover tests, with its operators built as typed ASTs the way the
 * transformation language's converter emits them.
 *
 * Metamodel: `Component { name } --host--> Container { name, weight: double?, rank: int?, kind: Kind? }`,
 * `enum Kind { SMALL, LARGE }`. The start model has containers `a` and `b` and components
 * `c1`…`c4`, all on `a`.
 */
object CrossoverFixtures {

    const val MM = "/mm/arch.mm"

    const val MOVE = "/ops/move.mt"
    const val TAG = "/ops/tag.mt"
    const val TAKE_HOSTING = "/ops/take-hosting.mt"
    const val COPY_NAMES = "/ops/copy-names.mt"
    const val VOTE = "/ops/vote.mt"
    const val NEVER = "/ops/never.mt"
    const val MIXED = "/ops/mixed.mt"

    val metamodelData = MetamodelData(
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

    val metamodel: Metamodel by lazy { Metamodel.compile(metamodelData) }

    private fun named(name: String, className: String) =
        ModelDataInstance(name, className, mapOf("name" to ModelDataPropertyValue.StringValue(name)))

    /** A model of [containers] and of components hosted as [hosting] (`component to container`). */
    fun model(containers: List<String>, hosting: Map<String, String>) = ModelData(
        metamodelPath = MM,
        instances = containers.map { named(it, "Container") } + hosting.keys.map { named(it, "Component") },
        links = hosting.map { (component, container) -> ModelDataLink(component, "host", container, null) }
    )

    /** The start model: every component on `a`. */
    val startModel: ModelData = model(listOf("a", "b"), (1..4).associate { "c$it" to "a" })

    fun graph(data: ModelData = startModel): ModelGraph = MdeoModelGraph.create(data, metamodel)

    fun solution(data: ModelData = startModel) = Solution(graph(data))

    /** Where each component of [graph] is hosted. */
    fun hosting(graph: ModelGraph): Map<String, String> {
        val data = graph.toModelData()
        return data.links.filter { it.sourceProperty == "host" }.associate { it.sourceName to it.targetName }
    }

    /** Container names of [graph], sorted. */
    fun containers(graph: ModelGraph): List<String> =
        graph.toModelData().instances.filter { it.className == "Container" }
            .map { (it.properties["name"] as ModelDataPropertyValue.StringValue).value }.sorted()

    /** Objective: the number of components on container `a` (minimised). */
    class ComponentsOnA : GuidanceFunction {
        override val name = "componentsOnA"
        override fun computeFitness(solution: Solution): Double =
            hosting(solution.modelGraph).values.count { it == "a" }.toDouble()
    }

    /**
     * One of two conflicting objectives (`v` and `1 - v` for a value `v` per solution, drawn from
     * its graph's identity): no solution dominates another, so every solution is in the first
     * front and no objective is the same for all. Keeps indicator-based algorithms (IBEA,
     * SMS-EMOA), which can neither normalise a constant objective nor a front of one solution,
     * running on models that barely change.
     *
     * @param flip Whether this is the `1 - v` objective.
     */
    class TradeOff(private val flip: Boolean) : GuidanceFunction {
        override val name = if (flip) "tradeOffB" else "tradeOffA"
        override fun computeFitness(solution: Solution): Double {
            val v = (System.identityHashCode(solution.modelGraph) % 10_007) / 10_007.0
            return if (flip) 1 - v else v
        }
    }

    // ---- typed AST ----

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

    private fun builtin(name: String) = ClassTypeRef("builtin", name, false)

    private val types: List<ReturnType> = listOf(
        VoidType(),
        builtin("string"),
        builtin("boolean"),
        builtin("int"),
        builtin("double"),
        builtin("model"),
        ClassTypeRef("builtin", "List", false, mapOf("T" to builtin("model"))),
        ClassTypeRef("class$MM", "Component", false),
        ClassTypeRef("class$MM", "Container", false),
        ClassTypeRef("enum$MM", "Kind", false),
        ClassTypeRef("builtin", "Set", false, mapOf("T" to builtin("string")))
    )

    private fun ident(name: String, type: Int, scope: Int = 1) =
        TypedIdentifierExpression(evalType = type, name = name, scope = scope)

    private fun nameOf(name: String, type: Int = COMPONENT, scope: Int = 1) =
        TypedMemberAccessExpression(evalType = STRING, expression = ident(name, type, scope), member = "name", isNullChaining = false)

    private fun str(value: String) = TypedStringLiteralExpression(evalType = STRING, value = value)

    private fun binary(operator: String, left: TypedExpression, right: TypedExpression) =
        TypedBinaryExpression(evalType = BOOLEAN, operator = operator, left = left, right = right)

    private fun obj(
        name: String,
        className: String,
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

    private fun match(vararg elements: TypedPatternElement) = TypedMatchStatement(pattern = TypedPattern(elements.toList()))

    private fun input(name: String, type: Int, default: TypedExpression? = null, collection: String? = null) =
        TypedTransformationParameter(name, "in", type, defaultValue = default, collection = collection)

    private fun modelInput(name: String) = TypedTransformationParameter(name, "in", MODEL, isModel = true)

    private fun modelListInput(name: String) =
        TypedTransformationParameter(name, "in", MODEL_LIST, collection = "List", isModel = true)

    private fun transformation(file: String, parameters: List<TypedTransformationParameter>, vararg statements: com.mdeo.modeltransformation.ast.statements.TypedTransformationStatement) =
        TypedAst(types, MM, statements.toList(), TypedTransformationSignature(file, parameters))

    /** Mutation: moves a component to another container. */
    val move: TypedAst = transformation(
        "move.mt", emptyList(),
        match(
            obj("c", "Component"), obj("old", "Container"), obj("h", "Container"),
            host("c", "old"), host("c", "old", "delete"), host("c", "h", "create")
        )
    )

    /**
     * Mutation with inputs set from the config: creates a container `tagged` with
     * `weight = weight`, `kind = kind` and `rank = labels.size()`.
     */
    val tag: TypedAst = transformation(
        "tag.mt",
        listOf(input("weight", DOUBLE), input("kind", KIND), input("labels", STRING_SET, collection = "Set")),
        match(
            obj(
                "made", "Container", null, "create",
                set("name", str("tagged")), set("weight", ident("weight", DOUBLE)), set("kind", ident("kind", KIND)),
                set("rank", TypedMemberCallExpression(
                    evalType = INT, expression = ident("labels", STRING_SET), member = "size",
                    isNullChaining = false, overload = "", arguments = emptyList()
                ))
            )
        )
    )

    /**
     * Crossover (`in donor: model`): an implicit component named like a donor component moves to
     * the container named like that donor component's host.
     */
    val takeHosting: TypedAst = transformation(
        "take-hosting.mt", listOf(modelInput("donor")),
        match(
            obj("dc", "Component", "donor"), obj("dh", "Container", "donor"), host("dc", "dh"),
            obj("c", "Component"), obj("h", "Container"), obj("old", "Container"), host("c", "old"),
            where(binary("&&", binary("==", nameOf("c"), nameOf("dc")), binary("==", nameOf("h", CONTAINER), nameOf("dh", CONTAINER)))),
            host("c", "old", "delete"), host("c", "h", "create")
        )
    )

    /** Crossover (`in donor: model`) that always applies: creates a container named after a donor component. */
    val copyNames: TypedAst = transformation(
        "copy-names.mt", listOf(modelInput("donor")),
        match(obj("dc", "Component", "donor"), obj("made", "Container", null, "create", set("name", nameOf("dc"))))
    )

    /**
     * Variadic crossover (`in donors: List<model>`): for every donor, creates a container named
     * after one of its components.
     */
    val vote: TypedAst = transformation(
        "vote.mt", listOf(modelListInput("donors")),
        TypedForInStatement(
            variable = "d",
            collection = ident("donors", MODEL_LIST),
            doBlock = listOf(
                match(obj("dc", "Component", "d"), obj("made", "Container", null, "create", set("name", nameOf("dc", scope = 3))))
            )
        )
    )

    /** Crossover (`in donor: model`) that never applies: the donor has no component `nobody`. */
    val never: TypedAst = transformation(
        "never.mt", listOf(modelInput("donor")),
        match(obj("dc", "Component", "donor", null, equal("name", str("nobody"))))
    )

    /** Not a crossover operator: mixes a model input and a list of models. */
    val mixed: TypedAst = transformation(
        "mixed.mt", listOf(modelInput("donor"), modelListInput("others")),
        match(obj("dc", "Component", "donor"))
    )

    /** Every AST by path. */
    val asts: Map<String, TypedAst> = mapOf(
        MOVE to move, TAG to tag, TAKE_HOSTING to takeHosting, COPY_NAMES to copyNames,
        VOTE to vote, NEVER to never, MIXED to mixed
    )
}
