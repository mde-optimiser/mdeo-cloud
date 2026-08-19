package com.mdeo.modeltransformation.runtime.match

import com.mdeo.expression.ast.expressions.TypedBinaryExpression
import com.mdeo.expression.ast.expressions.TypedExpression
import com.mdeo.expression.ast.expressions.TypedIdentifierExpression
import com.mdeo.expression.ast.expressions.TypedIntLiteralExpression
import com.mdeo.expression.ast.expressions.TypedMemberAccessExpression
import com.mdeo.expression.ast.expressions.TypedStringLiteralExpression
import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.expression.ast.types.ReturnType
import com.mdeo.expression.ast.types.VoidType
import com.mdeo.metamodel.Metamodel
import com.mdeo.metamodel.data.AssociationData
import com.mdeo.metamodel.data.AssociationEndData
import com.mdeo.metamodel.data.ClassData
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.metamodel.data.MultiplicityData
import com.mdeo.metamodel.data.PropertyData
import com.mdeo.modeltransformation.ast.EdgeLabelUtils
import com.mdeo.modeltransformation.ast.TypedAst
import com.mdeo.modeltransformation.ast.patterns.TypedPattern
import com.mdeo.modeltransformation.ast.patterns.TypedPatternElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternPropertyAssignment
import com.mdeo.modeltransformation.ast.patterns.TypedPatternVariable
import com.mdeo.modeltransformation.ast.patterns.TypedPatternVariableElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternWhereClauseElement
import com.mdeo.modeltransformation.ast.patterns.TypedWhereClause
import com.mdeo.modeltransformation.compiler.ExpressionCompilerRegistry
import com.mdeo.modeltransformation.graph.tinker.TinkerModelGraph
import com.mdeo.modeltransformation.runtime.StatementExecutorRegistry
import com.mdeo.modeltransformation.runtime.TransformationEngine
import com.mdeo.modeltransformation.runtime.TransformationExecutionContext
import org.apache.tinkerpop.gremlin.structure.Vertex
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for `where` clauses declared inside a `forbid` / `require` block.
 *
 * A clause inside a block belongs to the condition, not to the match: a `forbid` block only
 * rejects a match when its graph is found *and* its clauses hold. That is what makes the
 * clause useful — it compares the condition's own nodes with each other and with the nodes
 * and variables of the match, which a property constraint on a single node cannot express.
 *
 * ## Test metamodel
 *
 * One class `Node` with a string property `value`, an int property `size`, and a
 * self-referential `to/from` association.
 */
class ConditionWhereClauseTest {

    companion object {
        private const val METAMODEL_PATH = "/test/node"
        private val CLASS_PACKAGE = "class$METAMODEL_PATH"

        const val STRING_IDX = 1
        const val INT_IDX = 2
        const val BOOL_IDX = 3
        const val NODE_IDX = 4

        /** Scope level of the enclosing match. */
        private const val MATCH_SCOPE = 1

        /** Scope level of an application condition block, one level inside the match's. */
        private const val BLOCK_SCOPE = MATCH_SCOPE + 1
    }

    private val types: List<ReturnType> = listOf(
        VoidType(),
        ClassTypeRef(`package` = "builtin", type = "string", isNullable = false),
        ClassTypeRef(`package` = "builtin", type = "int", isNullable = false),
        ClassTypeRef(`package` = "builtin", type = "boolean", isNullable = false),
        ClassTypeRef(`package` = CLASS_PACKAGE, type = "Node", isNullable = false)
    )

    private val metamodelData = MetamodelData(
        path = METAMODEL_PATH,
        classes = listOf(
            ClassData(
                name = "Node",
                isAbstract = false,
                properties = listOf(
                    PropertyData(name = "value", primitiveType = "string", multiplicity = MultiplicityData.single()),
                    PropertyData(name = "size", primitiveType = "int", multiplicity = MultiplicityData.single())
                )
            )
        ),
        associations = listOf(
            AssociationData(
                source = AssociationEndData(className = "Node", name = "to", multiplicity = MultiplicityData(0, -1)),
                operator = "<-->",
                target = AssociationEndData(className = "Node", name = "from", multiplicity = MultiplicityData(0, -1))
            )
        )
    )

    private val metamodel = Metamodel.compile(metamodelData)

    private fun propKey(propName: String): String =
        "prop_${metamodel.metadata.classes["Node"]!!.propertyFields[propName]!!.fieldIndex}"

    private val toFromEdge = EdgeLabelUtils.computeEdgeLabel("to", "from")

    private lateinit var graph: TinkerGraph
    private lateinit var engine: TransformationEngine
    private lateinit var context: TransformationExecutionContext

    @BeforeEach
    fun setUp() {
        graph = TinkerGraph.open()
        context = TransformationExecutionContext.empty()
        engine = TransformationEngine(
            modelGraph = TinkerModelGraph.wrap(graph, metamodel),
            ast = TypedAst(types = types, metamodelPath = METAMODEL_PATH, statements = emptyList()),
            expressionCompilerRegistry = ExpressionCompilerRegistry.createDefaultRegistry(),
            statementExecutorRegistry = StatementExecutorRegistry.createDefaultRegistry()
        )
    }

    @AfterEach
    fun tearDown() {
        graph.close()
    }

    // ── Expression helpers ────────────────────────────────────────────────────

    private fun nodeValue(name: String) = memberAccess(name, "value", STRING_IDX, MATCH_SCOPE)

    private fun nodeSize(name: String) = memberAccess(name, "size", INT_IDX, MATCH_SCOPE)

    /**
     * Reads a property of a node declared by a `forbid` / `require` block.
     *
     * A block is a scope of its own, so its names are recorded one level deeper than those
     * of the match — which is what tells the engine to look them up in the scope the block's
     * sub-traversal binds them in.
     */
    private fun blockValue(name: String) = memberAccess(name, "value", STRING_IDX, BLOCK_SCOPE)

    private fun blockSize(name: String) = memberAccess(name, "size", INT_IDX, BLOCK_SCOPE)

    private fun memberAccess(name: String, member: String, evalType: Int, scope: Int) =
        TypedMemberAccessExpression(
            evalType = evalType,
            expression = TypedIdentifierExpression(evalType = NODE_IDX, name = name, scope = scope),
            member = member,
            isNullChaining = false
        )

    private fun variable(name: String) =
        TypedIdentifierExpression(evalType = INT_IDX, name = name, scope = MATCH_SCOPE)

    private fun str(value: String) = TypedStringLiteralExpression(evalType = STRING_IDX, value = value)

    private fun int(value: Int) = TypedIntLiteralExpression(evalType = INT_IDX, value = value.toString())

    private fun binary(operator: String, left: TypedExpression, right: TypedExpression) =
        TypedBinaryExpression(evalType = BOOL_IDX, operator = operator, left = left, right = right)

    private fun property(name: String, operator: String, value: TypedExpression) =
        TypedPatternPropertyAssignment(propertyName = name, operator = operator, value = value)

    private fun where(expression: TypedExpression) =
        TypedPatternWhereClauseElement(whereClause = TypedWhereClause(expression = expression))

    private fun varElement(name: String, value: TypedExpression) =
        TypedPatternVariableElement(variable = TypedPatternVariable(name = name, value = value))

    // ── Model helpers ─────────────────────────────────────────────────────────

    private fun addNode(value: String, size: Int = 0): Vertex {
        val vertex = graph.addVertex("Node")
        vertex.property(propKey("value"), value)
        vertex.property(propKey("size"), size)
        return vertex
    }

    private fun link(from: Vertex, to: Vertex) = from.addEdge(toFromEdge, to)

    private fun matchAll(vararg elements: TypedPatternElement) =
        MatchExecutor().executeMatchAll(TypedPattern(elements = elements.toList()), context, engine)

    private fun matchCount(vararg elements: TypedPatternElement): Int = matchAll(*elements).size

    private fun matchedValues(vararg elements: TypedPatternElement): List<String> =
        matchAll(*elements).map { result ->
            val vertexId = result.instanceMappings["a"]!!.rawId
            graph.vertices(vertexId).next().value<String>(propKey("value"))
        }.sorted()

    // =========================================================================
    // 1. A clause reading the condition's own nodes
    // =========================================================================

    @Nested
    inner class ConditionLocalNodes {

        /**
         * The condition only rejects a match when the forbidden neighbour also satisfies the
         * clause: `a` keeps its match when its neighbour is small.
         */
        @Test
        fun `clause on a node of the block narrows what the block forbids`() {
            val withBigNeighbour = addNode("big-owner")
            link(withBigNeighbour, addNode("neighbour", size = 100))
            val withSmallNeighbour = addNode("small-owner")
            link(withSmallNeighbour, addNode("neighbour", size = 1))

            val values = matchedValues(
                conditionNode("a", "Node"),
                forbidBlock(
                    conditionNode("b", "Node"),
                    conditionLink("a", "to", "b", "from"),
                    where(binary(">", blockSize("b"), int(50))),
                    name = "bigNeighbour"
                )
            )

            assertEquals(
                listOf("neighbour", "neighbour", "small-owner"), values,
                "Only the node with a large neighbour is rejected; the neighbours themselves have none"
            )
        }

        /**
         * A clause may compare two nodes of the block with each other — the case a property
         * constraint on a single node cannot express.
         */
        @Test
        fun `clause compares two nodes of the same block`() {
            // a -> b -> c with equal values: the block holds and rejects a
            val equalStart = addNode("start")
            val equalMiddle = addNode("same")
            val equalEnd = addNode("same")
            link(equalStart, equalMiddle)
            link(equalMiddle, equalEnd)

            // d -> e -> f with differing values: the clause fails, so d survives
            val otherStart = addNode("other-start")
            val otherMiddle = addNode("x")
            val otherEnd = addNode("y")
            link(otherStart, otherMiddle)
            link(otherMiddle, otherEnd)

            val values = matchedValues(
                conditionNode("a", "Node"),
                forbidBlock(
                    conditionNode("b", "Node"),
                    conditionNode("c", "Node"),
                    conditionLink("a", "to", "b", "from"),
                    conditionLink("b", "to", "c", "from"),
                    where(binary("==", blockValue("b"), blockValue("c"))),
                    name = "twoEqualSuccessors"
                )
            )

            assertEquals(
                listOf("other-start", "same", "same", "x", "y"), values,
                "Only the node whose two successors carry the same value is rejected"
            )
        }

        /**
         * Two clauses inside one block are a conjunction: the block only holds when both of
         * them are satisfied.
         */
        @Test
        fun `two clauses in one block constrain the same graph together`() {
            val bothHold = addNode("both")
            link(bothHold, addNode("target", size = 100))
            val onlyOneHolds = addNode("one")
            link(onlyOneHolds, addNode("other", size = 100))

            val values = matchedValues(
                conditionNode("a", "Node"),
                forbidBlock(
                    conditionNode("b", "Node"),
                    conditionLink("a", "to", "b", "from"),
                    where(binary(">", blockSize("b"), int(50))),
                    where(binary("==", blockValue("b"), str("target"))),
                    name = "bigTarget"
                )
            )

            assertEquals(
                listOf("one", "other", "target"), values,
                "Only the node whose neighbour satisfies both clauses is rejected"
            )
        }
    }

    // =========================================================================
    // 2. A clause spanning the block and the match
    // =========================================================================

    @Nested
    inner class ClausesAcrossTheMatch {

        @Test
        fun `clause compares a block node against the matched node`() {
            val small = addNode("small", size = 10)
            val big = addNode("big", size = 90)
            // an unanchored block: "some node is larger than the matched one"

            val values = matchedValues(
                conditionNode("a", "Node"),
                forbidBlock(
                    conditionNode("other", "Node"),
                    where(binary(">", blockSize("other"), nodeSize("a"))),
                    name = "somethingLarger"
                )
            )

            assertEquals(listOf("big"), values, "Only the largest node has nothing above it")
            assertEquals(10, small.value<Int>(propKey("size")))
            assertEquals(90, big.value<Int>(propKey("size")))
        }

        @Test
        fun `clause reads a pattern variable bound by the match`() {
            addNode("small", size = 10)
            addNode("big", size = 90)

            // var limit = a.size ; forbid { other: Node where other.size > limit }
            val values = matchedValues(
                conditionNode("a", "Node"),
                varElement("limit", nodeSize("a")),
                forbidBlock(
                    conditionNode("other", "Node"),
                    where(binary(">", blockSize("other"), variable("limit"))),
                    name = "largerThanLimit"
                )
            )

            assertEquals(
                listOf("big"), values,
                "The variable is bound before the condition is evaluated, so the block reads it"
            )
        }

        /**
         * A block that holds nothing but a clause is a plain guard on the match: it rejects
         * every match for which the clause is true.
         */
        @Test
        fun `block consisting only of a clause guards the match`() {
            addNode("small", size = 10)
            addNode("big", size = 90)

            val values = matchedValues(
                conditionNode("a", "Node"),
                forbidBlock(
                    where(binary(">", nodeSize("a"), int(50))),
                    name = "tooBig"
                )
            )

            assertEquals(listOf("small"), values, "The clause alone rejects every large node")
        }
    }

    // =========================================================================
    // 3. Grouping: clauses stay with their block
    // =========================================================================

    @Nested
    inner class ClauseGrouping {

        /**
         * The clause constrains its own block and nothing else: the same two elements split
         * across two blocks reject strictly more matches than one block holding both.
         */
        @Test
        fun `separate blocks with clauses reject independently`() {
            addNode("small", size = 10)
            addNode("big", size = 90)

            val oneBlock = matchedValues(
                conditionNode("a", "Node"),
                forbidBlock(
                    conditionNode("x", "Node"),
                    where(binary(">", blockSize("x"), int(50))),
                    where(binary("==", blockValue("x"), str("nothing"))),
                    name = "bigAndNamedNothing"
                )
            )
            assertEquals(
                listOf("big", "small"), oneBlock,
                "No node satisfies both clauses, so the single block never holds"
            )

            val twoBlocks = matchedValues(
                conditionNode("a", "Node"),
                forbidBlock(
                    conditionNode("x", "Node"),
                    where(binary(">", blockSize("x"), int(50))),
                    name = "big"
                ),
                forbidBlock(
                    conditionNode("y", "Node"),
                    where(binary("==", blockValue("y"), str("nothing"))),
                    name = "namedNothing"
                )
            )
            assertEquals(
                listOf("big"), twoBlocks,
                "The first block holds on its own for the small node: a larger node exists"
            )
        }

        /**
         * Injectivity keeps holding when a clause is present: the block's node is never the
         * matched node itself.
         */
        @Test
        fun `condition node stays distinct from the matched node`() {
            addNode("only", size = 90)

            val values = matchedValues(
                conditionNode("a", "Node"),
                forbidBlock(
                    conditionNode("other", "Node"),
                    where(binary(">", blockSize("other"), int(50))),
                    name = "anotherBigNode"
                )
            )

            assertEquals(
                listOf("only"), values,
                "The only large node is the matched one, and the block cannot bind it again"
            )
        }
    }

    // =========================================================================
    // 4. Positive conditions
    // =========================================================================

    @Nested
    inner class RequireBlocks {

        @Test
        fun `require block holds only when its clause is satisfied`() {
            val withBig = addNode("with-big")
            link(withBig, addNode("neighbour", size = 100))
            val withSmall = addNode("with-small")
            link(withSmall, addNode("neighbour", size = 1))

            val values = matchedValues(
                conditionNode("a", "Node"),
                requireBlock(
                    conditionNode("b", "Node"),
                    conditionLink("a", "to", "b", "from"),
                    where(binary(">", blockSize("b"), int(50))),
                    name = "hasBigNeighbour"
                )
            )

            assertEquals(listOf("with-big"), values, "Only the node with a large neighbour qualifies")
        }

        @Test
        fun `require block with a clause across two components`() {
            val anchor = addNode("anchor", size = 5)
            link(anchor, addNode("neighbour", size = 5))
            addNode("detached", size = 5)

            val values = matchedValues(
                conditionNode("a", "Node"),
                requireBlock(
                    conditionNode("b", "Node"),
                    conditionLink("a", "to", "b", "from"),
                    conditionNode("c", "Node"),
                    where(binary("==", blockSize("b"), blockSize("c"))),
                    name = "neighbourMatchesSomeNode"
                )
            )

            assertEquals(
                listOf("anchor"), values,
                "Only the anchor has a neighbour, and a third node of equal size exists"
            )
        }
    }

    // =========================================================================
    // 5. Property constraints of a block read the same names a clause can
    // =========================================================================

    /**
     * A property constraint on a node of a block is compiled inside the condition, exactly
     * like a clause of the block, and reads the same names: the variables and nodes of the
     * match, and the block's own nodes.
     */
    @Nested
    inner class PropertyConstraintsInBlocks {

        @Test
        fun `property constraint of a block reads a pattern variable`() {
            addNode("small", size = 10)
            addNode("big", size = 90)

            // var limit = a.size ; forbid { other: Node { size > limit } }
            val values = matchedValues(
                conditionNode("a", "Node"),
                varElement("limit", nodeSize("a")),
                forbidBlock(
                    conditionNode("other", "Node", listOf(property("size", ">", variable("limit")))),
                    name = "largerThanLimit"
                )
            )

            assertEquals(
                listOf("big"), values,
                "The variable is bound before the condition is evaluated, so the block reads it"
            )
        }

        @Test
        fun `property constraint of a block reads another node of the same block`() {
            // a -> b -> c with equal sizes: the block holds and rejects a
            val equalStart = addNode("start")
            val equalMiddle = addNode("same", size = 7)
            val equalEnd = addNode("same", size = 7)
            link(equalStart, equalMiddle)
            link(equalMiddle, equalEnd)

            // d -> e -> f with differing sizes: the constraint fails, so d survives
            val otherStart = addNode("other-start")
            val otherMiddle = addNode("x", size = 1)
            val otherEnd = addNode("y", size = 2)
            link(otherStart, otherMiddle)
            link(otherMiddle, otherEnd)

            val values = matchedValues(
                conditionNode("a", "Node"),
                forbidBlock(
                    conditionNode("b", "Node"),
                    conditionNode("c", "Node", listOf(property("size", "==", blockSize("b")))),
                    conditionLink("a", "to", "b", "from"),
                    conditionLink("b", "to", "c", "from"),
                    name = "twoSuccessorsOfEqualSize"
                )
            )

            assertEquals(
                listOf("other-start", "same", "same", "x", "y"), values,
                "Only the node whose two successors have the same size is rejected"
            )
        }
    }

    // =========================================================================
    // 6. The clause does not leak out of the block
    // =========================================================================

    @Test
    fun `nodes of a block are not bound by the match`() {
        addNode("a", size = 1)
        addNode("b", size = 100)

        val results = matchAll(
            conditionNode("a", "Node"),
            forbidBlock(
                conditionNode("hidden", "Node"),
                where(binary(">", blockSize("hidden"), int(1000))),
                name = "veryLarge"
            )
        )

        assertEquals(2, results.size, "No node is that large, so nothing is rejected")
        assertNull(results.first().instanceMappings["hidden"], "A condition node is never part of the result")
        assertNull(context.variableScope.getVariable("hidden"), "…nor of the variable scope afterwards")
    }
}
