package com.mdeo.script.external

import com.mdeo.expression.ast.expressions.TypedExpression
import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.expression.ast.types.ReturnType
import com.mdeo.script.ast.ExternalImplementation
import com.mdeo.script.ast.TypedParameter
import com.mdeo.script.ast.TypedPluginAst
import com.mdeo.script.ast.TypedPluginClass
import com.mdeo.script.ast.TypedPluginFunction
import com.mdeo.script.ast.TypedPluginFunctionSignature

/**
 * Builds the typed AST of one contribution, as the script service emits it for a contribution
 * whose functions are answered by its `functions` session.
 *
 * @param contribution The contribution id.
 */
class PluginAstBuilder(private val contribution: String) {
    private val types = mutableListOf<ReturnType>()
    private val functions = mutableListOf<TypedPluginFunction>()
    private val classes = mutableListOf<TypedPluginClass>()

    private fun type(type: ReturnType): Int {
        val existing = types.indexOf(type)
        if (existing >= 0) return existing
        types += type
        return types.size - 1
    }

    /**
     * The type of a class this contribution defines, as scripts refer to it.
     *
     * @param name The class name.
     */
    fun classType(name: String): ClassTypeRef = ClassTypeRef("${TypedPluginClass.PACKAGE_PREFIX}/$contribution", name, false)

    /**
     * Declares a record.
     *
     * @param name The record name.
     * @param fields The fields, in declaration order.
     */
    fun record(name: String, vararg fields: Pair<String, ReturnType>) {
        classes += TypedPluginClass(
            contribution = contribution,
            name = name,
            kind = TypedPluginClass.KIND_RECORD,
            fields = fields.map { (fieldName, fieldType) -> TypedParameter(fieldName, type(fieldType)) }
        )
    }

    /**
     * Declares an opaque class.
     *
     * @param name The class name.
     */
    fun opaque(name: String) {
        classes += TypedPluginClass(contribution = contribution, name = name, kind = TypedPluginClass.KIND_OPAQUE)
    }

    /**
     * Declares a function implemented by the operation of the same name.
     *
     * @param name The function and operation name.
     * @param returnType The return type.
     * @param parameters The parameters, in declaration order.
     */
    fun external(name: String, returnType: ReturnType, vararg parameters: Pair<String, ReturnType>) {
        functions += TypedPluginFunction(
            name = name,
            signatures = mapOf(
                "" to TypedPluginFunctionSignature(
                    parameters = parameters.map { (parameterName, parameterType) -> TypedParameter(parameterName, type(parameterType)) },
                    returnType = type(returnType),
                    external = ExternalImplementation(operation = name, contribution = contribution, session = "functions")
                )
            )
        )
    }

    /**
     * A parameter or field, with the value it takes when left out.
     *
     * @param name The name.
     * @param type Its type.
     * @param default Builds the default value from the index of [type] in this plugin AST's types.
     */
    fun defaulted(name: String, type: ReturnType, default: (Int) -> TypedExpression): TypedParameter {
        val index = type(type)
        return TypedParameter(name, index, default(index))
    }

    /**
     * Declares a record whose fields may have default values.
     *
     * @param name The record name.
     * @param fields The fields, in declaration order.
     */
    fun record(name: String, fields: List<TypedParameter>) {
        classes += TypedPluginClass(contribution = contribution, name = name, kind = TypedPluginClass.KIND_RECORD, fields = fields)
    }

    /**
     * Declares a function implemented by the operation of the same name, whose parameters may have
     * default values.
     *
     * @param name The function and operation name.
     * @param returnType The return type.
     * @param parameters The parameters, in declaration order.
     */
    fun external(name: String, returnType: ReturnType, parameters: List<TypedParameter>) {
        functions += TypedPluginFunction(
            name = name,
            signatures = mapOf(
                "" to TypedPluginFunctionSignature(
                    parameters = parameters,
                    returnType = type(returnType),
                    external = ExternalImplementation(operation = name, contribution = contribution, session = "functions")
                )
            )
        )
    }

    /**
     * A parameter or field without a default value.
     *
     * @param name The name.
     * @param type Its type.
     */
    fun plain(name: String, type: ReturnType): TypedParameter = TypedParameter(name, type(type))

    /**
     * Builds the plugin AST.
     */
    fun build(): TypedPluginAst = TypedPluginAst(types = types.toList(), functions = functions.toList(), classes = classes.toList())
}

/**
 * Builds the typed AST of one contribution.
 *
 * @param contribution The contribution id.
 * @param block Declares the classes and functions.
 */
fun pluginAst(contribution: String, block: PluginAstBuilder.() -> Unit): TypedPluginAst =
    PluginAstBuilder(contribution).apply(block).build()
