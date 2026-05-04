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
import kotlin.math.min

/**
 * Builds a fully imperative [MatchPlan] from categorised pattern elements.
 *
 * The builder places all work into [MatchPlan.baseSteps]:
 *
 * 1. Connected-component starts, edge walks, and reachable vertices form the core traversal.
 * 2. Property constraints whose values are constants or expressions referencing only
 *    already-bound nodes are inlined right after their owning instance.
 * 3. All positive/negative application conditions ([BaseStep.ApplicationCondition]) — whether
 *    anchored to the main pattern or fully disconnected — are emitted as soon as their
 *    dependency nodes are covered. Cheaper conditions (fewer edges, lower multiplicity) are
 *    emitted before more expensive ones via a cost-based ranking.
 * 4. Uncovered instances, referenced instances, deferred conditions, variables,
 *    deferred property constraints, and where clauses are appended as imperative steps.
 * 5. Only injective and cross-node where clauses go into [MatchPlan.postMatchFilters].
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
                OrphanLinkInfo(src, tgt, link)
            } else null
        }
    }

    /**
     * Chooses the traversal direction for an orphan link (a forbid/require edge whose both
     * endpoints are main-pattern nodes).
     *
     * Prefers the direction where the starting end has the lower multiplicity upper bound,
     * as that minimises the number of edges traversed when checking existence.
     *
     * @return `true` when the link should be traversed target→source (reversed);
     *         `false` for the forward source→target direction.
     */
    private fun chooseOrphanLinkReversed(link: TypedPatternLinkElement): Boolean {
        val assoc = metamodelData.associations.firstOrNull { assoc ->
            assoc.source.name == link.link.source.propertyName &&
            assoc.target.name == link.link.target.propertyName
        } ?: return false  // no metamodel info: default forward

        val srcUpper = if (assoc.source.multiplicity.upper == -1) Int.MAX_VALUE
                       else assoc.source.multiplicity.upper
        val tgtUpper = if (assoc.target.multiplicity.upper == -1) Int.MAX_VALUE
                       else assoc.target.multiplicity.upper
        // Reversed = walk from target to source; that traverses target's side multiplicity
        return tgtUpper < srcUpper
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

    /**
     * An orphan link: a forbid/require link whose both endpoints are main-pattern nodes.
     * Stores the original [TypedPatternLinkElement] so that the traversal direction can be
     * optimised based on association multiplicities.
     */
    private data class OrphanLinkInfo(
        val sourceName: String,
        val targetName: String,
        val link: TypedPatternLinkElement
    )

    private data class MatchComponent(
        val instances: List<String>,
        val links: List<TypedPatternLinkElement>
    )

    /** Groups a pending PAC or NAC into a uniform representation for the plan builder. */
    private data class PendingCondition(
        val island: Island?,
        val orphanLink: OrphanLinkInfo?,
        val isNegative: Boolean
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

        // All PAC/NAC expressed uniformly: islands + orphan links in a single list.
        private val forbidIslands = IslandGrouper.groupIntoIslands(elements.forbidInstances, elements.forbidLinks)
        private val requireIslands = IslandGrouper.groupIntoIslands(elements.requireInstances, elements.requireLinks)
        private val forbidOrphanLinks = identifyOrphanLinks(elements.forbidInstances, elements.forbidLinks, matchableNames)
        private val requireOrphanLinks = identifyOrphanLinks(elements.requireInstances, elements.requireLinks, matchableNames)
        private val allConditions: List<PendingCondition> = buildAllConditions()

        private val baseSteps = mutableListOf<BaseStep>()
        private val postMatchFilters = mutableListOf<PostMatchFilter>()
        private val coveredInstances = mutableSetOf<String>()
        private val coveredLinks = mutableSetOf<TypedPatternLinkElement>()
        private val emittedConditionIndices = mutableSetOf<Int>()
        private val deferredProperties = mutableListOf<DeferredPropertyInfo>()

        private fun buildAllConditions(): List<PendingCondition> {
            val result = mutableListOf<PendingCondition>()
            forbidIslands.forEach  { result.add(PendingCondition(it, null, isNegative = true)) }
            requireIslands.forEach { result.add(PendingCondition(it, null, isNegative = false)) }
            forbidOrphanLinks.forEach  { result.add(PendingCondition(null, it, isNegative = true)) }
            requireOrphanLinks.forEach { result.add(PendingCondition(null, it, isNegative = false)) }
            return result
        }

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
            addDeferredConditions()
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
         * constraints (property, application condition) are applied immediately.
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
         * This includes inline property constraints for [instance] (when non-null) and
         * any application conditions whose dependency nodes are now all covered.
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
            tryInlineConditions(instanceName)
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
         * Attempts to emit any pending application conditions whose required nodes are now
         * all covered. Ready conditions are sorted cheapest-first before emitting.
         *
         * A condition is ready when:
         * - All anchor/outer nodes it references are covered.
         * - All main-pattern nodes whose class matches any condition node (needed for injective
         *   constraints) are also covered.
         *
         * @param currentNode The node most recently added to the traversal; a condition with
         *   this node as anchor is emitted without a preceding `select()`.
         */
        private fun tryInlineConditions(currentNode: String) {
            val readyConditions = mutableListOf<Pair<Int, BaseStep.ApplicationCondition>>()

            for ((index, pending) in allConditions.withIndex()) {
                if (index in emittedConditionIndices) continue
                val ac = when {
                    pending.island != null -> tryBuildIslandCondition(pending.island, pending.isNegative, currentNode)
                    pending.orphanLink != null -> tryBuildOrphanLinkCondition(pending.orphanLink, pending.isNegative, currentNode)
                    else -> null
                } ?: continue
                readyConditions.add(index to ac)
            }

            readyConditions.sortBy { computeConditionCost(it.second) }
            for ((index, ac) in readyConditions) {
                baseSteps.add(ac)
                emittedConditionIndices.add(index)
            }
        }

        /**
         * Tries to build an [BaseStep.ApplicationCondition] for [island] if all required
         * outer nodes are already covered. Returns `null` if not yet ready.
         */
        private fun tryBuildIslandCondition(
            island: Island,
            isNegative: Boolean,
            currentNode: String
        ): BaseStep.ApplicationCondition? {
            if (island.links.isEmpty()) {
                // Singleton disconnected island: only requires injective-sibling coverage.
                val injectiveRequired = computeInjectiveRequiredNodes(island)
                if (!injectiveRequired.all { it in coveredInstances }) return null
                return buildApplicationCondition(island, isNegative, anchorName = null, needsSelect = false)
            }

            val islandNames = island.instances.map { it.objectInstance.name }.toSet()
            val anchors = IslandTraversalUtils.findAnchorNames(island.links, islandNames, matchableNames)

            if (anchors.isEmpty()) {
                // Fully internal island (no links to main pattern): treat as disconnected.
                val injectiveRequired = computeInjectiveRequiredNodes(island)
                if (!injectiveRequired.all { it in coveredInstances }) return null
                return buildApplicationCondition(island, isNegative, anchorName = null, needsSelect = false)
            }

            val injectiveRequired = computeInjectiveRequiredNodes(island)
            val required = anchors + injectiveRequired
            if (!required.all { it in coveredInstances }) return null

            val bestAnchor = IslandTraversalUtils.selectBestAnchor(anchors, island.links, metamodelData)
                ?: return null
            val needsSelect = bestAnchor != currentNode
            return buildApplicationCondition(island, isNegative, bestAnchor, needsSelect)
        }

        /**
         * Tries to build an [BaseStep.ApplicationCondition] for an orphan link if both endpoints
         * are covered. Returns `null` if not yet ready.
         */
        private fun tryBuildOrphanLinkCondition(
            info: OrphanLinkInfo,
            isNegative: Boolean,
            currentNode: String
        ): BaseStep.ApplicationCondition? {
            if (info.sourceName !in coveredInstances || info.targetName !in coveredInstances) return null
            return buildOrphanLinkCondition(info, isNegative, currentNode)
        }

        /**
         * Computes the set of main-pattern instance names that must be covered before [island]
         * can be emitted, because they share a class with at least one island instance and
         * therefore require injective constraints.
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
         * Builds an [BaseStep.ApplicationCondition] for [island].
         *
         * When [anchorName] is non-null the inner traversal starts from that outer node
         * and walks island links via [BaseStep.EdgeWalk] steps.
         *
         * When [anchorName] is null (disconnected island) the inner traversal starts with
         * a [BaseStep.VertexScan] for the first island instance and then walks any internal
         * island links.
         *
         * Property constraints for island nodes are emitted as [BaseStep.InlinePropertyConstraint].
         * Links to secondary outer nodes are followed by [BaseStep.EqualityFilter] to verify
         * the traversal reached the correct already-matched vertex.
         */
        private fun buildApplicationCondition(
            island: Island,
            isNegative: Boolean,
            anchorName: String?,
            needsSelect: Boolean
        ): BaseStep.ApplicationCondition {
            val islandNames = island.instances.map { it.objectInstance.name }.toSet()
            val islandInstanceMap = island.instances.associateBy { it.objectInstance.name }

            val innerSteps = mutableListOf<BaseStep>()
            val startNode: String

            if (anchorName == null) {
                // Disconnected: begin with a VertexScan for the first island instance.
                val startInst = island.instances.firstOrNull()
                    ?: return BaseStep.ApplicationCondition(isNegative, null, false, emptyList())
                startNode = startInst.objectInstance.name
                innerSteps.add(BaseStep.VertexScan(startNode, startInst.objectInstance.className, null))
                innerSteps.addAll(buildConditionPropertySteps(startInst))
            } else {
                startNode = anchorName
            }

            // BFS over island links from startNode.
            val orderedLinks = IslandTraversalUtils.orderLinksByBFS(island.links, startNode)
            var currentInner = startNode

            for ((link, isReversed) in orderedLinks) {
                val fromName = if (isReversed) link.link.target.objectName else link.link.source.objectName
                val toName   = if (isReversed) link.link.source.objectName else link.link.target.objectName
                val toIsIslandNode = toName in islandNames
                val toInst = if (toIsIslandNode) islandInstanceMap[toName] else null

                innerSteps.add(
                    BaseStep.EdgeWalk(
                        link = link,
                        isReversed = isReversed,
                        fromInstanceName = fromName,
                        toInstanceName = toName,
                        toClassName = toInst?.objectInstance?.className,
                        toVertexId = null,
                        needsSelect = fromName != currentInner
                    )
                )

                // When the destination is an outer node (not island-only, not the primary anchor),
                // add an equality filter to verify the traversal reached the correct vertex.
                if (!toIsIslandNode && toName != anchorName) {
                    innerSteps.add(BaseStep.EqualityFilter(toName))
                }

                // Inline property constraints for island-only destination nodes.
                if (toIsIslandNode && toInst != null) {
                    innerSteps.addAll(buildConditionPropertySteps(toInst))
                }

                currentInner = toName
            }

            val injectiveConstraints = buildConditionInjectiveConstraints(island, orderedLinks, startNode)

            return BaseStep.ApplicationCondition(
                isNegative = isNegative,
                anchorName = anchorName,
                needsSelect = needsSelect,
                innerSteps = innerSteps,
                injectiveConstraints = injectiveConstraints
            )
        }

        /**
         * Builds an [BaseStep.ApplicationCondition] for an orphan link (a forbid/require edge
         * between two main-pattern nodes).
         *
         * The traversal direction is chosen so that the end with the lower multiplicity upper
         * bound is the anchor (fewer expected edge hops). After the edge walk, an
         * [BaseStep.EqualityFilter] verifies the traversal reached the other outer node.
         */
        private fun buildOrphanLinkCondition(
            info: OrphanLinkInfo,
            isNegative: Boolean,
            currentNode: String
        ): BaseStep.ApplicationCondition {
            val isReversed = chooseOrphanLinkReversed(info.link)
            val anchorName = if (isReversed) info.targetName else info.sourceName
            val otherName  = if (isReversed) info.sourceName else info.targetName
            val needsSelect = anchorName != currentNode

            val innerSteps = listOf(
                BaseStep.EdgeWalk(
                    link = info.link,
                    isReversed = isReversed,
                    fromInstanceName = anchorName,
                    toInstanceName = otherName,
                    toClassName = null,
                    toVertexId = null,
                    needsSelect = false   // inner traversal starts at the anchor
                ),
                BaseStep.EqualityFilter(otherName)
            )

            return BaseStep.ApplicationCondition(
                isNegative = isNegative,
                anchorName = anchorName,
                needsSelect = needsSelect,
                innerSteps = innerSteps,
                injectiveConstraints = emptyMap()
            )
        }

        /**
         * Builds the [BaseStep.InlinePropertyConstraint] inner steps for an island instance.
         *
         * Only `==` properties are included; the `isConstant` flag is determined the same
         * way as for main-pattern property constraints.
         */
        private fun buildConditionPropertySteps(
            instance: TypedPatternObjectInstanceElement
        ): List<BaseStep.InlinePropertyConstraint> {
            return instance.objectInstance.properties.mapNotNull { property ->
                if (property.operator != "==") return@mapNotNull null
                val referencedNodes = nodeAnalyzer.findReferencedNodes(property.value)
                val isConstant = referencedNodes.isEmpty() && !isCollectionExpression(property.value)
                BaseStep.InlinePropertyConstraint(
                    instance.objectInstance.name,
                    instance.objectInstance.className,
                    property,
                    isConstant
                )
            }
        }

        /**
         * Builds the injective-constraint map for an application condition's chain traversal.
         *
         * For each non-start island node (in BFS order), computes the list of step labels
         * that the node must be distinct from:
         * - [VariableBinding.stepLabel] of every main-pattern node that shares the same class.
         * - [VariableBinding.stepLabel] of every earlier island node in BFS order that shares
         *   the same class (using the standard step-label convention instead of synthetic names).
         *
         * The returned map uses the island node's step label as key.
         */
        private fun buildConditionInjectiveConstraints(
            island: Island,
            orderedLinks: List<Pair<TypedPatternLinkElement, Boolean>>,
            startName: String
        ): Map<String, List<String>> {
            val constraints = mutableMapOf<String, MutableList<String>>()
            val islandInstanceMap = island.instances.associateBy { it.objectInstance.name }

            val bfsOrder = mutableListOf<String>()
            val visited = mutableSetOf(startName)
            for ((link, isReversed) in orderedLinks) {
                val toNode = if (isReversed) link.link.source.objectName else link.link.target.objectName
                if (toNode in islandInstanceMap && visited.add(toNode)) {
                    bfsOrder.add(toNode)
                }
            }

            for ((i, islandNode) in bfsOrder.withIndex()) {
                val islandClass = islandInstanceMap[islandNode]?.objectInstance?.className ?: continue
                val nodeLabel = VariableBinding.stepLabel(islandNode)

                // Against already-matched main-pattern nodes of the same class.
                for (mainInst in allMatchable) {
                    val mainClass = mainInst.objectInstance.className ?: continue
                    if (islandClass == mainClass) {
                        constraints.getOrPut(nodeLabel) { mutableListOf() }
                            .add(VariableBinding.stepLabel(mainInst.objectInstance.name))
                    }
                }

                // Against earlier island nodes of the same class in BFS order.
                for (j in 0 until i) {
                    val prevNode = bfsOrder[j]
                    val prevClass = islandInstanceMap[prevNode]?.objectInstance?.className ?: continue
                    if (islandClass == prevClass) {
                        constraints.getOrPut(nodeLabel) { mutableListOf() }
                            .add(VariableBinding.stepLabel(prevNode))
                    }
                }
            }

            return constraints
        }

        /**
         * Estimates the evaluation cost of [condition] for ordering purposes.
         *
         * Lower scores are cheaper:
         * - Orphan links (single-edge anchored, <= 2 steps): 1-2
         * - Multi-step anchored islands: 10 * edge count, +1 if needsSelect
         * - Disconnected/unanchored: 1000 + steps
         */
        private fun computeConditionCost(condition: BaseStep.ApplicationCondition): Int {
            if (condition.anchorName == null) {
                return 1000 + condition.innerSteps.count { it is BaseStep.EdgeWalk } * 10
            }
            val edgeCount = condition.innerSteps.count { it is BaseStep.EdgeWalk }
            val selectPenalty = if (condition.needsSelect) 1 else 0
            return edgeCount * 10 + selectPenalty
        }

        /**
         * Emits [BaseStep.VertexScan] steps for matchable instances not covered during
         * the connected-component traversal phase, and attempts to inline any application
         * conditions that become ready after each instance is covered.
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
         * - Both endpoints covered → [BaseStep.EqualityFilter]-style check via require edge walk.
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
                        // Both endpoints already matched; verify the edge exists.
                        baseSteps.add(
                            BaseStep.ApplicationCondition(
                                isNegative = false,
                                anchorName = srcName,
                                needsSelect = true,
                                innerSteps = listOf(
                                    BaseStep.EdgeWalk(link, false, srcName, tgtName, null, null, needsSelect = false),
                                    BaseStep.EqualityFilter(tgtName)
                                )
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
         * Emits all remaining [BaseStep.ApplicationCondition] steps that were not inlined
         * during traversal. Conditions are sorted by estimated cost (cheapest first).
         */
        private fun addDeferredConditions() {
            val remaining = mutableListOf<Pair<Int, BaseStep.ApplicationCondition>>()

            for ((index, pending) in allConditions.withIndex()) {
                if (index in emittedConditionIndices) continue

                val ac: BaseStep.ApplicationCondition = if (pending.island != null) {
                    val island = pending.island
                    if (island.links.isEmpty()) {
                        buildApplicationCondition(island, pending.isNegative, null, false)
                    } else {
                        val islandNames = island.instances.map { it.objectInstance.name }.toSet()
                        val anchors = IslandTraversalUtils.findAnchorNames(island.links, islandNames, matchableNames)
                        val anchor = IslandTraversalUtils.selectBestAnchor(anchors, island.links, metamodelData)
                        if (anchor == null) {
                            buildApplicationCondition(island, pending.isNegative, null, false)
                        } else {
                            buildApplicationCondition(island, pending.isNegative, anchor, needsSelect = true)
                        }
                    }
                } else {
                    buildOrphanLinkCondition(pending.orphanLink!!, pending.isNegative, currentNode = "")
                }

                remaining.add(index to ac)
            }

            remaining.sortBy { computeConditionCost(it.second) }
            for ((_, ac) in remaining) {
                baseSteps.add(ac)
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
