package com.mdeo.optimizer.rulegen

import com.mdeo.expression.ast.expressions.TypedBinaryExpression
import com.mdeo.expression.ast.expressions.TypedIdentifierExpression
import com.mdeo.expression.ast.expressions.TypedIntLiteralExpression
import com.mdeo.expression.ast.expressions.TypedMemberAccessExpression
import com.mdeo.expression.ast.expressions.TypedMemberCallExpression
import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.expression.ast.types.ReturnType
import com.mdeo.expression.ast.types.VoidType
import com.mdeo.modeltransformation.ast.patterns.TypedPatternWhereClauseElement
import com.mdeo.modeltransformation.ast.patterns.TypedWhereClause

/**
 * Builds multiplicity-guard `where` clause elements and manages the shared types array for a
 * single [MutationAstBuilder.build] invocation.
 *
 * The types array is pre-populated with six built-in entries at fixed indices:
 * ```
 * 0: VoidType
 * 1: ClassTypeRef("string")
 * 2: ClassTypeRef("double")
 * 3: ClassTypeRef("boolean")   ← BOOLEAN_INDEX
 * 4: ClassTypeRef("Any", nullable=true)
 * 5: ClassTypeRef("int")        ← INT_INDEX
 * ```
 * Additional class and list types are appended on demand via [getOrAddClassType] and
 * [getOrAddListType] and their array index is returned for use in AST `evalType` fields.
 *
 * @param metamodelPath The metamodel path used as the `package` prefix for class types,
 *                      formatted as `"class$metamodelPath"`.
 */
class MultiplicityGuardBuilder(private val metamodelPath: String) {

    companion object {
        /** Index of the `boolean` type in the types array. */
        const val BOOLEAN_INDEX = 3

        /** Index of the `int` type in the types array. */
        const val INT_INDEX = 5
    }

    private val types: MutableList<ReturnType> = mutableListOf(
        VoidType(),
        ClassTypeRef(`package` = "builtin", type = "string",  isNullable = false),
        ClassTypeRef(`package` = "builtin", type = "double",  isNullable = false),
        ClassTypeRef(`package` = "builtin", type = "boolean", isNullable = false),
        ClassTypeRef(`package` = "builtin", type = "Any",     isNullable = true),
        ClassTypeRef(`package` = "builtin", type = "int",     isNullable = false)
    )

    // -------------------------------------------------------------------------
    // Type registry
    // -------------------------------------------------------------------------

    /**
     * Returns the index of a class type for [className] in the types array, registering a new
     * [ClassTypeRef] if not already present.
     */
    fun getOrAddClassType(className: String): Int {
        val pkg = "class$metamodelPath"
        val existing = types.indexOfFirst {
            it is ClassTypeRef && it.type == className && it.`package` == pkg
        }
        if (existing != -1) return existing
        types.add(ClassTypeRef(`package` = pkg, type = className, isNullable = false))
        return types.size - 1
    }

    /**
     * Returns the index of a `List<elementClassName>` type in the types array, registering new
     * entries (for both the element class and the List type) if not already present.
     */
    fun getOrAddListType(elementClassName: String): Int {
        val elementIdx = getOrAddClassType(elementClassName)
        val elementTypeRef = types[elementIdx] as ClassTypeRef
        val existing = types.indexOfFirst {
            it is ClassTypeRef &&
                it.type == "List" &&
                it.`package` == "builtin" &&
                it.typeArgs?.get("T") == elementTypeRef
        }
        if (existing != -1) return existing
        types.add(
            ClassTypeRef(
                `package` = "builtin",
                type = "List",
                isNullable = false,
                typeArgs = mapOf("T" to elementTypeRef)
            )
        )
        return types.size - 1
    }

    /**
     * Returns a snapshot of the current types array (immutable copy).
     */
    fun getTypes(): List<ReturnType> = types.toList()

    // -------------------------------------------------------------------------
    // Guard builders
    // -------------------------------------------------------------------------

    /**
     * Builds a `where varName.refName.size() < upperBound` guard element.
     *
     * This corresponds to the upper-bound NAC in CPO Table 3/1 (Add/Create edge).
     *
     * @param varName         Name of the pattern variable (e.g. "source", "newTarget").
     * @param varClassName    Metamodel class of [varName] – used for identifier evalType.
     * @param refName         The reference on [varName] to check.
     * @param targetClassName The element type of the reference collection.
     * @param upperBound      The maximum allowed size (exclusive).
     */
    fun buildUpperBoundGuard(
        varName: String,
        varClassName: String,
        refName: String,
        targetClassName: String,
        upperBound: Int
    ): TypedPatternWhereClauseElement =
        buildBoundGuard(varName, varClassName, refName, targetClassName, upperBound, "<")

    /**
     * Builds a `where varName.refName.size() > lowerBound` guard element.
     *
     * This corresponds to the lower-bound PAC in CPO Table 4/2 (Remove/Delete edge).
     *
     * @param varName         Name of the pattern variable (e.g. "source", "oldTarget").
     * @param varClassName    Metamodel class of [varName].
     * @param refName         The reference on [varName] to check.
     * @param targetClassName The element type of the reference collection.
     * @param lowerBound      The minimum required size (inclusive; guard fires when ≤ lowerBound).
     */
    fun buildLowerBoundGuard(
        varName: String,
        varClassName: String,
        refName: String,
        targetClassName: String,
        lowerBound: Int
    ): TypedPatternWhereClauseElement =
        buildBoundGuard(varName, varClassName, refName, targetClassName, lowerBound, ">")

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private fun buildBoundGuard(
        varName: String,
        varClassName: String,
        refName: String,
        targetClassName: String,
        bound: Int,
        operator: String
    ): TypedPatternWhereClauseElement {
        val varTypeIdx  = getOrAddClassType(varClassName)
        val listTypeIdx = getOrAddListType(targetClassName)

        val identifier = TypedIdentifierExpression(
            evalType = varTypeIdx,
            name = varName,
            scope = 1
        )
        val memberAccess = TypedMemberAccessExpression(
            evalType = listTypeIdx,
            expression = identifier,
            member = refName,
            isNullChaining = false
        )
        val sizeCall = TypedMemberCallExpression(
            evalType = INT_INDEX,
            expression = memberAccess,
            member = "size",
            isNullChaining = false,
            overload = "",
            arguments = emptyList()
        )
        val boundLiteral = TypedIntLiteralExpression(
            evalType = INT_INDEX,
            value = bound.toString()
        )
        val comparison = TypedBinaryExpression(
            evalType = BOOLEAN_INDEX,
            operator = operator,
            left = sizeCall,
            right = boundLiteral
        )
        return TypedPatternWhereClauseElement(
            whereClause = TypedWhereClause(expression = comparison)
        )
    }
}
