package com.mdeo.script.external

import com.mdeo.scriptfunctions.protocol.ClientMessage
import com.mdeo.scriptfunctions.protocol.WireValue
import com.mdeo.scriptfunctions.protocol.ServiceMessage
import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.expression.ast.types.ReturnType
import com.mdeo.expression.ast.types.ValueType
import com.mdeo.script.compiler.ExternalCallSpec
import com.mdeo.script.stdlib.impl.collections.BagImpl
import com.mdeo.script.stdlib.impl.collections.ListImpl
import com.mdeo.script.stdlib.impl.collections.MapImpl
import com.mdeo.script.stdlib.impl.collections.OrderedSetImpl
import com.mdeo.script.stdlib.impl.collections.SetImpl
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Calls over the `script-functions` protocol, through the real encoding, against the in-process
 * [Loopback] service.
 */
class ScriptFunctionsClientTest {

    private val int = ClassTypeRef("builtin", "int", false)
    private val string = ClassTypeRef("builtin", "string", false)
    private val any = ClassTypeRef("builtin", "Any", true)

    private fun collection(type: String, vararg args: Pair<String, ValueType>) =
        ClassTypeRef("builtin", type, false, if (args.isEmpty()) null else mapOf(*args))

    private fun spec(operation: String, returnType: ReturnType, vararg params: ReturnType) =
        ExternalCallSpec(operation, operation, "", operation, "none", params.toList(), returnType)

    private fun client(loopback: Loopback, vararg specs: ExternalCallSpec) =
        ScriptFunctionsClient(loopback, specs.associateBy { it.callId })

    @Test
    fun `a list, a set and a bag reach the service as they are`() {
        val loopback = Loopback.ofArguments(
            mapOf("describe" to { args -> args.joinToString(" ") })
        )
        val spec = spec(
            "describe", string,
            collection("List", "T" to int), collection("Set", "T" to string), collection("Bag", "T" to string)
        )
        val dispatcher = client(loopback, spec)

        val list = ListImpl(listOf(3, 1, 2))
        val set = SetImpl(listOf("a", "b"))
        val bag = BagImpl(listOf("x", "x"))

        assertEquals("[3, 1, 2] [a, b] [x, x]", dispatcher.call("describe", arrayOf(list, set, bag), null, javaClass.classLoader))
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `an operation that changes an argument fails and the script's collection stays as it was`() {
        val loopback = Loopback.ofArguments(
            mapOf("append" to { args -> (args[0] as MutableList<Any?>).add(4) })
        )
        val dispatcher = client(loopback, spec("append", any, collection("List", "T" to int)))

        val list = ListImpl(listOf(1, 2, 3))
        val error = assertFailsWith<ExternalCallException> {
            dispatcher.call("append", arrayOf(list), null, javaClass.classLoader)
        }
        assertTrue(error.message!!.contains("readonly"), error.message)
        assertEquals(listOf(1, 2, 3), list.heapSnapshot())
    }

    @Test
    fun `the same list passed twice is one list on the other side`() {
        var sameOnService = false
        val loopback = Loopback.ofArguments(
            mapOf(
                "alias" to { args ->
                    sameOnService = args[0] === args[1]
                    null
                }
            )
        )
        val listType = collection("List", "T" to int)
        val dispatcher = client(loopback, spec("alias", any, listType, listType))

        val list = ListImpl(listOf(1, 2, 3))
        dispatcher.call("alias", arrayOf(list, list), null, javaClass.classLoader)

        assertTrue(sameOnService)
        val call = loopback.received.single() as ClientMessage.Call
        assertEquals(1, call.objects.size, "an aliased collection is sent once")
    }

    @Test
    fun `a list that contains itself survives the trip`() {
        var cycleOnService = false
        val loopback = Loopback.ofArguments(
            mapOf(
                "cycle" to { args ->
                    val outer = args[0] as List<*>
                    cycleOnService = outer[1] === outer
                    outer
                }
            )
        )
        val dispatcher = client(loopback, spec("cycle", any, collection("List")))

        val list = ListImpl<Any?>()
        list.add("head")
        list.add(list)

        val returned = dispatcher.call("cycle", arrayOf(list), null, javaClass.classLoader)

        assertTrue(cycleOnService)
        assertSame(list, returned, "returning an argument returns the very same collection")
        assertEquals(2, list.size())
        assertSame(list, list.at(1))
    }

    @Test
    fun `an unchanged collection is sent again as just its id`() {
        val loopback = Loopback.ofArguments(mapOf("read" to { args -> (args[0] as List<*>).size }))
        val dispatcher = client(loopback, spec("read", int, collection("ReadonlyList", "T" to int)))

        val list = ListImpl(listOf(1, 2, 3))
        assertEquals(3, dispatcher.call("read", arrayOf(list), null, javaClass.classLoader))
        assertEquals(3, dispatcher.call("read", arrayOf(list), null, javaClass.classLoader))
        list.add(4)
        assertEquals(4, dispatcher.call("read", arrayOf(list), null, javaClass.classLoader))

        val calls = loopback.received.filterIsInstance<ClientMessage.Call>()
        assertTrue(calls[0].objects.single().elements != null)
        assertNull(calls[1].objects.single().elements, "second call omits content the service already holds")
        assertTrue(calls[2].objects.single().elements != null, "a changed collection is sent in full")
    }

    @Test
    fun `an operation failure reaches the script and changes nothing`() {
        val loopback = Loopback.ofArguments(mapOf("boom" to { _ -> error("no capacity") }))
        val dispatcher = client(loopback, spec("boom", any, collection("List", "T" to int)))

        val list = ListImpl(listOf(1))
        val error = assertFailsWith<ExternalCallException> { dispatcher.call("boom", arrayOf(list), null, javaClass.classLoader) }
        assertTrue(error.message!!.contains("no capacity"))
        assertEquals(listOf(1), list.heapSnapshot())
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `maps, ordered sets and new collections round trip`() {
        val loopback = Loopback.ofArguments(
            mapOf(
                "index" to { args ->
                    val map = args[0] as Map<Any?, Any?>
                    val ordered = args[1] as Set<Any?>
                    mutableListOf(mutableListOf(map.getValue("kept"), map.size), ordered.last())
                }
            )
        )
        val dispatcher = client(
            loopback,
            spec(
                "index", collection("List"),
                collection("Map", "K" to string, "V" to int), collection("OrderedSet", "T" to string)
            )
        )

        val map = MapImpl<String, Int>().apply { put("kept", 1); put("other", 2) }
        val ordered = OrderedSetImpl(listOf("a", "b"))

        val returned = dispatcher.call("index", arrayOf(map, ordered), null, javaClass.classLoader) as ListImpl<*>

        assertEquals(2, returned.size())
        assertEquals(listOf(1, 2), (returned.at(0) as ListImpl<*>).heapSnapshot())
        assertEquals("b", returned.at(1))
    }

    @Test
    fun `a result referring to a collection nobody holds is rejected`() {
        val loopback = Loopback(
            mapOf("noop" to { _ -> null }),
            rewriteAnswer = { answer -> (answer as ServiceMessage.Result).copy(value = WireValue.Ref(-42)) }
        )
        val dispatcher = client(loopback, spec("noop", any, collection("OrderedSet", "T" to string)))

        val error = assertFailsWith<ExternalCallException> {
            dispatcher.call("noop", arrayOf(OrderedSetImpl(listOf("a"))), null, javaClass.classLoader)
        }
        assertTrue(error.message!!.contains("unknown collection -42"), error.message)
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `returned collections take their declared kind at every level`() {
        val loopback = Loopback.ofArguments(
            mapOf(
                "groups" to { _ -> mutableListOf(mutableListOf(1, 2, 2), mutableListOf(3)) },
                "count" to { args -> (args[0] as List<Any?>).sumOf { (it as Collection<*>).size } }
            )
        )
        val groups = spec("groups", collection("List", "T" to collection("Set", "T" to int)))
        val count = spec("count", int, collection("List", "T" to collection("Set", "T" to int)))
        val dispatcher = client(loopback, groups, count)

        val returned = dispatcher.call("groups", arrayOf(), null, javaClass.classLoader) as ListImpl<*>
        val first = returned.at(0)
        assertTrue(first is SetImpl<*>, "got ${first?.javaClass}")
        assertEquals(setOf(1, 2), first.heapSnapshot().toSet())

        // Passed back, the rebuilt sets reach the service with their new content.
        assertEquals(3, dispatcher.call("count", arrayOf(returned), null, javaClass.classLoader))
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `collections the service created stay usable after a reconnect`() {
        val loopback = Loopback.ofArguments(
            mapOf(
                "make" to { args -> mutableListOf(args[0], args[0]) },
                "size" to { args -> (args[0] as List<Any?>).size }
            )
        )
        val make = spec("make", collection("List", "T" to int), int)
        val size = spec("size", int, collection("List", "T" to int))
        val dispatcher = client(loopback, make, size)

        val first = dispatcher.call("make", arrayOf(1), null, javaClass.classLoader) as ListImpl<*>

        // The new service picks the same id for its first collection as the lost one did.
        loopback.dropBeforeCall = 2
        val second = dispatcher.call("make", arrayOf(2), null, javaClass.classLoader) as ListImpl<*>

        assertEquals(listOf(1, 1), first.heapSnapshot())
        assertEquals(listOf(2, 2), second.heapSnapshot())
        assertEquals(2, dispatcher.call("size", arrayOf(first), null, javaClass.classLoader))
        assertEquals(2, dispatcher.call("size", arrayOf(second), null, javaClass.classLoader))
    }

    @Test
    fun `a call is sent again at most as often as the transport redials`() {
        val loopback = Loopback.ofArguments(mapOf("one" to { _ -> 1 }))
        loopback.dropBeforeEveryCall = true
        loopback.maxReconnects = 2
        val dispatcher = client(loopback, spec("one", int))

        val error = assertFailsWith<ExternalCallException> { dispatcher.call("one", arrayOf(), null, javaClass.classLoader) }
        assertTrue("kept reconnecting" in error.message!!, error.message)
        assertEquals(3, loopback.received.count { it is ClientMessage.Call }, "the first send and two resends")
    }

    @Test
    fun `a value the protocol cannot carry is refused before anything is sent`() {
        val loopback = Loopback.ofArguments(emptyMap())
        val dispatcher = client(loopback, spec("op", any, any))

        assertFailsWith<ExternalCallException> { dispatcher.call("op", arrayOf(Any()), null, javaClass.classLoader) }
        assertTrue(loopback.received.isEmpty())
    }
}
