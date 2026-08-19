package com.mdeo.modeltransformation.runtime.match

import com.mdeo.modeltransformation.ast.patterns.TypedPatternApplicationConditionElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLinkElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstanceElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternWhereClauseElement

/**
 * A single application condition — the graph of one `forbid` or `require` block.
 *
 * A block is matched as a whole and independently of every other block: a negative block
 * rejects the match as soon as its complete graph is found, a positive block requires its
 * complete graph to be found. This is what distinguishes two separate `forbid` blocks
 * (either of them rejects the match) from one block holding the same elements (only their
 * conjunction rejects it) — a distinction that the previous element-level `forbid` modifier
 * could not express, because the elements of a pattern were regrouped into connected
 * components after the fact.
 *
 * A block therefore no longer has to be connected: it may consist of several components,
 * all of which must match simultaneously for the condition to hold.
 *
 * @property name Optional block name, used in diagnostics and in the graphical syntax.
 * @property isNegative `true` for a `forbid` block, `false` for a `require` block.
 * @property instances The nodes that belong to this condition graph alone (they carry a
 *           class name and are never bound by the enclosing match).
 * @property references Instances without a class name: they refer to nodes of the enclosing
 *           pattern and only contribute the property constraints listed on them.
 * @property links The links of this condition graph. A link may connect two condition
 *           nodes, a condition node and an enclosing (anchor) node, or two anchor nodes.
 * @property whereClauses Boolean expressions that further constrain this condition graph.
 *           They may read the condition's own nodes as well as everything the enclosing
 *           match has bound, and they are part of the condition: a `forbid` block only
 *           rejects the match when its graph is found *and* its where clauses hold.
 */
data class ApplicationConditionBlock(
    val name: String?,
    val isNegative: Boolean,
    val instances: List<TypedPatternObjectInstanceElement>,
    val references: List<TypedPatternObjectInstanceElement>,
    val links: List<TypedPatternLinkElement>,
    val whereClauses: List<TypedPatternWhereClauseElement> = emptyList()
) {
    /**
     * Names of the nodes that belong to this condition graph alone.
     */
    val instanceNames: Set<String> = instances.map { it.objectInstance.name }.toSet()

    /**
     * A human readable identifier for diagnostics, falling back to the block kind when the
     * block is unnamed.
     */
    val label: String
        get() = name ?: if (isNegative) "forbid" else "require"

    companion object {
        /**
         * Builds the condition graph described by a typed application condition element.
         *
         * Instances that carry a class name become condition-exclusive nodes, instances
         * without one are references to nodes of the enclosing pattern. Where clauses are
         * kept apart from the graph: they constrain it, but they contribute no node to walk.
         *
         * @param element The typed application-condition element to convert.
         * @return The condition graph described by [element].
         * @throws IllegalArgumentException When the block contains an element kind that is
         *         not part of a condition graph.
         */
        fun from(element: TypedPatternApplicationConditionElement): ApplicationConditionBlock {
            val condition = element.condition
            val instances = mutableListOf<TypedPatternObjectInstanceElement>()
            val references = mutableListOf<TypedPatternObjectInstanceElement>()
            val links = mutableListOf<TypedPatternLinkElement>()
            val whereClauses = mutableListOf<TypedPatternWhereClauseElement>()

            for (conditionElement in condition.elements) {
                when (conditionElement) {
                    is TypedPatternObjectInstanceElement ->
                        if (conditionElement.objectInstance.className != null) instances.add(conditionElement)
                        else references.add(conditionElement)
                    is TypedPatternLinkElement -> links.add(conditionElement)
                    is TypedPatternWhereClauseElement -> whereClauses.add(conditionElement)
                    else -> throw IllegalArgumentException(
                        "Unsupported element kind '${conditionElement.kind}' in application condition block"
                    )
                }
            }

            return ApplicationConditionBlock(
                name = condition.name,
                isNegative = condition.negative,
                instances = instances,
                references = references,
                links = links,
                whereClauses = whereClauses
            )
        }
    }
}
