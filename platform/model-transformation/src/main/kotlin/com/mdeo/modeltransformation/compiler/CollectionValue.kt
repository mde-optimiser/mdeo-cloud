package com.mdeo.modeltransformation.compiler

import com.mdeo.modeltransformation.graph.ModelGraph
import com.mdeo.modeltransformation.graph.VertexRef
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource
import org.apache.tinkerpop.gremlin.structure.Vertex

/**
 * The value of a collection parameter or output (`List<T>` / `Set<T>`), held in a
 * [VariableBinding.ValueBinding].
 *
 * Model elements are kept as [VertexRef]s, which stay valid when the graph is rebuilt with new
 * vertex ids (see [ModelGraph.resetNondeterminism]); they are resolved to the current vertices
 * whenever an expression reads the collection. Scalars are kept as they are.
 *
 * A collection output starts empty and grows with `+=`; a set ignores an element it already
 * holds (model elements by vertex, scalars by equality).
 *
 * @param isSet Whether the collection is a set.
 * @param items The elements: [VertexRef]s for model elements, values for scalars.
 */
class CollectionValue(
    val isSet: Boolean,
    items: List<Any?> = emptyList()
) {
    private val _items: MutableList<Any?> = mutableListOf()

    init {
        items.forEach { add(it) }
    }

    /** The elements, in insertion order. */
    val items: List<Any?> get() = _items

    /** The number of elements. */
    val size: Int get() = _items.size

    /**
     * Adds an element; a set skips one it already holds.
     *
     * @param item A [VertexRef] or a scalar.
     */
    fun add(item: Any?) {
        if (isSet && _items.any { same(it, item) }) {
            return
        }
        _items.add(item)
    }

    /**
     * Resolves the elements to the current graph: model elements become their vertices (an
     * element whose vertex no longer exists is left out), scalars stay.
     *
     * @param traversalSource The traversal source of the current graph.
     * @return The resolved elements.
     */
    fun resolve(traversalSource: GraphTraversalSource?): List<Any?> {
        val refs = _items.filterIsInstance<VertexRef>()
        if (refs.isEmpty() || traversalSource == null) {
            return _items.toList()
        }
        val vertices: Map<Any, Vertex> = traversalSource.V(*refs.map { it.rawId }.toTypedArray())
            .toList()
            .associateBy { it.id() }
        return _items.mapNotNull { item ->
            if (item is VertexRef) vertices[item.rawId] else item
        }
    }

    /** A copy with the same elements. */
    fun copy(isSet: Boolean = this.isSet): CollectionValue = CollectionValue(isSet, _items)

    override fun toString(): String = "${if (isSet) "Set" else "List"}$_items"

    companion object {
        /**
         * Builds a collection from the values a traversal produced, turning vertices into
         * [VertexRef]s of [modelGraph].
         *
         * @param isSet Whether the collection is a set.
         * @param values The values (vertices or scalars); nested iterables are flattened one level.
         * @param modelGraph The graph the vertices belong to.
         * @return The collection.
         */
        fun of(isSet: Boolean, values: Iterable<Any?>, modelGraph: ModelGraph): CollectionValue {
            val collection = CollectionValue(isSet)
            for (value in values) {
                when (value) {
                    is CollectionValue -> value.items.forEach { collection.add(it) }
                    is Iterable<*> -> value.forEach { collection.add(item(it, modelGraph)) }
                    else -> collection.add(item(value, modelGraph))
                }
            }
            return collection
        }

        /**
         * Turns a value into a collection element: a vertex becomes a [VertexRef], an instance
         * binding its reference, a value binding its value.
         *
         * @param value The value.
         * @param modelGraph The graph the vertices belong to.
         * @return The element.
         */
        fun item(value: Any?, modelGraph: ModelGraph): Any? = when (value) {
            is Vertex -> modelGraph.createVertexRef(value.id())
            is VariableBinding.InstanceBinding -> value.vertexRef
            is VariableBinding.ValueBinding -> item(value.value, modelGraph)
            else -> value
        }

        private fun same(a: Any?, b: Any?): Boolean =
            if (a is VertexRef && b is VertexRef) a.rawId == b.rawId else a == b
    }
}
