package com.mdeo.modeltransformation.runtime

import com.mdeo.expression.ast.expressions.*
import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.expression.ast.types.ReturnType
import com.mdeo.expression.ast.types.VoidType
import com.mdeo.metamodel.Metamodel
import com.mdeo.metamodel.data.*
import com.mdeo.modeltransformation.ast.EdgeLabelUtils
import com.mdeo.modeltransformation.ast.TypedAst
import com.mdeo.modeltransformation.ast.expressions.TypedLambdaExpression
import com.mdeo.modeltransformation.ast.patterns.*
import com.mdeo.modeltransformation.compiler.ExpressionCompilerRegistry
import com.mdeo.modeltransformation.compiler.registry.TypeRegistry
import com.mdeo.modeltransformation.compiler.registry.gremlinType
import com.mdeo.modeltransformation.graph.tinker.TinkerModelGraph
import com.mdeo.modeltransformation.runtime.match.MatchExecutor
import com.mdeo.modeltransformation.runtime.match.MatchResult
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests that a matched instance can have its property assigned from a traversal expression
 * referencing another matched instance's property.
 *
 * Scenario (from user's knapsack model transformation):
 *
 *   match {
 *       knapsack: Knapsack {}
 *       item: Item {
 *           weight = knapsack.capacity
 *       }
 *   }
 *
 * Previously this silently removed the `weight` property on TinkerModelGraph because
 * `getPropertyValue()` evaluated the traversal eagerly via inject(null) without access
 * to the bound match variables.
 */
class MatchBlockPropertyFromTraversalTest {

    private lateinit var graph: TinkerGraph
    private lateinit var g: GraphTraversalSource
    private lateinit var engine: TransformationEngine
    private val executor = MatchExecutor()

    private val metamodelData = MetamodelData(
        classes = listOf(
            ClassData(
                name = "Knapsack",
                isAbstract = false,
                extends = emptyList(),
                properties = listOf(
                    PropertyData(name = "capacity", primitiveType = "int", multiplicity = MultiplicityData.single())
                )
            ),
            ClassData(
                name = "Item",
                isAbstract = false,
                extends = emptyList(),
                properties = listOf(
                    PropertyData(name = "weight", primitiveType = "int", multiplicity = MultiplicityData.single())
                )
            )
        ),
        associations = listOf(
            AssociationData(
                source = AssociationEndData(
                    className = "Item",
                    name = "knapsack",
                    multiplicity = MultiplicityData.single()
                ),
                operator = "<>->",
                target = AssociationEndData(
                    className = "Knapsack",
                    name = "items",
                    multiplicity = MultiplicityData.many()
                )
            )
        )
    )

    private val metamodel = Metamodel.compile(metamodelData)

    // types[0]: void
    // types[1]: int   (capacity and weight)
    // types[2]: Knapsack  (knapsack identifier)
    // types[3]: Item  (item identifier)
    // types[4]: Collection  (return type of Knapsack.items)
    // types[5]: boolean  (where clause result)
    // types[6]: Any?    (lambda evalType placeholder)
    private val types: List<ReturnType> = listOf(
        VoidType(),
        ClassTypeRef(`package` = "builtin", type = "int", isNullable = false),
        ClassTypeRef(`package` = "class", type = "Knapsack", isNullable = false),
        ClassTypeRef(`package` = "class", type = "Item", isNullable = false),
        ClassTypeRef(`package` = "builtin", type = "Collection", isNullable = false),
        ClassTypeRef(`package` = "builtin", type = "boolean", isNullable = false),
        ClassTypeRef(`package` = "builtin", type = "Any", isNullable = true)
    )

    private fun graphKey(className: String, propName: String): String =
        "prop_${metamodel.metadata.classes[className]!!.propertyFields[propName]!!.fieldIndex}"

    @BeforeEach
    fun setup() {
        graph = TinkerGraph.open()
        g = graph.traversal()

        val expressionRegistry = ExpressionCompilerRegistry.createDefaultRegistry()
        val statementRegistry = StatementExecutorRegistry.createDefaultRegistry()

        engine = TransformationEngine(
            modelGraph = TinkerModelGraph.wrap(graph, metamodel),
            ast = TypedAst(types = emptyList(), metamodelPath = "", statements = emptyList()),
            expressionCompilerRegistry = expressionRegistry,
            statementExecutorRegistry = statementRegistry
        )

        val typesField = TransformationEngine::class.java.getDeclaredField("types")
        typesField.isAccessible = true
        typesField.set(engine, types)

        val typeRegistry = TypeRegistry.GLOBAL
        typeRegistry.register(
            gremlinType("class", "Knapsack")
                .graphProperty("capacity")
                .association(
                    "items",
                    EdgeLabelUtils.computeEdgeLabel("knapsack", "items"),
                    isOutgoing = false,
                    isNullable = false
                )
                .build()
        )
        typeRegistry.register(
            gremlinType("class", "Item")
                .graphProperty("weight")
                .build()
        )
    }

    @AfterEach
    fun tearDown() {
        graph.close()
    }

    /**
     * Verifies that `item.weight = knapsack.capacity` on a matched instance correctly
     * copies the capacity value rather than removing the weight property.
     */
    @Test
    fun `matched instance property assignment from another matched instance traversal`() {
        val capacityKey = graphKey("Knapsack", "capacity")
        val weightKey = graphKey("Item", "weight")

        // Set up: knapsack with capacity=50, item with weight=0
        g.addV("Knapsack").property(capacityKey, 50).next()
        g.addV("Item").property(weightKey, 0).next()

        // Pattern:
        //   knapsack: Knapsack {}
        //   item: Item {
        //       weight = knapsack.capacity
        //   }
        val pattern = TypedPattern(
            elements = listOf(
                TypedPatternObjectInstanceElement(
                    objectInstance = TypedPatternObjectInstance(
                        name = "knapsack",
                        className = "Knapsack",
                        modifier = null,
                        properties = emptyList()
                    )
                ),
                TypedPatternObjectInstanceElement(
                    objectInstance = TypedPatternObjectInstance(
                        name = "item",
                        className = "Item",
                        modifier = null,
                        properties = listOf(
                            TypedPatternPropertyAssignment(
                                propertyName = "weight",
                                operator = "=",
                                value = TypedMemberAccessExpression(
                                    evalType = 1, // int
                                    expression = TypedIdentifierExpression(
                                        evalType = 2, // Knapsack
                                        name = "knapsack",
                                        scope = 1
                                    ),
                                    member = "capacity",
                                    isNullChaining = false
                                )
                            )
                        )
                    )
                )
            )
        )

        val context = TransformationExecutionContext.empty()
        val result = executor.executeMatch(pattern, context, engine)

        assertTrue(result is MatchResult.Matched, "Match should succeed but got: $result")
        result as MatchResult.Matched

        val itemId = result.instanceMappings["item"]?.rawId
        assertNotNull(itemId, "item should be mapped to a vertex")

        val itemVertex = g.V(itemId).next()
        val weightProp = itemVertex.property<Int>(weightKey)

        assertTrue(weightProp.isPresent, "weight property must still exist (was removed before fix)")
        assertEquals(50, weightProp.value(), "item.weight should equal knapsack.capacity (50)")
    }

    /**
     * Verifies that a constant property assignment on a matched instance still works.
     */
    @Test
    fun `matched instance property assignment from constant still works`() {
        val weightKey = graphKey("Item", "weight")
        val capacityKey = graphKey("Knapsack", "capacity")

        g.addV("Knapsack").property(capacityKey, 10).next()
        g.addV("Item").property(weightKey, 0).next()

        val pattern = TypedPattern(
            elements = listOf(
                TypedPatternObjectInstanceElement(
                    objectInstance = TypedPatternObjectInstance(
                        name = "knapsack",
                        className = "Knapsack",
                        modifier = null,
                        properties = emptyList()
                    )
                ),
                TypedPatternObjectInstanceElement(
                    objectInstance = TypedPatternObjectInstance(
                        name = "item",
                        className = "Item",
                        modifier = null,
                        properties = listOf(
                            TypedPatternPropertyAssignment(
                                propertyName = "weight",
                                operator = "=",
                                value = TypedIntLiteralExpression(
                                    evalType = 1, // int
                                    value = "99"
                                )
                            )
                        )
                    )
                )
            )
        )

        val context = TransformationExecutionContext.empty()
        val result = executor.executeMatch(pattern, context, engine)

        assertTrue(result is MatchResult.Matched, "Match should succeed")
        result as MatchResult.Matched

        val itemId = result.instanceMappings["item"]?.rawId
        assertNotNull(itemId)

        val weightProp = g.V(itemId).next().property<Int>(weightKey)
        assertTrue(weightProp.isPresent, "weight property should exist")
        assertEquals(99, weightProp.value(), "item.weight should be set to the constant 99")
    }

    /**
     * Verifies `item.weight = knapsack.items.first().weight` — a chained traversal that
     * crosses an association edge in the reverse direction, calls `first()` on the
     * resulting collection, then reads a property.
     *
     * Previously this threw "The provided traverser does not map to a value" because
     * AddPropertyStep passed the vertex to the nested traversal without outer path context.
     * The fix pre-evaluates the traversal with `.as(valueLabel)` (like list properties do)
     * and references `valueLabel` via a simple `select` in the property step.
     *
     * Also exercises the edge-label fix in TransformationEngine.createTypeRegistry:
     * Knapsack.items must use the stored edge label `knapsack`_`items` (source-first),
     * not `items`_`knapsack` (target-first).
     */
    @Test
    fun `matched instance property assignment from chained traversal through association with first()`() {
        val weightKey = graphKey("Item", "weight")
        val capacityKey = graphKey("Knapsack", "capacity")
        val edgeLabel = EdgeLabelUtils.computeEdgeLabel("knapsack", "items")

        // Graph: one knapsack, one item connected to it (weight=42)
        val knapsack = g.addV("Knapsack").property(capacityKey, 50).next()
        val item = g.addV("Item").property(weightKey, 42).next()
        // Edge goes FROM Item (source/out) TO Knapsack (target/in)
        g.addE(edgeLabel).from(item).to(knapsack).next()

        // Pattern:
        //   knapsack: Knapsack {}
        //   item: Item {
        //       weight = knapsack.items.first().weight
        //   }
        //
        // knapsack.items  → TypedMemberAccessExpression(evalType=4 Collection)
        // .first()        → TypedMemberCallExpression(evalType=3 Item)
        // .weight         → TypedMemberAccessExpression(evalType=1 int)
        val weightFromTraversal = TypedMemberAccessExpression(
            evalType = 1, // int
            expression = TypedMemberCallExpression(
                evalType = 3, // Item
                expression = TypedMemberAccessExpression(
                    evalType = 4, // Collection
                    expression = TypedIdentifierExpression(
                        evalType = 2, // Knapsack
                        name = "knapsack",
                        scope = 1
                    ),
                    member = "items",
                    isNullChaining = false
                ),
                member = "first",
                isNullChaining = false,
                overload = "",
                arguments = emptyList()
            ),
            member = "weight",
            isNullChaining = false
        )

        val pattern = TypedPattern(
            elements = listOf(
                TypedPatternObjectInstanceElement(
                    objectInstance = TypedPatternObjectInstance(
                        name = "knapsack",
                        className = "Knapsack",
                        modifier = null,
                        properties = emptyList()
                    )
                ),
                TypedPatternObjectInstanceElement(
                    objectInstance = TypedPatternObjectInstance(
                        name = "item",
                        className = "Item",
                        modifier = null,
                        properties = listOf(
                            TypedPatternPropertyAssignment(
                                propertyName = "weight",
                                operator = "=",
                                value = weightFromTraversal
                            )
                        )
                    )
                )
            )
        )

        val context = TransformationExecutionContext.empty()
        val result = executor.executeMatch(pattern, context, engine)

        assertTrue(result is MatchResult.Matched, "Match should succeed but got: $result")
        result as MatchResult.Matched

        val itemId = result.instanceMappings["item"]?.rawId
        assertNotNull(itemId, "item should be mapped to a vertex")

        val weightProp = g.V(itemId).next().property<Int>(weightKey)
        assertTrue(weightProp.isPresent, "weight property must still exist after update")
        assertEquals(42, weightProp.value(), "item.weight should equal knapsack.items.first().weight (42)")
    }

    /**
     * Regression test: `where knapsack.items.map((item) => item.weight).sum() <= knapsack.capacity`
     * previously found no matches because the reducing barrier step in sum() (fold) lost traverser
     * path context, making select("knapsack_label") on the right side return nothing.
     *
     * The fix saves the context traverser before evaluating the left operand, then restores it
     * before evaluating the right operand.
     */
    @Test
    fun `where clause sum on left side compares against property on right`() {
        val capacityKey = graphKey("Knapsack", "capacity")
        val edgeLabel = EdgeLabelUtils.computeEdgeLabel("knapsack", "items")

        // Case 1: no items, sum=0 <= capacity=1000 → should match
        val knapsack = g.addV("Knapsack").property(capacityKey, 1000).next()

        // where knapsack.items.map((item) => item.weight).sum() <= knapsack.capacity
        val sumExpr = TypedMemberCallExpression(
            evalType = 1, // int
            expression = TypedMemberCallExpression(
                evalType = 4, // Collection
                expression = TypedMemberAccessExpression(
                    evalType = 4, // Collection
                    expression = TypedIdentifierExpression(evalType = 2, name = "knapsack", scope = 1),
                    member = "items",
                    isNullChaining = false
                ),
                member = "map",
                isNullChaining = false,
                overload = "",
                arguments = listOf(
                    TypedCallArgument(
                        value = TypedLambdaExpression(
                            evalType = 6, // lambda placeholder
                            parameters = listOf("item"),
                            body = TypedMemberAccessExpression(
                                evalType = 1, // int
                                expression = TypedIdentifierExpression(evalType = 3, name = "item", scope = 2),
                                member = "weight",
                                isNullChaining = false
                            )
                        ),
                        parameterType = 6
                    )
                )
            ),
            member = "sum",
            isNullChaining = false,
            overload = "",
            arguments = emptyList()
        )
        val capacityExpr = TypedMemberAccessExpression(
            evalType = 1, // int
            expression = TypedIdentifierExpression(evalType = 2, name = "knapsack", scope = 1),
            member = "capacity",
            isNullChaining = false
        )
        val whereExpr = TypedBinaryExpression(
            evalType = 5, // boolean
            operator = "<=",
            left = sumExpr,
            right = capacityExpr
        )
        val pattern = TypedPattern(
            elements = listOf(
                TypedPatternObjectInstanceElement(
                    objectInstance = TypedPatternObjectInstance(
                        name = "knapsack", className = "Knapsack", modifier = null, properties = emptyList()
                    )
                ),
                TypedPatternWhereClauseElement(whereClause = TypedWhereClause(expression = whereExpr))
            )
        )

        val context = TransformationExecutionContext.empty()
        val result = executor.executeMatch(pattern, context, engine)
        assertTrue(result is MatchResult.Matched, "sum()=0 <= capacity=1000 should match, got: $result")

        // Case 2: add an item with weight=500, sum=500 <= 1000 → should still match
        val weightKey = graphKey("Item", "weight")
        val item1 = g.addV("Item").property(weightKey, 500).next()
        g.addE(edgeLabel).from(item1).to(knapsack).next()

        val context2 = TransformationExecutionContext.empty()
        val result2 = executor.executeMatch(pattern, context2, engine)
        assertTrue(result2 is MatchResult.Matched, "sum()=500 <= capacity=1000 should match, got: $result2")

        // Case 3: add another item with weight=600, sum=1100 > 1000 → should NOT match
        val item2 = g.addV("Item").property(weightKey, 600).next()
        g.addE(edgeLabel).from(item2).to(knapsack).next()

        val context3 = TransformationExecutionContext.empty()
        val result3 = executor.executeMatch(pattern, context3, engine)
        assertTrue(result3 is MatchResult.NoMatch, "sum()=1100 > capacity=1000 should not match, got: $result3")
    }
}
