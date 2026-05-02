package com.mdeo.script.stdlib.registrar.types

import com.mdeo.expression.ast.types.BuiltinTypes
import com.mdeo.script.compiler.registry.type.TypeDefinition
import com.mdeo.script.compiler.registry.type.typeDefinition

private const val READONLY_MAP = "com/mdeo/script/stdlib/impl/collections/ReadonlyMap"

/**
 * Creates the ReadonlyMap type definition.
 *
 * ReadonlyMap provides read-only access to key-value pairs.
 */
fun createReadonlyMapType(): TypeDefinition {
    return typeDefinition("builtin", "ReadonlyMap") {
        extends("builtin", "Any")

        instanceMethod("size") {
            overload(
                "",
                "()I",
                READONLY_MAP,
                isInterface = true,
                parameterTypes = emptyList(), returnType = BuiltinTypes.INT
            )
        }

        instanceMethod("isEmpty") {
            overload(
                "",
                "()Z",
                READONLY_MAP,
                isInterface = true,
                parameterTypes = emptyList(), returnType = BuiltinTypes.BOOLEAN
            )
        }

        instanceMethod("containsKey") {
            overload(
                "",
                "(Ljava/lang/Object;)Z",
                READONLY_MAP,
                isInterface = true,
                parameterTypes = listOf(BuiltinTypes.NULLABLE_ANY), returnType = BuiltinTypes.BOOLEAN
            )
        }

        instanceMethod("containsValue") {
            overload(
                "",
                "(Ljava/lang/Object;)Z",
                READONLY_MAP,
                isInterface = true,
                parameterTypes = listOf(BuiltinTypes.NULLABLE_ANY), returnType = BuiltinTypes.BOOLEAN
            )
        }

        instanceMethod("get") {
            overload(
                "",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                READONLY_MAP,
                isInterface = true,
                parameterTypes = listOf(BuiltinTypes.NULLABLE_ANY), returnType = BuiltinTypes.NULLABLE_ANY
            )
        }

        instanceMethod("keySet") {
            overload(
                "",
                "()Lcom/mdeo/script/stdlib/impl/collections/ReadonlySet;",
                READONLY_MAP,
                isInterface = true,
                parameterTypes = emptyList(), returnType = BuiltinTypes.READONLY_SET
            )
        }

        instanceMethod("values") {
            overload(
                "",
                "()Lcom/mdeo/script/stdlib/impl/collections/ReadonlyBag;",
                READONLY_MAP,
                isInterface = true,
                parameterTypes = emptyList(), returnType = BuiltinTypes.READONLY_BAG
            )
        }
    }
}
