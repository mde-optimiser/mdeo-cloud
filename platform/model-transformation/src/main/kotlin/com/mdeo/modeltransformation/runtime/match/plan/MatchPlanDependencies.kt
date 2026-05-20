package com.mdeo.modeltransformation.runtime.match.plan

import com.mdeo.modeltransformation.ast.patterns.TypedPatternLinkElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstanceElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternVariableElement
import com.mdeo.modeltransformation.runtime.match.ExpressionNodeAnalyzer

/**
 * Computes the transitive instance-level dependencies for each pattern variable.
 *
 * For a variable V whose expression directly references nodes N₁, N₂, …, any node that
 * is itself a variable is replaced by *its own* transitive instance dependencies, so the
 * resulting map contains only *instance* names — never other variable names.
 * The recursion is guarded against cycles by a per-call `visited` set.
 *
 * Example: if `V = f(W)` and `W = g(a, b)`, then `variableNodeDeps[V] = {a, b}`.
 *
 * @param variables All pattern variable elements in the match block.
 * @param variableNames The set of all variable names (used to distinguish variables from
 *        instances).
 * @param nodeAnalyzer Analyser that extracts the set of node names referenced by an
 *        expression AST node.
 * @return A map from variable name to the set of instance names that must be covered
 *         before that variable can be bound.
 */
internal fun computeVariableNodeDeps(
    variables: List<TypedPatternVariableElement>,
    variableNames: Set<String>,
    nodeAnalyzer: ExpressionNodeAnalyzer
): Map<String, Set<String>> {
    val directRefs = variables.associate { varEl ->
        varEl.variable.name to nodeAnalyzer.findReferencedNodes(varEl.variable.value)
    }
    val resolved = mutableMapOf<String, Set<String>>()

    fun resolve(varName: String, visited: MutableSet<String> = mutableSetOf()): Set<String> {
        resolved[varName]?.let { return it }
        if (!visited.add(varName)) return emptySet()
        val result = mutableSetOf<String>()
        for (dep in directRefs[varName] ?: emptySet()) {
            if (dep in variableNames) result.addAll(resolve(dep, visited))
            else result.add(dep)
        }
        resolved[varName] = result
        return result
    }

    for (varEl in variables) resolve(varEl.variable.name)
    return resolved
}

/**
 * Computes the direct variable-to-variable dependencies for each pattern variable.
 *
 * Unlike [computeVariableNodeDeps], this function does *not* resolve transitively: it
 * returns only the variables that are directly named in the variable's own expression.
 * The caller uses these dependencies to ensure that a variable is emitted only after all
 * variables it depends on have already been emitted.
 *
 * @param variables All pattern variable elements in the match block.
 * @param variableNames The set of all variable names.
 * @param nodeAnalyzer Analyser that extracts the set of node names referenced by an
 *        expression AST node.
 * @return A map from variable name to the set of variable names directly referenced in
 *         its defining expression.
 */
internal fun computeVariableVarDeps(
    variables: List<TypedPatternVariableElement>,
    variableNames: Set<String>,
    nodeAnalyzer: ExpressionNodeAnalyzer
): Map<String, Set<String>> = variables.associate { varEl ->
    val refs = nodeAnalyzer.findReferencedNodes(varEl.variable.value)
    varEl.variable.name to refs.filter { it in variableNames }.toSet()
}

/**
 * Computes a greedy traversal-priority score for every matchable and PAC instance.
 *
 * The priority of an instance A is the number of its *transitive descendants* in the
 * pseudo-composition DAG induced by the metamodel over the combined matchable + require
 * link set.  A is a parent of B when there is a link (A–B or B–A) whose metamodel edge
 * type is composition-like (the DAG is an over-approximation: ambiguous associations
 * are included in both directions).
 *
 * A higher score means the instance sits higher in the composition hierarchy.  The
 * planner prefers to begin traversals from high-priority instances because they are more
 * selective (the metamodel type narrows the set of candidate vertices) and because
 * walking *down* the composition tree via edge steps is cheaper than scanning for
 * disconnected instances.
 *
 * @param allMatchable All matchable (main-pattern) instance elements.
 * @param requireInstances Instance elements that appear only inside PAC (require) islands.
 * @param matchableLinks All matchable link elements.
 * @param requireLinks Link elements that appear only inside PAC islands.
 * @param pseudoCompositionDag Set of (parentClass, childClass) pairs representing the
 *        approximate composition hierarchy derived from the metamodel.
 * @return A map from instance name to priority score (higher = more selective /
 *         preferred as a traversal starting point).
 */
internal fun computeInstancePriorities(
    allMatchable: List<TypedPatternObjectInstanceElement>,
    requireInstances: List<TypedPatternObjectInstanceElement>,
    matchableLinks: List<TypedPatternLinkElement>,
    requireLinks: List<TypedPatternLinkElement>,
    pseudoCompositionDag: Set<Pair<String, String>>
): Map<String, Int> {
    val regularNames = allMatchable.map { it.objectInstance.name }.toSet()
    val requireNames = requireInstances.map { it.objectInstance.name }.toSet()
    val priorityNames = regularNames + requireNames

    val classOf = HashMap<String, String>()
    for (inst in allMatchable) inst.objectInstance.className?.let { classOf[inst.objectInstance.name] = it }
    for (inst in requireInstances) inst.objectInstance.className?.let { classOf[inst.objectInstance.name] = it }

    val priorityChildren = priorityNames.associateWith { mutableSetOf<String>() }.toMutableMap()

    for (link in matchableLinks + requireLinks) {
        val srcName = link.link.source.objectName
        val tgtName = link.link.target.objectName
        if (srcName !in priorityNames || tgtName !in priorityNames) continue
        val srcClass = classOf[srcName] ?: continue
        val tgtClass = classOf[tgtName] ?: continue
        when {
            (srcClass to tgtClass) in pseudoCompositionDag ->
                priorityChildren.getOrPut(srcName) { mutableSetOf() }.add(tgtName)
            (tgtClass to srcClass) in pseudoCompositionDag ->
                priorityChildren.getOrPut(tgtName) { mutableSetOf() }.add(srcName)
        }
    }

    return priorityNames.associateWith { countTransitiveDescendants(it, priorityChildren) }
}

/**
 * Computes all pairs of main-pattern instances that must be bound to *distinct* graph
 * vertices (injective matching).
 *
 * Two instances form an injective pair when they share the same metamodel class.  If
 * both were allowed to match the same vertex, the match would be degenerate (one object
 * playing two roles simultaneously), which is almost never the intended semantics.
 *
 * Only instances with a known class name are considered; untyped instances (className
 * == null) are pre-bound by vertex ID and therefore cannot collide.
 *
 * @param allMatchable All matchable instance elements in the main pattern.
 * @return A list of (nameA, nameB) pairs, each representing the required inequality
 *         nameA ≠ nameB.  Each unordered pair appears exactly once.
 */
internal fun computeInjectivePairs(
    allMatchable: List<TypedPatternObjectInstanceElement>
): List<Pair<String, String>> {
    val result = mutableListOf<Pair<String, String>>()
    for (i in allMatchable.indices) {
        for (j in i + 1 until allMatchable.size) {
            val a = allMatchable[i].objectInstance
            val b = allMatchable[j].objectInstance
            if (a.name != b.name && a.className != null && a.className == b.className) {
                result.add(a.name to b.name)
            }
        }
    }
    return result
}

/**
 * Counts the number of nodes reachable from [start] in [children] (exclusive of [start]
 * itself) using a breadth-first traversal.
 *
 * Cycles are handled correctly: each node is inserted into `visited` before being
 * enqueued, so no node is processed twice.
 *
 * @param start The root node from which to start the BFS.
 * @param children Adjacency map from a parent node name to its direct child node names.
 * @return The number of distinct nodes reachable from [start], not counting [start].
 */
private fun countTransitiveDescendants(start: String, children: Map<String, Set<String>>): Int {
    val visited = mutableSetOf<String>()
    val queue = ArrayDeque<String>()
    queue.add(start)
    while (queue.isNotEmpty()) {
        val cur = queue.removeFirst()
        if (visited.add(cur)) {
            for (child in children[cur] ?: emptySet()) queue.add(child)
        }
    }
    return visited.size - 1
}
