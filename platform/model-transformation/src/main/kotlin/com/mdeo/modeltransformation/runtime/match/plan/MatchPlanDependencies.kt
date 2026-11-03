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
    // Exclude a self-reference: a reassignment such as `x = x + 1` reads its own incoming
    // (old) value, which is always already available, so it is never a dependency on itself.
    varEl.variable.name to refs.filter { it in variableNames && it != varEl.variable.name }.toSet()
}

/**
 * Computes a greedy traversal-priority score for every matchable and PAC instance.
 *
 * The priority of an instance A is the number of its *transitive descendants* in the
 * pseudo-composition DAG induced by the metamodel over the matchable link set.  Nodes of
 * application conditions never take part: they are matched inside the condition's own
 * traversal and are never a starting point of the main traversal.  A is a parent of B
 * when there is a link (A–B or B–A) whose metamodel edge
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
 * @param matchableLinks All matchable link elements.
 * @param pseudoCompositionDag Set of (parentClass, childClass) pairs representing the
 *        approximate composition hierarchy derived from the metamodel.
 * @return A map from instance name to priority score (higher = more selective /
 *         preferred as a traversal starting point).
 */
internal fun computeInstancePriorities(
    allMatchable: List<TypedPatternObjectInstanceElement>,
    matchableLinks: List<TypedPatternLinkElement>,
    pseudoCompositionDag: Set<Pair<String, String>>
): Map<String, Int> {
    val priorityNames = allMatchable.map { it.objectInstance.name }.toSet()

    val classOf = HashMap<String, String>()
    for (inst in allMatchable) inst.objectInstance.className?.let { classOf[inst.objectInstance.name] = it }

    val priorityChildren = priorityNames.associateWith { mutableSetOf<String>() }.toMutableMap()

    for (link in matchableLinks) {
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
 * A pair of main-pattern instances that must be bound to distinct vertices.
 *
 * @property exactClassOfA When set, [nameA] is a pattern object of this class and [nameB] a
 *           bound object: the two must differ only when the bound element is of exactly this
 *           class, as two pattern objects must differ only when they have the same class.
 */
internal data class InjectivePair(val nameA: String, val nameB: String, val exactClassOfA: String? = null)

/**
 * Computes all pairs of main-pattern instances that must be bound to *distinct* graph
 * vertices (injective matching).
 *
 * Two objects of the pattern form an injective pair when they share the same metamodel class.
 * If both were allowed to match the same vertex, the match would be degenerate (one object
 * playing two roles simultaneously), which is almost never the intended semantics. The same
 * holds between a pattern object and a *bound* object the pattern references (an element
 * input, an object of an earlier or enclosing statement, a call output or a loop variable —
 * `seed { }`, without a class): the pattern object must not match the bound element when that
 * element is of exactly the object's class. The bound element's class is only known at run
 * time, so that pair carries the class ([InjectivePair.exactClassOfA]) and the traversal checks
 * it. Two bound objects are not compared: they are whatever the caller bound.
 *
 * @param allMatchable All matchable instance elements in the main pattern.
 * @param isBound Whether an instance without a class name is bound to a vertex.
 * @return The pairs; each unordered pair appears exactly once.
 */
internal fun computeInjectivePairs(
    allMatchable: List<TypedPatternObjectInstanceElement>,
    isBound: (String) -> Boolean = { false }
): List<InjectivePair> {
    val result = mutableListOf<InjectivePair>()
    for (i in allMatchable.indices) {
        for (j in i + 1 until allMatchable.size) {
            val a = allMatchable[i].objectInstance
            val b = allMatchable[j].objectInstance
            if (a.name == b.name) continue
            when {
                a.className != null && b.className != null ->
                    if (a.className == b.className) result.add(InjectivePair(a.name, b.name))
                a.className != null && isBound(b.name) -> result.add(InjectivePair(a.name, b.name, a.className))
                b.className != null && isBound(a.name) -> result.add(InjectivePair(b.name, a.name, b.className))
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
