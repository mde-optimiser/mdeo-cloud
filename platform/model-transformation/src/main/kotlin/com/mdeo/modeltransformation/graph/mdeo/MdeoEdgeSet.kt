package com.mdeo.modeltransformation.graph.mdeo

import org.apache.tinkerpop.gremlin.structure.Edge

/**
 * The edges of one label at one end of a vertex, in iteration order.
 *
 * Remembers the shuffle epoch of its graph in which its order was last randomised, so that the
 * graph can shuffle it lazily: only when a traversal reads it or an edge is added to it, at most
 * once per epoch. See [MdeoGraph.shuffleVertices].
 */
class MdeoEdgeSet : LinkedHashSet<Edge>() {
    /** The [MdeoGraph] shuffle epoch whose permutation this set already has. */
    @JvmField internal var shuffledIn: Long = 0
}
