package com.mdeo.modeltransformation.graph.mdeo

import org.apache.tinkerpop.gremlin.structure.Direction
import org.apache.tinkerpop.gremlin.structure.Edge
import org.apache.tinkerpop.gremlin.structure.Graph
import org.apache.tinkerpop.gremlin.structure.Property
import org.apache.tinkerpop.gremlin.structure.Vertex
import org.apache.tinkerpop.gremlin.structure.util.StringFactory
import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils
import java.util.Collections

/**
 * An edge in an [MdeoGraph] that connects two [MdeoVertex] instances.
 *
 * Edges in this implementation do not support properties — all relationship
 * metadata is encoded in the edge label (see [com.mdeo.modeltransformation.ast.EdgeLabelUtils]).
 *
 * Edge endpoints are stored as direct object references (no transactional indirection).
 *
 * @param id The unique integer identifier for this edge.
 * @param outVertex The source vertex.
 * @param label The edge label (encodes association source/target property names).
 * @param inVertex The target vertex.
 */
class MdeoEdge(
    id: Int,
    @JvmField val outVertex: MdeoVertex,
    label: String,
    @JvmField val inVertex: MdeoVertex
) : MdeoElement(id, label), Edge {

    override fun <V : Any?> property(key: String?, value: V): Property<V> =
        throw UnsupportedOperationException("Edge properties are not supported in MdeoGraph")

    override fun <V : Any?> property(key: String?): Property<V> = Property.empty()

    override fun keys(): Set<String> = Collections.emptySet()

    override fun remove() {
        if (removed) return
        outVertex.checkWritable()
        inVertex.checkWritable()
        (graph() as MdeoGraph).removeEdge(id)
        removed = true
    }

    override fun outVertex(): Vertex = outVertex
    override fun inVertex(): Vertex = inVertex

    override fun vertices(direction: Direction): Iterator<Vertex> {
        if (removed) return Collections.emptyIterator()
        return when (direction) {
            Direction.OUT -> IteratorUtils.of(outVertex)
            Direction.IN -> IteratorUtils.of(inVertex)
            Direction.BOTH -> IteratorUtils.of(outVertex, inVertex)
        }
    }

    override fun <V : Any?> properties(vararg propertyKeys: String?): Iterator<Property<V>> =
        Collections.emptyIterator()

    override fun graph(): Graph = outVertex.graph()

    override fun toString(): String = StringFactory.edgeString(this)
}
