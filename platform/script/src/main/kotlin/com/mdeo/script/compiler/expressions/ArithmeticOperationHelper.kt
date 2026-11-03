package com.mdeo.script.compiler.expressions

import com.mdeo.expression.ast.expressions.TypedBinaryExpression
import com.mdeo.expression.ast.expressions.TypedExpression
import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.expression.ast.types.ReturnType
import com.mdeo.script.compiler.util.CoercionUtil
import com.mdeo.script.compiler.CompilationContext
import com.mdeo.script.compiler.util.TypeConversionUtil
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Helper for compiling arithmetic binary operations.
 * 
 * Handles the following operators:
 * - Addition (+): numeric addition and string concatenation
 * - Subtraction (-): numeric subtraction
 * - Multiplication (*): numeric multiplication
 * - Division (/): numeric division
 * - Modulo (%): numeric remainder
 * 
 * Type promotion follows Java's binary numeric promotion rules:
 * 1. If either operand is double, both are converted to double
 * 2. Otherwise, if either operand is float, both are converted to float
 * 3. Otherwise, if either operand is long, both are converted to long
 * 4. Otherwise, both are converted to int
 * 
 * For nullable numeric types, the operands are automatically unboxed
 * before the arithmetic operation is performed.
 * 
 * @see TypeConversionUtil for type promotion logic
 * @see CoercionUtil for boxing/unboxing operations
 */
object ArithmeticOperationHelper {

    /**
     * Compiles an addition operation.
     * 
     * If the result type is String, delegates to string concatenation.
     * Otherwise, performs numeric addition with proper type promotion.
     * 
     * @param expr The binary expression AST node
     * @param context The compilation context
     * @param mv The ASM method visitor
     * @param leftType The resolved type of the left operand
     * @param rightType The resolved type of the right operand
     * @param resultType The resolved result type of the expression
     */
    fun compileAddition(
        expr: TypedBinaryExpression,
        context: CompilationContext,
        mv: MethodVisitor,
        leftType: ReturnType,
        rightType: ReturnType,
        resultType: ReturnType
    ) {
        if (TypeConversionUtil.isStringType(resultType)) {
            compileStringConcatenation(expr, context, mv, leftType, rightType)
        } else {
            compileArithmetic(expr, context, mv, leftType, rightType, resultType) { typeName ->
                TypeConversionUtil.getAddOpcode(typeName)
            }
        }
    }

    /**
     * Compiles a subtraction operation.
     * 
     * @param expr The binary expression AST node
     * @param context The compilation context
     * @param mv The ASM method visitor
     * @param leftType The resolved type of the left operand
     * @param rightType The resolved type of the right operand
     * @param resultType The resolved result type of the expression
     */
    fun compileSubtraction(
        expr: TypedBinaryExpression,
        context: CompilationContext,
        mv: MethodVisitor,
        leftType: ReturnType,
        rightType: ReturnType,
        resultType: ReturnType
    ) {
        compileArithmetic(expr, context, mv, leftType, rightType, resultType) { typeName ->
            TypeConversionUtil.getSubOpcode(typeName)
        }
    }

    /**
     * Compiles a multiplication operation.
     * 
     * @param expr The binary expression AST node
     * @param context The compilation context
     * @param mv The ASM method visitor
     * @param leftType The resolved type of the left operand
     * @param rightType The resolved type of the right operand
     * @param resultType The resolved result type of the expression
     */
    fun compileMultiplication(
        expr: TypedBinaryExpression,
        context: CompilationContext,
        mv: MethodVisitor,
        leftType: ReturnType,
        rightType: ReturnType,
        resultType: ReturnType
    ) {
        compileArithmetic(expr, context, mv, leftType, rightType, resultType) { typeName ->
            TypeConversionUtil.getMulOpcode(typeName)
        }
    }

    /**
     * Compiles a division operation.
     * 
     * @param expr The binary expression AST node
     * @param context The compilation context
     * @param mv The ASM method visitor
     * @param leftType The resolved type of the left operand
     * @param rightType The resolved type of the right operand
     * @param resultType The resolved result type of the expression
     */
    fun compileDivision(
        expr: TypedBinaryExpression,
        context: CompilationContext,
        mv: MethodVisitor,
        leftType: ReturnType,
        rightType: ReturnType,
        resultType: ReturnType
    ) {
        compileArithmetic(expr, context, mv, leftType, rightType, resultType) { typeName ->
            TypeConversionUtil.getDivOpcode(typeName)
        }
    }

    /**
     * Compiles a modulo (remainder) operation.
     * 
     * @param expr The binary expression AST node
     * @param context The compilation context
     * @param mv The ASM method visitor
     * @param leftType The resolved type of the left operand
     * @param rightType The resolved type of the right operand
     * @param resultType The resolved result type of the expression
     */
    fun compileModulo(
        expr: TypedBinaryExpression,
        context: CompilationContext,
        mv: MethodVisitor,
        leftType: ReturnType,
        rightType: ReturnType,
        resultType: ReturnType
    ) {
        compileArithmetic(expr, context, mv, leftType, rightType, resultType) { typeName ->
            TypeConversionUtil.getRemOpcode(typeName)
        }
    }

    /**
     * Compiles string concatenation using StringBuilder.
     * 
     * Generates bytecode equivalent to:
     * ```java
     * new StringBuilder().append(left).append(right).toString()
     * ```
     * 
     * @param expr The binary expression AST node
     * @param context The compilation context
     * @param mv The ASM method visitor
     * @param leftType The resolved type of the left operand
     * @param rightType The resolved type of the right operand
     */
    private fun compileStringConcatenation(
        expr: TypedBinaryExpression,
        context: CompilationContext,
        mv: MethodVisitor,
        leftType: ReturnType,
        rightType: ReturnType
    ) {
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder")
        mv.visitInsn(Opcodes.DUP)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false)
        
        context.compileExpression(expr.left, mv, leftType)
        appendToStringBuilder(leftType, mv)
        
        context.compileExpression(expr.right, mv, rightType)
        appendToStringBuilder(rightType, mv)
        
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false)
    }

    /**
     * Appends a value to a StringBuilder on the stack.
     * 
     * Uses the appropriate StringBuilder.append() overload based on the value type.
     * 
     * @param type The type of value to append
     * @param mv The ASM method visitor
     */
    private fun appendToStringBuilder(type: ReturnType, mv: MethodVisitor) {
        val descriptor = when {
            // A nullable primitive is boxed on the stack, so it is appended as an object.
            type is ClassTypeRef && type.`package` == "builtin" && !type.isNullable -> when (type.type) {
                "int" -> "(I)Ljava/lang/StringBuilder;"
                "long" -> "(J)Ljava/lang/StringBuilder;"
                "float" -> "(F)Ljava/lang/StringBuilder;"
                "double" -> "(D)Ljava/lang/StringBuilder;"
                "boolean" -> "(Z)Ljava/lang/StringBuilder;"
                "string" -> "(Ljava/lang/String;)Ljava/lang/StringBuilder;"
                else -> "(Ljava/lang/Object;)Ljava/lang/StringBuilder;"
            }
            else -> "(Ljava/lang/Object;)Ljava/lang/StringBuilder;"
        }
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", descriptor, false)
    }

    /**
     * Compiles an arithmetic operation with proper type promotion.
     * 
     * This method:
     * 1. Compiles both operands
     * 2. Unboxes nullable operands if needed
     * 3. Converts both operands to the promoted result type
     * 4. Emits the appropriate arithmetic instruction
     * 
     * @param expr The binary expression AST node
     * @param context The compilation context
     * @param mv The ASM method visitor
     * @param leftType The resolved type of the left operand
     * @param rightType The resolved type of the right operand
     * @param resultType The resolved result type of the expression
     * @param opcodeGetter Function that returns the appropriate opcode for the promoted type
     */
    private fun compileArithmetic(
        expr: TypedBinaryExpression,
        context: CompilationContext,
        mv: MethodVisitor,
        leftType: ReturnType,
        rightType: ReturnType,
        resultType: ReturnType,
        opcodeGetter: (String) -> Int
    ) {
        val resultTypeName = TypeConversionUtil.getNumericTypeName(resultType)
            ?: throw IllegalArgumentException("Result type must be numeric for arithmetic operation")
        
        val leftTypeName = TypeConversionUtil.getNumericTypeName(leftType)
            ?: throw IllegalArgumentException("Left operand must be numeric for arithmetic operation")
        
        val rightTypeName = TypeConversionUtil.getNumericTypeName(rightType)
            ?: throw IllegalArgumentException("Right operand must be numeric for arithmetic operation")
        
        compileOperandWithConversion(expr.left, context, mv, leftType, leftTypeName, resultTypeName)
        compileOperandWithConversion(expr.right, context, mv, rightType, rightTypeName, resultTypeName)
        
        mv.visitInsn(opcodeGetter(resultTypeName))
    }

    /**
     * Compiles an operand expression and converts it to the target type.
     * 
     * This method handles the complete operand compilation pipeline:
     * 1. Compiles the expression to produce a value on the stack
     * 2. Unboxes the value if it is a nullable primitive (boxed) type
     * 3. Emits type conversion instructions to convert to the target type
     * 
     * @param operand The expression AST node to compile
     * @param context The compilation context
     * @param mv The ASM method visitor
     * @param operandType The resolved type of the operand
     * @param operandTypeName The numeric type name of the operand (e.g., "int", "double")
     * @param targetTypeName The numeric type name to convert to
     */
    private fun compileOperandWithConversion(
        operand: TypedExpression,
        context: CompilationContext,
        mv: MethodVisitor,
        operandType: ReturnType,
        operandTypeName: String,
        targetTypeName: String
    ) {
        context.compileExpression(operand, mv, operandType)
        if (operandType is ClassTypeRef && operandType.isNullable && CoercionUtil.isPrimitiveType(operandType)) {
            CoercionUtil.emitUnboxing(operandType, mv)
        }
        TypeConversionUtil.emitConversion(operandTypeName, targetTypeName, mv)
    }
}
