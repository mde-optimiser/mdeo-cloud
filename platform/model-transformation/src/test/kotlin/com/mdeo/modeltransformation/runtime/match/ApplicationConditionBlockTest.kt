package com.mdeo.modeltransformation.runtime.match

import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.modeltransformation.ast.patterns.TypedPattern
import com.mdeo.modeltransformation.ast.patterns.TypedPatternWhereClauseElement
import com.mdeo.modeltransformation.ast.patterns.TypedWhereClause
import com.mdeo.expression.ast.expressions.TypedBooleanLiteralExpression
import com.mdeo.expression.ast.expressions.TypedExpression
import com.mdeo.expression.ast.expressions.TypedIdentifierExpression
import com.mdeo.modeltransformation.ast.patterns.TypedPatternVariable
import com.mdeo.modeltransformation.ast.patterns.TypedPatternPropertyAssignment
import com.mdeo.modeltransformation.ast.patterns.TypedPatternVariableElement
import com.mdeo.modeltransformation.runtime.match.plan.BaseStep
import com.mdeo.modeltransformation.runtime.match.plan.MatchPlanBuilder
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Builds a where-clause element around [expression].
 *
 * @param expression The constraint expression.
 * @return The where-clause element.
 */
private fun whereClause(expression: TypedExpression) =
    TypedPatternWhereClauseElement(whereClause = TypedWhereClause(expression))

/**
 * Builds an identifier expression reading [name] at the outermost scope.
 *
 * @param name The name the expression reads.
 * @return The identifier expression.
 */
private fun identifier(name: String) =
    TypedIdentifierExpression(evalType = 0, name = name, scope = 0)

/**
 * Unit tests for [ApplicationConditionBlock] and for the way the planner turns blocks into
 * [BaseStep.ApplicationCondition] steps.
 *
 * The central property: one block becomes exactly one step, whatever its graph looks like.
 * Nothing is regrouped, merged or split along the way.
 */
class ApplicationConditionBlockTest {

    @Nested
    inner class BlockConstruction {

        @Test
        fun `instances with a class name become condition nodes, those without become references`() {
            val block = ApplicationConditionBlock.from(
                forbidBlock(
                    conditionNode("other", "Patient"),
                    conditionReference("patient"),
                    conditionLink("other", "ref", "patient", null),
                    name = "noOther"
                )
            )

            assertEquals("noOther", block.name)
            assertTrue(block.isNegative)
            assertEquals(listOf("other"), block.instances.map { it.objectInstance.name })
            assertEquals(listOf("patient"), block.references.map { it.objectInstance.name })
            assertEquals(1, block.links.size)
            assertEquals(setOf("other"), block.instanceNames)
        }

        @Test
        fun `a require block is not negative and may stay unnamed`() {
            val block = ApplicationConditionBlock.from(requireBlock(conditionNode("any", "Patient")))

            assertFalse(block.isNegative)
            assertNull(block.name)
            assertEquals("require", block.label)
        }

        @Test
        fun `an unnamed forbid block reports the block kind as its label`() {
            val block = ApplicationConditionBlock.from(forbidBlock(conditionNode("any", "Patient")))

            assertEquals("forbid", block.label)
        }

        @Test
        fun `where clauses are kept apart from the graph`() {
            val block = ApplicationConditionBlock.from(
                forbidBlock(
                    conditionNode("other", "Patient"),
                    whereClause(TypedBooleanLiteralExpression(evalType = 0, value = true)),
                    name = "constrained"
                )
            )

            assertEquals(listOf("other"), block.instances.map { it.objectInstance.name })
            assertEquals(1, block.whereClauses.size, "The clause constrains the graph, it is not part of it")
        }

        @Test
        fun `elements that are not part of a condition are rejected`() {
            val invalid = forbidBlock(
                TypedPatternVariableElement(
                    variable = TypedPatternVariable(
                        name = "x",
                        value = TypedBooleanLiteralExpression(evalType = 0, value = true)
                    )
                )
            )

            assertFailsWith<IllegalArgumentException> { ApplicationConditionBlock.from(invalid) }
        }
    }

    @Nested
    inner class PlanConstruction {

        private fun plan(vararg elements: com.mdeo.modeltransformation.ast.patterns.TypedPatternElement) =
            MatchPlanBuilder(
                getVertexId = { null },
                nodeAnalyzer = ExpressionNodeAnalyzer(setOf("patient"), 0),
                isCollectionExpression = { false },
                metamodelData = MetamodelData.empty()
            ).build(PatternCategories.from(TypedPattern(elements = elements.toList())), emptySet())

        @Test
        fun `each block yields exactly one application condition step`() {
            val steps = plan(
                conditionNode("patient", "Patient"),
                forbidBlock(conditionNode("a", "Admission"), name = "first"),
                forbidBlock(conditionNode("b", "Admission"), name = "second")
            ).baseSteps.filterIsInstance<BaseStep.ApplicationCondition>()

            assertEquals(2, steps.size)
            assertEquals(setOf("first", "second"), steps.mapNotNull { it.name }.toSet())
            assertTrue(steps.all { it.isNegative })
        }

        @Test
        fun `a block whose graph has several components stays a single step`() {
            val steps = plan(
                conditionNode("patient", "Patient"),
                forbidBlock(
                    conditionNode("a", "Admission"),
                    conditionNode("b", "Admission"),
                    name = "both"
                )
            ).baseSteps.filterIsInstance<BaseStep.ApplicationCondition>()

            assertEquals(1, steps.size, "One block must not be split into several conditions")
            assertEquals(
                2, steps[0].innerSteps.count { it is BaseStep.VertexScan },
                "Both components are scanned inside the same condition traversal"
            )
        }

        @Test
        fun `components sharing an anchor stay one walk`() {
            val steps = plan(
                conditionNode("patient", "Patient"),
                forbidBlock(
                    conditionNode("a", "Admission"),
                    conditionLink("a", "patientId", "patient", null),
                    conditionNode("b", "Admission"),
                    conditionLink("b", "otherId", "patient", null),
                    name = "twoAdmissions"
                )
            ).baseSteps.filterIsInstance<BaseStep.ApplicationCondition>()

            assertEquals(1, steps.size)
            assertEquals(
                0, steps[0].innerSteps.count { it is BaseStep.SelectNode },
                "Both links hang off the same anchor, so one BFS walk covers them"
            )
            assertEquals(2, steps[0].innerSteps.count { it is BaseStep.EdgeWalk })
        }

        @Test
        fun `a component anchored elsewhere is entered through a select step`() {
            val steps = MatchPlanBuilder(
                getVertexId = { null },
                nodeAnalyzer = ExpressionNodeAnalyzer(setOf("patient", "ward"), 0),
                isCollectionExpression = { false },
                metamodelData = MetamodelData.empty()
            ).build(
                PatternCategories.from(
                    TypedPattern(
                        elements = listOf(
                            conditionNode("patient", "Patient"),
                            conditionNode("ward", "Ward"),
                            forbidBlock(
                                conditionNode("a", "Admission"),
                                conditionLink("a", "patientId", "patient", null),
                                conditionNode("b", "Bed"),
                                conditionLink("b", "wardId", "ward", null),
                                name = "admittedAndBedded"
                            )
                        )
                    )
                ),
                emptySet()
            ).baseSteps.filterIsInstance<BaseStep.ApplicationCondition>()

            assertEquals(1, steps.size)
            assertEquals(
                1, steps[0].innerSteps.count { it is BaseStep.SelectNode },
                "The second component starts by jumping to its own anchor"
            )
        }

        @Test
        fun `a clause of a block is planned inside the condition, not next to it`() {
            val steps = MatchPlanBuilder(
                getVertexId = { null },
                nodeAnalyzer = ExpressionNodeAnalyzer(setOf("patient", "a"), 0),
                isCollectionExpression = { false },
                metamodelData = MetamodelData.empty()
            ).build(
                PatternCategories.from(
                    TypedPattern(
                        elements = listOf(
                            conditionNode("patient", "Patient"),
                            forbidBlock(
                                conditionNode("a", "Admission"),
                                conditionLink("a", "patientId", "patient", null),
                                whereClause(identifier("a")),
                                name = "constrained"
                            )
                        )
                    )
                ),
                emptySet()
            ).baseSteps

            assertTrue(
                steps.none { it is BaseStep.WhereFilter },
                "A clause of a block must not become a filter on the match"
            )
            val condition = steps.filterIsInstance<BaseStep.ApplicationCondition>().single()
            val filter = condition.innerSteps.filterIsInstance<BaseStep.WhereFilter>().single()
            assertEquals(
                setOf("a"), filter.conditionNodes,
                "The node the clause reads is recorded so the chain labels it"
            )
            val walkIndex = condition.innerSteps.indexOfFirst { it is BaseStep.EdgeWalk }
            assertTrue(
                condition.innerSteps.indexOf(filter) > walkIndex,
                "The clause is only evaluated once the node it reads has been walked to"
            )
        }

        @Test
        fun `a property constraint of a block records the block nodes it reads`() {
            val steps = MatchPlanBuilder(
                getVertexId = { null },
                nodeAnalyzer = ExpressionNodeAnalyzer(setOf("patient", "a", "b"), 0),
                isCollectionExpression = { false },
                metamodelData = MetamodelData.empty()
            ).build(
                PatternCategories.from(
                    TypedPattern(
                        elements = listOf(
                            conditionNode("patient", "Patient"),
                            forbidBlock(
                                conditionNode("a", "Admission"),
                                conditionNode(
                                    "b", "Admission",
                                    listOf(TypedPatternPropertyAssignment("day", "==", identifier("a")))
                                ),
                                name = "twoAdmissionsOnOneDay"
                            )
                        )
                    )
                ),
                emptySet()
            ).baseSteps

            val condition = steps.filterIsInstance<BaseStep.ApplicationCondition>().single()
            val constraint = condition.innerSteps.filterIsInstance<BaseStep.InlinePropertyConstraint>().single()
            assertEquals(
                setOf("a"), constraint.conditionNodes,
                "The block node the comparison reads is recorded so the chain labels it"
            )
        }

        @Test
        fun `a condition is emitted after the variable its clause reads`() {
            val steps = MatchPlanBuilder(
                getVertexId = { null },
                nodeAnalyzer = ExpressionNodeAnalyzer(setOf("patient", "limit"), 0),
                isCollectionExpression = { false },
                metamodelData = MetamodelData.empty()
            ).build(
                PatternCategories.from(
                    TypedPattern(
                        elements = listOf(
                            conditionNode("patient", "Patient"),
                            TypedPatternVariableElement(
                                variable = TypedPatternVariable(
                                    name = "limit",
                                    value = TypedBooleanLiteralExpression(evalType = 0, value = true)
                                )
                            ),
                            forbidBlock(
                                conditionNode("a", "Admission"),
                                whereClause(identifier("limit")),
                                name = "readsVariable"
                            )
                        )
                    )
                ),
                emptySet()
            ).baseSteps

            val variableIndex = steps.indexOfFirst { it is BaseStep.VariableBinding }
            val conditionIndex = steps.indexOfFirst { it is BaseStep.ApplicationCondition }
            assertTrue(variableIndex >= 0, "The variable is bound in the plan")
            assertTrue(
                variableIndex < conditionIndex,
                "The condition reads the variable, so it cannot be checked before it is bound"
            )
        }

        @Test
        fun `a positive block is planned as a non-negative condition`() {
            val steps = plan(
                conditionNode("patient", "Patient"),
                requireBlock(conditionNode("a", "Admission"), name = "admitted")
            ).baseSteps.filterIsInstance<BaseStep.ApplicationCondition>()

            assertEquals(1, steps.size)
            assertFalse(steps[0].isNegative)
            assertEquals("admitted", steps[0].name)
        }
    }
}
