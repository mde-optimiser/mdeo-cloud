package com.mdeo.optimizer.operators

import com.mdeo.modeltransformation.ast.TransformationOperator
import com.mdeo.modeltransformation.ast.TypedTransformationParameter
import com.mdeo.modeltransformation.graph.ModelGraph
import com.mdeo.modeltransformation.runtime.ModelArgument

/**
 * A crossover operator: a transformation whose required inputs are model inputs only, at least
 * one. It runs on a copy of one parent (the primary; its implicit model becomes the child) and
 * reads the other parents of the group (the donors) through its model inputs.
 *
 * Its parent count is `1 +` the number of its `in x: model` inputs, or any count when it is
 * variadic: exactly one `in xs: List<model>` input and no single model input. Mixing both forms,
 * or several list inputs, is not a crossover operator.
 *
 * @param operator The transformation, its id (in the crossover id space) and its arguments.
 * @param label The operator for people, e.g. `take-hosting.mt (minShared = 2)`.
 * @throws IllegalArgumentException When the transformation is not a crossover operator.
 */
class CrossoverOperator(
    val operator: TransformationOperator,
    val label: String = "crossover ${operator.id}"
) {
    /** The model inputs, in declaration order: the donors are bound to them in this order. */
    val modelInputs: List<TypedTransformationParameter> = operator.ast.signature?.modelInputs.orEmpty()

    /** Whether the operator takes any number of donors through one `List<model>` input. */
    val isVariadic: Boolean = modelInputs.size == 1 && modelInputs.single().isCollection

    /** The parents the operator takes, or `null` when it is [isVariadic]. */
    val parentCount: Int? = if (isVariadic) null else 1 + modelInputs.size

    /** The operator id, in the crossover id space. */
    val id: Int get() = operator.id

    init {
        require(modelInputs.isNotEmpty()) { "$label has no model input; it is not a crossover operator" }
        require(isVariadic || modelInputs.none { it.isCollection }) {
            "$label mixes model inputs and List<model> inputs (or has several List<model> inputs); " +
                "a crossover operator has either single model inputs or one List<model> input"
        }
    }

    /**
     * Whether the operator takes a group of [parents] parents (one primary and `parents - 1` donors).
     */
    fun accepts(parents: Int): Boolean = parents >= 2 && (isVariadic || parentCount == parents)

    /**
     * The model arguments of one run: the donors bound to the model inputs in declaration order,
     * or all of them, in order, to the list input of a variadic operator.
     *
     * @param donors The donor models, in the group's cyclic order after the primary.
     * @return The models by input name.
     */
    fun modelArguments(donors: List<ModelGraph>): Map<String, ModelArgument> {
        require(accepts(donors.size + 1)) { "$label does not take ${donors.size + 1} parents" }
        return if (isVariadic) {
            mapOf(modelInputs.single().name to ModelArgument.of(donors))
        } else {
            modelInputs.zip(donors).associate { (input, donor) -> input.name to ModelArgument.of(donor) }
        }
    }
}
