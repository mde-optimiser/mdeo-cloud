package com.mdeo.modeltransformation.runtime.match

import com.mdeo.expression.ast.expressions.*
import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.expression.ast.types.ReturnType
import com.mdeo.expression.ast.types.VoidType
import com.mdeo.metamodel.Metamodel
import com.mdeo.metamodel.data.*
import com.mdeo.modeltransformation.ast.EdgeLabelUtils
import com.mdeo.modeltransformation.ast.TypedAst
import com.mdeo.modeltransformation.ast.patterns.*
import com.mdeo.modeltransformation.compiler.ExpressionCompilerRegistry
import com.mdeo.modeltransformation.graph.tinker.TinkerModelGraph
import com.mdeo.modeltransformation.runtime.*
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Tests for constraint scenarios that cross-reference matched nodes:
 *
 * 1. **Forbid islands** whose property conditions reference a matched node's field.
 * 2. **Require islands** whose property conditions reference a matched node's field.
 * 3. **Main-match property inlining** – constant properties inlined as `.has()` and
 *    cross-node expressions inlined when the referenced node is already bound.
 * 4. **Where-clause cross-node comparisons** – `where a.value == b.value`.
 * 5. **Combined** – constant main-match filter together with a cross-node forbid island.
 *
 * ## Test metamodel
 *
 * One class `Node` with two string properties (`value`, `tag`) and a self-referential
 * `to/from` association (Node.to → Node.from).  All graphs use plain TinkerGraph
 * (no index), and property keys use the metamodel-derived `prop_N` notation so that
 * the tests are consistent with the production code path.
 */
class CrossNodeConstraintTest {

    companion object {
        private const val METAMODEL_PATH = "/test/node"
        private val CLASS_PACKAGE = "class$METAMODEL_PATH"

        // Type-array indices
        const val VOID_IDX   = 0
        const val STRING_IDX = 1
        const val INT_IDX    = 2
        const val BOOL_IDX   = 3
        const val NODE_IDX   = 4  // ClassTypeRef for "Node"
    }

    // ── Metamodel ─────────────────────────────────────────────────────────────

    private val types: List<ReturnType> = listOf(
        VoidType(),
        ClassTypeRef(`package` = "builtin", type = "string",  isNullable = false),  // 1
        ClassTypeRef(`package` = "builtin", type = "int",     isNullable = false),  // 2
        ClassTypeRef(`package` = "builtin", type = "boolean", isNullable = false),  // 3
        ClassTypeRef(`package` = CLASS_PACKAGE, type = "Node", isNullable = false)  // 4
    )

    private val metamodelData = MetamodelData(
        path = METAMODEL_PATH,
        classes = listOf(
            ClassData(
                name = "Node",
                isAbstract = false,
                properties = listOf(
                    PropertyData(name = "value", primitiveType = "string",
                        multiplicity = MultiplicityData.single()),
                    PropertyData(name = "tag",   primitiveType = "string",
                        multiplicity = MultiplicityData.single())
                )
            )
        ),
        associations = listOf(
            AssociationData(
                source = AssociationEndData(
                    className = "Node", name = "to",
                    multiplicity = MultiplicityData(0, -1)
                ),
                operator = "<-->",
                target = AssociationEndData(
                    className = "Node", name = "from",
                    multiplicity = MultiplicityData(0, -1)
                )
            )
        )
    )

    private val metamodel = Metamodel.compile(metamodelData)

    /** Returns the Gremlin graph key (`prop_N`) for a Node property. */
    private fun propKey(propName: String): String =
        "prop_${metamodel.metadata.classes["Node"]!!.propertyFields[propName]!!.fieldIndex}"

    /** Computes the edge label for the self-referential `to/from` association. */
    private val toFromEdge = EdgeLabelUtils.computeEdgeLabel("to", "from")

    // ── Test infrastructure ───────────────────────────────────────────────────

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

    /** Builds `nodeName.value` as a `TypedMemberAccessExpression`. */
    private fun nodeValueExpr(nodeName: String) = TypedMemberAccessExpression(
        evalType = STRING_IDX,
        expression = TypedIdentifierExpression(evalType = NODE_IDX, name = nodeName, scope = 1),
        member = "value",
        isNullChaining = false
    )

    /** Builds `nodeName.tag` as a `TypedMemberAccessExpression`. */
    private fun nodeTagExpr(nodeName: String) = TypedMemberAccessExpression(
        evalType = STRING_IDX,
        expression = TypedIdentifierExpression(evalType = NODE_IDX, name = nodeName, scope = 1),
        member = "tag",
        isNullChaining = false
    )

    /** Builds a string literal expression. */
    private fun str(v: String) = TypedStringLiteralExpression(evalType = STRING_IDX, value = v)

    /** Builds `left == right` (boolean binary expression). */
    private fun eq(left: TypedExpression, right: TypedExpression) =
        TypedBinaryExpression(evalType = BOOL_IDX, operator = "==", left = left, right = right)

    /** Builds `left != right` (boolean binary expression). */
    private fun neq(left: TypedExpression, right: TypedExpression) =
        TypedBinaryExpression(evalType = BOOL_IDX, operator = "!=", left = left, right = right)

    // ── Pattern element helpers ───────────────────────────────────────────────

    /** Match instance – no properties. */
    private fun matchNode(name: String) = TypedPatternObjectInstanceElement(
        objectInstance = TypedPatternObjectInstance(
            modifier = null, name = name, className = "Node", properties = emptyList()
        )
    )

    /** Match instance with a single `==` property constraint. */
    private fun matchNodeWithProp(name: String, propName: String, value: TypedExpression) =
        TypedPatternObjectInstanceElement(
            objectInstance = TypedPatternObjectInstance(
                modifier = null, name = name, className = "Node",
                properties = listOf(TypedPatternPropertyAssignment(propName, "==", value))
            )
        )

    /** Forbid instance – no properties. */
    private fun forbidNode(name: String) = TypedPatternObjectInstanceElement(
        objectInstance = TypedPatternObjectInstance(
            modifier = "forbid", name = name, className = "Node", properties = emptyList()
        )
    )

    /** Forbid instance with a single `==` property constraint. */
    private fun forbidNodeWithProp(name: String, propName: String, value: TypedExpression) =
        TypedPatternObjectInstanceElement(
            objectInstance = TypedPatternObjectInstance(
                modifier = "forbid", name = name, className = "Node",
                properties = listOf(TypedPatternPropertyAssignment(propName, "==", value))
            )
        )

    /** Require instance – no properties. */
    private fun requireNode(name: String) = TypedPatternObjectInstanceElement(
        objectInstance = TypedPatternObjectInstance(
            modifier = "require", name = name, className = "Node", properties = emptyList()
        )
    )

    /** Require instance with a single `==` property constraint. */
    private fun requireNodeWithProp(name: String, propName: String, value: TypedExpression) =
        TypedPatternObjectInstanceElement(
            objectInstance = TypedPatternObjectInstance(
                modifier = "require", name = name, className = "Node",
                properties = listOf(TypedPatternPropertyAssignment(propName, "==", value))
            )
        )

    /** Match link element (to/from association). */
    private fun matchLink(src: String, tgt: String) = TypedPatternLinkElement(
        link = TypedPatternLink(
            modifier = null,
            source = TypedPatternLinkEnd(objectName = src, propertyName = "to"),
            target = TypedPatternLinkEnd(objectName = tgt, propertyName = "from")
        )
    )

    /** Forbid link element (to/from association). */
    private fun forbidLink(src: String, tgt: String) = TypedPatternLinkElement(
        link = TypedPatternLink(
            modifier = "forbid",
            source = TypedPatternLinkEnd(objectName = src, propertyName = "to"),
            target = TypedPatternLinkEnd(objectName = tgt, propertyName = "from")
        )
    )

    /** Require link element (to/from association). */
    private fun requireLink(src: String, tgt: String) = TypedPatternLinkElement(
        link = TypedPatternLink(
            modifier = "require",
            source = TypedPatternLinkEnd(objectName = src, propertyName = "to"),
            target = TypedPatternLinkEnd(objectName = tgt, propertyName = "from")
        )
    )

    /** Where clause element. */
    private fun whereClause(expr: TypedExpression) = TypedPatternWhereClauseElement(
        whereClause = TypedWhereClause(expression = expr)
    )

    /** Adds a Node vertex with a value property. */
    private fun addNode(value: String, tag: String = ""): org.apache.tinkerpop.gremlin.structure.Vertex {
        val v = graph.addVertex("Node")
        v.property(propKey("value"), value)
        if (tag.isNotEmpty()) v.property(propKey("tag"), tag)
        return v
    }

    /** Adds a `to/from` edge between two vertices. */
    private fun addEdge(from: org.apache.tinkerpop.gremlin.structure.Vertex,
                        to:   org.apache.tinkerpop.gremlin.structure.Vertex) =
        from.addEdge(toFromEdge, to)

    /** Runs executeMatchAll and returns the number of results. */
    private fun matchCount(vararg elements: TypedPatternElement): Int =
        MatchExecutor().executeMatchAll(TypedPattern(elements = elements.toList()), context, engine).size

    // =========================================================================
    // 1. Forbid island with property referencing a matched node's field
    // =========================================================================

    @Nested
    inner class ForbidIslandCrossNodePropertyTests {

        @Test
        fun `1a - forbid triggers when island node value equals matched node value`() {
            // nodeA →(to/from)→ nodeB, both value="same"
            val nodeA = addNode("same"); val nodeB = addNode("same"); addEdge(nodeA, nodeB)

            // match a:Node, forbid b:Node { value == a.value }, forbidLink a.to -> b.from
            // nodeA: edge to nodeB whose value equals nodeA.value  → FORBID TRIGGERS → excluded
            // nodeB: no outgoing edge                               → forbid never matches → included
            val count = matchCount(
                matchNode("a"),
                forbidNodeWithProp("b", "value", nodeValueExpr("a")),
                forbidLink("a", "b")
            )
            assertEquals(1, count, "Only nodeB should match; nodeA is excluded by forbid")
        }

        @Test
        fun `1b - forbid does not trigger when island node value differs from matched node`() {
            // nodeA.value="x", nodeB.value="y", nodeA→nodeB
            val nodeA = addNode("x"); val nodeB = addNode("y"); addEdge(nodeA, nodeB)

            // nodeA: edge to nodeB but nodeB.value != nodeA.value → forbid doesn't trigger → included
            // nodeB: no outgoing edge → included
            val count = matchCount(
                matchNode("a"),
                forbidNodeWithProp("b", "value", nodeValueExpr("a")),
                forbidLink("a", "b")
            )
            assertEquals(2, count, "Both nodes match; values differ so forbid never triggers")
        }

        @Test
        fun `1c - selective exclusion across three nodes`() {
            // nodeA(value="x"), nodeB(value="x"), nodeC(value="y")
            // nodeA→nodeB (same value), nodeC→nodeA (different value)
            val nodeA = addNode("x"); val nodeB = addNode("x"); val nodeC = addNode("y")
            addEdge(nodeA, nodeB)  // A→B: same value  → nodeA excluded
            addEdge(nodeC, nodeA)  // C→A: different value → nodeC included

            val count = matchCount(
                matchNode("a"),
                forbidNodeWithProp("b", "value", nodeValueExpr("a")),
                forbidLink("a", "b")
            )
            // nodeA: edge to nodeB, nodeB.value=="x"==nodeA.value → EXCLUDED
            // nodeB: no outgoing edge → included
            // nodeC: edge to nodeA, nodeA.value="x"!=nodeC.value="y" → included
            assertEquals(2, count, "nodeB and nodeC should match; nodeA excluded because outgoing node has same value")
        }

        @Test
        fun `1d - forbid on tag property instead of value`() {
            // nodeA(tag="bad"), nodeB(tag="bad"), nodeA→nodeB
            val nodeA = addNode("irrelevant", "bad"); val nodeB = addNode("irrelevant", "bad")
            addEdge(nodeA, nodeB)

            val count = matchCount(
                matchNode("a"),
                forbidNodeWithProp("b", "tag", nodeTagExpr("a")),
                forbidLink("a", "b")
            )
            // nodeA: edge to nodeB whose tag == nodeA.tag → EXCLUDED
            // nodeB: no outgoing edge → included
            assertEquals(1, count, "nodeA excluded because nodeB.tag == nodeA.tag")
        }

        @Test
        fun `1e - forbid island with two hops, property check on final island node`() {
            // Chain: nodeA → nodeB → nodeC (three hops: a=match, b=forbid, c=forbid)
            // nodeA.value="x", nodeB has no property constraint, nodeC.value="x" (same as A)
            val nodeA = addNode("x"); val nodeB = addNode("y"); val nodeC = addNode("x")
            addEdge(nodeA, nodeB); addEdge(nodeB, nodeC)

            // match a:Node, forbid b:Node, forbid c:Node { value == a.value },
            // forbidLink a.to->b.from, b.to->c.from
            val count = matchCount(
                matchNode("a"),
                forbidNode("b"),
                forbidNodeWithProp("c", "value", nodeValueExpr("a")),
                forbidLink("a", "b"),
                forbidLink("b", "c")
            )
            // nodeA: chain A→B→C exists and C.value="x"==A.value="x" → FORBID CHAIN MATCHES → EXCLUDED
            // nodeB: chain B→C exists. b=nodeC. c? nodeC has no outgoing "to" edge → chain incomplete → included
            // nodeC: no outgoing edge → included
            assertEquals(2, count, "nodeA excluded by two-hop forbid chain; nodeB and nodeC included")
        }

        @Test
        fun `1f - forbid island where no outgoing edge exists is never triggered`() {
            // nodeA(value="x"), nodeB(value="x"), but NO edge between them
            addNode("x"); addNode("x")

            val count = matchCount(
                matchNode("a"),
                forbidNodeWithProp("b", "value", nodeValueExpr("a")),
                forbidLink("a", "b")
            )
            // No edges → forbid island never walks to any "b" → never triggers
            assertEquals(2, count, "No edges means forbid island never matches; both nodes included")
        }

        @Test
        fun `1g - forbid property constant does not trigger when no connected node with that value exists`() {
            // nodeA, nodeB(value="forbidden"), but nodeA→nodeB does NOT exist
            // and nodeC(value="forbidden") but nodeA→nodeC also doesn't exist
            val nodeA = addNode("ok")
            addNode("forbidden")  // nodeB, disconnected
            // nodeA: outgoing edge exists only if added, but we don't add any
            val count = matchCount(
                matchNode("a"),
                forbidNodeWithProp("b", "value", str("forbidden")),
                forbidLink("a", "b")
            )
            // nodeA has no "to" edges → forbid island finds no match → nodeA included
            // nodeB has no outgoing "to" edge → included
            assertEquals(2, count, "No edges from nodeA; forbid island never fires")
        }

        @Test
        fun `1h - forbid with constant property value correctly blocks matching node`() {
            // nodeA → nodeB(value="forbidden")
            val nodeA = addNode("other"); val nodeB = addNode("forbidden")
            addEdge(nodeA, nodeB)

            val count = matchCount(
                matchNode("a"),
                forbidNodeWithProp("b", "value", str("forbidden")),
                forbidLink("a", "b")
            )
            // nodeA: edge to nodeB which has value="forbidden" → EXCLUDED
            // nodeB: no outgoing edge → included
            assertEquals(1, count, "nodeA excluded because it links to a node with the forbidden value")
        }

        @Test
        fun `1i - multiple matched nodes each checked against separate cross-node forbids`() {
            // Graph: nodeA→nodeB, nodeA.value="same", nodeB.value="same"
            //        nodeA→nodeX, nodeX.value="same"  (extra unmatched node with same value)
            //        nodeC→nodeD, nodeC.value="x",    nodeD.value="y"
            //
            // With island injective semantics, the forbid island node c must be distinct
            // from ALL main-pattern nodes (a and b). nodeB is already matched as b, so it
            // cannot act as c. Only a truly fresh node (nodeX) can trigger the forbid.
            val nodeA = addNode("same"); val nodeB = addNode("same"); addEdge(nodeA, nodeB)
            val nodeX = addNode("same"); addEdge(nodeA, nodeX) // extra same-valued node for nodeA
            val nodeC = addNode("x");    val nodeD = addNode("y");    addEdge(nodeC, nodeD)

            // match a:Node, b:Node, link a.to→b.from,
            // forbid c:Node { value == a.value }, forbidLink a.to→c.from
            // c must be distinct from a and b (injective island semantics).
            val count = matchCount(
                matchNode("a"),
                matchNode("b"),
                matchLink("a", "b"),
                forbidNodeWithProp("c", "value", nodeValueExpr("a")),
                forbidLink("a", "c")
            )
            // (a=nodeA, b=nodeB): Forbid looks for c (!=nodeA,!=nodeB) linked from nodeA with value="same".
            //   nodeX.value="same" and nodeA→nodeX, nodeX!=nodeA and !=nodeB → forbid fires → EXCLUDED.
            // (a=nodeA, b=nodeX): Forbid looks for c (!=nodeA,!=nodeX) linked from nodeA with value="same".
            //   nodeB.value="same" and nodeA→nodeB, nodeB!=nodeA and !=nodeX → forbid fires → EXCLUDED.
            // (a=nodeC, b=nodeD): Forbid: c linked from nodeC with value="x". nodeD.value="y"!="x" → not triggered → INCLUDED.
            assertEquals(1, count, "Only (nodeC, nodeD) should match; (nodeA, nodeB) and (nodeA, nodeX) excluded by cross-node forbid")
        }
    }

    // =========================================================================
    // 2. Require island with property referencing a matched node's field
    // =========================================================================

    @Nested
    inner class RequireIslandCrossNodePropertyTests {

        @Test
        fun `2a - require island passes when connected node has same value as matched node`() {
            // nodeA.value="target", nodeB.value="target", nodeA→nodeB
            val nodeA = addNode("target"); val nodeB = addNode("target"); addEdge(nodeA, nodeB)

            // match a:Node, require b:Node { value == a.value }, requireLink a.to→b.from
            val count = matchCount(
                matchNode("a"),
                requireNodeWithProp("b", "value", nodeValueExpr("a")),
                requireLink("a", "b")
            )
            // nodeA: edge to nodeB, nodeB.value == nodeA.value → REQUIRE MET → included
            // nodeB: no outgoing edge → REQUIRE FAILS → excluded
            assertEquals(1, count, "Only nodeA satisfies the require (nodeB.value == nodeA.value)")
        }

        @Test
        fun `2b - require island fails when connected node has different value`() {
            // nodeA.value="x", nodeB.value="y", nodeA→nodeB
            val nodeA = addNode("x"); val nodeB = addNode("y"); addEdge(nodeA, nodeB)

            val count = matchCount(
                matchNode("a"),
                requireNodeWithProp("b", "value", nodeValueExpr("a")),
                requireLink("a", "b")
            )
            // nodeA: edge to nodeB but nodeB.value="y" != nodeA.value="x" → REQUIRE NOT MET → excluded
            // nodeB: no outgoing edge → REQUIRE FAILS → excluded
            assertEquals(0, count, "No node satisfies the require; connected values differ")
        }

        @Test
        fun `2c - require island - one of three candidates satisfies it`() {
            // nodeA.value="target", nodeB.value="target", nodeC.value="other"
            // nodeA→nodeB (same values), nodeC→nodeB (C.value != B.value)
            val nodeA = addNode("target"); val nodeB = addNode("target"); val nodeC = addNode("other")
            addEdge(nodeA, nodeB)   // A→B: same value → A satisfies require
            addEdge(nodeC, nodeB)   // C→B: C.value="other" != B.value="target" → C fails

            val count = matchCount(
                matchNode("a"),
                requireNodeWithProp("b", "value", nodeValueExpr("a")),
                requireLink("a", "b")
            )
            assertEquals(1, count, "Only nodeA satisfies the require (nodeB.value == nodeA.value)")
        }

        @Test
        fun `2d - require passes when connected node matches a constant value`() {
            // nodeA → nodeB(value="required"), nodeC → nodeD(value="other")
            val nodeA = addNode("anything"); val nodeB = addNode("required")
            val nodeC = addNode("anything"); val nodeD = addNode("other")
            addEdge(nodeA, nodeB); addEdge(nodeC, nodeD)

            val count = matchCount(
                matchNode("a"),
                requireNodeWithProp("b", "value", str("required")),
                requireLink("a", "b")
            )
            // nodeA: edge to nodeB(value="required") → REQUIRE MET → included
            // nodeB: no outgoing edge → excluded
            // nodeC: edge to nodeD(value="other"), not "required" → excluded
            // nodeD: no outgoing edge → excluded
            assertEquals(1, count, "Only nodeA links to a required-value node")
        }

        @Test
        fun `2e - require island with tag cross-reference`() {
            // nodeA.tag="good", nodeB.tag="good", nodeA→nodeB
            val nodeA = addNode("v", "good"); val nodeB = addNode("v", "good"); addEdge(nodeA, nodeB)
            addNode("v", "bad")  // nodeC isolated, different tag

            val count = matchCount(
                matchNode("a"),
                requireNodeWithProp("b", "tag", nodeTagExpr("a")),
                requireLink("a", "b")
            )
            // nodeA: edge to nodeB, nodeB.tag=="good"==nodeA.tag → included
            // nodeB: no outgoing edge → excluded
            // nodeC: no edge → excluded
            assertEquals(1, count, "Only nodeA passes the tag-based require")
        }

        @Test
        fun `2f - require does not multiply the result set`() {
            // nodeA.value="x", nodeB.value="x", nodeC.value="x"
            // nodeA→nodeB, nodeA→nodeC (two edges from nodeA to same-value nodes)
            val nodeA = addNode("x"); val nodeB = addNode("x"); val nodeC = addNode("x")
            addEdge(nodeA, nodeB); addEdge(nodeA, nodeC)

            val count = matchCount(
                matchNode("a"),
                requireNodeWithProp("b", "value", nodeValueExpr("a")),
                requireLink("a", "b")
            )
            // nodeA satisfies the require (both nodeB and nodeC qualify)
            // Require islands must NOT multiply matches – even though two nodes satisfy the require,
            // we should still get exactly one match for nodeA.
            // nodeB and nodeC have no outgoing edges → excluded.
            assertEquals(1, count, "Require island must not multiply result set when multiple nodes satisfy it")
        }
    }

    // =========================================================================
    // 3. Main-match property constraint inlining (constant and cross-node)
    // =========================================================================

    @Nested
    inner class PropertyConstraintInliningTests {

        @Test
        fun `3a - constant property inlined - match by string value`() {
            addNode("target"); addNode("other"); addNode("other")

            val count = matchCount(matchNodeWithProp("a", "value", str("target")))
            assertEquals(1, count, "Only the one node with value='target' should match")
        }

        @Test
        fun `3b - constant property inlined - multiple matching nodes`() {
            addNode("target"); addNode("target"); addNode("other")

            val count = matchCount(matchNodeWithProp("a", "value", str("target")))
            assertEquals(2, count, "Both nodes with value='target' should match")
        }

        @Test
        fun `3c - constant property on second node of a linked pair`() {
            // nodeA.value="A" → nodeB.value="TARGET"
            // nodeC.value="A" → nodeD.value="OTHER"
            val nodeA = addNode("A"); val nodeB = addNode("TARGET")
            val nodeC = addNode("A"); val nodeD = addNode("OTHER")
            addEdge(nodeA, nodeB); addEdge(nodeC, nodeD)

            val count = matchCount(
                matchNode("a"),
                matchNodeWithProp("b", "value", str("TARGET")),
                matchLink("a", "b")
            )
            assertEquals(1, count, "Only (nodeA, nodeB) match; nodeD.value != 'TARGET'")
        }

        @Test
        fun `3d - cross-node property constraint on second node - value equals first node`() {
            // nodeA.value="X" → nodeB.value="X"   matched (same)
            // nodeC.value="Y" → nodeD.value="Z"   not matched (different)
            val nodeA = addNode("X"); val nodeB = addNode("X")
            val nodeC = addNode("Y"); val nodeD = addNode("Z")
            addEdge(nodeA, nodeB); addEdge(nodeC, nodeD)

            // match a:Node, b:Node { value == a.value }, link a.to→b.from
            val count = matchCount(
                matchNode("a"),
                matchNodeWithProp("b", "value", nodeValueExpr("a")),
                matchLink("a", "b")
            )
            assertEquals(1, count, "Only pair (nodeA, nodeB) has b.value == a.value")
        }

        @Test
        fun `3e - cross-node property - multiple pairs with matching values`() {
            // nodeA.value="X"→nodeB.value="X", nodeC.value="Y"→nodeD.value="Y"
            val nodeA = addNode("X"); val nodeB = addNode("X")
            val nodeC = addNode("Y"); val nodeD = addNode("Y")
            addEdge(nodeA, nodeB); addEdge(nodeC, nodeD)

            val count = matchCount(
                matchNode("a"),
                matchNodeWithProp("b", "value", nodeValueExpr("a")),
                matchLink("a", "b")
            )
            assertEquals(2, count, "Both pairs have b.value == a.value")
        }

        @Test
        fun `3f - constant AND cross-node constraint on same instance`() {
            // nodeA(value="X")→nodeB(value="X"), nodeC(value="Y")→nodeD(value="X")
            val nodeA = addNode("X"); val nodeB = addNode("X")
            val nodeC = addNode("Y"); val nodeD = addNode("X")
            addEdge(nodeA, nodeB); addEdge(nodeC, nodeD)

            // match a:Node { value == "X" }, b:Node { value == a.value }, link a.to→b.from
            val count = matchCount(
                matchNodeWithProp("a", "value", str("X")),
                matchNodeWithProp("b", "value", nodeValueExpr("a")),
                matchLink("a", "b")
            )
            // nodeA satisfies { value == "X" }, nodeB.value == nodeA.value="X" → match
            // nodeC.value="Y" != "X" → excluded before checking b
            // nodeD.value="X" ✓ but no outgoing edge from nodeD → not a start candidate for a
            assertEquals(1, count, "Only (nodeA, nodeB) satisfies both constant and cross-node constraints")
        }

        @Test
        fun `3g - cross-node constraint for tag property`() {
            // nodeA.tag="T"→nodeB.tag="T", nodeC.tag="T"→nodeD.tag="Q"
            val nodeA = addNode("v", "T"); val nodeB = addNode("v", "T")
            val nodeC = addNode("v", "T"); val nodeD = addNode("v", "Q")
            addEdge(nodeA, nodeB); addEdge(nodeC, nodeD)

            val count = matchCount(
                matchNode("a"),
                matchNodeWithProp("b", "tag", nodeTagExpr("a")),
                matchLink("a", "b")
            )
            assertEquals(1, count, "Only (nodeA, nodeB) has b.tag == a.tag")
        }
    }

    // =========================================================================
    // 4. Where-clause cross-node comparisons
    // =========================================================================

    @Nested
    inner class WhereClauseCrossNodeTests {

        @Test
        fun `4a - where clause matches pairs with equal values`() {
            // nodeA.value="X"→nodeB.value="X", nodeA→nodeC.value="Y"
            val nodeA = addNode("X"); val nodeB = addNode("X"); val nodeC = addNode("Y")
            addEdge(nodeA, nodeB); addEdge(nodeA, nodeC)

            // match a:Node, b:Node, link a.to→b.from, where b.value == a.value
            val count = matchCount(
                matchNode("a"),
                matchNode("b"),
                matchLink("a", "b"),
                whereClause(eq(nodeValueExpr("b"), nodeValueExpr("a")))
            )
            // (a=nodeA, b=nodeB): nodeB.value=="X"==nodeA.value → match
            // (a=nodeA, b=nodeC): nodeC.value="Y"!=nodeA.value="X" → filtered
            // nodeB, nodeC have no outgoing edges
            assertEquals(1, count, "Only (nodeA, nodeB) satisfies where b.value == a.value")
        }

        @Test
        fun `4b - where clause filters to pairs with different values`() {
            // nodeA.value="X"→nodeB.value="X", nodeA→nodeC.value="Y"
            val nodeA = addNode("X"); val nodeB = addNode("X"); val nodeC = addNode("Y")
            addEdge(nodeA, nodeB); addEdge(nodeA, nodeC)

            val count = matchCount(
                matchNode("a"),
                matchNode("b"),
                matchLink("a", "b"),
                whereClause(neq(nodeValueExpr("b"), nodeValueExpr("a")))
            )
            // (a=nodeA, b=nodeC): nodeC.value="Y" != nodeA.value="X" → match
            // (a=nodeA, b=nodeB): same value → filtered
            assertEquals(1, count, "Only (nodeA, nodeC) satisfies where b.value != a.value")
        }

        @Test
        fun `4c - where clause on unlinked pair of matched nodes`() {
            // nodeA(value="same"), nodeB(value="same"), nodeC(value="other")
            addNode("same"); addNode("same"); addNode("other")

            // match a:Node, b:Node, where a.value == b.value (no link - cartesian product)
            // Injective constraint removes (a,a), (b,b), (c,c)
            val count = matchCount(
                matchNode("a"),
                matchNode("b"),
                whereClause(eq(nodeValueExpr("a"), nodeValueExpr("b")))
            )
            // Injective: a != b; value equal pairs: (A,B) and (B,A) → 2 matches
            assertEquals(2, count, "(A,B) and (B,A) have matching values; injective removes same-node pairs")
        }

        @Test
        fun `4d - where b_value == a_value combined with constant forbid island`() {
            // nodeA.value="X"→nodeB.value="X"→nodeC.value="X"
            val nodeA = addNode("X"); val nodeB = addNode("X"); val nodeC = addNode("X")
            addEdge(nodeA, nodeB); addEdge(nodeB, nodeC)

            // match a:Node, b:Node, link a.to→b.from,
            // where b.value == a.value,
            // forbid c:Node { value == "X" }, forbidLink b.to→c.from
            val count = matchCount(
                matchNode("a"),
                matchNode("b"),
                matchLink("a", "b"),
                whereClause(eq(nodeValueExpr("b"), nodeValueExpr("a"))),
                forbidNodeWithProp("c", "value", str("X")),
                forbidLink("b", "c")
            )
            // (a=nodeA, b=nodeB): where nodeB.value=="X"==nodeA.value ✓
            //   forbid: b=nodeB has outgoing edge to nodeC(value="X") → FORBID → excluded
            // (a=nodeB, b=nodeC): where nodeC.value=="X"==nodeB.value ✓
            //   forbid: b=nodeC has no outgoing "to" edge → forbid doesn't trigger → included
            assertEquals(1, count, "Where passes for both but forbid excludes (nodeA, nodeB)")
        }

        @Test
        fun `4e - where clause comparing both properties`() {
            // nodeA(value="V", tag="T")→nodeB(value="V", tag="T")
            // nodeA→nodeC(value="V", tag="Q")   (different tag)
            val nodeA = addNode("V", "T"); val nodeB = addNode("V", "T"); val nodeC = addNode("V", "Q")
            addEdge(nodeA, nodeB); addEdge(nodeA, nodeC)

            // match a:Node, b:Node, link a.to→b.from,
            // where a.value == b.value AND a.tag == b.tag
            val count = matchCount(
                matchNode("a"),
                matchNode("b"),
                matchLink("a", "b"),
                whereClause(eq(nodeValueExpr("a"), nodeValueExpr("b"))),
                whereClause(eq(nodeTagExpr("a"), nodeTagExpr("b")))
            )
            // (a=nodeA, b=nodeB): value match ✓, tag match ✓ → included
            // (a=nodeA, b=nodeC): value match ✓, tag "T" != "Q" → filtered
            assertEquals(1, count, "Only (nodeA, nodeB) satisfies both where clauses")
        }
    }

    // =========================================================================
    // 5. Combined scenarios: constant main-match + cross-node forbid/require
    // =========================================================================

    @Nested
    inner class CombinedConstraintTests {

        @Test
        fun `5a - constant main-match filter plus cross-node forbid island`() {
            // nodeA(value="X")→nodeB(value="X"), nodeC(value="X")→nodeD(value="Y"),
            // nodeE(value="Z")  (isolated, value != "X")
            val nodeA = addNode("X"); val nodeB = addNode("X")
            val nodeC = addNode("X"); val nodeD = addNode("Y")
            addNode("Z")
            addEdge(nodeA, nodeB); addEdge(nodeC, nodeD)

            // match a:Node { value == "X" }, forbid b:Node { value == a.value }, forbidLink a→b
            val count = matchCount(
                matchNodeWithProp("a", "value", str("X")),
                forbidNodeWithProp("b", "value", nodeValueExpr("a")),
                forbidLink("a", "b")
            )
            // nodeA: value="X" ✓, outgoing → nodeB(value="X"==nodeA.value) → FORBIDDEN → excluded
            // nodeB: value="X" ✓, no outgoing edge → included
            // nodeC: value="X" ✓, outgoing → nodeD(value="Y" != "X") → forbid doesn't trigger → included
            // nodeD: value="Y" ≠ "X" → excluded by main-match constant constraint
            // nodeE: value="Z" ≠ "X" → excluded
            assertEquals(2, count, "nodeB and nodeC match; nodeA excluded by forbid, nodeD/nodeE by constant")
        }

        @Test
        fun `5b - cross-node require must hold even when main-match is unsatisfied`() {
            // nodeA(value="X")→nodeB(value="X"), nodeC(value="Y")→nodeD(value="X")
            val nodeA = addNode("X"); val nodeB = addNode("X")
            val nodeC = addNode("Y"); val nodeD = addNode("X")
            addEdge(nodeA, nodeB); addEdge(nodeC, nodeD)

            // match a:Node { value == "X" }, require b:Node { value == a.value }, requireLink a→b
            // Main match: a must have value="X".  Require: a must link to a node with same value.
            val count = matchCount(
                matchNodeWithProp("a", "value", str("X")),
                requireNodeWithProp("b", "value", nodeValueExpr("a")),
                requireLink("a", "b")
            )
            // nodeA: value="X" ✓, edge to nodeB(value="X"==nodeA.value) → REQUIRE MET → included
            // nodeB: value="X" ✓, no outgoing edge → REQUIRE FAILS → excluded
            // nodeC: value="Y" ≠ "X" → excluded by main-match filter → require not checked
            // nodeD: value="X" ✓, no outgoing edge → REQUIRE FAILS → excluded
            assertEquals(1, count, "Only nodeA satisfies both constant constraint and cross-node require")
        }

        @Test
        fun `5c - forbid and require islands on same matched node, both cross-node`() {
            // nodeA(value="X") → nodeB(value="X") → nodeC(value="X")
            // forbid: a must NOT link to a node with value == a.value that also has an outgoing edge
            // require: a must link to a node with value="good"
            //
            // Actually let's simplify: same-value forbid + different-property require
            // nodeA.value="X", nodeB.value="X",  nodeA→nodeB
            // nodeC.value="Y", nodeD.value="Y",  nodeC→nodeD (tag="good"), nodeC also requires tagged node
            val nodeA = addNode("X");  val nodeB = addNode("X", "bad");  addEdge(nodeA, nodeB)
            val nodeC = addNode("Y");  val nodeD = addNode("Y", "good"); addEdge(nodeC, nodeD)

            // match a:Node,
            // forbid  fbd:Node { value == a.value, tag == "bad"  }, forbidLink a→fbd
            // require req:Node { value == a.value, tag == "good" }, requireLink a→req
            val count = matchCount(
                matchNode("a"),
                forbidNodeWithProp("fbd", "tag", str("bad")),   // using constant on forbid
                forbidLink("a", "fbd"),
                requireNodeWithProp("req", "value", nodeValueExpr("a")),
                requireLink("a", "req")
            )
            // nodeA: forbid check: a.to→fbd with fbd.tag=="bad" → nodeB.tag="bad" → FORBIDDEN
            // nodeB: no outgoing edge → forbid doesn't trigger. require: no outgoing edge → REQUIRE FAILS
            // nodeC: forbid check: a.to→fbd with fbd.tag=="bad"? nodeD.tag="good" ≠ "bad" → OK
            //        require check: a.to→req where req.value==nodeC.value="Y"? nodeD.value="Y" ✓ → OK → included
            // nodeD: no outgoing edge → require fails → excluded
            assertEquals(1, count, "Only nodeC passes: forbid doesn't trigger, require is satisfied")
        }
    }

    // =========================================================================
    // 6. Forbid/require islands with branching trees (N-2 backtracking via cross-node ref)
    // =========================================================================

    @Nested
    inner class BranchingIslandCrossNodePropertyTests {

        @Test
        fun `6a - branching forbid tree with cross-node property on each branch`() {
            // Matched: nodeA(value="X")
            // Forbid tree: nodeA → nodeB, nodeA → nodeC
            // forbid b:Node { value == a.value }, forbidLink a→b
            // forbid c:Node { value == a.value }, forbidLink a→c
            // (two separate single-hop islands, both cross-referencing a)
            val nodeA = addNode("X"); val nodeB = addNode("X"); val nodeC = addNode("X")
            addEdge(nodeA, nodeB); addEdge(nodeA, nodeC)

            val count = matchCount(
                matchNode("a"),
                forbidNodeWithProp("b", "value", nodeValueExpr("a")),
                forbidLink("a", "b"),
                forbidNodeWithProp("c", "value", nodeValueExpr("a")),
                forbidLink("a", "c")
            )
            // nodeA: both b and c can be satisfied (nodeB and nodeC have same value) → BOTH FORBIDS TRIGGER
            //   (each forbid is a separate island; both are forbidden)
            //   → nodeA excluded
            // nodeB: no outgoing edges → neither forbid triggers → nodeB included
            // nodeC: no outgoing edges → included
            assertEquals(2, count, "nodeA excluded by dual cross-node forbids; nodeB and nodeC included")
        }

        @Test
        fun `6b - branching forbid with different property conditions on each branch`() {
            // nodeA(value="X", tag="T") → nodeB(value="X"), nodeA → nodeC(tag="T")
            val nodeA = addNode("X", "T"); val nodeB = addNode("X"); val nodeC = addNode("Z", "T")
            addEdge(nodeA, nodeB); addEdge(nodeA, nodeC)

            // forbid b on value; forbid c on tag – should trigger for b (X==X) but not test a's tag
            // Island 1: forbid b { value == a.value }, link a→b → triggers for nodeA (nodeB.value == nodeA.value)
            // Island 2: forbid c { tag == a.tag }, link a→c → triggers for nodeA (nodeC.tag == nodeA.tag)
            // nodeA falls to both forbids → excluded
            val forbidBelem = forbidNodeWithProp("b", "value", nodeValueExpr("a"))
            val forbidCelem = forbidNodeWithProp("c", "tag", nodeTagExpr("a"))

            val count = matchCount(
                matchNode("a"),
                forbidBelem, forbidLink("a", "b"),
                forbidCelem, forbidLink("a", "c")
            )
            // nodeA: island 1 triggers (b matches by value), island 2 triggers (c matches by tag) → excluded
            // nodeB: no outgoing edge → included
            // nodeC: no outgoing edge → included
            assertEquals(2, count, "nodeA excluded by two independent cross-node forbid islands")
        }
    }
}
