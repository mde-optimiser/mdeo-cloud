package com.mdeo.modeltransformation.runtime.match

import com.mdeo.modeltransformation.ast.patterns.TypedPattern
import com.mdeo.modeltransformation.ast.patterns.TypedPatternVariableElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternVariableReassignmentElement
import com.mdeo.modeltransformation.compiler.CollectionValue
import com.mdeo.modeltransformation.compiler.SequentialLabelIdGenerator
import com.mdeo.modeltransformation.compiler.VariableBinding
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstanceElement
import com.mdeo.modeltransformation.compiler.conditionScope
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

        /**
         * When this system property is set, every match plan is printed to stdout as a
         * compact step summary. Used to inspect the join order chosen for a pattern when
         * diagnosing slow transformations; it has no effect on execution.
         */
        const val DEBUG_PLAN_PROPERTY = "mdeo.debug.matchplan"
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

        val elements = withReferenceClasses(PatternCategories.from(pattern), context, engine)
        val models = PatternModels.of(elements, context, engine.modelGraph)
        for (name in elements.allInstanceNames) {
            if (context.variableScope.getVariable(name) == null) {
                context.variableScope.setBinding(name, VariableBinding.InstanceBinding(vertexRef = null))
            }
        }
        return executeUnifiedTraversal(elements, context, engine, limit, models)
    }

    /**
     * Gives a reference to a bound object (`pc { shape == "x" }`: no class in the pattern) the
     * class of the element it is bound to, when one of its properties is a name that classes
     * store under different graph keys ([TransformationEngine.isAmbiguousPropertyName]), or it
     * assigns a property that is numeric in some class ([TransformationEngine.isNumericPropertyName]).
     * Otherwise such a property would be resolved by name across the whole metamodel and read
     * or write another class's field, and an assigned value would not be cast to the property's
     * int/long/float/double. The class is the bound vertex's label (one `V(id).label()` query,
     * only for such references); a bound object with a class is matched as that very element, as
     * a pinned optional input is.
     */
    private fun withReferenceClasses(
        elements: PatternCategories,
        context: TransformationExecutionContext,
        engine: TransformationEngine
    ): PatternCategories {
        fun resolve(element: TypedPatternObjectInstanceElement): TypedPatternObjectInstanceElement {
            val instance = element.objectInstance
            val needsClass = instance.properties.any {
                engine.isAmbiguousPropertyName(it.propertyName) ||
                    (it.operator == "=" && engine.isNumericPropertyName(it.propertyName))
            }
            if (instance.className != null || !needsClass) {
                return element
            }
            val vertexId = (context.variableScope.getVariable(instance.name) as? VariableBinding.InstanceBinding)?.vertexId
                ?: return element
            val label = engine.traversalSource.V(vertexId).label().tryNext().orElse(null) ?: return element
            return element.copy(objectInstance = instance.copy(className = label))
        }
        if (elements.matchableInstances.none { it.objectInstance.className == null && it.objectInstance.properties.isNotEmpty() }) {
            return elements
        }
        return elements.copy(matchableInstances = elements.matchableInstances.map(::resolve))
    }

    /**
     * Orchestrates the complete match pipeline: plan building, traversal assembly, execution,
     * and result extraction.
     *
     * The links and constraints of a condition may reference nodes of the enclosing match;
     * those have to be part of the referenced set, so that the planner covers them before the
     * condition is emitted.
     *
     * The nodes of an application condition are bound inside the condition's own
     * sub-traversal, never by the match, and their names live in a scope of their own: they
     * are visible to the constraints of their block and to nothing else. Nothing has to be
     * unbound afterwards — the scope simply ends with the block it belongs to.
     *
     * @param elements Categorised pattern elements.
     * @param context The current transformation execution context.
     * @param engine The transformation engine providing graph access and type information.
     * @param limit Maximum number of results (-1 for unlimited).
     * @param models The models of the pattern's objects while model inputs are loaded, else null.
     * @return List of matched results (may be empty).
     */
    private fun executeUnifiedTraversal(
        allElements: PatternCategories,
        context: TransformationExecutionContext,
        engine: TransformationEngine,
        limit: Long,
        models: PatternModels?
    ): List<MatchResult.Matched> {
        val expressionSupport = ExpressionSupport(engine, context, labelIdGenerator)
        val compilationContext = expressionSupport.newCompilationContext()

        for (varElement in allElements.variables) {
            val varLabel = VariableBinding.variableLabel(varElement.variable.name)
            context.variableScope.setBinding(varElement.variable.name, VariableBinding.LabelBinding(varLabel))
        }

        // Variables and reassignments that read an object the pattern creates (e.g. an `out`
        // parameter assigned `out = created`) can only be computed once the object exists:
        // they are left out of the match plan and bound after the modifications.
        val deferred = deferredBindings(allElements, context)
        val elements = allElements.copy(
            variables = allElements.variables.filter { it.variable.name !in deferred.variableNames },
            variableReassignments = allElements.variableReassignments.filter {
                it.reassignment.name !in deferred.reassignmentNames
            }
        )

        val analyzer = MatchAnalyzer(context.variableScope)
        elements.variables.forEach { analyzer.analyzeVariable(it) }
        elements.variableReassignments.forEach { analyzer.analyzeExpression(it.reassignment.value) }
        elements.collectionAppends.forEach { analyzer.analyzeExpression(it.append.value) }
        elements.whereClauses.forEach { analyzer.analyzeWhereClause(it) }
        (elements.matchableInstances + elements.createInstances + elements.deleteInstances)
            .forEach { analyzer.analyzeObjectInstance(it) }
        (elements.matchableLinks + elements.createLinks + elements.deleteLinks)
            .forEach { analyzer.analyzeLink(it) }
        val referencedInstances = analyzer.getReferencedInstances() + analyzeConditions(elements, context)

        val allMatchable = elements.matchableInstances + elements.deleteInstances
        val matchableNames = allMatchable.map { it.objectInstance.name }.toSet()

        val variableNames = elements.variables.map { it.variable.name }.toSet() +
            elements.variableReassignments.map { it.reassignment.name }.toSet()
        val nodeAnalyzer = ExpressionNodeAnalyzer(matchableNames + variableNames + referencedInstances, context.variableScope.scopeIndex)

        val matchPlan = MatchPlanBuilder(
            getVertexId = { name ->
                (context.variableScope.getVariable(name)
                    as? VariableBinding.InstanceBinding)?.vertexId
            },
            nodeAnalyzer = nodeAnalyzer,
            isCollectionExpression = { expr ->
                expressionSupport.isCollectionType(expressionSupport.resolveExpressionType(expr))
            },
            metamodelData = engine.metamodelData,
            statistics = engine.modelGraph.statistics
        ).build(elements, referencedInstances)

        // Snapshot the declaring-scope bindings of reassigned variables. Building the
        // traversal flips them to label bindings (so within-block accesses see the new
        // value); on a successful match the extractor overwrites them with the computed
        // value, but on a no-match nothing is extracted, so the originals must be restored.
        val reassignSnapshots = allElements.variableReassignments.mapNotNull { el ->
            val name = el.reassignment.name
            context.variableScope.findDeclaringScope(name)?.let { scope ->
                Triple(name, scope, scope.getLocalBinding(name))
            }
        }

        if (System.getProperty(DEBUG_PLAN_PROPERTY) != null) { printPlan(matchPlan) }

        val traversal = buildUnifiedTraversal(
            elements, matchPlan,
            expressionSupport, compilationContext, limit, models, deferred, allElements
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
        val results = executeTraversalAndExtract(traversal, allElements, context, engine, matchedInstanceNames, allSelectNames)
        if (results.isEmpty()) {
            for ((name, scope, original) in reassignSnapshots) {
                if (original != null) scope.setBinding(name, original)
            }
        }
        return results
    }

    /**
     * Analyses the application conditions of the pattern and returns everything their
     * elements read.
     *
     * Every block is analysed in a scope of its own — a child of the match's scope holding
     * the block's own nodes — because that is where the names of a block live: an expression
     * inside a block records them one level deeper than the match, so an analyser of the match
     * would not recognise a single reference to them.
     *
     * @param elements The categorised pattern elements.
     * @param context The current transformation execution context.
     * @return The names referenced from inside the blocks, condition-local and outer alike.
     */
    private fun analyzeConditions(
        elements: PatternCategories,
        context: TransformationExecutionContext
    ): Set<String> {
        val referenced = mutableSetOf<String>()
        for (condition in elements.conditions) {
            val block = ApplicationConditionBlock.from(condition)
            val analyzer = MatchAnalyzer(context.variableScope.conditionScope(block.instanceNames))
            (block.instances + block.references).forEach { analyzer.analyzeObjectInstance(it) }
            block.links.forEach { analyzer.analyzeLink(it) }
            block.whereClauses.forEach { analyzer.analyzeWhereClause(it) }
            referenced += analyzer.getReferencedInstances()
        }
        return referenced
    }

    /**
     * Prints a one-line-per-step summary of [plan] to stdout.
     *
     * Only the structural shape is printed (scans, walks, conditions and filter kinds); the
     * compiled expressions behind property and where constraints are omitted so that the
     * join order stays readable.
     *
     * @param plan The plan to describe.
     */
    private fun printPlan(plan: MatchPlan) {
        println("[match-plan] ${plan.baseSteps.size} steps")
        for ((index, step) in plan.baseSteps.withIndex()) {
            println("  ${index.toString().padStart(2)} ${describeStep(step)}")
        }
    }

    /** Returns a compact, human-readable description of a single [step]. */
    private fun describeStep(step: BaseStep): String = when (step) {
        is BaseStep.VertexScan ->
            "scan   ${step.instanceName}: ${step.className ?: "?"}${if (step.vertexId != null) " (bound)" else ""}"
        is BaseStep.EdgeWalk ->
            "walk   ${step.fromInstanceName} -> ${step.toInstanceName}: ${step.toClassName ?: "?"}" +
                (if (step.isReversed) " (reversed)" else "")
        is BaseStep.InlinePropertyConstraint ->
            "prop   ${step.instanceName}.${step.property.propertyName}${if (step.isConstant) " (const)" else ""}"
        is BaseStep.DeferredPropertyConstraint -> "prop*  ${step.instanceName}.${step.property.propertyName}"
        is BaseStep.SelectNode -> "select ${step.instanceName}"
        is BaseStep.ApplicationCondition ->
            "${if (step.isNegative) "forbid" else "require"}${step.name?.let { " $it" } ?: ""} " +
                "anchor=${step.anchorName ?: "-"} " +
                "inner=[${step.innerSteps.joinToString("; ") { describeStep(it) }}]"
        is BaseStep.EqualityFilter -> "eq     ${step.instanceName}"
        is BaseStep.VariableBinding -> "var    ${step.variable.variable.name}"
        is BaseStep.WhereFilter -> "where"
        is BaseStep.InjectiveConstraint -> "neq    ${step.instanceNameA} != ${step.instanceNameB}"
    }

    /**
     * Assembles the full Gremlin traversal from the match plan, applying limit, modifications,
     * and the final select step.
     *
     * The traversal is fully imperative — no `match()` step is used. All constraints
     * (application-condition, property, injective, where-clauses, variables) are expressed as imperative
     * steps and post-match filters.
     *
     * @param elements Categorised pattern elements.
     * @param plan The abstract match plan produced by [MatchPlanBuilder].
     * @param expressionSupport Shared expression compilation utilities.
     * @param compilationContext The compilation context for unique label generation.
     * @param limit Maximum number of results (-1 for unlimited).
     * @param models The models of the pattern's objects while model inputs are loaded, else null.
     * @return The fully assembled traversal ready for execution.
     */
    @Suppress("UNCHECKED_CAST")
    private fun buildUnifiedTraversal(
        elements: PatternCategories,
        plan: MatchPlan,
        expressionSupport: ExpressionSupport,
        compilationContext: com.mdeo.modeltransformation.compiler.CompilationContext,
        limit: Long,
        models: PatternModels?,
        deferred: DeferredBindings = DeferredBindings.NONE,
        allElements: PatternCategories = elements
    ): GraphTraversal<Vertex, Any> {
        val traversalBuilder = MatchTraversalBuilder(expressionSupport, compilationContext, expressionSupport.engine, models)
        var t: GraphTraversal<Vertex, Map<String, Any>> =
            traversalBuilder.buildBaseTraversal(plan) as GraphTraversal<Vertex, Map<String, Any>>

        t = applyLimit(t, limit)

        val matchedNames = elements.matchableInstances.map { it.objectInstance.name }
        t = GraphModificationApplier(elements, expressionSupport, compilationContext).apply(t, matchedNames)
        t = applyDeferredBindings(t, deferred, expressionSupport, compilationContext)
        t = applyCollectionAppends(t, allElements, expressionSupport, compilationContext)

        val planBoundNames = plan.baseSteps.mapNotNull { step ->
            when (step) {
                is BaseStep.VertexScan -> step.instanceName
                is BaseStep.EdgeWalk -> step.toInstanceName
                else -> null
            }
        }
        val allSelectNames = (elements.allInstanceNames + planBoundNames).distinct()

        val variableLabels = allVariableLabels(allElements)
        return addSelectStep(t, allSelectNames, variableLabels)
    }

    /**
     * The variables and reassignments of a pattern that read objects the pattern creates.
     *
     * @param variables The deferred variable declarations.
     * @param reassignments The deferred reassignments.
     */
    private data class DeferredBindings(
        val variables: List<TypedPatternVariableElement>,
        val reassignments: List<TypedPatternVariableReassignmentElement>
    ) {
        val variableNames: Set<String> get() = variables.map { it.variable.name }.toSet()
        val reassignmentNames: Set<String> get() = reassignments.map { it.reassignment.name }.toSet()

        companion object {
            val NONE = DeferredBindings(emptyList(), emptyList())
        }
    }

    /**
     * Finds the variables and reassignments whose value reads an object created by the pattern,
     * directly or through another deferred variable.
     */
    private fun deferredBindings(elements: PatternCategories, context: TransformationExecutionContext): DeferredBindings {
        val created = elements.createInstances.map { it.objectInstance.name }.toMutableSet()
        if (created.isEmpty()) {
            return DeferredBindings.NONE
        }
        val deferredVariables = mutableListOf<TypedPatternVariableElement>()
        var changed = true
        while (changed) {
            changed = false
            for (variable in elements.variables) {
                if (variable in deferredVariables) continue
                if (reads(variable.variable.value, created, context)) {
                    deferredVariables += variable
                    created += variable.variable.name
                    changed = true
                }
            }
        }
        val deferredReassignments = elements.variableReassignments.filter { reads(it.reassignment.value, created, context) }
        return DeferredBindings(deferredVariables, deferredReassignments)
    }

    /**
     * Whether an expression reads one of [names].
     */
    private fun reads(expression: com.mdeo.expression.ast.expressions.TypedExpression, names: Set<String>, context: TransformationExecutionContext): Boolean {
        val analyzer = ExpressionNodeAnalyzer(names, context.variableScope.scopeIndex)
        return analyzer.findReferencedNodes(expression).isNotEmpty()
    }

    /**
     * Binds the deferred variables and reassignments after the modifications, in pattern order,
     * each to the step label the result extraction reads.
     */
    @Suppress("UNCHECKED_CAST")
    private fun applyDeferredBindings(
        traversal: GraphTraversal<Vertex, Map<String, Any>>,
        deferred: DeferredBindings,
        expressionSupport: ExpressionSupport,
        compilationContext: com.mdeo.modeltransformation.compiler.CompilationContext
    ): GraphTraversal<Vertex, Map<String, Any>> {
        var t = traversal
        val bindings = deferred.variables.map { it.variable.name to it.variable.value } +
            deferred.reassignments.map { it.reassignment.name to it.reassignment.value }
        for ((name, value) in bindings) {
            val label = VariableBinding.variableLabel(name)
            val compiled = expressionSupport.engine.expressionCompilerRegistry.compile(value, compilationContext)
            val mapped = MatchTraversalBuilder.collectedIfCollection(compiled.traversal, value, expressionSupport.engine)
            t = t.map(mapped).`as`(label) as GraphTraversal<Vertex, Map<String, Any>>
            if (deferred.reassignmentNames.contains(name)) {
                expressionSupport.context.variableScope.findDeclaringScope(name)
                    ?.setBinding(name, VariableBinding.LabelBinding(label))
            }
        }
        return t
    }

    /**
     * Computes the values of the pattern's additions to collection outputs (`name += value`)
     * after the modifications, so a value can be an object the pattern creates. Each value gets
     * a label of its own, read by the result extraction.
     */
    @Suppress("UNCHECKED_CAST")
    private fun applyCollectionAppends(
        traversal: GraphTraversal<Vertex, Map<String, Any>>,
        elements: PatternCategories,
        expressionSupport: ExpressionSupport,
        compilationContext: com.mdeo.modeltransformation.compiler.CompilationContext
    ): GraphTraversal<Vertex, Map<String, Any>> {
        var t = traversal
        for ((index, element) in elements.collectionAppends.withIndex()) {
            val value = element.append.value
            val compiled = expressionSupport.engine.expressionCompilerRegistry.compile(value, compilationContext)
            val mapped = MatchTraversalBuilder.collectedIfCollection(compiled.traversal, value, expressionSupport.engine)
            t = t.map(mapped).`as`(appendLabel(index, element.append.name)) as GraphTraversal<Vertex, Map<String, Any>>
        }
        return t
    }

    /**
     * The step label of the value of the [index]-th addition of a pattern, to collection [name].
     */
    private fun appendLabel(index: Int, name: String): String = VariableBinding.variableLabel("+$index:$name")

    /**
     * Returns the Gremlin step labels for all bound variables — declarations, reassignments and
     * the values added to collections — that must be included in the final `select()` and
     * result extraction.
     */
    private fun allVariableLabels(elements: PatternCategories): List<String> =
        elements.variables.map { VariableBinding.variableLabel(it.variable.name) } +
        elements.variableReassignments.map { VariableBinding.variableLabel(it.reassignment.name) } +
        elements.collectionAppends.mapIndexed { index, it -> appendLabel(index, it.append.name) }

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
        val variableLabels = allVariableLabels(elements)
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
            val value = collectionOrValue(result[VariableBinding.variableLabel(varName)], varElement.variable.value, engine)
            bindings[varName] = value
            context.variableScope.setBinding(varName, VariableBinding.ValueBinding(value))
        }

        // Reassignments write their computed value back to the scope where the variable was
        // originally declared (not the current match scope), so the update survives the match
        // scope and is observed by subsequent statements and loop iterations. They are not
        // added to `bindings`, which would otherwise shadow the variable in the current scope.
        for (reassignElement in elements.variableReassignments) {
            val varName = reassignElement.reassignment.name
            val value = collectionOrValue(
                result[VariableBinding.variableLabel(varName)], reassignElement.reassignment.value, engine
            )
            val declaringScope = context.variableScope.findDeclaringScope(varName) ?: context.variableScope
            // a model element is kept as a reference, which survives a rebuild of the graph
            val binding = if (value is Vertex) {
                VariableBinding.InstanceBinding(engine.modelGraph.createVertexRef(value.id()))
            } else {
                VariableBinding.ValueBinding(value)
            }
            declaringScope.setBinding(varName, binding)
        }

        // Additions go to the collection bound in the scope declaring it; every match of a
        // for-match adds its value.
        for ((index, appendElement) in elements.collectionAppends.withIndex()) {
            val name = appendElement.append.name
            val target = (context.variableScope.findDeclaringScope(name)?.getLocalBinding(name)
                as? VariableBinding.ValueBinding)?.value as? CollectionValue
                ?: throw IllegalStateException("'$name' is not a collection; '+=' adds to a collection output")
            val value = result[appendLabel(index, name)]
            if (engine.isCollectionType(appendElement.append.value.evalType) && value is Iterable<*>) {
                value.forEach { target.add(CollectionValue.item(it, engine.modelGraph)) }
            } else {
                target.add(CollectionValue.item(value, engine.modelGraph))
            }
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
        return MatchResult.Matched(bindings, instanceMappings, matchedNodeIds, createdNodeIds, deletedNodeIds,
            edgesModified = elements.createLinks.isNotEmpty() || elements.deleteLinks.isNotEmpty())
    }

    /**
     * Turns the value of a collection-typed expression (a list, see
     * [MatchTraversalBuilder.collectedIfCollection]) into a [CollectionValue]; other values are
     * returned as they are.
     */
    private fun collectionOrValue(
        value: Any?,
        expression: com.mdeo.expression.ast.expressions.TypedExpression,
        engine: TransformationEngine
    ): Any? {
        if (value !is Iterable<*> || !engine.isCollectionType(expression.evalType)) {
            return value
        }
        return CollectionValue.of(engine.isSetType(expression.evalType), value, engine.modelGraph)
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
