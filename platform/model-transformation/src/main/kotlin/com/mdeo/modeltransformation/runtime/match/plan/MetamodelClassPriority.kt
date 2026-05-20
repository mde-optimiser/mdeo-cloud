package com.mdeo.modeltransformation.runtime.match.plan

import com.mdeo.metamodel.data.MetamodelData

/**
 * Builds a pseudo-composition DAG from the metamodel, used to derive per-instance
 * priority scores in [MatchPlanBuilder].
 *
 * Higher pseudo-composition depth = higher priority = should be matched first.
 *
 * See [computePseudoCompositionDag] for the full algorithm.
 */
internal object MetamodelClassPriority {

    private const val CONTAINMENT_OPERATOR = "<>->"

    /**
     * Builds a cycle-free directed pseudo-composition DAG from the metamodel.
     *
     * Returns a set of `(parentClass, childClass)` pairs representing all structural
     * containment relationships, including both **real compositions** and
     * **pseudo-compositions** derived from non-composition associations.
     *
     * ## Algorithm
     *
     * 1. **Seed the DAG** with real composition edges (`<>->`: source → target) and
     *    inheritance edges (parent → child).
     *
     * 2. **Break cycles** via DFS: back-edges (to in-stack nodes) are discarded;
     *    cross-edges (to already-finished nodes) are **kept**, so the result is a
     *    cycle-free directed graph rather than a spanning tree. This allows a node to
     *    have multiple parents.
     *
     * 3. **Extend with pseudo-compositions** from non-composition associations.
     *    An association end qualifies as a pseudo-composition *child* when:
     *    - (a) Its upper multiplicity is finite (not −1 / unbounded).
     *    - (b) That upper bound is strictly less than the other end's upper bound,
     *          or the other end is unbounded.
     *    - (c) Its class is **not** already the target of a real composition
     *          (real compositions take precedence).
     *    If exactly one end qualifies, the other end is the parent and an edge
     *    `parent → child` is added — provided it would not introduce a cycle.
     *    If both or neither ends qualify, the association is skipped.
     *
     * Unlike the old spanning-tree approach, no Union-Find is used: pseudo-composition edges
     * may connect nodes already in the same component, yielding additional parent→child
     * edges that would be forbidden there.
     *
     * @return Immutable set of `(parentClass, childClass)` pairs.
     */
    fun computePseudoCompositionDag(metamodelData: MetamodelData): Set<Pair<String, String>> {
        if (metamodelData.classes.isEmpty()) return emptySet()

        val allClasses = metamodelData.classes.map { it.name }.toSet()

        val realCompositionTargets = metamodelData.associations
            .filter { it.operator == CONTAINMENT_OPERATOR }
            .map { it.target.className }
            .filter { it in allClasses }
            .toSet()

        val seedEdges = mutableSetOf<Pair<String, String>>()
        for (assoc in metamodelData.associations) {
            if (assoc.operator == CONTAINMENT_OPERATOR) {
                val src = assoc.source.className; val tgt = assoc.target.className
                if (src in allClasses && tgt in allClasses && src != tgt)
                    seedEdges.add(src to tgt)
            }
        }
        for (cls in metamodelData.classes) {
            for (parent in cls.extends) {
                if (parent in allClasses && cls.name != parent)
                    seedEdges.add(parent to cls.name)
            }
        }

        val dagEdges = makeCycleFree(seedEdges, allClasses)

        val pseudoCandidates = metamodelData.associations
            .filter { it.operator != CONTAINMENT_OPERATOR }
            .sortedWith(compareBy({ it.source.className }, { it.target.className }))

        for (assoc in pseudoCandidates) {
            val src = assoc.source.className; val tgt = assoc.target.className
            if (src !in allClasses || tgt !in allClasses || src == tgt) continue

            val srcUpper = assoc.source.multiplicity.upper
            val tgtUpper = assoc.target.multiplicity.upper

            val tgtIsChild = tgtUpper != -1 &&
                (srcUpper == -1 || tgtUpper < srcUpper) &&
                tgt !in realCompositionTargets

            val srcIsChild = srcUpper != -1 &&
                (tgtUpper == -1 || srcUpper < tgtUpper) &&
                src !in realCompositionTargets

            val edge: Pair<String, String>? = when {
                tgtIsChild && !srcIsChild -> src to tgt
                srcIsChild && !tgtIsChild -> tgt to src
                else -> null
            }

            if (edge != null && !wouldCreateCycle(dagEdges, edge.first, edge.second)) {
                dagEdges.add(edge)
            }
        }

        return dagEdges
    }

    /**
     * Returns a mutable set of edges from [edges] that form a cycle-free directed graph.
     *
     * DFS is performed over the adjacency list derived from [edges].
     * - **Tree edges** (to unvisited nodes): kept.
     * - **Cross/forward edges** (to already-finished nodes): kept — a node may have multiple parents.
     * - **Back edges** (to in-stack nodes): discarded (would create a cycle).
     */
    private fun makeCycleFree(
        edges: Set<Pair<String, String>>,
        allClasses: Set<String>
    ): MutableSet<Pair<String, String>> {
        val adj = allClasses.associateWith { mutableSetOf<String>() }.toMutableMap()
        for ((parent, child) in edges) adj.getOrPut(parent) { mutableSetOf() }.add(child)

        val result = mutableSetOf<Pair<String, String>>()
        val state = HashMap<String, Int>() // 0=unvisited, 1=in-stack, 2=done

        fun dfs(node: String) {
            state[node] = 1
            for (child in adj[node] ?: emptySet()) {
                when (state.getOrDefault(child, 0)) {
                    0 -> { result.add(node to child); dfs(child) }  // tree edge
                    2 -> { result.add(node to child) }               // cross/forward edge – safe
                    // 1 = back-edge: would create a cycle – skip
                }
            }
            state[node] = 2
        }

        for (cls in allClasses.sorted()) {
            if (state.getOrDefault(cls, 0) == 0) dfs(cls)
        }
        return result
    }

    /**
     * Returns `true` when adding an edge `from → to` to [dag] would introduce a cycle.
     *
     * A cycle would be created iff [from] is already reachable from [to] in [dag]
     * (i.e., there is already a directed path `to ~~> from`).
     */
    private fun wouldCreateCycle(dag: Set<Pair<String, String>>, from: String, to: String): Boolean {
        val adj = HashMap<String, MutableSet<String>>()
        for ((p, c) in dag) adj.getOrPut(p) { mutableSetOf() }.add(c)

        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(to)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (cur == from) return true
            if (visited.add(cur)) {
                for (next in adj[cur] ?: emptySet()) queue.add(next)
            }
        }
        return false
    }

}
