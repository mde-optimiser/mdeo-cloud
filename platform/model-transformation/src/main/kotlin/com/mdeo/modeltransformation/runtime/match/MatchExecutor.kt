package com.mdeo.modeltransformation.runtime.match

import com.mdeo.modeltransformation.ast.patterns.TypedPattern
import com.mdeo.modeltransformation.compiler.SequentialLabelIdGenerator
import com.mdeo.modeltransformation.compiler.VariableBinding
import com.mdeo.modeltransformation.graph.VertexRef
import com.mdeo.modeltransformation.runtime.TransformationExecutionContext
import com.mdeo.modeltransformation.runtime.TransformationEngine
import com.mdeo.modeltransformation.runtime.match.plan.BaseStep
import com.mdeo.modeltransformation.runtime.match.plan.MatchPlan
import com.mdeo.modeltransformation.runtime.match.plan.MatchPlanBuilder
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal
import org.apache.tinkerpop.gremlin.structure.Vertex

/**
 * Unified executor that combines pattern matching and modifications in a single Gremlin traversal.
 *
 * Delegates to specialised helpers:
 * - [MatchPlanBuilder] (plan package) — abstract plan construction (no traversal objects).
 * - [MatchTraversalBuilder] — translates the [MatchPlan] into concrete Gremlin traversal steps.
 * - [GraphModificationApplier] — create-vertex, property-update, create-edge and delete steps.
 *
 * A single [SequentialLabelIdGenerator] is shared across all helpers via [ExpressionSupport]
 * to prevent step-label collisions.
 */
class MatchExecutor {

    companion object {
        /** Default limit for single-match operations. */
        const val DEFAULT_LIMIT = 1L
        /** Signals unlimited results (for foreach operations). */
        const val UNLIMITED = -1L
    }

    private val labelIdGenerator = SequentialLabelIdGenerator()

    /** Returns the first match, or [MatchResult.NoMatch] when none is found. */
    fun executeMatch(
        pattern: TypedPattern,
        context: TransformationExecutionContext,
        engine: TransformationEngine
    ): MatchResult = executeMatchWithLimit(pattern, context, engine, DEFAULT_LIMIT).firstOrNull()
        ?: MatchResult.NoMatch("No match found")

    /** Returns all matches for the pattern. */
    fun executeMatchAll(
        pattern: TypedPattern,
        context: TransformationExecutionContext,
        engine: TransformationEngine
    ): List<MatchResult.Matched> = executeMatchWithLimit(pattern, context, engine, UNLIMITED)

    /**
     * Executes the pattern match returning up to [limit] results (-1 for unlimited).
     *
     * When [TransformationEngine.deterministic] is false, the graph's vertex iteration order is
     * reset before each match so that independent match steps within the same transformation each
     * explore a fresh, independently-shuffled order.
     */
    fun executeMatchWithLimit(
        pattern: TypedPattern,
        context: TransformationExecutionContext,
        engine: TransformationEngine,
        limit: Long
    ): List<MatchResult.Matched> {
        if (!engine.deterministic) { engine.modelGraph.resetNondeterminism() }

        val elements = PatternCategories.from(pattern)
        for (name in elements.allInstanceNames) {
            if (context.variableScope.getVariable(name) == null) {
                context.variableScope.setBinding(name, VariableBinding.InstanceBinding(vertexRef = null))
            }
        }
        return executeUnifiedTraversal(elements, context, engine, limit)
    }

    /**
     * Orchestrates the complete match pipeline: plan building, traversal assembly, execution,
     * and result extraction.
     *
     * @param elements Categorised pattern elements.
     * @param context The current transformation execution context.
     * @param engine The transformation engine providing graph access and type information.
     * @param limit Maximum number of results (-1 for unlimited).
     * @return List of matched results (may be empty).
     */
    private fun executeUnifiedTraversal(
        elements: PatternCategories,
        context: TransformationExecutionContext,
        engine: TransformationEngine,
        limit: Long
    ): List<MatchResult.Matched> {
        val expressionSupport = ExpressionSupport(engine, context, labelIdGenerator)
        val compilationContext = expressionSupport.newCompilationContext()

        for (varElement in elements.variables) {
            val varLabel = VariableBinding.variableLabel(varElement.variable.name)
            context.variableScope.setBinding(varElement.variable.name, VariableBinding.LabelBinding(varLabel))
        }

        val analyzer = MatchAnalyzer(context.variableScope)
        elements.variables.forEach { analyzer.analyzeVariable(it) }
        elements.whereClauses.forEach { analyzer.analyzeWhereClause(it) }
        (elements.matchableInstances + elements.createInstances + elements.deleteInstances)
            .forEach { analyzer.analyzeObjectInstance(it) }
        (elements.matchableLinks + elements.createLinks + elements.deleteLinks +
            elements.forbidLinks + elements.requireLinks)
            .forEach { analyzer.analyzeLink(it) }
        val referencedInstances = analyzer.getReferencedInstances()

        val allMatchable = elements.matchableInstances + elements.deleteInstances
        val matchableNames = allMatchable.map { it.objectInstance.name }.toSet()

        val variableNames = elements.variables.map { it.variable.name }.toSet()
        val nodeAnalyzer = ExpressionNodeAnalyzer(matchableNames + variableNames, context.variableScope.scopeIndex)

        val matchPlan = MatchPlanBuilder(
            getVertexId = { name ->
                (context.variableScope.getVariable(name)
                    as? VariableBinding.InstanceBinding)?.vertexId
            },
            nodeAnalyzer = nodeAnalyzer,
            isCollectionExpression = { expr ->
                expressionSupport.isCollectionType(expressionSupport.resolveExpressionType(expr))
            },
            metamodelData = engine.metamodelData
        ).build(elements, referencedInstances)

        val traversal = buildUnifiedTraversal(
            elements, matchPlan,
            expressionSupport, compilationContext, limit
        )

        val planBoundNames = matchPlan.baseSteps.mapNotNull { step ->
            when (step) {
                is BaseStep.VertexScan -> step.instanceName
                is BaseStep.EdgeWalk -> step.toInstanceName
                else -> null
            }
        }
        val allSelectNames = (elements.allInstanceNames + planBoundNames).distinct()

        val matchedInstanceNames = allMatchable.map { it.objectInstance.name }
        return executeTraversalAndExtract(traversal, elements, context, engine, matchedInstanceNames, allSelectNames)
    }

    /**
     * Assembles the full Gremlin traversal from the match plan, applying limit, modifications,
     * and the final select step.
     *
     * The traversal is fully imperative — no `match()` step is used. All constraints
     * (island, property, injective, where-clauses, variables) are expressed as imperative
     * steps and post-match filters.
     *
     * @param elements Categorised pattern elements.
     * @param plan The abstract match plan produced by [MatchPlanBuilder].
     * @param expressionSupport Shared expression compilation utilities.
     * @param compilationContext The compilation context for unique label generation.
     * @param limit Maximum number of results (-1 for unlimited).
     * @return The fully assembled traversal ready for execution.
     */
    @Suppress("UNCHECKED_CAST")
    private fun buildUnifiedTraversal(
        elements: PatternCategories,
        plan: MatchPlan,
        expressionSupport: ExpressionSupport,
        compilationContext: com.mdeo.modeltransformation.compiler.CompilationContext,
        limit: Long
    ): GraphTraversal<Vertex, Any> {
        val traversalBuilder = MatchTraversalBuilder(expressionSupport, compilationContext, expressionSupport.engine)
        var t: GraphTraversal<Vertex, Map<String, Any>> =
            traversalBuilder.buildBaseTraversal(plan) as GraphTraversal<Vertex, Map<String, Any>>

        t = applyLimit(t, limit)

        val matchedNames = elements.matchableInstances.map { it.objectInstance.name }
        t = GraphModificationApplier(elements, expressionSupport, compilationContext).apply(t, matchedNames)

        val planBoundNames = plan.baseSteps.mapNotNull { step ->
            when (step) {
                is BaseStep.VertexScan -> step.instanceName
                is BaseStep.EdgeWalk -> step.toInstanceName
                else -> null
            }
        }
        val allSelectNames = (elements.allInstanceNames + planBoundNames).distinct()

        val variableLabels = elements.variables.map { VariableBinding.variableLabel(it.variable.name) }
        return addSelectStep(t, allSelectNames, variableLabels)
    }

    /**
     * Applies a `.limit()` step if [limit] is positive.
     *
     * @param traversal The traversal to limit.
     * @param limit Maximum number of results; non-positive means unlimited.
     * @return The traversal with or without the limit step.
     */
    private fun applyLimit(
        traversal: GraphTraversal<Vertex, Map<String, Any>>,
        limit: Long
    ): GraphTraversal<Vertex, Map<String, Any>> =
        if (limit <= 0) traversal else traversal.limit(limit) as GraphTraversal<Vertex, Map<String, Any>>

    /**
     * Appends a `.select()` step to extract all instance and variable bindings from the traversal.
     *
     * @param traversal The traversal to add the select step to.
     * @param instanceNames Instance names whose step labels should be selected.
     * @param variableLabels Variable labels to include in the selection.
     * @return The traversal with the select step applied.
     */
    @Suppress("UNCHECKED_CAST")
    private fun addSelectStep(
        traversal: GraphTraversal<Vertex, Map<String, Any>>,
        instanceNames: List<String>,
        variableLabels: List<String>
    ): GraphTraversal<Vertex, Any> {
        val stepLabels = instanceNames.map { VariableBinding.stepLabel(it) }
        val all = stepLabels + variableLabels
        return when (all.size) {
            0 -> traversal as GraphTraversal<Vertex, Any>
            1 -> traversal.select<Any>(all[0]) as GraphTraversal<Vertex, Any>
            else -> traversal.select<Any>(all[0], all[1], *all.drop(2).toTypedArray())
                as GraphTraversal<Vertex, Any>
        }
    }

    /**
     * Materialises the traversal and converts each raw result map into a [MatchResult.Matched].
     *
     * @param traversal The fully assembled traversal to execute.
     * @param elements Categorised pattern elements.
     * @param context The current transformation execution context.
     * @param engine The transformation engine.
     * @param matchedInstanceNames Names of matchable instances.
     * @param allSelectNames All instance names included in the final select step (may include
     *        referenced instances from outer scopes beyond allInstanceNames).
     * @return List of extracted match results.
     */
    @Suppress("UNCHECKED_CAST")
    private fun executeTraversalAndExtract(
        traversal: GraphTraversal<Vertex, Any>,
        elements: PatternCategories,
        context: TransformationExecutionContext,
        engine: TransformationEngine,
        matchedInstanceNames: List<String>,
        allSelectNames: List<String>
    ): List<MatchResult.Matched> {
        val instanceNames = elements.allInstanceNames
        val variableLabels = elements.variables.map { VariableBinding.variableLabel(it.variable.name) }
        val allLabels = allSelectNames + variableLabels

        return traversal.toList().map { rawResult ->
            val result: Map<String, Any> = when {
                allLabels.isEmpty() -> rawResult as Map<String, Any>
                allLabels.size == 1 -> mapOf(allLabels[0] to rawResult)
                else -> {
                    val rawMap = rawResult as Map<String, Any>
                    allSelectNames.associateWith { rawMap[VariableBinding.stepLabel(it)]!! } +
                        variableLabels.associateWith { rawMap[it]!! }
                }
            }
            extractMatchResult(result, elements, context, engine, matchedInstanceNames)
        }
    }

    /**
     * Converts a single raw result map into a [MatchResult.Matched], populating variable
     * bindings and classifying each node as matched, created, or deleted.
     *
     * @param result The raw result map from the traversal.
     * @param elements Categorised pattern elements.
     * @param context The current transformation execution context.
     * @param engine The transformation engine.
     * @param matchedInstanceNames Names of matchable instances.
     * @return The extracted match result.
     */
    private fun extractMatchResult(
        result: Map<String, Any>,
        elements: PatternCategories,
        context: TransformationExecutionContext,
        engine: TransformationEngine,
        matchedInstanceNames: List<String>
    ): MatchResult.Matched {
        val bindings = mutableMapOf<String, Any?>()
        for (varElement in elements.variables) {
            val varName = varElement.variable.name
            val value = result[VariableBinding.variableLabel(varName)]
            bindings[varName] = value
            context.variableScope.setBinding(varName, VariableBinding.ValueBinding(value))
        }

        val instanceMappings = mutableMapOf<String, VertexRef>()
        val matchedNodeIds = mutableSetOf<Any>()
        val createdNodeIds = mutableSetOf<Any>()
        val deletedNodeIds = mutableSetOf<Any>()
        val createNames = elements.createInstances.map { it.objectInstance.name }.toSet()
        val deleteNames = elements.deleteInstances.map { it.objectInstance.name }.toSet()

        for (name in elements.allInstanceNames) {
            classifyNode(
                name, result[name], createNames, deleteNames,
                instanceMappings, matchedNodeIds, createdNodeIds, deletedNodeIds, engine
            )
        }
        return MatchResult.Matched(bindings, instanceMappings, matchedNodeIds, createdNodeIds, deletedNodeIds)
    }

    /**
     * Classifies a single result node as matched, created, or deleted based on the pattern
     * element sets and adds it to the appropriate output collections.
     *
     * @param name The instance name.
     * @param value The raw vertex value from the result map (may be null or non-Vertex).
     * @param createNames Names of create instances.
     * @param deleteNames Names of delete instances.
     * @param instanceMappings Output map of instance name to vertex reference.
     * @param matchedNodeIds Output set of matched vertex IDs.
     * @param createdNodeIds Output set of created vertex IDs.
     * @param deletedNodeIds Output set of deleted vertex IDs.
     * @param engine The transformation engine for vertex reference creation.
     */
    private fun classifyNode(
        name: String,
        value: Any?,
        createNames: Set<String>,
        deleteNames: Set<String>,
        instanceMappings: MutableMap<String, VertexRef>,
        matchedNodeIds: MutableSet<Any>,
        createdNodeIds: MutableSet<Any>,
        deletedNodeIds: MutableSet<Any>,
        engine: TransformationEngine
    ) {
        if (value !is Vertex) { return }
        val rawId = value.id()
        instanceMappings[name] = engine.modelGraph.createVertexRef(rawId)
        when {
            name in createNames -> {
                createdNodeIds.add(rawId)
                engine.instanceNameRegistry.registerWithUniqueName(rawId, name)
            }
            name in deleteNames -> {
                matchedNodeIds.add(rawId)
                deletedNodeIds.add(rawId)
            }
            else -> matchedNodeIds.add(rawId)
        }
    }
}
