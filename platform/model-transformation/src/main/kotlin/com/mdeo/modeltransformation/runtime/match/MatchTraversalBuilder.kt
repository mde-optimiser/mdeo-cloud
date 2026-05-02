package com.mdeo.modeltransformation.runtime.match

import com.mdeo.modeltransformation.ast.EdgeLabelUtils
import com.mdeo.modeltransformation.compiler.CompilationContext
import com.mdeo.modeltransformation.compiler.CompilationResult
import com.mdeo.modeltransformation.compiler.VariableBinding
import com.mdeo.modeltransformation.compiler.expressions.EqualityCompilerUtil
import com.mdeo.modeltransformation.runtime.TransformationEngine
import com.mdeo.modeltransformation.runtime.match.plan.BaseStep
import com.mdeo.modeltransformation.runtime.match.plan.MatchPlan
import org.apache.tinkerpop.gremlin.process.traversal.P
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal
import org.apache.tinkerpop.gremlin.structure.Vertex
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.`__` as AnonymousTraversal

/**
 * Translates an abstract [MatchPlan] into concrete Gremlin traversal steps.
 *
 * The translation is fully imperative — no `match()` step is used. All [BaseStep]s are
 * applied sequentially to build an efficient traversal with early constraint pruning.
 *
 * Post-match filters are handled by the caller ([MatchExecutor]).
 */
internal class MatchTraversalBuilder(
    private val expressionSupport: ExpressionSupport,
    private val compilationContext: CompilationContext,
    private val engine: TransformationEngine
) {
    private val anchorLabel = MATCH_ANCHOR_LABEL

    /**
     * Translates [plan] into a concrete Gremlin traversal by applying each [BaseStep] in order.
     *
     * The traversal begins with `inject(emptyMap).as(anchorLabel)` and each step narrows
     * or extends it. Post-match filters are not applied here; they are handled by the caller.
     *
     * @param plan The match plan whose base steps are to be compiled.
     * @return A traversal rooted at a map-inject anchor and extended by all base steps.
     */
    @Suppress("UNCHECKED_CAST")
    fun buildBaseTraversal(plan: MatchPlan): GraphTraversal<Vertex, Vertex> {
        var t = engine.traversalSource
            .inject(emptyMap<String, Any>()).`as`(anchorLabel) as GraphTraversal<Vertex, Vertex>

        for (step in plan.baseSteps) {
            t = when (step) {
                is BaseStep.VertexScan -> applyVertexScan(t, step)
                is BaseStep.EdgeWalk -> applyEdgeWalk(t, step)
                is BaseStep.InlinePropertyConstraint -> applyInlinePropertyConstraint(t, step)
                is BaseStep.InlineIslandConstraint -> applyInlineIslandConstraint(t, step)
                is BaseStep.InlineOrphanLinkConstraint -> applyInlineOrphanLink(t, step)
                is BaseStep.VariableBinding -> applyVariableBinding(t, step)
                is BaseStep.DeferredPropertyConstraint -> applyDeferredPropertyConstraint(t, step)
                is BaseStep.WhereFilter -> applyWhereFilter(t, step)
                is BaseStep.DisconnectedIslandFilter -> applyDisconnectedIslandFilter(t, step)
            }
        }
        return t
    }

    /**
     * Applies a [BaseStep.VertexScan] to [t].
     *
     * Emits `V(vertexId).as(stepLabel)` when [step] has a pre-bound `vertexId`, or
     * `V().hasLabel(className).as(stepLabel)` otherwise.
     *
     * @param t The traversal to extend.
     * @param step The vertex-scan step to apply.
     * @return The extended traversal.
     */
    @Suppress("UNCHECKED_CAST")
    private fun applyVertexScan(
        t: GraphTraversal<Vertex, Vertex>,
        step: BaseStep.VertexScan
    ): GraphTraversal<Vertex, Vertex> {
        val scanned = when {
            step.vertexId != null -> t.V(step.vertexId)
            step.className != null -> t.V().applyClassFilter(step.className)
            else -> throw IllegalStateException(
                "VertexScan for '${step.instanceName}' has neither vertexId nor className"
            )
        }
        return scanned.`as`(VariableBinding.stepLabel(step.instanceName)) as GraphTraversal<Vertex, Vertex>
    }

    /**
     * Applies a [BaseStep.EdgeWalk] to [t].
     *
     * Optionally emits `select(from)` when [step] requires a back-navigation, then
     * emits `out/in(edgeLabel)` followed by optional `hasId` or `hasLabel` constraints,
     * and finally `.as(toLabel)` to label the reached vertex.
     *
     * @param t The traversal to extend.
     * @param step The edge-walk step to apply.
     * @return The extended traversal.
     */
    @Suppress("UNCHECKED_CAST")
    private fun applyEdgeWalk(
        t: GraphTraversal<Vertex, Vertex>,
        step: BaseStep.EdgeWalk
    ): GraphTraversal<Vertex, Vertex> {
        val edgeLabel = EdgeLabelUtils.computeEdgeLabel(
            step.link.link.source.propertyName, step.link.link.target.propertyName
        )
        var result: GraphTraversal<*, *> = t
        if (step.needsSelect) {
            result = result.select<Any>(VariableBinding.stepLabel(step.fromInstanceName))
        }
        result = if (step.isReversed) result.`in`(edgeLabel) else result.out(edgeLabel)
        if (step.toVertexId != null) {
            result = result.hasId(step.toVertexId)
        } else if (step.toClassName != null) {
            @Suppress("UNCHECKED_CAST")
            result = (result as GraphTraversal<Vertex, Vertex>).applyClassFilter(step.toClassName)
        }
        return result.`as`(VariableBinding.stepLabel(step.toInstanceName)) as GraphTraversal<Vertex, Vertex>
    }

    /**
     * Applies a [BaseStep.InlinePropertyConstraint] to [t].
     *
     * For constant values emits `.has(key, value)`. For non-constant expressions emits a
     * `.filter(equalityExpr.is(true))` sub-traversal that evaluates [step]'s expression
     * against the anchor's current state.
     *
     * @param t The traversal to extend.
     * @param step The inline property-constraint step to apply.
     * @return The extended traversal.
     */
    @Suppress("UNCHECKED_CAST")
    private fun applyInlinePropertyConstraint(
        t: GraphTraversal<Vertex, Vertex>,
        step: BaseStep.InlinePropertyConstraint
    ): GraphTraversal<Vertex, Vertex> {
        val graphKey = engine.resolvePropertyGraphKey(step.className, step.property.propertyName)
        val compiled = expressionSupport.compilePropertyExpression(step.property.value, emptyList())

        return if (step.isConstant && compiled is CompilationResult.ValueResult) {
            t.has(graphKey, compiled.value) as GraphTraversal<Vertex, Vertex>
        } else if (compiled != null) {
            val propertyTraversal = AnonymousTraversal.values<Vertex, Any>(graphKey) as GraphTraversal<Any, Any>
            val exprTraversal = expressionSupport.compileToTraversal(
                step.property.value,
                AnonymousTraversal.`as`<Any>(anchorLabel)
            ) as GraphTraversal<Any, Any>
            val propertyType = expressionSupport.resolveExpressionType(step.property.value)
                ?: throw IllegalStateException("Cannot resolve type for: ${step.property.propertyName}")
            val eq = EqualityCompilerUtil.buildEqualityTraversal(
                "==", propertyTraversal, exprTraversal,
                propertyType, propertyType,
                engine.typeRegistry,
                compilationContext.getUniqueId(),
                compilationContext.getUniqueId()
            )
            t.filter(eq.`is`(true)) as GraphTraversal<Vertex, Vertex>
        } else {
            t
        }
    }

    /**
     * Applies a [BaseStep.InlineIslandConstraint] to [t].
     *
     * Builds an anonymous chain traversal from the anchor via [buildIslandChainFromIdentity]
     * and wraps it in `.not(chain)` (forbid) or `.where(chain)` (require). When
     * [step] requires a select, the chain is anchored via `select(anchorLabel).where(chain)`.
     *
     * @param t The traversal to extend.
     * @param step The island-constraint step to apply.
     * @return The extended traversal.
     */
    @Suppress("UNCHECKED_CAST")
    private fun applyInlineIslandConstraint(
        t: GraphTraversal<Vertex, Vertex>,
        step: BaseStep.InlineIslandConstraint
    ): GraphTraversal<Vertex, Vertex> {
        val chain = buildIslandChainFromIdentity(
            step.island, step.anchorName, step.orderedLinks,
            step.nodesNeedingBacktrackLabel, step.injectiveConstraints
        ) ?: return t

        if (!step.needsSelect) {
            return if (step.isNegative) {
                t.not(chain) as GraphTraversal<Vertex, Vertex>
            } else {
                t.where(chain) as GraphTraversal<Vertex, Vertex>
            }
        }

        val anchorStepLabel = VariableBinding.stepLabel(step.anchorName)
        return if (step.isNegative) {
            t.not(
                AnonymousTraversal.select<Any, Any>(anchorStepLabel).where(chain)
            ) as GraphTraversal<Vertex, Vertex>
        } else {
            t.where(
                AnonymousTraversal.select<Any, Any>(anchorStepLabel).where(chain)
            ) as GraphTraversal<Vertex, Vertex>
        }
    }

    /**
     * Applies a [BaseStep.InlineOrphanLinkConstraint] to [t].
     *
     * Emits `.where(as(source).out(edge).as(target))` for a require constraint, or
     * `.where(not(as(source).out(edge).as(target)))` for a forbid constraint.
     *
     * @param t The traversal to extend.
     * @param step The orphan-link constraint step to apply.
     * @return The extended traversal.
     */
    @Suppress("UNCHECKED_CAST")
    private fun applyInlineOrphanLink(
        t: GraphTraversal<Vertex, Vertex>,
        step: BaseStep.InlineOrphanLinkConstraint
    ): GraphTraversal<Vertex, Vertex> {
        val inner = AnonymousTraversal.`as`<Any>(VariableBinding.stepLabel(step.sourceName))
            .out(step.edgeLabel)
            .`as`(VariableBinding.stepLabel(step.targetName)) as GraphTraversal<Any, Any>
        return if (step.isNegative) {
            t.where(AnonymousTraversal.not<Any>(inner)) as GraphTraversal<Vertex, Vertex>
        } else {
            t.where(inner) as GraphTraversal<Vertex, Vertex>
        }
    }

    /**
     * Applies a [BaseStep.VariableBinding] to [t].
     *
     * Compiles [step]'s expression against the anchor and emits
     * `.map(compiledExpression).as(variableLabel)` to bind the result.
     *
     * @param t The traversal to extend.
     * @param step The variable-binding step to apply.
     * @return The extended traversal.
     */
    @Suppress("UNCHECKED_CAST")
    private fun applyVariableBinding(
        t: GraphTraversal<Vertex, Vertex>,
        step: BaseStep.VariableBinding
    ): GraphTraversal<Vertex, Vertex> {
        val anchorTraversal = AnonymousTraversal.`as`<Any>(anchorLabel)
        val result: CompilationResult = engine.expressionCompilerRegistry.compile(
            step.variable.variable.value, compilationContext, anchorTraversal
        )
        return t.map(result.traversal).`as`(step.variableLabel) as GraphTraversal<Vertex, Vertex>
    }

    /**
     * Applies a [BaseStep.DeferredPropertyConstraint] to [t].
     *
     * Navigates to the instance via `select(instanceLabel)` and emits a
     * `.where(has / equalityFilter)` sub-traversal that does not change the traverser
     * position. Handles both constant and expression values, and both scalar and
     * collection types.
     *
     * @param t The traversal to extend.
     * @param step The deferred property-constraint step to apply.
     * @return The extended traversal.
     */
    @Suppress("UNCHECKED_CAST")
    private fun applyDeferredPropertyConstraint(
        t: GraphTraversal<Vertex, Vertex>,
        step: BaseStep.DeferredPropertyConstraint
    ): GraphTraversal<Vertex, Vertex> {
        val instanceLabel = VariableBinding.stepLabel(step.instanceName)
        val graphKey = engine.resolvePropertyGraphKey(step.className, step.property.propertyName)
        val compiled = expressionSupport.compilePropertyExpression(step.property.value, emptyList())
        val propertyType = expressionSupport.resolveExpressionType(step.property.value)

        return when {
            compiled is CompilationResult.ValueResult && !expressionSupport.isCollectionType(propertyType) -> {
                t.where(
                    AnonymousTraversal.select<Any, Any>(instanceLabel)
                        .has(graphKey, compiled.value)
                ) as GraphTraversal<Vertex, Vertex>
            }
            compiled is CompilationResult.ValueResult && expressionSupport.isCollectionType(propertyType) -> {
                val propTraversal = AnonymousTraversal.select<Any, Any>(instanceLabel)
                    .values<Any>(graphKey) as GraphTraversal<Any, Any>
                val exprTraversal = expressionSupport.buildConstantCollectionTraversal(compiled.value)
                val resolvedType = propertyType ?: throw IllegalStateException(
                    "Cannot resolve type for: ${step.property.propertyName}"
                )
                val eq = EqualityCompilerUtil.buildEqualityTraversal(
                    "==", propTraversal, exprTraversal,
                    resolvedType, resolvedType,
                    engine.typeRegistry,
                    compilationContext.getUniqueId(),
                    compilationContext.getUniqueId()
                )
                t.where(eq.`is`(true)) as GraphTraversal<Vertex, Vertex>
            }
            compiled != null -> {
                val propTraversal = AnonymousTraversal.select<Any, Any>(instanceLabel)
                    .values<Any>(graphKey) as GraphTraversal<Any, Any>
                val exprTraversal = expressionSupport.compileToTraversal(
                    step.property.value,
                    AnonymousTraversal.`as`<Any>(anchorLabel)
                ) as GraphTraversal<Any, Any>
                val resolvedType = propertyType ?: throw IllegalStateException(
                    "Cannot resolve type for: ${step.property.propertyName}"
                )
                val eq = EqualityCompilerUtil.buildEqualityTraversal(
                    "==", propTraversal, exprTraversal,
                    resolvedType, resolvedType,
                    engine.typeRegistry,
                    compilationContext.getUniqueId(),
                    compilationContext.getUniqueId()
                )
                t.where(eq.`is`(true)) as GraphTraversal<Vertex, Vertex>
            }
            else -> t
        }
    }

    /**
     * Applies a [BaseStep.WhereFilter] to [t].
     *
     * Compiles [step]'s where-clause expression and emits
     * `.where(compiledExpression.is(true))`.
     *
     * @param t The traversal to extend.
     * @param step The where-filter step to apply.
     * @return The extended traversal.
     */
    @Suppress("UNCHECKED_CAST")
    private fun applyWhereFilter(
        t: GraphTraversal<Vertex, Vertex>,
        step: BaseStep.WhereFilter
    ): GraphTraversal<Vertex, Vertex> {
        val compiled = expressionSupport.compileToTraversal(step.whereClause.whereClause.expression)
        return t.where(compiled.`is`(true)) as GraphTraversal<Vertex, Vertex>
    }

    /**
     * Applies a [BaseStep.DisconnectedIslandFilter] to [t].
     *
     * For each instance in [step]'s island, emits a
     * `.where(V().hasLabel(cls)...count().is(predicate))` sub-traversal that counts
     * matching vertices and requires zero (forbid) or more than zero (require).
     *
     * @param t The traversal to extend.
     * @param step The disconnected-island filter step to apply.
     * @return The extended traversal.
     */
    @Suppress("UNCHECKED_CAST")
    private fun applyDisconnectedIslandFilter(
        t: GraphTraversal<Vertex, Vertex>,
        step: BaseStep.DisconnectedIslandFilter
    ): GraphTraversal<Vertex, Vertex> {
        var result = t
        for (instance in step.island.instances) {
            val className = instance.objectInstance.className ?: continue
            val instName = instance.objectInstance.name
            val predicate = if (step.isNegative) P.eq(0L) else P.gt(0L)
            var countTraversal: GraphTraversal<Any, Any> =
                (AnonymousTraversal.V<Any>() as GraphTraversal<Vertex, Vertex>).applyClassFilter(className)
                    as GraphTraversal<Any, Any>
            countTraversal = expressionSupport.applyPropertyEqualityConstraints(
                countTraversal, instance.objectInstance.className, instance.objectInstance.properties
            )
            step.injectiveConstraints[instName]?.forEach { label ->
                @Suppress("UNCHECKED_CAST")
                countTraversal = countTraversal.where(P.neq(label)) as GraphTraversal<Any, Any>
            }
            result = result.where(
                countTraversal.count().`is`(predicate)
            ) as GraphTraversal<Vertex, Vertex>
        }
        return result
    }

    /**
     * Builds an anonymous traversal chain starting from `identity()` that walks the
     * BFS-ordered island links from the given anchor.
     *
     * Each link in [orderedLinks] is translated to an `out/in(edgeLabel)` step, optionally
     * preceded by a `select(backtrackLabel)` when the chain needs to back-navigate to a
     * previously labelled node. Property equality constraints are applied inline.
     *
     * After each island node is reached, injective-match constraints are emitted as
     * `.where(P.neq(label))` for every label in [injectiveConstraints][toNode].
     * Labels may refer to outer traversal step labels (for main-pattern nodes) or to
     * `__inline_<name>` labels of earlier island nodes in the chain.
     *
     * @param island The island whose graph structure is encoded in the chain.
     * @param anchorName The name of the main-pattern node from which the chain starts.
     * @param orderedLinks The BFS-ordered links to walk, each paired with an `isReversed` flag.
     * @param nodesNeedingBacktrackLabel The set of node names that need an inline step label
     *   because the chain will select back to them from a different branch, or because a later
     *   island node references them via an injective constraint.
     * @param injectiveConstraints Map from island node name to the list of step labels that
     *   node must be distinct from (injective matching).
     * @return The built chain traversal, or `null` when [orderedLinks] is empty and no steps
     *   can be emitted.
     */
    @Suppress("UNCHECKED_CAST")
    private fun buildIslandChainFromIdentity(
        island: Island,
        anchorName: String,
        orderedLinks: List<Pair<com.mdeo.modeltransformation.ast.patterns.TypedPatternLinkElement, Boolean>>,
        nodesNeedingBacktrackLabel: Set<String>,
        injectiveConstraints: Map<String, List<String>> = emptyMap()
    ): GraphTraversal<Any, Any>? {
        val islandClassMap = island.instances.mapNotNull { inst ->
            inst.objectInstance.className?.let { inst.objectInstance.name to it }
        }.toMap()
        val islandInstanceMap = island.instances.associateBy { it.objectInstance.name }

        var chain: GraphTraversal<Any, Any> = AnonymousTraversal.identity<Any>() as GraphTraversal<Any, Any>
        if (anchorName in nodesNeedingBacktrackLabel) {
            chain = chain.`as`("__inline_${anchorName}") as GraphTraversal<Any, Any>
        }

        var current = anchorName
        for ((link, isReversed) in orderedLinks) {
            val edgeLabel = EdgeLabelUtils.computeEdgeLabel(
                link.link.source.propertyName, link.link.target.propertyName
            )
            val fromNode = if (isReversed) link.link.target.objectName else link.link.source.objectName
            val toNode = if (isReversed) link.link.source.objectName else link.link.target.objectName

            if (fromNode != current) {
                chain = chain.select<Any>("__inline_${fromNode}") as GraphTraversal<Any, Any>
            }
            chain = if (isReversed) chain.`in`(edgeLabel) as GraphTraversal<Any, Any> else chain.out(edgeLabel) as GraphTraversal<Any, Any>

            islandClassMap[toNode]?.let { cls ->
                @Suppress("UNCHECKED_CAST")
                chain = (chain as GraphTraversal<Vertex, Vertex>).applyClassFilter(cls)
                    as GraphTraversal<Any, Any>
            }
            islandInstanceMap[toNode]?.let { inst ->
                chain = expressionSupport.applyPropertyEqualityConstraints(
                    chain, inst.objectInstance.className, inst.objectInstance.properties
                )
            }
            if (toNode !in islandInstanceMap && toNode != anchorName) {
                @Suppress("UNCHECKED_CAST")
                chain = chain.where(P.eq(VariableBinding.stepLabel(toNode))) as GraphTraversal<Any, Any>
            }
            injectiveConstraints[toNode]?.forEach { label ->
                @Suppress("UNCHECKED_CAST")
                chain = chain.where(P.neq(label)) as GraphTraversal<Any, Any>
            }
            if (toNode in nodesNeedingBacktrackLabel) {
                chain = chain.`as`("__inline_${toNode}") as GraphTraversal<Any, Any>
            }
            current = toNode
        }
        return chain
    }

    /**
     * Applies a class-membership filter to the traversal.
     *
     * For classes **without** subclasses, the cheap `hasLabel(className)` step is used.
     *
     * For classes **with** subclasses two conditions are combined with `or`:
     * - `hasLabel(className)` — matches instances whose label IS exactly the class name
     *   (e.g. direct `Room` vertices added without going through [ModelDataGraphLoader]).
     * When the class has no subclasses the filter is `hasLabel(className)`. When it does have
     * subclasses, all subtypes (including the class itself) are retrieved from
     * [com.mdeo.metamodel.metadata.MetamodelMetadata.classHierarchy] and passed as a vararg to
     * `hasLabel(...)`. Gremlin's `hasLabel` matches if the vertex label equals **any** of the
     * given strings, so no separate property check is needed.
     *
     * @param className The metamodel class name to filter by.
     * @return The traversal extended with the appropriate class filter.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <S, E> GraphTraversal<S, E>.applyClassFilter(className: String): GraphTraversal<S, E> {
        val subtypes = engine.metamodel.metadata.classHierarchy[className]
        return if (subtypes != null && subtypes.size > 1) {
            val labels = subtypes.toTypedArray()
            hasLabel(labels[0], *labels.drop(1).toTypedArray()) as GraphTraversal<S, E>
        } else {
            hasLabel(className) as GraphTraversal<S, E>
        }
    }
}

