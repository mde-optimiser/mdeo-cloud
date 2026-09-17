package com.mdeo.script.compiler.expressions

import com.mdeo.expression.ast.expressions.TypedCallArgument
import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.expression.ast.types.LambdaType
import com.mdeo.expression.ast.types.ReturnType
import com.mdeo.expression.ast.types.ValueType
import com.mdeo.script.compiler.util.ASMUtil
import com.mdeo.script.compiler.util.CoercionUtil
import com.mdeo.script.compiler.CompilationContext
import com.mdeo.script.compiler.CompilationException
import com.mdeo.script.compiler.ExpressionCompiler
import com.mdeo.script.compiler.registry.function.DefaultsMethod
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Abstract base class for call expression compilers.
 *
 * This class provides shared functionality for:
 * - Parameter type coercion (boxing primitives, type widening) based on resolved parameter types
 *   provided by each [TypedCallArgument]
 * - Return type coercion (unboxing when needed)
 * - Varargs argument packaging with proper coercion
 * - Common compile patterns
 *
 * Subclasses implement specific invocation logic for:
 * - Global function calls ([FunctionCallCompiler])
 * - Member method calls ([MemberCallCompiler])
 * - Expression (lambda) calls ([ExpressionCallCompiler])
 */
abstract class AbstractCallCompiler : ExpressionCompiler() {

    /**
     * Emits return type coercion if needed.
     *
     * Handles unboxing when the return type from the function signature is a
     * generic (Any?) but the expected type at the call site is a primitive.
     *
     * @param expectedType The expected return type at the call site.
     * @param actualReturnType The actual return type from the method signature.
     * @param mv The ASM MethodVisitor for emitting bytecode.
     */
    protected fun emitReturnTypeCoercion(
        expectedType: ReturnType,
        actualReturnType: ReturnType,
        mv: MethodVisitor
    ) {
        if (expectedType !is ClassTypeRef || actualReturnType !is ClassTypeRef) {
            return
        }

        val isReturnNullable = actualReturnType.isNullable
        val isReturnAny = actualReturnType.`package` == "builtin" && actualReturnType.type == "Any"

        if ((isReturnAny || isReturnNullable) &&
            !expectedType.isNullable && CoercionUtil.isPrimitiveType(expectedType)) {
            CoercionUtil.emitUnboxing(expectedType, mv)
        }
    }

    /**
     * Compiles call arguments with type coercion, handling both regular and varargs parameters.
     *
     * Each [TypedCallArgument] carries its own expected parameter type (resolved during
     * type checking, including generic substitution). This method uses that type to apply
     * correct coercion for each argument.
     *
     * Coercion is applied in two steps:
     * 1. Coerce from the argument's actual type to the resolved parameter type from
     *    [TypedCallArgument.parameterType] (e.g., int → double for generic resolution).
     * 2. Coerce from the resolved parameter type to the JVM method's expected type from
     *    [signatureParameterTypes] (e.g., boxing an int to Object when the JVM method
     *    takes Object). This step is only needed when the JVM type differs from the
     *    resolved type.
     *
     * For varargs, arguments starting at [varArgsStartIndex] are packaged into an Object[]
     * array. Each varargs element is first coerced to its expected type, then boxed if it is
     * a primitive (since Object[] can only hold reference types). Arguments before
     * [varArgsStartIndex] are compiled as regular parameters with coercion.
     *
     * @param arguments The list of call arguments with their expected parameter types.
     * @param context The compilation context.
     * @param mv The ASM MethodVisitor for emitting bytecode.
     * @param signatureParameterTypes The JVM-level parameter types from the function/method
     *                                 signature. Used for the second coercion step (boxing,
     *                                 widening to match JVM descriptor). Empty list if not
     *                                 available (e.g., for expression calls where JVM types
     *                                 are derived differently).
     * @param varArgsStartIndex The index at which varargs begin. Arguments before this index
     *                          are compiled as regular parameters. Null means no varargs
     */
    protected fun compileArgumentsWithCoercion(
        arguments: List<TypedCallArgument>,
        context: CompilationContext,
        mv: MethodVisitor,
        signatureParameterTypes: List<ValueType> = emptyList(),
        varArgsStartIndex: Int? = null
    ) {
        if (varArgsStartIndex == null) {
            for ((i, arg) in arguments.withIndex()) {
                val resolvedType = context.getType(arg.parameterType)
                context.compileExpression(arg.value, mv, resolvedType)
                if (i < signatureParameterTypes.size) {
                    val effectiveTargetType = normalizeSignatureParamType(signatureParameterTypes[i], context)
                    CoercionUtil.emitCoercion(resolvedType, effectiveTargetType, mv, context)
                }
            }
            return
        }

        for (i in 0 until varArgsStartIndex.coerceAtMost(arguments.size)) {
            val arg = arguments[i]
            val resolvedType = context.getType(arg.parameterType)
            context.compileExpression(arg.value, mv, resolvedType)
            if (i < signatureParameterTypes.size) {
                val effectiveTargetType = normalizeSignatureParamType(signatureParameterTypes[i], context)
                CoercionUtil.emitCoercion(resolvedType, effectiveTargetType, mv, context)
            }
        }

        val varArgs = arguments.subList(varArgsStartIndex.coerceAtMost(arguments.size), arguments.size)
        mv.visitLdcInsn(varArgs.size)
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object")

        for ((index, arg) in varArgs.withIndex()) {
            mv.visitInsn(Opcodes.DUP)
            mv.visitLdcInsn(index)

            val resolvedType = context.getType(arg.parameterType)
            context.compileExpression(arg.value, mv, resolvedType)

            if (resolvedType is ClassTypeRef && !resolvedType.isNullable && CoercionUtil.isPrimitiveType(resolvedType)) {
                CoercionUtil.emitBoxing(resolvedType, mv)
            }

            mv.visitInsn(Opcodes.AASTORE)
        }
    }

    /**
     * Reports whether the arguments of a call are not simply one per parameter in order, because
     * some are passed by name to another position or some parameters are left to their defaults.
     *
     * @param arguments The call arguments, in the order they are written.
     * @param parameterCount The number of parameters of the called signature.
     * @param hasDefaults Whether the called signature has default values.
     * @return True when the arguments have to be placed with [compileBoundArguments].
     */
    protected fun needsArgumentBinding(
        arguments: List<TypedCallArgument>,
        parameterCount: Int,
        hasDefaults: Boolean
    ): Boolean =
        (hasDefaults && arguments.size < parameterCount) ||
            arguments.withIndex().any { (index, argument) -> argument.parameter != null && argument.parameter != index }

    /**
     * Compiles call arguments onto the stack in parameter order, whatever order they are written in.
     *
     * Arguments are evaluated in the order they are written. When that differs from the parameter
     * order, each value is kept in a temporary local until all are evaluated. A parameter no
     * argument is passed to gets a zero placeholder of its JVM type, and its bit is set in the
     * returned mask, so the caller can invoke the [com.mdeo.script.compiler.registry.function.DefaultsMethod]
     * companion instead.
     *
     * @param arguments The call arguments, in the order they are written.
     * @param context The compilation context.
     * @param mv The ASM MethodVisitor for emitting bytecode.
     * @param signatureParameterTypes The JVM-level parameter types of the called signature.
     * @return A mask with bit `i` set when parameter `i` received no argument.
     */
    protected fun compileBoundArguments(
        arguments: List<TypedCallArgument>,
        context: CompilationContext,
        mv: MethodVisitor,
        signatureParameterTypes: List<ValueType>
    ): Int {
        val parameterCount = signatureParameterTypes.size
        val parameterOf = arguments.mapIndexed { index, argument -> argument.parameter ?: index }
        val argumentFor = arrayOfNulls<Int>(parameterCount)
        parameterOf.forEachIndexed { index, parameter -> argumentFor[parameter] = index }
        val targetTypes = signatureParameterTypes.map { normalizeSignatureParamType(it, context) }

        val compileArgument = { index: Int ->
            val argument = arguments[index]
            val resolvedType = context.getType(argument.parameterType)
            context.compileExpression(argument.value, mv, resolvedType)
            CoercionUtil.emitCoercion(resolvedType, targetTypes[parameterOf[index]], mv, context)
        }

        val inParameterOrder = parameterOf.zipWithNext().all { (first, second) -> first < second }
        val slots = IntArray(arguments.size)
        if (!inParameterOrder) {
            for (index in arguments.indices) {
                compileArgument(index)
                val targetType = targetTypes[parameterOf[index]]
                slots[index] = context.allocateTempSlot(ASMUtil.getSlotsForType(targetType))
                mv.visitVarInsn(ASMUtil.getStoreOpcode(targetType), slots[index])
            }
        }

        var mask = 0
        for (parameter in 0 until parameterCount) {
            val index = argumentFor[parameter]
            when {
                index == null -> {
                    if (parameter >= DefaultsMethod.MAX_PARAMETERS) {
                        throw CompilationException("Parameter ${parameter + 1} is left out, but only the first ${DefaultsMethod.MAX_PARAMETERS} parameters can have default values")
                    }
                    emitZeroValue(targetTypes[parameter], mv)
                    mask = mask or (1 shl parameter)
                }
                inParameterOrder -> compileArgument(index)
                else -> mv.visitVarInsn(ASMUtil.getLoadOpcode(targetTypes[parameter]), slots[index])
            }
        }
        return mask
    }

    /**
     * Pushes the zero value of a type: `0` for a primitive, `null` for a reference.
     *
     * @param type The type.
     * @param mv The ASM MethodVisitor for emitting bytecode.
     */
    private fun emitZeroValue(type: ReturnType, mv: MethodVisitor) {
        when (ASMUtil.getTypeDescriptor(type)) {
            "I", "Z", "B", "S", "C" -> mv.visitInsn(Opcodes.ICONST_0)
            "J" -> mv.visitInsn(Opcodes.LCONST_0)
            "F" -> mv.visitInsn(Opcodes.FCONST_0)
            "D" -> mv.visitInsn(Opcodes.DCONST_0)
            else -> mv.visitInsn(Opcodes.ACONST_NULL)
        }
    }

    /**
     * Returns the effective coercion target type for a signature parameter.
     *
     * If the signature parameter is a [LambdaType] that maps to a predefined runtime
     * interface (Func0-3, Action0-3, Predicate1), the normalized key is returned so that
     * the argument lambda is coerced to exactly the predefined interface type. Otherwise
     * the original type is returned unchanged.
     *
     * @param sigType The declared parameter type from the function/method signature.
     * @param context The compilation context.
     * @return The type to use as the coercion target.
     */
    private fun normalizeSignatureParamType(sigType: ValueType, context: CompilationContext): ReturnType {
        if (sigType !is LambdaType) {
            return sigType
        }
        val registry = context.getLambdaInterfaceRegistry()
        return if (registry.isPredefined(sigType)) registry.createKey(sigType) else sigType
    }

}
