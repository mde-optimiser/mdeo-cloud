package com.mdeo.modeltransformation.graph

import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategies
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.FilterRankingStrategy
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.LazyBarrierStrategy
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.PathRetractionStrategy

/**
 * The TinkerPop strategies the model graphs leave out of their traversals.
 *
 * `LazyBarrierStrategy` is always left out. `PathRetractionStrategy` and `FilterRankingStrategy`
 * are left out too unless [ENV] (or the system property [PROPERTY]) is `1` or `true`: path
 * retraction can then drop the path labels no later step reads, which keeps a traverser's memory
 * lower on traversals that do not select every label, at the cost of an analysis that grows with
 * the square of the traversal's length (see `TraversalStrategiesTest`). The flag is read once,
 * when this object initialises, that is before the model graphs register their strategies.
 */
object ModelGraphStrategies {
    /** Environment variable that restores TinkerPop's path retraction and filter ranking. */
    const val ENV = "MDEO_MT_DEFAULT_STRATEGIES"

    /** System property with the same effect as [ENV]; it takes precedence. */
    const val PROPERTY = "mdeo.mt.defaultStrategies"

    /** Whether path retraction and filter ranking are kept, read once at startup. */
    val defaultStrategies: Boolean = isEnabled(System.getProperty(PROPERTY) ?: System.getenv(ENV))

    /** Whether a flag value enables the default strategies: `1` or `true`, ignoring case. */
    fun isEnabled(value: String?): Boolean = value?.trim()?.lowercase() in setOf("1", "true")

    /** The strategies left out, given whether the default strategies are kept. */
    fun removed(defaultStrategies: Boolean = this.defaultStrategies): Array<Class<out TraversalStrategy<*>>> =
        if (defaultStrategies) {
            arrayOf(LazyBarrierStrategy::class.java)
        } else {
            arrayOf(LazyBarrierStrategy::class.java, PathRetractionStrategy::class.java, FilterRankingStrategy::class.java)
        }

    /** Removes the [removed] strategies from [strategies] (in place) and returns them. */
    fun configure(
        strategies: TraversalStrategies,
        defaultStrategies: Boolean = this.defaultStrategies
    ): TraversalStrategies = strategies.removeStrategies(*removed(defaultStrategies))
}
