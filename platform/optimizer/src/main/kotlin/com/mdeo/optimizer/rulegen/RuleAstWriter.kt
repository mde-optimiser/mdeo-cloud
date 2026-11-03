package com.mdeo.optimizer.rulegen

import com.mdeo.expression.ast.expressions.TypedBinaryExpression
import com.mdeo.expression.ast.expressions.TypedBooleanLiteralExpression
import com.mdeo.expression.ast.expressions.TypedDoubleLiteralExpression
import com.mdeo.expression.ast.expressions.TypedExpression
import com.mdeo.expression.ast.expressions.TypedFloatLiteralExpression
import com.mdeo.expression.ast.expressions.TypedIdentifierExpression
import com.mdeo.expression.ast.expressions.TypedIntLiteralExpression
import com.mdeo.expression.ast.expressions.TypedLongLiteralExpression
import com.mdeo.expression.ast.expressions.TypedMemberAccessExpression
import com.mdeo.expression.ast.expressions.TypedMemberCallExpression
import com.mdeo.expression.ast.expressions.TypedNullLiteralExpression
import com.mdeo.expression.ast.expressions.TypedStringLiteralExpression
import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.expression.ast.types.ReturnType
import com.mdeo.expression.ast.types.VoidType
import com.mdeo.metamodel.data.PropertyData
import com.mdeo.modeltransformation.ast.patterns.TypedPatternApplicationCondition
import com.mdeo.modeltransformation.ast.patterns.TypedPatternApplicationConditionElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLink
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLinkElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLinkEnd
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstance
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstanceElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternPropertyAssignment
import com.mdeo.modeltransformation.ast.patterns.TypedPatternWhereClauseElement
import com.mdeo.modeltransformation.ast.patterns.TypedWhereClause

/**
 * Builds the pieces of one generated transformation and keeps the types table its expressions
 * index into.
 *
 * Expressions name objects together with the scope that binds them: the transformation's top level
 * is scope 1, a `for match` or `if match` pattern one level below the statement holding it plus
 * one, and the objects a `forbid` block declares one level below its pattern.
 */
class RuleAstWriter(private val info: MetamodelInfo) {

    private val types: MutableList<ReturnType> = mutableListOf(
        VoidType(),
        builtin("string"),
        builtin("double"),
        builtin("boolean"),
        ClassTypeRef(`package` = "builtin", type = "Any", isNullable = true),
        builtin("int")
    )

    /** A snapshot of the types table. */
    fun types(): List<ReturnType> = types.toList()

    private fun builtin(type: String) = ClassTypeRef(`package` = "builtin", type = type, isNullable = false)

    private fun typeIndex(type: ClassTypeRef): Int {
        val existing = types.indexOf(type)
        if (existing != -1) return existing
        types += type
        return types.size - 1
    }

    private fun classType(className: String): Int =
        typeIndex(ClassTypeRef(`package` = "class${info.pathOf(className)}", type = className, isNullable = false))

    private fun listType(className: String): Int {
        val element = types[classType(className)] as ClassTypeRef
        return typeIndex(ClassTypeRef(`package` = "builtin", type = "List", isNullable = false, typeArgs = mapOf("T" to element)))
    }

    private val booleanType get() = typeIndex(builtin("boolean"))
    private val intType get() = typeIndex(builtin("int"))

    // -------------------------------------------------------------------------
    // Pattern elements
    // -------------------------------------------------------------------------

    /** An object of [className] that is matched, or created or deleted with [modifier]. */
    fun obj(name: String, className: String, modifier: String? = null, properties: List<TypedPatternPropertyAssignment> = emptyList()) =
        TypedPatternObjectInstanceElement(objectInstance = TypedPatternObjectInstance(modifier = modifier, name = name, className = className, properties = properties))

    /** Refers to the object [name] bound by an enclosing statement. */
    fun reference(name: String) =
        TypedPatternObjectInstanceElement(objectInstance = TypedPatternObjectInstance(name = name, properties = emptyList()))

    /** Deletes the object [name] bound by an earlier statement. */
    fun deleteBound(name: String) =
        TypedPatternObjectInstanceElement(objectInstance = TypedPatternObjectInstance(modifier = "delete", name = name, className = null, properties = emptyList()))

    /**
     * A link of [end]'s association between [owner], an object holding [end], and [target],
     * written in the association's own direction as the engine expects.
     */
    fun link(end: AssociationEnd, owner: String, target: String, modifier: String? = null): TypedPatternLinkElement {
        val ownerEnd = TypedPatternLinkEnd(objectName = owner, propertyName = end.name)
        val targetEnd = TypedPatternLinkEnd(objectName = target, propertyName = end.opposite.name)
        return TypedPatternLinkElement(
            link = if (end.isSourceSide) TypedPatternLink(modifier = modifier, source = ownerEnd, target = targetEnd)
            else TypedPatternLink(modifier = modifier, source = targetEnd, target = ownerEnd)
        )
    }

    /**
     * A block rejecting the match when all of [elements] can be found together. Objects its links
     * name but it does not declare are anchors bound outside the block, and get a reference
     * element, as the language's compiler emits them.
     */
    fun forbid(name: String, elements: List<TypedPatternElement>): TypedPatternApplicationConditionElement {
        val declared = elements.filterIsInstance<TypedPatternObjectInstanceElement>().map { it.objectInstance.name }.toSet()
        val anchors = elements.filterIsInstance<TypedPatternLinkElement>()
            .flatMap { listOf(it.link.source.objectName, it.link.target.objectName) }
            .filter { it !in declared }
            .distinct()
            .map { reference(it) }
        return TypedPatternApplicationConditionElement(
            condition = TypedPatternApplicationCondition(negative = true, name = name, elements = anchors + elements)
        )
    }

    /** `where [expression]`. */
    fun where(expression: TypedExpression) = TypedPatternWhereClauseElement(whereClause = TypedWhereClause(expression = expression))

    /**
     * `[obj].[end].size() [operator] [bound]` for an object of [className] bound in [scope].
     *
     * The language types an end declared with an upper bound of 1 as one optional object, which
     * has no `size()`: its count is 0 or 1, so the comparison becomes `== null`, `!= null` or a
     * constant.
     */
    fun size(obj: String, className: String, scope: Int, end: AssociationEnd, operator: String, bound: Int): TypedExpression {
        if (end.isSingle) {
            val holds = listOf(0, 1).filter { count ->
                when (operator) {
                    "<" -> count < bound
                    "<=" -> count <= bound
                    ">" -> count > bound
                    ">=" -> count >= bound
                    else -> error("unsupported operator $operator")
                }
            }
            if (holds.size != 1) return TypedBooleanLiteralExpression(evalType = booleanType, value = holds.isNotEmpty())
            return TypedBinaryExpression(
                evalType = booleanType,
                operator = if (holds.single() == 0) "==" else "!=",
                left = TypedMemberAccessExpression(
                    evalType = typeIndex(ClassTypeRef(`package` = "class${info.pathOf(end.target)}", type = end.target, isNullable = true)),
                    expression = TypedIdentifierExpression(evalType = classType(className), name = obj, scope = scope),
                    member = end.name!!,
                    isNullChaining = false
                ),
                right = TypedNullLiteralExpression(evalType = typeIndex(ClassTypeRef(`package` = "builtin", type = "Any", isNullable = true)))
            )
        }
        return TypedBinaryExpression(
            evalType = booleanType,
            operator = operator,
            left = TypedMemberCallExpression(
                evalType = intType,
                expression = TypedMemberAccessExpression(
                    evalType = listType(end.target),
                    expression = TypedIdentifierExpression(evalType = classType(className), name = obj, scope = scope),
                    member = end.name!!,
                    isNullChaining = false
                ),
                member = "size",
                isNullChaining = false,
                overload = "",
                arguments = emptyList()
            ),
            right = TypedIntLiteralExpression(evalType = intType, value = bound.toString())
        )
    }

    /** `[a] != [b]` for two objects with their classes and binding scopes. */
    fun distinct(a: String, aClass: String, aScope: Int, b: String, bClass: String, bScope: Int): TypedExpression =
        TypedBinaryExpression(
            evalType = booleanType,
            operator = "!=",
            left = TypedIdentifierExpression(evalType = classType(aClass), name = a, scope = aScope),
            right = TypedIdentifierExpression(evalType = classType(bClass), name = b, scope = bScope)
        )

    // -------------------------------------------------------------------------
    // Attribute defaults
    // -------------------------------------------------------------------------

    /**
     * Assignments giving each required single-valued attribute of a new [className] object a
     * default: `""`, zero, `false` or the enum's first entry. Required multi-valued attributes
     * cannot be defaulted and are returned in [skipped].
     */
    fun defaultAttributes(className: String, skipped: MutableList<PropertyData>): List<TypedPatternPropertyAssignment> =
        info.propertiesOf(className).filter { it.multiplicity.lower > 0 }.mapNotNull { property ->
            if (property.multiplicity.upper != 1) {
                skipped += property
                return@mapNotNull null
            }
            val value = defaultValue(property) ?: run { skipped += property; return@mapNotNull null }
            TypedPatternPropertyAssignment(propertyName = property.name, operator = "=", value = value)
        }

    private fun defaultValue(property: PropertyData): TypedExpression? {
        val enumType = property.enumType
        if (enumType != null) {
            val entry = info.enumEntries(enumType).firstOrNull() ?: return null
            val path = info.enumPathOf(enumType)
            return TypedMemberAccessExpression(
                evalType = typeIndex(ClassTypeRef(`package` = "enum$path", type = enumType, isNullable = false)),
                expression = TypedIdentifierExpression(
                    evalType = typeIndex(ClassTypeRef(`package` = "enum-container$path", type = enumType, isNullable = false)),
                    name = enumType,
                    scope = 1
                ),
                member = entry,
                isNullChaining = false
            )
        }
        return when (property.primitiveType?.lowercase()) {
            "string" -> TypedStringLiteralExpression(evalType = typeIndex(builtin("string")), value = "")
            "int" -> TypedIntLiteralExpression(evalType = intType, value = "0")
            "long" -> TypedLongLiteralExpression(evalType = typeIndex(builtin("long")), value = "0")
            "float" -> TypedFloatLiteralExpression(evalType = typeIndex(builtin("float")), value = "0.0")
            "double" -> TypedDoubleLiteralExpression(evalType = typeIndex(builtin("double")), value = "0.0")
            "boolean" -> TypedBooleanLiteralExpression(evalType = booleanType, value = false)
            else -> null
        }
    }
}
