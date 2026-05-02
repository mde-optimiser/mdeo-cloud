package com.mdeo.script.stdlib.registrar.types

import com.mdeo.script.compiler.registry.type.TypeDefinition
import com.mdeo.script.compiler.registry.type.typeDefinition

/**
 * Creates the ReadonlyList type definition.
 *
 * ReadonlyList is a readonly ordered collection that allows duplicates.
 * It extends ReadonlyOrderedCollection and delegates all methods to that parent.
 */
fun createReadonlyListType(): TypeDefinition {
    return typeDefinition("builtin", "ReadonlyList") {
        extends("builtin", "ReadonlyOrderedCollection")
    }
}
