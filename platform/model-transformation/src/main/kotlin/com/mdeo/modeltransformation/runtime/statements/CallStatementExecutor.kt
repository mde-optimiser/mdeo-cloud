package com.mdeo.modeltransformation.runtime.statements

import com.mdeo.modeltransformation.ast.statements.TypedCallStatement
import com.mdeo.modeltransformation.ast.statements.TypedTransformationStatement
import com.mdeo.modeltransformation.compiler.VariableBinding
import com.mdeo.modeltransformation.runtime.StatementExecutor
import com.mdeo.modeltransformation.runtime.TransformationEngine
import com.mdeo.modeltransformation.runtime.TransformationExecutionContext
import com.mdeo.modeltransformation.runtime.TransformationExecutionResult

/**
 * Executor for [TypedCallStatement]: runs another transformation on the same model graph.
 *
 * 1. The arguments are evaluated in the caller's context; an argument naming a bound object
 *    passes that object, and one naming a model value (a model input or a loop variable over a
 *    list of them) passes its [com.mdeo.modeltransformation.runtime.ModelSlot] — the model the
 *    caller's run already loaded, by reference.
 * 2. The callee runs as an engine of its own (its own types and root scope, the caller's graph
 *    and type registry) with its inputs bound, so its patterns treat element inputs as bound
 *    nodes.
 * 3. On success, which includes a `stop` in the callee, every bound output is set in the caller's
 *    current scope, exactly as a match sets its objects, and the callee's changes are part of
 *    the call's result. A callee that fails fails the call, which fails the caller as a match
 *    that does not match does; `kill` in the callee ends the caller too.
 *
 * Calls cannot recurse (the language rejects import cycles); [MAX_CALL_DEPTH] guards against a
 * cycle in a hand-made typed AST.
 */
class CallStatementExecutor : StatementExecutor {

    companion object {
        /** The deepest call chain the engine runs. */
        const val MAX_CALL_DEPTH = 64
    }

    override fun canExecute(statement: TypedTransformationStatement): Boolean {
        return statement is TypedCallStatement
    }

    override fun execute(
        statement: TypedTransformationStatement,
        context: TransformationExecutionContext,
        engine: TransformationEngine
    ): TransformationExecutionResult = call(statement as TypedCallStatement, context, engine)

    /**
     * Runs a call: evaluates the arguments in [context], runs the callee and, on success, binds
     * the outputs in [context]'s current scope.
     *
     * @param call The call.
     * @param context The caller's context.
     * @param engine The caller's engine.
     * @return The callee's result: Success (also after `stop`), Failure, or Stopped for `kill`.
     */
    fun call(
        call: TypedCallStatement,
        context: TransformationExecutionContext,
        engine: TransformationEngine
    ): TransformationExecutionResult {
        val location = "call ${call.name}"

        if (engine.callDepth >= MAX_CALL_DEPTH) {
            return failure("Calls nest deeper than $MAX_CALL_DEPTH (is there a cycle?)", location)
        }
        val callee = engine.calleeEngine(call.callee)
            ?: return failure("The typed AST of '${call.name}' (${call.callee}) was not bundled", location)

        val evaluator = ExpressionValueEvaluator(engine)
        val inputs = call.arguments.associate { argument ->
            argument.name to evaluator.evaluate(argument.value, context)
        }

        val outcome = callee.executeCall(inputs)
        return when (val result = outcome.result) {
            is TransformationExecutionResult.Success -> {
                bindOutputs(call, outcome.outputs, context)
                result
            }
            is TransformationExecutionResult.Failure ->
                result.copy(reason = "'${call.name}' failed: ${result.reason}", failedAt = location)
            is TransformationExecutionResult.Stopped -> result
        }
    }

    /**
     * Binds the outputs the call names in the caller's current scope.
     */
    private fun bindOutputs(
        call: TypedCallStatement,
        outputs: Map<String, VariableBinding>,
        context: TransformationExecutionContext
    ) {
        val scope = context.variableScope
        for (output in call.outputs) {
            scope.setBinding(output.name, outputs[output.parameter] ?: VariableBinding.ValueBinding(null))
        }
    }

    private fun failure(reason: String, location: String) = TransformationExecutionResult.Failure(
        reason = reason,
        failedAt = location,
        isDeterministic = false,
        changesWereMade = false
    )
}
