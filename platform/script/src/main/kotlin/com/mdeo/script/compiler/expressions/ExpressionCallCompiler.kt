package com.mdeo.script.compiler.expressions

import com.mdeo.expression.ast.expressions.TypedExpression
import com.mdeo.expression.ast.expressions.TypedExpressionCallExpression
import com.mdeo.expression.ast.types.LambdaType
import com.mdeo.expression.ast.types.ValueType
import com.mdeo.script.compiler.CompilationContext
import com.mdeo.script.compiler.util.CoercionUtil
import com.mdeo.script.compiler.util.MethodDescriptorUtil
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Compiles expression call expressions to bytecode.
 * 
 * An expression call is when a lambda or function reference stored in a variable
 * is invoked directly, rather than through member access. For example:
 * 
 * ```
 * val f = (x: Int) => x + 1
 * val result = f(10)  // This is an expression call
 * ```
 * 
 * The compiler generates:
 * 1. Load the lambda object from the variable
 * 2. Push all arguments onto the stack (no boxing needed with custom interfaces)
 * 3. Invoke the `call` method on the custom functional interface using INVOKEINTERFACE
 */
class ExpressionCallCompiler : AbstractCallCompiler() {
    
    /**
     * Checks if this compiler can handle the given expression.
     *
     * @param expression The expression to check.
     * @return True if this is an expression call expression, false otherwise.
     */
    override fun canCompile(expression: TypedExpression): Boolean {
        return expression.kind == "call"
    }
    
    /**
     * Compiles an expression call expression to bytecode.
     *
     * This method generates bytecode that:
     * 1. Loads the lambda object onto the stack
     * 2. Compiles all arguments with type conversions as needed (with boxing for generic interfaces)
     * 3. Invokes the `call` method using INVOKEINTERFACE
     *
     * @param expression The expression call expression to compile.
     * @param context The compilation context containing type information and utilities.
     * @param mv The method visitor for emitting bytecode instructions.
     */
    override fun compileInternal(expression: TypedExpression, context: CompilationContext, mv: MethodVisitor) {
        val exprCall = expression as TypedExpressionCallExpression
        val calleeType = context.getType(exprCall.expression.evalType)
        
        if (calleeType !is LambdaType) {
            throw IllegalStateException(
                "Expression call requires a lambda type, but got: $calleeType"
            )
        }
        
        context.compileExpression(exprCall.expression, mv, calleeType)
        
        // The call may be compiled before any lambda expression of this type, so the interface
        // may have to be generated here.
        val functionalInterface = CoercionUtil.getInterfaceForLambdaType(calleeType, context)
        
        compileArguments(exprCall, calleeType, context, mv)
        
        val methodDescriptor = buildMethodDescriptor(calleeType, context)
        
        mv.visitMethodInsn(
            Opcodes.INVOKEINTERFACE,
            functionalInterface,
            "call",
            methodDescriptor,
            true
        )
    }
    
    /**
     * Compiles all arguments for the expression call.
     *
     * Uses the normalized key parameter types for coercion. For predefined interfaces
     * (Func0-3, Action0-3, Predicate1), the key has Any? for erased generic type parameters,
     * which ensures primitives are boxed. For generated interfaces the key matches the
     * actual lambda type.
     *
     * @param exprCall The expression call containing the arguments.
     * @param lambdaType The lambda type of the callee.
     * @param context The compilation context.
     * @param mv The method visitor for emitting bytecode.
     */
    private fun compileArguments(
        exprCall: TypedExpressionCallExpression,
        lambdaType: LambdaType,
        context: CompilationContext,
        mv: MethodVisitor
    ) {
        val registry = context.getLambdaInterfaceRegistry()
        val key = registry.createKey(lambdaType)
        val jvmParamTypes: List<ValueType> = key.parameters.map { it.type }
        compileArgumentsWithCoercion(exprCall.arguments, context, mv, signatureParameterTypes = jvmParamTypes)
    }
    
    /**
     * Builds the method descriptor for the call method.
     *
     * Uses the normalized key types to build the descriptor. For predefined interfaces
     * (Func0-3, Action0-3, Predicate1), the key has Any? for erased generics and concrete
     * types for non-generic positions (e.g. boolean return for Predicate1). For generated
     * interfaces the key matches the actual lambda type.
     *
     * @param lambdaType The lambda type.
     * @param context The compilation context.
     * @return The method descriptor.
     */
    private fun buildMethodDescriptor(lambdaType: LambdaType, context: CompilationContext): String {
        val registry = context.getLambdaInterfaceRegistry()
        val key = registry.createKey(lambdaType)
        return MethodDescriptorUtil.buildDescriptor(key.parameters.map { it.type }, key.returnType)
    }
}
