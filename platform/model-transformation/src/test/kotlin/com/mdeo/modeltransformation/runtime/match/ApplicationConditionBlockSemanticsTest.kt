package com.mdeo.modeltransformation.runtime.match

import com.mdeo.expression.ast.expressions.TypedIntLiteralExpression
import com.mdeo.modeltransformation.ast.EdgeLabelUtils
import com.mdeo.modeltransformation.ast.TypedAst
import com.mdeo.modeltransformation.ast.patterns.TypedPattern
import com.mdeo.modeltransformation.ast.patterns.TypedPatternElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternPropertyAssignment
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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for the semantics that only explicit application condition blocks can express.
 *
 * The distinction this suite is about: elements of *one* block reject a match only when
 * they *all* match together, whereas *separate* blocks reject it when *any* of them
 * matches. Deriving conditions from element modifiers could not express this, because the
 * elements of a pattern were regrouped into connected components after the fact — two
 * unconnected forbidden nodes silently became two independent conditions, and there was no
 * way to ask for their conjunction.
 *
 * The model used throughout: `Patient` and `Admission` vertices, linked through
 * `Admission.patientId -- Patient`, mirroring the example from the syntax proposal.
 */
class ApplicationConditionBlockSemanticsTest {

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

    // ── Model helpers ─────────────────────────────────────────────────────────

    private fun addPatient(mandatory: Boolean, duration: Int = 0): Vertex {
        val vertex = graph.addVertex("Patient")
        vertex.property("isMandatory", mandatory)
        vertex.property("surgeryDuration", duration)
        return vertex
    }

    private fun addAdmission(): Vertex = graph.addVertex("Admission")

    private fun admit(admission: Vertex, patient: Vertex) {
        admission.addEdge(EdgeLabelUtils.computeEdgeLabel("patientId", null), patient)
    }

    private fun intEquals(property: String, value: Int) =
        TypedPatternPropertyAssignment(property, "==", TypedIntLiteralExpression(evalType = 0, value = value.toString()))

    private fun matchCount(vararg elements: TypedPatternElement): Int =
        MatchExecutor().executeMatchAll(TypedPattern(elements = elements.toList()), context, engine).size

    // =========================================================================
    // 1. One block versus two blocks
    // =========================================================================

    @Nested
    inner class BlockGrouping {

        /**
         * Two separate blocks reject the match when *either* of them matches — the
         * behaviour the proposal asks for.
         */
        @Test
        fun `separate blocks reject the match independently`() {
            val patient = addPatient(mandatory = false)
            val admission = addAdmission()
            admit(admission, patient)
            // no second Patient exists, so only the first block can match

            val count = matchCount(
                conditionNode("patient", "Patient"),
                forbidBlock(
                    conditionNode("existingAdmission", "Admission"),
                    conditionLink("existingAdmission", "patientId", "patient", null),
                    name = "alreadyAdmitted"
                ),
                forbidBlock(
                    conditionNode("betterPatient", "Patient"),
                    name = "betterCandidate"
                )
            )

            assertEquals(0, count, "The first block matches on its own, so the match is rejected")
        }

        /**
         * The same two elements inside *one* block only reject the match when both parts
         * match together. With no second patient, the conjunction does not hold and the
         * match survives — the case that could not be distinguished before.
         */
        @Test
        fun `one block only rejects the match when all of its parts match`() {
            val patient = addPatient(mandatory = false)
            val admission = addAdmission()
            admit(admission, patient)

            val count = matchCount(
                conditionNode("patient", "Patient"),
                forbidBlock(
                    conditionNode("existingAdmission", "Admission"),
                    conditionLink("existingAdmission", "patientId", "patient", null),
                    conditionNode("betterPatient", "Patient"),
                    name = "admittedAndBeaten"
                )
            )

            assertEquals(1, count, "Only one half of the block matches, so the condition does not hold")
        }

        /**
         * …and the very same single block does reject the match once both of its
         * components can be found at the same time.
         */
        @Test
        fun `one block rejects the match when all of its parts match together`() {
            val patient = addPatient(mandatory = false)
            val admission = addAdmission()
            admit(admission, patient)
            addPatient(mandatory = false)  // the second patient completes the block

            val count = matchCount(
                conditionNode("patient", "Patient"),
                forbidBlock(
                    conditionNode("existingAdmission", "Admission"),
                    conditionLink("existingAdmission", "patientId", "patient", null),
                    conditionNode("betterPatient", "Patient"),
                    name = "admittedAndBeaten"
                )
            )

            // the second patient is not admitted, so only it survives the condition
            assertEquals(1, count, "The admitted patient is rejected; the other one has no admission")
        }
    }

    // =========================================================================
    // 2. Blocks whose graph is not connected
    // =========================================================================

    @Nested
    inner class MultiComponentBlocks {

        @Test
        fun `block with two detached components holds only when both are found`() {
            addPatient(mandatory = true)
            // an Admission exists, but no second Patient for the second component

            val count = matchCount(
                conditionNode("patient", "Patient"),
                forbidBlock(
                    conditionNode("someAdmission", "Admission"),
                    conditionNode("otherPatient", "Patient")
                )
            )

            assertEquals(1, count, "No Admission exists, so the two-component block cannot hold")
        }

        @Test
        fun `block with two detached components rejects the match when both are found`() {
            val patient = addPatient(mandatory = true)
            addAdmission()
            addPatient(mandatory = false)

            val count = matchCount(
                conditionNode("patient", "Patient"),
                forbidBlock(
                    conditionNode("someAdmission", "Admission"),
                    conditionNode("otherPatient", "Patient")
                )
            )

            assertEquals(0, count, "Both components can be found, so every match is rejected")
            assertTrue(patient.property<Boolean>("isMandatory").isPresent)
        }

        /**
         * Injectivity holds across component boundaries: two condition nodes of the same
         * class must bind to different vertices even when nothing links them.
         */
        @Test
        fun `condition nodes of the same class stay distinct across components`() {
            addPatient(mandatory = true)   // the matched patient
            addPatient(mandatory = false)  // exactly one further patient

            val count = matchCount(
                conditionNode("patient", "Patient"),
                forbidBlock(
                    conditionNode("first", "Patient"),
                    conditionNode("second", "Patient")
                )
            )

            assertEquals(
                2, count,
                "The block needs three pairwise distinct patients; only two exist, so it never holds"
            )
        }

        @Test
        fun `require block with two detached components holds only when both are found`() {
            addPatient(mandatory = true)
            addPatient(mandatory = false)

            val withoutAdmission = matchCount(
                conditionNode("patient", "Patient"),
                requireBlock(
                    conditionNode("someAdmission", "Admission"),
                    conditionNode("otherPatient", "Patient")
                )
            )
            assertEquals(0, withoutAdmission, "No Admission exists, so the positive condition fails")

            addAdmission()
            val withAdmission = matchCount(
                conditionNode("patient", "Patient"),
                requireBlock(
                    conditionNode("someAdmission", "Admission"),
                    conditionNode("otherPatient", "Patient")
                )
            )
            assertEquals(2, withAdmission, "Both components can now be found for either patient")
        }
    }

    // =========================================================================
    // 3. References to nodes of the enclosing match
    // =========================================================================

    @Nested
    inner class AnchorReferences {

        @Test
        fun `block constrains a node of the enclosing match through a reference`() {
            addPatient(mandatory = true, duration = 30)
            addPatient(mandatory = true, duration = 60)

            val count = matchCount(
                conditionNode("patient", "Patient"),
                forbidBlock(
                    conditionReference("patient", listOf(intEquals("surgeryDuration", 60)))
                )
            )

            assertEquals(1, count, "Only the patient whose duration is not 60 survives the condition")
        }

        @Test
        fun `reference constraint combines with the rest of the block graph`() {
            val shortPatient = addPatient(mandatory = true, duration = 30)
            val longPatient = addPatient(mandatory = true, duration = 60)
            admit(addAdmission(), shortPatient)
            admit(addAdmission(), longPatient)

            val count = matchCount(
                conditionNode("patient", "Patient"),
                forbidBlock(
                    conditionReference("patient", listOf(intEquals("surgeryDuration", 60))),
                    conditionNode("admission", "Admission"),
                    conditionLink("admission", "patientId", "patient", null)
                )
            )

            assertEquals(
                1, count,
                "Only the long-surgery patient is both admitted and constrained away"
            )
        }
    }

    // =========================================================================
    // 4. Mixing negative and positive blocks
    // =========================================================================

    @Test
    fun `negative and positive blocks apply together`() {
        val admitted = addPatient(mandatory = true)
        admit(addAdmission(), admitted)
        addPatient(mandatory = false)

        val count = matchCount(
            conditionNode("patient", "Patient"),
            requireBlock(
                conditionNode("admission", "Admission"),
                conditionLink("admission", "patientId", "patient", null),
                name = "isAdmitted"
            ),
            forbidBlock(
                conditionNode("other", "Patient"),
                conditionNode("otherAdmission", "Admission"),
                conditionLink("otherAdmission", "patientId", "other", null),
                name = "noOtherAdmittedPatient"
            )
        )

        assertEquals(1, count, "The admitted patient passes; no *other* admitted patient exists")
    }

    // =========================================================================
    // 5. Rejection of the removed element-level modifiers
    // =========================================================================

    @Nested
    inner class LegacyModifiers {

        @Test
        fun `an instance carrying the removed forbid modifier is rejected`() {
            val legacy = com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstanceElement(
                objectInstance = com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstance(
                    modifier = "forbid", name = "old", className = "Patient", properties = emptyList()
                )
            )

            val error = assertFailsWith<IllegalArgumentException> {
                matchCount(conditionNode("patient", "Patient"), legacy)
            }
            assertTrue(
                error.message!!.contains("forbid { ... }"),
                "The error should point at the block syntax, was: ${error.message}"
            )
        }

        @Test
        fun `a link carrying the removed require modifier is rejected`() {
            val legacy = com.mdeo.modeltransformation.ast.patterns.TypedPatternLinkElement(
                link = com.mdeo.modeltransformation.ast.patterns.TypedPatternLink(
                    modifier = "require",
                    source = com.mdeo.modeltransformation.ast.patterns.TypedPatternLinkEnd("admission", "patientId"),
                    target = com.mdeo.modeltransformation.ast.patterns.TypedPatternLinkEnd("patient", null)
                )
            )

            assertFailsWith<IllegalArgumentException> {
                matchCount(
                    conditionNode("patient", "Patient"),
                    conditionNode("admission", "Admission"),
                    legacy
                )
            }
        }
    }
}
