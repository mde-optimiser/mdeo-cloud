package com.mdeo.optimizer.rulegen

import com.mdeo.expression.ast.expressions.TypedBinaryExpression
import com.mdeo.expression.ast.expressions.TypedExpression
import com.mdeo.expression.ast.expressions.TypedIdentifierExpression
import com.mdeo.expression.ast.expressions.TypedMemberAccessExpression
import com.mdeo.expression.ast.expressions.TypedMemberCallExpression
import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.modeltransformation.ast.TypedAst
import com.mdeo.modeltransformation.ast.patterns.TypedPattern
import com.mdeo.modeltransformation.ast.patterns.TypedPatternApplicationConditionElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLinkElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstanceElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternWhereClauseElement
import com.mdeo.modeltransformation.ast.statements.TypedForMatchStatement
import com.mdeo.modeltransformation.ast.statements.TypedIfMatchStatement
import com.mdeo.modeltransformation.ast.statements.TypedMatchStatement
import com.mdeo.modeltransformation.ast.statements.TypedTransformationStatement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Generated operators are also shown, and can be saved, as ordinary `.mt` transformations, so they
 * must be valid ones. This checks the rules of the language the engine itself does not enforce,
 * for every operator of MDEOptimiser's example metamodels and every pair of end multiplicities:
 *
 * - an end declared with an upper bound of 1 is one optional object, which has no `size()`;
 * - a `forbid` block may not refer to an object its own pattern deletes.
 *
 * (Rendering every generated operator as text and running the language's validator on it, on a
 * running platform, found these two; it found no other problem.)
 */
class GeneratedOperatorLanguageTest {

    private val bounds = listOf("*", "?", "+", "1", "2..3", "2..*", "1..2", "2")

    @Test
    fun `generated operators follow the rules of the transformation language`() {
        val metamodels = ExampleMetamodels.all.values + bounds.flatMap { e ->
            bounds.map { f ->
                mm("Context", "A", "B") {
                    assoc("Context.as[*]", "*-->", "A.ctx[?]")
                    assoc("Context.bs[*]", "*-->", "B.ctx[?]")
                    assoc("A.e[$e]", "<-->", "B.f[$f]")
                }
            }
        }
        val problems = metamodels.flatMap { metamodel ->
            val info = MetamodelInfo(metamodel)
            MutationRuleGenerator.generate(metamodel, metamodel.classes.map { spec("mutate ${it.name}") }).mutations
                .flatMap { mutation -> problems(mutation.typedAst, info).map { "${mutation.name}: $it" } }
        }
        assertEquals(emptyList<String>(), problems.distinct().take(20))
    }

    private fun problems(ast: TypedAst, info: MetamodelInfo): List<String> {
        val found = mutableListOf<String>()

        fun expression(e: TypedExpression) {
            when (e) {
                is TypedBinaryExpression -> { expression(e.left); expression(e.right) }
                is TypedMemberCallExpression -> {
                    val access = e.expression
                    if (e.member == "size" && access is TypedMemberAccessExpression) {
                        val holder = access.expression as? TypedIdentifierExpression
                        val type = holder?.let { ast.types[it.evalType] as? ClassTypeRef }
                        val end = type?.let { info.endOf(it.type, access.member) }
                        if (end != null && end.isSingle) found += "${holder.name}.${access.member}.size() on a single-valued end"
                    }
                    expression(e.expression)
                }
                is TypedMemberAccessExpression -> expression(e.expression)
                else -> {}
            }
        }

        fun names(elements: List<TypedPatternElement>): Set<String> = elements.flatMap { element ->
            when (element) {
                is TypedPatternObjectInstanceElement -> listOf(element.objectInstance.name)
                is TypedPatternLinkElement -> listOf(element.link.source.objectName, element.link.target.objectName)
                else -> emptyList()
            }
        }.toSet()

        fun pattern(p: TypedPattern) {
            val deleted = p.elements.filterIsInstance<TypedPatternObjectInstanceElement>()
                .filter { it.objectInstance.modifier == "delete" }.map { it.objectInstance.name }.toSet()
            for (element in p.elements) {
                when (element) {
                    is TypedPatternApplicationConditionElement -> {
                        (names(element.condition.elements) intersect deleted).forEach { found += "forbid block refers to deleted '$it'" }
                        element.condition.elements.filterIsInstance<TypedPatternWhereClauseElement>().forEach { expression(it.whereClause.expression) }
                    }
                    is TypedPatternWhereClauseElement -> expression(element.whereClause.expression)
                    else -> {}
                }
            }
        }

        fun statements(list: List<TypedTransformationStatement>) {
            for (s in list) when (s) {
                is TypedMatchStatement -> pattern(s.pattern)
                is TypedForMatchStatement -> { pattern(s.pattern); statements(s.doBlock) }
                is TypedIfMatchStatement -> { pattern(s.pattern); statements(s.thenBlock); statements(s.elseBlock.orEmpty()) }
                else -> {}
            }
        }
        statements(ast.statements)
        return found
    }
}
