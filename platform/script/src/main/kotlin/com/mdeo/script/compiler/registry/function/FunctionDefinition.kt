package com.mdeo.script.compiler.registry.function

import com.mdeo.expression.ast.types.ReturnType
import com.mdeo.expression.ast.types.ValueType
import com.mdeo.script.compiler.util.MethodDescriptorUtil

/**
 * Represents a function definition that can have multiple overloads.
 *
 * This unified interface supports both:
 * - Global stdlib functions (like println, listOf) with potential overloads
 * - File-scope functions (defined in script files) which typically have one overload
 *
 * Each function can have multiple signatures (overloads), identified by overload keys.
 * For file-scope functions without overloading, use the empty string "" as the key.
 */
interface FunctionDefinition {

    /**
     * The name of the function.
     */
    val name: String

    /**
     * Gets all overloads (signatures) for this function.
     *
     * For file-scope functions without overloading, this returns a single signature
     * with an empty string overload key.
     *
     * @return List of function signature definitions for all overloads.
     */
    fun getOverloads(): List<FunctionSignatureDefinition>

    /**
     * Gets a specific overload by key.
     *
     * @param overloadKey The overload key that identifies this specific overload.
     *                    Use empty string "" for non-overloaded file-scope functions.
     * @return The function signature definition, or null if not found.
     */
    fun getOverload(overloadKey: String): FunctionSignatureDefinition?

    /**
     * The JVM internal name of the class that owns this function.
     * For file-scope functions, this is the generated class for that file.
     * For global functions, this is the stdlib class.
     * Example: "com/mdeo/script/generated/Script_xxx" or "com/mdeo/script/stdlib/globals/GlobalFunctions"
     */
    val ownerClass: String
}

/**
 * Represents a function parameter with its name and type.
 *
 * @param name The parameter name.
 * @param type The parameter type.
 */
data class FunctionParameter(
    val name: String,
    val type: ReturnType
)

/**
 * Implementation of FunctionDefinition that holds multiple overloads.
 *
 * This is the standard implementation for functions with one or more overloads.
 * Use [createSimpleFileFunction] helper for file-scope functions with a single signature.
 */
class FunctionDefinitionImpl(
    override val name: String,
    override val ownerClass: String
) : FunctionDefinition {

    private val overloads: MutableMap<String, FunctionSignatureDefinition> = mutableMapOf()

    /**
     * Adds an overload to this function.
     *
     * @param overload The function signature definition for this overload.
     */
    fun addOverload(overload: FunctionSignatureDefinition) {
        overloads[overload.overloadKey] = overload
    }

    override fun getOverloads(): List<FunctionSignatureDefinition> = overloads.values.toList()

    override fun getOverload(overloadKey: String): FunctionSignatureDefinition? = overloads[overloadKey]
}

/**
 * Creates a simple file-scope function definition with a single overload.
 *
 * File-scope functions typically don't support overloading, so this helper creates
 * a FunctionDefinition with exactly one signature accessible via the empty string key.
 *
 * @param name The function name (used for registry lookup).
 * @param parameters The function parameters.
 * @param returnType The return type.
 * @param ownerClass The JVM internal class name owning this function.
 * @param jvmMethodName The actual JVM method name to invoke (may differ from [name] when
 *   artificial names like `fn0`, `fn1` are assigned during compilation).
 * @param hasDefaults Whether some parameters have default values, so the function has a
 *   [DefaultsMethod] companion.
 * @return A FunctionDefinition with a single overload.
 */
fun createSimpleFileFunction(
    name: String,
    parameters: List<FunctionParameter>,
    returnType: ReturnType,
    ownerClass: String,
    jvmMethodName: String = name,
    hasDefaults: Boolean = false
): FunctionDefinition {
    val impl = FunctionDefinitionImpl(name, ownerClass)
    
    val paramTypes: List<ValueType> = parameters.mapNotNull { param ->
        param.type as? ValueType
    }
    
    val descriptor = MethodDescriptorUtil.buildDescriptor(parameters.map { it.type }, returnType)
    
    val signature = InstanceFunctionSignatureDefinition(
        overloadKey = "",
        descriptor = descriptor,
        ownerClass = ownerClass,
        jvmMethodName = jvmMethodName,
        isVarArgs = false,
        parameterTypes = paramTypes,
        returnType = returnType,
        hasDefaults = hasDefaults
    )
    
    impl.addOverload(signature)
    return impl
}
