package com.mdeo.modeltransformation.runtime.match.plan

import com.mdeo.expression.ast.expressions.TypedBinaryExpression
import com.mdeo.expression.ast.expressions.TypedExpression
import com.mdeo.metamodel.data.AssociationData
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.modeltransformation.ast.EdgeLabelUtils
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLinkElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstanceElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternPropertyAssignment
import com.mdeo.modeltransformation.graph.ModelStatistics
import com.mdeo.modeltransformation.runtime.match.ExpressionNodeAnalyzer

/**
 * Model-sensitive cost estimates used to order the structural steps of a [MatchPlan].
 *
 * ## What it estimates
 *
 * The planner builds a match incrementally: after *k* steps it holds a set of partial
 * matches whose expected size is `S_k`. Every candidate next step multiplies that size by a
 * **branching factor**:
 *
 * ```
 * S_k = S_{k-1} × branchingFactor(step_k)
 * ```
 *
 * and the total work of the plan is dominated by `Σ S_k`. This class estimates the
 * branching factor of each candidate from *exact counts of the current model*
 * ([ModelStatistics]) rather than from metamodel structure alone:
 *
 * - **Vertex scan of `x : C`** — `|C| × selectivity(x's constant property filters)`, where
 *   `|C|` counts `C` and all of its subclasses, because the emitted `hasLabel(...)` step
 *   admits subtypes.
 * - **Edge walk along association `A`** — the *average out-degree* `|edges(A)| / |sourceClass(A)|`,
 *   capped at 1 when the destination end has multiplicity upper bound 1.
 * - **Cycle closure** — when covering an instance makes an *additional* pattern link
 *   checkable (both endpoints covered), that check has selectivity
 *   `|edges(A)| / (|sourceClass(A)| × |targetClass(A)|)`, i.e. the probability that a given
 *   ordered vertex pair is connected by `A`. This is what makes cyclic patterns cheap and
 *   is the single most valuable estimate here.
 * - **Attribute predicates** — property constraints and where-clauses that become evaluable
 *   fall back to the classical System R constants (Selinger et al., *Access Path Selection
 *   in a Relational Database Management System*, SIGMOD 1979): `1/10` for equality, `1/3`
 *   for a range comparison, `0.9` for inequality. The engine keeps no value histograms, so
 *   nothing better is available.
 *
 * ## Relation to the literature
 *
 * Estimating branching factors from the instance model and then choosing a search plan that
 * minimises them is the *model-sensitive search plan* of Varró, Friedl & Varró (*Adaptive
 * Graph Pattern Matching for Model Transformations using Model-sensitive Search Plans*,
 * ENTCS 152, 2006), and the same idea drives the cost model of GrGen.NET (Batz, Kroll &
 * Geiß, *A First Experimental Evaluation of Search Plan Driven Graph Pattern Matching*,
 * AGTIVE 2007). Those tools compute the optimum search plan as a minimum spanning
 * arborescence (Edmonds' algorithm) over the weighted search graph; [MatchPlanBuilder]
 * instead keeps its existing greedy step-by-step selection and only replaces the *weights*,
 * which preserves all the other ordering rules the planner already implements (application
 * conditions emitted as early as possible, injective checks, variable binding order).
 *
 * ## Accuracy
 *
 * Every estimate assumes uniform distribution and independence between predicates — the
 * standard assumptions, and the standard source of error. The estimates are only ever used
 * to *order* steps, never to decide which matches exist, so an inaccurate estimate can cost
 * time but can never change a result.
 *
 * @property metamodelData Metamodel used to resolve associations and the class hierarchy.
 * @property statistics Exact cardinality snapshot of the model being matched.
 * @property nodeAnalyzer Extracts the node names referenced by an expression.
 * @property isCollectionExpression Returns `true` for collection-typed expressions, which
 *           cannot be emitted as simple vertex-property filters.
 */
internal class MatchCostModel(
    private val metamodelData: MetamodelData,
    private val statistics: ModelStatistics,
    private val nodeAnalyzer: ExpressionNodeAnalyzer,
    private val isCollectionExpression: (TypedExpression) -> Boolean
) {

    companion object {
        /** Selectivity of `attribute == value` with no value distribution available. */
        const val EQUALITY_SELECTIVITY = 0.1

        /** Selectivity of `attribute <|>|<=|>= value`. */
        const val RANGE_SELECTIVITY = 1.0 / 3.0

        /** Selectivity of `attribute != value`. */
        const val INEQUALITY_SELECTIVITY = 0.9

        /** Selectivity assumed for a predicate whose top-level operator is not recognised. */
        const val UNKNOWN_PREDICATE_SELECTIVITY = 0.5

        /**
         * Smallest branching factor an estimate may report.
         *
         * Estimates are compared, so a genuine zero (an empty class) must stay zero — it
         * marks the ideal first step, one that empties the traverser stream immediately.
         * This floor only prevents a *non-empty* class from being estimated at exactly zero
         * after several selectivity factors have been multiplied together, which would make
         * unrelated candidates compare equal.
         */
        const val MIN_NONZERO_FACTOR = 1e-9

        /**
         * Relative tolerance below which two branching factors count as equal.
         *
         * Near-ties are resolved by the planner's pre-existing structural tie-breakers, so
         * a model whose statistics carry no useful signal keeps the legacy step order.
         */
        const val COMPARISON_EPSILON = 1e-6
    }

    /** Metamodel-derived lookup tables, shared by every cost model over the same metamodel. */
    private val metamodelIndex = MetamodelIndex.of(metamodelData)

    /** Maps each class name to itself plus all of its transitive subclasses. */
    private val subclassesOf: Map<String, Set<String>> get() = metamodelIndex.subclassesOf

    /** Associations keyed by the `(sourceRole, targetRole)` pair carried by pattern links. */
    private val associationByRoles: Map<Pair<String?, String?>, AssociationData>
        get() = metamodelIndex.associationByRoles

    /** Cached instance counts per class name, including subclasses. */
    private val instanceCountCache by lazy(LazyThreadSafetyMode.NONE) { HashMap<String, Double>() }

    /**
     * Per-link memo of the two derived values the scoring loop asks for repeatedly.
     *
     * The greedy loop evaluates every candidate against every link at every step, and each
     * evaluation would otherwise rebuild the composite edge label string and re-look-up the
     * association. Keyed by identity because pattern links are reused as-is within a plan.
     */
    private val linkCache by lazy(LazyThreadSafetyMode.NONE) {
        java.util.IdentityHashMap<TypedPatternLinkElement, LinkFacts>()
    }

    /**
     * Derived per-link values.
     *
     * @property edges Number of edges in the model carrying this link's label.
     * @property association The metamodel association, or `null` when the link's roles do not
     *           resolve to one.
     * @property checkSelectivity Selectivity of verifying the link between two covered
     *           instances; see [linkCheckSelectivity].
     */
    private class LinkFacts(
        val edges: Int,
        val association: AssociationData?,
        val checkSelectivity: Double
    )

    /**
     * Returns the number of vertices that a `hasLabel` filter for [className] admits, i.e.
     * the instance count of the class **and all of its subclasses**.
     *
     * @param className Metamodel class name, or `null` for "any vertex".
     * @return The instance count as a double, so callers can multiply it by selectivities.
     */
    fun instanceCount(className: String?): Double {
        if (className == null) return statistics.vertexCount.toDouble()
        return instanceCountCache.getOrPut(className) {
            val labels = subclassesOf[className] ?: setOf(className)
            labels.sumOf { statistics.verticesWithLabel(it) }.toDouble()
        }
    }

    /**
     * Returns the estimated number of partial matches produced per incoming traverser by a
     * [BaseStep.VertexScan] of [instance].
     *
     * This is the instance count of the scanned class reduced by the selectivity of the
     * constant property filters that the planner will inline directly after the scan.
     *
     * @param instance The instance element that the scan covers.
     * @return The estimated branching factor of the scan.
     */
    fun scanFactor(instance: TypedPatternObjectInstanceElement): Double {
        val count = instanceCount(instance.objectInstance.className)
        if (count == 0.0) return 0.0
        return floorNonZero(count * constantPropertySelectivity(instance))
    }

    /**
     * Returns the estimated number of destination vertices reached per incoming traverser by
     * a [BaseStep.EdgeWalk] along [link].
     *
     * The estimate is the association's average out-degree in the *current model*: the number
     * of edges carrying the link's label divided by the number of vertices the walk starts
     * from.
     *
     * An [AssociationEndData] describes a field *on* its own `className` — `("Hospital",
     * "shifts", 0..*)` is `Hospital.shifts : Set<Shift>` — so the multiplicity that bounds a
     * walk is the one belonging to the end the walk *starts* at, not the end it lands on.
     * When that bound is 1 the fan-out cannot exceed 1 and the average is capped, which keeps
     * a to-one walk strictly preferred over any to-many alternative even in a model whose
     * edges are unevenly distributed.
     *
     * @param link The pattern link to be traversed.
     * @param isReversed `true` when the link is followed from its AST target to its source.
     * @param toInstance The instance element reached by the walk, or `null` when it is not
     *        part of the matchable set. Its constant property filters, if any, are applied.
     * @return The estimated branching factor of the walk.
     */
    fun walkFactor(
        link: TypedPatternLinkElement,
        isReversed: Boolean,
        toInstance: TypedPatternObjectInstanceElement?
    ): Double {
        val propertySelectivity = toInstance?.let { constantPropertySelectivity(it) } ?: 1.0
        val facts = factsFor(link)
        val edges = facts.edges
        if (edges == 0) return 0.0

        val association = facts.association
            ?: return floorNonZero(propertySelectivity)

        val fromEnd = if (isReversed) association.target else association.source
        val sources = instanceCount(fromEnd.className)
        val rawFanout = if (sources <= 0.0) edges.toDouble() else edges / sources
        val fanout = if (fromEnd.multiplicity.upper == 1) minOf(rawFanout, 1.0) else rawFanout
        return floorNonZero(fanout * propertySelectivity)
    }

    /**
     * Returns the selectivity of verifying that an already-covered pair of instances is
     * connected by [link] — the probability that a given ordered vertex pair of the
     * association's end classes carries such an edge.
     *
     * This is the estimate that rewards closing a cycle in the pattern: a step that also
     * makes a second link checkable is far more selective than its own fan-out suggests.
     *
     * @param link The pattern link whose endpoints are both covered.
     * @return A selectivity in `[0, 1]`.
     */
    fun linkCheckSelectivity(link: TypedPatternLinkElement): Double = factsFor(link).checkSelectivity

    /** Computes, once per link, the values cached in [LinkFacts]. */
    private fun factsFor(link: TypedPatternLinkElement): LinkFacts = linkCache.getOrPut(link) {
        val edges = statistics.edgesWithLabel(
            EdgeLabelUtils.computeEdgeLabel(link.link.source.propertyName, link.link.target.propertyName)
        )
        val association =
            associationByRoles[link.link.source.propertyName to link.link.target.propertyName]
        LinkFacts(edges, association, computeCheckSelectivity(edges, association))
    }

    /** The body of [linkCheckSelectivity]; see that method for the rationale. */
    private fun computeCheckSelectivity(edges: Int, association: AssociationData?): Double {
        if (edges == 0) return 0.0
        if (association == null) return UNKNOWN_PREDICATE_SELECTIVITY
        val sources = instanceCount(association.source.className)
        val targets = instanceCount(association.target.className)
        if (sources <= 0.0 || targets <= 0.0) return UNKNOWN_PREDICATE_SELECTIVITY
        return minOf(1.0, floorNonZero(edges / (sources * targets)))
    }

    /**
     * Returns the combined selectivity of [expression] when used as a filter predicate.
     *
     * Only the top-level operator is inspected: without value histograms there is nothing to
     * gain from descending further, and the System R constants are already an admission that
     * the estimate is coarse.
     *
     * @param expression The predicate expression.
     * @return A selectivity in `(0, 1]`.
     */
    fun predicateSelectivity(expression: TypedExpression): Double =
        operatorSelectivity((expression as? TypedBinaryExpression)?.operator)

    /**
     * Returns the selectivity of a comparison [operator] applied to a vertex property.
     *
     * @param operator One of `==`, `!=`, `<`, `>`, `<=`, `>=`, or `null`/anything else for
     *        the unknown-predicate default.
     * @return A selectivity in `(0, 1]`.
     */
    fun operatorSelectivity(operator: String?): Double = when (operator) {
        "==" -> EQUALITY_SELECTIVITY
        "!=" -> INEQUALITY_SELECTIVITY
        "<", ">", "<=", ">=" -> RANGE_SELECTIVITY
        else -> UNKNOWN_PREDICATE_SELECTIVITY
    }

    /**
     * Returns the combined selectivity of every constant `==`-style property filter on
     * [instance] that the planner inlines immediately after covering it.
     *
     * Properties whose value expression references other nodes are *deferred* by the planner
     * and only emitted once their dependencies are covered, so they are not counted here;
     * they are accounted for when the covering step that unlocks them is scored.
     *
     * @param instance The instance element whose properties are inspected.
     * @return A selectivity in `(0, 1]`.
     */
    fun constantPropertySelectivity(instance: TypedPatternObjectInstanceElement): Double {
        var selectivity = 1.0
        for (property in instance.objectInstance.properties) {
            if (!isConstantFilter(property)) continue
            selectivity *= operatorSelectivity(property.operator)
        }
        return selectivity
    }

    /**
     * Returns `true` when two branching factors are close enough that the difference carries
     * no information and the planner should fall back to its structural tie-breakers.
     */
    fun approximatelyEqual(a: Double, b: Double): Boolean {
        val difference = kotlin.math.abs(a - b)
        if (difference == 0.0) return true
        return difference <= COMPARISON_EPSILON * maxOf(kotlin.math.abs(a), kotlin.math.abs(b))
    }

    /**
     * Returns `true` when [property] is a comparison against a value that does not depend on
     * any other pattern node, i.e. one the planner can inline as a vertex filter the moment
     * the owning instance is covered.
     */
    private fun isConstantFilter(property: TypedPatternPropertyAssignment): Boolean {
        if (property.operator == "=") return false
        if (isCollectionExpression(property.value)) return false
        return nodeAnalyzer.findReferencedNodes(property.value).isEmpty()
    }

    /** Clamps a strictly positive estimate away from zero; see [MIN_NONZERO_FACTOR]. */
    private fun floorNonZero(value: Double): Double =
        if (value <= 0.0) MIN_NONZERO_FACTOR else value
}

/**
 * Metamodel-derived lookup tables used by [MatchCostModel].
 *
 * These depend only on the metamodel, never on the model, so they are built once per
 * [MetamodelData] and shared by every match. A transformation-heavy search rebuilds a cost
 * model for every match block of every mutation — tens of thousands of times in an optimizer
 * run — and rebuilding these maps each time was measurable.
 *
 * @property subclassesOf Each class name mapped to itself plus all transitive subclasses,
 *           mirroring [com.mdeo.metamodel.MetamodelMetadata.classHierarchy] so that a scan
 *           estimate covers exactly the labels the emitted `hasLabel(...)` step admits.
 * @property associationByRoles Associations keyed by the `(sourceRole, targetRole)` pair that
 *           pattern links carry.
 */
internal class MetamodelIndex private constructor(
    val subclassesOf: Map<String, Set<String>>,
    val associationByRoles: Map<Pair<String?, String?>, AssociationData>
) {
    companion object {
        /**
         * The index per metamodel. A search runs one metamodel for its whole duration and
         * rebuilds a cost model for every match block of every mutation, so this is read
         * hundreds of thousands of times; [MetamodelMemo] answers the repeated lookup with a
         * volatile read and a reference comparison.
         */
        private val memo = MetamodelMemo { metamodelData ->
            MetamodelIndex(
                subclassesOf = buildClassHierarchy(metamodelData),
                associationByRoles = metamodelData.associations.associateBy {
                    it.source.name to it.target.name
                }
            )
        }

        /** Returns the index for [metamodelData], building it on first use. */
        fun of(metamodelData: MetamodelData): MetamodelIndex = memo[metamodelData]

        private fun buildClassHierarchy(metamodelData: MetamodelData): Map<String, Set<String>> {
            val classByName = metamodelData.classes.associateBy { it.name }
            val result = HashMap<String, MutableSet<String>>()
            for (classData in metamodelData.classes) {
                result.getOrPut(classData.name) { mutableSetOf() }.add(classData.name)
            }
            for (classData in metamodelData.classes) {
                var current = classData
                while (current.extends.isNotEmpty()) {
                    val parentName = current.extends.first()
                    result.getOrPut(parentName) { mutableSetOf() }.add(classData.name)
                    current = classByName[parentName] ?: break
                }
            }
            return result
        }
    }
}
