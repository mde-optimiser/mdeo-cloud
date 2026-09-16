package com.mdeo.script.ast

import com.mdeo.expression.ast.TypedCallableBody
import com.mdeo.expression.ast.types.ReturnType
import com.mdeo.expression.ast.types.ReturnTypeSerializer
import kotlinx.serialization.Serializable

/**
 * Root of the TypedAST for plugin contributions.
 *
 * Represents all functions contributed by script contribution plugins, as produced
 * by the `typedAstHandler` in the `service-script` frontend package. All plugin
 * function signatures share a single merged [types] array.
 *
 * @param types Array of all types used across all plugin functions. Indexed by typeIndex
 *              in expressions and parameter/return-type declarations.
 * @param functions All functions contributed by plugins, each potentially carrying
 *                  multiple overloads.
 */
@Serializable
data class TypedPluginAst(
    val types: List<@Serializable(with = ReturnTypeSerializer::class) ReturnType>,
    val functions: List<TypedPluginFunction>
)

/**
 * A single contributed function with all of its overloads.
 *
 * @param name The function name as visible to script code.
 * @param signatures Map from overload identifier to the concrete signature and body.
 *                   Extension-call expressions carry `overload = ""`, so a single-overload
 *                   contribution plugin should use `""` as the key; the compiler also
 *                   falls back to the first available overload when the exact key is absent.
 */
@Serializable
data class TypedPluginFunction(
    val name: String,
    val signatures: Map<String, TypedPluginFunctionSignature>
)

/**
 * One overload of a contributed function.
 *
 * All type indices refer to positions in [TypedPluginAst.types].
 *
 * Exactly one of [body] and [external] is set. A body is run by the execution service itself;
 * an external implementation is answered by the service of the plugin that shipped the
 * contribution, over the `script-functions` session that contribution declares.
 *
 * @param parameters Parameters of this overload in declaration order.
 * @param returnType Index into [TypedPluginAst.types] for the return type.
 * @param body Compiled callable body that implements this overload, when it is implemented here.
 * @param external The operation answering this overload, when it is implemented elsewhere.
 */
@Serializable
data class TypedPluginFunctionSignature(
    val parameters: List<TypedParameter>,
    val returnType: Int,
    val body: TypedCallableBody? = null,
    val external: ExternalImplementation? = null
) {
    init {
        require((body == null) != (external == null)) {
            "A contributed signature needs exactly one of a body and an external implementation"
        }
    }
}

/**
 * An implementation that lives outside the platform.
 *
 * The compiler emits a stub with the declared descriptor for such a signature: the stub boxes
 * its arguments, hands them to the [dispatcher][com.mdeo.script.runtime.ExternalCallDispatcher]
 * on the script context, and unboxes whatever comes back. Everything about how the call actually
 * reaches the plugin — the session, the encoding, the copy-restore of mutable arguments — lives
 * behind that dispatcher and not in the generated code.
 *
 * @param kind Discriminator, always `external`.
 * @param operation Names the operation within the plugin's own protocol. The platform passes it
 *        through and ascribes it no meaning.
 * @param model Whether the operation needs the model, and in what form. `none` sends none;
 *        `readonly` sends the model the call works on, readonly, once per model.
 * @param contribution Id of the contribution that shipped the function, the `contrib:<id>`
 *        target the call is answered on.
 * @param session Name of that contribution's `script-functions` session.
 */
@Serializable
data class ExternalImplementation(
    val kind: String = KIND,
    val operation: String,
    val model: String = MODEL_NONE,
    val contribution: String = "",
    val session: String? = null
) {
    companion object {
        /**
         * Discriminator distinguishing an external implementation from a typed AST body.
         */
        const val KIND = "external"

        /**
         * [model] value for an operation that needs no model.
         */
        const val MODEL_NONE = "none"

        /**
         * [model] value for an operation that reads the model the script runs on.
         */
        const val MODEL_READONLY = "readonly"
    }
}
