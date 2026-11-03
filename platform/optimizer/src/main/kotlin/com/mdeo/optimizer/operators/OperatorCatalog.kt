package com.mdeo.optimizer.operators

import com.mdeo.modeltransformation.ast.TransformationOperator
import com.mdeo.modeltransformation.ast.TypedAst
import com.mdeo.modeltransformation.ast.TypedTransformationParameter
import com.mdeo.optimizer.config.OperatorRef

/**
 * Builds the operators of a run from the config's [OperatorRef]s and the typed ASTs of their
 * files, and assigns their ids.
 *
 * Operator ids are the index in the operators of one kind sorted by [OperatorRef.path] and then
 * by [OperatorRef.canonicalArguments]. Every node of a federated run builds its operators from the
 * same refs, so every node assigns the same id to the same operator, which keeps
 * [com.mdeo.optimizer.solution.FailedOperatorsMetadata] meaningful across nodes; two entries of
 * one file with different arguments get distinct ids (and separate failure entries). Mutation and
 * crossover operators are numbered separately: crossover ids are their own id space and never
 * appear in [com.mdeo.optimizer.solution.FailedOperatorsMetadata].
 *
 * Generated mutations take part as refs without arguments whose [OperatorRef.path] is the rule
 * name their AST is stored under.
 */
object OperatorCatalog {

    /**
     * [refs] in id order: by path, then by canonical arguments. Equal refs keep their order.
     *
     * @param refs The operators of one kind.
     * @return The sorted refs; the index of a ref is its operator id.
     */
    fun sorted(refs: List<OperatorRef>): List<OperatorRef> =
        refs.sortedWith(compareBy<OperatorRef>({ it.path }, { it.canonicalArguments }))

    /**
     * The mutation operators of [refs], with ids assigned ([sorted]).
     *
     * @param refs The mutation operators of the config (and the generated ones).
     * @param asts The typed ASTs by [OperatorRef.path].
     * @return One operator per ref, in id order.
     * @throws IllegalArgumentException When an AST is missing, an operator has model inputs or a
     *   required input its arguments do not supply, or an argument names no passable input.
     */
    fun mutationOperators(refs: List<OperatorRef>, asts: Map<String, TypedAst>): List<TransformationOperator> =
        sorted(refs).mapIndexed { id, ref ->
            val ast = astOf(ref, asts)
            checkArguments(ref, ast)
            require(ast.signature?.modelInputs.isNullOrEmpty()) {
                "Mutation operator ${ref.label} has model inputs; it is a crossover operator and belongs in 'crossovers'"
            }
            checkRequiredInputsSupplied(ref, ast, "Mutation operator")
            TransformationOperator(id = id, ast = ast, arguments = ref.engineArguments())
        }

    /**
     * The crossover operators of [refs], with ids assigned ([sorted]) in their own id space.
     *
     * @param refs The crossover operators of the config.
     * @param asts The typed ASTs by [OperatorRef.path].
     * @param parents The parents per group ([com.mdeo.optimizer.config.CrossoverParameters.parents]);
     *   every operator must accept that many.
     * @return One operator per ref, in id order.
     * @throws IllegalArgumentException When an AST is missing, a file is not a crossover operator
     *   (see [CrossoverOperator]), does not accept [parents] parents, has a required non-model
     *   input its arguments do not supply, or an argument names no passable input.
     */
    fun crossoverOperators(
        refs: List<OperatorRef>,
        asts: Map<String, TypedAst>,
        parents: Int
    ): List<CrossoverOperator> =
        sorted(refs).mapIndexed { id, ref ->
            val ast = astOf(ref, asts)
            checkArguments(ref, ast)
            checkRequiredInputsSupplied(ref, ast, "Crossover operator")
            val operator = CrossoverOperator(TransformationOperator(id = id, ast = ast, arguments = ref.engineArguments()), ref.label)
            require(operator.accepts(parents)) {
                "Crossover operator ${ref.label} takes ${operator.parentCount} parents, but crossover.parents is $parents"
            }
            operator
        }

    /**
     * The AST of [ref].
     */
    private fun astOf(ref: OperatorRef, asts: Map<String, TypedAst>): TypedAst =
        requireNotNull(asts[ref.path]) { "No transformation loaded for operator ${ref.label}" }

    /**
     * Checks that every argument of [ref] names an input of [ast] that can be set from the config:
     * a scalar, enum or collection-of-those input. Value types are checked by the engine when the
     * operator runs.
     */
    private fun checkArguments(ref: OperatorRef, ast: TypedAst) {
        for (name in ref.arguments.keys) {
            val parameter = ast.signature?.parameters?.firstOrNull { it.name == name && it.isInput }
            require(parameter != null) { "Operator ${ref.label}: '$name' is not an input of the transformation" }
            require(!parameter.isElement && !parameter.elementsAreElements && !parameter.isModel) {
                "Operator ${ref.label}: input '$name' takes model elements or models and cannot be set from the config"
            }
        }
    }

    /**
     * Checks that every required input of [ast] other than its model inputs is supplied by the
     * arguments of [ref]. Model inputs are a crossover's donors and are always passed.
     */
    private fun checkRequiredInputsSupplied(ref: OperatorRef, ast: TypedAst, kind: String) {
        val missing = ast.signature?.parameters.orEmpty()
            .filter { it.isRequiredInput() && !it.isModel && it.name !in ref.arguments }
            .map { it.name }
        require(missing.isEmpty()) {
            "$kind ${ref.label} has required input(s) ${missing.joinToString(", ") { "'$it'" }} that the config does not set"
        }
    }

    /** Whether a run fails when this input is not passed (no default, not optional, not pinning). */
    private fun TypedTransformationParameter.isRequiredInput(): Boolean =
        isInput && defaultValue == null && !optional && !pinsNode
}
