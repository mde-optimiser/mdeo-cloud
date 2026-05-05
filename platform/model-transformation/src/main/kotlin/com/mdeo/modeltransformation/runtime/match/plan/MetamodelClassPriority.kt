package com.mdeo.modeltransformation.runtime.match.plan

import com.mdeo.metamodel.data.MetamodelData

/**
 * Computes class priority scores from the metamodel structure using a spanning-tree approach.
 *
 * Higher score = higher priority = should be matched first in the traversal plan.
 *
 * ## Algorithm
 *
 * 1. **Build directed graph** from composition (`<>->`) and inheritance edges.
 *    - Composition: source → target (the container is "above" the contained class).
 *    - Inheritance: parent → child (the parent class is "above" the subclass).
 *
 * 2. **Break cycles** using DFS: only tree-edges (to unvisited nodes) are kept;
 *    back-edges that would form cycles are discarded.
 *
 * 3. **Compute depth** of each node via BFS from roots (nodes with no incoming
 *    tree-edges). Root depth = 0; children get depth = parent depth + 1.
 *
 * 4. **Join separate trees** using regular uni- and bidirectional associations.
 *    For each association that bridges two separate connected components the direction
 *    is determined by multiplicity: the side with higher upper bound is the "parent"
 *    (it has many references = it is the container in this relationship). The child
 *    tree's root is attached under the parent, and depths in the child tree are
 *    updated accordingly.
 *
 * 5. **Compute priority** = `(maxDepth − depth + 1)`, so root nodes get the highest
 *    priority value and leaf nodes get 1. Classes not present in the metamodel default
 *    to priority 1.
 */
internal object MetamodelClassPriority {

    private const val CONTAINMENT_OPERATOR = "<>->"

    /**
     * Returns a map from class name to integer priority score.
     *
     * Classes with no definition in [metamodelData] are not present in the returned map;
     * callers should default to `1` for unknown classes.
     */
    fun computeClassPriorities(metamodelData: MetamodelData): Map<String, Int> {
        if (metamodelData.classes.isEmpty()) return emptyMap()

        val allClasses: Set<String> = metamodelData.classes.map { it.name }.toSet()

        // ── Step 1: Build directed edges (composition + inheritance) ─────────────

        // directed[A] = set of classes that A is "above" (A is parent/container)
        val directed = allClasses.associateWith { mutableSetOf<String>() }.toMutableMap()

        for (assoc in metamodelData.associations) {
            if (assoc.operator == CONTAINMENT_OPERATOR) {
                val src = assoc.source.className
                val tgt = assoc.target.className
                if (src in allClasses && tgt in allClasses && src != tgt) {
                    directed.getOrPut(src) { mutableSetOf() }.add(tgt)
                }
            }
        }

        for (cls in metamodelData.classes) {
            for (parent in cls.extends) {
                if (parent in allClasses && cls.name != parent) {
                    directed.getOrPut(parent) { mutableSetOf() }.add(cls.name)
                }
            }
        }

        // ── Step 2: DFS spanning forest (break cycles by skipping back-edges) ────

        // treeChildren[A] = set of children of A in the spanning forest
        val treeChildren = allClasses.associateWith { mutableSetOf<String>() }.toMutableMap()

        val state = HashMap<String, Int>() // 0=unvisited, 1=in-stack, 2=done

        fun dfs(node: String) {
            state[node] = 1
            for (child in directed[node] ?: emptySet()) {
                if (state.getOrDefault(child, 0) == 0) {
                    treeChildren.getOrPut(node) { mutableSetOf() }.add(child)
                    dfs(child)
                }
                // back-edges (state=1) and cross-edges (state=2) are silently skipped
            }
            state[node] = 2
        }

        // Visit in sorted order for determinism
        for (cls in allClasses.sorted()) {
            if (state.getOrDefault(cls, 0) == 0) dfs(cls)
        }

        // ── Step 3: BFS to compute depths from roots ──────────────────────────────

        val depth = HashMap<String, Int>()

        // Roots = nodes with no incoming tree-edges
        val hasTreeParent = allClasses.associateWith { false }.toMutableMap()
        for ((_, children) in treeChildren) {
            for (child in children) hasTreeParent[child] = true
        }

        val queue = ArrayDeque<String>()
        for (cls in allClasses) {
            if (hasTreeParent[cls] == false) {
                depth[cls] = 0
                queue.add(cls)
            }
        }

        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            val d = depth[cur]!!
            for (child in treeChildren[cur] ?: emptySet()) {
                if (child !in depth) {
                    depth[child] = d + 1
                    queue.add(child)
                }
            }
        }

        // Safety: any class still not assigned (cycle participant detached from BFS)
        val baseMax = depth.values.maxOrNull() ?: 0
        for (cls in allClasses) {
            if (cls !in depth) depth[cls] = baseMax + 1
        }

        // ── Step 4: Join separate trees with regular associations ─────────────────

        // Union-Find to track which tree each class belongs to
        val uf = allClasses.associateWith { it }.toMutableMap<String, String>()

        fun find(cls: String): String {
            var cur = cls
            while (uf[cur] != cur) cur = uf.getValue(cur)
            return cur
        }

        fun union(a: String, b: String) {
            val ra = find(a); val rb = find(b)
            if (ra != rb) uf[ra] = rb
        }

        // Initialise UF from treeChildren edges
        for ((parent, children) in treeChildren) {
            for (child in children) union(parent, child)
        }

        // Try each non-containment association; process in sorted order for determinism
        val regularAssocs = metamodelData.associations
            .filter { it.operator != CONTAINMENT_OPERATOR }
            .sortedWith(compareBy({ it.source.className }, { it.target.className }))

        for (assoc in regularAssocs) {
            val src = assoc.source.className
            val tgt = assoc.target.className
            if (src !in allClasses || tgt !in allClasses || src == tgt) continue
            if (find(src) == find(tgt)) continue // already in the same tree

            // Determine direction based on multiplicity:
            // The side with higher upper bound is the "parent"
            // (it can reference many objects = it is typically the owner in this relationship)
            val srcUpper = assoc.source.multiplicity.upper
            val tgtUpper = assoc.target.multiplicity.upper

            val parentClass: String
            val childClass: String
            when {
                srcUpper == -1 && tgtUpper != -1 -> { parentClass = src; childClass = tgt }
                tgtUpper == -1 && srcUpper != -1 -> { parentClass = tgt; childClass = src }
                srcUpper != -1 && tgtUpper != -1 && srcUpper > tgtUpper -> { parentClass = src; childClass = tgt }
                srcUpper != -1 && tgtUpper != -1 && tgtUpper > srcUpper -> { parentClass = tgt; childClass = src }
                else -> {
                    // Equal or both unbounded: arbitrary tie-break by class name
                    if (src <= tgt) { parentClass = src; childClass = tgt }
                    else { parentClass = tgt; childClass = src }
                }
            }

            // Find the root of the child's tree and attach it one level below parentClass
            val childRoot = findTreeRoot(childClass, treeChildren, depth)
            val parentDepth = depth[parentClass]!!
            val newChildRootDepth = parentDepth + 1
            val depthDelta = newChildRootDepth - (depth[childRoot] ?: 0)

            if (depthDelta != 0) {
                // BFS-update depths for all nodes in the child subtree rooted at childRoot
                val updateQueue = ArrayDeque<String>()
                updateQueue.add(childRoot)
                val visited = mutableSetOf<String>()
                while (updateQueue.isNotEmpty()) {
                    val cur = updateQueue.removeFirst()
                    if (!visited.add(cur)) continue
                    depth[cur] = (depth[cur] ?: 0) + depthDelta
                    for (c in treeChildren[cur] ?: emptySet()) updateQueue.add(c)
                }
            }

            // Add a virtual tree edge parentClass → childRoot (only for UF bookkeeping)
            treeChildren.getOrPut(parentClass) { mutableSetOf() }.add(childRoot)
            union(parentClass, childClass)
        }

        // ── Step 5: Priority = maxDepth − depth + 1 ──────────────────────────────

        val maxDepth = depth.values.maxOrNull() ?: 0
        return depth.mapValues { (_, d) -> maxDepth - d + 1 }
    }

    /**
     * Finds the root of the spanning sub-tree that contains [cls] by walking
     * upward through [treeChildren] until a node with no parent is found.
     *
     * Because [treeChildren] stores parent→children direction only, we need
     * to reverse-search. We use the depth map as a fast heuristic: the root
     * is the node with minimum depth that is an ancestor of [cls].
     *
     * In practice, every tree component has exactly one root (a node at
     * depth 0 or the shallowest node in the component). We perform a BFS
     * across all nodes, starting from [cls], walking up via depth to find
     * the minimum-depth ancestor.
     *
     * Simpler approach: collect all ancestors by iterating parents via a
     * reverse-child-map built from [treeChildren].
     */
    private fun findTreeRoot(
        cls: String,
        treeChildren: Map<String, Set<String>>,
        depth: Map<String, Int>
    ): String {
        // Build a reverse map (child → parent) for upward traversal
        val parentOf = HashMap<String, String>()
        for ((parent, children) in treeChildren) {
            for (child in children) parentOf[child] = parent
        }

        // Walk up to the root
        var cur = cls
        while (cur in parentOf) cur = parentOf.getValue(cur)
        return cur
    }
}
