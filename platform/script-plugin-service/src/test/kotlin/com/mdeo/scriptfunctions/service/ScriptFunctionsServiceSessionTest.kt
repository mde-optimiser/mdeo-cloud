package com.mdeo.scriptfunctions.service

import com.mdeo.scriptfunctions.protocol.ClientMessage
import com.mdeo.scriptfunctions.protocol.Delta
import com.mdeo.scriptfunctions.protocol.HeapKind
import com.mdeo.scriptfunctions.protocol.HeapObject
import com.mdeo.scriptfunctions.protocol.ServiceMessage
import com.mdeo.scriptfunctions.protocol.WireInstance
import com.mdeo.scriptfunctions.protocol.WireModel
import com.mdeo.scriptfunctions.protocol.WireValue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The service side on its own, fed hand-written messages, for the cases a well-behaved client
 * never produces.
 */
class ScriptFunctionsServiceSessionTest {

    private fun list(id: Long, vararg values: Int, mutable: Boolean = true) =
        HeapObject(id, HeapKind.LIST, version = 1, mutable = mutable, elements = values.map { WireValue.IntValue(it) })

    private fun session(vararg operations: Pair<String, ScriptFunctionOperation>) =
        ScriptFunctionsServiceSession(mapOf(*operations))

    @Suppress("UNCHECKED_CAST")
    private val append = "append" to ScriptFunctionOperation { call ->
        call.argument<MutableList<Any?>>(0).add(4)
        null
    }

    @Test
    fun `a double stays a double`() = runBlocking {
        val service = session("same" to ScriptFunctionOperation { call -> call.arguments[0] })
        val answer = service.handle(ClientMessage.Call(1, "same", emptyList(), listOf(WireValue.DoubleValue(2.0))))
        assertEquals(WireValue.DoubleValue(2.0), assertIs<ServiceMessage.Result>(answer).value)
    }

    @Test
    fun `only the appended element comes back`() = runBlocking {
        val service = session(append)
        val answer = service.handle(ClientMessage.Call(1, "append", listOf(list(1, 1, 2, 3)), listOf(WireValue.Ref(1))))
        val delta = assertIs<ServiceMessage.Result>(answer).deltas.single()
        assertEquals(Delta.Splice(1, 3, 0, listOf(WireValue.IntValue(4))), delta)
    }

    @Test
    fun `changing a readonly collection fails the call`() = runBlocking {
        val service = session(append)
        val answer = service.handle(
            ClientMessage.Call(1, "append", listOf(list(1, 1, mutable = false)), listOf(WireValue.Ref(1)))
        )
        assertTrue(assertIs<ServiceMessage.Failure>(answer).message.contains("readonly"))
    }

    @Test
    fun `a collection sent without content that is not held asks for a resend`() = runBlocking {
        val service = session(append)
        val answer = service.handle(
            ClientMessage.Call(1, "append", listOf(HeapObject(5, HeapKind.LIST, 1, true)), listOf(WireValue.Ref(5)))
        )
        assertEquals(ServiceMessage.Failure.UNKNOWN_OBJECT, assertIs<ServiceMessage.Failure>(answer).code)
    }

    @Test
    fun `a failed call forgets the collections it was sent`() = runBlocking {
        val service = session("boom" to ScriptFunctionOperation { error("no capacity") }, append)
        val failed = service.handle(ClientMessage.Call(1, "boom", listOf(list(1, 1)), listOf(WireValue.Ref(1))))
        assertEquals("no capacity", assertIs<ServiceMessage.Failure>(failed).message)

        val retried = service.handle(
            ClientMessage.Call(2, "append", listOf(HeapObject(1, HeapKind.LIST, 1, true)), listOf(WireValue.Ref(1)))
        )
        assertEquals(ServiceMessage.Failure.UNKNOWN_OBJECT, assertIs<ServiceMessage.Failure>(retried).code)
    }

    @Test
    fun `unknown operations and unsendable results fail with a message`() = runBlocking {
        val service = session("weird" to ScriptFunctionOperation { Any() })
        val unknown = service.handle(ClientMessage.Call(1, "missing", emptyList(), emptyList()))
        assertTrue(assertIs<ServiceMessage.Failure>(unknown).message.contains("Unknown operation"))
        val weird = service.handle(ClientMessage.Call(2, "weird", emptyList(), emptyList()))
        assertTrue(assertIs<ServiceMessage.Failure>(weird).message.contains("cannot be sent"))
    }

    @Test
    fun `a released collection is no longer held`() = runBlocking {
        val service = session(append)
        service.handle(ClientMessage.Call(1, "append", listOf(list(1, 1)), listOf(WireValue.Ref(1))))
        assertNull(service.handle(ClientMessage.Release(listOf(1))))
        val answer = service.handle(
            ClientMessage.Call(2, "append", listOf(HeapObject(1, HeapKind.LIST, 2, true)), listOf(WireValue.Ref(1)))
        )
        assertEquals(ServiceMessage.Failure.UNKNOWN_OBJECT, assertIs<ServiceMessage.Failure>(answer).code)
    }

    @Test
    fun `sets, bags and maps produce their own deltas`() = runBlocking {
        @Suppress("UNCHECKED_CAST")
        val service = session("edit" to ScriptFunctionOperation { call ->
            (call.arguments[0] as MutableSet<Any?>).apply { remove("a"); add("c") }
            (call.arguments[1] as MutableList<Any?>).add("x")
            (call.arguments[2] as MutableMap<Any?, Any?>).apply { remove("gone"); put("k", 2) }
            null
        })
        fun s(v: String) = WireValue.StringValue(v)
        val objects = listOf(
            HeapObject(1, HeapKind.SET, 1, true, elements = listOf(s("a"), s("b"))),
            HeapObject(2, HeapKind.BAG, 1, true, elements = listOf(s("x"))),
            HeapObject(3, HeapKind.MAP, 1, true, entries = listOf(s("k"), WireValue.IntValue(1), s("gone"), WireValue.IntValue(0)))
        )
        val answer = service.handle(ClientMessage.Call(1, "edit", objects, listOf(WireValue.Ref(1), WireValue.Ref(2), WireValue.Ref(3))))
        assertEquals(
            listOf(
                Delta.Remove(1, listOf(s("a"))),
                Delta.Add(1, listOf(s("c"))),
                Delta.Count(2, s("x"), 2),
                Delta.RemoveKey(3, s("gone")),
                Delta.Put(3, s("k"), WireValue.IntValue(2))
            ),
            assertIs<ServiceMessage.Result>(answer).deltas
        )
    }

    private val street = WireModel(
        metamodelPath = "/houses.mm",
        subtypes = mapOf("Building" to listOf("Building", "House"), "House" to listOf("House")),
        instances = listOf(
            WireInstance("a", "House", attributes = mapOf("rooms" to listOf(WireValue.IntValue(3)))),
            WireInstance("b", "House", attributes = mapOf("rooms" to listOf(WireValue.IntValue(5))))
        )
    )

    @Test
    fun `a call names the model it works on, and a stale name asks for an upload`() = runBlocking {
        val service = session("count" to ScriptFunctionOperation { call -> call.model!!.instancesOf("Building").size })
        assertNull(service.handle(ClientMessage.ModelPut(7, street)))

        val answer = service.handle(ClientMessage.Call(1, "count", emptyList(), emptyList(), modelId = 7))
        assertEquals(WireValue.IntValue(2), assertIs<ServiceMessage.Result>(answer).value)

        val stale = service.handle(ClientMessage.Call(2, "count", emptyList(), emptyList(), modelId = 6))
        assertEquals(ServiceMessage.Failure.UNKNOWN_MODEL, assertIs<ServiceMessage.Failure>(stale).code)
    }

    @Test
    fun `a new model drops the old one, its cache and every collection`() = runBlocking {
        val service = session(append)
        service.handle(ClientMessage.ModelPut(1, street))
        val first = service.currentModel!!
        first.cache["index"] = "built"
        service.handle(ClientMessage.Call(1, "append", listOf(list(1, 1)), listOf(WireValue.Ref(1)), modelId = 1))

        service.handle(ClientMessage.ModelPut(2, street))

        assertTrue(service.currentModel !== first)
        assertTrue(service.currentModel!!.cache.isEmpty())
        val resent = service.handle(
            ClientMessage.Call(2, "append", listOf(HeapObject(1, HeapKind.LIST, 2, true)), listOf(WireValue.Ref(1)), modelId = 2)
        )
        assertEquals(ServiceMessage.Failure.UNKNOWN_OBJECT, assertIs<ServiceMessage.Failure>(resent).code)
    }

    @Test
    fun `instances arrive as the model's own objects and go back by name`() = runBlocking {
        val service = session("other" to ScriptFunctionOperation { call ->
            val given = call.argument<ScriptModelInstance>(0)
            assertTrue(given === call.model!!.instances[given.name])
            call.model.instances.values.first { it !== given }
        })
        service.handle(ClientMessage.ModelPut(1, street))
        val answer = service.handle(
            ClientMessage.Call(1, "other", emptyList(), listOf(WireValue.InstanceValue("a")), modelId = 1)
        )
        assertEquals(WireValue.InstanceValue("b"), assertIs<ServiceMessage.Result>(answer).value)
    }

    @Test
    fun `an instance of a model the call does not work on cannot be returned`() = runBlocking {
        var kept: ScriptModelInstance? = null
        val service = session("keep" to ScriptFunctionOperation { call ->
            kept ?: call.model!!.instances.getValue("a").also { kept = it }
        })
        service.handle(ClientMessage.ModelPut(1, street))
        service.handle(ClientMessage.Call(1, "keep", emptyList(), emptyList(), modelId = 1))
        service.handle(ClientMessage.ModelPut(2, street))

        val answer = service.handle(ClientMessage.Call(2, "keep", emptyList(), emptyList(), modelId = 2))
        assertTrue(assertIs<ServiceMessage.Failure>(answer).message.contains("model the call does not work on"))
    }
}
