package com.mdeo.modeltransformation.graph.tinker

import com.mdeo.metamodel.Metamodel
import com.mdeo.metamodel.data.ClassData
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.metamodel.data.PropertyData
import com.mdeo.modeltransformation.ast.EdgeLabelUtils
import com.mdeo.metamodel.data.ModelData
import com.mdeo.metamodel.data.ModelDataInstance
import com.mdeo.metamodel.data.ModelDataPropertyValue
import com.mdeo.modeltransformation.graph.ModelGraph
import com.mdeo.modeltransformation.runtime.InstanceNameRegistry
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource
import org.apache.tinkerpop.gremlin.structure.Vertex
import org.apache.tinkerpop.gremlin.structure.VertexProperty
import org.slf4j.LoggerFactory

/**
 * Loads [ModelData] into a TinkerGraph.
 *
 * Creates vertices for instances and edges for links, preserving
 * all property values and using the edge label format from [EdgeLabelUtils].
 *
 * Instance names are registered in the [InstanceNameRegistry] instead of being
 * stored as graph properties, avoiding conflicts with metamodel properties.
 *
 * Properties are stored under `prop_X` graph keys matching the compiled instance
 * class field names from the [Metamodel]. List properties use
 * [VertexProperty.Cardinality.list] with each element added separately.
 *
 * Enum values are stored using the backtick format `` `EnumName`.`entryName` ``
 * matching the format used for enum values in the transformation scope.
 */
class ModelDataGraphLoader {
    private val logger = LoggerFactory.getLogger(ModelDataGraphLoader::class.java)
    
    /**
     * Loads [ModelData] into the graph.
     *
     * @param g The graph traversal source.
     * @param modelData The model data to load.
     * @param nameRegistry Registry for tracking vertex ID to name mappings.
     * @param metamodel The compiled metamodel for property graph key resolution and enum lookup.
     * @param modelSlot The model slot of the loaded vertices: [ModelGraph.IMPLICIT_MODEL_SLOT]
     *   for the model the graph holds, else the slot of a model input, which every vertex is
     *   tagged with ([ModelGraph.MODEL_SLOT_KEY]) and whose names are qualified with
     *   ([ModelGraph.modelInputName]).
     * @return Map of instance names to their vertices.
     */
    fun load(
        g: GraphTraversalSource,
        modelData: ModelData,
        nameRegistry: InstanceNameRegistry,
        metamodel: Metamodel,
        modelSlot: Int = ModelGraph.IMPLICIT_MODEL_SLOT
    ): Map<String, Vertex> {
        val classMap = metamodel.data.classes.associateBy { it.name }
        val vertexMap = createVertices(g, modelData.instances, nameRegistry, classMap, metamodel, modelSlot)
        createEdges(g, modelData, vertexMap)
        return vertexMap
    }
    
    /**
     * Creates vertices for all instances.
     */
    private fun createVertices(
        g: GraphTraversalSource,
        instances: List<ModelDataInstance>,
        nameRegistry: InstanceNameRegistry,
        classMap: Map<String, ClassData>,
        metamodel: Metamodel,
        modelSlot: Int
    ): Map<String, Vertex> {
        return instances.associate { instance ->
            instance.name to createVertex(g, instance, nameRegistry, classMap, metamodel, modelSlot)
        }
    }
    
    /**
     * Creates a vertex for a single instance.
     * Registers the vertex ID with its name in the registry instead of storing it as a property.
     * 
     * List properties are stored using Cardinality.list, with each element added separately.
     * The className is used only as the vertex label, not as a property.
     */
    private fun createVertex(
        g: GraphTraversalSource,
        instance: ModelDataInstance,
        nameRegistry: InstanceNameRegistry,
        classMap: Map<String, ClassData>,
        metamodel: Metamodel,
        modelSlot: Int
    ): Vertex {
        var traversal = g.addV(instance.className)
        if (modelSlot != ModelGraph.IMPLICIT_MODEL_SLOT) {
            traversal = traversal.property(ModelGraph.MODEL_SLOT_KEY, modelSlot)
        }

        for ((propertyName, propertyValue) in instance.properties) {
            val graphKey = resolveGraphKey(metamodel, instance.className, propertyName)
            traversal = addPropertyValue(traversal, instance.className, propertyName, graphKey, propertyValue, classMap)
        }
        
        val vertex = traversal.next()
        
        nameRegistry.register(
            vertex.id(),
            if (modelSlot == ModelGraph.IMPLICIT_MODEL_SLOT) instance.name else ModelGraph.modelInputName(modelSlot, instance.name)
        )
        
        return vertex
    }

    /**
     * Adds property value(s) to a vertex traversal.
     * For list properties, adds each element separately with Cardinality.list.
     * For scalar properties, adds a single value.
     */
    private fun addPropertyValue(
        traversal: org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal<Vertex, Vertex>,
        className: String,
        propertyName: String,
        graphKey: String,
        propertyValue: ModelDataPropertyValue,
        classMap: Map<String, ClassData>
    ): org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal<Vertex, Vertex> {
        return when (propertyValue) {
            is ModelDataPropertyValue.ListValue -> {
                var result = traversal
                for (element in propertyValue.values) {
                    val value = convertScalarPropertyValue(element, className, propertyName, classMap)
                    if (value != null) {
                        result = result.property(VertexProperty.Cardinality.list, graphKey, value)
                    }
                }
                result
            }
            else -> {
                val value = convertScalarPropertyValue(propertyValue, className, propertyName, classMap)
                if (value != null) {
                    traversal.property(graphKey, value)
                } else {
                    traversal
                }
            }
        }
    }
    
    /**
     * Converts ModelDataPropertyValue to a Gremlin-compatible scalar value.
     * Does not handle ListValue - that is handled separately in addPropertyValue.
     *
     * Enum values are formatted as `EnumName`.`entryName` using the metamodel to
     * look up the enum type name for the given class property, considering the
     * class hierarchy.
     */
    private fun convertScalarPropertyValue(
        value: ModelDataPropertyValue,
        className: String,
        propertyName: String,
        classMap: Map<String, ClassData>
    ): Any? {
        return when (value) {
            is ModelDataPropertyValue.NullValue -> null
            is ModelDataPropertyValue.StringValue -> value.value
            is ModelDataPropertyValue.NumberValue -> value.value
            is ModelDataPropertyValue.BooleanValue -> value.value
            is ModelDataPropertyValue.EnumValue -> {
                val propData = findPropertyInHierarchy(classMap, className, propertyName)
                    ?: throw IllegalStateException("No property metadata found for $propertyName on class $className")
                val enumTypeName = propData.enumType
                    ?: throw IllegalStateException("Property $propertyName on class $className is not an enum property in the metamodel")
                formatEnumEntry(enumTypeName, value.enumEntry)
            }
            is ModelDataPropertyValue.ListValue -> throw IllegalArgumentException("Lists should be handled by addPropertyValue, not convertScalarPropertyValue")
        }
    }
    
    /**
     * Formats an enum entry into the backtick-quoted string used in the transformation scope.
     *
     * @param enumTypeName The name of the enum type (e.g., "Status")
     * @param entryName The name of the enum entry (e.g., "ACTIVE")
     * @return The formatted string (e.g., "`Status`.`ACTIVE`")
     */
    private fun formatEnumEntry(enumTypeName: String, entryName: String): String =
        "`$enumTypeName`.`$entryName`"
    
    /**
     * Looks up a PropertyData by name for the given class, traversing the class
     * hierarchy to find inherited properties.
     *
     * @param classMap Map of class name to ClassData for the full metamodel
     * @param className The class to start the search from
     * @param propertyName The property name to find
     * @return The PropertyData if found, or null if not found in the hierarchy
     */
    private fun findPropertyInHierarchy(
        classMap: Map<String, ClassData>,
        className: String,
        propertyName: String
    ): PropertyData? {
        val classData = classMap[className] ?: return null
        val property = classData.properties.find { it.name == propertyName }
        if (property != null) return property
        for (parentName in classData.extends) {
            val parentProperty = findPropertyInHierarchy(classMap, parentName, propertyName)
            if (parentProperty != null) return parentProperty
        }
        return null
    }
    
    /**
     * Creates edges for all links in the model.
     */
    private fun createEdges(
        g: GraphTraversalSource,
        modelData: ModelData,
        vertexMap: Map<String, Vertex>
    ) {
        for (link in modelData.links) {
            createEdge(g, link, vertexMap)
        }
    }
    
    /**
     * Creates an edge for a single link.
     */
    private fun createEdge(
        g: GraphTraversalSource,
        link: com.mdeo.metamodel.data.ModelDataLink,
        vertexMap: Map<String, Vertex>
    ) {
        val sourceVertex = vertexMap[link.sourceName]
        val targetVertex = vertexMap[link.targetName]
        
        if (sourceVertex == null) {
            logger.warn("Source vertex not found: ${link.sourceName}")
            return
        }
        if (targetVertex == null) {
            logger.warn("Target vertex not found: ${link.targetName}")
            return
        }
        
        val edgeLabel = EdgeLabelUtils.computeEdgeLabel(
            link.sourceProperty,
            link.targetProperty
        )
        
        g.V(sourceVertex).addE(edgeLabel).to(targetVertex).next()
    }

    private fun resolveGraphKey(metamodel: Metamodel, className: String, propertyName: String): String {
        val mapping = metamodel.metadata.classes[className]?.propertyFields?.get(propertyName)
        return mapping?.let { "prop_${it.fieldIndex}" } ?: propertyName
    }
}
