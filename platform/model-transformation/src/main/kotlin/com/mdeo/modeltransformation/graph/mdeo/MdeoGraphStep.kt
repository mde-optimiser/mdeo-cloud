package com.mdeo.modeltransformation.graph.mdeo

import org.apache.tinkerpop.gremlin.process.traversal.Compare
import org.apache.tinkerpop.gremlin.process.traversal.Contains
import org.apache.tinkerpop.gremlin.process.traversal.step.HasContainerHolder
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStepContract
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer
import org.apache.tinkerpop.gremlin.process.traversal.util.AndP
import org.apache.tinkerpop.gremlin.structure.Edge
import org.apache.tinkerpop.gremlin.structure.Element
import org.apache.tinkerpop.gremlin.structure.T
import org.apache.tinkerpop.gremlin.structure.Vertex
import org.apache.tinkerpop.gremlin.structure.util.StringFactory
import java.util.Collections

/**
 * Optimized [GraphStep] for [MdeoGraph] that filters results efficiently using arrays.
 *
 * Unlike TinkerGraphStep, this implementation:
 * - Indexes only on vertex label, not on arbitrary properties
 * - Uses array-backed iterators instead of list + CloseableIterator
 * - Avoids exception-based flow control in iteration
 * - Only supports integer IDs
 *
 * @param S The start type.
 * @param E The element type (Vertex or Edge).
 */
class MdeoGraphStep<S, E : Element>(
    originalGraphStep: GraphStepContract<S, E>
) : GraphStep<S, E>(
    originalGraphStep.getTraversal<S, E>(),
    originalGraphStep.returnClass,
    originalGraphStep.isStartStep,
    *originalGraphStep.ids
), HasContainerHolder<S, E> {

    private val hasContainers = mutableListOf<HasContainer>()

    init {
        originalGraphStep.labels.forEach(this::addLabel)
        this.setIteratorSupplier {
            @Suppress("UNCHECKED_CAST")
            if (Vertex::class.java.isAssignableFrom(this.returnClass)) vertices() as Iterator<E>
            else edges() as Iterator<E>
        }
    }

    /**
     * Returns vertices from the graph, filtered by IDs and HasContainers.
     *
     * When the step carries a label equality constraint — which every pattern match does,
     * since a match step compiles to `V().hasLabel(className)` — the candidates come from
     * the graph's per-label index instead of the full vertex list. The label container is
     * then dropped from the filter, as the index has already applied it.
     *
     * @return An iterator over matching vertices.
     */
    private fun vertices(): Iterator<Vertex> {
        val graph = this.traversal.graph.get() as MdeoGraph

        if (this.ids == null) return Collections.emptyIterator()

        if (this.ids.isNotEmpty()) {
            return filterToArray(graph.vertices(*this.ids), hasContainers)
        }

        val labelContainer = hasContainers.firstOrNull { indexableLabels(it) != null }
            ?: return filterToArray(graph.vertices(), hasContainers)

        val labels = indexableLabels(labelContainer)!!
        val remaining = hasContainers.filter { it !== labelContainer }
        val candidates = if (labels.size == 1) {
            graph.verticesWithLabel(labels[0])
        } else {
            // Each bucket is shuffled on its own; joined in label order, the first candidate
            // would always be of the first label that has one.
            labels.flatMapTo(ArrayList()) { graph.verticesWithLabel(it) }.also { if (graph.isShuffled) it.shuffle() }
        }
        return filterToArray(candidates.iterator(), remaining)
    }

    /**
     * Returns the labels [container] restricts to, if the per-label index can satisfy it alone.
     *
     * Only label equality (`hasLabel(x)`) and label membership (`hasLabel(x, y, …)`, which
     * Gremlin compiles to `within`) qualify; anything else — `neq`, `without`, a regex, or a
     * value that is not a plain label string — returns `null` and is left to the ordinary
     * filter pass. Buckets are disjoint, so a multi-label lookup needs no de-duplication.
     *
     * @param container The container to inspect.
     * @return The labels to look up, or `null` if this container is not index-satisfiable.
     */
    private fun indexableLabels(container: HasContainer): List<String>? {
        if (container.key != T.label.accessor) return null
        return when (container.biPredicate) {
            Compare.eq -> (container.value as? String)?.let { listOf(it) }
            Contains.within -> {
                val values = container.value as? Collection<*> ?: return null
                if (values.isEmpty() || values.any { it !is String }) null
                else values.map { it as String }
            }
            else -> null
        }
    }

    /**
     * Returns edges from the graph, filtered by IDs and HasContainers.
     *
     * @return An iterator over matching edges.
     */
    private fun edges(): Iterator<Edge> {
        val graph = this.traversal.graph.get() as MdeoGraph

        if (this.ids == null) return Collections.emptyIterator()

        return if (this.ids.isNotEmpty()) {
            filterToArray(graph.edges(*this.ids), hasContainers)
        } else {
            filterToArray(graph.edges(), hasContainers)
        }
    }

    /**
     * Filters elements from the source iterator using [containers] and returns
     * an array-backed iterator.
     *
     * The source iterator is fully consumed into a filtered array. Since the array
     * is never modified, its length serves as the natural stop condition, avoiding
     * exception-based flow control entirely. Consuming it eagerly also means the whole
     * candidate set is decided before any traverser reaches a downstream step, so a
     * traversal that modifies the graph cannot disturb an in-flight scan.
     *
     * @param T The element type.
     * @param source The source iterator to filter.
     * @param containers The constraints every returned element must satisfy.
     * @return An [ArrayIterator] over the filtered elements, or an empty iterator.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Element> filterToArray(source: Iterator<T>, containers: List<HasContainer>): Iterator<T> {
        val filtered = mutableListOf<T>()
        while (source.hasNext()) {
            val elem = source.next()
            if (HasContainer.testAll(elem, containers)) {
                filtered.add(elem)
            }
        }

        if (filtered.isEmpty()) return Collections.emptyIterator()

        val array = Array<Any>(filtered.size) { filtered[it] as Any }
        return ArrayIterator<T>(array)
    }

    override fun getHasContainers(): List<HasContainer> = Collections.unmodifiableList(hasContainers)

    override fun addHasContainer(hasContainer: HasContainer) {
        if (hasContainer.predicate is AndP<*>) {
            for (predicate in (hasContainer.predicate as AndP<*>).predicates) {
                addHasContainer(HasContainer(hasContainer.key, predicate))
            }
        } else {
            hasContainers.add(hasContainer)
        }
    }

    override fun remove() {
        throw UnsupportedOperationException("remove")
    }

    override fun hashCode(): Int = super.hashCode() xor hasContainers.hashCode()

    override fun toString(): String {
        return if (hasContainers.isEmpty()) super.toString()
        else if (ids == null || ids.isEmpty()) {
            StringFactory.stepString(this, returnClass.simpleName.lowercase(), hasContainers)
        } else {
            StringFactory.stepString(this, returnClass.simpleName.lowercase(), ids.contentToString(), hasContainers)
        }
    }
}

/**
 * A simple array-backed iterator that avoids the overhead of list wrappers
 * and exception-based flow control.
 *
 * The array is never modified after construction, so its length serves as the
 * natural stop condition. This is more efficient than TinkerGraphIterator's
 * tryComputeNext approach which catches NoSuchElementException.
 *
 * @param T The element type.
 * @param array The backing array of elements (stored as Array&lt;Any&gt; to avoid JVM array covariance issues).
 */
class ArrayIterator<T>(
    private val array: Array<Any>
) : Iterator<T> {
    private var index = 0

    override fun hasNext(): Boolean = index < array.size

    @Suppress("UNCHECKED_CAST")
    override fun next(): T {
        if (index >= array.size) throw NoSuchElementException()
        return array[index++] as T
    }
}
