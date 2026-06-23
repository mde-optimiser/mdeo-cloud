package com.mdeo.script.compiler.expressions

import com.mdeo.expression.ast.expressions.TypedExpression
import com.mdeo.expression.ast.expressions.TypedTypeCheckExpression
import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.expression.ast.types.ReturnType
import com.mdeo.script.compiler.CompilationContext
import com.mdeo.script.compiler.ExpressionCompiler
import com.mdeo.script.compiler.registry.type.TypeRegistry
import com.mdeo.script.compiler.util.CoercionUtil
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Compiler for type check expressions (is / !is).
 *
 * The `is` operator checks if an expression is of a specific type at runtime.
 * The `!is` operator is the negation of `is`.
 *
 * Examples:
 * ```
 * val x: Any? = 42
 * if (x is int) { ... }     // true
 * if (x !is string) { ... } // true
 * ```
 *
 * Optimizations:
 * - Compile-time evaluation when result is statically determinable
 *   (e.g., int is always int, int is never double, List is always Any?)
 * - Runtime check is only emitted when necessary (e.g., Any? is int)
 */
class TypeCheckCompiler : ExpressionCompiler() {

    /**
     * Checks if this compiler can handle the given expression.
     *
     * @param expression The expression to check.
     * @return true if the expression is a type check expression.
     */
    override fun canCompile(expression: TypedExpression): Boolean {
        return expression.kind == "typeCheck"
    }

    /**
     * Compiles the type check expression to bytecode.
     *
     * @param expression The type check expression to compile.
     * @param context The compilation context.
     * @param mv The method visitor to emit bytecode to.
     */
    override fun compileInternal(expression: TypedExpression, context: CompilationContext, mv: MethodVisitor) {
        val checkExpr = expression as TypedTypeCheckExpression
        val sourceType = context.getType(checkExpr.expression.evalType)
        val checkType = context.getType(checkExpr.checkType)

        val staticResult = determineStaticResult(sourceType, checkType, context)

        when {
            staticResult == true -> compileAlwaysTrue(checkExpr, sourceType, context, mv)
            staticResult == false -> compileAlwaysFalse(checkExpr, sourceType, context, mv)
            else -> compileRuntimeCheck(checkExpr, sourceType, checkType, context, mv)
        }
    }

    /**
     * Compiles a type check that is statically known to be true.
     * Evaluates the expression (for side effects), pops it, and pushes true/false.
     */
    private fun compileAlwaysTrue(
        checkExpr: TypedTypeCheckExpression,
        sourceType: ReturnType,
        context: CompilationContext,
        mv: MethodVisitor
    ) {
        context.compileExpression(checkExpr.expression, mv, sourceType)
        popValue(sourceType, mv)

        val result = if (checkExpr.isNegated) false else true
        mv.visitInsn(if (result) Opcodes.ICONST_1 else Opcodes.ICONST_0)
    }

    /**
     * Compiles a type check that is statically known to be false.
     * Evaluates the expression (for side effects), pops it, and pushes false/true.
     */
    private fun compileAlwaysFalse(
        checkExpr: TypedTypeCheckExpression,
        sourceType: ReturnType,
        context: CompilationContext,
        mv: MethodVisitor
    ) {
        context.compileExpression(checkExpr.expression, mv, sourceType)
        popValue(sourceType, mv)

        val result = if (checkExpr.isNegated) true else false
        mv.visitInsn(if (result) Opcodes.ICONST_1 else Opcodes.ICONST_0)
    }

    /**
     * Compiles a type check that requires runtime instanceof check.
     */
    private fun compileRuntimeCheck(
        checkExpr: TypedTypeCheckExpression,
        sourceType: ReturnType,
        checkType: ReturnType,
        context: CompilationContext,
        mv: MethodVisitor
    ) {
        context.compileExpression(checkExpr.expression, mv, sourceType)
        ensureObjectOnStack(sourceType, mv)

        val checkClass = getInstanceOfClass(checkType, context)
        mv.visitTypeInsn(Opcodes.INSTANCEOF, checkClass)

        if (checkExpr.isNegated) {
            emitBooleanNegation(mv)
        }
    }

    /**
     * Determines if the type check result can be computed at compile time.
     *
     * @return true if always true, false if always false, null if runtime check needed.
     */
    private fun determineStaticResult(
        sourceType: ReturnType,
        checkType: ReturnType,
        context: CompilationContext
    ): Boolean? {
        if (sourceType !is ClassTypeRef || checkType !is ClassTypeRef) {
            return null
        }

        val sourceIsPrimitive = CoercionUtil.isPrimitiveType(sourceType)
        val checkIsPrimitive = CoercionUtil.isPrimitiveType(checkType)
        val sourceIsNullable = sourceType.isNullable
        val checkIsNullable = checkType.isNullable

        if (sourceType.`package` == "builtin" && sourceType.type == "Any") {
            return null
        }

        if (sourceType.`package` == checkType.`package` && sourceType.type == checkType.type) {
            return handleSameTypeName(sourceIsNullable, checkIsNullable)
        }

        if (sourceIsPrimitive && checkIsPrimitive && !sourceIsNullable) {
            return false
        }

        if (sourceIsPrimitive && !sourceIsNullable && checkType.`package` == "builtin" && checkType.type == "Any") {
            return true
        }

        if (!sourceIsPrimitive && !checkIsPrimitive) {
            return handleReferenceTypeCheck(sourceType, checkType, context)
        }

        if (sourceIsNullable && sourceIsPrimitive && checkIsPrimitive) {
            return null
        }

        return null
    }

    /**
     * Handles type check when source and check type names are the same.
     */
    private fun handleSameTypeName(sourceIsNullable: Boolean, checkIsNullable: Boolean): Boolean? {
        if (!sourceIsNullable) {
            return true
        }

        if (checkIsNullable) {
            return true
        }

        return null
    }

    /**
     * Handles type check for reference types (non-primitives).
     * Uses TypeRegistry to check subtype relationships via the extends hierarchy.
     */
    private fun handleReferenceTypeCheck(
        sourceType: ClassTypeRef,
        checkType: ClassTypeRef,
        context: CompilationContext
    ): Boolean? {
        if (checkType.`package` == "builtin" && checkType.type == "Any" && checkType.isNullable) {
            return true
        }

        val isSubtype = context.typeRegistry.isSubtype(sourceType, checkType)
        if (isSubtype) {
            return if (sourceType.isNullable && !checkType.isNullable) null else true
        }

        return null
    }

    /**
     * Ensures there's an object (not primitive) on the stack for instanceof checks.
     */
    private fun ensureObjectOnStack(sourceType: ReturnType, mv: MethodVisitor) {
        if (CoercionUtil.producesStackPrimitive(sourceType)) {
            CoercionUtil.emitBoxing(sourceType as ClassTypeRef, mv)
        }
    }

    /**
     * Pops a value from the stack, accounting for long/double taking 2 slots.
     */
    private fun popValue(type: ReturnType, mv: MethodVisitor) {
        if (type is ClassTypeRef && !type.isNullable && type.`package` == "builtin") {
            when (type.type) {
                "long", "double" -> mv.visitInsn(Opcodes.POP2)
                else -> mv.visitInsn(Opcodes.POP)
            }
        } else {
            mv.visitInsn(Opcodes.POP)
        }
    }

    /**
     * Gets the JVM class name for INSTANCEOF checks using the TypeRegistry.
     */
    private fun getInstanceOfClass(checkType: ReturnType, context: CompilationContext): String {
        val ref = checkType as ClassTypeRef
        
        val jvmClass = context.typeRegistry.getJvmClassName(ref, true)
        
        return jvmClass ?: "java/lang/Object"
    }

    /**
     * Emits bytecode to negate a boolean value on the stack.
     * Converts 0 to 1 and non-zero to 0.
     */
    private fun emitBooleanNegation(mv: MethodVisitor) {
        val falseLabel = Label()
        val endLabel = Label()

        mv.visitJumpInsn(Opcodes.IFEQ, falseLabel)
        mv.visitInsn(Opcodes.ICONST_0)
        mv.visitJumpInsn(Opcodes.GOTO, endLabel)
        mv.visitLabel(falseLabel)
        mv.visitInsn(Opcodes.ICONST_1)
        mv.visitLabel(endLabel)
    }
}
