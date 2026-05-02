package com.mdeo.script.stdlib.registrar.types

import com.mdeo.script.compiler.registry.type.TypeDefinition
import com.mdeo.script.compiler.registry.type.typeDefinition

/**
 * Creates the ReadonlySet type definition.
 *
 * ReadonlySet is a readonly collection that does not allow duplicates.
 * It extends ReadonlyCollection and delegates all methods to that parent.
 */
fun createReadonlySetType(): TypeDefinition {
    return typeDefinition("builtin", "ReadonlySet") {
        extends("builtin", "ReadonlyCollection")
    }
}
