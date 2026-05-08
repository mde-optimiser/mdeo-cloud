package com.mdeo.script.stdlib.registrar.types

import com.mdeo.expression.ast.types.BuiltinTypes
import com.mdeo.script.compiler.registry.type.TypeDefinition
import com.mdeo.script.compiler.registry.type.typeDefinition

private const val LONG_HELPER = "com/mdeo/script/stdlib/impl/primitives/LongHelper"

/**
 * Creates the Long type definition.
 */
fun createLongType(): TypeDefinition {
    return typeDefinition("builtin", "long") {
        extends("builtin", "Any")
        primitiveDesc("J")
        wrapperClass("java/lang/Long")

        staticMethod("abs") {
            overload("", "(J)J", LONG_HELPER, parameterTypes = emptyList(), returnType = BuiltinTypes.LONG)
        }

        staticMethod("ceiling") {
            overload("", "(J)J", LONG_HELPER, parameterTypes = emptyList(), returnType = BuiltinTypes.LONG)
        }

        staticMethod("floor") {
            overload("", "(J)J", LONG_HELPER, parameterTypes = emptyList(), returnType = BuiltinTypes.LONG)
        }

        staticMethod("log") {
            overload("", "(J)D", LONG_HELPER, parameterTypes = emptyList(), returnType = BuiltinTypes.DOUBLE)
        }

        staticMethod("log10") {
            overload("", "(J)D", LONG_HELPER, parameterTypes = emptyList(), returnType = BuiltinTypes.DOUBLE)
        }

        staticMethod("max") {
            overload("int", "(JI)J", LONG_HELPER, "max", parameterTypes = listOf(BuiltinTypes.INT), returnType = BuiltinTypes.LONG)
            overload("long", "(JJ)J", LONG_HELPER, "max", parameterTypes = listOf(BuiltinTypes.LONG), returnType = BuiltinTypes.LONG)
            overload("float", "(JF)F", LONG_HELPER, "max", parameterTypes = listOf(BuiltinTypes.FLOAT), returnType = BuiltinTypes.FLOAT)
            overload("double", "(JD)D", LONG_HELPER, "max", parameterTypes = listOf(BuiltinTypes.DOUBLE), returnType = BuiltinTypes.DOUBLE)
        }

        staticMethod("min") {
            overload("int", "(JI)J", LONG_HELPER, "min", parameterTypes = listOf(BuiltinTypes.INT), returnType = BuiltinTypes.LONG)
            overload("long", "(JJ)J", LONG_HELPER, "min", parameterTypes = listOf(BuiltinTypes.LONG), returnType = BuiltinTypes.LONG)
            overload("float", "(JF)F", LONG_HELPER, "min", parameterTypes = listOf(BuiltinTypes.FLOAT), returnType = BuiltinTypes.FLOAT)
            overload("double", "(JD)D", LONG_HELPER, "min", parameterTypes = listOf(BuiltinTypes.DOUBLE), returnType = BuiltinTypes.DOUBLE)
        }

        staticMethod("pow") {
            overload("", "(JD)D", LONG_HELPER, parameterTypes = listOf(BuiltinTypes.DOUBLE), returnType = BuiltinTypes.DOUBLE)
        }

        staticMethod("round") {
            overload("", "(J)J", LONG_HELPER, parameterTypes = emptyList(), returnType = BuiltinTypes.LONG)
        }

        staticMethod("iota") {
            overload("", "(JJJ)Lcom/mdeo/script/stdlib/impl/collections/ScriptList;", LONG_HELPER,
                parameterTypes = listOf(BuiltinTypes.LONG, BuiltinTypes.LONG), returnType = BuiltinTypes.LIST)
        }

        staticMethod("mod") {
            overload("", "(JJ)J", LONG_HELPER, parameterTypes = listOf(BuiltinTypes.LONG), returnType = BuiltinTypes.LONG)
        }

        staticMethod("to") {
            overload("", "(JJ)Lcom/mdeo/script/stdlib/impl/collections/ScriptList;", LONG_HELPER,
                parameterTypes = listOf(BuiltinTypes.LONG), returnType = BuiltinTypes.LIST)
        }

        staticMethod("toBinary") {
            overload("", "(J)Ljava/lang/String;", LONG_HELPER, parameterTypes = emptyList(), returnType = BuiltinTypes.STRING)
        }

        staticMethod("toHex") {
            overload("", "(J)Ljava/lang/String;", LONG_HELPER, parameterTypes = emptyList(), returnType = BuiltinTypes.STRING)
        }

        staticMethod("toString") {
            overload("", "(J)Ljava/lang/String;", LONG_HELPER, parameterTypes = emptyList(), returnType = BuiltinTypes.STRING)
        }

    }
}
