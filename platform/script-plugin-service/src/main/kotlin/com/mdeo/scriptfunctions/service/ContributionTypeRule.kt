package com.mdeo.scriptfunctions.service

import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.expression.ast.types.GenericTypeRef
import com.mdeo.expression.ast.types.LambdaType
import com.mdeo.expression.ast.types.ReturnType
import com.mdeo.expression.ast.types.VoidType

/**
 * The one rule for the types a contribution declares, used for parameters, results and record
 * fields alike. Its twin is `checkType` in the script language's `resolvePlugins`, which checks
 * every contribution a project loads, however it was built.
 *
 * Every type of a service's contribution crosses the boundary to the service, so it must be one
 * the protocol can carry: scalars, strings, `Any`, model instances, enum values, the
 * contribution's own records and opaque classes, declared generics, and collections of those, but
 * no lambdas. An external function only reads its arguments, so parameters take read-only
 * collections.
 *
 * @param contributionId The contribution declaring the types
 * @param classes The names of the records and opaque classes the contribution defines
 */
internal class ContributionTypeRule(
    private val contributionId: String,
    private val classes: Set<String>
) {
    private val classPackage = CONTRIBUTED_CLASS_PACKAGE + "/" + contributionId

    /**
     * Checks one type and the types inside it.
     *
     * @param type The type
     * @param where Names the declaration in the error, such as `Parameter 'a' of function 'f'`
     * @param isParameter Whether the type is that of a parameter
     * @param generics The generic type parameters in scope
     * @throws IllegalArgumentException when the type breaks the rule
     */
    fun check(type: ReturnType, where: String, isParameter: Boolean, generics: Set<String>) {
        fun refuse(reason: String): Nothing =
            throw IllegalArgumentException("$where of contribution '$contributionId' $reason")

        when (type) {
            is VoidType -> refuse("is void, which only a function's result can be")
            // A lambda is code inside the execution process, which cannot be sent.
            is LambdaType -> refuse("is a lambda, which cannot cross the boundary to a plugin's service")
            is GenericTypeRef -> if (type.generic !in generics) {
                refuse("refers to generic type '${type.generic}', which is not declared")
            }
            is ClassTypeRef -> {
                val typePackage = type.`package`
                when {
                    typePackage.startsWith("$CONTRIBUTED_CLASS_PACKAGE/") -> {
                        if (typePackage != classPackage || type.type !in classes) {
                            refuse("refers to class '${type.type}' of '$typePackage', which the contribution does not define")
                        }
                        return
                    }
                    typePackage == "builtin" -> {
                        val readonly = COLLECTION_TYPES[type.type]
                        if (readonly == null && type.type !in SCALAR_TYPES) {
                            refuse("has type '${type.type}', which cannot be sent to or from a plugin's service")
                        }
                        if (isParameter && readonly != null && readonly != type.type) {
                            refuse("is a '${type.type}', but an external function cannot change its arguments. Declare it as a '$readonly'")
                        }
                    }
                    !typePackage.startsWith("class/") && !typePackage.startsWith("enum/") ->
                        refuse("has type '${type.type}' of '$typePackage', which cannot be sent to or from a plugin's service")
                }
                type.typeArgs.orEmpty().values.forEach { check(it, where, isParameter, generics) }
            }
        }
    }

    private companion object {
        /**
         * Built-in types other than collections that can be sent to and from a service.
         */
        val SCALAR_TYPES = setOf("int", "long", "float", "double", "boolean", "string", "Any")

        /**
         * Collection types, each with the read-only type a parameter takes instead.
         */
        val COLLECTION_TYPES = mapOf(
            "Collection" to "ReadonlyCollection",
            "OrderedCollection" to "ReadonlyOrderedCollection",
            "List" to "ReadonlyList",
            "Set" to "ReadonlySet",
            "OrderedSet" to "ReadonlyOrderedSet",
            "Bag" to "ReadonlyBag",
            "Map" to "ReadonlyMap",
            "ReadonlyCollection" to "ReadonlyCollection",
            "ReadonlyOrderedCollection" to "ReadonlyOrderedCollection",
            "ReadonlyList" to "ReadonlyList",
            "ReadonlySet" to "ReadonlySet",
            "ReadonlyOrderedSet" to "ReadonlyOrderedSet",
            "ReadonlyBag" to "ReadonlyBag",
            "ReadonlyMap" to "ReadonlyMap"
        )
    }
}
