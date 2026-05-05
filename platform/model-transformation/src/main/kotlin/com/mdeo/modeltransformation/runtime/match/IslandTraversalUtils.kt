package com.mdeo.modeltransformation.runtime.match

import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLinkElement

/**
 * Pure graph-algorithm utilities for walking island/constraint subgraphs.
 *
 * These functions depend only on the pattern AST, not on any engine or execution
 * context, making them easy to test and reuse across the match pipeline.
 */
internal object IslandTraversalUtils {

    /**
     * Finds the anchor node names for an island — main-pattern matched nodes that are
     * connected to island instances via island links but are not themselves island instances.
     */
    fun findAnchorNames(
        links: List<TypedPatternLinkElement>,
        islandInstanceNames: Set<String>,
        matchableNames: Set<String>
    ): Set<String> {
        val anchors = mutableSetOf<String>()
        for (link in links) {
            val source = link.link.source.objectName
            val target = link.link.target.objectName
            if (source !in islandInstanceNames && source in matchableNames) { anchors.add(source) }
            if (target !in islandInstanceNames && target in matchableNames) { anchors.add(target) }
        }
        return anchors
    }

    /**
     * Selects the best anchor from [anchors] to start the island traversal chain,
     * preferring anchors whose first edge into the island has a low multiplicity upper
     * bound (i.e. the fewest nodes are expected to be reached in the first step).
     *
     * For each candidate anchor the score is the minimum upper bound of the
     * multiplicity at the anchor's end for any direct link from that anchor to a
     * non-anchor island node.  An unbounded multiplicity (`upper == -1`) is treated as
     * [Int.MAX_VALUE].  When no matching association can be found in [metamodelData],
     * the anchor scores as [Int.MAX_VALUE] so that well-typed anchors are preferred.
     *
     * @param anchors Candidate anchor names (all matched, non-island nodes connected to the island).
     * @param links All links belonging to the island (including anchor-to-island links).
     * @param metamodelData Metamodel data for looking up association multiplicities.
     * @return The best-scoring anchor name, or `null` when [anchors] is empty.
     */
    fun selectBestAnchor(
        anchors: Set<String>,
        links: List<TypedPatternLinkElement>,
        metamodelData: MetamodelData
    ): String? {
        if (anchors.isEmpty()) return null
        if (anchors.size == 1) return anchors.first()

        // Index associations by (sourcePropName, targetPropName) for O(1) lookup.
        val assocByProps = metamodelData.associations.associateBy { assoc ->
            assoc.source.name to assoc.target.name
        }

        fun scoreAnchor(anchorName: String): Int {
            // Only consider links that cross from the anchor into the island.
            val directLinks = links.filter { link ->
                val src = link.link.source.objectName
                val tgt = link.link.target.objectName
                (src == anchorName && tgt !in anchors) ||
                (tgt == anchorName && src !in anchors)
            }
            if (directLinks.isEmpty()) return Int.MAX_VALUE

            return directLinks.minOf { link ->
                val assoc = assocByProps[link.link.source.propertyName to link.link.target.propertyName]
                    ?: return@minOf Int.MAX_VALUE
                val multiplicity = if (link.link.source.objectName == anchorName) {
                    assoc.source.multiplicity
                } else {
                    assoc.target.multiplicity
                }
                if (multiplicity.upper == -1) Int.MAX_VALUE else multiplicity.upper
            }
        }

        return anchors.minByOrNull { scoreAnchor(it) }
    }

    /**
     * Orders island links into a BFS traversal sequence starting from [startAnchor].
     *
     * Returns a list of `(link, isReversed)` pairs; when `isReversed` is true, the link
     * is traversed target→source (`.in(edgeLabel)` instead of `.out(edgeLabel)`).
     *
     * When [metamodelData] contains association definitions, the traversal is
     * **composition-aware**: at each BFS step the available links are sorted by the
     * upper-bound multiplicity at the *from* side, so that low-fan-out (composition /
     * to-one) edges are traversed before high-fan-out (to-many) edges.  When two links
     * have equal cost the original list order is preserved (stable sort).  When
     * [metamodelData] has no associations the function behaves exactly as before.
     */
    fun orderLinksByBFS(
        links: List<TypedPatternLinkElement>,
        startAnchor: String,
        metamodelData: MetamodelData = MetamodelData.empty()
    ): List<Pair<TypedPatternLinkElement, Boolean>> {
        val visited = mutableSetOf(startAnchor)
        val ordered = mutableListOf<Pair<TypedPatternLinkElement, Boolean>>()
        val remaining = links.toMutableList()

        if (metamodelData.associations.isEmpty()) {
            // Original behaviour: greedy first-fit BFS without metamodel preference.
            var changed = true
            while (remaining.isNotEmpty() && changed) {
                changed = false
                val iterator = remaining.iterator()
                while (iterator.hasNext()) {
                    val link = iterator.next()
                    val src = link.link.source.objectName
                    val tgt = link.link.target.objectName
                    when {
                        src in visited -> {
                            ordered.add(link to false)
                            visited.add(tgt)
                            iterator.remove()
                            changed = true
                        }
                        tgt in visited -> {
                            ordered.add(link to true)
                            visited.add(src)
                            iterator.remove()
                            changed = true
                        }
                    }
                }
            }
        } else {
            // Composition-aware BFS: at every step pick the cheapest reachable link
            // (lowest multiplicity from the current-frontier node) to minimise fan-out.
            val assocByProps = metamodelData.associations
                .associateBy { it.source.name to it.target.name }

            fun linkCost(link: TypedPatternLinkElement, fromNode: String): Int {
                val assoc = assocByProps[link.link.source.propertyName to link.link.target.propertyName]
                    ?: return Int.MAX_VALUE
                val mult = if (link.link.source.objectName == fromNode) assoc.source.multiplicity
                           else assoc.target.multiplicity
                return if (mult.upper == -1) Int.MAX_VALUE else mult.upper
            }

            while (remaining.isNotEmpty()) {
                // Collect all links reachable from the current frontier.
                val reachable = mutableListOf<Triple<TypedPatternLinkElement, Boolean, Int>>()
                for (link in remaining) {
                    val src = link.link.source.objectName
                    val tgt = link.link.target.objectName
                    when {
                        src in visited && tgt !in visited ->
                            reachable.add(Triple(link, false, linkCost(link, src)))
                        tgt in visited && src !in visited ->
                            reachable.add(Triple(link, true, linkCost(link, tgt)))
                    }
                }
                if (reachable.isEmpty()) break

                // Pick the cheapest link (stable: minByOrNull preserves order on tie).
                val (best, isReversed, _) = reachable.minByOrNull { it.third }!!
                val newNode = if (isReversed) best.link.source.objectName else best.link.target.objectName
                ordered.add(best to isReversed)
                visited.add(newNode)
                remaining.remove(best)
            }
        }
        return ordered
    }

    /**
     * Determines which nodes in the BFS-ordered link sequence need a step label for
     * backtracking (i.e. the chain will `.select()` back to them for a different branch).
     */
    fun findNodesNeedingBacktrackLabel(
        orderedLinks: List<Pair<TypedPatternLinkElement, Boolean>>,
        startAnchor: String
    ): Set<String> {
        val needed = mutableSetOf<String>()
        var current = startAnchor
        for ((link, isReversed) in orderedLinks) {
            val from = if (isReversed) link.link.target.objectName else link.link.source.objectName
            val to = if (isReversed) link.link.source.objectName else link.link.target.objectName
            if (from != current) { needed.add(from) }
            current = to
        }
        return needed
    }
}
