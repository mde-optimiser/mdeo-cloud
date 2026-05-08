package com.mdeo.modeltransformation.ast

/**
 * A compiled transformation operator ready for use in the optimizer.
 *
 * Wraps a [TypedAst] together with a stable numerical [id] that uniquely
 * identifies this operator within a particular optimization run.  The id is
 * assigned **before** the operator list is handed to the
 * [com.mdeo.optimizer.operators.MutationStrategyFactory] by sorting all
 * operator paths alphabetically and using their zero-based index.  This
 * guarantees that every node in a federated setup assigns the same id to the
 * same operator regardless of insertion order, keeping
 * [com.mdeo.optimizer.solution.FailedOperatorsMetadata] consistent across the
 * cluster.
 *
 * @param id Stable numerical id (sorted alphabetical index over all operators).
 * @param ast The compiled typed AST for this transformation.
 */
data class TransformationOperator(
    val id: Int,
    val ast: TypedAst
)
