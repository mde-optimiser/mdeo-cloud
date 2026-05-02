package com.mdeo.script.stdlib.registrar.types

import com.mdeo.expression.ast.types.BuiltinTypes
import com.mdeo.script.compiler.registry.type.TypeDefinition
import com.mdeo.script.compiler.registry.type.typeDefinition

private const val SCRIPT_MAP = "com/mdeo/script/stdlib/impl/collections/ScriptMap"

/**
 * Creates the Map type definition.
 *
 * Map is a mutable key-value store that extends ReadonlyMap.
 */
fun createMapType(): TypeDefinition {
    return typeDefinition("builtin", "Map") {
        extends("builtin", "ReadonlyMap")

        instanceMethod("clear") {
            overload("", "()V", SCRIPT_MAP, isInterface = true,
                parameterTypes = emptyList(), returnType = BuiltinTypes.VOID)
        }

        instanceMethod("put") {
            overload("", "(Ljava/lang/Object;Ljava/lang/Object;)V", SCRIPT_MAP, isInterface = true,
                parameterTypes = listOf(BuiltinTypes.NULLABLE_ANY, BuiltinTypes.NULLABLE_ANY), returnType = BuiltinTypes.VOID)
        }

        instanceMethod("putAll") {
            overload("", "(Lcom/mdeo/script/stdlib/impl/collections/ReadonlyMap;)V", SCRIPT_MAP, isInterface = true,
                parameterTypes = listOf(BuiltinTypes.READONLY_MAP), returnType = BuiltinTypes.VOID)
        }

        instanceMethod("remove") {
            overload("", "(Ljava/lang/Object;)Ljava/lang/Object;", SCRIPT_MAP, isInterface = true,
                parameterTypes = listOf(BuiltinTypes.NULLABLE_ANY), returnType = BuiltinTypes.NULLABLE_ANY)
        }
    }
}
