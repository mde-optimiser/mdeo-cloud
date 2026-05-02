package com.mdeo.script.stdlib.registrar.types

import com.mdeo.expression.ast.types.BuiltinTypes
import com.mdeo.script.compiler.registry.type.TypeDefinition
import com.mdeo.script.compiler.registry.type.typeDefinition

private const val READONLY_COLLECTION = "com/mdeo/script/stdlib/impl/collections/ReadonlyCollection"

/**
 * Creates the ReadonlyCollection type definition.
 *
 * ReadonlyCollection is the base interface for all readonly collections.
 * It provides read-only operations like size(), isEmpty(), includes(), etc.
 */
fun createReadonlyCollectionType(): TypeDefinition {
    return typeDefinition("builtin", "ReadonlyCollection") {
        extends("builtin", "Any")

        instanceMethod("size") {
            overload("", "()I", READONLY_COLLECTION, isInterface = true,
                parameterTypes = emptyList(), returnType = BuiltinTypes.INT)
        }

        instanceMethod("isEmpty") {
            overload("", "()Z", READONLY_COLLECTION, isInterface = true,
                parameterTypes = emptyList(), returnType = BuiltinTypes.BOOLEAN)
        }

        instanceMethod("notEmpty") {
            overload("", "()Z", READONLY_COLLECTION, isInterface = true,
                parameterTypes = emptyList(), returnType = BuiltinTypes.BOOLEAN)
        }

        instanceMethod("includes") {
            overload("", "(Ljava/lang/Object;)Z", READONLY_COLLECTION, isInterface = true,
                parameterTypes = listOf(BuiltinTypes.NULLABLE_ANY), returnType = BuiltinTypes.BOOLEAN)
        }

        instanceMethod("excludes") {
            overload("", "(Ljava/lang/Object;)Z", READONLY_COLLECTION, isInterface = true,
                parameterTypes = listOf(BuiltinTypes.NULLABLE_ANY), returnType = BuiltinTypes.BOOLEAN)
        }

        instanceMethod("count") {
            overload("", "(Ljava/lang/Object;)I", READONLY_COLLECTION, isInterface = true,
                parameterTypes = listOf(BuiltinTypes.NULLABLE_ANY), returnType = BuiltinTypes.INT)
        }

        instanceMethod("sum") {
            overload("", "()D", READONLY_COLLECTION, isInterface = true,
                parameterTypes = emptyList(), returnType = BuiltinTypes.DOUBLE)
        }

        instanceMethod("concat") {
            overload("nosep", "()Ljava/lang/String;", READONLY_COLLECTION, isInterface = true,
                parameterTypes = emptyList(), returnType = BuiltinTypes.STRING)
            overload("sep", "(Ljava/lang/String;)Ljava/lang/String;", READONLY_COLLECTION, isInterface = true,
                parameterTypes = listOf(BuiltinTypes.STRING), returnType = BuiltinTypes.STRING)
        }

        instanceMethod("including") {
            overload("", "(Ljava/lang/Object;)Lcom/mdeo/script/stdlib/impl/collections/ReadonlyCollection;",
                READONLY_COLLECTION, isInterface = true,
                parameterTypes = listOf(BuiltinTypes.NULLABLE_ANY), returnType = BuiltinTypes.READONLY_COLLECTION)
        }

        instanceMethod("excluding") {
            overload("", "(Ljava/lang/Object;)Lcom/mdeo/script/stdlib/impl/collections/ReadonlyCollection;",
                READONLY_COLLECTION, isInterface = true,
                parameterTypes = listOf(BuiltinTypes.NULLABLE_ANY), returnType = BuiltinTypes.READONLY_COLLECTION)
        }

        instanceMethod("toList") {
            overload("", "()Lcom/mdeo/script/stdlib/impl/collections/ScriptList;", READONLY_COLLECTION, isInterface = true,
                parameterTypes = emptyList(), returnType = BuiltinTypes.LIST)
        }

        instanceMethod("toSet") {
            overload("", "()Lcom/mdeo/script/stdlib/impl/collections/ScriptSet;", READONLY_COLLECTION, isInterface = true,
                parameterTypes = emptyList(), returnType = BuiltinTypes.SET)
        }

        instanceMethod("toBag") {
            overload("", "()Lcom/mdeo/script/stdlib/impl/collections/Bag;", READONLY_COLLECTION, isInterface = true,
                parameterTypes = emptyList(), returnType = BuiltinTypes.BAG)
        }

        instanceMethod("toOrderedSet") {
            overload("", "()Lcom/mdeo/script/stdlib/impl/collections/OrderedSet;", READONLY_COLLECTION, isInterface = true,
                parameterTypes = emptyList(), returnType = BuiltinTypes.ORDERED_SET)
        }

        instanceMethod("includesAll") {
            overload("", "(Lcom/mdeo/script/stdlib/impl/collections/ReadonlyCollection;)Z",
                READONLY_COLLECTION, isInterface = true,
                parameterTypes = listOf(BuiltinTypes.READONLY_COLLECTION), returnType = BuiltinTypes.BOOLEAN)
        }

        instanceMethod("excludesAll") {
            overload("", "(Lcom/mdeo/script/stdlib/impl/collections/ReadonlyCollection;)Z",
                READONLY_COLLECTION, isInterface = true,
                parameterTypes = listOf(BuiltinTypes.READONLY_COLLECTION), returnType = BuiltinTypes.BOOLEAN)
        }

        instanceMethod("includingAll") {
            overload("", "(Lcom/mdeo/script/stdlib/impl/collections/ReadonlyCollection;)Lcom/mdeo/script/stdlib/impl/collections/ReadonlyCollection;",
                READONLY_COLLECTION, isInterface = true,
                parameterTypes = listOf(BuiltinTypes.READONLY_COLLECTION), returnType = BuiltinTypes.READONLY_COLLECTION)
        }

        instanceMethod("excludingAll") {
            overload("", "(Lcom/mdeo/script/stdlib/impl/collections/ReadonlyCollection;)Lcom/mdeo/script/stdlib/impl/collections/ReadonlyCollection;",
                READONLY_COLLECTION, isInterface = true,
                parameterTypes = listOf(BuiltinTypes.READONLY_COLLECTION), returnType = BuiltinTypes.READONLY_COLLECTION)
        }

        instanceMethod("flatten") {
            overload("", "()Lcom/mdeo/script/stdlib/impl/collections/ReadonlyCollection;", READONLY_COLLECTION, isInterface = true,
                parameterTypes = emptyList(), returnType = BuiltinTypes.READONLY_COLLECTION)
        }

        instanceMethod("random") {
            overload("", "()Ljava/lang/Object;", READONLY_COLLECTION, isInterface = true,
                parameterTypes = emptyList(), returnType = BuiltinTypes.NULLABLE_ANY)
        }

        instanceMethod("clone") {
            overload("", "()Lcom/mdeo/script/stdlib/impl/collections/Collection;", READONLY_COLLECTION, isInterface = true,
                parameterTypes = emptyList(), returnType = BuiltinTypes.COLLECTION)
        }

        instanceMethod("atLeastNMatch") {
            overload("", "(Lcom/mdeo/script/runtime/interfaces/Predicate1;I)Z", READONLY_COLLECTION, isInterface = true,
                parameterTypes = listOf(BuiltinTypes.predicate(), BuiltinTypes.INT), returnType = BuiltinTypes.BOOLEAN)
        }

        instanceMethod("atMostNMatch") {
            overload("", "(Lcom/mdeo/script/runtime/interfaces/Predicate1;I)Z", READONLY_COLLECTION, isInterface = true,
                parameterTypes = listOf(BuiltinTypes.predicate(), BuiltinTypes.INT), returnType = BuiltinTypes.BOOLEAN)
        }

        instanceMethod("aggregate") {
            overload("", "(Lcom/mdeo/script/runtime/interfaces/Func1;)Lcom/mdeo/script/stdlib/impl/collections/ScriptMap;",
                READONLY_COLLECTION, isInterface = true,
                parameterTypes = listOf(BuiltinTypes.function()), returnType = BuiltinTypes.MAP)
        }

        instanceMethod("map") {
            overload("", "(Lcom/mdeo/script/runtime/interfaces/Func1;)Lcom/mdeo/script/stdlib/impl/collections/ScriptList;",
                READONLY_COLLECTION, isInterface = true,
                parameterTypes = listOf(BuiltinTypes.function()), returnType = BuiltinTypes.LIST)
        }

        instanceMethod("exists") {
            overload("", "(Lcom/mdeo/script/runtime/interfaces/Predicate1;)Z", READONLY_COLLECTION, isInterface = true,
                parameterTypes = listOf(BuiltinTypes.predicate()), returnType = BuiltinTypes.BOOLEAN)
        }

        instanceMethod("forEach") {
            overload("", "(Lcom/mdeo/script/runtime/interfaces/Action1;)V", READONLY_COLLECTION, isInterface = true,
                parameterTypes = listOf(BuiltinTypes.consumer()), returnType = BuiltinTypes.VOID)
        }

        instanceMethod("associate") {
            overload("", "(Lcom/mdeo/script/runtime/interfaces/Func1;)Lcom/mdeo/script/stdlib/impl/collections/ReadonlyMap;",
                READONLY_COLLECTION, isInterface = true,
                parameterTypes = listOf(BuiltinTypes.function()), returnType = BuiltinTypes.READONLY_MAP)
        }

        instanceMethod("nMatch") {
            overload("", "(Lcom/mdeo/script/runtime/interfaces/Predicate1;I)Z", READONLY_COLLECTION, isInterface = true,
                parameterTypes = listOf(BuiltinTypes.predicate(), BuiltinTypes.INT), returnType = BuiltinTypes.BOOLEAN)
        }

        instanceMethod("none") {
            overload("", "(Lcom/mdeo/script/runtime/interfaces/Predicate1;)Z", READONLY_COLLECTION, isInterface = true,
                parameterTypes = listOf(BuiltinTypes.predicate()), returnType = BuiltinTypes.BOOLEAN)
        }

        instanceMethod("one") {
            overload("", "(Lcom/mdeo/script/runtime/interfaces/Predicate1;)Z", READONLY_COLLECTION, isInterface = true,
                parameterTypes = listOf(BuiltinTypes.predicate()), returnType = BuiltinTypes.BOOLEAN)
        }

        instanceMethod("reject") {
            overload("", "(Lcom/mdeo/script/runtime/interfaces/Predicate1;)Lcom/mdeo/script/stdlib/impl/collections/Collection;",
                READONLY_COLLECTION, isInterface = true,
                parameterTypes = listOf(BuiltinTypes.predicate()), returnType = BuiltinTypes.COLLECTION)
        }

        instanceMethod("rejectOne") {
            overload("", "(Lcom/mdeo/script/runtime/interfaces/Predicate1;)Lcom/mdeo/script/stdlib/impl/collections/Collection;",
                READONLY_COLLECTION, isInterface = true,
                parameterTypes = listOf(BuiltinTypes.predicate()), returnType = BuiltinTypes.COLLECTION)
        }

        instanceMethod("filter") {
            overload("", "(Lcom/mdeo/script/runtime/interfaces/Predicate1;)Lcom/mdeo/script/stdlib/impl/collections/Collection;",
                READONLY_COLLECTION, isInterface = true,
                parameterTypes = listOf(BuiltinTypes.predicate()), returnType = BuiltinTypes.COLLECTION)
        }

        instanceMethod("all") {
            overload("", "(Lcom/mdeo/script/runtime/interfaces/Predicate1;)Z", READONLY_COLLECTION, isInterface = true,
                parameterTypes = listOf(BuiltinTypes.predicate()), returnType = BuiltinTypes.BOOLEAN)
        }

        instanceMethod("find") {
            overload("", "(Lcom/mdeo/script/runtime/interfaces/Predicate1;)Ljava/lang/Object;", READONLY_COLLECTION, isInterface = true,
                parameterTypes = listOf(BuiltinTypes.predicate()), returnType = BuiltinTypes.NULLABLE_ANY)
        }

        instanceMethod("sortedBy") {
            overload("", "(Lcom/mdeo/script/runtime/interfaces/Func1;)Lcom/mdeo/script/stdlib/impl/collections/ReadonlyOrderedCollection;",
                READONLY_COLLECTION, isInterface = true,
                parameterTypes = listOf(BuiltinTypes.function()), returnType = BuiltinTypes.READONLY_ORDERED_COLLECTION)
        }

        instanceMethod("count") {
            overload("lambda", "(Lcom/mdeo/script/runtime/interfaces/Predicate1;)I", READONLY_COLLECTION, isInterface = true,
                parameterTypes = listOf(BuiltinTypes.predicate()), returnType = BuiltinTypes.INT)
        }

        instanceMethod("flatMap") {
            overload("", "(Lcom/mdeo/script/runtime/interfaces/Func1;)Lcom/mdeo/script/stdlib/impl/collections/Collection;",
                READONLY_COLLECTION, isInterface = true,
                parameterTypes = listOf(BuiltinTypes.function()), returnType = BuiltinTypes.COLLECTION)
        }

        instanceMethod("first") {
            overload("", "()Ljava/lang/Object;", READONLY_COLLECTION, isInterface = true,
                parameterTypes = emptyList(), returnType = BuiltinTypes.NULLABLE_ANY)
        }

        instanceMethod("firstOrNull") {
            overload("", "()Ljava/lang/Object;", READONLY_COLLECTION, isInterface = true,
                parameterTypes = emptyList(), returnType = BuiltinTypes.NULLABLE_ANY)
        }
    }
}
