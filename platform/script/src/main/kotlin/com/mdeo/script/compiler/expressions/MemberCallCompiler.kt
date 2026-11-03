package com.mdeo.script.compiler.expressions

import com.mdeo.expression.ast.expressions.TypedExpression
import com.mdeo.expression.ast.expressions.TypedMemberCallExpression
import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.expression.ast.types.LambdaType
import com.mdeo.expression.ast.types.ReturnType
import com.mdeo.script.compiler.util.CoercionUtil
import com.mdeo.script.compiler.CompilationContext
import com.mdeo.script.compiler.RecordClasses
import com.mdeo.script.compiler.util.ASMUtil
import com.mdeo.script.compiler.ScriptCompiler
import com.mdeo.script.compiler.registry.type.TypeRegistry
import com.mdeo.script.compiler.registry.type.MethodDefinition
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Compiles member call expressions to bytecode.
 *
 * Member call expressions invoke methods on objects.
 * Supports null-safe chaining (?.) where if the expression is null,
 * the result is null instead of throwing a NullPointerException.
 *
 * The compiler looks up method definitions from the [TypeRegistry] using:
 * - The target type name (e.g., "builtin.int", "builtin.string")
 * - The method name
 * - The overload key (empty string "" for non-overloaded, type name for overloaded)
 *
 * If a method is not found in the registry, the compiler falls back to
 * file-scope behavior for custom types.
 */
class MemberCallCompiler : AbstractCallCompiler() {

    /**
     * Checks if this compiler can handle the given expression.
     *
     * @param expression The expression to check.
     * @return True if the expression is a member call expression, false otherwise.
     */
    override fun canCompile(expression: TypedExpression): Boolean {
        return expression.kind == "memberCall"
    }

    /**
     * Compiles a member call expression to bytecode.
     *
     * @param expression The typed member call expression to compile.
     * @param context The compilation context containing type information and utilities.
     * @param mv The method visitor used to emit bytecode instructions.
     */
    override fun compileInternal(expression: TypedExpression, context: CompilationContext, mv: MethodVisitor) {
        val memberCall = expression as TypedMemberCallExpression
        val targetType = context.getType(memberCall.expression.evalType)
        val resultType = context.getType(memberCall.evalType)

        if (memberCall.isNullChaining) {
            compileNullSafeMemberCall(memberCall, context, mv, targetType, resultType)
        } else {
            compileMemberCall(memberCall, context, mv, targetType, resultType)
        }
    }

    /**
     * Compiles a null-safe member call (?.).
     *
     * If the expression is null, the result is null instead of throwing a NullPointerException.
     * For null-safe chaining, the result must be boxed if the underlying method returns a primitive,
     * since the expression result type is always nullable.
     * 
     * When the base expression is a nullable primitive (Int?, Boolean?, etc.), the value on the stack
     * is boxed, so we must look up methods on builtin.any instead of the primitive type.
     */
    private fun compileNullSafeMemberCall(
        memberCall: TypedMemberCallExpression,
        context: CompilationContext,
        mv: MethodVisitor,
        targetType: ReturnType,
        resultType: ReturnType
    ) {
        val nullLabel = Label()
        val endLabel = Label()

        context.compileExpression(memberCall.expression, mv, targetType)

        mv.visitInsn(Opcodes.DUP)
        mv.visitJumpInsn(Opcodes.IFNULL, nullLabel)

        val effectiveTargetType = if (targetType is ClassTypeRef && targetType.isNullable && CoercionUtil.isPrimitiveType(targetType)) {
            ClassTypeRef("builtin", "Any", false)
        } else {
            targetType
        }

        emitMemberCall(memberCall, context, mv, effectiveTargetType, resultType)

        if (resultType is ClassTypeRef && resultType.isNullable && CoercionUtil.isPrimitiveType(resultType)) {
            CoercionUtil.emitBoxing(resultType, mv)
        }

        mv.visitJumpInsn(Opcodes.GOTO, endLabel)

        mv.visitLabel(nullLabel)
        mv.visitInsn(Opcodes.POP)
        mv.visitInsn(Opcodes.ACONST_NULL)

        mv.visitLabel(endLabel)
    }

    /**
     * Compiles a regular (non-null-safe) member call.
     */
    private fun compileMemberCall(
        memberCall: TypedMemberCallExpression,
        context: CompilationContext,
        mv: MethodVisitor,
        targetType: ReturnType,
        resultType: ReturnType
    ) {
        context.compileExpression(memberCall.expression, mv, targetType)
        emitMemberCall(memberCall, context, mv, targetType, resultType)
    }

    /**
     * Emits a member call on the object already present on the stack.
     *
     * First tries to look up the method in the registry. If found, uses the
     * registry definition to emit the invocation. Otherwise falls back to
     * file-scope behavior for custom types.
     */
    private fun emitMemberCall(
        memberCall: TypedMemberCallExpression,
        context: CompilationContext,
        mv: MethodVisitor,
        targetType: ReturnType,
        resultType: ReturnType
    ) {
        val lookupTypeRef = if (targetType is LambdaType) {
            ClassTypeRef("builtin", "Any", false)
        } else if (targetType is ClassTypeRef) {
            targetType
        } else {
            throw UnsupportedOperationException("Cannot call member on non-class/lambda type")
        }

        val methodDef = context.typeRegistry.lookupMethod(lookupTypeRef, memberCall.member, memberCall.overload)
            ?: throw UnsupportedOperationException("Method ${memberCall.member} not found on type ${lookupTypeRef.`package`}.${lookupTypeRef.type}")

        if (methodDef is RecordClasses.CopyMethodDefinition) {
            emitRecordCopy(memberCall, context, mv, methodDef)
            return
        }

        emitRegistryMethodCall(memberCall, context, mv, methodDef, resultType)
    }

    /**
     * Emits a method call using a registry method definition.
     *
     * Handles argument compilation and coercion, then delegates to the
     * method definition to emit the actual invocation.
     *
     * For static helper methods that expect Object (inherited from Any),
     * the primitive receiver must be boxed before the call.
     */
    private fun emitRegistryMethodCall(
        memberCall: TypedMemberCallExpression,
        context: CompilationContext,
        mv: MethodVisitor,
        methodDef: MethodDefinition,
        resultType: ReturnType
    ) {
        val targetType = context.getType(memberCall.expression.evalType)
        
        if (needsReceiverBoxing(targetType, methodDef)) {
            if (targetType is ClassTypeRef) {
                CoercionUtil.emitBoxing(targetType, mv)
            }
        }

        if (methodDef.requiresContext) {
            mv.visitVarInsn(Opcodes.ALOAD, 0)
            mv.visitFieldInsn(
                Opcodes.GETFIELD,
                context.currentClassName,
                ScriptCompiler.CONTEXT_FIELD_NAME,
                ScriptCompiler.CONTEXT_DESCRIPTOR
            )
        }

        if (!methodDef.isVarArgs && needsArgumentBinding(memberCall.arguments, methodDef.parameterTypes.size, hasDefaults = false)) {
            // Methods have no default values, so every parameter receives an argument.
            compileBoundArguments(memberCall.arguments, context, mv, methodDef.parameterTypes)
        } else {
            compileArgumentsWithCoercion(
                memberCall.arguments,
                context,
                mv,
                signatureParameterTypes = methodDef.parameterTypes,
                varArgsStartIndex = if (methodDef.isVarArgs)  methodDef.parameterTypes.size - 1 else null
            )
        }

        methodDef.emitInvocation(mv)

        emitReturnTypeCoercion(resultType, methodDef.returnType, mv)
    }

    /**
     * Checks if the receiver value on the stack needs boxing before method invocation.
     *
     * This is needed when:
     * 1. The target type is a primitive (int, long, float, double, boolean)
     * 2. The method is a static helper (not nullable primitive on stack)
     * 3. The method's descriptor expects Object as the first parameter
     *
     * This typically happens when calling methods inherited from Any on primitives.
     *
     * @param targetType The type of the receiver value on the stack.
     * @param methodDef The method definition being invoked.
     * @return true if the receiver needs to be boxed.
     */
    /**
     * Emits a call of a record's copy method, with the record on the stack.
     *
     * Copies the record, then sets each field the call passes on the copy, evaluating the
     * arguments in the order they are written. Leaves the copy on the stack.
     *
     * @param memberCall The member call expression.
     * @param context The compilation context.
     * @param mv The method visitor.
     * @param methodDef The copy method, which carries the field types.
     */
    private fun emitRecordCopy(
        memberCall: TypedMemberCallExpression,
        context: CompilationContext,
        mv: MethodVisitor,
        methodDef: RecordClasses.CopyMethodDefinition
    ) {
        mv.visitTypeInsn(Opcodes.CHECKCAST, RecordClasses.RECORD_BASE)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RecordClasses.RECORD_BASE, "copy", "()L${RecordClasses.RECORD_BASE};", false)
        for ((index, argument) in memberCall.arguments.withIndex()) {
            val field = argument.parameter ?: index
            val fieldType = methodDef.parameterTypes[field]
            val resolvedType = context.getType(argument.parameterType)
            mv.visitInsn(Opcodes.DUP)
            context.compileExpression(argument.value, mv, resolvedType)
            CoercionUtil.emitCoercion(resolvedType, fieldType, mv, context)
            RecordClasses.emitBoxing(fieldType, mv)
            mv.visitLdcInsn(field)
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                RecordClasses.RECORD_BASE,
                "set",
                "(Ljava/lang/Object;Ljava/lang/Object;I)V",
                false
            )
        }
    }

    private fun needsReceiverBoxing(targetType: ReturnType, methodDef: MethodDefinition): Boolean {
        if (!methodDef.isStatic) {
            return false
        }

        if (targetType is LambdaType) {
            return false
        }

        if (targetType !is ClassTypeRef) {
            return false
        }

        if (targetType.isNullable) {
            return false
        }

        if (!CoercionUtil.isPrimitiveType(targetType)) {
            return false
        }

        val descriptor = methodDef.descriptor
        return descriptor.startsWith("(Ljava/lang/Object;")
    }
}
