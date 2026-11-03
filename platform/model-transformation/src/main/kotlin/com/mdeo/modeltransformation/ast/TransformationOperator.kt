package com.mdeo.modeltransformation.ast

/**
 * A compiled transformation operator ready for use in the optimizer.
 *
 * Wraps a [TypedAst] together with a stable numerical [id] that uniquely
 * identifies this operator within a particular optimization run.  The id is
 * assigned **before** the operator list is handed to the
 * [com.mdeo.optimizer.operators.MutationStrategyFactory] by sorting all
 * operators by path and then by their arguments and using their zero-based index
 * ([com.mdeo.optimizer.operators.OperatorCatalog]).  This
 * guarantees that every node in a federated setup assigns the same id to the
 * same operator regardless of insertion order, keeping
 * [com.mdeo.optimizer.solution.FailedOperatorsMetadata] consistent across the
 * cluster.
 *
 * Two operators may share one [ast] and differ in their [arguments] (one `.mt` file used twice in
 * a configuration with different `with { … }` values): they are separate operators with separate
 * ids, sorted by path and then by their arguments.
 *
 * @param id Stable numerical id (index in the operators sorted by path and arguments).
 * @param ast The compiled typed AST for this transformation.
 * @param arguments Values of the transformation's scalar, enum and collection inputs, passed to
 *   every run of the operator ([com.mdeo.modeltransformation.runtime.TransformationEngine.execute]);
 *   inputs not given take their defaults.
 */
data class TransformationOperator(
    val id: Int,
    val ast: TypedAst,
    val arguments: Map<String, Any?> = emptyMap()
)
