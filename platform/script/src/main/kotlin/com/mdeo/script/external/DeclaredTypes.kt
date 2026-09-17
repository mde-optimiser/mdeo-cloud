package com.mdeo.script.external

import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.expression.ast.types.ReturnType

/**
 * Derives the declared types of what a collection holds from the type it was declared as, which
 * decides how numbers are read back and which collection kind a returned one is rebuilt as.
 */
internal object DeclaredTypes {

    private val ANY: ReturnType = ClassTypeRef("builtin", "Any", true)

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
