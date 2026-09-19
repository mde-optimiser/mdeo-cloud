package com.mdeo.script.compiler.registry.function

import com.mdeo.expression.ast.types.ReturnType
import com.mdeo.expression.ast.types.ValueType
import com.mdeo.expression.ast.types.VoidType
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * Represents a specific function signature (overload) for a function.
 *
 * Contains all information needed to emit bytecode for calling this function signature.
 * This can represent both global stdlib functions and file-scope functions.
 */
interface FunctionSignatureDefinition {
    /**
     * The overload key that uniquely identifies this method signature.
     *
     * For non-overloaded functions, this is typically an empty string "".
     * For overloaded functions (like methods that accept different types),
     * this is typically the distinguishing type name like "builtin.int", "builtin.long".
     */
    val overloadKey: String

    /**
     * The JVM method descriptor.
     * Example: "([Ljava/lang/Object;)Lcom/mdeo/script/stdlib/collections/ScriptList;"
     */
    val descriptor: String

    /**
     * The JVM internal name of the owner class.
     * Example: "com/mdeo/script/stdlib/globals/GlobalFunctions"
     */
    val ownerClass: String

    /**
     * The JVM method name to invoke.
     */
    val jvmMethodName: String

    /**
     * Whether this method uses varargs.
     */
    val isVarArgs: Boolean

    /**
     * The parameter types for type coercion.
     *
     * Each element is a ValueType representing the expected type of the corresponding parameter.
     * For varargs methods, this list may be empty as all args are boxed to Object.
     */
    val parameterTypes: List<ValueType>

    /**
     * The return type for type coercion.
     *
     * Represents the expected return type of the function.
     * Use VoidType for functions that don't return a value.
     */
    val returnType: ReturnType

    /**
     * Whether this function requires a [ScriptContext] as the first JVM argument.
     *
     * When true, the caller must push `this.__ctx` onto the stack before the
     * user-visible arguments. The JVM descriptor already includes the context
     * parameter, but [parameterTypes] does NOT — it only lists user-visible params.
     */
    val requiresContext: Boolean
        get() = false

    /**
     * Emits the method invocation bytecode.
     *
     * All arguments should already be on the stack when this is called.
     *
     * @param mv The method visitor to emit bytecode to.
     */
    fun emitInvocation(mv: MethodVisitor)

    /**
     * Whether some parameters have default values, so a call may leave them out.
     */
    val hasDefaults: Boolean
        get() = false

    /**
     * Emits the invocation of the companion method that fills in default values.
     *
     * All parameters should be on the stack, a placeholder for each one that is left out, followed
     * by the masks of [DefaultsMethod] saying which ones are left out.
     *
     * @param mv The method visitor to emit bytecode to.
     */
    fun emitDefaultsInvocation(mv: MethodVisitor) {
        throw UnsupportedOperationException("Function '$jvmMethodName' has no default values")
    }
}

/**
 * Naming of the companion method of a function with default values.
 *
 * As in Kotlin, the companion takes the function's parameters followed by one `int` mask per 32
 * parameters: bit `i % 32` of mask `i / 32` is set when parameter `i` was left out. It evaluates
 * the default value of each such parameter, in order, and then calls the function.
 */
object DefaultsMethod {
    /**
     * The suffix appended to the function's JVM method name.
     */
    const val SUFFIX = "\$default"

    private const val BITS_PER_MASK = 32

    /**
     * The number of masks a function with [parameterCount] parameters takes.
     */
    fun maskCount(parameterCount: Int): Int = maxOf(1, (parameterCount + BITS_PER_MASK - 1) / BITS_PER_MASK)

    /**
     * The mask that holds the bit of a parameter.
     */
    fun maskIndex(parameterIndex: Int): Int = parameterIndex / BITS_PER_MASK

    /**
     * The bit of a parameter within its mask.
     */
    fun bit(parameterIndex: Int): Int = 1 shl (parameterIndex % BITS_PER_MASK)

    /**
     * Builds the descriptor of the companion method.
     *
     * @param descriptor The descriptor of the function itself.
     * @param parameterCount The number of parameters of the function.
     * @return The same descriptor with the masks appended to the parameters.
     */
    fun descriptor(descriptor: String, parameterCount: Int): String {
        val method = Type.getMethodType(descriptor)
        val masks = Array(maskCount(parameterCount)) { Type.INT_TYPE }
        return Type.getMethodDescriptor(method.returnType, *method.argumentTypes, *masks)
    }
}

/**
 * Implementation of FunctionSignatureDefinition for static method calls.
 *
 * This is the primary implementation used for both stdlib global functions
 * (like println, listOf, setOf) and file-scope functions.
 *
 * @param overloadKey The overload key (empty string for non-overloaded or file-scope functions).
 * @param descriptor The JVM method descriptor.
 * @param ownerClass The owner class internal name.
 * @param jvmMethodName The JVM method name to invoke.
 * @param isVarArgs Whether this is a varargs method.
 * @param parameterTypes The parameter types for coercion.
 * @param returnType The return type for coercion.
 */
class StaticFunctionSignatureDefinition(
    override val overloadKey: String,
    override val descriptor: String,
    override val ownerClass: String,
    override val jvmMethodName: String,
    override val isVarArgs: Boolean = false,
    override val parameterTypes: List<ValueType>,
    override val returnType: ReturnType
) : FunctionSignatureDefinition {

    override fun emitInvocation(mv: MethodVisitor) {
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            ownerClass,
            jvmMethodName,
            descriptor,
            false
        )
    }
}

/**
 * Signature for static methods whose first JVM parameter is a [ScriptContext].
 *
 * The JVM [descriptor] already includes the context parameter, but the user-visible
 * [parameterTypes] list does NOT contain it. The caller (e.g. [FunctionCallCompiler])
 * checks [requiresContext] and pushes `this.__ctx` before the regular arguments.
 *
 * @param overloadKey The overload key.
 * @param descriptor The full JVM method descriptor including the ScriptContext parameter.
 * @param ownerClass The owner class internal name.
 * @param jvmMethodName The JVM method name to invoke.
 * @param isVarArgs Whether this is a varargs method.
 * @param parameterTypes The user-visible parameter types (without ScriptContext).
 * @param returnType The return type.
 */
class ContextAwareStaticFunctionSignatureDefinition(
    override val overloadKey: String,
    override val descriptor: String,
    override val ownerClass: String,
    override val jvmMethodName: String,
    override val isVarArgs: Boolean = false,
    override val parameterTypes: List<ValueType>,
    override val returnType: ReturnType
) : FunctionSignatureDefinition {

    override val requiresContext: Boolean
        get() = true

    override fun emitInvocation(mv: MethodVisitor) {
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            ownerClass,
            jvmMethodName,
            descriptor,
            false
        )
    }
}

/**
 * Signature for instance methods on generated script classes.
 *
 * Used for file-scope functions which are now compiled as instance methods.
 * The caller must push a receiver instance onto the stack before the arguments.
 * Emits [Opcodes.INVOKEVIRTUAL] instead of [Opcodes.INVOKESTATIC].
 *
 * @param overloadKey The overload key (empty string for file-scope functions).
 * @param descriptor The JVM method descriptor (does NOT include the receiver).
 * @param ownerClass The owner class internal name.
 * @param jvmMethodName The JVM method name to invoke.
 * @param isVarArgs Whether this is a varargs method.
 * @param parameterTypes The parameter types for coercion.
 * @param returnType The return type for coercion.
 * @param hasDefaults Whether a [DefaultsMethod] companion exists for this function.
 */
class InstanceFunctionSignatureDefinition(
    override val overloadKey: String,
    override val descriptor: String,
    override val ownerClass: String,
    override val jvmMethodName: String,
    override val isVarArgs: Boolean = false,
    override val parameterTypes: List<ValueType>,
    override val returnType: ReturnType,
    override val hasDefaults: Boolean = false
) : FunctionSignatureDefinition {

    override fun emitInvocation(mv: MethodVisitor) {
        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            ownerClass,
            jvmMethodName,
            descriptor,
            false
        )
    }

    override fun emitDefaultsInvocation(mv: MethodVisitor) {
        if (!hasDefaults) {
            super.emitDefaultsInvocation(mv)
        }
        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            ownerClass,
            jvmMethodName + DefaultsMethod.SUFFIX,
            DefaultsMethod.descriptor(descriptor, parameterTypes.size),
            false
        )
    }
}

/**
 * A single named parameter from a plugin-contributed function.
 *
 * Unlike [FunctionParameter], this carries the actual [ReturnType] object instead of
 * looking it up through a type-index; this is necessary because plugin types live in
 * a separate types array that is not accessible via the calling script's compilation
 * context.
 *
 * @param name Parameter name as declared in the plugin function.
 * @param type Resolved [ReturnType] for this parameter.
 */
data class PluginFunctionParameter(
    val name: String,
    val type: ReturnType
)

/**
 * Signature definition for plugin-contributed functions compiled into the generated
 * [com.mdeo.script.compiler.CompiledProgram.SCRIPT_PROGRAM_BINARY_NAME] class.
 *
 * Extends the instance-method calling convention ([Opcodes.INVOKEVIRTUAL]) of regular
 * file-scope functions. The key difference is that each parameter carries both its
 * **name** (needed to match named extension-call arguments) and its resolved
 * [ReturnType] (needed for coercion and list-cardinality detection at call sites).
 *
 * @param overloadKey The overload identifier (typically `""` for extension-expression
 *                    overloads, but may be any string for multi-overload contributions).
 * @param descriptor The JVM method descriptor (does NOT include the receiver `this`).
 * @param ownerClass The JVM internal class name that owns this method.
 * @param jvmMethodName The JVM method name assigned during compilation (e.g. `fn0`).
 * @param namedParameters Ordered list of parameters with names and resolved types.
 * @param returnType Resolved return type of this overload.
 */
class PluginFunctionSignatureDefinition(
    override val overloadKey: String,
    override val descriptor: String,
    override val ownerClass: String,
    override val jvmMethodName: String,
    val namedParameters: List<PluginFunctionParameter>,
    override val returnType: ReturnType,
    override val hasDefaults: Boolean = false
) : FunctionSignatureDefinition {

    override val isVarArgs: Boolean = false

    override val parameterTypes: List<ValueType> =
        namedParameters.mapNotNull { it.type as? ValueType }

    override fun emitInvocation(mv: MethodVisitor) {
        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            ownerClass,
            jvmMethodName,
            descriptor,
            false
        )
    }

    override fun emitDefaultsInvocation(mv: MethodVisitor) {
        if (!hasDefaults) {
            super.emitDefaultsInvocation(mv)
        }
        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            ownerClass,
            jvmMethodName + DefaultsMethod.SUFFIX,
            DefaultsMethod.descriptor(descriptor, parameterTypes.size),
            false
        )
    }
}
