package com.mdeo.optimizer.rulegen

import kotlinx.serialization.Serializable

/**
 * User-facing specification describing which mutation operators to generate for a metamodel class
 * and (optionally) one of its references.
 *
 * @param node   The metamodel class name to generate operators for.
 * @param edge   Optional reference name to restrict generation to a specific edge.
 * @param action Which category of operators to generate.
 */
@Serializable
data class MutationRuleSpec(
    val node: String,
    val edge: String? = null,
    val action: MutationAction
)

/**
 * Selects the category of mutation operators that [SpecsGenerator] should produce for a
 * [MutationRuleSpec].
 */
@Serializable
enum class MutationAction {
    /** Generate all operator categories (CREATE, DELETE, ADD, REMOVE). */
    ALL,
    /** Generate node-creation operators only. */
    CREATE,
    /** Generate node-deletion operators only. */
    DELETE,
    /** Generate edge-addition operators only. */
    ADD,
    /** Generate edge-removal operators only. */
    REMOVE
}
