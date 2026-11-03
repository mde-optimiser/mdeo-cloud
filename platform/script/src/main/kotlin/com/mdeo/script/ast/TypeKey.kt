package com.mdeo.script.ast

/**
 * Identifies a class scripts refer to by name: a record a script declares or a class a
 * contribution defines.
 *
 * The package and the name are kept apart rather than joined into one string: packages hold file
 * paths and contribution ids, which may contain any separator one could join them with.
 *
 * @property package The type package, as types in the types array refer to it
 * @property name The class name within its package
 */
data class TypeKey(val `package`: String, val name: String)
