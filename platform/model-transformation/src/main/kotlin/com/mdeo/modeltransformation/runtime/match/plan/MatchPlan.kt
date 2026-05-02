package com.mdeo.modeltransformation.runtime.match.plan

import com.mdeo.modeltransformation.ast.patterns.TypedPatternLinkElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternPropertyAssignment
import com.mdeo.modeltransformation.ast.patterns.TypedPatternVariableElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternWhereClauseElement
import com.mdeo.modeltransformation.runtime.match.Island

/**
 * An abstract plan for executing a single match operation.
 *
 * The plan is fully imperative — no `match()` step is used. It consists of two layers:
 *
 * 1. **[baseSteps]** — An imperative sequence of vertex scans, edge walks, property
 *    constraints, island constraints, variable bindings, and where filters. These steps
 *    form the complete traversal and prune traversers as early as possible.
 *
 * 2. **[postMatchFilters]** — Filters applied after all steps complete. Used for
 *    injective constraints and cross-node where clauses that reference multiple instances.
 *
 * No Gremlin traversal objects are referenced; the plan is purely structural data.
 */
internal data class MatchPlan(
    val baseSteps: List<BaseStep>,
    val postMatchFilters: List<PostMatchFilter>
)

/**
 * A single step in the imperative traversal sequence.
 *
 * Steps are applied in order to build `inject(emptyMap).as("_").<step1>.<step2>...`.
 * The traversal builder translates each step to concrete Gremlin instructions.
 */
internal sealed class BaseStep {

    /**
     * Scan for a vertex to start a new connected component.
     *
     * Translated to `V(vertexId).as(stepLabel)` or `V().hasLabel(className).as(stepLabel)`.
     */
    data class VertexScan(
        val instanceName: String,
        val className: String?,
        val vertexId: Any?
    ) : BaseStep()

    /**
     * Walk an edge from the current position (or from a labelled vertex via select).
     *
     * Translated to `[select(from).]out/in(edgeLabel).hasLabel(targetClass).as(targetLabel)`.
     */
    data class EdgeWalk(
        val link: TypedPatternLinkElement,
        val isReversed: Boolean,
        val fromInstanceName: String,
        val toInstanceName: String,
        val toClassName: String?,
        val toVertexId: Any?,
        val needsSelect: Boolean
    ) : BaseStep()

    /**
     * An inline property constraint applied directly on the current traverser.
     *
     * Constant values are translated to `.has(key, value)`.
     * Non-constant expressions are translated to `.filter(equalityExpr.is(true))`.
     */
    data class InlinePropertyConstraint(
        val instanceName: String,
        val className: String?,
        val property: TypedPatternPropertyAssignment,
        val isConstant: Boolean
    ) : BaseStep()

    /**
     * An island constraint (forbid/require) applied as an imperative filter.
     *
     * When [needsSelect] is false (inline), the traverser is already at the anchor and
     * the chain is applied directly: `.not(chain)` (forbid) or `.where(chain)` (require).
     *
     * When [needsSelect] is true (deferred), a `select()` navigates to the anchor first:
     * `.not(select(anchor).where(chain))` or `.where(select(anchor).where(chain))`.
     *
     * [injectiveConstraints] maps each island node name to a list of step labels
     * (outer traversal labels for main-pattern nodes, or `__inline_<name>` labels for
     * earlier island nodes in BFS order) that the island node must be distinct from.
     * After each island node is reached in the chain, a `.where(P.neq(label))` is
     * emitted for every label in the list.
     */
    data class InlineIslandConstraint(
        val island: Island,
        val anchorName: String,
        val orderedLinks: List<Pair<TypedPatternLinkElement, Boolean>>,
        val nodesNeedingBacktrackLabel: Set<String>,
        val isNegative: Boolean,
        val needsSelect: Boolean = false,
        val injectiveConstraints: Map<String, List<String>> = emptyMap()
    ) : BaseStep()

    /**
     * An orphan-link constraint (a forbid/require link between two instances).
     *
     * Translated to `.where(not(as(source).out(edge).as(target)))` (forbid) or the positive form.
     */
    data class InlineOrphanLinkConstraint(
        val sourceName: String,
        val targetName: String,
        val edgeLabel: String,
        val isNegative: Boolean
    ) : BaseStep()

    /**
     * Bind a pattern variable's computed value and label it.
     *
     * Translated to `.map(compiledExpression).as(variableLabel)`.
     */
    data class VariableBinding(
        val variable: TypedPatternVariableElement,
        val variableLabel: String
    ) : BaseStep()

    /**
     * A property constraint that was deferred because it references pattern variables
     * or instances not yet covered at inline time.
     *
     * Uses `select()` to navigate to the instance and `where()` to avoid changing
     * the traverser position.
     */
    data class DeferredPropertyConstraint(
        val instanceName: String,
        val className: String?,
        val property: TypedPatternPropertyAssignment
    ) : BaseStep()

    /**
     * A where-clause filter applied after all instances and variables are bound.
     *
     * Translated to `.where(compiledExpression.is(true))`.
     */
    data class WhereFilter(
        val whereClause: TypedPatternWhereClauseElement
    ) : BaseStep()

    /**
     * A disconnected island filter (no links connecting it to the matchable graph).
     *
     * Translated to `.where(V().hasLabel(cls)...count().is(predicate))`.
     *
     * [injectiveConstraints] maps each island instance name to a list of outer-traversal
     * step labels (for already-matched main-pattern nodes of the same class) that the
     * counted vertex must be distinct from. Applied as `.where(P.neq(label))` inside the
     * count sub-traversal so that already-matched vertices are not counted.
     */
    data class DisconnectedIslandFilter(
        val island: Island,
        val isNegative: Boolean,
        val injectiveConstraints: Map<String, List<String>> = emptyMap()
    ) : BaseStep()
}

/**
 * A filter applied after all base steps complete.
 */
internal sealed class PostMatchFilter {

    /**
     * Injective constraint: two matched instances must bind to distinct vertices.
     *
     * Translated to `.where(labelA, P.neq(labelB))`.
     */
    data class InjectiveConstraint(
        val instanceNameA: String,
        val instanceNameB: String
    ) : PostMatchFilter()

    /**
     * A cross-node where-clause that references multiple matchable instances.
     *
     * Applied as `.where(compiledExpression.is(true))`.
     */
    data class CrossNodeWhereClause(
        val whereClause: TypedPatternWhereClauseElement
    ) : PostMatchFilter()
}
