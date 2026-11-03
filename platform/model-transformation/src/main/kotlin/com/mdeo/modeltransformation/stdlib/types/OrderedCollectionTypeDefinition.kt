package com.mdeo.modeltransformation.stdlib.types

import com.mdeo.modeltransformation.compiler.CompilationResult
import com.mdeo.modeltransformation.compiler.registry.GremlinTypeDefinition
import com.mdeo.modeltransformation.compiler.registry.gremlinType
import com.mdeo.modeltransformation.stdlib.contextMethod
import com.mdeo.modeltransformation.stdlib.equalsTraversal
import org.apache.tinkerpop.gremlin.process.traversal.Scope
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.`__` as AnonymousTraversal
import org.apache.tinkerpop.gremlin.structure.VertexProperty

/**
 * Creates the OrderedCollection type definition.
 *
 * An OrderedCollection extends Collection with ordered-specific operations.
 * Elements maintain their insertion order when stored and retrieved.
 *
 * Uses list cardinality for vertex properties to maintain order.
 * Inherits all methods from Collection including filter, map, exists, all, etc.
 *
 * ## Methods
 * - `indexOf(item)` - The position of the first element that equals the item (`==`), or -1, as
 *   in the script language: each element is mapped to whether it equals the item, the
 *   booleans are folded into a list, `index()` pairs them with their positions, and the first
 *   position whose boolean is true is taken, with `-1` from `coalesce` when there is none.
 *
 * @return The OrderedCollection type definition for the Gremlin type registry
 */
@Suppress("UNCHECKED_CAST")
fun createOrderedCollectionType(): GremlinTypeDefinition {
    return gremlinType("builtin", "OrderedCollection")
        .extends("builtin", "Collection")
        .cardinality(VertexProperty.Cardinality.list)
        .contextMethod("indexOf", 1) { receiver, arguments, context, registry ->
            val flags = (receiver as GraphTraversal<Any, Any>).unfold<Any>()
                .map(equalsTraversal(arguments.single(), context, registry))
                .fold()
            val firstMatch = AnonymousTraversal.index<Any, Any>()
                .unfold<Any>()
                .filter(AnonymousTraversal.limit<Any>(Scope.local, 1).unfold<Any>().`is`(true))
                .limit(1)
                .tail<Any>(Scope.local, 1)
                .unfold<Any>()
            val result = flags.coalesce(firstMatch, AnonymousTraversal.constant<Any>(-1))
            CompilationResult.of(result as GraphTraversal<Any, Any>)
        }
        .build()
}
