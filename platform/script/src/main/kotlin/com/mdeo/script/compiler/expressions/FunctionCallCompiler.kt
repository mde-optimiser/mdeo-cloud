package com.mdeo.script.compiler.expressions

import com.mdeo.script.compiler.registry.function.PluginFunctionSignatureDefinition
import com.mdeo.expression.ast.expressions.TypedExpression
import com.mdeo.expression.ast.expressions.TypedFunctionCallExpression
import com.mdeo.script.compiler.CompilationContext
import com.mdeo.script.compiler.ScriptCompiler
import com.mdeo.script.compiler.registry.function.InstanceFunctionSignatureDefinition
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Compiles function call expressions to bytecode using the unified registry system.
 *
 * All functions (global stdlib and file-scope) are resolved through the hierarchical
 * [FunctionRegistry] system, which provides automatic fallback from file-scope to
 * imported functions to global stdlib functions.
 *
 * **Function Types:**
 * - Global functions: `println`, `listOf`, etc. with potential overloads
 * - File-scope functions: defined in current file, single overload (key "")
 * - Imported functions: from other files, resolved via import registries
 *
 * **Overload Keys:**
 * - File-scope functions: empty string `""` (single signature)
 * - Global functions: specific key like `"builtin.int"` or `""` for default
 *
 * **Compilation Strategy:**
 * All functions MUST be in the registry before compilation. If a function is missing,
 * this indicates a bug in the registration phase and will result in a compilation error.
 */
class FunctionCallCompiler : AbstractCallCompiler() {

    /**
     * Checks if this compiler can handle the given expression.
     */
    override fun canCompile(expression: TypedExpression): Boolean {
        return expression.kind == "functionCall"
    }

    /**
     * Compiles a function call expression to bytecode.
     *
     * Resolves the function through the unified registry and emits the appropriate
     * invocation bytecode. For instance methods (file-scope functions), pushes the
     * receiver before arguments. For context-aware static functions (stdlib),
     * pushes `this.__ctx` before user arguments.
     *
     * Argument types are taken from the [TypedCallArgument.parameterType] carried by
     * each argument, which reflect the resolved parameter types (including generic
     * substitution) determined during type checking.
     *
     * @throws IllegalStateException if function is not found in registry
     */
    override fun compileInternal(expression: TypedExpression, context: CompilationContext, mv: MethodVisitor) {
        val functionCall = expression as TypedFunctionCallExpression
        
        val funcDef = context.functionRegistry.lookupFunction(functionCall.name)
            ?: error("Function '${functionCall.name}' not found in registry")
        
        val signature = funcDef.getOverload(functionCall.overload)
            ?: error("Overload '${functionCall.overload}' not found for function '${functionCall.name}'")
        
        // Contributed functions are instance methods of the script program too, like the file's own.
        if (signature is InstanceFunctionSignatureDefinition || signature is PluginFunctionSignatureDefinition) {
            emitInstanceReceiver(mv)
        }

        if (signature.requiresContext) {
            emitContextLoad(context, mv)
        }

        compileArgumentsWithCoercion(
            functionCall.arguments,
            context,
            mv,
            signatureParameterTypes = signature.parameterTypes,
            varArgsStartIndex = if (signature.isVarArgs) signature.parameterTypes.size else null
        )
        
        signature.emitInvocation(mv)
        
        val expectedReturnType = context.getType(functionCall.evalType)
        emitReturnTypeCoercion(expectedReturnType, signature.returnType, mv)
    }

    /**
     * Emits the receiver instance for an instance method call.
     *
     * Since all script functions are compiled into a single [ScriptProgram] class,
     * the receiver is always `this` regardless of which file the target function
     * was originally defined in.
     */
    private fun emitInstanceReceiver(mv: MethodVisitor) {
        mv.visitVarInsn(Opcodes.ALOAD, 0)
    }

    /**
     * Emits bytecode to load `this.__ctx` onto the stack.
     *
     * @param context The compilation context containing the class name.
     * @param mv The method visitor.
     */
    private fun emitContextLoad(context: CompilationContext, mv: MethodVisitor) {
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitFieldInsn(
            Opcodes.GETFIELD,
            context.currentClassName,
            ScriptCompiler.CONTEXT_FIELD_NAME,
            ScriptCompiler.CONTEXT_DESCRIPTOR
        )
    }
}
