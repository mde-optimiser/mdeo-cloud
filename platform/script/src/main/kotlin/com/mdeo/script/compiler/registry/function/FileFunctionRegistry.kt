package com.mdeo.script.compiler.registry.function

import com.mdeo.script.ast.TypedAst
import com.mdeo.script.ast.TypedFunction
import com.mdeo.script.ast.TypedImport

/**
 * Reference to a function imported from another file.
 *
 * @param path The path of the source file containing the function.
 * @param ref  The actual function name in the source file.
 */
data class ImportedFunctionRef(
    val path: String,
    val ref: String
)

/**
 * File-specific function registry that handles local and imported function lookups.
 *
 * This registry implements a hierarchical lookup:
 * 1. Local functions defined in the current file
 * 2. Imported functions resolved through their source file registries
 * 3. Parent registry (typically the global registry)
 *
 * @param parent The parent registry (typically GlobalFunctionRegistry).
 * @param importedFileRegistries Map of file paths to their registries for resolving imports.
 */
class FileFunctionRegistry(
    private val parent: FunctionRegistry,
    private val importedFileRegistries: Map<String, FileFunctionRegistry> = emptyMap()
) : FunctionRegistry {

    private val localFunctions: MutableMap<String, FunctionDefinition> = mutableMapOf()
    private val importedFunctions: MutableMap<String, ImportedFunctionRef> = mutableMapOf()

    /**
     * Registers a local function from the file.
     *
     * Extracts parameter types from the TypedFunction and stores them in the registry.
     * File-scope functions are registered with a single overload using the empty string key.
     * The [jvmMethodName] is the artificial method name (e.g. `fn0`) assigned during
     * compilation; the function is still looked up by [func.name].
     *
     * @param func The typed function from the AST.
     * @param ast The TypedAst containing the types array.
     * @param ownerClass The JVM internal class name that owns all compiled functions.
     * @param jvmMethodName The artificial JVM method name assigned during compilation.
     */
    fun registerLocalFunction(func: TypedFunction, ast: TypedAst, ownerClass: String, jvmMethodName: String) {
        val parameters = func.parameters.map { param ->
            FunctionParameter(
                name = param.name,
                type = ast.types[param.type]
            )
        }

        val definition = createSimpleFileFunction(
            name = func.name,
            parameters = parameters,
            returnType = ast.types[func.returnType],
            ownerClass = ownerClass,
            jvmMethodName = jvmMethodName,
            hasDefaults = func.parameters.any { it.defaultValue != null }
        )

        localFunctions[func.name] = definition
    }

    /**
     * Registers an imported function.
     *
     * Stores a reference that will be resolved through the imported file's registry.
     *
     * @param import The TypedImport declaration from the AST.
     */
    fun registerImport(import: TypedImport) {
        importedFunctions[import.name] = ImportedFunctionRef(
            path = import.uri,
            ref = import.ref
        )
    }

    /**
     * Looks up a function by name following the hierarchy.
     *
     * The lookup order is:
     * 1. Local functions defined in this file
     * 2. Imported functions (resolved through imported file registries)
     * 3. Parent registry (global functions)
     *
     * @param name The function name to look up.
     * @return The function definition, or null if not found.
     */
    override fun lookupFunction(name: String): FunctionDefinition? {
        localFunctions[name]?.let { return it }

        importedFunctions[name]?.let { importRef ->
            val importedRegistry = importedFileRegistries[importRef.path]
            return importedRegistry?.lookupFunction(importRef.ref)
        }

        return parent.lookupFunction(name)
    }

    /**
     * Gets the parent registry.
     *
     * For [FileFunctionRegistry], the parent is always non-null (typically the global registry).
     * The interface returns nullable to support the root registry case.
     *
     * @return The parent function registry (never null for file registries).
     */
    override fun getParent(): FunctionRegistry = parent

    /**
     * Gets the imported function reference for a given local name.
     *
     * This is useful for determining if a function call is to an imported function
     * and getting the actual function name in the source file.
     *
     * @param name The local name of the imported function.
     * @return The import reference, or null if not an imported function.
     */
    fun getImportRef(name: String): ImportedFunctionRef? = importedFunctions[name]

    companion object {
        /**
         * Creates FileFunctionRegistry instances for all files in a compilation.
         *
         * All functions are registered against [ownerClassName] (the single
         * [CompiledProgram.SCRIPT_PROGRAM_INTERNAL_NAME] class). The [functionLookup]
         * supplies the pre-assigned artificial JVM method name for each function so that
         * call-site bytecode resolves to the correct `fn0`/`fn1`/… method.
         *
         * Registries are linked together so that imported functions can be resolved
         * through cross-file lookups at compilation time.
         *
         * @param files Map of file paths to their TypedAst.
         * @param globalRegistry The global function registry to use as parent.
         * @param ownerClassName JVM internal class name of the single generated class.
         * @param functionLookup Maps (filePath → functionName → jvmMethodName).
         * @return Map of file paths to their FileFunctionRegistry instances.
         */
        fun createForCompilation(
            files: Map<String, TypedAst>,
            globalRegistry: FunctionRegistry,
            ownerClassName: String,
            functionLookup: Map<String, Map<String, String>>
        ): Map<String, FileFunctionRegistry> {
            val linkedRegistries = mutableMapOf<String, FileFunctionRegistry>()

            for ((path, ast) in files) {
                val linkedRegistry = FileFunctionRegistry(globalRegistry, linkedRegistries)
                val fileLookup = functionLookup[path] ?: emptyMap()

                for (func in ast.functions) {
                    val jvmName = fileLookup[func.name] ?: func.name
                    linkedRegistry.registerLocalFunction(func, ast, ownerClassName, jvmName)
                }

                for (record in ast.records) {
                    val jvmName = fileLookup[record.name] ?: record.name
                    linkedRegistry.registerLocalFunction(record.toConstructor(), ast, ownerClassName, jvmName)
                }

                for (import in ast.imports) {
                    linkedRegistry.registerImport(import)
                }

                linkedRegistries[path] = linkedRegistry
            }

            return linkedRegistries
        }
    }
}
