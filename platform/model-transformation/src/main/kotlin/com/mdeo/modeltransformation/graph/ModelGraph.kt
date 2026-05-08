package com.mdeo.modeltransformation.graph

import com.mdeo.metamodel.Metamodel
import com.mdeo.metamodel.SerializedModel
import com.mdeo.metamodel.data.ModelData
import com.mdeo.modeltransformation.runtime.InstanceNameRegistry
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource
import org.apache.tinkerpop.gremlin.structure.Vertex

/**
 * Graph-based representation of a model.
 *
 * A [ModelGraph] wraps a graph database (e.g. TinkerGraph) that stores model instances
 * as vertices and associations as edges. It owns the [Metamodel] used for graph key
 * resolution and the [InstanceNameRegistry] that maps vertex IDs to instance names.
 *
 * Implementations support deep copying for copy-before-transform mutation strategies,
 * and resetting nondeterministic behavior between match operations.
 *
 * @see TinkerModelGraph
 */
interface ModelGraph : AutoCloseable {

    /**
     * The compiled metamodel this graph is based on. 
     */
    val metamodel: Metamodel

    /**
     * Arbitrary metadata attached to this graph.
     *
     * Mutable so that the optimizer can swap in a fresh [ModelMetadata] instance
     * (e.g. [com.mdeo.optimizer.solution.FailedOperatorsMetadata]) after a
     * successful mutation without touching the graph state itself.
     *
     * Because [ModelMetadata.deepCopy] is a no-op by default, parent and child
     * graphs produced by [deepCopy] share the same metadata object.  The
     * optimizer replaces this reference on the child after a successful
     * mutation to decouple the child's failure tracking from the parent's.
     */
    var metadata: ModelMetadata?

    /**
     * Registry mapping vertex IDs to instance names. 
     */
    val nameRegistry: InstanceNameRegistry

    /**
     * Returns a Gremlin [GraphTraversalSource] for querying and modifying this graph.
     * The returned source is valid until [close] is called.
     */
    fun traversal(): GraphTraversalSource

    /**
     * Creates a deep copy of this model graph.
     *
     * The copy has independent graph state, name registry, and can be modified
     * without affecting the original. For implementations backed by ordered stores
     * (e.g. TinkerGraph), the copy may have shuffled vertex insertion order to
     * provide nondeterministic traversal behavior.
     *
     * @return A new, independent [ModelGraph] with the same model state.
     */
    fun deepCopy(): ModelGraph

    /**
     * Resets the nondeterministic behavior of the underlying graph.
     *
     * This ensures that subsequent operations (like match queries) may produce
     * different iteration orders. For stores where traversal order depends on
     * insertion order (e.g. TinkerGraph), this internally rebuilds the graph
     * with shuffled vertex order.
     *
     * The first call after construction or [deepCopy] is a no-op, since the
     * graph already has fresh nondeterministic state from its initial creation.
     * Subsequent calls perform the actual reset.
     *
     * @return A mapping from old vertex IDs to new vertex IDs. Empty if no change occurred.
     */
    fun resetNondeterminism(): Map<Any, Any>

    /**
     * Creates a [VertexRef] for the given raw vertex ID.
     *
     * The returned [VertexRef] is tracked by this graph instance and its
     * [VertexRef.rawId] is automatically updated whenever [resetNondeterminism]
     * rebuilds the graph with new vertex IDs. Use this factory instead of
     * constructing [VertexRef] directly so that updates propagate correctly.
     *
     * @param rawId The current raw vertex ID (e.g., from `Vertex.id()`).
     * @return A new [VertexRef] pointing to the given vertex.
     */
    fun createVertexRef(rawId: Any): VertexRef

    /**
     * Converts the current graph state back to [ModelData].
     *
     * @return The model data representing the current graph state.
     */
    fun toModelData(): ModelData

    /**
     * Returns the current graph state as a [com.mdeo.metamodel.Model].
     *
     * For [MdeoModelGraph], this returns the live model backed directly by the
     * underlying [ModelInstance] objects. For [TinkerModelGraph], this performs
     * an eager conversion via [toModelData] and [com.mdeo.metamodel.Metamodel.loadModel].
     *
     * @return A [com.mdeo.metamodel.Model] reflecting the current state of this graph.
     */
    fun toModel(): com.mdeo.metamodel.Model

    /**
     * Converts the current graph state to a [SerializedModel] using the most efficient
     * backing format available for this implementation.     *
     * [MdeoModelGraph] returns a [SerializedModel.AsBinary] produced by
     * [com.mdeo.metamodel.ModelBinarySerializer] for maximum speed.
     * [TinkerModelGraph] returns a [SerializedModel.AsModelData] wrapping
     * the result of [toModelData].
     *
     * @return A [SerializedModel] representing the current graph state.
     */
    fun toSerializedModel(): SerializedModel

    /**
     * Appends an `addV(className)` step to [traversal] and labels it [stepLabel].
     *
     * This is the canonical way to create a new vertex during a transformation. Placing
     * the logic here allows each backend to inject backend-specific post-creation steps
     * without leaking implementation details into the transformation engine.
     *
     * @param traversal The current traversal that the creation step is appended to.
     * @param className The metamodel class name of the vertex to create.
     * @param stepLabel The Gremlin step-label used to reference the vertex in later steps.
     * @return The traversal extended with the vertex creation step.
     */
    @Suppress("UNCHECKED_CAST")
    fun addVertexStep(
        traversal: GraphTraversal<Vertex, Map<String, Any>>,
        className: String,
        stepLabel: String
    ): GraphTraversal<Vertex, Map<String, Any>>
}
