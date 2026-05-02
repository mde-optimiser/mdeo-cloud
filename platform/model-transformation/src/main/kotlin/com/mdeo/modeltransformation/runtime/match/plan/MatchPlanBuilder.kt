package com.mdeo.modeltransformation.runtime.match.plan

import com.mdeo.expression.ast.expressions.TypedExpression
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.modeltransformation.ast.EdgeLabelUtils
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLinkElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstance
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstanceElement
import com.mdeo.modeltransformation.compiler.VariableBinding
import com.mdeo.modeltransformation.runtime.match.ExpressionNodeAnalyzer
import com.mdeo.modeltransformation.runtime.match.Island
import com.mdeo.modeltransformation.runtime.match.IslandGrouper
import com.mdeo.modeltransformation.runtime.match.IslandTraversalUtils
import com.mdeo.modeltransformation.runtime.match.PatternCategories

/**
 * Builds a fully imperative [MatchPlan] from categorised pattern elements.
 *
 * The builder places all work into [MatchPlan.baseSteps]:
 *
 * 1. Connected-component starts, edge walks, and reachable vertices form the core traversal.
 * 2. Property constraints whose values are constants or expressions referencing only
 *    already-bound nodes are inlined right after their owning instance.
 * 3. Single-anchor island constraints are inlined right after their anchor is bound.
 * 4. Orphan link constraints are inlined once both endpoints are covered.
 * 5. Uncovered instances, referenced instances, non-inlined islands, variables,
 *    deferred property constraints, and where clauses are appended as imperative steps.
 * 6. Only injective and cross-node where clauses go into [MatchPlan.postMatchFilters].
 *
 * No `match()` step is ever used — the plan is purely imperative.
 *
 * @param getVertexId Returns the pre-bound vertex ID for an instance name.
 * @param nodeAnalyzer Analyzes expression trees to find referenced match nodes.
 * @param isCollectionExpression Returns true when an expression evaluates to a collection type.
 */
internal class MatchPlanBuilder(
    private val getVertexId: (String) -> Any?,
    private val nodeAnalyzer: ExpressionNodeAnalyzer,
    private val isCollectionExpression: (TypedExpression) -> Boolean,
    private val metamodelData: MetamodelData = MetamodelData.empty()
) {

    /**
     * Builds a [MatchPlan] from the given [elements] and [referencedInstances].
     *
     * All construction logic is encapsulated in a fresh [PlanExecution] instance
     * to keep mutable state local to the call and each phase concise.
     *
     * @param elements The categorised pattern elements to plan.
     * @param referencedInstances Instance names referenced externally that must be bound
     *   even if they are not part of the matchable instance set.
     * @return The completed [MatchPlan].
     */
    fun build(elements: PatternCategories, referencedInstances: Set<String>): MatchPlan =
        PlanExecution(elements, referencedInstances).run()

    /**
     * Partitions [instances] into connected components given the [links] between them.
     *
     * Two instances belong to the same component when they are transitively connected
     * through one or more links. Instances with no links each form a singleton component.
     *
     * @param instances All matchable instances to partition.
     * @param links All matchable links that may connect instances.
     * @return A list of [MatchComponent]s, one per connected component.
     */
    private fun buildComponents(
        instances: List<TypedPatternObjectInstanceElement>,
        links: List<TypedPatternLinkElement>
    ): List<MatchComponent> {
        if (instances.isEmpty()) { return emptyList() }
        val names = instances.map { it.objectInstance.name }
        val adjacency = names.associateWith { mutableListOf<TypedPatternLinkElement>() }.toMutableMap()
        for (link in links) {
            val src = link.link.source.objectName
            val tgt = link.link.target.objectName
            if (src in adjacency && tgt in adjacency) {
                adjacency.getValue(src).add(link)
                adjacency.getValue(tgt).add(link)
            }
        }
        val visited = mutableSetOf<String>()
        return names.mapNotNull { name ->
            if (!visited.add(name)) { return@mapNotNull null }
            val queue = ArrayDeque<String>()
            queue.add(name)
            val compInstances = mutableListOf<String>()
            val compLinks = linkedSetOf<TypedPatternLinkElement>()
            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                compInstances.add(cur)
                for (link in adjacency.getValue(cur)) {
                    compLinks.add(link)
                    val next = if (link.link.source.objectName == cur) link.link.target.objectName else link.link.source.objectName
                    if (visited.add(next)) { queue.add(next) }
                }
            }
            MatchComponent(compInstances, compLinks.toList())
        }
    }

    /**
     * Chooses the best starting instance for traversing a connected component.
     *
     * Prefers a pre-bound instance (one with a known vertex ID) so that the traversal
     * starts from a single vertex. Falls back to any instance with a class constraint,
     * allowing a labelled vertex scan.
     *
     * @param componentInstances The ordered list of instance names in the component.
     * @param instanceMap Map from instance name to its element.
     * @return The chosen starting instance name, or `null` if the component is empty.
     */
    private fun chooseComponentStart(
        componentInstances: List<String>,
        instanceMap: Map<String, TypedPatternObjectInstanceElement>
    ): String? {
        for (name in componentInstances) {
            if (getVertexId(name) != null) { return name }
        }
        return componentInstances.firstOrNull { instanceMap[it]?.objectInstance?.className != null }
    }

    /**
     * Identifies orphan links — forbid or require links whose both endpoints are
     * main-pattern (matchable) instances rather than constraint-only instances.
     *
     * Orphan links connect two already-matchable nodes without introducing new
     * constraint nodes; they are tracked and handled separately from island links.
     *
     * @param conditionInstances All forbid or require instances for the block.
     * @param conditionLinks All forbid or require links for the block.
     * @param matchableNames Names of all matched (non-condition) instances.
     * @return List of [OrphanLinkInfo] objects describing each orphan link.
     */
    private fun identifyOrphanLinks(
        conditionInstances: List<TypedPatternObjectInstanceElement>,
        conditionLinks: List<TypedPatternLinkElement>,
        matchableNames: Set<String>
    ): List<OrphanLinkInfo> {
        val conditionNames = conditionInstances.map { it.objectInstance.name }.toSet()
        return conditionLinks.mapNotNull { link ->
            val src = link.link.source.objectName
            val tgt = link.link.target.objectName
            if (src !in conditionNames && src in matchableNames &&
                tgt !in conditionNames && tgt in matchableNames) {
                OrphanLinkInfo(src, tgt, EdgeLabelUtils.computeEdgeLabel(
                    link.link.source.propertyName, link.link.target.propertyName
                ))
            } else null
        }
    }

    /**
     * Merges instances that share the same name.
     *
     * When the same instance name appears multiple times (e.g., once with a className and once
     * without for a re-reference with additional property constraints), the className is taken
     * from the first occurrence that has one, and properties are combined from all occurrences.
     *
     * @param instances The (possibly duplicated) list of instances to merge.
     * @return A de-duplicated list of instances with properties merged per name.
     */
    private fun mergeInstancesByName(
        instances: List<TypedPatternObjectInstanceElement>
    ): List<TypedPatternObjectInstanceElement> {
        val grouped = instances.groupBy { it.objectInstance.name }
        return grouped.map { (_, elements) ->
            if (elements.size == 1) { return@map elements.first() }
            val className = elements.firstNotNullOfOrNull { it.objectInstance.className }
            val modifier = elements.firstNotNullOfOrNull { it.objectInstance.modifier }
            val allProperties = elements.flatMap { it.objectInstance.properties }
            TypedPatternObjectInstanceElement(
                objectInstance = TypedPatternObjectInstance(
                    modifier = modifier,
                    name = elements.first().objectInstance.name,
                    className = className,
                    properties = allProperties
                )
            )
        }
    }

    private data class DeferredPropertyInfo(
        val instanceName: String,
        val className: String?,
        val property: com.mdeo.modeltransformation.ast.patterns.TypedPatternPropertyAssignment
    )

    private data class OrphanLinkInfo(
        val sourceName: String,
        val targetName: String,
        val edgeLabel: String
    )

    private data class MatchComponent(
        val instances: List<String>,
        val links: List<TypedPatternLinkElement>
    )

    /**
     * Encapsulates all mutable state for a single invocation of [build].
     *
     * Splitting the execution into an inner class keeps each phase concise and avoids
     * threading the entire mutable context through every private method signature.
     * The outer-class members ([getVertexId], [nodeAnalyzer], [isCollectionExpression],
     * and the utility methods) are accessible via the implicit outer reference.
     */
    private inner class PlanExecution(
        private val elements: PatternCategories,
        private val referencedInstances: Set<String>
    ) {
        private val allMatchable = mergeInstancesByName(elements.matchableInstances + elements.deleteInstances)
        private val allMatchableLinks = elements.matchableLinks + elements.deleteLinks
        private val instanceMap = allMatchable.associateBy { it.objectInstance.name }
        private val matchableNames = allMatchable.map { it.objectInstance.name }.toSet()
        private val variableNames = elements.variables.map { it.variable.name }.toSet()
        private val forbidIslands = IslandGrouper.groupIntoIslands(elements.forbidInstances, elements.forbidLinks)
        private val requireIslands = IslandGrouper.groupIntoIslands(elements.requireInstances, elements.requireLinks)
        private val forbidOrphanLinks = identifyOrphanLinks(elements.forbidInstances, elements.forbidLinks, matchableNames)
        private val requireOrphanLinks = identifyOrphanLinks(elements.requireInstances, elements.requireLinks, matchableNames)
        private val baseSteps = mutableListOf<BaseStep>()
        private val postMatchFilters = mutableListOf<PostMatchFilter>()
        private val coveredInstances = mutableSetOf<String>()
        private val coveredLinks = mutableSetOf<TypedPatternLinkElement>()
        private val inlinedIslandIndices = mutableSetOf<Int>()
        private val inlinedOrphanLinkIndices = mutableSetOf<Pair<Boolean, Int>>()
        private val deferredProperties = mutableListOf<DeferredPropertyInfo>()

        /**
         * Executes all plan construction phases and returns the completed [MatchPlan].
         *
         * @return The [MatchPlan] ready for compilation into a Gremlin traversal.
         */
        fun run(): MatchPlan {
            val components = buildComponents(allMatchable, allMatchableLinks)
            val sortedComponents = components.sortedByDescending { comp ->
                comp.instances.any { getVertexId(it) != null }
            }
            processComponents(sortedComponents)
            addUncoveredInstances()
            addReferencedInstances()
            addUncoveredLinks()
            addDeferredIslands()
            addDeferredOrphanLinks()
            addVariableBindings()
            addDeferredPropertyConstraints()
            addWhereClauses()
            addInjectiveConstraints()
            return MatchPlan(baseSteps, postMatchFilters)
        }

        /**
         * Iterates over each connected component and delegates to [processComponent].
         *
         * @param sortedComponents Components in traversal-start priority order.
         */
        private fun processComponents(sortedComponents: List<MatchComponent>) {
            for (component in sortedComponents) {
                processComponent(component)
            }
        }

        /**
         * Emits all steps for a single connected component.
         *
         * Chooses the best starting vertex, emits a [BaseStep.VertexScan], applies inline
         * constraints for the start instance, then delegates link traversal to
         * [processComponentLinks].
         *
         * @param component The component to process.
         */
        private fun processComponent(component: MatchComponent) {
            val startName = chooseComponentStart(component.instances, instanceMap) ?: return
            val startInstance = instanceMap[startName]!!
            val className = startInstance.objectInstance.className
            val vertexId = getVertexId(startName)
            if (className == null && vertexId == null) {
                throw IllegalStateException(
                    "Instance '${startName}' has no class constraint and no pre-bound vertex. " +
                    "All matchable instances must be typed or pre-bound."
                )
            }
            baseSteps.add(BaseStep.VertexScan(startName, className, vertexId))
            coveredInstances.add(startName)
            applyInlineConstraintsAt(startName, startInstance)
            if (component.links.isNotEmpty()) {
                processComponentLinks(component.links, startName)
            }
        }

        /**
         * Emits [BaseStep.EdgeWalk] steps for each link in BFS order.
         *
         * After each newly covered instance is added to [coveredInstances], inline
         * constraints (property, island, orphan-link) are applied immediately.
         *
         * @param links The component's links to walk.
         * @param startName The name of the already-covered start vertex.
         */
        private fun processComponentLinks(links: List<TypedPatternLinkElement>, startName: String) {
            val orderedLinks = IslandTraversalUtils.orderLinksByBFS(links, startName)
            var currentNode = startName
            for ((link, isReversed) in orderedLinks) {
                coveredLinks.add(link)
                val fromName = if (isReversed) link.link.target.objectName else link.link.source.objectName
                val toName = if (isReversed) link.link.source.objectName else link.link.target.objectName
                val toInstance = instanceMap[toName]
                baseSteps.add(
                    BaseStep.EdgeWalk(
                        link = link,
                        isReversed = isReversed,
                        fromInstanceName = fromName,
                        toInstanceName = toName,
                        toClassName = toInstance?.objectInstance?.className,
                        toVertexId = getVertexId(toName),
                        needsSelect = fromName != currentNode
                    )
                )
                coveredInstances.add(toName)
                currentNode = toName
                applyInlineConstraintsAt(toName, toInstance)
            }
        }

        /**
         * Applies all inline constraints for [instanceName] immediately after it is covered.
         *
         * This includes inline property constraints for [instance] (when non-null), island
         * constraints anchored at [instanceName], and orphan-link constraints whose both
         * endpoints are now covered.
         *
         * @param instanceName The name of the newly covered instance.
         * @param instance The element for [instanceName], or `null` when not in the matchable set.
         */
        private fun applyInlineConstraintsAt(
            instanceName: String,
            instance: TypedPatternObjectInstanceElement?
        ) {
            if (instance != null) {
                addInlinePropertyConstraints(instance)
            }
            tryInlineIslands(instanceName)
            inlineOrphanLinks(forbidOrphanLinks, isNegative = true)
            inlineOrphanLinks(requireOrphanLinks, isNegative = false)
        }

        /**
         * Evaluates each property of [instance] and either emits an inline property
         * constraint or defers it to [deferredProperties].
         *
         * A property is inlined when its expression is a constant (no referenced nodes,
         * non-collection) or references only already-covered instances and no pattern
         * variables. All other properties are deferred.
         *
         * @param instance The instance whose properties are to be evaluated.
         */
        private fun addInlinePropertyConstraints(instance: TypedPatternObjectInstanceElement) {
            for (property in instance.objectInstance.properties) {
                if (property.operator != "==") { continue }
                val referencedNodes = nodeAnalyzer.findReferencedNodes(property.value)
                val referencedVars = referencedNodes.filter { it in variableNames }
                val isConstant = referencedNodes.isEmpty() && !isCollectionExpression(property.value)
                val canInline = referencedVars.isEmpty() && (isConstant || (referencedNodes.isNotEmpty()
                    && referencedNodes.all { it in coveredInstances }
                    && !isCollectionExpression(property.value)))
                if (canInline) {
                    baseSteps.add(
                        BaseStep.InlinePropertyConstraint(
                            instance.objectInstance.name,
                            instance.objectInstance.className,
                            property,
                            isConstant
                        )
                    )
                } else {
                    deferredProperties.add(
                        DeferredPropertyInfo(
                            instance.objectInstance.name,
                            instance.objectInstance.className,
                            property
                        )
                    )
                }
            }
        }

        /**
         * Attempts to inline all island constraints anchored at [instanceName].
         *
         * An island is inlined when it has exactly one anchor and that anchor equals
         * [currentNode]. Already-processed islands (in [inlinedIslandIndices]) are skipped.
         *
         * An island is inlinable when ALL of the following are covered:
         * - Every anchor node (main-pattern nodes connected to the island via links).
         * - Every main-pattern (matchable-only, no modifier) node whose class is the same
         *   as any island node's class — needed so that injective constraints against those
         *   nodes can reference their already-bound step labels.
         *
         * This replaces separate single-anchor and multi-anchor helper methods: all islands
         * (regardless of anchor count) are inlined as soon as their required nodes are ready.
         *
         * @param currentNode The name of the node most recently added to the traversal.
         *   Islands that need `needsSelect = false` (i.e. directly at the anchor) are only
         *   emitted without a select when [currentNode] equals the chosen best anchor.
         */
        private fun tryInlineIslands(currentNode: String) {
            val allIslands = forbidIslands.map { it to true } + requireIslands.map { it to false }
            for ((index, pair) in allIslands.withIndex()) {
                if (index in inlinedIslandIndices) { continue }
                val (island, isNegative) = pair
                if (island.links.isEmpty()) { continue }
                val islandNames = island.instances.map { it.objectInstance.name }.toSet()
                val anchors = IslandTraversalUtils.findAnchorNames(island.links, islandNames, matchableNames)
                if (anchors.isEmpty()) { continue }

                // Require all anchors and all main-pattern nodes type-compatible with island
                // nodes to be covered before inlining (needed for injective constraint labels).
                val injectiveRequiredNodes = computeInjectiveRequiredNodes(island)
                val requiredNodes = anchors + injectiveRequiredNodes
                if (!requiredNodes.all { it in coveredInstances }) { continue }

                val bestAnchor = IslandTraversalUtils.selectBestAnchor(anchors, island.links, metamodelData)
                    ?: continue
                val orderedLinks = IslandTraversalUtils.orderLinksByBFS(island.links, bestAnchor)
                val (injectiveConstraints, nodesNeedingInjectiveLabel) =
                    buildIslandInjectiveConstraints(island, orderedLinks, bestAnchor)
                val backtrackLabels = IslandTraversalUtils.findNodesNeedingBacktrackLabel(orderedLinks, bestAnchor)
                val needsSelect = bestAnchor != currentNode
                baseSteps.add(
                    BaseStep.InlineIslandConstraint(
                        island = island,
                        anchorName = bestAnchor,
                        orderedLinks = orderedLinks,
                        nodesNeedingBacktrackLabel = backtrackLabels + nodesNeedingInjectiveLabel,
                        isNegative = isNegative,
                        needsSelect = needsSelect,
                        injectiveConstraints = injectiveConstraints
                    )
                )
                inlinedIslandIndices.add(index)
            }
        }

        /**
         * Computes the set of main-pattern instance names (including delete nodes) that
         * must be covered before [island] can be inlined, because they share a class with
         * at least one island instance and therefore require injective constraints.
         *
         * Only exact class-name equality is tested, consistent with [addInjectiveConstraints].
         */
        private fun computeInjectiveRequiredNodes(island: Island): Set<String> {
            val result = mutableSetOf<String>()
            for (islandInst in island.instances) {
                val islandClass = islandInst.objectInstance.className ?: continue
                for (mainInst in allMatchable) {
                    val mainClass = mainInst.objectInstance.className ?: continue
                    if (islandClass == mainClass) {
                        result.add(mainInst.objectInstance.name)
                    }
                }
            }
            return result
        }

        /**
         * Builds the injective-constraint map for an island's chain traversal.
         *
         * For each non-anchor island node (in BFS order), computes the list of step labels
         * that the node must be distinct from:
         * - [VariableBinding.stepLabel] of every main-pattern node (including delete nodes)
         *   that shares the same class.
         * - `__inline_<prevName>` for every earlier island node in BFS order that shares
         *   the same class (so those nodes are labelled in the chain and the constraint can
         *   reference them).
         *
         * Also returns the set of island node names that must receive an `__inline_<name>`
         * step label in the chain (because a later island node has an injective constraint
         * against them).
         */
        private fun buildIslandInjectiveConstraints(
            island: Island,
            orderedLinks: List<Pair<TypedPatternLinkElement, Boolean>>,
            anchorName: String
        ): Pair<Map<String, List<String>>, Set<String>> {
            val constraints = mutableMapOf<String, MutableList<String>>()
            val nodesNeedingLabel = mutableSetOf<String>()

            val islandInstanceMap = island.instances.associateBy { it.objectInstance.name }

            val islandBfsOrder = mutableListOf<String>()
            val visited = mutableSetOf(anchorName)
            for ((link, isReversed) in orderedLinks) {
                val toNode = if (isReversed) link.link.source.objectName else link.link.target.objectName
                if (toNode in islandInstanceMap && visited.add(toNode)) {
                    islandBfsOrder.add(toNode)
                }
            }

            for ((i, islandNode) in islandBfsOrder.withIndex()) {
                val islandClass = islandInstanceMap[islandNode]?.objectInstance?.className ?: continue

                for (mainInst in allMatchable) {
                    val mainClass = mainInst.objectInstance.className ?: continue
                    if (islandClass == mainClass) {
                        constraints.getOrPut(islandNode) { mutableListOf() }
                            .add(VariableBinding.stepLabel(mainInst.objectInstance.name))
                    }
                }

                for (j in 0 until i) {
                    val prevNode = islandBfsOrder[j]
                    val prevClass = islandInstanceMap[prevNode]?.objectInstance?.className ?: continue
                    if (islandClass == prevClass) {
                        nodesNeedingLabel.add(prevNode)
                        constraints.getOrPut(islandNode) { mutableListOf() }
                            .add("__inline_$prevNode")
                    }
                }
            }

            return Pair(constraints, nodesNeedingLabel)
        }

        /**
         * Builds injective constraints for a disconnected island (no links to main pattern).
         *
         * For each island instance, returns a list of outer-traversal step labels for all
         * main-pattern nodes (including delete nodes) that share the same class. These are
         * used to exclude already-matched vertices from the disconnected count sub-traversal.
         */
        private fun buildDisconnectedInjectiveConstraints(island: Island): Map<String, List<String>> {
            val constraints = mutableMapOf<String, MutableList<String>>()
            for (islandInst in island.instances) {
                val islandClass = islandInst.objectInstance.className ?: continue
                val instName = islandInst.objectInstance.name
                for (mainInst in allMatchable) {
                    val mainClass = mainInst.objectInstance.className ?: continue
                    if (islandClass == mainClass) {
                        constraints.getOrPut(instName) { mutableListOf() }
                            .add(VariableBinding.stepLabel(mainInst.objectInstance.name))
                    }
                }
            }
            return constraints
        }

        /**
         * Attempts to inline any [orphanLinks] whose both endpoints are already covered.
         *
         * Links already processed (tracked via [inlinedOrphanLinkIndices]) are skipped.
         *
         * @param orphanLinks The list of orphan links to attempt inlining.
         * @param isNegative `true` for forbid links, `false` for require links.
         */
        private fun inlineOrphanLinks(orphanLinks: List<OrphanLinkInfo>, isNegative: Boolean) {
            for ((index, info) in orphanLinks.withIndex()) {
                val key = Pair(isNegative, index)
                if (key in inlinedOrphanLinkIndices) { continue }
                if (info.sourceName !in coveredInstances || info.targetName !in coveredInstances) { continue }
                baseSteps.add(
                    BaseStep.InlineOrphanLinkConstraint(info.sourceName, info.targetName, info.edgeLabel, isNegative)
                )
                inlinedOrphanLinkIndices.add(key)
            }
        }

        /**
         * Emits [BaseStep.VertexScan] steps for matchable instances not covered during
         * the connected-component traversal phase, and attempts to inline any island
         * constraints that become ready after each instance is covered.
         */
        private fun addUncoveredInstances() {
            for (instance in allMatchable) {
                val name = instance.objectInstance.name
                if (name in coveredInstances) { continue }
                val vertexId = getVertexId(name)
                val className = instance.objectInstance.className
                when {
                    vertexId != null -> baseSteps.add(BaseStep.VertexScan(name, className, vertexId))
                    className != null -> baseSteps.add(BaseStep.VertexScan(name, className, null))
                    else -> throw IllegalStateException(
                        "Instance '${name}' has no class constraint and no pre-bound vertex. " +
                        "All matchable instances must be typed or pre-bound."
                    )
                }
                coveredInstances.add(name)
                applyInlineConstraintsAt(name, instance)
            }
        }

        /**
         * Emits [BaseStep.VertexScan] steps for externally referenced instances not yet
         * covered and not part of the matchable instance set.
         *
         * A referenced instance is silently skipped when no pre-bound vertex ID is available.
         */
        private fun addReferencedInstances() {
            for (refName in referencedInstances) {
                if (refName in coveredInstances || refName in instanceMap) continue
                val vertexId = getVertexId(refName) ?: continue
                baseSteps.add(BaseStep.VertexScan(refName, null, vertexId))
                coveredInstances.add(refName)
            }
        }

        /**
         * Emits steps for matchable links not covered during component traversal.
         *
         * - Both endpoints covered → [BaseStep.InlineOrphanLinkConstraint].
         * - Source covered only → forward [BaseStep.EdgeWalk] to reach the target.
         * - Target covered only → reversed [BaseStep.EdgeWalk] to reach the source.
         * - Neither covered → forward [BaseStep.EdgeWalk] from source to target.
         */
        private fun addUncoveredLinks() {
            for (link in allMatchableLinks) {
                if (link in coveredLinks) continue
                val srcName = link.link.source.objectName
                val tgtName = link.link.target.objectName
                val edgeLabel = EdgeLabelUtils.computeEdgeLabel(
                    link.link.source.propertyName, link.link.target.propertyName
                )
                when {
                    srcName in coveredInstances && tgtName in coveredInstances -> {
                        baseSteps.add(
                            BaseStep.InlineOrphanLinkConstraint(
                                sourceName = srcName,
                                targetName = tgtName,
                                edgeLabel = edgeLabel,
                                isNegative = false
                            )
                        )
                    }
                    srcName in coveredInstances -> {
                        val toInstance = instanceMap[tgtName]
                        baseSteps.add(
                            BaseStep.EdgeWalk(
                                link = link, isReversed = false,
                                fromInstanceName = srcName, toInstanceName = tgtName,
                                toClassName = toInstance?.objectInstance?.className,
                                toVertexId = getVertexId(tgtName),
                                needsSelect = true
                            )
                        )
                        coveredInstances.add(tgtName)
                    }
                    tgtName in coveredInstances -> {
                        val fromInstance = instanceMap[srcName]
                        baseSteps.add(
                            BaseStep.EdgeWalk(
                                link = link, isReversed = true,
                                fromInstanceName = tgtName, toInstanceName = srcName,
                                toClassName = fromInstance?.objectInstance?.className,
                                toVertexId = getVertexId(srcName),
                                needsSelect = true
                            )
                        )
                        coveredInstances.add(srcName)
                    }
                    else -> {
                        val toInstance = instanceMap[tgtName]
                        baseSteps.add(
                            BaseStep.EdgeWalk(
                                link = link, isReversed = false,
                                fromInstanceName = srcName, toInstanceName = tgtName,
                                toClassName = toInstance?.objectInstance?.className,
                                toVertexId = getVertexId(tgtName),
                                needsSelect = true
                            )
                        )
                    }
                }
            }
        }

        /**
         * Emits [BaseStep.InlineIslandConstraint] or [BaseStep.DisconnectedIslandFilter] steps
         * for all islands not inlined during component traversal.
         *
         * Any connected island that was not inlined earlier (because some required nodes
         * were not covered in time) is emitted here with [BaseStep.InlineIslandConstraint]
         * and `needsSelect = true`. Injective constraints are computed and included.
         * Truly disconnected islands (no links) use [BaseStep.DisconnectedIslandFilter]
         * with injective constraints so already-matched vertices are excluded from the count.
         */
        private fun addDeferredIslands() {
            val allIslands = forbidIslands.map { it to true } + requireIslands.map { it to false }
            for ((index, pair) in allIslands.withIndex()) {
                if (index in inlinedIslandIndices) continue
                val (island, isNegative) = pair
                if (island.links.isEmpty()) {
                    baseSteps.add(BaseStep.DisconnectedIslandFilter(
                        island, isNegative,
                        buildDisconnectedInjectiveConstraints(island)
                    ))
                    continue
                }
                val islandNames = island.instances.map { it.objectInstance.name }.toSet()
                val anchorNames = IslandTraversalUtils.findAnchorNames(island.links, islandNames, matchableNames)
                val anchor = IslandTraversalUtils.selectBestAnchor(anchorNames, island.links, metamodelData)
                if (anchor == null) {
                    baseSteps.add(BaseStep.DisconnectedIslandFilter(island, isNegative))
                    continue
                }
                val orderedLinks = IslandTraversalUtils.orderLinksByBFS(island.links, anchor)
                val (injectiveConstraints, nodesNeedingInjectiveLabel) =
                    buildIslandInjectiveConstraints(island, orderedLinks, anchor)
                val backtrackLabels = IslandTraversalUtils.findNodesNeedingBacktrackLabel(orderedLinks, anchor)
                baseSteps.add(
                    BaseStep.InlineIslandConstraint(
                        island = island,
                        anchorName = anchor,
                        orderedLinks = orderedLinks,
                        nodesNeedingBacktrackLabel = backtrackLabels + nodesNeedingInjectiveLabel,
                        isNegative = isNegative,
                        needsSelect = true,
                        injectiveConstraints = injectiveConstraints
                    )
                )
            }
        }

        /**
         * Emits [BaseStep.InlineOrphanLinkConstraint] steps for orphan links not inlined
         * during component traversal.
         */
        private fun addDeferredOrphanLinks() {
            for ((index, info) in forbidOrphanLinks.withIndex()) {
                if (Pair(true, index) in inlinedOrphanLinkIndices) continue
                baseSteps.add(
                    BaseStep.InlineOrphanLinkConstraint(info.sourceName, info.targetName, info.edgeLabel, isNegative = true)
                )
            }
            for ((index, info) in requireOrphanLinks.withIndex()) {
                if (Pair(false, index) in inlinedOrphanLinkIndices) continue
                baseSteps.add(
                    BaseStep.InlineOrphanLinkConstraint(info.sourceName, info.targetName, info.edgeLabel, isNegative = false)
                )
            }
        }

        /**
         * Emits [BaseStep.VariableBinding] steps for all pattern variable definitions.
         */
        private fun addVariableBindings() {
            for (varElement in elements.variables) {
                baseSteps.add(
                    BaseStep.VariableBinding(varElement, VariableBinding.variableLabel(varElement.variable.name))
                )
            }
        }

        /**
         * Emits [BaseStep.DeferredPropertyConstraint] steps for all properties that
         * could not be inlined during component traversal.
         */
        private fun addDeferredPropertyConstraints() {
            for (info in deferredProperties) {
                baseSteps.add(BaseStep.DeferredPropertyConstraint(info.instanceName, info.className, info.property))
            }
        }

        /**
         * Classifies each where-clause as either a [BaseStep.WhereFilter] (references at
         * most one matchable instance) or a [PostMatchFilter.CrossNodeWhereClause]
         * (references multiple matchable instances) and appends it accordingly.
         */
        private fun addWhereClauses() {
            for (clause in elements.whereClauses) {
                val referencedMatchable = nodeAnalyzer.findReferencedNodes(clause.whereClause.expression)
                    .filter { it in matchableNames }
                if (referencedMatchable.size > 1) {
                    postMatchFilters.add(PostMatchFilter.CrossNodeWhereClause(clause))
                } else {
                    baseSteps.add(BaseStep.WhereFilter(clause))
                }
            }
        }

        /**
         * Appends [PostMatchFilter.InjectiveConstraint]s for all pairs of matched instances
         * that share the same class name, ensuring they bind to distinct vertices.
         */
        private fun addInjectiveConstraints() {
            for (i in allMatchable.indices) {
                for (j in i + 1 until allMatchable.size) {
                    val a = allMatchable[i].objectInstance
                    val b = allMatchable[j].objectInstance
                    if (a.name != b.name && a.className != null && a.className == b.className) {
                        postMatchFilters.add(PostMatchFilter.InjectiveConstraint(a.name, b.name))
                    }
                }
            }
        }
    }
}
