package com.mdeo.script.stdlib.registrar.types

import com.mdeo.expression.ast.types.BuiltinTypes
import com.mdeo.script.compiler.registry.type.TypeDefinition
import com.mdeo.script.compiler.registry.type.typeDefinition

private const val INT_HELPER = "com/mdeo/script/stdlib/impl/primitives/IntHelper"

/**
 * Creates the Int type definition.
 */
fun createIntType(): TypeDefinition {
    return typeDefinition("builtin", "int") {
        extends("builtin", "Any")
        primitiveDesc("I")
        wrapperClass("java/lang/Integer")

        staticMethod("abs") {
            overload(
                "", "(I)I", INT_HELPER,
                parameterTypes = emptyList(),
                returnType = BuiltinTypes.INT
            )
        }

        staticMethod("ceil") {
            overload(
                "", "(I)I", INT_HELPER,
                parameterTypes = emptyList(),
                returnType = BuiltinTypes.INT
            )
        }

        staticMethod("floor") {
            overload(
                "", "(I)I", INT_HELPER,
                parameterTypes = emptyList(),
                returnType = BuiltinTypes.INT
            )
        }

        staticMethod("log") {
            overload(
                "", "(I)F", INT_HELPER,
                parameterTypes = emptyList(),
                returnType = BuiltinTypes.FLOAT
            )
        }

        staticMethod("log10") {
            overload(
                "", "(I)F", INT_HELPER,
                parameterTypes = emptyList(),
                returnType = BuiltinTypes.FLOAT
            )
        }

        staticMethod("max") {
            overload(
                "int", "(II)I", INT_HELPER, "max",
                parameterTypes = listOf(BuiltinTypes.INT),
                returnType = BuiltinTypes.INT
            )
            overload(
                "long", "(IJ)J", INT_HELPER, "max",
                parameterTypes = listOf(BuiltinTypes.LONG),
                returnType = BuiltinTypes.LONG
            )
            overload(
                "float", "(IF)F", INT_HELPER, "max",
                parameterTypes = listOf(BuiltinTypes.FLOAT),
                returnType = BuiltinTypes.FLOAT
            )
            overload(
                "double", "(ID)D", INT_HELPER, "max",
                parameterTypes = listOf(BuiltinTypes.DOUBLE),
                returnType = BuiltinTypes.DOUBLE
            )
        }

        staticMethod("min") {
            overload(
                "int", "(II)I", INT_HELPER, "min",
                parameterTypes = listOf(BuiltinTypes.INT),
                returnType = BuiltinTypes.INT
            )
            overload(
                "long", "(IJ)J", INT_HELPER, "min",
                parameterTypes = listOf(BuiltinTypes.LONG),
                returnType = BuiltinTypes.LONG
            )
            overload(
                "float", "(IF)F", INT_HELPER, "min",
                parameterTypes = listOf(BuiltinTypes.FLOAT),
                returnType = BuiltinTypes.FLOAT
            )
            overload(
                "double", "(ID)D", INT_HELPER, "min",
                parameterTypes = listOf(BuiltinTypes.DOUBLE),
                returnType = BuiltinTypes.DOUBLE
            )
        }

        staticMethod("pow") {
            overload(
                "", "(ID)D", INT_HELPER,
                parameterTypes = listOf(BuiltinTypes.DOUBLE),
                returnType = BuiltinTypes.DOUBLE
            )
        }

        staticMethod("round") {
            overload(
                "", "(I)I", INT_HELPER,
                parameterTypes = emptyList(),
                returnType = BuiltinTypes.INT
            )
        }

        staticMethod("iota") {
            overload(
                "", "(III)Lcom/mdeo/script/stdlib/impl/collections/ScriptList;", INT_HELPER,
                parameterTypes = listOf(BuiltinTypes.INT, BuiltinTypes.INT),
                returnType = BuiltinTypes.LIST
            )
        }

        staticMethod("mod") {
            overload(
                "", "(II)I", INT_HELPER,
                parameterTypes = listOf(BuiltinTypes.INT),
                returnType = BuiltinTypes.INT
            )
        }

        staticMethod("to") {
            overload(
                "", "(II)Lcom/mdeo/script/stdlib/impl/collections/ScriptList;", INT_HELPER,
                parameterTypes = listOf(BuiltinTypes.INT),
                returnType = BuiltinTypes.LIST
            )
        }

        staticMethod("toBinary") {
            overload(
                "", "(I)Ljava/lang/String;", INT_HELPER,
                parameterTypes = emptyList(),
                returnType = BuiltinTypes.STRING
            )
        }

        staticMethod("toHex") {
            overload(
                "", "(I)Ljava/lang/String;", INT_HELPER,
                parameterTypes = emptyList(),
                returnType = BuiltinTypes.STRING
            )
        }

        staticMethod("toString") {
            overload(
                "", "(I)Ljava/lang/String;", INT_HELPER,
                parameterTypes = emptyList(),
                returnType = BuiltinTypes.STRING
            )
        }

    }
}
