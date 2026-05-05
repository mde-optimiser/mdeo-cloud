package com.mdeo.modeltransformation.runtime

import com.mdeo.metamodel.Metamodel
import com.mdeo.metamodel.data.AssociationData
import com.mdeo.metamodel.data.ClassData
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.expression.ast.types.ReturnType
import com.mdeo.modeltransformation.ast.EdgeLabelUtils
import com.mdeo.modeltransformation.ast.TypedAst
import com.mdeo.modeltransformation.ast.statements.TypedTransformationStatement
import com.mdeo.modeltransformation.compiler.ExpressionCompilerRegistry
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
 */
class TransformationEngine(
    val modelGraph: ModelGraph,
    val ast: TypedAst,
    val expressionCompilerRegistry: ExpressionCompilerRegistry = ExpressionCompilerRegistry.createDefaultRegistry(),
    val statementExecutorRegistry: StatementExecutorRegistry = StatementExecutorRegistry.createDefaultRegistry(),
    val deterministic: Boolean = true
) {

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
     * The class package prefix derived from the metamodel path.
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
        typeRegistry = createTypeRegistry(metamodelData)
    }

    /**
     * Executes a complete model transformation.
     *
     * This is the main entry point for running a transformation. It processes
     * all statements in the TypedAst sequentially, maintaining state throughout
     * the execution.
     *
     * @return The result of the transformation execution.
     */
    fun execute(): TransformationExecutionResult {
        var context = TransformationExecutionContext.emptyWithEnums(metamodelData.enums.map { it.name })
        var accumulatedResult = TransformationExecutionResult.Success()

        for (statement in ast.statements) {
            val result = executeStatement(statement, context)

            when (result) {
                is TransformationExecutionResult.Success -> {
                    accumulatedResult = accumulatedResult.merge(result)
                }
                is TransformationExecutionResult.Failure -> {
                    // A failure is deterministic when no graph modifications have occurred yet:
                    // the match found no candidates in the current model state, and since the
                    // graph is unchanged, retrying will always produce the same outcome.
                    val noChangesSoFar = accumulatedResult.createdNodes.isEmpty() && accumulatedResult.deletedNodes.isEmpty()
                    return if (noChangesSoFar) result.copy(isDeterministic = true) else result
                }
                is TransformationExecutionResult.Stopped -> return result
            }
        }

        return accumulatedResult
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
        var accumulatedResult = TransformationExecutionResult.Success()

        for (statement in statements) {
            val result = executeStatement(statement, currentContext)

            when (result) {
                is TransformationExecutionResult.Success -> {
                    accumulatedResult = accumulatedResult.merge(result)
                }
                is TransformationExecutionResult.Failure -> return result
                is TransformationExecutionResult.Stopped -> return result
            }
        }

        return accumulatedResult
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
     * @param metamodelData The metamodel data containing class, enum, and association definitions.
     */
    private fun createTypeRegistry(metamodelData: MetamodelData): TypeRegistry {
        val dynamicRegistry = TypeRegistry(parent = TypeRegistry.GLOBAL)

        registerEnumTypes(dynamicRegistry, metamodelData)

        val classAssociations = buildClassAssociationsMap(metamodelData.associations)

        for (classData in metamodelData.classes) {
            val builder = gremlinType(classPackage, classData.name)

            for (superClass in classData.extends) {
                builder.extends(classPackage, superClass)
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
                val oppositePropertyName = otherEnd.name
                
                val edgeLabel = EdgeLabelUtils.computeEdgeLabel(propertyName, oppositePropertyName)
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

            val containerBuilder = gremlinType(enumContainerPackage, enumName)
            for (entryName in enumData.entries) {
                val enumValueString = "`$enumName`.`$entryName`"
                containerBuilder.enumEntry(entryName, enumValueString)
            }
            registry.register(containerBuilder.build())

            val valueTypeBuilder = gremlinType(enumPackage, enumName)
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
