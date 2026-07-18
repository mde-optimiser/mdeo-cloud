package com.mdeo.modeltransformation.runtime

import com.mdeo.expression.ast.expressions.*
import com.mdeo.metamodel.data.AssociationData
import com.mdeo.metamodel.data.AssociationEndData
import com.mdeo.metamodel.data.ClassData
import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.metamodel.Metamodel
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.metamodel.data.MultiplicityData
import com.mdeo.metamodel.data.PropertyData
import com.mdeo.expression.ast.types.VoidType
import com.mdeo.expression.ast.types.ReturnType
import com.mdeo.modeltransformation.ast.TypedAst
import com.mdeo.modeltransformation.ast.patterns.*
import com.mdeo.modeltransformation.ast.statements.TypedMatchStatement
import com.mdeo.modeltransformation.ast.statements.TypedWhileExpressionStatement
import com.mdeo.modeltransformation.compiler.ExpressionCompilerRegistry
import com.mdeo.modeltransformation.compiler.VariableBinding
import com.mdeo.modeltransformation.graph.tinker.TinkerModelGraph
import com.mdeo.modeltransformation.compiler.registry.TypeRegistry
import com.mdeo.modeltransformation.compiler.registry.gremlinType
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlin.test.assertEquals
import kotlin.test.assertIs
import java.util.concurrent.TimeUnit

/**
 * Tests for the variable-reassignment pattern element (`name = value`).
 *
 * A reassignment updates a variable declared in an enclosing scope. Its right-hand side
 * reads the incoming (old) value; the new value is committed to the declaring scope after
 * the match and is visible both to the rest of that match block and to subsequent
 * statements / loop iterations.
 */
class VariableReassignmentTest {

    private lateinit var graph: TinkerGraph
    private lateinit var g: GraphTraversalSource
    private lateinit var engine: TransformationEngine
    private lateinit var context: TransformationExecutionContext

    // Type indices:
    // 0: void, 1: string, 2: boolean, 3: int, 4: List<Room>, 5: House, 6: Room
    private val types: List<ReturnType> = listOf(
        VoidType(),
        ClassTypeRef(`package` = "builtin", type = "string", isNullable = false),
        ClassTypeRef(`package` = "builtin", type = "boolean", isNullable = false),
        ClassTypeRef(`package` = "builtin", type = "int", isNullable = false),
        ClassTypeRef(
            `package` = "builtin", type = "List", isNullable = false, typeArgs = mapOf(
                "T" to ClassTypeRef(`package` = "class", type = "Room", isNullable = false, typeArgs = emptyMap())
            )
        ),
        ClassTypeRef(`package` = "class", type = "House", isNullable = false),
        ClassTypeRef(`package` = "class", type = "Room", isNullable = false)
    )

    private val metamodelData = MetamodelData(
        classes = listOf(
            ClassData(
                name = "House",
                isAbstract = false,
                extends = emptyList(),
                properties = listOf(
                    PropertyData(name = "address", primitiveType = "string", multiplicity = MultiplicityData.single())
                )
            ),
            ClassData(
                name = "Room",
                isAbstract = false,
                extends = emptyList(),
                properties = listOf(
                    PropertyData(name = "value", primitiveType = "int", multiplicity = MultiplicityData.single())
                )
            )
        ),
        associations = listOf(
            AssociationData(
                source = AssociationEndData(className = "House", name = "rooms", multiplicity = MultiplicityData.many()),
                operator = "<>->",
                target = AssociationEndData(className = "Room", name = "house", multiplicity = MultiplicityData.single())
            )
        )
    )

    private val metamodel = Metamodel.compile(metamodelData)

    private fun graphKey(className: String, propName: String): String =
        "prop_${metamodel.metadata.classes[className]!!.propertyFields[propName]!!.fieldIndex}"

    @BeforeEach
    fun setup() {
        graph = TinkerGraph.open()
        g = graph.traversal()

        val typeRegistry = TypeRegistry.GLOBAL
        typeRegistry.register(gremlinType("class", "House").graphProperty("address").build())
        typeRegistry.register(gremlinType("class", "Room").graphProperty("value").build())

        val ast = TypedAst(types = types, metamodelPath = "", statements = emptyList())
        engine = TransformationEngine(
            modelGraph = TinkerModelGraph.wrap(graph, metamodel),
            ast = ast,
            expressionCompilerRegistry = ExpressionCompilerRegistry.createDefaultRegistry(),
            statementExecutorRegistry = StatementExecutorRegistry.createDefaultRegistry()
        )
        context = TransformationExecutionContext.empty()
    }

    @AfterEach
    fun tearDown() {
        graph.close()
    }

    /** Identifier reference to `counter` (int), declared at scope level 1. */
    private fun counterRef() = TypedIdentifierExpression(evalType = 3, name = "counter", scope = 1)

    /**
     * First match: bind `house` and declare `var counter = 0` at scope level 1.
     */
    private fun declareHouseAndCounter() {
        g.addV("House").property(graphKey("House", "address"), "addr").next()
        val matchStatement = TypedMatchStatement(
            pattern = TypedPattern(
                elements = listOf(
                    TypedPatternObjectInstanceElement(
                        objectInstance = TypedPatternObjectInstance(
                            modifier = null, name = "house", className = "House", properties = emptyList()
                        )
                    ),
                    TypedPatternVariableElement(
                        variable = TypedPatternVariable(
                            name = "counter", value = TypedIntLiteralExpression(evalType = 3, value = "0")
                        )
                    )
                )
            )
        )
        val result = engine.executeStatement(matchStatement, context)
        assertIs<TransformationExecutionResult.Success>(result)
    }

    /**
     * A reassignment `counter = counter + 1` must read the old value and store the new one,
     * so a `while (counter < 5)` loop terminates after committing counter = 5.
     */
    @Test
    @Timeout(value = 40, unit = TimeUnit.SECONDS)
    fun `reassignment increments counter across while-expression iterations`() {
        declareHouseAndCounter()

        val whileStatement = TypedWhileExpressionStatement(
            condition = TypedBinaryExpression(
                evalType = 2, operator = "<",
                left = counterRef(),
                right = TypedIntLiteralExpression(evalType = 3, value = "5")
            ),
            block = listOf(
                TypedMatchStatement(
                    pattern = TypedPattern(
                        elements = listOf(
                            TypedPatternVariableReassignmentElement(
                                reassignment = TypedPatternVariableReassignment(
                                    name = "counter",
                                    value = TypedBinaryExpression(
                                        evalType = 3, operator = "+",
                                        left = counterRef(),
                                        right = TypedIntLiteralExpression(evalType = 3, value = "1")
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )

        val result = engine.executeStatement(whileStatement, context)
        assertIs<TransformationExecutionResult.Success>(result)

        val binding = context.variableScope.getVariable("counter")
        assertIs<VariableBinding.ValueBinding>(binding)
        assertEquals(5L, (binding.value as Number).toLong(), "counter should have been incremented to 5")
    }

    /**
     * The reassigned value must be visible to the rest of the same match block: each new
     * room's `value` property is set from the just-incremented counter, producing 1..5.
     */
    @Test
    @Timeout(value = 40, unit = TimeUnit.SECONDS)
    fun `reassigned value is visible within the same match block`() {
        declareHouseAndCounter()

        val loopBody = TypedMatchStatement(
            pattern = TypedPattern(
                elements = listOf(
                    TypedPatternVariableReassignmentElement(
                        reassignment = TypedPatternVariableReassignment(
                            name = "counter",
                            value = TypedBinaryExpression(
                                evalType = 3, operator = "+",
                                left = counterRef(),
                                right = TypedIntLiteralExpression(evalType = 3, value = "1")
                            )
                        )
                    ),
                    TypedPatternLinkElement(
                        link = TypedPatternLink(
                            modifier = "create",
                            source = TypedPatternLinkEnd(objectName = "house", propertyName = "rooms"),
                            target = TypedPatternLinkEnd(objectName = "newRoom", propertyName = "house")
                        )
                    ),
                    TypedPatternObjectInstanceElement(
                        objectInstance = TypedPatternObjectInstance(
                            modifier = "create", name = "newRoom", className = "Room",
                            properties = listOf(
                                TypedPatternPropertyAssignment(
                                    propertyName = "value", operator = "=", value = counterRef()
                                )
                            )
                        )
                    )
                )
            )
        )

        val whileStatement = TypedWhileExpressionStatement(
            condition = TypedBinaryExpression(
                evalType = 2, operator = "<",
                left = counterRef(),
                right = TypedIntLiteralExpression(evalType = 3, value = "5")
            ),
            block = listOf(loopBody)
        )

        val result = engine.executeStatement(whileStatement, context)
        assertIs<TransformationExecutionResult.Success>(result)

        val roomValues = g.V().hasLabel("Room")
            .values<Any>(graphKey("Room", "value")).toList()
            .map { (it as Number).toLong() }.sorted()
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), roomValues, "Each room should record the incremented counter")
    }

    /**
     * A reassignment whose right-hand side references a matched instance's property must be
     * ordered after that instance is covered by the traversal, and read the property value.
     */
    @Test
    @Timeout(value = 40, unit = TimeUnit.SECONDS)
    fun `reassignment can depend on a matched instance property`() {
        declareHouseAndCounter()
        g.addV("Room").property(graphKey("Room", "value"), 10).next()

        val matchStatement = TypedMatchStatement(
            pattern = TypedPattern(
                elements = listOf(
                    TypedPatternObjectInstanceElement(
                        objectInstance = TypedPatternObjectInstance(
                            modifier = null, name = "r", className = "Room", properties = emptyList()
                        )
                    ),
                    TypedPatternVariableReassignmentElement(
                        reassignment = TypedPatternVariableReassignment(
                            name = "counter",
                            value = TypedBinaryExpression(
                                evalType = 3, operator = "+",
                                left = counterRef(),
                                right = TypedMemberAccessExpression(
                                    evalType = 3,
                                    expression = TypedIdentifierExpression(evalType = 6, name = "r", scope = 1),
                                    member = "value",
                                    isNullChaining = false
                                )
                            )
                        )
                    )
                )
            )
        )

        val result = engine.executeStatement(matchStatement, context)
        assertIs<TransformationExecutionResult.Success>(result)

        val binding = context.variableScope.getVariable("counter")
        assertIs<VariableBinding.ValueBinding>(binding)
        assertEquals(10L, (binding.value as Number).toLong(), "counter should be 0 + room.value")
    }

    /**
     * When the surrounding pattern fails to match, the reassignment must not take effect: the
     * variable's declaring-scope binding is restored to its original value (not left dangling
     * as an internal label binding).
     */
    @Test
    @Timeout(value = 40, unit = TimeUnit.SECONDS)
    fun `no match leaves the reassigned variable unchanged`() {
        declareHouseAndCounter() // counter = 0; no Room vertices exist

        val matchStatement = TypedMatchStatement(
            pattern = TypedPattern(
                elements = listOf(
                    // There is no Room in the graph, so this pattern cannot match.
                    TypedPatternObjectInstanceElement(
                        objectInstance = TypedPatternObjectInstance(
                            modifier = null, name = "ghost", className = "Room", properties = emptyList()
                        )
                    ),
                    TypedPatternVariableReassignmentElement(
                        reassignment = TypedPatternVariableReassignment(
                            name = "counter",
                            value = TypedBinaryExpression(
                                evalType = 3, operator = "+",
                                left = counterRef(),
                                right = TypedIntLiteralExpression(evalType = 3, value = "1")
                            )
                        )
                    )
                )
            )
        )

        val result = engine.executeStatement(matchStatement, context)
        assertIs<TransformationExecutionResult.Failure>(result)

        val binding = context.variableScope.getVariable("counter")
        assertIs<VariableBinding.ValueBinding>(binding)
        assertEquals(0L, (binding.value as Number).toLong(), "counter must remain 0 after a failed match")
    }
}
