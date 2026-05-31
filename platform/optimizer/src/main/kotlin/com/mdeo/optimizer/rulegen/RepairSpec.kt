package com.mdeo.optimizer.rulegen

/**
 * A concrete, parameterised repair action produced by [SpecsGenerator].
 *
 * @param className  The metamodel class the operator acts on (source node for edge operations).
 * @param edgeName   The reference name involved, or `null` for node-only operations.
 * @param type       The kind of mutation this spec describes.
 */
data class RepairSpec(
    val className: String,
    val edgeName: String?,
    val type: RepairSpecType
)
