package com.mdeo.script.compiler

import com.mdeo.metamodel.Metamodel
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.script.ast.ExternalImplementation
import com.mdeo.script.ast.TypedAst
import com.mdeo.script.ast.TypedFunction
import com.mdeo.script.ast.TypedImport
import com.mdeo.script.ast.TypedPluginAst
import com.mdeo.script.ast.TypedPluginClass
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
import com.mdeo.script.compiler.registry.function.DefaultsMethod
import com.mdeo.script.compiler.registry.function.FunctionRegistry
import com.mdeo.script.compiler.registry.function.GlobalFunctionRegistry
import com.mdeo.script.compiler.registry.function.PluginFunctionParameter
import com.mdeo.script.compiler.registry.function.PluginFunctionSignatureDefinition
import com.mdeo.script.compiler.util.ASMUtil
import com.mdeo.script.compiler.util.CoercionUtil
import com.mdeo.script.compiler.util.MethodDescriptorUtil
import com.mdeo.script.compiler.registry.property.GlobalPropertyRegistry
import com.mdeo.script.compiler.registry.type.TypeDefinitionImpl
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
import org.objectweb.asm.Label
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

        val baseRegistries = if (metamodelData != null && metamodelData.path.isNotBlank()) {
            metamodel = Metamodel.compile(metamodelData)
            createTypeRegistries(metamodel, metamodelData.path)
        } else {
            TypeRegistries(TypeRegistry.GLOBAL, GlobalPropertyRegistry())
        }

        // Records and opaque classes contributions define, chained to the metamodel's types, and
        // the records scripts declare, chained to those.
        val contributedClasses = ContributedClassCompiler.specs(input.pluginAst)
        val recordClasses = scriptRecordClassNames(input)
        val typeRegistries = TypeRegistries(
            registerScriptRecords(
                ContributedClassCompiler.register(baseRegistries.typeRegistry, contributedClasses.values),
                input,
                recordClasses
            ),
            baseRegistries.fileScopePropertyRegistry
        )
        allBytecodes += ContributedClassCompiler.generate(contributedClasses.values)
        for (ast in input.files.values) {
            for (record in ast.records) {
                val jvmClassName = recordClasses.getValue(record.typeId)
                allBytecodes[jvmClassName.toJvmBinaryName()] =
                    RecordClasses.generate(jvmClassName, record.typeId, record.fields.map { it.name })
            }
        }

        var counter = 0
        val mutableLookup = mutableMapOf<String, MutableMap<String, String>>()
        for ((filePath, ast) in input.files) {
            val fileLookup = mutableMapOf<String, String>()
            for (func in ast.functions) {
                fileLookup[func.name] = "fn${counter++}"
            }
            for (record in ast.records) {
                fileLookup[record.name] = "fn${counter++}"
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
        // A contributed record's constructor is a contributed function named like the record.
        for (spec in contributedClasses.values) {
            if (spec.kind == TypedPluginClass.KIND_RECORD) {
                pluginLookup[spec.name] = mutableMapOf("" to "fn${counter++}")
            }
        }

        val pluginFunctionDefs = buildPluginFunctionDefs(input.pluginAst, pluginLookup, contributedClasses.values)

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
            pluginLookup, generatedInterfaces, externalCalls, recordClasses, contributedClasses
        )

        allBytecodes[CompiledProgram.SCRIPT_PROGRAM_BINARY_NAME] = programBytecode
        generatedInterfaces.forEach { (internalIface, ifaceBytecode) ->
            allBytecodes[internalIface.toJvmBinaryName()] = ifaceBytecode
        }

        val immutableLookup = functionLookup.mapValues { (_, v) -> v.toMap() }
        return CompiledProgram(allBytecodes, immutableLookup, metamodel, externalCalls.toMap(), contributedClasses)
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
     * @param recordClasses JVM class names of the records scripts declare, by type id.
     * @param contributedClasses The classes contributions define, by type id.
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
        externalCalls: MutableMap<String, ExternalCallSpec>,
        recordClasses: Map<String, String>,
        contributedClasses: Map<String, ContributedClassSpec>
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

            for (record in ast.records) {
                val constructor = record.toConstructor()
                val jvmMethodName = fileLookup[record.name]!!
                val descriptor = functionRegistry.lookupFunction(record.name)!!.getOverload("")!!.descriptor
                compileRecordConstructor(
                    cw, jvmMethodName, descriptor, recordClasses.getValue(record.typeId),
                    record.fields.map { ast.types[it.type] }
                )
                if (record.fields.any { it.defaultValue != null }) {
                    compileDefaultsMethod(
                        constructor, ast, jvmMethodName, descriptor,
                        CompiledProgram.SCRIPT_PROGRAM_INTERNAL_NAME, cw, generatedInterfaces,
                        functionRegistry, typeRegistries.typeRegistry,
                        typeRegistries.fileScopePropertyRegistry,
                        sharedLambdaCounter, sharedLambdaInterfaceRegistry
                    )
                }
            }
        }

        for (spec in contributedClasses.values) {
            if (spec.kind != TypedPluginClass.KIND_RECORD) continue
            val signature = pluginRegistry.lookupFunction(spec.name)!!.getOverload("")!!
            compileRecordConstructor(cw, signature.jvmMethodName, signature.descriptor, spec.jvmClassName, spec.fieldTypes)
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
     * Assigns a JVM class name to every record the scripts declare.
     *
     * @param input The compilation input.
     * @return The internal class names, by [com.mdeo.script.ast.TypedRecord.typeId].
     */
    private fun scriptRecordClassNames(input: CompilationInput): Map<String, String> {
        var index = 0
        return input.files.values
            .flatMap { it.records }
            .associate { record -> record.typeId to "$RECORD_CLASS_PACKAGE/R${index++}_${record.name}" }
    }

    /**
     * Registers the records the scripts declare in a registry chained to [parent].
     *
     * @param parent The registry scripts see otherwise.
     * @param input The compilation input.
     * @param recordClasses The JVM class names of the records, by type id.
     * @return The registry that also knows the records, or [parent] when there are none.
     */
    private fun registerScriptRecords(
        parent: TypeRegistry,
        input: CompilationInput,
        recordClasses: Map<String, String>
    ): TypeRegistry {
        if (recordClasses.isEmpty()) return parent
        val registry = TypeRegistry(parent = parent)
        for (ast in input.files.values) {
            for (record in ast.records) {
                val definition = TypeDefinitionImpl(
                    typePackage = record.`package`,
                    typeName = record.name,
                    extends = listOf(ClassTypeRef("builtin", "Any", false)),
                    jvmClassName = recordClasses.getValue(record.typeId)
                )
                RecordClasses.addMembers(
                    definition,
                    record.fields.map { it.name },
                    record.fields.map { ast.types[it.type] }
                )
                registry.register(definition)
            }
        }
        return registry
    }

    /**
     * Compiles the constructor of a record: a method on the program class that takes the field
     * values and returns a new record.
     *
     * @param cw The class writer to emit the method on.
     * @param jvmMethodName The JVM method name of the constructor.
     * @param descriptor The JVM descriptor of the constructor.
     * @param recordClassName Internal name of the record class.
     * @param fieldTypes The field types, in declaration order.
     */
    private fun compileRecordConstructor(
        cw: ClassWriter,
        jvmMethodName: String,
        descriptor: String,
        recordClassName: String,
        fieldTypes: List<ReturnType>
    ) {
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, jvmMethodName, descriptor, null, null)
        mv.visitCode()
        var slot = 1
        val slots = fieldTypes.map { type -> slot.also { slot += ASMUtil.getSlotsForType(type) } }
        RecordClasses.emitConstruction(mv, recordClassName, fieldTypes, slots)
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
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

        if (function.parameters.any { it.defaultValue != null }) {
            compileDefaultsMethod(
                function, ast, jvmMethodName, descriptor, className, cw, generatedInterfaces,
                functionRegistry, typeRegistry, fileScopePropertyRegistry, lambdaCounter, lambdaInterfaceRegistry
            )
        }
    }

    /**
     * Compiles the [DefaultsMethod] companion of a function with default values.
     *
     * The companion receives the function's parameters and a mask of the ones the call left out.
     * It evaluates the default value of each left-out parameter in declaration order, so a default
     * sees the final values of the parameters before it, and then calls the function.
     *
     * @param function The function to compile the companion for.
     * @param ast The [TypedAst] containing type information.
     * @param jvmMethodName The JVM method name of the function.
     * @param descriptor The JVM descriptor of the function.
     * @param className The JVM internal class name.
     * @param cw The shared [ClassWriter] to emit bytecode to.
     * @param generatedInterfaces Shared map for collecting generated functional interfaces.
     * @param functionRegistry The function registry for resolving function references.
     * @param typeRegistry The type registry for type lookups.
     * @param fileScopePropertyRegistry Registry for file-scope identifiers.
     * @param lambdaCounter Shared counter for generating unique lambda method names.
     * @param lambdaInterfaceRegistry Shared registry for lambda functional interfaces.
     */
    private fun compileDefaultsMethod(
        function: TypedFunction,
        ast: TypedAst,
        jvmMethodName: String,
        descriptor: String,
        className: String,
        cw: ClassWriter,
        generatedInterfaces: MutableMap<String, ByteArray>,
        functionRegistry: FunctionRegistry,
        typeRegistry: TypeRegistry,
        fileScopePropertyRegistry: GlobalPropertyRegistry,
        lambdaCounter: LambdaCounter,
        lambdaInterfaceRegistry: LambdaInterfaceRegistry
    ) {
        val mv = cw.visitMethod(
            Opcodes.ACC_PUBLIC,
            jvmMethodName + DefaultsMethod.SUFFIX,
            DefaultsMethod.descriptor(descriptor),
            null,
            null
        )

        val paramsScope = Scope(level = 2)
        val parameters = function.parameters.map { param -> paramsScope.declareVariable(param.name, ast.types[param.type]) }
        val mask = paramsScope.declareVariable(DEFAULTS_MASK_VARIABLE, ClassTypeRef("builtin", "int", false))

        val tempContext = CompilationContext(
            ast, className, expressionCompilers, statementCompilers, function.returnType,
            paramsScope, classWriter = cw, generatedInterfaces = generatedInterfaces,
            lambdaCounter = lambdaCounter, lambdaInterfaceRegistry = lambdaInterfaceRegistry,
            functionRegistry = functionRegistry, typeRegistry = typeRegistry,
            fileScopePropertyRegistry = fileScopePropertyRegistry
        )
        val scopeBuilder = ScopeBuilder(tempContext)
        scopeBuilder.buildDefaultValuesScope(function.parameters.mapNotNull { it.defaultValue }, paramsScope)
        LocalVariableIndexAssigner(tempContext).assignIndices(paramsScope, isStatic = false)

        val context = CompilationContext(
            ast, className, expressionCompilers, statementCompilers, function.returnType,
            paramsScope, scopeBuilder.statementScopes, classWriter = cw,
            generatedInterfaces = generatedInterfaces,
            lambdaCounter = lambdaCounter, lambdaInterfaceRegistry = lambdaInterfaceRegistry,
            functionRegistry = functionRegistry,
            typeRegistry = typeRegistry, fileScopePropertyRegistry = fileScopePropertyRegistry
        )

        mv.visitCode()
        for ((index, param) in function.parameters.withIndex()) {
            // Only the first parameters have a mask bit; a call never leaves out a later one.
            if (index >= DefaultsMethod.MAX_PARAMETERS) {
                break
            }
            val defaultValue = param.defaultValue ?: continue
            val keep = Label()
            mv.visitVarInsn(Opcodes.ILOAD, mask.slotIndex)
            mv.visitLdcInsn(1 shl index)
            mv.visitInsn(Opcodes.IAND)
            mv.visitJumpInsn(Opcodes.IFEQ, keep)
            val type = ast.types[param.type]
            context.compileExpression(defaultValue, mv, type)
            val descriptorType = ASMUtil.getTypeDescriptor(type)
            if (descriptorType.startsWith("L")) {
                // Frames merge the slot at the label below; give the value the parameter's
                // declared JVM type so they do not have to relate a generated class to it.
                mv.visitTypeInsn(Opcodes.CHECKCAST, descriptorType.substring(1, descriptorType.length - 1))
            }
            mv.visitVarInsn(ASMUtil.getStoreOpcode(type), parameters[index].slotIndex)
            mv.visitLabel(keep)
        }

        mv.visitVarInsn(Opcodes.ALOAD, 0)
        for ((index, param) in function.parameters.withIndex()) {
            mv.visitVarInsn(ASMUtil.getLoadOpcode(ast.types[param.type]), parameters[index].slotIndex)
        }
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, className, jvmMethodName, descriptor, false)
        val returnType = ast.types[function.returnType]
        mv.visitInsn(if (returnType is VoidType) Opcodes.RETURN else returnOpcode(returnType))

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

        // this.__ctx.getModel(), and the loader of the program, which also loads contributed classes
        // this.__ctx.getModel()
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
            "getModel",
            "()Lcom/mdeo/metamodel/Model;",
            true
        )
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getClassLoader", "()Ljava/lang/ClassLoader;", false)

        mv.visitMethodInsn(
            Opcodes.INVOKEINTERFACE,
            EXTERNAL_DISPATCHER_INTERNAL_NAME,
            "call",
            "(Ljava/lang/String;[Ljava/lang/Object;Lcom/mdeo/metamodel/Model;Ljava/lang/ClassLoader;)Ljava/lang/Object;",
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
     * @param contributedClasses The classes contributions define; each record gets a constructor.
     * @return Map from function name to [FunctionDefinitionImpl] containing all overloads.
     */
    private fun buildPluginFunctionDefs(
        pluginAst: TypedPluginAst?,
        pluginLookup: Map<String, Map<String, String>>,
        contributedClasses: Collection<ContributedClassSpec>
    ): Map<String, FunctionDefinitionImpl> {
        if (pluginAst == null) return emptyMap()
        val result = mutableMapOf<String, FunctionDefinitionImpl>()
        for (spec in contributedClasses) {
            if (spec.kind != TypedPluginClass.KIND_RECORD) continue
            val parameters = spec.fieldNames.mapIndexed { index, fieldName ->
                PluginFunctionParameter(name = fieldName, type = spec.fieldTypes[index])
            }
            val returnType = ClassTypeRef("${TypedPluginClass.PACKAGE_PREFIX}/${spec.contribution}", spec.name, false)
            result[spec.name] = FunctionDefinitionImpl(spec.name, CompiledProgram.SCRIPT_PROGRAM_INTERNAL_NAME).apply {
                addOverload(
                    PluginFunctionSignatureDefinition(
                        overloadKey = "",
                        descriptor = MethodDescriptorUtil.buildDescriptor(spec.fieldTypes, returnType),
                        ownerClass = CompiledProgram.SCRIPT_PROGRAM_INTERNAL_NAME,
                        jvmMethodName = pluginLookup.getValue(spec.name).getValue(""),
                        namedParameters = parameters,
                        returnType = returnType
                    )
                )
            }
        }
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
         * The JVM package the classes of the records scripts declare are generated in.
         */
        private const val RECORD_CLASS_PACKAGE = "com/mdeo/script/record"

        /**
         * The name of the local holding the mask of left-out parameters in a [DefaultsMethod]
         * companion. It cannot clash with a script identifier.
         */
        private const val DEFAULTS_MASK_VARIABLE = "\$defaults"

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