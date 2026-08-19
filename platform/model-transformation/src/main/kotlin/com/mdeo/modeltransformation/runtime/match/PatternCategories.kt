package com.mdeo.modeltransformation.runtime.match

import com.mdeo.modeltransformation.ast.patterns.TypedPattern
import com.mdeo.modeltransformation.ast.patterns.TypedPatternApplicationConditionElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstanceElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternLinkElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternVariableElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternVariableReassignmentElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternWhereClauseElement

/**
 * Categorizes pattern elements by their role in pattern matching and modifications.
 *
 * This data class separates pattern elements into distinct categories based on their
 * modifiers (create, delete) and types (instances, links, variables, where clauses,
 * application conditions). This categorization enables the unified executor to process
 * different element types efficiently in the appropriate phases of the execution pipeline.
 *
 * ## Categories
 *
 * ### Instances
 * - **matchableInstances**: Object instances without modifiers that must be found in the graph
 * - **createInstances**: Object instances with "create" modifier to be inserted
 * - **deleteInstances**: Object instances with "delete" modifier to be removed
 *
 * ### Links
 * - **matchableLinks**: Links without modifiers that must exist in the graph
 * - **createLinks**: Links with "create" modifier to be inserted
 * - **deleteLinks**: Links with "delete" modifier to be removed
 *
 * ### Other Elements
 * - **variables**: Variable definitions that compute and bind values
 * - **whereClauses**: Boolean expressions that constrain the match
 * - **conditions**: `forbid` / `require` blocks, each carrying a graph of its own
 *
 * Negative and positive application conditions are *not* derived from element modifiers:
 * every condition is an explicit block, and each block is matched as a whole.
 *
 * @property matchableInstances Object instances to match in the graph
 * @property matchableLinks Links to match in the graph
 * @property createInstances Object instances to create during modifications
 * @property deleteInstances Object instances to delete during modifications
 * @property createLinks Links to create during modifications
 * @property deleteLinks Links to delete during modifications
 * @property variables Variable definitions for computed values
 * @property variableReassignments Reassignments of variables declared in enclosing scopes
 * @property whereClauses Boolean expressions constraining the match
 * @property conditions Application condition blocks, in declaration order
 */
internal data class PatternCategories(
    val matchableInstances: List<TypedPatternObjectInstanceElement>,
    val matchableLinks: List<TypedPatternLinkElement>,
    val createInstances: List<TypedPatternObjectInstanceElement>,
    val deleteInstances: List<TypedPatternObjectInstanceElement>,
    val createLinks: List<TypedPatternLinkElement>,
    val deleteLinks: List<TypedPatternLinkElement>,
    val variables: List<TypedPatternVariableElement>,
    val variableReassignments: List<TypedPatternVariableReassignmentElement> = emptyList(),
    val whereClauses: List<TypedPatternWhereClauseElement>,
    val conditions: List<TypedPatternApplicationConditionElement> = emptyList()
) {
    /**
     * All instance names for final select() step.
     *
     * Includes all instances that will be available in the result binding:
     * - Matched instances (found in the graph)
     * - Created instances (inserted during execution)
     * - Deleted instances (available until drop but included in result)
     *
     * Nodes of an application condition are never included: they are matched inside the
     * condition's own traversal and are not bound by the match.
     *
     * @return List of all instance names that should be selected in the final result
     */
    val allInstanceNames: List<String>
        get() = matchableInstances.map { it.objectInstance.name } +
                createInstances.map { it.objectInstance.name } +
                deleteInstances.map { it.objectInstance.name }

    companion object {
        /**
         * Creates a PatternCategories instance from a TypedPattern.
         *
         * Analyzes all pattern elements and categorizes them based on their type
         * and modifier. This method is the entry point for pattern analysis.
         *
         * @param pattern The typed pattern to categorize
         * @return A PatternCategories instance with all elements sorted into appropriate categories
         */
        fun from(pattern: TypedPattern): PatternCategories {
            val matchableInstances = mutableListOf<TypedPatternObjectInstanceElement>()
            val matchableLinks = mutableListOf<TypedPatternLinkElement>()
            val createInstances = mutableListOf<TypedPatternObjectInstanceElement>()
            val deleteInstances = mutableListOf<TypedPatternObjectInstanceElement>()
            val createLinks = mutableListOf<TypedPatternLinkElement>()
            val deleteLinks = mutableListOf<TypedPatternLinkElement>()
            val variables = mutableListOf<TypedPatternVariableElement>()
            val variableReassignments = mutableListOf<TypedPatternVariableReassignmentElement>()
            val whereClauses = mutableListOf<TypedPatternWhereClauseElement>()
            val conditions = mutableListOf<TypedPatternApplicationConditionElement>()

            for (element in pattern.elements) {
                when (element) {
                    is TypedPatternObjectInstanceElement -> categorizeInstance(
                        element, matchableInstances, createInstances, deleteInstances
                    )
                    is TypedPatternLinkElement -> categorizeLink(
                        element, matchableLinks, createLinks, deleteLinks
                    )
                    is TypedPatternVariableElement -> variables.add(element)
                    is TypedPatternVariableReassignmentElement -> variableReassignments.add(element)
                    is TypedPatternWhereClauseElement -> whereClauses.add(element)
                    is TypedPatternApplicationConditionElement -> conditions.add(element)
                }
            }

            return PatternCategories(
                matchableInstances, matchableLinks, createInstances, deleteInstances,
                createLinks, deleteLinks, variables, variableReassignments, whereClauses,
                conditions
            )
        }

        /**
         * Categorizes an object instance element based on its modifier.
         *
         * Routes the instance to the appropriate category list:
         * - "create" → create list
         * - "delete" → delete list
         * - no modifier → matchable list
         *
         * The removed "forbid" / "require" modifiers are rejected with an explanatory error.
         * Rejecting them beats silently matching the element: a stale AST would otherwise
         * change meaning.
         *
         * @param element The instance element to categorize
         * @param matchable Target list for matchable instances
         * @param create Target list for create instances
         * @param delete Target list for delete instances
         */
        private fun categorizeInstance(
            element: TypedPatternObjectInstanceElement,
            matchable: MutableList<TypedPatternObjectInstanceElement>,
            create: MutableList<TypedPatternObjectInstanceElement>,
            delete: MutableList<TypedPatternObjectInstanceElement>
        ) {
            when (val modifier = element.objectInstance.modifier) {
                "create" -> create.add(element)
                "delete" -> delete.add(element)
                "forbid", "require" -> throw IllegalArgumentException(
                    "Instance '${element.objectInstance.name}' uses the removed '$modifier' modifier. " +
                    "Application conditions are expressed as '$modifier { ... }' blocks."
                )
                else -> matchable.add(element)
            }
        }

        /**
         * Categorizes a link element based on its modifier.
         *
         * Routes the link to the appropriate category list:
         * - "create" → create list
         * - "delete" → delete list
         * - no modifier → matchable list
         *
         * As for instances, the removed "forbid" / "require" modifiers are rejected with an
         * explanatory error.
         *
         * @param element The link element to categorize
         * @param matchable Target list for matchable links
         * @param create Target list for create links
         * @param delete Target list for delete links
         */
        private fun categorizeLink(
            element: TypedPatternLinkElement,
            matchable: MutableList<TypedPatternLinkElement>,
            create: MutableList<TypedPatternLinkElement>,
            delete: MutableList<TypedPatternLinkElement>
        ) {
            when (val modifier = element.link.modifier) {
                "create" -> create.add(element)
                "delete" -> delete.add(element)
                "forbid", "require" -> throw IllegalArgumentException(
                    "Link '${element.link.source.objectName} -- ${element.link.target.objectName}' uses the " +
                    "removed '$modifier' modifier. Application conditions are expressed as '$modifier { ... }' blocks."
                )
                else -> matchable.add(element)
            }
        }
    }
}
