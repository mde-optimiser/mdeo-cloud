package com.mdeo.modeltransformation.runtime.match

import com.mdeo.expression.ast.expressions.TypedBinaryExpression
import com.mdeo.expression.ast.expressions.TypedStringLiteralExpression
import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.modeltransformation.ast.EdgeLabelUtils
import com.mdeo.modeltransformation.ast.TypedAst
import com.mdeo.modeltransformation.ast.patterns.*
import com.mdeo.modeltransformation.ast.statements.TypedMatchStatement
import com.mdeo.modeltransformation.compiler.ExpressionCompilerRegistry
import com.mdeo.modeltransformation.graph.tinker.TinkerModelGraph
import com.mdeo.modeltransformation.runtime.*
import com.mdeo.modeltransformation.runtime.statements.MatchStatementExecutor
import com.mdeo.modeltransformation.runtime.match.ApplicationConditionTest.Companion.matchInstance
import com.mdeo.modeltransformation.runtime.match.ApplicationConditionTest.Companion.matchLink
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for the evaluation of application condition blocks.
 *
 * Every `forbid` / `require` block carries a graph of its own and is evaluated as a whole:
 *
 * - **`forbid` blocks** generate `not(...)` clauses: the match succeeds only when the
 *   entire block graph does NOT exist.
 * - **`require` blocks** generate existential checks: the match succeeds only when the
 *   entire block graph DOES exist. They are pure filters and do not multiply the result
 *   set.
 *
 * A block may be attached to matched nodes (via links whose source or target is a matched
 * node) or completely detached from the match (no links to any matched node).
 */
class ApplicationConditionTest {

    private lateinit var graph: TinkerGraph
    private lateinit var engine: TransformationEngine
    private lateinit var context: TransformationExecutionContext
    private lateinit var executor: MatchStatementExecutor

    @BeforeEach
    fun setup() {
        graph = TinkerGraph.open()
        engine = TransformationEngine(
            modelGraph = TinkerModelGraph.wrap(graph),
            ast = TypedAst(types = emptyList(), metamodelPath = "test://model", statements = emptyList()),
            expressionCompilerRegistry = ExpressionCompilerRegistry.createDefaultRegistry(),
            statementExecutorRegistry = StatementExecutorRegistry.createDefaultRegistry()
        )
        context = TransformationExecutionContext.empty()
        executor = MatchStatementExecutor()
    }

    @AfterEach
    fun tearDown() {
        graph.close()
    }

    // ========================================================================
    // 1. Forbid Block Tests
    // ========================================================================

    @Nested
    inner class ForbidBlockTests {

        @Test
        fun `1a - disconnected forbid node succeeds when forbidden class absent`() {
            graph.addVertex("House")

            val statement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        matchInstance("house", "House"),
                        forbidBlock(
                            matchInstance("room", "Room")
                        )
                    )
                )
            )

            val result = engine.executeStatement(statement, context)
            assertIs<TransformationExecutionResult.Success>(result)
        }

        @Test
        fun `1a - disconnected forbid node fails when forbidden class present`() {
            graph.addVertex("House")
            graph.addVertex("Room")

            val statement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        matchInstance("house", "House"),
                        forbidBlock(
                            matchInstance("room", "Room")
                        )
                    )
                )
            )

            val result = engine.executeStatement(statement, context)
            assertIs<TransformationExecutionResult.Failure>(result)
        }

        @Test
        fun `1b - connected forbid block succeeds when no forbidden target exists`() {
            graph.addVertex("House")

            val statement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        matchInstance("house", "House"),
                        forbidBlock(
                            matchInstance("room", "Room"),
                            matchLink("house", "rooms", "room", null)
                        )
                    )
                )
            )

            val result = engine.executeStatement(statement, context)
            assertIs<TransformationExecutionResult.Success>(result)
        }

        @Test
        fun `1b - connected forbid block fails when forbidden target exists`() {
            val house = graph.addVertex("House")
            val room = graph.addVertex("Room")
            house.addEdge(EdgeLabelUtils.computeEdgeLabel("rooms", null), room)

            val statement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        matchInstance("house", "House"),
                        forbidBlock(
                            matchInstance("room", "Room"),
                            matchLink("house", "rooms", "room", null)
                        )
                    )
                )
            )

            val result = engine.executeStatement(statement, context)
            assertIs<TransformationExecutionResult.Failure>(result)
        }

        @Test
        fun `1c - forbid chain succeeds when neither chained node exists`() {
            graph.addVertex("House")

            val statement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        matchInstance("house", "House"),
                        forbidBlock(
                            matchInstance("room", "Room"),
                            matchInstance("garden", "Garden"),
                            matchLink("house", "rooms", "room", "house"),
                            matchLink("room", "gardens", "garden", "room")
                        )
                    )
                )
            )

            val result = engine.executeStatement(statement, context)
            assertIs<TransformationExecutionResult.Success>(result)
        }

        @Test
        fun `1c - forbid chain succeeds when only partial chain exists`() {
            val house = graph.addVertex("House")
            val room = graph.addVertex("Room")
            house.addEdge(EdgeLabelUtils.computeEdgeLabel("rooms", "house"), room)

            val statement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        matchInstance("house", "House"),
                        forbidBlock(
                            matchInstance("room", "Room"),
                            matchInstance("garden", "Garden"),
                            matchLink("house", "rooms", "room", "house"),
                            matchLink("room", "gardens", "garden", "room")
                        )
                    )
                )
            )

            val result = engine.executeStatement(statement, context)
            assertIs<TransformationExecutionResult.Success>(result)
        }

        @Test
        fun `1c - forbid chain fails when full chain exists`() {
            val house = graph.addVertex("House")
            val room = graph.addVertex("Room")
            val garden = graph.addVertex("Garden")
            house.addEdge(EdgeLabelUtils.computeEdgeLabel("rooms", "house"), room)
            room.addEdge(EdgeLabelUtils.computeEdgeLabel("gardens", "room"), garden)

            val statement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        matchInstance("house", "House"),
                        forbidBlock(
                            matchInstance("room", "Room"),
                            matchInstance("garden", "Garden"),
                            matchLink("house", "rooms", "room", "house"),
                            matchLink("room", "gardens", "garden", "room")
                        )
                    )
                )
            )

            val result = engine.executeStatement(statement, context)
            assertIs<TransformationExecutionResult.Failure>(result)
        }

        @Test
        fun `1d - multiple separate forbid blocks both absent succeeds`() {
            graph.addVertex("House")

            val statement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        matchInstance("house", "House"),
                        forbidBlock(
                            matchInstance("room", "Room"),
                            matchLink("house", "rooms", "room", null)
                        ),
                        forbidBlock(
                            matchInstance("garage", "Garage")
                        )
                    )
                )
            )

            val result = engine.executeStatement(statement, context)
            assertIs<TransformationExecutionResult.Success>(result)
        }

        @Test
        fun `1d - multiple forbid blocks fails when connected block matches`() {
            val house = graph.addVertex("House")
            val room = graph.addVertex("Room")
            house.addEdge(EdgeLabelUtils.computeEdgeLabel("rooms", null), room)

            val statement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        matchInstance("house", "House"),
                        forbidBlock(
                            matchInstance("room", "Room"),
                            matchLink("house", "rooms", "room", null)
                        ),
                        forbidBlock(
                            matchInstance("garage", "Garage")
                        )
                    )
                )
            )

            val result = engine.executeStatement(statement, context)
            assertIs<TransformationExecutionResult.Failure>(result)
        }

        @Test
        fun `1d - multiple forbid blocks fails when disconnected block matches`() {
            graph.addVertex("House")
            graph.addVertex("Garage")

            val statement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        matchInstance("house", "House"),
                        forbidBlock(
                            matchInstance("room", "Room"),
                            matchLink("house", "rooms", "room", null)
                        ),
                        forbidBlock(
                            matchInstance("garage", "Garage")
                        )
                    )
                )
            )

            val result = engine.executeStatement(statement, context)
            assertIs<TransformationExecutionResult.Failure>(result)
        }

        @Test
        fun `1e - forbid link between two matched nodes succeeds when no edge`() {
            val house = graph.addVertex("House")
            val room = graph.addVertex("Room")

            val statement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        matchInstance("house", "House"),
                        matchInstance("room", "Room"),
                        forbidBlock(
                            matchLink("house", "rooms", "room", "house")
                        )
                    )
                )
            )

            val result = engine.executeStatement(statement, context)
            assertIs<TransformationExecutionResult.Success>(result)
        }

        @Test
        fun `1e - forbid link between two matched nodes fails when edge exists`() {
            val house = graph.addVertex("House")
            val room = graph.addVertex("Room")
            house.addEdge(EdgeLabelUtils.computeEdgeLabel("rooms", "house"), room)

            val statement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        matchInstance("house", "House"),
                        matchInstance("room", "Room"),
                        forbidBlock(
                            matchLink("house", "rooms", "room", "house")
                        )
                    )
                )
            )

            val result = engine.executeStatement(statement, context)
            assertIs<TransformationExecutionResult.Failure>(result)
        }
    }

    // ========================================================================
    // 1f. Branching Forbid Block Tests
    //
    // Reproduces the bug in buildBlockChainTraversal where a branching (tree-shaped)
    // forbid pattern requires BFS backtracking via .select() to a forbid node that was
    // never labeled with .as() — because the label was only applied when
    // matchableNames.contains(toNode).
    //
    // Scenario topology:
    //   Match:  A
    //   Forbid: C, D, E
    //   Links:  A->C, C->D, C->E
    //
    // BFS produces orderedLinks = [(A->C), (C->D), (C->E)].
    // After walking A->C->D, the chain attempts to backtrack to C via
    //   chain.select(step_C)
    // but C was never labeled. This causes a Gremlin error.
    // ========================================================================

    @Nested
    inner class ForbidBranchingBlockTests {

        /**
         * Full forbid branch present: the match on A should be blocked because
         * both C->D and C->E sub-branches of the forbidden tree exist.
         *
         * Before the fix this test throws a Gremlin exception caused by the missing
         * .as() label on the intermediate forbid node C.
         */
        @Test
        fun `1f - branching forbid block fails when full branch exists`() {
            val a = graph.addVertex("NodeA")
            val c = graph.addVertex("NodeC")
            val d = graph.addVertex("NodeD")
            val e = graph.addVertex("NodeE")
            a.addEdge(EdgeLabelUtils.computeEdgeLabel("children", "parent"), c)
            c.addEdge(EdgeLabelUtils.computeEdgeLabel("left", "owner"), d)
            c.addEdge(EdgeLabelUtils.computeEdgeLabel("right", "owner"), e)

            val statement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        matchInstance("a", "NodeA"),
                        forbidBlock(
                            matchInstance("c", "NodeC"),
                            matchInstance("d", "NodeD"),
                            matchInstance("e", "NodeE"),
                            matchLink("a", "children", "c", "parent"),
                            matchLink("c", "left", "d", "owner"),
                            matchLink("c", "right", "e", "owner")
                        )
                    )
                )
            )

            val result = engine.executeStatement(statement, context)
            assertIs<TransformationExecutionResult.Failure>(result)
        }

        /**
         * Only the first branch (C->D) exists, but not the second (C->E).
         * The forbid pattern requires all three nodes C, D, E, so the partial match
         * should NOT trigger the forbid — the match on A should succeed.
         *
         * Before the fix this test also throws a Gremlin exception.
         */
        @Test
        fun `1f - branching forbid block succeeds when second branch is absent`() {
            val a = graph.addVertex("NodeA")
            val c = graph.addVertex("NodeC")
            val d = graph.addVertex("NodeD")
            // NodeE is absent; the second branch does not exist
            a.addEdge(EdgeLabelUtils.computeEdgeLabel("children", "parent"), c)
            c.addEdge(EdgeLabelUtils.computeEdgeLabel("left", "owner"), d)

            val statement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        matchInstance("a", "NodeA"),
                        forbidBlock(
                            matchInstance("c", "NodeC"),
                            matchInstance("d", "NodeD"),
                            matchInstance("e", "NodeE"),
                            matchLink("a", "children", "c", "parent"),
                            matchLink("c", "left", "d", "owner"),
                            matchLink("c", "right", "e", "owner")
                        )
                    )
                )
            )

            val result = engine.executeStatement(statement, context)
            assertIs<TransformationExecutionResult.Success>(result)
        }

        /**
         * No forbidden nodes exist at all. The match on A should succeed trivially.
         *
         * Before the fix this test is expected to also throw.
         */
        @Test
        fun `1f - branching forbid block succeeds when no forbidden nodes exist`() {
            graph.addVertex("NodeA")
            // No NodeC, NodeD, or NodeE in the graph

            val statement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        matchInstance("a", "NodeA"),
                        forbidBlock(
                            matchInstance("c", "NodeC"),
                            matchInstance("d", "NodeD"),
                            matchInstance("e", "NodeE"),
                            matchLink("a", "children", "c", "parent"),
                            matchLink("c", "left", "d", "owner"),
                            matchLink("c", "right", "e", "owner")
                        )
                    )
                )
            )

            val result = engine.executeStatement(statement, context)
            assertIs<TransformationExecutionResult.Success>(result)
        }
    }

    // ========================================================================
    // 2. Require Block Tests
    // ========================================================================

    @Nested
    inner class RequireBlockTests {

        @Test
        fun `2a - connected require block succeeds when required target exists`() {
            val house = graph.addVertex("House")
            val room = graph.addVertex("Room")
            house.addEdge(EdgeLabelUtils.computeEdgeLabel("rooms", "house"), room)

            val statement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        matchInstance("house", "House"),
                        requireBlock(
                            matchInstance("room", "Room"),
                            matchLink("house", "rooms", "room", "house")
                        )
                    )
                )
            )

            val result = engine.executeStatement(statement, context)
            assertIs<TransformationExecutionResult.Success>(result)
        }

        @Test
        fun `2a - connected require block fails when required target absent`() {
            graph.addVertex("House")

            val statement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        matchInstance("house", "House"),
                        requireBlock(
                            matchInstance("room", "Room"),
                            matchLink("house", "rooms", "room", "house")
                        )
                    )
                )
            )

            val result = engine.executeStatement(statement, context)
            assertIs<TransformationExecutionResult.Failure>(result)
        }

        @Test
        fun `2b - disconnected require node succeeds when required class exists`() {
            graph.addVertex("House")
            graph.addVertex("Room")

            val statement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        matchInstance("house", "House"),
                        requireBlock(
                            matchInstance("room", "Room")
                        )
                    )
                )
            )

            val result = engine.executeStatement(statement, context)
            assertIs<TransformationExecutionResult.Success>(result)
        }

        @Test
        fun `2b - disconnected require node fails when required class absent`() {
            graph.addVertex("House")

            val statement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        matchInstance("house", "House"),
                        requireBlock(
                            matchInstance("room", "Room")
                        )
                    )
                )
            )

            val result = engine.executeStatement(statement, context)
            assertIs<TransformationExecutionResult.Failure>(result)
        }

        @Test
        fun `2c - connected require chain succeeds when full chain exists`() {
            val house = graph.addVertex("House")
            val room = graph.addVertex("Room")
            val window = graph.addVertex("Window")
            house.addEdge(EdgeLabelUtils.computeEdgeLabel("rooms", "house"), room)
            room.addEdge(EdgeLabelUtils.computeEdgeLabel("windows", "room"), window)

            val statement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        matchInstance("house", "House"),
                        requireBlock(
                            matchInstance("room", "Room"),
                            matchInstance("window", "Window"),
                            matchLink("house", "rooms", "room", "house"),
                            matchLink("room", "windows", "window", "room")
                        )
                    )
                )
            )

            val result = engine.executeStatement(statement, context)
            assertIs<TransformationExecutionResult.Success>(result)
        }

        @Test
        fun `2c - connected require chain fails when tail of chain missing`() {
            val house = graph.addVertex("House")
            val room = graph.addVertex("Room")
            house.addEdge(EdgeLabelUtils.computeEdgeLabel("rooms", "house"), room)

            val statement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        matchInstance("house", "House"),
                        requireBlock(
                            matchInstance("room", "Room"),
                            matchInstance("window", "Window"),
                            matchLink("house", "rooms", "room", "house"),
                            matchLink("room", "windows", "window", "room")
                        )
                    )
                )
            )

            val result = engine.executeStatement(statement, context)
            assertIs<TransformationExecutionResult.Failure>(result)
        }

        @Test
        fun `2d - require does not multiply matches`() {
            val house = graph.addVertex("House")
            val room1 = graph.addVertex("Room")
            val room2 = graph.addVertex("Room")
            val room3 = graph.addVertex("Room")
            house.addEdge(EdgeLabelUtils.computeEdgeLabel("rooms", "house"), room1)
            house.addEdge(EdgeLabelUtils.computeEdgeLabel("rooms", "house"), room2)
            house.addEdge(EdgeLabelUtils.computeEdgeLabel("rooms", "house"), room3)

            val pattern = TypedPattern(
                elements = listOf(
                    matchInstance("house", "House"),
                    requireBlock(
                        matchInstance("room", "Room"),
                        matchLink("house", "rooms", "room", "house")
                    )
                )
            )

            val matchExecutor = MatchExecutor()
            val results = matchExecutor.executeMatchAll(pattern, context, engine)
            assertEquals(1, results.size)
        }
    }

    // ========================================================================
    // 3. Mixed Forbid + Require + Match
    // ========================================================================

    @Nested
    inner class MixedConstraintTests {

        @Test
        fun `3a - require satisfied and forbid absent succeeds`() {
            val house = graph.addVertex("House")
            val room = graph.addVertex("Room")
            house.addEdge(EdgeLabelUtils.computeEdgeLabel("rooms", "house"), room)

            val statement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        matchInstance("house", "House"),
                        forbidBlock(
                            matchInstance("garage", "Garage")
                        ),
                        requireBlock(
                            matchInstance("room", "Room"),
                            matchLink("house", "rooms", "room", "house")
                        )
                    )
                )
            )

            val result = engine.executeStatement(statement, context)
            assertIs<TransformationExecutionResult.Success>(result)
        }

        @Test
        fun `3a - require satisfied but forbid present fails`() {
            val house = graph.addVertex("House")
            val room = graph.addVertex("Room")
            house.addEdge(EdgeLabelUtils.computeEdgeLabel("rooms", "house"), room)
            graph.addVertex("Garage")

            val statement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        matchInstance("house", "House"),
                        forbidBlock(
                            matchInstance("garage", "Garage")
                        ),
                        requireBlock(
                            matchInstance("room", "Room"),
                            matchLink("house", "rooms", "room", "house")
                        )
                    )
                )
            )

            val result = engine.executeStatement(statement, context)
            assertIs<TransformationExecutionResult.Failure>(result)
        }

        @Test
        fun `3a - require absent and forbid absent fails`() {
            graph.addVertex("House")

            val statement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        matchInstance("house", "House"),
                        forbidBlock(
                            matchInstance("garage", "Garage")
                        ),
                        requireBlock(
                            matchInstance("room", "Room"),
                            matchLink("house", "rooms", "room", "house")
                        )
                    )
                )
            )

            val result = engine.executeStatement(statement, context)
            assertIs<TransformationExecutionResult.Failure>(result)
        }

        @Test
        fun `mixed forbid and require blocks with match links`() {
            val house = graph.addVertex("House")
            val room = graph.addVertex("Room")
            house.addEdge(EdgeLabelUtils.computeEdgeLabel("rooms", "house"), room)

            val statement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        matchInstance("house", "House"),
                        matchInstance("room", "Room"),
                        matchLink("house", "rooms", "room", "house"),
                        forbidBlock(
                            matchInstance("pool", "Pool"),
                            matchLink("house", "pools", "pool", null)
                        ),
                        requireBlock(
                            matchInstance("garden", "Garden")
                        )
                    )
                )
            )

            val result = engine.executeStatement(statement, context)
            assertIs<TransformationExecutionResult.Failure>(result)
        }

        @Test
        fun `mixed forbid and require blocks with all satisfied`() {
            val house = graph.addVertex("House")
            val room = graph.addVertex("Room")
            house.addEdge(EdgeLabelUtils.computeEdgeLabel("rooms", "house"), room)
            graph.addVertex("Garden")

            val statement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        matchInstance("house", "House"),
                        matchInstance("room", "Room"),
                        matchLink("house", "rooms", "room", "house"),
                        forbidBlock(
                            matchInstance("pool", "Pool"),
                            matchLink("house", "pools", "pool", null)
                        ),
                        requireBlock(
                            matchInstance("garden", "Garden")
                        )
                    )
                )
            )

            val result = engine.executeStatement(statement, context)
            assertIs<TransformationExecutionResult.Success>(result)
        }
    }

    // ========================================================================
    // 1g. Forbid Block with Property Condition Tests
    //
    // Reproduces the bug where a forbid block connected to a matched node ignores
    // the property conditions on the forbid instance.
    //
    // Scenario (mirrors the user-reported bug):
    //   Match:  node : Node
    //   Forbid: node2 : Node { value == "thisValueNeverOccurs" }
    //   Forbid link: node.to -- node2.from
    //
    // Graph:
    //   nodeA (Node, value="old") --[to_from]--> nodeB (Node, value="old")
    //   nodeC (Node, value="old")   (no outgoing edge)
    //
    // The forbid condition can NEVER match because no Node has value=="thisValueNeverOccurs".
    // Therefore executeMatchAll should return 3 results (one per node).
    //
    // BUG: buildBlockChainTraversal builds the chain
    //   __.as(step_node).out("to_from").hasLabel("Node")
    // and wraps it in not(...). It never adds .has("value", "thisValueNeverOccurs").
    // This causes:
    //   - nodeA: not( nodeA.out("to_from").hasLabel("Node") ) = not(nodeB) = FALSE → excluded
    //   - nodeB,C: not(empty) = TRUE → included
    // Result: only 2 of 3 matches, not 3.
    // ========================================================================

    @Nested
    inner class ForbidBlockWithPropertyConditionTests {

        /**
         * 1g-FAILS: property condition on forbid block is currently ignored.
         *
         * The forbid instance has `value == "thisValueNeverOccurs"`.  No node in the
         * graph has that value, so the forbid should NEVER trigger.  All three Node
         * vertices must match.
         *
         * Bug: the generated chain is
         *   `__.as(step_node).out("to_from").hasLabel("Node")`
         * — the `.has("value","thisValueNeverOccurs")` step is missing.
         * For nodeA (which has an outgoing "to_from" edge to another Node) the
         * `not(...)` evaluates to `false`, incorrectly excluding it.
         */
        @Test
        fun `1g - forbid with property condition never matching allows all nodes to match`() {
            // Three Node vertices, all with value="old"
            val nodeA = graph.addVertex("Node")
            nodeA.property("value", "old")
            val nodeB = graph.addVertex("Node")
            nodeB.property("value", "old")
            val nodeC = graph.addVertex("Node")
            nodeC.property("value", "old")

            // nodeA has an outgoing "to_from" edge to nodeB
            nodeA.addEdge(EdgeLabelUtils.computeEdgeLabel("to", "from"), nodeB)

            val pattern = TypedPattern(
                elements = listOf(
                    matchInstance("node", "Node"),
                    forbidBlock(
                        instanceWithStringProperty("node2", "Node", "value", "thisValueNeverOccurs"),
                        matchLink("node", "to", "node2", "from")
                    )
                )
            )

            val matchExecutor = MatchExecutor()
            val results = matchExecutor.executeMatchAll(pattern, context, engine)

            // All three nodes must match because the forbid condition never applies
            assertEquals(3, results.size,
                "Expected 3 matches (forbid condition never matches any node), but got ${results.size}. " +
                "This indicates the forbid block is not applying its property conditions.")
        }

        /**
         * 1g-PASSES: sanity check — when the forbid condition CAN match, the rule correctly
         * excludes the matched node.
         *
         * nodeA→nodeB edge exists and nodeB has value="forbidden". For nodeA the forbid
         * block (node2:Node{value=="forbidden"} linked from node.to) does match, so nodeA
         * should be excluded. nodeB and nodeC have no such outgoing edge, so they are included.
         * Expected: 2 matches (nodeB, nodeC).
         */
        @Test
        fun `1g - forbid with property condition that matches excludes the correct node`() {
            val nodeA = graph.addVertex("Node")
            nodeA.property("value", "old")
            val nodeB = graph.addVertex("Node")
            nodeB.property("value", "forbidden")  // <-- this value IS forbidden
            val nodeC = graph.addVertex("Node")
            nodeC.property("value", "old")

            nodeA.addEdge(EdgeLabelUtils.computeEdgeLabel("to", "from"), nodeB)

            val pattern = TypedPattern(
                elements = listOf(
                    matchInstance("node", "Node"),
                    forbidBlock(
                        instanceWithStringProperty("node2", "Node", "value", "forbidden"),
                        matchLink("node", "to", "node2", "from")
                    )
                )
            )

            val matchExecutor = MatchExecutor()
            val results = matchExecutor.executeMatchAll(pattern, context, engine)

            // nodeA is excluded (it links to nodeB which has value=="forbidden")
            // nodeB and nodeC have no outgoing "to_from" edge, so their forbid chains are empty
            assertEquals(2, results.size,
                "Expected 2 matches (nodeA excluded because it links to a Node with value='forbidden').")
        }

        /**
         * 1g - non-static expression in forbid condition: all nodes must match.
         *
         * The forbid instance has `value == "thisValue" + "NeverOccurs"`.
         * The concatenated string ("thisValueNeverOccurs") never occurs on any vertex,
         * so the forbid should NEVER trigger and all 3 nodes must match.
         *
         * Previously the non-static TraversalResult branch was silently skipped, causing
         * the forbid to trigger on nodeA (which has an outgoing edge) even though the
         * target value never matched — incorrectly excluding nodeA.
         */
        @Test
        fun `1g - non-static expression in forbid condition never matches - all nodes must match`() {
            // Three Node vertices, all with value="old" (never equals "thisValue"+"NeverOccurs")
            val nodeA = graph.addVertex("Node")
            nodeA.property("value", "old")
            val nodeB = graph.addVertex("Node")
            nodeB.property("value", "old")
            val nodeC = graph.addVertex("Node")
            nodeC.property("value", "old")

            // nodeA has an outgoing "to_from" edge to nodeB
            nodeA.addEdge(EdgeLabelUtils.computeEdgeLabel("to", "from"), nodeB)

            // Build a local engine that has the string type registered at index 0
            // so that BinaryOperatorCompiler can resolve the evalType of the operands.
            val stringType = ClassTypeRef(`package` = "builtin", type = "string", isNullable = false)
            val localEngine = TransformationEngine(
                modelGraph = TinkerModelGraph.wrap(graph),
                ast = TypedAst(
                    types = listOf(stringType),
                    metamodelPath = "test://model",
                    statements = emptyList()
                ),
                expressionCompilerRegistry = ExpressionCompilerRegistry.createDefaultRegistry(),
                statementExecutorRegistry = StatementExecutorRegistry.createDefaultRegistry()
            )

            val pattern = TypedPattern(
                elements = listOf(
                    matchInstance("node", "Node"),
                    forbidBlock(
                        instanceWithStringConcatExpression("node2", "Node", "value", "thisValue", "NeverOccurs"),
                        matchLink("node", "to", "node2", "from")
                    )
                )
            )

            val matchExecutor = MatchExecutor()
            val results = matchExecutor.executeMatchAll(pattern, context, localEngine)

            // All three nodes must match: the forbid target value ("thisValueNeverOccurs")
            // never exists, so the forbid block never triggers.
            assertEquals(3, results.size,
                "Expected 3 matches because the forbid non-static expression never matches any node, " +
                "but got ${results.size}. This indicates the TraversalResult branch is still broken.")
        }
    }

    // ========================================================================
    // Helper factory methods for constructing pattern elements
    // ========================================================================

    companion object {

        /**
         * Creates a match (no modifier) object instance element.
         */
        fun matchInstance(name: String, className: String): TypedPatternObjectInstanceElement {
            return TypedPatternObjectInstanceElement(
                objectInstance = TypedPatternObjectInstance(
                    modifier = null,
                    name = name,
                    className = className,
                    properties = emptyList()
                )
            )
        }

        /**
         * Creates an object instance element with a single String equality condition.
         *
         * Produces an instance equivalent to:
         *   `<name> : <className> { <propertyName> == "<propertyValue>" }`
         *
         * Used to reproduce bugs where the condition chain traversal ignores property
         * conditions on condition instances.
         */
        fun instanceWithStringProperty(
            name: String,
            className: String,
            propertyName: String,
            propertyValue: String
        ): TypedPatternObjectInstanceElement {
            return TypedPatternObjectInstanceElement(
                objectInstance = TypedPatternObjectInstance(
                    modifier = null,
                    name = name,
                    className = className,
                    properties = listOf(
                        TypedPatternPropertyAssignment(
                            propertyName = propertyName,
                            operator = "==",
                            value = TypedStringLiteralExpression(evalType = 0, value = propertyValue)
                        )
                    )
                )
            )
        }

        /**
         * Creates an object instance element with a string-concatenation expression
         * as the equality condition.
         *
         * Produces an instance equivalent to:
         *   `<name> : <className> { <propertyName> == <left> + <right> }`
         *
         * The expression is a non-static `TypedBinaryExpression` (operator "+"), so it
         * compiles to a `CompilationResult.TraversalResult` — exercising the code path
         * that was previously silently skipped.
         */
        fun instanceWithStringConcatExpression(
            name: String,
            className: String,
            propertyName: String,
            left: String,
            right: String
        ): TypedPatternObjectInstanceElement {
            return TypedPatternObjectInstanceElement(
                objectInstance = TypedPatternObjectInstance(
                    modifier = null,
                    name = name,
                    className = className,
                    properties = listOf(
                        TypedPatternPropertyAssignment(
                            propertyName = propertyName,
                            operator = "==",
                            value = TypedBinaryExpression(
                                evalType = 0,
                                operator = "+",
                                left = TypedStringLiteralExpression(evalType = 0, value = left),
                                right = TypedStringLiteralExpression(evalType = 0, value = right)
                            )
                        )
                    )
                )
            )
        }

        /**
         * Creates a match (no modifier) link element.
         */
        fun matchLink(
            sourceName: String,
            sourceProperty: String?,
            targetName: String,
            targetProperty: String?
        ): TypedPatternLinkElement {
            return TypedPatternLinkElement(
                link = TypedPatternLink(
                    modifier = null,
                    source = TypedPatternLinkEnd(objectName = sourceName, propertyName = sourceProperty),
                    target = TypedPatternLinkEnd(objectName = targetName, propertyName = targetProperty)
                )
            )
        }

    }
}

// ========================================================================
// 5. Block Injective Match Tests
//
// Forbid/require blocks must enforce injective matching:
//
// - Each block node must bind to a vertex DISTINCT from every main-pattern
//   (matchable, no modifier) node that has the same class.
// - Each block node must also be distinct from earlier block nodes within the
//   SAME block that share the same class (within-block injectivity).
// - Nodes in OTHER blocks are NOT considered.
//
// These tests verify the new semantics introduced to fix a core bug where the
// block evaluation could accidentally reuse an already-matched vertex.
// ========================================================================

/**
 * Tests for injective matching within forbid/require blocks.
 */
class ConditionInjectiveMatchTest {

    private lateinit var graph: TinkerGraph
    private lateinit var engine: TransformationEngine
    private lateinit var context: TransformationExecutionContext

    @BeforeEach
    fun setup() {
        graph = TinkerGraph.open()
        engine = TransformationEngine(
            modelGraph = TinkerModelGraph.wrap(graph),
            ast = TypedAst(types = emptyList(), metamodelPath = "test://model", statements = emptyList()),
            expressionCompilerRegistry = ExpressionCompilerRegistry.createDefaultRegistry(),
            statementExecutorRegistry = StatementExecutorRegistry.createDefaultRegistry()
        )
        context = TransformationExecutionContext.empty()
    }

    @AfterEach
    fun tearDown() {
        graph.close()
    }

    // ========================================================================
    // 5a. Self-loop exclusion: block node must be distinct from matched anchor
    //
    // When only one vertex of a given type exists and it is the matched anchor,
    // a forbid block of the same type connected to the anchor via a self-loop
    // must NOT trigger, because the only candidate for the block node IS the
    // anchor itself — and injective matching forbids c == a.
    // ========================================================================

    @Nested
    inner class SelfLoopExclusion {

        /**
         * One Node vertex with a self-referential "ref" edge.
         *
         * Pattern: match a:Node; forbid c:Node -- "ref" --> a
         *
         * Without injective semantics: c = a (via self-loop), forbid fires → FAIL.
         * With injective semantics: c must != a; the only candidate is a itself, so no
         * valid c exists → forbid is NOT triggered → SUCCESS.
         */
        @Test
        fun `5a - self-loop does not trigger forbid block when block node must differ from anchor`() {
            val node = graph.addVertex("Node")
            node.addEdge(EdgeLabelUtils.computeEdgeLabel("ref", "backRef"), node) // self-loop

            val statement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        matchInstance("a", "Node"),
                        forbidBlock(
                            matchInstance("c", "Node"),
                            matchLink("c", "ref", "a", "backRef")
                        )
                    )
                )
            )

            val result = engine.executeStatement(statement, context)
            assertIs<TransformationExecutionResult.Success>(result,
                "Forbid block should NOT fire when the only candidate for the block node is the matched anchor itself")
        }

        /**
         * Two Node vertices: n1 and n2. n2 has a "ref" edge to n1.
         *
         * Pattern: match a:Node; forbid c:Node -- "ref" --> a
         *
         * When a = n1: c = n2 (distinct from n1) → forbid fires → n1 excluded.
         * When a = n2: no incoming "ref" edge → forbid not triggered → n2 included.
         *
         * Uses executeMatchAll to confirm n1 is never matched as `a`.
         */
        @Test
        fun `5a - forbid block fires when a distinct same-type node is connected`() {
            val n1 = graph.addVertex("Node")
            val n2 = graph.addVertex("Node")
            n2.addEdge(EdgeLabelUtils.computeEdgeLabel("ref", "backRef"), n1)

            val pattern = TypedPattern(
                elements = listOf(
                    matchInstance("a", "Node"),
                    forbidBlock(
                        matchInstance("c", "Node"),
                        matchLink("c", "ref", "a", "backRef")
                    )
                )
            )

            val matchExecutor = MatchExecutor()
            val results = matchExecutor.executeMatchAll(pattern, context, engine)

            // n1 is excluded because c=n2 (distinct from n1) satisfies the forbid.
            // n2 is the only valid match for a.
            val n1Id = n1.id()
            assertTrue(results.none { it.instanceMappings["a"]?.rawId == n1Id },
                "n1 should be excluded: c=n2 (distinct from a=n1) links to n1 → forbid fires")
            assertEquals(1, results.size, "Only n2 should match as a")
        }
    }

    // ========================================================================
    // 5b. Block node excluded from all main-pattern nodes of the same type
    //
    // When there are multiple matchable nodes of the same type, the block node
    // must be distinct from ALL of them — not just the anchor.
    // ========================================================================

    @Nested
    inner class BlockExcludedFromAllMatchedNodes {

        /**
         * Two matched Node vertices (a1, a2) and forbid block c:Node connected to a1.
         * The graph contains exactly a1 and a2 — a2 links to a1 via "ref".
         *
         * Pattern: match a1:Node, a2:Node; forbid c:Node -- "ref" --> a1
         *
         * The injective constraint for c is: c != a1 AND c != a2.
         * The only Node that links to a1 is a2, but a2 is excluded (c != a2).
         * Therefore no valid c exists → forbid NOT triggered → SUCCESS.
         *
         * Without block injective: c = a2 (which IS linked to a1), forbid fires → FAIL.
         */
        @Test
        fun `5b - forbid block does not fire when only already-matched nodes are connected`() {
            val n1 = graph.addVertex("Node")
            val n2 = graph.addVertex("Node")
            n2.addEdge(EdgeLabelUtils.computeEdgeLabel("ref", "backRef"), n1) // n2 -> n1

            val statement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        matchInstance("a1", "Node"),
                        matchInstance("a2", "Node"),
                        forbidBlock(
                            matchInstance("c", "Node"),
                            matchLink("c", "ref", "a1", "backRef")
                        )
                    )
                )
            )

            val result = engine.executeStatement(statement, context)
            assertIs<TransformationExecutionResult.Success>(result,
                "Forbid block should NOT fire when the only connected node of the right type is already matched")
        }

        /**
         * Three Node vertices: n1, n2, n3. n3 links to n1 via "ref".
         *
         * Pattern: match a1:Node, a2:Node; forbid c:Node -- "ref" --> a1
         *
         * (a1=n1, a2=n2): c=n3 (distinct from n1 and n2) → forbid fires → EXCLUDED.
         * (a1=n1, a2=n3): c must != n1,n3; n3 is matched as a2, no valid c → INCLUDED.
         * All other combos: no incoming "ref" edge to a1 → INCLUDED.
         *
         * The specific combination (a1=n1, a2=n2) must be absent from the results.
         */
        @Test
        fun `5b - forbid block fires when an unmatched same-type node is connected`() {
            val n1 = graph.addVertex("Node")
            val n2 = graph.addVertex("Node")
            val n3 = graph.addVertex("Node")
            n3.addEdge(EdgeLabelUtils.computeEdgeLabel("ref", "backRef"), n1) // n3 → n1

            val pattern = TypedPattern(
                elements = listOf(
                    matchInstance("a1", "Node"),
                    matchInstance("a2", "Node"),
                    forbidBlock(
                        matchInstance("c", "Node"),
                        matchLink("c", "ref", "a1", "backRef")
                    )
                )
            )

            val matchExecutor = MatchExecutor()
            val results = matchExecutor.executeMatchAll(pattern, context, engine)

            val n1Id = n1.id()
            val n2Id = n2.id()
            // The combination (a1=n1, a2=n2) is blocked because c=n3 (!=n1,!=n2) links to n1.
            val blockedCombo = results.filter {
                it.instanceMappings["a1"]?.rawId == n1Id && it.instanceMappings["a2"]?.rawId == n2Id
            }
            assertTrue(blockedCombo.isEmpty(),
                "(a1=n1, a2=n2) should be excluded because c=n3 (unmatched, distinct) fires the forbid")
            assertTrue(results.isNotEmpty(), "Other combinations should still produce matches")
        }
    }

    // ========================================================================
    // 5c. Within-block injective matching
    //
    // Two block nodes of the same type within the same block must bind to
    // distinct vertices.
    // ========================================================================

    @Nested
    inner class WithinBlockInjective {

        /**
         * Pattern: match x:Container; forbid c1:Item -- "link1" --> c2:Item -- "link2" --> x
         *
         * c1 and c2 are in the SAME block (connected via link1). With within-block
         * injective, c2 must be distinct from c1.
         *
         * Graph: one Container, one Item. The Item has a self-loop for link1 (c1=item, c2=item)
         * and links to the Container via link2.
         *
         * Without within-block injective: c1=item, c2=item → forbid fires → FAIL.
         * With within-block injective: c2 must != c1; only one Item, so no valid c2 → NOT triggered → SUCCESS.
         */
        @Test
        fun `5c - within-block injective prevents two block nodes binding to the same vertex`() {
            val container = graph.addVertex("Container")
            val item = graph.addVertex("Item")
            // Self-loop so c1=item can link to c2=item (same vertex), then c2→container
            item.addEdge(EdgeLabelUtils.computeEdgeLabel("link1", "src"), item)
            item.addEdge(EdgeLabelUtils.computeEdgeLabel("link2", "owner"), container)

            val statement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        matchInstance("x", "Container"),
                        forbidBlock(
                            matchInstance("c1", "Item"),
                            matchInstance("c2", "Item"),
                            matchLink("c1", "link1", "c2", "src"),
                            matchLink("c2", "link2", "x", "owner")
                        )
                    )
                )
            )

            val result = engine.executeStatement(statement, context)
            assertIs<TransformationExecutionResult.Success>(result,
                "Forbid block should NOT fire when the two block nodes would have to bind to the same vertex (c2 must != c1)")
        }

        /**
         * Pattern: match x:Container; forbid c1:Item -- "link1" --> c2:Item -- "link2" --> x
         *
         * Graph: one Container, two Items. item1 links to item2 (via link1), item2 links to container (via link2).
         *
         * c1=item1, c2=item2 (distinct) → within-block injective satisfied → forbid fires → FAIL.
         */
        @Test
        fun `5c - within-block forbid fires when two distinct same-type nodes exist`() {
            val container = graph.addVertex("Container")
            val item1 = graph.addVertex("Item")
            val item2 = graph.addVertex("Item")
            item1.addEdge(EdgeLabelUtils.computeEdgeLabel("link1", "src"), item2)
            item2.addEdge(EdgeLabelUtils.computeEdgeLabel("link2", "owner"), container)

            val statement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        matchInstance("x", "Container"),
                        forbidBlock(
                            matchInstance("c1", "Item"),
                            matchInstance("c2", "Item"),
                            matchLink("c1", "link1", "c2", "src"),
                            matchLink("c2", "link2", "x", "owner")
                        )
                    )
                )
            )

            val result = engine.executeStatement(statement, context)
            assertIs<TransformationExecutionResult.Failure>(result,
                "Forbid block should fire when two distinct same-type nodes satisfy the block pattern")
        }
    }

    // ========================================================================
    // 5d. Require block injective matching
    // ========================================================================

    @Nested
    inner class RequireBlockInjective {

        /**
         * Pattern: match a:Node; require c:Node -- "ref" --> a
         *
         * Graph: one Node with self-loop.
         *
         * Without injective: c = a (via self-loop) → require satisfied → SUCCESS.
         * With injective: c must != a → no valid c → require NOT satisfied → FAIL.
         */
        @Test
        fun `5d - require block with self-loop fails when block node must differ from matched node`() {
            val node = graph.addVertex("Node")
            node.addEdge(EdgeLabelUtils.computeEdgeLabel("ref", "backRef"), node) // self-loop

            val statement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        matchInstance("a", "Node"),
                        requireBlock(
                            matchInstance("c", "Node"),
                            matchLink("c", "ref", "a", "backRef")
                        )
                    )
                )
            )

            val result = engine.executeStatement(statement, context)
            assertIs<TransformationExecutionResult.Failure>(result,
                "Require block should FAIL when the only candidate is the matched anchor (injective: c must != a)")
        }

        /**
         * Pattern: match a:Node; require c:Node -- "ref" --> a
         *
         * Graph: two Nodes: n1 (anchor), n2. n2 links to n1.
         *
         * c = n2 (distinct from a = n1) → require satisfied → SUCCESS.
         */
        @Test
        fun `5d - require block succeeds when a distinct same-type node is connected`() {
            val n1 = graph.addVertex("Node")
            val n2 = graph.addVertex("Node")
            n2.addEdge(EdgeLabelUtils.computeEdgeLabel("ref", "backRef"), n1)

            val statement = TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        matchInstance("a", "Node"),
                        requireBlock(
                            matchInstance("c", "Node"),
                            matchLink("c", "ref", "a", "backRef")
                        )
                    )
                )
            )

            val result = engine.executeStatement(statement, context)
            assertIs<TransformationExecutionResult.Success>(result,
                "Require block should succeed when a distinct same-type node is connected to the anchor")
        }
    }
}
