package com.mdeo.modeltransformation.graph.mdeo

import com.mdeo.metamodel.ClassMetadata
import com.mdeo.metamodel.ModelInstance
import com.mdeo.metamodel.PropertyFieldMapping
import org.apache.tinkerpop.gremlin.structure.Direction
import org.apache.tinkerpop.gremlin.structure.Edge
import org.apache.tinkerpop.gremlin.structure.Graph
import org.apache.tinkerpop.gremlin.structure.Vertex
import org.apache.tinkerpop.gremlin.structure.VertexProperty
import org.apache.tinkerpop.gremlin.structure.util.ElementHelper
import org.apache.tinkerpop.gremlin.structure.util.StringFactory
import java.util.Collections

/**
 * A vertex in an [MdeoGraph] backed by a [ModelInstance].
 *
 * Properties are lazily read from the underlying model instance and writes
 * propagate directly to it. The vertex maintains edge adjacency maps keyed
 * by edge label for efficient traversal.
 *
 * @param id The unique integer identifier.
 * @param label The metamodel class name.
 * @param graph The owning graph.
 * @param backingInstance The model instance this vertex represents.
 * @param classMetadata The metamodel metadata for this vertex's class.
 */
class MdeoVertex(
    id: Int,
    label: String,
    @JvmField val graph: MdeoGraph,
    @JvmField var backingInstance: ModelInstance,
    @JvmField val classMetadata: ClassMetadata?
) : MdeoElement(id, label), Vertex {

    /**
     * Outgoing edges grouped by label. 
     */
    @JvmField var outEdges: MutableMap<String, MutableSet<Edge>>? = null

    /**
     * Incoming edges grouped by label. 
     */
    @JvmField var inEdges: MutableMap<String, MutableSet<Edge>>? = null

    override fun graph(): Graph = graph

    override fun addEdge(label: String, vertex: Vertex, vararg keyValues: Any): Edge {
        if (vertex !is MdeoVertex) throw IllegalArgumentException("Target vertex must be an MdeoVertex")
        if (removed || vertex.removed) throw IllegalStateException("Cannot add edge to removed vertex")
        return graph.addEdge(this, vertex, label, *keyValues)
    }

    override fun <V : Any?> property(
        cardinality: VertexProperty.Cardinality,
        key: String,
        value: V,
        vararg keyValues: Any
    ): VertexProperty<V> {
        if (removed) throw IllegalStateException("Vertex has been removed")
        ElementHelper.validateProperty(key, value)

        val meta = classMetadata
        val propertyName = resolvePropertyName(key)

        if (propertyName != null && meta != null) {
            val instance = backingInstance
            val mapping = meta.propertyFields[propertyName]
            if (mapping != null) {
                if (mapping.isCollection) {
                    @Suppress("UNCHECKED_CAST")
                    val currentList = instance.getPropertyByKey(propertyName) as? MutableList<Any?>
                        ?: throw IllegalStateException("Multi-valued property '$propertyName' has no backing list")
                    currentList.add(convertToEnumValueIfNeeded(value, mapping))
                } else {
                    instance.setPropertyByKey(propertyName, convertToEnumValueIfNeeded(value, mapping))
                }
            }
        }

        val vpId = graph.nextVertexPropertyId()
        return MdeoVertexProperty(vpId, this, key, value)
    }

    override fun <V : Any?> property(key: String): VertexProperty<V> {
        if (removed) return VertexProperty.empty()

        val meta = classMetadata ?: return VertexProperty.empty()
        val propertyName = resolvePropertyName(key) ?: return VertexProperty.empty()
        val mapping = meta.propertyFields[propertyName] ?: return VertexProperty.empty()

        if (mapping.isCollection) {
            @Suppress("UNCHECKED_CAST")
            val list = backingInstance.getPropertyByKey(propertyName) as? List<Any?>
                ?: throw IllegalStateException("Multi-valued property '$propertyName' has no backing list")
            if (list.size > 1) throw Vertex.Exceptions.multiplePropertiesExistForProvidedKey(key)
            if (list.isEmpty()) return VertexProperty.empty()
            @Suppress("UNCHECKED_CAST")
            return MdeoVertexProperty(graph.nextVertexPropertyId(), this, key, convertFromEnumValueIfNeeded(list.first(), mapping) as V)
        }

        val raw = backingInstance.getPropertyByKey(propertyName) ?: return VertexProperty.empty()
        @Suppress("UNCHECKED_CAST")
        return MdeoVertexProperty(graph.nextVertexPropertyId(), this, key, convertFromEnumValueIfNeeded(raw, mapping) as V)
    }

    override fun keys(): Set<String> {
        val meta = classMetadata ?: return Collections.emptySet()
        val keys = mutableSetOf<String>()
        for ((propName, mapping) in meta.propertyFields) {
            val value = backingInstance.getPropertyByKey(propName)
            if (value != null) {
                keys.add("prop_${mapping.fieldIndex}")
            }
        }
        return keys
    }

    @Suppress("UNCHECKED_CAST")
    override fun <V : Any?> properties(vararg propertyKeys: String): Iterator<VertexProperty<V>> {
        if (removed) return Collections.emptyIterator()
        val meta = classMetadata ?: return Collections.emptyIterator()

        val result = mutableListOf<VertexProperty<V>>()

        if (propertyKeys.isEmpty()) {
            for ((propName, mapping) in meta.propertyFields) {
                val graphKey = "prop_${mapping.fieldIndex}"
                addPropertiesForKey(backingInstance, propName, graphKey, mapping.isCollection, result)
            }
        } else if (propertyKeys.size == 1) {
            val key = propertyKeys[0]
            val propertyName = resolvePropertyName(key) ?: return Collections.emptyIterator()
            val mapping = meta.propertyFields[propertyName] ?: return Collections.emptyIterator()
            addPropertiesForKey(backingInstance, propertyName, key, mapping.isCollection, result)
        } else {
            for (key in propertyKeys) {
                val propertyName = resolvePropertyName(key) ?: continue
                val mapping = meta.propertyFields[propertyName] ?: continue
                addPropertiesForKey(backingInstance, propertyName, key, mapping.isCollection, result)
            }
        }
        return result.iterator()
    }

    /**
     * Adds vertex properties for a single metamodel property to the result list.
     *
     * For collection properties, each list element becomes a separate [MdeoVertexProperty].
     * For scalar properties, a single property is added if the value is non-null.
     *
     * @param V The value type.
     * @param instance The backing model instance.
     * @param propName The metamodel property name.
     * @param graphKey The graph key (prop_X format).
     * @param isCollection Whether this property is a collection.
     * @param result The list to append vertex properties to.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <V> addPropertiesForKey(
        instance: ModelInstance,
        propName: String,
        graphKey: String,
        isMultiValued: Boolean,
        result: MutableList<VertexProperty<V>>
    ) {
        val raw = instance.getPropertyByKey(propName) ?: return
        val mapping = classMetadata?.propertyFields?.get(propName)
        if (isMultiValued) {
            val list = raw as? List<*>
                ?: throw IllegalStateException("Expected List for multi-valued property '$propName' but got ${raw::class.simpleName}")
            for (elem in list) {
                if (elem != null) {
                    result.add(MdeoVertexProperty(graph.nextVertexPropertyId(), this, graphKey, convertFromEnumValueIfNeeded(elem, mapping) as V))
                }
            }
        } else {
            result.add(MdeoVertexProperty(graph.nextVertexPropertyId(), this, graphKey, convertFromEnumValueIfNeeded(raw, mapping) as V))
        }
    }

    override fun edges(direction: Direction, vararg edgeLabels: String): Iterator<Edge> {
        return MdeoHelper.getEdges(this, direction, *edgeLabels)
    }

    override fun vertices(direction: Direction, vararg edgeLabels: String): Iterator<Vertex> {
        return MdeoHelper.getVertices(this, direction, *edgeLabels)
    }

    override fun remove() {
        if (removed) return
        val edgesToRemove = mutableListOf<Edge>()
        edges(Direction.BOTH).forEachRemaining { edgesToRemove.add(it) }
        edgesToRemove.forEach { it.remove() }
        graph.removeVertex(id)
        removed = true
    }

    override fun toString(): String = StringFactory.vertexString(this)

    /**
     * Resolves a graph property key (like "prop_X") back to the metamodel property name.
     *
     * @param graphKey The graph key string.
     * @return The metamodel property name, or null if no mapping found.
     */
    internal fun resolvePropertyName(graphKey: String): String? {
        val meta = classMetadata ?: return null
        if (!graphKey.startsWith("prop_")) return null
        val fieldIndex = graphKey.removePrefix("prop_").toIntOrNull() ?: return null
        for ((propName, mapping) in meta.propertyFields) {
            if (mapping.fieldIndex == fieldIndex) return propName
        }
        return null
    }

    /**
     * Converts a backtick-formatted enum string (e.g., `` `Status`.`ACTIVE` ``) to the
     * actual generated enum value object expected by the backing [ModelInstance].
     *
     * If the property has no [PropertyFieldMapping.enumType], the value is returned unchanged.
     * If the value is already not a [String] (i.e., already an enum object), it is returned
     * unchanged to be safe.
     *
     * @param value The value to potentially convert.
     * @param mapping The property field mapping that may declare an [PropertyFieldMapping.enumType].
     * @return The converted enum value, or the original value if no conversion is needed.
     */
    private fun convertToEnumValueIfNeeded(value: Any?, mapping: PropertyFieldMapping): Any? {
        val enumTypeName = mapping.enumType ?: return value
        if (value !is String) return value
        // Backtick format: "`EnumName`.`EntryName`"
        val entryName = parseEnumEntryFromBacktickString(value) ?: return value
        return graph.metamodel.resolveEnumValue(enumTypeName, entryName)
    }

    /**
     * Converts a generated enum value object back to a backtick-formatted string
     * (e.g., `` `Status`.`ACTIVE` ``) for use in the traversal pipeline.
     *
     * If the property has no [PropertyFieldMapping.enumType], the value is returned unchanged.
     * If the value is already a [String], it is returned unchanged.
     *
     * @param value The value to potentially convert.
     * @param mapping The property field mapping, or null if unknown.
     * @return The backtick-formatted string, or the original value if no conversion is needed.
     */
    private fun convertFromEnumValueIfNeeded(value: Any?, mapping: PropertyFieldMapping?): Any? {
        val enumTypeName = mapping?.enumType ?: return value
        if (value == null || value is String) return value
        // Enum value objects have a getEntry() method returning the entry name string
        return try {
            val entryName = value.javaClass.getMethod("getEntry").invoke(value) as String
            "`$enumTypeName`.`$entryName`"
        } catch (_: Exception) {
            value
        }
    }

    /**
     * Parses an entry name from a backtick-formatted enum string.
     *
     * Format: `` `EnumName`.`EntryName` ``
     *
     * @param backtickString The formatted string to parse.
     * @return The entry name, or null if the format is not recognized.
     */
    private fun parseEnumEntryFromBacktickString(backtickString: String): String? {
        // Format: "`EnumName`.`EntryName`"
        val dotIdx = backtickString.indexOf('`', 1)
        if (dotIdx < 0) return null
        // Find second backtick group: after the dot
        val secondStart = backtickString.indexOf('`', dotIdx + 1)
        if (secondStart < 0) return null
        val secondEnd = backtickString.indexOf('`', secondStart + 1)
        if (secondEnd < 0) return null
        return backtickString.substring(secondStart + 1, secondEnd).takeIf { it.isNotEmpty() }
    }

    /**
     * Removes a specific vertex property from this vertex.
     *
     * For collection properties, removes the specific value from the list.
     * For scalar properties, sets the value to null.
     *
     * @param graphKey The graph property key.
     * @param vp The vertex property being removed.
     */
    internal fun removeProperty(graphKey: String, vp: MdeoVertexProperty<*>) {
        val propertyName = resolvePropertyName(graphKey) ?: return
        val meta = classMetadata ?: return
        val mapping = meta.propertyFields[propertyName] ?: return

        if (mapping.isCollection) {
            @Suppress("UNCHECKED_CAST")
            val list = backingInstance.getPropertyByKey(propertyName) as? MutableList<Any?>
                ?: throw IllegalStateException("Multi-valued property '$propertyName' has no backing list")
            list.remove(vp.value())
        } else {
            backingInstance.setPropertyByKey(propertyName, null)
        }
    }
}
