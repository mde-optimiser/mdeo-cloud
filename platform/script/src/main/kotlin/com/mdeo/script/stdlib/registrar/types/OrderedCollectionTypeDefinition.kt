package com.mdeo.script.stdlib.registrar.types

import com.mdeo.expression.ast.types.BuiltinTypes
import com.mdeo.script.compiler.registry.type.TypeDefinition
import com.mdeo.script.compiler.registry.type.typeDefinition

private const val ORDERED_COLLECTION = "com/mdeo/script/stdlib/impl/collections/OrderedCollection"

/**
 * Creates the OrderedCollection type definition.
 *
 * OrderedCollection extends ReadonlyOrderedCollection and adds mutation operations.
 */
fun createOrderedCollectionType(): TypeDefinition {
    return typeDefinition("builtin", "OrderedCollection") {
        extends("builtin", "ReadonlyOrderedCollection")
        extends("builtin", "Collection")

        instanceMethod("removeAt") {
            overload(
                "",
                "(I)Ljava/lang/Object;",
                ORDERED_COLLECTION,
                isInterface = true,
                parameterTypes = listOf(BuiltinTypes.INT),
                returnType = BuiltinTypes.NULLABLE_ANY
            )
        }

        instanceMethod("sort") {
            overload(
                "natural", "()Lcom/mdeo/script/stdlib/impl/collections/OrderedCollection;",
                ORDERED_COLLECTION, isInterface = true,
                parameterTypes = emptyList(),
                returnType = BuiltinTypes.ORDERED_COLLECTION
            )
            overload(
                "comparator", "(Lcom/mdeo/script/runtime/interfaces/Func2;)Lcom/mdeo/script/stdlib/impl/collections/OrderedCollection;",
                ORDERED_COLLECTION, isInterface = true,
                parameterTypes = listOf(BuiltinTypes.biFunction()),
                returnType = BuiltinTypes.ORDERED_COLLECTION
            )
        }

        instanceMethod("sortBy") {
            overload(
                "",
                "(Lcom/mdeo/script/api/Function;)Lcom/mdeo/script/stdlib/impl/collections/OrderedCollection;",
                ORDERED_COLLECTION,
                isInterface = true,
                parameterTypes = listOf(BuiltinTypes.function(BuiltinTypes.NULLABLE_ANY, BuiltinTypes.NULLABLE_ANY)),
                returnType = BuiltinTypes.ORDERED_COLLECTION
            )
        }
    }
}
