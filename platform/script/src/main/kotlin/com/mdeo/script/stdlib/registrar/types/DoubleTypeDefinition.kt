package com.mdeo.script.stdlib.registrar.types

import com.mdeo.expression.ast.types.BuiltinTypes
import com.mdeo.script.compiler.registry.type.TypeDefinition
import com.mdeo.script.compiler.registry.type.typeDefinition

private const val DOUBLE_HELPER = "com/mdeo/script/stdlib/impl/primitives/DoubleHelper"

/**
 * Creates the Double type definition.
 */
fun createDoubleType(): TypeDefinition {
    return typeDefinition("builtin", "double") {
        extends("builtin", "Any")
        primitiveDesc("D")
        wrapperClass("java/lang/Double")

        staticMethod("abs") {
            overload("", "(D)D", DOUBLE_HELPER, parameterTypes = emptyList(), returnType = BuiltinTypes.DOUBLE)
        }

        staticMethod("ceil") {
            overload("", "(D)J", DOUBLE_HELPER, parameterTypes = emptyList(), returnType = BuiltinTypes.LONG)
        }

        staticMethod("floor") {
            overload("", "(D)J", DOUBLE_HELPER, parameterTypes = emptyList(), returnType = BuiltinTypes.LONG)
        }

        staticMethod("log") {
            overload("", "(D)D", DOUBLE_HELPER, parameterTypes = emptyList(), returnType = BuiltinTypes.DOUBLE)
        }

        staticMethod("log10") {
            overload("", "(D)D", DOUBLE_HELPER, parameterTypes = emptyList(), returnType = BuiltinTypes.DOUBLE)
        }

        staticMethod("max") {
            overload("int", "(DI)D", DOUBLE_HELPER, "max", parameterTypes = listOf(BuiltinTypes.INT), returnType = BuiltinTypes.DOUBLE)
            overload("long", "(DJ)D", DOUBLE_HELPER, "max", parameterTypes = listOf(BuiltinTypes.LONG), returnType = BuiltinTypes.DOUBLE)
            overload("float", "(DF)D", DOUBLE_HELPER, "max", parameterTypes = listOf(BuiltinTypes.FLOAT), returnType = BuiltinTypes.DOUBLE)
            overload("double", "(DD)D", DOUBLE_HELPER, "max", parameterTypes = listOf(BuiltinTypes.DOUBLE), returnType = BuiltinTypes.DOUBLE)
        }

        staticMethod("min") {
            overload("int", "(DI)D", DOUBLE_HELPER, "min", parameterTypes = listOf(BuiltinTypes.INT), returnType = BuiltinTypes.DOUBLE)
            overload("long", "(DJ)D", DOUBLE_HELPER, "min", parameterTypes = listOf(BuiltinTypes.LONG), returnType = BuiltinTypes.DOUBLE)
            overload("float", "(DF)D", DOUBLE_HELPER, "min", parameterTypes = listOf(BuiltinTypes.FLOAT), returnType = BuiltinTypes.DOUBLE)
            overload("double", "(DD)D", DOUBLE_HELPER, "min", parameterTypes = listOf(BuiltinTypes.DOUBLE), returnType = BuiltinTypes.DOUBLE)
        }

        staticMethod("pow") {
            overload("", "(DD)D", DOUBLE_HELPER, parameterTypes = listOf(BuiltinTypes.DOUBLE), returnType = BuiltinTypes.DOUBLE)
        }

        staticMethod("round") {
            overload("", "(D)J", DOUBLE_HELPER, parameterTypes = emptyList(), returnType = BuiltinTypes.LONG)
        }

        staticMethod("sqrt") {
            overload("", "(D)D", DOUBLE_HELPER, parameterTypes = emptyList(), returnType = BuiltinTypes.DOUBLE)
        }

        staticMethod("sin") {
            overload("", "(D)D", DOUBLE_HELPER, parameterTypes = emptyList(), returnType = BuiltinTypes.DOUBLE)
        }

        staticMethod("cos") {
            overload("", "(D)D", DOUBLE_HELPER, parameterTypes = emptyList(), returnType = BuiltinTypes.DOUBLE)
        }

        staticMethod("tan") {
            overload("", "(D)D", DOUBLE_HELPER, parameterTypes = emptyList(), returnType = BuiltinTypes.DOUBLE)
        }

        staticMethod("asin") {
            overload("", "(D)D", DOUBLE_HELPER, parameterTypes = emptyList(), returnType = BuiltinTypes.DOUBLE)
        }

        staticMethod("acos") {
            overload("", "(D)D", DOUBLE_HELPER, parameterTypes = emptyList(), returnType = BuiltinTypes.DOUBLE)
        }

        staticMethod("atan") {
            overload("", "(D)D", DOUBLE_HELPER, parameterTypes = emptyList(), returnType = BuiltinTypes.DOUBLE)
        }

        staticMethod("sinh") {
            overload("", "(D)D", DOUBLE_HELPER, parameterTypes = emptyList(), returnType = BuiltinTypes.DOUBLE)
        }

        staticMethod("cosh") {
            overload("", "(D)D", DOUBLE_HELPER, parameterTypes = emptyList(), returnType = BuiltinTypes.DOUBLE)
        }

        staticMethod("tanh") {
            overload("", "(D)D", DOUBLE_HELPER, parameterTypes = emptyList(), returnType = BuiltinTypes.DOUBLE)
        }

        staticMethod("toString") {
            overload("", "(D)Ljava/lang/String;", DOUBLE_HELPER, parameterTypes = emptyList(), returnType = BuiltinTypes.STRING)
        }
    }
}
