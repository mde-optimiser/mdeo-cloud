package com.mdeo.modeltransformation.runtime

import com.mdeo.metamodel.Metamodel
import com.mdeo.metamodel.data.AssociationData
import com.mdeo.metamodel.data.ClassData
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.expression.ast.types.ReturnType
import com.mdeo.modeltransformation.ast.EdgeLabelUtils
import com.mdeo.modeltransformation.ast.TypedAst
import com.mdeo.modeltransformation.ast.TypedTransformationParameter
import com.mdeo.modeltransformation.ast.statements.TypedTransformationStatement
import com.mdeo.modeltransformation.compiler.CollectionValue
import com.mdeo.modeltransformation.compiler.ExpressionCompilerRegistry
import com.mdeo.modeltransformation.compiler.VariableBinding
import com.mdeo.modeltransformation.runtime.statements.ExpressionValueEvaluator
import com.mdeo.expression.ast.types.ClassTypeRef
import org.apache.tinkerpop.gremlin.structure.Vertex
import com.mdeo.modeltransformation.compiler.registry.TypeRegistry
import com.mdeo.modeltransformation.compiler.registry.gremlinType
import com.mdeo.modeltransformation.graph.ModelGraph
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource

/**
 * Main entry point for executing model transformations.
 *
 * The TransformationEngine executes a [TypedAst] against the graph provided by a [ModelGraph].
 * It maintains execution state through a [TransformationExecutionContext] and delegates
 * statement execution to registered [StatementExecutor][StatementExecutorRegistry] instances.
 *
 * The engine processes statements sequentially, updating the context as bindings
 * are established through pattern matching. If a statement fails (e.g., a match
 * doesn't apply when required), the transformation terminates with a failure result.
 *
 * The [traversalSource] property is computed lazily from [modelGraph] on each access.
 * This ensures that if the underlying graph is rebuilt (e.g., via
 * [ModelGraph.resetNondeterminism]), subsequent traversals automatically operate on
 * the fresh graph without requiring an engine restart.
 *
 * @param modelGraph The model graph providing the graph traversal source and metamodel.
 * @param ast The TypedAst containing transformation statements.
 * @param expressionCompilerRegistry Registry for compiling expressions to GraphTraversal.
 * @param statementExecutorRegistry Registry for executing transformation statements.
 * @param deterministic When true (default), single-match statements use `limit(1)` which always
 *   selects the first candidate in traversal order. When false, the [ModelGraph] nondeterminism
 *   is reset before each match (see [ModelGraph.resetNondeterminism]), so each single-match step
 *   still uses `limit(1)` but operates on a freshly shuffled traversal order. Set to false for
 *   search-based optimisation runs where variety across iterations is desired.
 * @param callees The typed ASTs of the transformations [ast] may call, by absolute path. Defaults to
 *   the callees bundled in [ast]; the engine of a callee gets the root's map.
 * @param sharedTypeRegistry A type registry to reuse instead of building one. The registry depends
 *   only on the model graph's metamodel, so a callee reuses its caller's.
 * @param callDepth How many calls deep this engine runs (0 for the transformation that was started).
 */
class TransformationEngine(
    val modelGraph: ModelGraph,
    val ast: TypedAst,
    val expressionCompilerRegistry: ExpressionCompilerRegistry = ExpressionCompilerRegistry.createDefaultRegistry(),
    val statementExecutorRegistry: StatementExecutorRegistry = StatementExecutorRegistry.createDefaultRegistry(),
    val deterministic: Boolean = true,
    callees: Map<String, TypedAst>? = null,
    sharedTypeRegistry: TypeRegistry? = null,
    val callDepth: Int = 0
) {

    /**
     * The typed ASTs of every transformation this engine's transformation may call, by path.
     */
    val callees: Map<String, TypedAst> = callees ?: ast.callees

    /**
     * The Gremlin [GraphTraversalSource] obtained from [modelGraph] on each access.
     *
     * Because this is a computed property, it always returns a traversal source bound
     * to the current underlying graph — even if [ModelGraph.resetNondeterminism] has
     * replaced the internal graph since engine construction.
     */
    val traversalSource: GraphTraversalSource get() = modelGraph.traversal()

    /**
     * The compiled metamodel derived from [modelGraph].
     *
     * Because this is a computed property, it always reflects the metamodel of the
     * ModelGraph. In practice, the metamodel is constant for the lifetime of the engine.
     */
    val metamodel: Metamodel get() = modelGraph.metamodel

    /**
     * The metamodel data, derived from the compiled [metamodel].
     *
     * This is a computed property; it re-evaluates on each access but in practice the
     * metamodel (and its data) are constant for the engine's lifetime.
     */
    val metamodelData: MetamodelData get() = metamodel.data

    /**
     * The instance-name registry owned by [modelGraph].
     *
     * Reusing the graph's registry keeps input-model names, created-node registrations, and
     * graph-to-model conversion in sync throughout a transformation run.
     */
    val instanceNameRegistry: InstanceNameRegistry get() = modelGraph.nameRegistry

    /**
     * The types list from the currently executing TypedAst.
     * This is populated from the AST provided in the constructor.
     */
    val types: List<ReturnType> = ast.types

    /**
     * Dynamic type registry that includes both standard library types and metamodel types.
     *
     * This registry is built during initialization using the MetamodelData. It uses the GLOBAL
     * registry as a parent, so all stdlib types remain available.
     *
     * Metamodel types are registered with package prefixes:
     * - "class/path/to/metamodel.ClassName" for metamodel classes
     * - "enum/path/to/metamodel.EnumName" for enum value types
     * - "enum-container/path/to/metamodel.EnumName" for enum container types
     */
    val typeRegistry: TypeRegistry

    /**
     * The class package prefix derived from the metamodel path. Classes of files the metamodel
     * imports are named after their own files instead.
     * Example: if metamodelPath is "/project/models/house", classPackage is "class/project/models/house"
     */
    val classPackage: String = "class${ast.metamodelPath}"

    /**
     * The enum package prefix derived from the metamodel path.
     * Example: if metamodelPath is "/project/models/house", enumPackage is "enum/project/models/house"
     */
    val enumPackage: String = "enum${ast.metamodelPath}"

    /**
     * The enum container package prefix derived from the metamodel path.
     * Example: if metamodelPath is "/project/models/house", enumContainerPackage is "enum-container/project/models/house"
     */
    val enumContainerPackage: String = "enum-container${ast.metamodelPath}"

    init {
        typeRegistry = sharedTypeRegistry ?: createTypeRegistry(metamodelData)
    }

    /**
     * Executes a complete model transformation.
     *
     * This is the main entry point for running a transformation. It processes
     * all statements in the TypedAst sequentially, maintaining state throughout
     * the execution. Every input takes its default (a transformation with a required input
     * fails); see the overload for passing inputs.
     *
     * @return The result of the transformation execution.
     */
    fun execute(): TransformationExecutionResult = execute(emptyMap())

    /**
     * Executes the transformation as a top-level run with inputs: values for scalar, enum and
     * collection inputs (an operator's arguments from a configuration) and models for its model
     * inputs (the donors of a crossover).
     *
     * [arguments] are bound exactly as the arguments of a `call`, at the root scope before the
     * first statement; inputs not passed take their default, or null when optional. A value is a
     * `String`, `Boolean`, number (converted to the input's `int`, `long`, `float` or `double`,
     * where an `int`/`long` input takes integral numbers only), [EnumLiteral], or for a `List<T>`/
     * `Set<T>` input any [Iterable] of those (a set keeps one of equal values).
     *
     * [models] are loaded into this engine's graph for the run, each into a model-input slot of
     * its own ([ModelGraph.loadModelInput]), in the order of the declarations and of a list's
     * models; the inputs are bound to their [ModelSlot]s. They are removed again when the run
     * ends — by success, failure, `stop`, `kill` or an exception — so that afterwards the graph
     * holds the changed implicit model alone, with no vertex, edge, name or statistic of the
     * inputs ([ModelGraph.unloadModelInputs]). While they are loaded, every match filters the
     * objects it scans for by model; without model inputs no such step is emitted.
     *
     * @param arguments Values of scalar, enum and collection inputs, by name.
     * @param models The models of the model inputs, by name: a [ModelArgument.Single] for
     *   `in x: model`, a [ModelArgument.Many] for `in xs: List<model>`. Every model input must be
     *   passed (otherwise the run fails, as for any required input).
     * @return The result of the transformation execution.
     * @throws IllegalArgumentException When an argument names no input of the transformation,
     *   names an element, model or output parameter, or does not fit the input's type; when a
     *   model argument names no model input or has the wrong form.
     * @throws IllegalStateException When model inputs are already loaded into the graph.
     */
    fun execute(
        arguments: Map<String, Any?>,
        models: Map<String, ModelArgument> = emptyMap()
    ): TransformationExecutionResult {
        val inputs = argumentBindings(arguments)
        val modelArguments = modelArguments(models)
        if (modelArguments.isEmpty()) {
            return run(inputs).result
        }
        check(modelGraph.modelInputSlots.isEmpty()) {
            "The graph already holds model inputs ${modelGraph.modelInputSlots}; runs with model inputs do not nest"
        }
        try {
            return run(inputs + loadModelInputs(modelArguments)).result
        } finally {
            modelGraph.unloadModelInputs()
        }
    }

    /**
     * Checks [arguments] against the signature and converts them to the bindings a call would
     * pass: numbers to the input's type, enum literals to their runtime value, collections to a
     * [CollectionValue] of the input's kind.
     */
    private fun argumentBindings(arguments: Map<String, Any?>): Map<String, VariableBinding> {
        if (arguments.isEmpty()) return emptyMap()
        return arguments.mapValues { (name, value) ->
            val parameter = inputParameter(name)
            require(!parameter.isElement && !parameter.elementsAreElements && !parameter.isModel) {
                "Input '$name' of '${displayName}' takes model elements or models; it cannot be passed a value"
            }
            val type = getTypeOrNull(parameter.type) as? ClassTypeRef
            val converted = when {
                value == null -> null
                parameter.isCollection -> {
                    require(value is Iterable<*>) { "Input '$name' of '$displayName' is a collection, not $value" }
                    val elementType = type?.typeArgs?.values?.firstOrNull() as? ClassTypeRef
                    CollectionValue(parameter.collection == "Set", value.map { argumentValue(name, it, elementType) })
                }
                else -> argumentValue(name, value, type)
            }
            VariableBinding.ValueBinding(converted)
        }
    }

    /**
     * Converts the value of an argument (or of one element of a collection argument) to the
     * runtime value of [type].
     */
    private fun argumentValue(name: String, value: Any?, type: ClassTypeRef?): Any? {
        if (value == null || type == null) {
            return (value as? EnumLiteral)?.runtimeValue ?: value
        }
        fun mismatch(): Nothing =
            throw IllegalArgumentException("Input '$name' of '$displayName' is of type ${type.type}, not $value")
        if (type.`package`.startsWith("enum")) {
            return when (value) {
                is EnumLiteral -> if (value.enumName == type.type) value.runtimeValue else mismatch()
                is String -> value
                else -> mismatch()
            }
        }
        if (type.`package` != "builtin") {
            return value
        }
        return when (type.type) {
            "int", "long" -> if (value is Int || value is Long || value is Short || value is Byte) {
                if (type.type == "int") (value as Number).toInt() else (value as Number).toLong()
            } else {
                mismatch()
            }
            "float" -> (value as? Number)?.toFloat() ?: mismatch()
            "double" -> (value as? Number)?.toDouble() ?: mismatch()
            "string" -> value as? String ?: mismatch()
            "boolean" -> value as? Boolean ?: mismatch()
            else -> value
        }
    }

    /**
     * The input parameter [name] of the signature.
     *
     * @throws IllegalArgumentException When the transformation has no such input.
     */
    private fun inputParameter(name: String): TypedTransformationParameter =
        ast.signature?.parameters?.firstOrNull { it.isInput && it.name == name }
            ?: throw IllegalArgumentException("'$displayName' has no input '$name'")

    /** The transformation's name for messages: its file's base name, if known. */
    private val displayName: String get() = ast.signature?.name ?: "transformation"

    /**
     * Checks [models] against the model inputs of the signature and orders them by declaration.
     */
    private fun modelArguments(models: Map<String, ModelArgument>): List<Pair<TypedTransformationParameter, ModelArgument>> {
        if (models.isEmpty()) return emptyList()
        for ((name, argument) in models) {
            val parameter = inputParameter(name)
            require(parameter.isModel) { "Input '$name' of '$displayName' is not a model input" }
            require((argument is ModelArgument.Many) == parameter.isCollection) {
                "Input '$name' of '$displayName' takes " + if (parameter.isCollection) "a list of models" else "one model"
            }
        }
        return ast.signature!!.parameters.mapNotNull { parameter -> models[parameter.name]?.let { parameter to it } }
    }

    /**
     * Loads the models of [arguments] into slots 1, 2, … of the graph and returns the bindings of
     * their inputs: a [ModelSlot], or a list of them.
     */
    private fun loadModelInputs(
        arguments: List<Pair<TypedTransformationParameter, ModelArgument>>
    ): Map<String, VariableBinding> {
        var nextSlot = ModelGraph.IMPLICIT_MODEL_SLOT + 1
        return arguments.associate { (parameter, argument) ->
            val slots = argument.models.map { model ->
                ModelSlot(nextSlot++).also { modelGraph.loadModelInput(it.slot, model) }
            }
            val value: Any = if (parameter.isCollection) CollectionValue(false, slots) else slots.single()
            parameter.name to VariableBinding.ValueBinding(value)
        }
    }

    /**
     * Runs this engine's transformation as a callee: with its inputs bound, and with `stop`
     * returning from the callee (a success with the changes made up to the `stop`) instead of
     * ending the caller.
     *
     * @param inputs The bindings of the inputs that were passed; the others take their defaults.
     * @return The callee's result and, on success, the bindings of its outputs.
     */
    fun executeCall(inputs: Map<String, VariableBinding>): CallOutcome {
        val run = run(inputs)
        val result = when (val result = run.result) {
            is TransformationExecutionResult.Stopped ->
                if (result.isNormalStop) run.accumulated ?: TransformationExecutionResult.Success.empty() else result
            else -> result
        }
        val outputs = if (result is TransformationExecutionResult.Success) readOutputs(run.context) else emptyMap()
        return CallOutcome(result, outputs)
    }

    /**
     * Creates the engine that runs the callee at [path] on the same model graph, or null when no
     * callee of that path was bundled.
     *
     * @param path The absolute path of the callee's file.
     * @return The callee's engine.
     */
    fun calleeEngine(path: String): TransformationEngine? {
        val calleeAst = callees[path] ?: return null
        return TransformationEngine(
            modelGraph = modelGraph,
            ast = calleeAst,
            expressionCompilerRegistry = expressionCompilerRegistry,
            statementExecutorRegistry = statementExecutorRegistry,
            deterministic = deterministic,
            callees = callees,
            sharedTypeRegistry = typeRegistry,
            callDepth = callDepth + 1
        )
    }

    /**
     * The result of a call: the callee's result and the bindings of its outputs.
     *
     * @param result Success (also after `stop`), Failure, or Stopped for `kill`.
     * @param outputs The outputs by parameter name; empty unless the call succeeded.
     */
    data class CallOutcome(
        val result: TransformationExecutionResult,
        val outputs: Map<String, VariableBinding>
    )

    /**
     * What one run of the statements left: its result, what the statements before an ending
     * `stop` changed, and the root context with the parameters.
     */
    private data class Run(
        val result: TransformationExecutionResult,
        val accumulated: TransformationExecutionResult.Success?,
        val context: TransformationExecutionContext
    )

    /**
     * Runs the statements in a root context with the parameters bound.
     */
    private fun run(inputs: Map<String, VariableBinding>): Run {
        val context = TransformationExecutionContext.emptyWithEnums(metamodelData.enums.map { it.name })
        bindParameters(inputs, context)?.let { failure -> return Run(failure, null, context) }
        var accumulatedResult: TransformationExecutionResult.Success? = null

        for (statement in ast.statements) {
            val result = executeStatement(statement, context)

            when (result) {
                is TransformationExecutionResult.Success -> {
                    accumulatedResult = result.merge(accumulatedResult)
                }
                is TransformationExecutionResult.Failure -> return Run(result.merge(accumulatedResult), accumulatedResult, context)
                is TransformationExecutionResult.Stopped -> return Run(result, accumulatedResult, context)
            }
        }

        return Run(accumulatedResult ?: TransformationExecutionResult.Success.empty(), accumulatedResult, context)
    }

    /**
     * Binds the parameters of the signature at the root scope: every input to its argument or,
     * when none was passed, its default, and every output to null.
     *
     * @return A failure when a required input was not passed, else null.
     */
    private fun bindParameters(
        inputs: Map<String, VariableBinding>,
        context: TransformationExecutionContext
    ): TransformationExecutionResult.Failure? {
        val signature = ast.signature ?: return null
        val scope = context.variableScope
        for (parameter in signature.parameters) {
            if (parameter.isOutput) {
                val initial = if (parameter.isCollection) CollectionValue(parameter.collection == "Set") else null
                scope.setBinding(parameter.name, VariableBinding.ValueBinding(initial))
                continue
            }
            val passed = inputs[parameter.name]?.takeUnless { parameter.pinsNode && it.isNull() }
            val binding = when {
                passed != null -> passed
                parameter.defaultValue != null ->
                    ExpressionValueEvaluator(this).evaluate(parameter.defaultValue, context)
                // an input that pins an object of the first pattern stays unbound: the pattern
                // then matches the object freely
                parameter.pinsNode -> continue
                parameter.optional -> VariableBinding.ValueBinding(null)
                else -> return TransformationExecutionResult.Failure(
                    reason = "Required input '${parameter.name}' of '${signature.name}' was not passed",
                    failedAt = signature.name,
                    isDeterministic = true,
                    changesWereMade = false
                )
            }
            scope.setBinding(parameter.name, coerce(binding, parameter))
        }
        return null
    }

    /**
     * Whether a binding holds no element: a null value or an instance binding without a vertex.
     */
    private fun VariableBinding.isNull(): Boolean = when (this) {
        is VariableBinding.ValueBinding -> value == null
        is VariableBinding.InstanceBinding -> vertexRef == null
        is VariableBinding.LabelBinding -> false
    }

    /**
     * Whether the type at [typeIndex] of this engine's types is a collection (a type of list or
     * set cardinality, as `List<T>`, `Set<T>` or `Collection<T>`).
     *
     * @param typeIndex An index into [types].
     * @return True for collection types.
     */
    fun isCollectionType(typeIndex: Int): Boolean {
        val type = getTypeOrNull(typeIndex) as? ClassTypeRef ?: return false
        val cardinality = typeRegistry.getType(type)?.cardinality
        return cardinality == org.apache.tinkerpop.gremlin.structure.VertexProperty.Cardinality.list ||
            cardinality == org.apache.tinkerpop.gremlin.structure.VertexProperty.Cardinality.set
    }

    /**
     * Whether the type at [typeIndex] is a set type (`Set<T>`, `OrderedSet<T>`).
     *
     * @param typeIndex An index into [types].
     * @return True for set types.
     */
    fun isSetType(typeIndex: Int): Boolean {
        val type = getTypeOrNull(typeIndex) as? ClassTypeRef ?: return false
        return type.`package` == "builtin" && (type.type == "Set" || type.type == "OrderedSet" || type.type == "ReadonlySet")
    }

    /**
     * Converts a scalar input to the parameter's primitive type (an `int` argument of a `long`
     * parameter becomes a Long), so the callee compares values of the type it declares.
     */
    private fun coerce(binding: VariableBinding, parameter: TypedTransformationParameter): VariableBinding {
        if (binding !is VariableBinding.ValueBinding) {
            return binding
        }
        val collection = binding.value as? CollectionValue
        if (parameter.isCollection) {
            // a callee works on a collection of its own kind; it never changes its inputs
            val isSet = parameter.collection == "Set"
            return VariableBinding.ValueBinding(
                when {
                    collection == null -> CollectionValue(isSet)
                    collection.isSet == isSet -> collection
                    else -> collection.copy(isSet)
                }
            )
        }
        return VariableBinding.ValueBinding(coerceNumber(binding.value, parameter.type))
    }

    /**
     * Converts a number to the primitive type at [typeIndex] (`int`, `long`, `float` or
     * `double`): arithmetic yields doubles, while a value of an `int` type should be an Int.
     * Other values are returned as they are.
     *
     * @param value The value.
     * @param typeIndex An index into [types].
     * @return The converted value.
     */
    fun coerceNumber(value: Any?, typeIndex: Int): Any? {
        if (value !is Number) {
            return value
        }
        val type = (getTypeOrNull(typeIndex) as? ClassTypeRef)?.takeIf { it.`package` == "builtin" }?.type
        return when (type) {
            "int" -> value.toInt()
            "long" -> value.toLong()
            "float" -> value.toFloat()
            "double" -> value.toDouble()
            else -> value
        }
    }

    /**
     * Reads the outputs from the root scope after a successful run. An element output assigned
     * with `out = node` holds the node's vertex, which becomes an instance binding.
     */
    private fun readOutputs(context: TransformationExecutionContext): Map<String, VariableBinding> {
        val signature = ast.signature ?: return emptyMap()
        val scope = context.variableScope
        return signature.parameters.filter { it.isOutput }.associate { parameter ->
            val binding: VariableBinding = when (val bound = scope.getLocalBinding(parameter.name)) {
                is VariableBinding.InstanceBinding -> VariableBinding.InstanceBinding(bound.vertexRef)
                is VariableBinding.ValueBinding -> {
                    val value = bound.value
                    when (value) {
                        is Vertex -> VariableBinding.InstanceBinding(modelGraph.createVertexRef(value.id()))
                        is CollectionValue -> VariableBinding.ValueBinding(value.copy())
                        else -> VariableBinding.ValueBinding(coerceNumber(value, parameter.type))
                    }
                }
                else -> VariableBinding.ValueBinding(null)
            }
            parameter.name to binding
        }
    }

    /**
     * Executes a single transformation statement.
     *
     * This method delegates to the appropriate StatementExecutor based on the
     * statement type. If no executor is registered for the statement type,
     * a failure result is returned.
     *
     * @param statement The statement to execute.
     * @param context The current execution context.
     * @return The result of executing the statement.
     */
    fun executeStatement(
        statement: TypedTransformationStatement,
        context: TransformationExecutionContext
    ): TransformationExecutionResult {
        return statementExecutorRegistry.execute(statement, context, this)
    }

    /**
     * Executes a block of statements sequentially.
     *
     * Statements are executed in order. If any statement fails or stops,
     * execution terminates and that result is returned. Otherwise, results
     * are accumulated and returned as a merged Success.
     *
     * @param statements The statements to execute.
     * @param context The initial execution context.
     * @return The combined result of executing all statements.
     */
    fun executeBlock(
        statements: List<TypedTransformationStatement>,
        context: TransformationExecutionContext
    ): TransformationExecutionResult {
        val currentContext = context.enterScope()
        var accumulatedResult: TransformationExecutionResult.Success? = null

        for (statement in statements) {
            val result = executeStatement(statement, currentContext)

            when (result) {
                is TransformationExecutionResult.Success -> {
                    accumulatedResult = result.merge(accumulatedResult)
                }
                is TransformationExecutionResult.Failure -> return result.merge(accumulatedResult)
                is TransformationExecutionResult.Stopped -> return result
            }
        }

        return accumulatedResult ?: TransformationExecutionResult.Success.empty()
    }

    /**
     * Returns the type at the given index in the types array.
     *
     * @param typeIndex The index of the type to retrieve.
     * @return The ReturnType at the specified index.
     * @throws IndexOutOfBoundsException If the index is out of bounds.
     */
    fun getType(typeIndex: Int): ReturnType {
        return types[typeIndex]
    }

    /**
     * Returns the type at the given index, or null if out of bounds.
     *
     * @param typeIndex The index of the type to retrieve.
     * @return The ReturnType at the specified index, or null if invalid.
     */
    fun getTypeOrNull(typeIndex: Int): ReturnType? {
        return types.getOrNull(typeIndex)
    }

    /**
     * Creates a dynamic type registry including metamodel classes and enums.
     * 
     * Type references use the following formats:
     * - "class/path/to/metamodel.ClassName" for metamodel classes
     * - "enum/path/to/metamodel.EnumName" for enum value types (stored on properties)
     * - "enum-container/path/to/metamodel.EnumName" for enum container types (the singleton for accessing enum values)
     *
     * The path is that of the metamodel file declaring the class or enum ([MetamodelData.pathOf]):
     * for the classes and enums of a file the metamodel imports, that file's.
     *
     * @param metamodelData The metamodel data containing class, enum, and association definitions.
     */
    private fun createTypeRegistry(metamodelData: MetamodelData): TypeRegistry {
        val dynamicRegistry = TypeRegistry(parent = TypeRegistry.GLOBAL)

        registerEnumTypes(dynamicRegistry, metamodelData)

        val classAssociations = buildClassAssociationsMap(metamodelData.associations)

        val classMap = metamodelData.classes.associateBy { it.name }
        for (classData in metamodelData.classes) {
            val builder = gremlinType("class${metamodelData.pathOf(classData)}", classData.name)

            for (superClass in classData.extends) {
                val superPath = classMap[superClass]?.let { metamodelData.pathOf(it) } ?: metamodelData.path
                builder.extends("class$superPath", superClass)
            }

            for (property in classData.properties) {
                val graphKey = resolvePropertyGraphKey(classData.name, property.name)
                builder.graphProperty(property.name, graphKey)
            }

            val associations = classAssociations[classData.name] ?: emptyList()
            for ((assoc, isSource) in associations) {
                val thisEnd = if (isSource) assoc.source else assoc.target
                val otherEnd = if (isSource) assoc.target else assoc.source
                
                val propertyName = thisEnd.name ?: continue
                
                val edgeLabel = EdgeLabelUtils.computeEdgeLabel(assoc.source.name, assoc.target.name)
                builder.association(
                    propertyName = propertyName,
                    edgeLabel = edgeLabel,
                    isOutgoing = isSource
                )
            }

            dynamicRegistry.register(builder.build())
        }

        return dynamicRegistry
    }

    /**
     * Registers enum types in the type registry.
     * 
     * For each enum in the metamodel, two types are registered:
     * 
     * 1. "enum-container/path/to/metamodel.EnumName" - The container type (singleton) used to access enum values.
     *    Each entry is a property that returns a constant string in the format `EnumName`.`entryName`.
     *    Accessing an entry like `EnumName.entryName` produces this string literal.
     * 
     * 2. "enum/path/to/metamodel.EnumName" - The actual enum value type, used when a property has an enum type.
     *    This represents the stored enum value on an object property.
     * 
     * @param registry The registry to add enum types to.
     * @param metamodelData The metamodel data containing enum definitions.
     */
    private fun registerEnumTypes(registry: TypeRegistry, metamodelData: MetamodelData) {
        for (enumData in metamodelData.enums) {
            val enumName = enumData.name
            val enumPath = metamodelData.pathOf(enumData)

            val containerBuilder = gremlinType("enum-container$enumPath", enumName)
            for (entryName in enumData.entries) {
                val enumValueString = "`$enumName`.`$entryName`"
                containerBuilder.enumEntry(entryName, enumValueString)
            }
            registry.register(containerBuilder.build())

            val valueTypeBuilder = gremlinType("enum$enumPath", enumName)
            registry.register(valueTypeBuilder.build())
        }
    }

    /**
     * Builds a map from class name to list of (association, isSource) pairs.
     * isSource is true if the class is the source end of the association.
     */
    private fun buildClassAssociationsMap(associations: List<AssociationData>): Map<String, List<Pair<AssociationData, Boolean>>> {
        val result = mutableMapOf<String, MutableList<Pair<AssociationData, Boolean>>>()
        
        for (assoc in associations) {
            result.getOrPut(assoc.source.className) { mutableListOf() }
                .add(assoc to true)
            
            result.getOrPut(assoc.target.className) { mutableListOf() }
                .add(assoc to false)
        }
        
        return result
    }

    /**
     * Property names that different classes store under different graph keys: for these, a
     * property of an object without a class (a reference to a bound object) cannot be resolved
     * by name ([resolvePropertyGraphKey] would take the first class that has it).
     */
    private val ambiguousPropertyNames: Set<String> by lazy {
        metamodel.metadata.classes.values
            .flatMap { classMeta -> classMeta.propertyFields.map { (name, field) -> name to field.fieldIndex } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.toSet().size > 1 }
            .keys
    }

    /** Whether [propertyName] maps to different graph keys in different classes. */
    fun isAmbiguousPropertyName(propertyName: String): Boolean = propertyName in ambiguousPropertyNames

    /**
     * Property names that are an int, long, float or double in some class: a value assigned to
     * such a property is cast to the property's type, so for an object without a class (a
     * reference to a bound object) the cast needs the bound element's class.
     */
    private val numericPropertyNames: Set<String> by lazy {
        metamodelData.classes
            .flatMap { classData -> classData.properties.filter { it.primitiveType in NUMERIC_TYPES }.map { it.name } }
            .toSet()
    }

    /** Whether [propertyName] is a numeric property (int, long, float, double) of some class. */
    fun isNumericPropertyName(propertyName: String): Boolean = propertyName in numericPropertyNames

    /**
     * Resolves the graph property key for a given class and property name.
     *
     * When the metamodel has metadata for the class, the graph key is `prop_X` where X
     * is the field index from the compiled instance class. When [className] is null or
     * not found in the metadata, falls back to a best-effort search across all classes
     * and ultimately returns the property name unchanged.
     *
     * @param className The metamodel class name, or null for untyped instances.
     * @param propertyName The logical property name.
     * @return The graph key (e.g. "prop_2") or the original property name as fallback.
     */
    fun resolvePropertyGraphKey(className: String?, propertyName: String): String {
        if (className != null) {
            val mapping = metamodel.metadata.classes[className]?.propertyFields?.get(propertyName)
            if (mapping != null) return "prop_${mapping.fieldIndex}"
        }
        for ((_, classMeta) in metamodel.metadata.classes) {
            val mapping = classMeta.propertyFields[propertyName]
            if (mapping != null) return "prop_${mapping.fieldIndex}"
        }
        return propertyName
    }

    companion object {
        private val NUMERIC_TYPES = setOf("int", "long", "float", "double")

        /**
         * Creates a [TransformationEngine] with default registries.
         *
         * @param modelGraph The model graph providing both the traversal source and the metamodel.
         * @param ast The [TypedAst] to execute.
         * @param deterministic When true (default) single-match steps use `limit(1)`; when false
         *   the model graph nondeterminism is reset before each match so that `limit(1)` operates
         *   on a freshly shuffled traversal order.
         * @return A new [TransformationEngine] with default configuration.
         */
        fun create(
            modelGraph: ModelGraph,
            ast: TypedAst,
            deterministic: Boolean = true
        ): TransformationEngine {
            return TransformationEngine(
                modelGraph = modelGraph,
                ast = ast,
                expressionCompilerRegistry = ExpressionCompilerRegistry.createDefaultRegistry(),
                statementExecutorRegistry = StatementExecutorRegistry.createDefaultRegistry(),
                deterministic = deterministic
            )
        }
    }
}
