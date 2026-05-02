package com.mdeo.script.stdlib.registrar.types

import com.mdeo.script.compiler.registry.type.TypeDefinition
import com.mdeo.script.compiler.registry.type.TypeRegistry
import com.mdeo.script.compiler.registry.type.typeDefinition

/**
 * Registers all stdlib type definitions in the registry.
 *
 * The definitions match the TypeScript stdlib definitions in
 * app/packages/language-expression/src/stdlib/
 */
object StdlibRegistrar {

    /**
     * Registers all stdlib types in the given registry.
     *
     * @param registry The registry to add types to.
     */
    fun registerAll(registry: TypeRegistry) {
        registry.register(createAnyType())
        registry.register(createIntType())
        registry.register(createLongType())
        registry.register(createFloatType())
        registry.register(createDoubleType())
        registry.register(createBooleanType())
        registry.register(createStringType())

        registry.register(createReadonlyCollectionType())
        registry.register(createCollectionType())
        registry.register(createReadonlyOrderedCollectionType())
        registry.register(createOrderedCollectionType())
        registry.register(createReadonlyListType())
        registry.register(createListType())
        registry.register(createReadonlySetType())
        registry.register(createSetType())
        registry.register(createReadonlyBagType())
        registry.register(createBagType())
        registry.register(createReadonlyOrderedSetType())
        registry.register(createOrderedSetType())

        registry.register(createReadonlyMapType())
        registry.register(createMapType())
    }
}
