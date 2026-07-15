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
                is BaseStep.ApplicationCondition -> applyApplicationCondition(t, step)
                is BaseStep.EqualityFilter -> applyEqualityFilter(t, step)
                is BaseStep.VariableBinding -> applyVariableBinding(t, step)
                is BaseStep.DeferredPropertyConstraint -> applyDeferredPropertyConstraint(t, step)
                is BaseStep.WhereFilter -> applyWhereFilter(t, step)
                is BaseStep.InjectiveConstraint -> applyInjectiveConstraint(t, step)
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
     * For constant-equality (`==`) constraints emits the cheap `.has(key, value)` step.
     * For all other comparison operators (`!=`, `<`, `>`, `<=`, `>=`) and for non-constant
     * equality expressions, emits a `.filter(comparisonExpr.is(true))` sub-traversal.
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
        val operator = step.property.operator

        // The cheap `.has(key, value)` shortcut is only correct for equality (==).
        return if (operator == "==" && step.isConstant && compiled is CompilationResult.ValueResult) {
            t.has(graphKey, compiled.value) as GraphTraversal<Vertex, Vertex>
        } else if (compiled != null) {
            val propertyTraversal = AnonymousTraversal.values<Vertex, Any>(graphKey) as GraphTraversal<Any, Any>
            val exprTraversal = expressionSupport.compileToTraversal(
                step.property.value,
                AnonymousTraversal.`as`<Any>(anchorLabel)
            ) as GraphTraversal<Any, Any>
            val propertyType = expressionSupport.resolveExpressionType(step.property.value)
                ?: throw IllegalStateException("Cannot resolve type for: ${step.property.propertyName}")
            val comparison = buildPropertyComparisonTraversal(
                operator, propertyTraversal, exprTraversal, propertyType
            )
            t.filter(comparison.`is`(true)) as GraphTraversal<Vertex, Vertex>
        } else {
            t
        }
    }

    /**
     * Applies a [BaseStep.EqualityFilter] to [t].
     *
     * Verifies the current traverser vertex equals the already-bound outer instance
     * [step.instanceName]. Translated to `.where(P.eq(stepLabel(instanceName)))`.
     *
     * @param t The traversal to extend.
     * @param step The equality-filter step to apply.
     * @return The extended traversal.
     */
    @Suppress("UNCHECKED_CAST")
    private fun applyEqualityFilter(
        t: GraphTraversal<Vertex, Vertex>,
        step: BaseStep.EqualityFilter
    ): GraphTraversal<Vertex, Vertex> {
        return t.where(P.eq(VariableBinding.stepLabel(step.instanceName))) as GraphTraversal<Vertex, Vertex>
    }

    /**
     * Applies a [BaseStep.ApplicationCondition] to [t].
     *
     * Builds an anonymous sub-traversal from [step.innerSteps] (reusing the same step
     * translation logic as for the main traversal) and wraps it in `.not(chain)` (NAC)
     * or `.where(chain)` (PAC).
     *
     * - When [step.anchorName] is null the sub-traversal starts from a fresh `V()` scan
     *   (the first inner step must be a [BaseStep.VertexScan]).
     * - When [step.anchorName] is non-null and [step.needsSelect] is false the traverser is
     *   already positioned at the anchor; the chain starts with `identity()`.
     * - When [step.anchorName] is non-null and [step.needsSelect] is true the chain is
     *   wrapped as `select(anchor).where(innerChain)`.
     *
     * Injective constraints from [step.injectiveConstraints] are applied as
     * `.where(P.neq(label))` immediately after each island node is reached in the chain.
     *
     * @param t The traversal to extend.
     * @param step The application-condition step to apply.
     * @return The extended traversal.
     */
    @Suppress("UNCHECKED_CAST")
    private fun applyApplicationCondition(
        t: GraphTraversal<Vertex, Vertex>,
        step: BaseStep.ApplicationCondition
    ): GraphTraversal<Vertex, Vertex> {
        val chain = buildConditionChain(step.innerSteps, step.injectiveConstraints)
            ?: return t

        if (step.anchorName == null || !step.needsSelect) {
            // Traverser is at the anchor (or no anchor — chain starts with V() scan).
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
     * Builds an anonymous traversal chain from a list of [BaseStep]s for use inside a
     * [BaseStep.ApplicationCondition].
     *
     * The first [BaseStep.VertexScan] step (if any) creates the root `__.V()...` traversal;
     * all subsequent steps are appended using the same translation logic as for the main
     * traversal. After each [BaseStep.EdgeWalk] step, any injective constraints registered
     * for the destination node are emitted as `.where(P.neq(label))`.
     *
     * A node in the chain is labeled with `.as(label)` only when its label is genuinely
     * needed inside the chain — either for backtracking (a later [BaseStep.EdgeWalk] has
     * `needsSelect=true` and references this node as its `fromInstanceName`) or for
     * within-island injective constraints (another island node must be distinct from this
     * one via `where(P.neq(label))`).  Labeling every node unconditionally causes
     * TinkerPop to throw [org.apache.tinkerpop.gremlin.process.traversal.step.Scoping.KeyNotFoundException]
     * when `where(traversal)` encounters an `.as(label)` for a label not already in the
     * outer scope.
     *
     * @param innerSteps The ordered steps encoding the condition pattern.
     * @param injectiveConstraints Map from island-node step label to labels it must differ from.
     * @return The built anonymous traversal, or `null` when no steps are provided.
     */
    @Suppress("UNCHECKED_CAST")
    private fun buildConditionChain(
        innerSteps: List<BaseStep>,
        injectiveConstraints: Map<String, List<String>> = emptyMap()
    ): GraphTraversal<Any, Any>? {
        if (innerSteps.isEmpty()) return null

        val neededLabels = computeNeededLabels(innerSteps, injectiveConstraints)
        var chain: GraphTraversal<Any, Any>? = null

        for (step in innerSteps) {
            chain = when {
                step is BaseStep.VertexScan && chain == null -> {
                    // First step in an unanchored condition: start with a V() scan.
                    val v: GraphTraversal<Any, Any> = when {
                        step.vertexId != null ->
                            AnonymousTraversal.V<Any>(step.vertexId) as GraphTraversal<Any, Any>
                        step.className != null ->
                            (AnonymousTraversal.V<Any>() as GraphTraversal<Vertex, Vertex>)
                                .applyClassFilter(step.className) as GraphTraversal<Any, Any>
                        else -> throw IllegalStateException(
                            "VertexScan for '${step.instanceName}' has neither vertexId nor className"
                        )
                    }
                    val nodeLabel = VariableBinding.stepLabel(step.instanceName)
                    val labeled = if (nodeLabel in neededLabels) {
                        v.`as`(nodeLabel) as GraphTraversal<Any, Any>
                    } else {
                        v
                    }
                    // Apply injective constraints for this start node.
                    applyInjectiveConstraints(labeled, nodeLabel, injectiveConstraints)
                }
                step is BaseStep.VertexScan -> {
                    throw IllegalStateException(
                        "VertexScan for '${step.instanceName}' in non-first position of condition chain"
                    )
                }
                chain == null -> {
                    // First step when the chain starts at the anchor via identity().
                    val base = AnonymousTraversal.identity<Any>() as GraphTraversal<Any, Any>
                    applyConditionStep(base, step, injectiveConstraints, neededLabels)
                }
                else -> applyConditionStep(chain, step, injectiveConstraints, neededLabels)
            }
        }
        return chain
    }

    /**
     * Computes the set of step labels that must be explicitly assigned with `.as(label)`
     * inside a condition chain.
     *
     * A label is needed when:
     * 1. It appears as `fromInstanceName` in a [BaseStep.EdgeWalk] with `needsSelect=true`
     *    AND the corresponding node is an island-internal node (appears as a destination in
     *    a previous edge walk, not an outer matched node).
     * 2. It appears as a value in [injectiveConstraints] AND the label refers to an
     *    island-internal node (so the constraint `where(P.neq(label))` can resolve the
     *    label from the chain's own path rather than from the outer traversal scope).
     *
     * Labels that refer to outer matched nodes are already present in the outer traversal
     * scope and do not need to be re-assigned inside the chain.
     */
    private fun computeNeededLabels(
        innerSteps: List<BaseStep>,
        injectiveConstraints: Map<String, List<String>>
    ): Set<String> {
        // Collect labels for island-internal destination nodes.
        // A node is "island-internal" when it appears as the destination of an EdgeWalk
        // and is NOT immediately followed by an EqualityFilter (which marks it as an outer node).
        val islandInternalLabels = mutableSetOf<String>()
        for (i in innerSteps.indices) {
            val step = innerSteps[i]
            if (step is BaseStep.VertexScan) {
                islandInternalLabels.add(VariableBinding.stepLabel(step.instanceName))
            } else if (step is BaseStep.EdgeWalk) {
                val toLabel = VariableBinding.stepLabel(step.toInstanceName)
                val nextStep = if (i + 1 < innerSteps.size) innerSteps[i + 1] else null
                // Only treat as island-internal when NOT followed by an EqualityFilter.
                if (nextStep !is BaseStep.EqualityFilter ||
                    nextStep.instanceName != step.toInstanceName) {
                    islandInternalLabels.add(toLabel)
                }
            }
        }

        val needed = mutableSetOf<String>()

        // (1) Backtracking: a later EdgeWalk with needsSelect=true whose fromInstanceName
        //     is an island-internal node requires that node to be labeled in the chain.
        for (step in innerSteps) {
            if (step is BaseStep.EdgeWalk && step.needsSelect) {
                val fromLabel = VariableBinding.stepLabel(step.fromInstanceName)
                if (fromLabel in islandInternalLabels) {
                    needed.add(fromLabel)
                }
            }
        }

        // (2) Within-island injective constraints: if a constraint value refers to an
        //     island-internal node, that node must be labeled so the chain can reference it.
        for (values in injectiveConstraints.values) {
            for (label in values) {
                if (label in islandInternalLabels) {
                    needed.add(label)
                }
            }
        }

        return needed
    }

    /**
     * Applies a single [BaseStep] to an anonymous traversal [chain] inside a condition.
     *
     * Supported step types: [BaseStep.EdgeWalk], [BaseStep.InlinePropertyConstraint],
     * [BaseStep.EqualityFilter]. After each [BaseStep.EdgeWalk] the injective constraints
     * for the destination node are emitted.
     *
     * @param neededLabels Labels that must be assigned with `.as()` — see [computeNeededLabels].
     */
    @Suppress("UNCHECKED_CAST")
    private fun applyConditionStep(
        chain: GraphTraversal<Any, Any>,
        step: BaseStep,
        injectiveConstraints: Map<String, List<String>>,
        neededLabels: Set<String> = emptySet()
    ): GraphTraversal<Any, Any> {
        return when (step) {
            is BaseStep.EdgeWalk -> {
                val edgeLabel = EdgeLabelUtils.computeEdgeLabel(
                    step.link.link.source.propertyName, step.link.link.target.propertyName
                )
                var result = chain
                if (step.needsSelect) {
                    result = result.select<Any>(VariableBinding.stepLabel(step.fromInstanceName)) as GraphTraversal<Any, Any>
                }
                result = if (step.isReversed) result.`in`(edgeLabel) as GraphTraversal<Any, Any>
                         else result.out(edgeLabel) as GraphTraversal<Any, Any>
                if (step.toVertexId != null) {
                    result = result.hasId(step.toVertexId) as GraphTraversal<Any, Any>
                } else if (step.toClassName != null) {
                    result = (result as GraphTraversal<Vertex, Vertex>).applyClassFilter(step.toClassName) as GraphTraversal<Any, Any>
                }
                val toLabel = VariableBinding.stepLabel(step.toInstanceName)
                if (toLabel in neededLabels) {
                    result = result.`as`(toLabel) as GraphTraversal<Any, Any>
                }
                // Injective constraints for the destination node.
                applyInjectiveConstraints(result, toLabel, injectiveConstraints)
            }
            is BaseStep.InlinePropertyConstraint -> {
                applyInlinePropertyConstraint(
                    chain as GraphTraversal<Vertex, Vertex>,
                    step
                ) as GraphTraversal<Any, Any>
            }
            is BaseStep.EqualityFilter -> {
                // The destination node was NOT labeled in the chain (it is an outer matched
                // node); reference its label from the outer traversal scope via P.eq().
                chain.where(P.eq(VariableBinding.stepLabel(step.instanceName))) as GraphTraversal<Any, Any>
            }
            else -> throw IllegalStateException("Unsupported step type inside condition chain: ${step::class.simpleName}")
        }
    }

    /**
     * Emits `.where(P.neq(label))` for every label listed under [nodeLabel] in
     * [injectiveConstraints], if any.
     */
    @Suppress("UNCHECKED_CAST")
    private fun applyInjectiveConstraints(
        chain: GraphTraversal<Any, Any>,
        nodeLabel: String,
        injectiveConstraints: Map<String, List<String>>
    ): GraphTraversal<Any, Any> {
        var result = chain
        injectiveConstraints[nodeLabel]?.forEach { label ->
            result = result.where(P.neq(label)) as GraphTraversal<Any, Any>
        }
        return result
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
     * `.where(has / comparisonFilter)` sub-traversal that does not change the traverser
     * position. Handles constant and expression values, and both scalar and
     * collection types. Supports all comparison operators.
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
        val operator = step.property.operator

        return when {
            // The cheap `.has(key, value)` shortcut is only correct for equality (==).
            operator == "==" && compiled is CompilationResult.ValueResult && !expressionSupport.isCollectionType(propertyType) -> {
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
                val comparison = buildPropertyComparisonTraversal(
                    operator, propTraversal, exprTraversal, resolvedType
                )
                t.where(comparison.`is`(true)) as GraphTraversal<Vertex, Vertex>
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
                val comparison = buildPropertyComparisonTraversal(
                    operator, propTraversal, exprTraversal, resolvedType
                )
                t.where(comparison.`is`(true)) as GraphTraversal<Vertex, Vertex>
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
     * Applies a [BaseStep.InjectiveConstraint] to [t].
     *
     * Ensures that the two matched instances bind to distinct vertices.
     * Translated to `.where(labelA, P.neq(labelB))`.
     */
    @Suppress("UNCHECKED_CAST")
    private fun applyInjectiveConstraint(
        t: GraphTraversal<Vertex, Vertex>,
        step: BaseStep.InjectiveConstraint
    ): GraphTraversal<Vertex, Vertex> {
        val labelA = VariableBinding.stepLabel(step.instanceNameA)
        val labelB = VariableBinding.stepLabel(step.instanceNameB)
        return t.where(labelA, P.neq(labelB)) as GraphTraversal<Vertex, Vertex>
    }

    /**
     * Applies a class-membership filter to the traversal.
     *
     * For classes without subclasses, the cheap `hasLabel(className)` step is used.
     * When the class has subclasses, all subtypes (including the class itself) are passed
     * as a vararg to `hasLabel(...)`. Gremlin's `hasLabel` matches if the vertex label
     * equals **any** of the given strings, so no separate property check is needed.
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

    /**
     * Builds a boolean-producing Gremlin traversal for a property comparison constraint.
     *
     * Dispatches based on [operator]:
     * - `"=="` and `"!="` — delegates to [EqualityCompilerUtil.buildEqualityTraversal], which
     *   handles collection folding and enum-type guards.
     * - `"<"`, `">"`, `"<="`, `">="` — builds a `project().by(left).by(right).choose(where(left, P.*), true, false)`
     *   traversal using the corresponding Gremlin [P] predicate.
     *
     * The returned traversal produces `true` when the constraint is satisfied and `false`
     * otherwise.  Callers wrap it in `.filter(t.is(true))` or `.where(t.is(true))`.
     *
     * @param operator One of `"=="`, `"!="`, `"<"`, `">"`, `"<="`, `">="`.
     * @param propertyTraversal A traversal that produces the property value from the vertex.
     * @param exprTraversal A traversal that produces the compared expression value.
     * @param type The resolved [com.mdeo.expression.ast.types.ValueType] of the property.
     * @return A traversal producing a [Boolean] comparison result.
     */
    @Suppress("UNCHECKED_CAST")
    private fun buildPropertyComparisonTraversal(
        operator: String,
        propertyTraversal: GraphTraversal<Any, Any>,
        exprTraversal: GraphTraversal<Any, Any>,
        type: com.mdeo.expression.ast.types.ValueType
    ): GraphTraversal<Any, Boolean> {
        return when (operator) {
            "==", "!=" -> EqualityCompilerUtil.buildEqualityTraversal(
                operator, propertyTraversal, exprTraversal,
                type, type,
                engine.typeRegistry,
                compilationContext.getUniqueId(),
                compilationContext.getUniqueId()
            )
            "<", ">", "<=", ">=" -> {
                val leftLabel = compilationContext.getUniqueId()
                val rightLabel = compilationContext.getUniqueId()
                val predicate: P<String> = when (operator) {
                    "<"  -> P.lt(rightLabel)
                    ">"  -> P.gt(rightLabel)
                    "<=" -> P.lte(rightLabel)
                    ">=" -> P.gte(rightLabel)
                    else -> throw IllegalStateException("Unexpected relational operator: $operator")
                }
                AnonymousTraversal.project<Any, Any>(leftLabel, rightLabel)
                    .by(propertyTraversal)
                    .by(exprTraversal)
                    .choose(
                        AnonymousTraversal.where<Any>(leftLabel, predicate),
                        AnonymousTraversal.constant<Any>(true),
                        AnonymousTraversal.constant<Any>(false)
                    ) as GraphTraversal<Any, Boolean>
            }
            else -> throw IllegalStateException(
                "Unsupported property comparison operator: '$operator'. " +
                "Expected one of: ==, !=, <, >, <=, >="
            )
        }
    }
}
