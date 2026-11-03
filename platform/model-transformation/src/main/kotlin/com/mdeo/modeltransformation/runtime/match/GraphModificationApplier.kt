package com.mdeo.modeltransformation.runtime.match

import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.expression.ast.types.GenericTypeRef
import com.mdeo.expression.ast.types.ValueType
import com.mdeo.metamodel.data.AssociationData
import com.mdeo.modeltransformation.ast.EdgeLabelUtils
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLinkElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstanceElement
import com.mdeo.modeltransformation.compiler.CompilationContext
import com.mdeo.modeltransformation.compiler.CompilationResult
import com.mdeo.modeltransformation.compiler.VariableBinding
import org.apache.tinkerpop.gremlin.process.traversal.GType
import org.apache.tinkerpop.gremlin.process.traversal.P
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.`__` as AnonymousTraversal
import org.apache.tinkerpop.gremlin.structure.Vertex
import org.apache.tinkerpop.gremlin.structure.VertexProperty

/**
 * Appends graph-modification steps (create vertices, update properties, create edges, delete)
 * to a traversal after the match and filter phases are complete.
 */
internal class GraphModificationApplier(
    private val elements: PatternCategories,
    private val expressionSupport: ExpressionSupport,
    private val compilationContext: CompilationContext
) {
    /**
     * Applies all modification steps in order:
     * 1. Create new vertices with properties.
     * 2. Update properties on existing matched instances.
     * 3. Create edges.
     * 4. Delete edges and vertices.
     *
     * @param matchedInstanceNames Names of instances already bound before modifications.
     */
    @Suppress("UNCHECKED_CAST")
    fun apply(
        traversal: GraphTraversal<Vertex, Map<String, Any>>,
        matchedInstanceNames: List<String>
    ): GraphTraversal<Vertex, Map<String, Any>> {
        var result = traversal
        result = addCreateVertexSteps(result, matchedInstanceNames)
        result = addPropertyUpdateSteps(result, matchedInstanceNames)
        val allInstanceNames = elements.allInstanceNames.toSet()
        result = addCreateEdgeSteps(result, allInstanceNames)
        result = addDeleteSteps(result)
        return result
    }

    /**
     * Adds `addV()` steps for each create instance and applies their property assignments.
     *
     * @param traversal The current traversal.
     * @param matchedInstanceNames Names of already-bound instances (for expression resolution).
     * @return The traversal with all create-vertex steps appended.
     */
    @Suppress("UNCHECKED_CAST")
    private fun addCreateVertexSteps(
        traversal: GraphTraversal<Vertex, Map<String, Any>>,
        matchedInstanceNames: List<String>
    ): GraphTraversal<Vertex, Map<String, Any>> {
        var result = traversal
        for (instance in elements.createInstances) {
            val className = instance.objectInstance.className
            val name = instance.objectInstance.name
            result = expressionSupport.engine.modelGraph.addVertexStep(
                result, className!!, VariableBinding.stepLabel(name))
            result = addVertexProperties(result, className, name, instance, matchedInstanceNames)
        }
        return result
    }

    /**
     * Adds `.property()` steps for all `=` properties on a newly created vertex.
     *
     * Simple scalar properties are set with `.property(key, value)` while collection-typed
     * properties use a `.sideEffect()` approach with `list` cardinality.
     *
     * @param traversal The current traversal positioned at the new vertex.
     * @param className The class name of the instance (for property key resolution).
     * @param instanceName The name of the instance being created.
     * @param instance The instance element containing property assignments.
     * @param matchedInstanceNames Names of already-bound instances (for expression resolution).
     * @return The traversal with property steps appended.
     */
    @Suppress("UNCHECKED_CAST")
    private fun addVertexProperties(
        traversal: GraphTraversal<Vertex, Map<String, Any>>,
        className: String?,
        instanceName: String,
        instance: TypedPatternObjectInstanceElement,
        matchedInstanceNames: List<String>
    ): GraphTraversal<Vertex, Map<String, Any>> {
        var result = traversal
        val props = instance.objectInstance.properties.filter { it.operator == "=" }
        val (listProps, simpleProps) = props.partition { property ->
            val t = expressionSupport.resolveExpressionType(property.value) ?: return@partition false
            expressionSupport.isCollectionType(t)
        }

        for (property in simpleProps) {
            val graphKey = expressionSupport.engine.resolvePropertyGraphKey(className, property.propertyName)
            val compiled = expressionSupport.compilePropertyExpression(property.value, matchedInstanceNames)!!
            val numericType = getNumericType(className, property.propertyName)

            result = if (compiled is CompilationResult.ValueResult) {
                if (compiled.value != null) {
                    result.property(graphKey, castConstant(compiled.value, numericType)) as GraphTraversal<Vertex, Map<String, Any>>
                } else {
                    result
                }
            } else {
                setScalarProperty(
                    result, instanceName, graphKey, compiled,
                    expressionSupport.resolveExpressionType(property.value), numericType, unsetOnNull = false
                )
            }
        }

        for (property in listProps) {
            val graphKey = expressionSupport.engine.resolvePropertyGraphKey(className, property.propertyName)
            val listResult = expressionSupport.compilePropertyExpression(property.value, matchedInstanceNames)!!
            val numericType = getNumericType(className, property.propertyName)

            @Suppress("UNCHECKED_CAST")
            val listTraversal = castTraversal(nonNull(listResult.traversal as GraphTraversal<Any, Any>), numericType)

            val valueLabel = compilationContext.getUniqueId()
            result = result.sideEffect(
                listTraversal.`as`(valueLabel)
                    .select<Any>(VariableBinding.stepLabel(instanceName))
                    .property(
                        VertexProperty.Cardinality.list, graphKey,
                        AnonymousTraversal.select<Any, Any>(valueLabel)
                    )
            ) as GraphTraversal<Vertex, Map<String, Any>>
        }
        return result
    }

    /**
     * Adds `.sideEffect(.property(...))` steps for `=` properties on matched instances.
     *
     * @param traversal The current traversal.
     * @param matchedInstanceNames Names of already-bound instances (for expression resolution).
     * @return The traversal with property update steps appended.
     */
    @Suppress("UNCHECKED_CAST")
    private fun addPropertyUpdateSteps(
        traversal: GraphTraversal<Vertex, Map<String, Any>>,
        matchedInstanceNames: List<String>
    ): GraphTraversal<Vertex, Map<String, Any>> {
        var result = traversal
        for (instance in elements.matchableInstances) {
            if (instance.objectInstance.modifier == "delete") { continue }
            result = addMatchedInstanceProperties(result, instance, matchedInstanceNames)
        }
        return result
    }

    /**
     * Adds property update steps for a single matched instance via `.sideEffect()`.
     *
     * @param traversal The current traversal.
     * @param instance The matched instance with `=` property assignments.
     * @param matchedInstanceNames Names of already-bound instances (for expression resolution).
     * @return The traversal with the instance's property updates appended.
     */
    @Suppress("UNCHECKED_CAST")
    private fun addMatchedInstanceProperties(
        traversal: GraphTraversal<Vertex, Map<String, Any>>,
        instance: TypedPatternObjectInstanceElement,
        matchedInstanceNames: List<String>
    ): GraphTraversal<Vertex, Map<String, Any>> {
        var result = traversal
        val name = instance.objectInstance.name
        val className = instance.objectInstance.className
        val stepLabel = VariableBinding.stepLabel(name)

        for (property in instance.objectInstance.properties) {
            if (property.operator != "=") { continue }
            val graphKey = expressionSupport.engine.resolvePropertyGraphKey(className, property.propertyName)
            val propType = expressionSupport.resolveExpressionType(property.value)

            if (expressionSupport.isCollectionType(propType)) {
                val listResult = expressionSupport.compilePropertyExpression(property.value, matchedInstanceNames)
                if (listResult != null) { 
                    result = setListPropertyViaSideEffect(result, name, graphKey, listResult, getNumericType(className, property.propertyName)) 
                }
            } else {
                val compiled = expressionSupport.compilePropertyExpression(property.value, matchedInstanceNames)!!
                val numericType = getNumericType(className, property.propertyName)
                result = if (compiled is CompilationResult.ValueResult) {
                    if (compiled.value != null) {
                        result.sideEffect(
                            AnonymousTraversal.select<Any, Any>(stepLabel).property(graphKey, castConstant(compiled.value, numericType))
                        ) as GraphTraversal<Vertex, Map<String, Any>>
                    } else {
                        // `= null` unsets the property
                        result.sideEffect(
                            AnonymousTraversal.select<Any, Any>(stepLabel).properties<Any>(graphKey).drop()
                        ) as GraphTraversal<Vertex, Map<String, Any>>
                    }
                } else {
                    setScalarProperty(result, name, graphKey, compiled, propType, numericType, unsetOnNull = true)
                }
            }
        }
        return result
    }

    /**
     * Sets the scalar property [graphKey] of the vertex [instanceName] to the value of a computed
     * expression, cast to [numericType]. An expression of a non-nullable type is assigned as
     * `select(x).property(key, value)`. One of a nullable type (an optional property, a ternary
     * with a `null` branch) may have no value or the value null; then the property is left unset,
     * and on a matched object ([unsetOnNull]) an existing value is removed:
     * `select(x).choose(value.is(neq(null)), property(key, value), properties(key).drop())`.
     * The value is tested and assigned by two copies of the expression rather than labelled once
     * and assigned from the label: an expression may end in a barrier (`size()` is a `count()`),
     * which drops the path, so `select(x)` after it would find nothing.
     *
     * @param traversal The current traversal.
     * @param instanceName The instance owning the property.
     * @param graphKey The resolved graph property key.
     * @param compiled The compiled value expression (a traversal result).
     * @param valueType The expression's type (null when unknown: handled as nullable).
     * @param numericType The property's numeric type the value is cast to, if any.
     * @param unsetOnNull Whether a null value removes an existing value (matched objects).
     * @return The traversal with the property assignment side-effect appended.
     */
    @Suppress("UNCHECKED_CAST")
    private fun setScalarProperty(
        traversal: GraphTraversal<Vertex, Map<String, Any>>,
        instanceName: String,
        graphKey: String,
        compiled: CompilationResult,
        valueType: ValueType?,
        numericType: GType?,
        unsetOnNull: Boolean
    ): GraphTraversal<Vertex, Map<String, Any>> {
        val vertex = AnonymousTraversal.select<Any, Any>(VariableBinding.stepLabel(instanceName))
        val value = compiled.traversal as GraphTraversal<Any, Any>
        val update = if (!isNullable(valueType)) {
            vertex.property(graphKey, castTraversal(value, numericType))
        } else {
            val present = nonNull(value.asAdmin().clone() as GraphTraversal<Any, Any>)
            val castValue = castTraversal(nonNull(value), numericType)
            if (unsetOnNull) {
                vertex.choose(
                    present,
                    AnonymousTraversal.start<Any>().property(graphKey, castValue),
                    AnonymousTraversal.properties<Vertex, Any>(graphKey).drop() as GraphTraversal<Any, Any>
                )
            } else {
                vertex.filter(present).property(graphKey, castValue)
            }
        }
        return traversal.sideEffect(update) as GraphTraversal<Vertex, Map<String, Any>>
    }

    /** Whether an expression of [type] may be null (an unknown type counts as nullable). */
    private fun isNullable(type: ValueType?): Boolean = when (type) {
        is ClassTypeRef -> type.isNullable
        is GenericTypeRef -> type.isNullable ?: true
        else -> true
    }

    /** Drops null values (an expression whose value is null assigns nothing). */
    @Suppress("UNCHECKED_CAST")
    private fun nonNull(traversal: GraphTraversal<Any, Any>): GraphTraversal<Any, Any> =
        traversal.`is`(P.neq(null)) as GraphTraversal<Any, Any>

    /**
     * Replaces a list-typed property using a side-effect that drops existing values
     * then re-adds them with `list` cardinality.
     *
     * @param traversal The current traversal.
     * @param instanceName The instance owning the property.
     * @param graphKey The resolved graph property key.
     * @param listResult The compiled list expression result.
     * @param numericType The property's numeric type the elements are cast to, if any.
     * @return The traversal with the list property replacement side-effects appended.
     */
    @Suppress("UNCHECKED_CAST")
    private fun setListPropertyViaSideEffect(
        traversal: GraphTraversal<Vertex, Map<String, Any>>,
        instanceName: String,
        graphKey: String,
        listResult: CompilationResult,
        numericType: GType?
    ): GraphTraversal<Vertex, Map<String, Any>> {
        val stepLabel = VariableBinding.stepLabel(instanceName)
        var result = traversal
        result = result.sideEffect(
            AnonymousTraversal.select<Any, Any>(stepLabel).properties<Any>(graphKey).drop()
        ) as GraphTraversal<Vertex, Map<String, Any>>

        @Suppress("UNCHECKED_CAST")
        val listTraversal = castTraversal(nonNull(listResult.traversal as GraphTraversal<Any, Any>), numericType)

        val valueLabel = compilationContext.getUniqueId()
        result = result.sideEffect(
            listTraversal.`as`(valueLabel)
                .select<Any>(stepLabel)
                .property(
                    VertexProperty.Cardinality.list, graphKey,
                    AnonymousTraversal.select<Any, Any>(valueLabel)
                )
        ) as GraphTraversal<Vertex, Map<String, Any>>
        return result
    }

    private fun findPropertyInHierarchy(className: String?, propertyName: String): com.mdeo.metamodel.data.PropertyData? {
        if (className == null) return null
        val classMap = expressionSupport.engine.metamodelData.classes.associateBy { it.name }
        fun find(cName: String): com.mdeo.metamodel.data.PropertyData? {
            val classData = classMap[cName] ?: return null
            val property = classData.properties.find { it.name == propertyName }
            if (property != null) return property
            for (parentName in classData.extends) {
                val parentProperty = find(parentName)
                if (parentProperty != null) return parentProperty
            }
            return null
        }
        return find(className)
    }

    /** The numeric type of a property (int, long, float, double), or null for other properties. */
    private fun getNumericType(className: String?, propertyName: String): GType? {
        val propData = findPropertyInHierarchy(className, propertyName) ?: return null
        return when (propData.primitiveType) {
            "int" -> GType.INT
            "long" -> GType.LONG
            "float" -> GType.FLOAT
            "double" -> GType.DOUBLE
            else -> null
        }
    }

    /** Casts a constant known at compile time to the property's numeric type, on the client. */
    private fun castConstant(value: Any, numericType: GType?): Any {
        if (numericType == null || value !is Number) return value
        return when (numericType) {
            GType.INT -> value.toInt()
            GType.LONG -> value.toLong()
            GType.FLOAT -> value.toFloat()
            else -> value.toDouble()
        }
    }

    /**
     * Casts every value of [traversal] to the property's numeric type with Gremlin's `asNumber`
     * step, not a lambda step: the engine compiles to pure Gremlin, so that it also runs against
     * remote graph databases.
     */
    @Suppress("UNCHECKED_CAST")
    private fun castTraversal(traversal: GraphTraversal<Any, Any>, numericType: GType?): GraphTraversal<Any, Any> =
        if (numericType == null) traversal else traversal.asNumber(numericType) as GraphTraversal<Any, Any>

    /**
     * Adds `.sideEffect(addE(...).to(...))` steps for each create link.
     *
     * @param traversal The current traversal.
     * @param currentPatternInstanceNames All instance names known in the current pattern.
     * @return The traversal with edge creation side-effects appended.
     */
    @Suppress("UNCHECKED_CAST")
    private fun addCreateEdgeSteps(
        traversal: GraphTraversal<Vertex, Map<String, Any>>,
        currentPatternInstanceNames: Set<String>
    ): GraphTraversal<Vertex, Map<String, Any>> {
        var result = traversal
        for (link in elements.createLinks) {
            val src = link.link.source.objectName
            val tgt = link.link.target.objectName
            val edgeLabel = EdgeLabelUtils.computeEdgeLabel(
                link.link.source.propertyName, link.link.target.propertyName
            )
            result = result.sideEffect(
                AnonymousTraversal.select<Any, Any>(VariableBinding.stepLabel(src))
                    .addE(edgeLabel)
                    .to(AnonymousTraversal.select<Any, Any>(VariableBinding.stepLabel(tgt)))
            ) as GraphTraversal<Vertex, Map<String, Any>>
        }
        return result
    }

    /**
     * Adds `.sideEffect(drop())` steps for delete links and delete instances.
     *
     * The association index is built once and shared across all delete-link steps
     * in this applier invocation so that per-link lookups remain O(1).
     *
     * @param traversal The current traversal.
     * @return The traversal with deletion side-effects appended.
     */
    @Suppress("UNCHECKED_CAST")
    private fun addDeleteSteps(
        traversal: GraphTraversal<Vertex, Map<String, Any>>
    ): GraphTraversal<Vertex, Map<String, Any>> {
        var result = traversal
        val assocByProps = expressionSupport.engine.metamodelData.associations
            .associateBy { it.source.name to it.target.name }
        for (link in elements.deleteLinks) {
            result = addDeleteEdgeStep(result, link, assocByProps)
        }
        for (instance in elements.deleteInstances) {
            result = result.sideEffect(
                AnonymousTraversal.select<Any, Any>(VariableBinding.stepLabel(instance.objectInstance.name)).drop()
            ) as GraphTraversal<Vertex, Map<String, Any>>
        }
        return result
    }

    /**
     * Adds a side-effect that drops the edge between two matched instances, choosing
     * the traversal direction based on metamodel multiplicity to minimise fan-out.
     *
     * The decision rule mirrors the composition-aware logic already used by
     * [ConditionTraversalUtils.orderLinksByBFS]:
     * - Look up the [AssociationData] for this link via the `(sourcePropName, targetPropName)` key.
     * - Compare the **source-end upper bound** (fan-out when starting from `src` via `outE`)
     *   with the **target-end upper bound** (fan-out when starting from `tgt` via `inE`).
     * - Traverse from the end whose upper bound is smaller (i.e. fewer edges to inspect).
     * - An unbounded multiplicity (`upper == -1`) is treated as [Int.MAX_VALUE].
     *
     * **Example**: deleting the `rooms` edge between a `House` (container, 1 side) and a
     * `Room` (item, 0..* side). The source end (`House.rooms`, upper = -1) is cheaper to
     * reach from `Room` via `inE` (upper = 1 on the target/`Room.house` side), so the
     * traversal becomes `tgt --inE→ where(outV == src) → drop()`.
     *
     * @param traversal The current traversal.
     * @param link The delete link element describing the edge to remove.
     * @param assocByProps Pre-built index of associations keyed by (sourcePropName, targetPropName).
     * @return The traversal with the edge deletion side-effect appended.
     */
    @Suppress("UNCHECKED_CAST")
    private fun addDeleteEdgeStep(
        traversal: GraphTraversal<Vertex, Map<String, Any>>,
        link: TypedPatternLinkElement,
        assocByProps: Map<Pair<String?, String?>, AssociationData>
    ): GraphTraversal<Vertex, Map<String, Any>> {
        val src = link.link.source.objectName
        val tgt = link.link.target.objectName
        val edgeLabel = EdgeLabelUtils.computeEdgeLabel(
            link.link.source.propertyName, link.link.target.propertyName
        )

        val assoc = assocByProps[link.link.source.propertyName to link.link.target.propertyName]
            ?: throw IllegalStateException(
                "No metamodel association found for link " +
                "'${link.link.source.propertyName}' → '${link.link.target.propertyName}'. " +
                "Every delete link must correspond to a declared association in the metamodel."
            )

        val srcUpper = assoc.source.multiplicity.upper
        val tgtUpper = assoc.target.multiplicity.upper
        val srcCost = if (srcUpper == -1) Int.MAX_VALUE else srcUpper
        val tgtCost = if (tgtUpper == -1) Int.MAX_VALUE else tgtUpper

        val sideEffectTraversal = if (tgtCost < srcCost) {
            AnonymousTraversal.select<Any, Any>(VariableBinding.stepLabel(tgt))
                .inE(edgeLabel)
                .where(AnonymousTraversal.outV().`as`(VariableBinding.stepLabel(src)))
                .drop()
        } else {
            AnonymousTraversal.select<Any, Any>(VariableBinding.stepLabel(src))
                .outE(edgeLabel)
                .where(AnonymousTraversal.inV().`as`(VariableBinding.stepLabel(tgt)))
                .drop()
        }

        return traversal.sideEffect(sideEffectTraversal) as GraphTraversal<Vertex, Map<String, Any>>
    }
}
