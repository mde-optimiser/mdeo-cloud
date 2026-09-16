package com.mdeo.script.external

import com.mdeo.scriptfunctions.protocol.ClientMessage
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
 * Copy-restore over the `script-functions` protocol, through the real encoding, against the
 * in-process [Loopback] service.
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

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `a list, a set and a bag come back changed, and only they come back`() {
        val loopback = Loopback(
            mapOf(
                "shuffle" to { args ->
                    val list = args[0] as MutableList<Any?>
                    list.reverse()
                    list.add(99)
                    (args[1] as MutableSet<Any?>).add("c")
                    (args[2] as MutableList<Any?>).add("x")
                    null
                }
            )
        )
        val spec = spec(
            "shuffle", ClassTypeRef("builtin", "void", false),
            collection("List", "T" to int), collection("Set", "T" to string), collection("Bag", "T" to string),
            collection("List", "T" to int)
        )
        val dispatcher = client(loopback, spec)

        val list = ListImpl(listOf(1, 2, 3))
        val set = SetImpl(listOf("a", "b"))
        val bag = BagImpl(listOf("x", "y"))
        val untouched = ListImpl(listOf(7, 8))

        dispatcher.call("shuffle", arrayOf(list, set, bag, untouched))

        assertEquals(listOf(3, 2, 1, 99), list.deltaSnapshot())
        assertEquals(setOf("a", "b", "c"), set.deltaSnapshot().toSet())
        assertEquals(2, bag.count("x"))
        assertEquals(listOf(7, 8), untouched.deltaSnapshot())

        val result = loopback.answered.single() as ServiceMessage.Result
        val changedIds = result.deltas.map { it.id }.toSet()
        assertEquals(3, changedIds.size, "only the three changed collections produce deltas")
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `the same list passed twice is one list on the other side`() {
        var sameOnService = false
        val loopback = Loopback(
            mapOf(
                "alias" to { args ->
                    sameOnService = args[0] === args[1]
                    (args[0] as MutableList<Any?>).add(4)
                    null
                }
            )
        )
        val listType = collection("List", "T" to int)
        val dispatcher = client(loopback, spec("alias", any, listType, listType))

        val list = ListImpl(listOf(1, 2, 3))
        dispatcher.call("alias", arrayOf(list, list))

        assertTrue(sameOnService)
        assertEquals(listOf(1, 2, 3, 4), list.deltaSnapshot())
        val call = loopback.received.single() as ClientMessage.Call
        assertEquals(1, call.objects.size, "an aliased collection is sent once")
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `a list that contains itself survives the trip`() {
        var cycleOnService = false
        val loopback = Loopback(
            mapOf(
                "cycle" to { args ->
                    val outer = args[0] as MutableList<Any?>
                    cycleOnService = outer[1] === outer
                    outer.add("tail")
                    outer
                }
            )
        )
        val dispatcher = client(loopback, spec("cycle", any, collection("List")))

        val list = ListImpl<Any?>()
        list.add("head")
        list.add(list)

        val returned = dispatcher.call("cycle", arrayOf(list))

        assertTrue(cycleOnService)
        assertSame(list, returned, "returning an argument returns the very same collection")
        assertEquals(3, list.size())
        assertSame(list, list.at(1))
    }

    @Test
    fun `an unchanged collection is sent again as just its id`() {
        val loopback = Loopback(mapOf("read" to { args -> (args[0] as List<*>).size }))
        val dispatcher = client(loopback, spec("read", int, collection("ReadonlyList", "T" to int)))

        val list = ListImpl(listOf(1, 2, 3))
        assertEquals(3, dispatcher.call("read", arrayOf(list)))
        assertEquals(3, dispatcher.call("read", arrayOf(list)))
        list.add(4)
        assertEquals(4, dispatcher.call("read", arrayOf(list)))

        val calls = loopback.received.filterIsInstance<ClientMessage.Call>()
        assertTrue(calls[0].objects.single().elements != null)
        assertNull(calls[1].objects.single().elements, "second call omits content the service already holds")
        assertTrue(calls[2].objects.single().elements != null, "a changed collection is sent in full")
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `changing a readonly argument rejects the whole result and applies nothing`() {
        val loopback = Loopback(
            mapOf(
                "sneaky" to { args ->
                    (args[0] as MutableList<Any?>).add(1)
                    (args[1] as MutableList<Any?>).add(1)
                    null
                }
            ),
            claimEverythingMutable = true
        )
        val dispatcher = client(
            loopback,
            spec("sneaky", any, collection("List", "T" to int), collection("ReadonlyList", "T" to int))
        )

        val writable = ListImpl<Int>()
        val readonly = ListImpl<Int>()

        val error = assertFailsWith<ExternalCallException> { dispatcher.call("sneaky", arrayOf(writable, readonly)) }
        assertTrue(error.message!!.contains("readonly"))
        assertEquals(0, writable.size(), "the legitimate change is not applied either")
        assertEquals(0, readonly.size())
    }

    @Test
    fun `an operation failure reaches the script and changes nothing`() {
        val loopback = Loopback(mapOf("boom" to { _ -> error("no capacity") }))
        val dispatcher = client(loopback, spec("boom", any, collection("List", "T" to int)))

        val list = ListImpl(listOf(1))
        val error = assertFailsWith<ExternalCallException> { dispatcher.call("boom", arrayOf(list)) }
        assertTrue(error.message!!.contains("no capacity"))
        assertEquals(listOf(1), list.deltaSnapshot())
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `maps, ordered sets and new collections round trip`() {
        val loopback = Loopback(
            mapOf(
                "index" to { args ->
                    val map = args[0] as MutableMap<Any?, Any?>
                    map.remove("gone")
                    map["new"] = 3
                    (args[1] as MutableSet<Any?>).add("z")
                    mutableListOf(mutableListOf(1, 2), 3)
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

        val map = MapImpl<String, Int>().apply { put("kept", 1); put("gone", 2) }
        val ordered = OrderedSetImpl(listOf("a", "b"))

        val returned = dispatcher.call("index", arrayOf(map, ordered)) as ListImpl<*>

        assertEquals(listOf("kept" to 1, "new" to 3), map.deltaEntries())
        assertEquals(listOf("a", "b", "z"), ordered.deltaSnapshot())
        assertEquals(2, returned.size())
        assertEquals(listOf(1, 2), (returned.at(0) as ListImpl<*>).deltaSnapshot())
    }

    @Test
    fun `a value the protocol cannot carry is refused before anything is sent`() {
        val loopback = Loopback(emptyMap())
        val dispatcher = client(loopback, spec("op", any, any))

        assertFailsWith<ExternalCallException> { dispatcher.call("op", arrayOf(Any())) }
        assertTrue(loopback.received.isEmpty())
    }
}
