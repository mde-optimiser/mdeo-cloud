package com.mdeo.script.external

import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.expression.ast.types.ReturnType

/**
 * Derives whether the service may change a collection from the type it was declared as.
 *
 * The declared type is the whole contract. A parameter declared as a mutable collection type is
 * *inout*: the service may change it and the change is written back. Everything else is *in* —
 * readonly collection types, `Any`, generics, scalars — and must come back exactly as it went.
 * Elements inherit the rule from their own declared type argument, so a `List<ReadonlyList<int>>`
 * lets the service reorder the outer list but not touch the inner ones. Map keys are always *in*:
 * changing a key in place would silently break the map's hashing.
 */
internal object ParameterModes {

    private val MUTABLE_TYPES = setOf("List", "Set", "Bag", "OrderedSet", "Map", "Collection", "OrderedCollection")

    private val ANY: ReturnType = ClassTypeRef("builtin", "Any", true)

    /**
     * Whether a value declared as [type] may be changed by the service.
     */
    fun isInout(type: ReturnType): Boolean =
        type is ClassTypeRef && type.`package` == "builtin" && type.type in MUTABLE_TYPES

    /**
     * The declared type of the elements of a collection declared as [type].
     */
    fun elementType(type: ReturnType): ReturnType {
        if (type !is ClassTypeRef) return ANY
        val args = type.typeArgs ?: return ANY
        return args["T"] ?: args.values.firstOrNull() ?: ANY
    }

    /**
     * The declared type of the keys of a map declared as [type].
     */
    fun keyType(type: ReturnType): ReturnType {
        if (type !is ClassTypeRef) return ANY
        val args = type.typeArgs ?: return ANY
        return args["K"] ?: args.values.firstOrNull() ?: ANY
    }

    /**
     * The declared type of the values of a map declared as [type].
     */
    fun valueType(type: ReturnType): ReturnType {
        if (type !is ClassTypeRef) return ANY
        val args = type.typeArgs ?: return ANY
        return args["V"] ?: args.values.drop(1).firstOrNull() ?: ANY
    }

    /**
     * Declared `Any?`, for values whose type nothing says anything about.
     */
    val any: ReturnType get() = ANY
}
