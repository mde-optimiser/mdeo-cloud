package com.mdeo.script.compiler

import com.mdeo.metamodel.Metamodel
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.script.ast.ExternalImplementation
import com.mdeo.script.ast.TypedAst
import com.mdeo.script.ast.TypedFunction
import com.mdeo.script.ast.TypedImport
import com.mdeo.script.ast.TypedPluginAst
import com.mdeo.script.ast.TypedPluginFunctionSignature
import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.expression.ast.types.ReturnType
import com.mdeo.expression.ast.types.VoidType
import com.mdeo.script.compiler.expressions.AssertNonNullCompiler
import com.mdeo.script.compiler.expressions.BinaryExpressionCompiler
import com.mdeo.script.compiler.expressions.BooleanLiteralCompiler
import com.mdeo.script.compiler.expressions.DoubleLiteralCompiler
import com.mdeo.script.compiler.expressions.ExpressionCallCompiler
import com.mdeo.script.compiler.expressions.FloatLiteralCompiler
import com.mdeo.script.compiler.expressions.IdentifierCompiler
import com.mdeo.script.compiler.expressions.IntLiteralCompiler
import com.mdeo.script.compiler.expressions.LambdaCompiler
import com.mdeo.script.compiler.expressions.LongLiteralCompiler
import com.mdeo.script.compiler.expressions.NullLiteralCompiler
import com.mdeo.script.compiler.expressions.ExtensionCallCompiler
import com.mdeo.script.compiler.expressions.FunctionCallCompiler
import com.mdeo.script.compiler.expressions.MemberAccessCompiler
import com.mdeo.script.compiler.expressions.MemberCallCompiler
import com.mdeo.script.compiler.expressions.StringLiteralCompiler
import com.mdeo.script.compiler.expressions.TernaryExpressionCompiler
import com.mdeo.script.compiler.expressions.TypeCastCompiler
import com.mdeo.script.compiler.expressions.TypeCheckCompiler
import com.mdeo.script.compiler.expressions.UnaryExpressionCompiler
import com.mdeo.script.compiler.expressions.ListLiteralCompiler
import com.mdeo.script.compiler.model.ScriptMetamodelTypeRegistrar
import com.mdeo.script.compiler.util.toJvmBinaryName
import com.mdeo.script.compiler.registry.function.FileFunctionRegistry
import com.mdeo.script.compiler.registry.function.FunctionDefinition
import com.mdeo.script.compiler.registry.function.FunctionDefinitionImpl
import com.mdeo.script.compiler.registry.function.FunctionRegistry
import com.mdeo.script.compiler.registry.function.GlobalFunctionRegistry
import com.mdeo.script.compiler.registry.function.PluginFunctionParameter
import com.mdeo.script.compiler.registry.function.PluginFunctionSignatureDefinition
import com.mdeo.script.compiler.util.ASMUtil
import com.mdeo.script.compiler.util.CoercionUtil
import com.mdeo.script.compiler.util.MethodDescriptorUtil
import com.mdeo.script.compiler.registry.property.GlobalPropertyRegistry
import com.mdeo.script.compiler.registry.type.TypeRegistry
import com.mdeo.script.compiler.statements.AssignmentCompiler
import com.mdeo.script.compiler.statements.BreakStatementCompiler
import com.mdeo.script.compiler.statements.ContinueStatementCompiler
import com.mdeo.script.compiler.statements.ExpressionStatementCompiler
import com.mdeo.script.compiler.statements.ForStatementCompiler
import com.mdeo.script.compiler.statements.IfStatementCompiler
import com.mdeo.script.compiler.statements.ReturnStatementCompiler
import com.mdeo.script.compiler.statements.VariableDeclarationCompiler
import com.mdeo.script.compiler.statements.WhileStatementCompiler
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Main compiler for the script language.
 *
 * Takes a [CompilationInput] containing [TypedAst]s for each file and generates JVM bytecode.
 * All functions from all script files are compiled into a single JVM class
 * ([CompiledProgram.SCRIPT_PROGRAM_BINARY_NAME]). Each function is assigned an artificial JVM
 * method name (`fn0`, `fn1`, …) in file-iteration order. The mapping from
 * (filePath, functionName) to JVM method name is stored in [CompiledProgram.functionLookup].
 */
class ScriptCompiler {

    /**
     * The registered expression compilers.
     */
    private val expressionCompilers: List<ExpressionCompiler> = listOf(
        IntLiteralCompiler(),
        LongLiteralCompiler(),
        FloatLiteralCompiler(),
        DoubleLiteralCompiler(),
        BooleanLiteralCompiler(),
        StringLiteralCompiler(),
        NullLiteralCompiler(),
        BinaryExpressionCompiler(),
        UnaryExpressionCompiler(),
        TernaryExpressionCompiler(),
        IdentifierCompiler(),
        MemberAccessCompiler(),
        FunctionCallCompiler(),
        MemberCallCompiler(),
        ExpressionCallCompiler(),
        ExtensionCallCompiler(),
        LambdaCompiler(),
        AssertNonNullCompiler(),
        TypeCastCompiler(),
        TypeCheckCompiler(),
        ListLiteralCompiler()
    )

    /**
     * The registered statement compilers.
     */
    private val statementCompilers: List<StatementCompiler> = listOf(
        ReturnStatementCompiler(),
        VariableDeclarationCompiler(),
        AssignmentCompiler(),
        WhileStatementCompiler(),
        ForStatementCompiler(),
        IfStatementCompiler(),
        BreakStatementCompiler(),
        ContinueStatementCompiler(),
        ExpressionStatementCompiler()
    )

    /**
     * Compiles the given input to a [CompiledProgram].
     *
     * @param input The compilation input containing [TypedAst]s for each file.
     * @return A [CompiledProgram] containing the generated bytecode.
     */
    fun compile(input: CompilationInput): CompiledProgram {
        return compile(input, metamodelData = null)
    }

    /**
     * Compiles the given input with optional metamodel support.
     *
     * When [metamodelData] is provided, a [Metamodel] is compiled from it. The metamodel
     * generates all bytecode for instance classes, enum classes, and class containers.
     * A [TypeRegistry] is built from the metamodel's metadata so that property accesses
     * emit direct GETFIELD instructions for `prop_X` fields.
     *
     * All functions from all input files are compiled into a single JVM class
     * ([CompiledProgram.SCRIPT_PROGRAM_BINARY_NAME]). Functions are assigned artificial JVM method
     * names (`fn0`, `fn1`, …) in file-iteration order, with the mapping recorded in
     * [CompiledProgram.functionLookup].
     *
     * @param input The compilation input containing [TypedAst]s for each file.
     * @param metamodelData Optional metamodel data for type-safe model access.
     * @return A [CompiledProgram] containing the generated bytecode.
     */
    fun compile(
        input: CompilationInput,
        metamodelData: MetamodelData?
    ): CompiledProgram {
        val allBytecodes = mutableMapOf<String, ByteArray>()
        var metamodel: Metamodel? = null

        val typeRegistries = if (metamodelData != null && metamodelData.path.isNotBlank()) {
            metamodel = Metamodel.compile(metamodelData)
            createTypeRegistries(metamodel, metamodelData.path)
        } else {
            TypeRegistries(TypeRegistry.GLOBAL, GlobalPropertyRegistry())
        }

        var counter = 0
        val mutableLookup = mutableMapOf<String, MutableMap<String, String>>()
        for ((filePath, ast) in input.files) {
            val fileLookup = mutableMapOf<String, String>()
            for (func in ast.functions) {
                fileLookup[func.name] = "fn${counter++}"
            }
            mutableLookup[filePath] = fileLookup
        }
        val functionLookup: Map<String, Map<String, String>> = mutableLookup

        val pluginLookup = mutableMapOf<String, MutableMap<String, String>>()
        input.pluginAst?.functions?.forEach { func ->
            val overloadLookup = mutableMapOf<String, String>()
            func.signatures.keys.forEach { overloadKey ->
                overloadLookup[overloadKey] = "fn${counter++}"
            }
            pluginLookup[func.name] = overloadLookup
        }

        val pluginFunctionDefs = buildPluginFunctionDefs(input.pluginAst, pluginLookup)

        val effectiveGlobalRegistry = buildEffectiveGlobalRegistry(pluginFunctionDefs)

        val fileRegistries = FileFunctionRegistry.createForCompilation(
            input.files,
            effectiveGlobalRegistry,
            CompiledProgram.SCRIPT_PROGRAM_INTERNAL_NAME,
            functionLookup
        )

        val externalCalls = mutableMapOf<String, ExternalCallSpec>()
        val generatedInterfaces = mutableMapOf<String, ByteArray>()
        val programBytecode = compileSingleClass(
            input, fileRegistries, effectiveGlobalRegistry, typeRegistries, functionLookup,
            pluginLookup, generatedInterfaces, externalCalls
        )

        allBytecodes[CompiledProgram.SCRIPT_PROGRAM_BINARY_NAME] = programBytecode
        generatedInterfaces.forEach { (internalIface, ifaceBytecode) ->
            allBytecodes[internalIface.toJvmBinaryName()] = ifaceBytecode
        }

        val immutableLookup = functionLookup.mapValues { (_, v) -> v.toMap() }
        return CompiledProgram(allBytecodes, immutableLookup, metamodel, externalCalls.toMap())
    }

    /**
     * Creates a [TypeRegistries] bundle from the given compiled metamodel.
     *
     * @param metamodel The compiled metamodel.
     * @param metamodelPath The absolute path to the metamodel file.
     * @return The [TypeRegistries] for use during script compilation.
     */
    private fun createTypeRegistries(metamodel: Metamodel, metamodelPath: String): TypeRegistries {
        val compilationResult = ScriptMetamodelTypeRegistrar.createRegistry(metamodel, metamodelPath)
        return TypeRegistries(compilationResult.typeRegistry, compilationResult.fileScopeRegistry)
    }

    /**
     * Compiles all script files from [input] into a single [CompiledProgram.SCRIPT_PROGRAM_BINARY_NAME]
     * class.
     *
     * A single [ClassWriter] is used for all files. The shared [generatedInterfaces] map
     * accumulates any lambda functional interfaces produced during compilation.
     *
     * @param input The compilation input.
     * @param fileRegistries Per-file function registries for import resolution.
     * @param typeRegistries Shared type and property registries derived from the metamodel.
     * @param functionLookup Pre-assigned (filePath → functionName → jvmMethodName) mapping.
     * @param generatedInterfaces Shared mutable map for collecting generated lambda interfaces.
     * @param externalCalls Shared mutable map collecting one spec per emitted external stub.
     * @return The bytecode of the single ScriptProgram class.
     */
    private fun compileSingleClass(
        input: CompilationInput,
        fileRegistries: Map<String, FileFunctionRegistry>,
        pluginRegistry: FunctionRegistry,
        typeRegistries: TypeRegistries,
        functionLookup: Map<String, Map<String, String>>,
        pluginLookup: Map<String, Map<String, String>>,
        generatedInterfaces: MutableMap<String, ByteArray>,
        externalCalls: MutableMap<String, ExternalCallSpec>
    ): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)

        cw.visit(
            Opcodes.V11,
            Opcodes.ACC_PUBLIC,
            CompiledProgram.SCRIPT_PROGRAM_INTERNAL_NAME,
            null,
            "java/lang/Object",
            null
        )

        emitContextField(cw)
        emitConstructor(cw, CompiledProgram.SCRIPT_PROGRAM_INTERNAL_NAME)

        val sharedLambdaCounter = LambdaCounter()
        val sharedLambdaInterfaceRegistry = LambdaInterfaceRegistry()

        for ((filePath, ast) in input.files) {
            val functionRegistry = fileRegistries[filePath] ?: GlobalFunctionRegistry.GLOBAL
            val fileLookup = functionLookup[filePath] ?: emptyMap()

            for (func in ast.functions) {
                val jvmMethodName = fileLookup[func.name]!!
                compileFunction(
                    func, ast, jvmMethodName,
                    CompiledProgram.SCRIPT_PROGRAM_INTERNAL_NAME,
                    cw, generatedInterfaces,
                    functionRegistry, typeRegistries.typeRegistry,
                    typeRegistries.fileScopePropertyRegistry,
                    sharedLambdaCounter, sharedLambdaInterfaceRegistry
                )
            }
        }

        input.pluginAst?.let { pluginAst ->
            val pluginTypedAst = TypedAst(
                types = pluginAst.types,
                metamodelPath = null,
                imports = emptyList(),
                functions = emptyList()
            )
            for (func in pluginAst.functions) {
                val overloadLookup = pluginLookup[func.name] ?: continue
                for ((overloadKey, signature) in func.signatures) {
                    val jvmMethodName = overloadLookup[overloadKey] ?: continue
                    val external = signature.external
                    if (external != null) {
                        externalCalls[jvmMethodName] = compileExternalStub(
                            func.name, overloadKey, external, signature, pluginAst,
                            jvmMethodName, cw, pluginRegistry
                        )
                        continue
                    }
                    val syntheticFunc = TypedFunction(
                        name = func.name,
                        parameters = signature.parameters,
                        returnType = signature.returnType,
                        body = signature.body!!
                    )
                    compileFunction(
                        syntheticFunc, pluginTypedAst, jvmMethodName,
                        CompiledProgram.SCRIPT_PROGRAM_INTERNAL_NAME,
                        cw, generatedInterfaces,
                        pluginRegistry, typeRegistries.typeRegistry,
                        typeRegistries.fileScopePropertyRegistry,
                        sharedLambdaCounter, sharedLambdaInterfaceRegistry
                    )
                }
            }
        }

        cw.visitEnd()
        return cw.toByteArray()
    }

    /**
     * Emits the `__ctx` field that stores the [ScriptContext] for this instance.
     *
     * @param cw The [ClassWriter] to emit the field on.
     */
    private fun emitContextField(cw: ClassWriter) {
        cw.visitField(
            Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL,
            CONTEXT_FIELD_NAME,
            CONTEXT_DESCRIPTOR,
            null,
            null
        )?.visitEnd()
    }

    /**
     * Emits a constructor that accepts a [ScriptContext] and stores it in `__ctx`.
     *
     * @param cw The [ClassWriter] to emit the constructor on.
     * @param className The JVM internal class name.
     */
    private fun emitConstructor(cw: ClassWriter, className: String) {
        val mv = cw.visitMethod(
            Opcodes.ACC_PUBLIC,
            "<init>",
            "($CONTEXT_DESCRIPTOR)V",
            null,
            null
        )
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitFieldInsn(Opcodes.PUTFIELD, className, CONTEXT_FIELD_NAME, CONTEXT_DESCRIPTOR)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /**
     * Compiles a function to an instance method on the shared ScriptProgram class.
     *
     * Slot 0 is `this`, parameters start at slot 1.
     *
     * @param function The function to compile.
     * @param ast The [TypedAst] containing type information.
     * @param jvmMethodName The artificial JVM method name to use (e.g. `fn0`, `fn1`).
     * @param className The JVM internal class name (always [CompiledProgram.SCRIPT_PROGRAM_INTERNAL_NAME]).
     * @param cw The shared [ClassWriter] to emit bytecode to.
     * @param generatedInterfaces Shared map for collecting generated functional interfaces.
     * @param functionRegistry The function registry for resolving function references.
     * @param typeRegistry The type registry for type lookups.
     * @param fileScopePropertyRegistry Registry for file-scope (level 1) identifiers such as
     *   class/enum containers.
     * @param lambdaCounter Shared counter for generating unique lambda method names across functions.
     *   Must be the same instance for all functions compiled into the same class to prevent
     *   duplicate `lambda$script$N` method names.
     * @param lambdaInterfaceRegistry Shared registry for lambda functional interfaces. Must be
     *   the same instance for all functions compiled into the same class to prevent two functions
     *   from independently generating `Lambda$0` with different signatures, which would corrupt
     *   the shared [generatedInterfaces] map.
     */
    private fun compileFunction(
        function: TypedFunction,
        ast: TypedAst,
        jvmMethodName: String,
        className: String,
        cw: ClassWriter,
        generatedInterfaces: MutableMap<String, ByteArray>,
        functionRegistry: FunctionRegistry,
        typeRegistry: TypeRegistry = TypeRegistry.GLOBAL,
        fileScopePropertyRegistry: GlobalPropertyRegistry = GlobalPropertyRegistry(),
        lambdaCounter: LambdaCounter = LambdaCounter(),
        lambdaInterfaceRegistry: LambdaInterfaceRegistry = LambdaInterfaceRegistry()
    ) {
        val returnType = ast.types[function.returnType]
        val descriptor = functionRegistry.lookupFunction(function.name)!!.getOverload("")!!.descriptor

        val mv = cw.visitMethod(
            Opcodes.ACC_PUBLIC,
            jvmMethodName,
            descriptor,
            null,
            null
        )

        val paramsScope = Scope(level = 2)

        for (param in function.parameters) {
            val paramType = ast.types[param.type]
            paramsScope.declareVariable(param.name, paramType)
        }

        val tempContext = CompilationContext(
            ast, className, expressionCompilers, statementCompilers, function.returnType,
            paramsScope, classWriter = cw, generatedInterfaces = generatedInterfaces,
            lambdaCounter = lambdaCounter, lambdaInterfaceRegistry = lambdaInterfaceRegistry,
            functionRegistry = functionRegistry, typeRegistry = typeRegistry,
            fileScopePropertyRegistry = fileScopePropertyRegistry
        )

        val scopeBuilder = ScopeBuilder(tempContext)
        val bodyScope = scopeBuilder.buildFunctionScope(function, paramsScope)

        val indexAssigner = LocalVariableIndexAssigner(tempContext)
        indexAssigner.assignIndices(paramsScope, isStatic = false)

        val context = CompilationContext(
            ast, className, expressionCompilers, statementCompilers, function.returnType,
            paramsScope, scopeBuilder.statementScopes, classWriter = cw,
            generatedInterfaces = generatedInterfaces,
            lambdaCounter = lambdaCounter, lambdaInterfaceRegistry = lambdaInterfaceRegistry,
            functionRegistry = functionRegistry,
            typeRegistry = typeRegistry, fileScopePropertyRegistry = fileScopePropertyRegistry
        )
        context.enterScope(bodyScope)

        mv.visitCode()

        for (statement in function.body.body) {
            context.compileStatement(statement, mv)
        }

        ensureReturn(returnType, mv)

        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /**
     * Emits the stub standing in for a signature implemented outside the platform.
     *
     * The stub has the *same descriptor* as a locally implemented overload would, so nothing at
     * the call site can tell the two apart: a call to an external function compiles to the same
     * INVOKEVIRTUAL, with the same coercions, as a call to a contributed function with a body.
     * The difference lives entirely inside the method, which boxes its arguments into an
     * `Object[]`, hands them to the dispatcher on the script context, and unboxes the result
     * back to the declared return type.
     *
     * @param functionName The function name as the script sees it.
     * @param overloadKey Which overload of that name this is.
     * @param external The declared external implementation.
     * @param signature The overload's parameters and return type.
     * @param pluginAst The plugin AST the type indices refer to.
     * @param jvmMethodName The JVM method name assigned to this overload, also its call id.
     * @param cw The class writer to emit the method on.
     * @param functionRegistry Registry holding the descriptor assigned to this overload.
     * @return The spec describing the emitted stub.
     */
    private fun compileExternalStub(
        functionName: String,
        overloadKey: String,
        external: ExternalImplementation,
        signature: TypedPluginFunctionSignature,
        pluginAst: TypedPluginAst,
        jvmMethodName: String,
        cw: ClassWriter,
        functionRegistry: FunctionRegistry
    ): ExternalCallSpec {
        val parameterTypes = signature.parameters.map { pluginAst.types[it.type] }
        val returnType = pluginAst.types[signature.returnType]
        val descriptor = functionRegistry.lookupFunction(functionName)
            ?.getOverload(overloadKey)
            ?.descriptor
            ?: MethodDescriptorUtil.buildDescriptor(parameterTypes, returnType)

        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, jvmMethodName, descriptor, null, null)
        mv.visitCode()

        // this.__ctx.getExternalCalls()
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitFieldInsn(
            Opcodes.GETFIELD,
            CompiledProgram.SCRIPT_PROGRAM_INTERNAL_NAME,
            CONTEXT_FIELD_NAME,
            CONTEXT_DESCRIPTOR
        )
        mv.visitMethodInsn(
            Opcodes.INVOKEINTERFACE,
            CONTEXT_INTERNAL_NAME,
            "getExternalCalls",
            "()L$EXTERNAL_DISPATCHER_INTERNAL_NAME;",
            true
        )

        mv.visitLdcInsn(jvmMethodName)

        mv.visitLdcInsn(parameterTypes.size)
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object")

        var localIndex = 1
        for ((index, parameterType) in parameterTypes.withIndex()) {
            mv.visitInsn(Opcodes.DUP)
            mv.visitLdcInsn(index)
            mv.visitVarInsn(ASMUtil.getLoadOpcode(parameterType), localIndex)
            if (parameterType is ClassTypeRef && !parameterType.isNullable) {
                CoercionUtil.emitBoxing(parameterType, mv)
            }
            mv.visitInsn(Opcodes.AASTORE)
            localIndex += ASMUtil.getSlotsForType(parameterType)
        }

        mv.visitMethodInsn(
            Opcodes.INVOKEINTERFACE,
            EXTERNAL_DISPATCHER_INTERNAL_NAME,
            "call",
            "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;",
            true
        )

        if (returnType is VoidType) {
            mv.visitInsn(Opcodes.POP)
            mv.visitInsn(Opcodes.RETURN)
        } else {
            ASMUtil.emitUnboxOrCast(returnType, mv)
            mv.visitInsn(returnOpcode(returnType))
        }

        mv.visitMaxs(0, 0)
        mv.visitEnd()

        return ExternalCallSpec(
            callId = jvmMethodName,
            functionName = functionName,
            overloadKey = overloadKey,
            operation = external.operation,
            model = external.model,
            parameterTypes = parameterTypes,
            returnType = returnType,
            contribution = external.contribution,
            session = external.session
        )
    }

    /**
     * Picks the return instruction for a value of the given type.
     *
     * @param type The declared return type.
     * @return The matching return opcode.
     */
    private fun returnOpcode(type: ReturnType): Int {
        if (type is ClassTypeRef && !type.isNullable && type.`package` == "builtin") {
            return when (type.type) {
                "int", "boolean" -> Opcodes.IRETURN
                "long" -> Opcodes.LRETURN
                "float" -> Opcodes.FRETURN
                "double" -> Opcodes.DRETURN
                else -> Opcodes.ARETURN
            }
        }
        return Opcodes.ARETURN
    }

    /**
     * Ensures the method ends with a return instruction.
     *
     * Adds a void return at the end of the method regardless of whether the body already
     * terminates with a return.  This is safe because ASM's control-flow analysis drops
     * unreachable instructions.
     *
     * @param returnType The declared return type of the method.
     * @param mv The method visitor.
     */
    private fun ensureReturn(returnType: ReturnType, mv: MethodVisitor) {
        if (returnType is VoidType) {
            mv.visitInsn(Opcodes.RETURN)
        }
    }

    /**
     * Builds [PluginFunctionSignatureDefinition]s for all plugin functions in [pluginAst].
     *
     * Returns an empty map when [pluginAst] is null.
     *
     * @param pluginAst Optional plugin AST to build definitions from.
     * @param pluginLookup Pre-assigned JVM method names (funcName → overloadKey → jvmName).
     * @return Map from function name to [FunctionDefinitionImpl] containing all overloads.
     */
    private fun buildPluginFunctionDefs(
        pluginAst: TypedPluginAst?,
        pluginLookup: Map<String, Map<String, String>>
    ): Map<String, FunctionDefinitionImpl> {
        if (pluginAst == null) return emptyMap()
        val result = mutableMapOf<String, FunctionDefinitionImpl>()
        for (func in pluginAst.functions) {
            val funcDef = FunctionDefinitionImpl(
                name = func.name,
                ownerClass = CompiledProgram.SCRIPT_PROGRAM_INTERNAL_NAME
            )
            val overloadLookup = pluginLookup[func.name] ?: continue
            for ((overloadKey, signature) in func.signatures) {
                val jvmName = overloadLookup[overloadKey] ?: continue
                val namedParams = signature.parameters.map { param ->
                    PluginFunctionParameter(
                        name = param.name,
                        type = pluginAst.types[param.type]
                    )
                }
                val returnType = pluginAst.types[signature.returnType]
                val paramTypes = namedParams.map { it.type }
                val descriptor = MethodDescriptorUtil.buildDescriptor(paramTypes, returnType)
                funcDef.addOverload(
                    PluginFunctionSignatureDefinition(
                        overloadKey = overloadKey,
                        descriptor = descriptor,
                        ownerClass = CompiledProgram.SCRIPT_PROGRAM_INTERNAL_NAME,
                        jvmMethodName = jvmName,
                        namedParameters = namedParams,
                        returnType = returnType
                    )
                )
            }
            result[func.name] = funcDef
        }
        return result
    }

    /**
     * Builds a [FunctionRegistry] that resolves plugin functions before falling back to
     * [GlobalFunctionRegistry.GLOBAL].
     *
     * When [pluginFunctionDefs] is empty, returns [GlobalFunctionRegistry.GLOBAL] directly.
     *
     * @param pluginFunctionDefs Plugin function definitions to expose in the registry.
     * @return A registry that contains both plugin and stdlib functions.
     */
    private fun buildEffectiveGlobalRegistry(
        pluginFunctionDefs: Map<String, FunctionDefinitionImpl>
    ): FunctionRegistry {
        if (pluginFunctionDefs.isEmpty()) return GlobalFunctionRegistry.GLOBAL
        return object : FunctionRegistry {
            override fun lookupFunction(name: String): FunctionDefinition? =
                pluginFunctionDefs[name] ?: GlobalFunctionRegistry.GLOBAL.lookupFunction(name)

            override fun getParent(): FunctionRegistry = GlobalFunctionRegistry.GLOBAL
        }
    }

    companion object {
        /**
         * The name of the field storing the [ScriptContext] instance. 
         */
        const val CONTEXT_FIELD_NAME = "__ctx"

        /**
         * The JVM descriptor for [ScriptContext]. 
         */
        const val CONTEXT_DESCRIPTOR = "Lcom/mdeo/script/runtime/ScriptContext;"

        /**
         * The JVM internal name for [ScriptContext]. 
         */
        const val CONTEXT_INTERNAL_NAME = "com/mdeo/script/runtime/ScriptContext"

        /**
         * JVM internal class name of the dispatcher an external stub calls through.
         */
        const val EXTERNAL_DISPATCHER_INTERNAL_NAME = "com/mdeo/script/runtime/ExternalCallDispatcher"
    }
}

/**
 * Bundles the two registries produced from metamodel data so they can be passed
 * around as a single value.
 */
private data class TypeRegistries(
    val typeRegistry: TypeRegistry,
    val fileScopePropertyRegistry: GlobalPropertyRegistry
)