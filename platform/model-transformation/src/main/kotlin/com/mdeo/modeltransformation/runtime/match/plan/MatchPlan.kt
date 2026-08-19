package com.mdeo.modeltransformation.runtime.match.plan

import com.mdeo.modeltransformation.ast.patterns.TypedPatternLinkElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternPropertyAssignment
import com.mdeo.modeltransformation.ast.patterns.TypedPatternVariableElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternWhereClauseElement

/**
 * An abstract plan for executing a single match operation.
 *
 * The plan is fully imperative — no `match()` step is used. It consists of a single
 * ordered list:
 *
 * **[baseSteps]** — An imperative sequence of vertex scans, edge walks, property
 * constraints, application conditions, variable bindings, where filters, and injective
 * constraints. All constraints are emitted as early as possible and in-lined into
 * the traversal.
 *
 * No Gremlin traversal objects are referenced; the plan is purely structural data.
 */
internal data class MatchPlan(
    val baseSteps: List<BaseStep>
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
     *
     * [conditionNodes] is empty for a constraint of the main pattern. For a constraint
     * declared inside a `forbid` / `require` block it holds the names of the block's own
     * nodes that the compared expression reads, which have to be labelled inside the
     * condition chain before the expression can select them.
     */
    data class InlinePropertyConstraint(
        val instanceName: String,
        val className: String?,
        val property: TypedPatternPropertyAssignment,
        val isConstant: Boolean,
        val conditionNodes: Set<String> = emptySet()
    ) : BaseStep()

    /**
     * Repositions the traverser onto an already-bound node inside a condition chain.
     *
     * Used when the graph of an application condition falls apart into several components:
     * after one component has been walked, the chain jumps to the anchor of the next one
     * instead of continuing from wherever the previous walk ended.
     *
     * Translated to `.select(stepLabel(instanceName))`.
     */
    data class SelectNode(
        val instanceName: String
    ) : BaseStep()

    /**
     * Verifies the current traverser equals the already-bound outer instance [instanceName].
     *
     * Used inside [ApplicationCondition.innerSteps] when an edge walk inside the condition
     * leads to a main-pattern node (outer anchor) that was matched earlier. Ensures the edge
     * endpoint is exactly the already-matched vertex, not any vertex of that class.
     *
     * Translated to `.where(P.eq(stepLabel(instanceName)))`.
     */
    data class EqualityFilter(
        val instanceName: String
    ) : BaseStep()

    /**
     * A positive or negative application condition (PAC/NAC).
     *
     * One step represents one `forbid` / `require` block. All PAC/NACs — whether connected
     * (with a main-pattern anchor) or disconnected (no anchor), and whether their graph is
     * connected or falls into several components — are represented uniformly through this
     * single step type: the whole block is one traversal, so it is satisfied only when
     * *all* of its components match simultaneously.
     *
     * When [anchorName] is non-null the sub-traversal starts at that anchor node.
     * When [anchorName] is null the first [innerStep][innerSteps] must be a [VertexScan]
     * that initiates a fresh `V().hasLabel(...)` scan.
     *
     * When [needsSelect] is true the outer traverser is not already positioned at the
     * anchor and the chain is built as `select(anchor).where(innerChain)` instead of
     * applying [innerSteps] directly.
     *
     * [innerSteps] are regular [BaseStep] instances ([VertexScan], [SelectNode], [EdgeWalk],
     * [InlinePropertyConstraint], [EqualityFilter]) that encode the condition pattern.
     * This allows reusing the same step translation logic as for the main pattern. A
     * [VertexScan] or [SelectNode] in a non-initial position starts the next component of
     * the condition graph.
     *
     * [injectiveConstraints] maps the step label of each condition-only node to the list
     * of outer (or earlier inner) step labels that the node must be distinct from.
     * These are emitted as `.where(P.neq(label))` immediately after each node is reached
     * in the chain.
     *
     * Application conditions are sorted by estimated evaluation cost: cheaper conditions
     * (anchored, fewer steps) are placed before more expensive ones (unanchored, many steps).
     *
     * [localNames] are the names the block itself declares. They name a scope of their own:
     * the block's nodes are bound inside its sub-traversal and nowhere else, so the chain is
     * compiled against a child scope holding exactly these names. It is empty for the
     * synthetic conditions that check the existence of a link, which declare nothing.
     */
    data class ApplicationCondition(
        val isNegative: Boolean,
        val anchorName: String?,
        val needsSelect: Boolean,
        val innerSteps: List<BaseStep>,
        val injectiveConstraints: Map<String, List<String>> = emptyMap(),
        val name: String? = null,
        val localNames: Set<String> = emptySet()
    ) : BaseStep()

    /**
     * Bind a pattern variable's computed value and label it.
     *
     * Translated to `.map(compiledExpression).as(variableLabel)`.
     *
     * When [isReassignment] is true this step reassigns a variable declared in an enclosing
     * scope rather than declaring a new one. The value expression is compiled against the
     * variable's incoming (old) binding, and — immediately after the `.as(variableLabel)` is
     * emitted — the declaring scope's binding is flipped to a label binding so that later
     * accesses within the same match block observe the new value.
     */
    data class VariableBinding(
        val variable: TypedPatternVariableElement,
        val variableLabel: String,
        val isReassignment: Boolean = false
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
     * A where-clause filter applied after all instances and variables it reads are bound.
     *
     * Translated to `.where(compiledExpression.is(true))`.
     *
     * [conditionNodes] is empty for a where clause of the main pattern. For a clause
     * declared inside a `forbid` / `require` block it holds the names of the block's own
     * nodes that the expression reads: those nodes live only inside the condition chain,
     * so they have to be labelled there before the filter can select them.
     */
    data class WhereFilter(
        val whereClause: TypedPatternWhereClauseElement,
        val conditionNodes: Set<String> = emptySet()
    ) : BaseStep()

    /**
     * Injective constraint: two matched instances must bind to distinct vertices.
     *
     * Emitted as early as possible — immediately after both instances are covered —
     * by `tryInlineInjectiveConstraints()`. Any remaining pairs are appended at the
     * end by `addInjectiveConstraints()`.
     * Translated to `.where(labelA, P.neq(labelB))`.
     */
    data class InjectiveConstraint(
        val instanceNameA: String,
        val instanceNameB: String
    ) : BaseStep()
}
