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
     * Cardinality statistics of the current model, used by the match planner to order plan
     * steps by estimated selectivity.
     *
     * Implementations are expected to cache the snapshot and refresh it only when the model
     * changes structurally; see [GraphStatisticsCache]. Because the statistics only affect
     * *step order*, a slightly stale snapshot is never a correctness problem.
     */
    val statistics: ModelStatistics

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

    // ---- Model inputs ----

    /**
     * The model-input slots whose models are loaded into this graph ([loadModelInput]): empty
     * except during a run of a transformation with model inputs.
     *
     * While it is not empty, every pattern object a match scans for is filtered by its model
     * ([filterModelSlot]): an object of the implicit model only matches vertices without a slot,
     * one of a model input only vertices of that input's slot. That is a property of the run, not
     * of the transformation: a callee without model inputs needs the implicit-model filter too
     * while its caller's inputs are loaded. While it is empty, matching emits no slot step at all,
     * so a run without model inputs pays nothing for the feature.
     */
    val modelInputSlots: Set<Int>

    /**
     * Loads [model] into this graph as the read-only model of model-input slot [slot], for the
     * duration of one run (see [com.mdeo.modeltransformation.runtime.TransformationEngine.execute]).
     *
     * Every vertex of [model] is copied with its properties and the edges between its vertices,
     * tagged with [slot] in [MODEL_SLOT_KEY], a hidden key that is not a metamodel property; the
     * implicit model's vertices carry no slot. [model] itself is not changed. The copied vertices
     * are registered in [nameRegistry] under names qualified by the slot ([modelInputName]), so
     * they never collide with the implicit model's names. [statistics] include the loaded
     * vertices and edges until [unloadModelInputs]: the match planner estimates from the counts of
     * all loaded models together. That is adequate because statistics only order plan steps and
     * never decide matches, and because the inputs of a crossover are models of the same
     * population as the implicit one, which scale every count alike.
     *
     * Why one graph with tagged vertices rather than a graph per model: a match is a single
     * Gremlin traversal over a single graph, and one pattern may join objects of several models
     * (`where c.name == dc.name`), so all of them must be reachable from one traversal source.
     * The tag is a vertex key rather than a label (the label is the class, which scans, the
     * planner's statistics and `hasLabel` filters rely on) or an ID range (Tinker renumbers its
     * vertices on every [resetNondeterminism]); it costs the implicit model nothing, as its
     * vertices carry no tag. Links never cross models, so only scans need the filter, never
     * edge walks.
     *
     * @param slot The slot, at least 1 and not loaded yet.
     * @param model A model of this graph's metamodel (of either backend).
     */
    fun loadModelInput(slot: Int, model: ModelGraph)

    /**
     * Removes every loaded model input ([loadModelInput]): its vertices, its edges, its names in
     * [nameRegistry] and its share of [statistics], so that the graph holds exactly its implicit
     * model again, without a trace of the inputs. Does nothing when none is loaded.
     */
    fun unloadModelInputs()

    /**
     * Appends the filter that keeps only vertices of model slot [slot] ([IMPLICIT_MODEL_SLOT] for
     * the implicit model) to [traversal], positioned at vertices. Only called while
     * [modelInputSlots] is not empty.
     *
     * The default keeps vertices without a slot for the implicit model (`hasNot`) and those
     * tagged with the slot otherwise; a backend whose vertices can report the implicit slot as a
     * value filters with a plain `has` instead, which its graph step can fold into its scan.
     *
     * @param traversal The traversal, positioned at vertices.
     * @param slot The model slot to keep.
     * @return The filtered traversal.
     */
    @Suppress("UNCHECKED_CAST")
    fun <S, E> filterModelSlot(traversal: GraphTraversal<S, E>, slot: Int): GraphTraversal<S, E> =
        if (slot == IMPLICIT_MODEL_SLOT) traversal.hasNot(MODEL_SLOT_KEY) as GraphTraversal<S, E>
        else traversal.has(MODEL_SLOT_KEY, slot) as GraphTraversal<S, E>

    /**
     * The model slot of [vertex]: the slot of the model input it was loaded for, or
     * [IMPLICIT_MODEL_SLOT].
     *
     * @param vertex A vertex of this graph.
     * @return Its slot.
     */
    fun modelSlotOf(vertex: Vertex): Int =
        vertex.property<Int>(MODEL_SLOT_KEY).orElse(IMPLICIT_MODEL_SLOT)

    companion object {
        /**
         * The vertex key holding the model-input slot of a loaded vertex. It is not a metamodel
         * property (those are `prop_<index>`) and not an identifier, so no pattern can read or
         * write it. (Not a TinkerPop hidden key `~…`: graphs refuse to store those.)
         */
        const val MODEL_SLOT_KEY = "@modelSlot"

        /** The slot of the implicit model, the one a transformation runs on and changes. */
        const val IMPLICIT_MODEL_SLOT = 0

        /**
         * The name under which an instance of a model input is registered while it is loaded:
         * qualified by the slot with characters no instance name of a model has, so that it
         * neither collides with nor is reused for a name of the implicit model.
         *
         * @param slot The model-input slot.
         * @param name The instance's name in its own model.
         * @return The qualified name.
         */
        fun modelInputName(slot: Int, name: String): String = "<model $slot>$name"
    }
}
