package com.mdeo.modeltransformation.runtime.match

import com.mdeo.metamodel.data.AssociationData
import com.mdeo.modeltransformation.ast.EdgeLabelUtils
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLinkElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstanceElement
import com.mdeo.modeltransformation.compiler.CompilationContext
import com.mdeo.modeltransformation.compiler.CompilationResult
import com.mdeo.modeltransformation.compiler.VariableBinding
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
            result = if (compiled is CompilationResult.ValueResult) {
                if (compiled.value != null) {
                    result.property(graphKey, compiled.value) as GraphTraversal<Vertex, Map<String, Any>>
                } else {
                    result
                }
            } else {
                @Suppress("UNCHECKED_CAST")
                val propTraversal = compiled.traversal as GraphTraversal<Any, Any>
                result.sideEffect(
                    AnonymousTraversal.select<Any, Any>(VariableBinding.stepLabel(instanceName))
                        .property(graphKey, propTraversal)
                ) as GraphTraversal<Vertex, Map<String, Any>>
            }
        }

        for (property in listProps) {
            val graphKey = expressionSupport.engine.resolvePropertyGraphKey(className, property.propertyName)
            val listResult = expressionSupport.compilePropertyExpression(property.value, matchedInstanceNames)!!
            @Suppress("UNCHECKED_CAST")
            val listTraversal = listResult.traversal as GraphTraversal<Any, Any>
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
                if (listResult != null) { result = setListPropertyViaSideEffect(result, name, graphKey, listResult) }
            } else {
                val compiled = expressionSupport.compilePropertyExpression(property.value, matchedInstanceNames)!!
                result = if (compiled is CompilationResult.ValueResult) {
                    if (compiled.value != null) {
                        result.sideEffect(
                            AnonymousTraversal.select<Any, Any>(stepLabel).property(graphKey, compiled.value)
                        ) as GraphTraversal<Vertex, Map<String, Any>>
                    } else {
                        result
                    }
                } else {
                    @Suppress("UNCHECKED_CAST")
                    val propTraversal = compiled.traversal as GraphTraversal<Any, Any>
                    result.sideEffect(
                        AnonymousTraversal.select<Any, Any>(stepLabel).property(graphKey, propTraversal)
                    ) as GraphTraversal<Vertex, Map<String, Any>>
                }
            }
        }
        return result
    }

    /**
     * Replaces a list-typed property using a side-effect that drops existing values
     * then re-adds them with `list` cardinality.
     *
     * @param traversal The current traversal.
     * @param instanceName The instance owning the property.
     * @param graphKey The resolved graph property key.
     * @param listResult The compiled list expression result.
     * @return The traversal with the list property replacement side-effects appended.
     */
    @Suppress("UNCHECKED_CAST")
    private fun setListPropertyViaSideEffect(
        traversal: GraphTraversal<Vertex, Map<String, Any>>,
        instanceName: String,
        graphKey: String,
        listResult: CompilationResult
    ): GraphTraversal<Vertex, Map<String, Any>> {
        val stepLabel = VariableBinding.stepLabel(instanceName)
        var result = traversal
        result = result.sideEffect(
            AnonymousTraversal.select<Any, Any>(stepLabel).properties<Any>(graphKey).drop()
        ) as GraphTraversal<Vertex, Map<String, Any>>

        @Suppress("UNCHECKED_CAST")
        val listTraversal = listResult.traversal as GraphTraversal<Any, Any>
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
     * [IslandTraversalUtils.orderLinksByBFS]:
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
