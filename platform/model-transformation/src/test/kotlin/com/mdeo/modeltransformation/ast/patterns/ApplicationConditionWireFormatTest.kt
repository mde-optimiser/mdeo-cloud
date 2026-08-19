package com.mdeo.modeltransformation.ast.patterns

import com.mdeo.expression.ast.expressions.TypedBinaryExpression
import com.mdeo.expression.ast.expressions.TypedExpression
import com.mdeo.expression.ast.expressions.TypedIdentifierExpression
import com.mdeo.expression.ast.expressions.TypedMemberAccessExpression
import com.mdeo.modeltransformation.ast.expressions.TypedExpressionSerializer
import com.mdeo.modeltransformation.ast.TypedAst
import com.mdeo.modeltransformation.ast.statements.TypedMatchStatement
import com.mdeo.modeltransformation.ast.statements.TypedTransformationStatement
import com.mdeo.modeltransformation.ast.statements.TypedTransformationStatementSerializer
import com.mdeo.modeltransformation.runtime.match.ApplicationConditionBlock
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies that the JSON the language front-end produces for application condition blocks is
 * exactly what this module deserialises.
 *
 * The resource is the unmodified typed AST the converter emitted for the example from the
 * syntax proposal:
 *
 * ```
 * match {
 *     patient: Patient { isMandatory == false }
 *     var patientDuration = patient.surgeryDuration
 *     forbid alreadyAdmitted { existingAdmission: Admission {}  existingAdmission.patient -- patient }
 *     forbid betterCandidate { betterPatient: Patient { isMandatory == false }
 *                              where betterPatient.surgeryDuration < patientDuration }
 *     create admission: Admission { day = 1 }
 *     create admission.patient -- patient
 * }
 * ```
 *
 * Regenerating it after a change to the front-end is the point: a silent divergence between
 * the two sides would otherwise only show up as a failing transformation at runtime.
 */
class ApplicationConditionWireFormatTest {

    private val json = Json {
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            contextual(TypedExpression::class, TypedExpressionSerializer)
            contextual(TypedTransformationStatement::class, TypedTransformationStatementSerializer)
            contextual(TypedPatternElement::class, TypedPatternElementSerializer)
        }
    }

    private fun loadPattern(): TypedPattern {
        val resource = checkNotNull(javaClass.classLoader.getResourceAsStream("applicationConditionTypedAst.json")) {
            "Missing test resource applicationConditionTypedAst.json"
        }
        val ast = json.decodeFromString(TypedAst.serializer(), resource.bufferedReader().readText())
        return (ast.statements.single() as TypedMatchStatement).pattern
    }

    @Test
    fun `the front-end emits both blocks as separate application conditions`() {
        val conditions = loadPattern().elements.filterIsInstance<TypedPatternApplicationConditionElement>()

        assertEquals(2, conditions.size, "Two forbid blocks must stay two conditions")
        assertEquals(
            listOf("alreadyAdmitted", "betterCandidate"),
            conditions.map { it.condition.name },
            "Block names have to survive the conversion"
        )
        assertTrue(conditions.all { it.condition.negative })
    }

    @Test
    fun `the emitted blocks build the condition graphs the engine expects`() {
        val conditions = loadPattern().elements.filterIsInstance<TypedPatternApplicationConditionElement>()
        val blocks = conditions.map { ApplicationConditionBlock.from(it) }

        val admitted = blocks.single { it.name == "alreadyAdmitted" }
        assertEquals(setOf("existingAdmission"), admitted.instanceNames)
        assertEquals(1, admitted.links.size, "The link anchoring the block to the match is kept")
        assertTrue(admitted.references.isEmpty())

        val better = blocks.single { it.name == "betterCandidate" }
        assertEquals(setOf("betterPatient"), better.instanceNames)
        assertTrue(better.links.isEmpty(), "This block is detached from the match")
        assertEquals(
            listOf("isMandatory"),
            better.instances.single().objectInstance.properties.map { it.propertyName },
            "The property constraint on the block's node is carried over"
        )
        assertEquals(
            1, better.whereClauses.size,
            "The clause of the block belongs to the block, not to the enclosing match"
        )
    }

    @Test
    fun `a where clause inside a block stays inside it`() {
        val pattern = loadPattern()

        assertTrue(
            pattern.elements.none { it is TypedPatternWhereClauseElement },
            "The clause must not be lifted out of its block into the match"
        )

        val betterCandidate = pattern.elements
            .filterIsInstance<TypedPatternApplicationConditionElement>()
            .single { it.condition.name == "betterCandidate" }
        val clause = betterCandidate.condition.elements.filterIsInstance<TypedPatternWhereClauseElement>().single()
        val comparison = clause.whereClause.expression as TypedBinaryExpression

        assertEquals("<", comparison.operator)
        val left = comparison.left as TypedMemberAccessExpression
        assertEquals("surgeryDuration", left.member)
        assertEquals(
            "betterPatient", (left.expression as TypedIdentifierExpression).name,
            "The clause reads the block's own node"
        )
        assertEquals(
            "patientDuration", (comparison.right as TypedIdentifierExpression).name,
            "…and compares it against a variable of the match"
        )
        assertEquals(
            (left.expression as TypedIdentifierExpression).scope,
            (comparison.right as TypedIdentifierExpression).scope,
            "A block is not a scope of its own at run time: both names live in the match's scope"
        )
    }

    @Test
    fun `elements outside the blocks keep their own modifiers`() {
        val instances = loadPattern().elements.filterIsInstance<TypedPatternObjectInstanceElement>()

        val patient = instances.single { it.objectInstance.name == "patient" }
        assertEquals(null, patient.objectInstance.modifier)
        val admission = instances.single { it.objectInstance.name == "admission" }
        assertEquals("create", admission.objectInstance.modifier)
        assertFalse(
            instances.any { it.objectInstance.modifier == "forbid" || it.objectInstance.modifier == "require" },
            "Application conditions must never come back as element modifiers"
        )
    }
}
