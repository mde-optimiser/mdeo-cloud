package com.mdeo.scriptfunctions.service

import com.mdeo.scriptfunctions.protocol.ClientMessage
import com.mdeo.scriptfunctions.protocol.HeapKind
import com.mdeo.scriptfunctions.protocol.HeapObject
import com.mdeo.scriptfunctions.protocol.ServiceMessage
import com.mdeo.scriptfunctions.protocol.WireAssociation
import com.mdeo.scriptfunctions.protocol.WireAssociationEnd
import com.mdeo.scriptfunctions.protocol.WireClass
import com.mdeo.scriptfunctions.protocol.WireInstance
import com.mdeo.scriptfunctions.protocol.WireMetamodel
import com.mdeo.scriptfunctions.protocol.WireModel
import com.mdeo.scriptfunctions.protocol.WireValue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The service side on its own, fed hand-written messages, for the cases a well-behaved client
 * never produces.
 */
class ScriptFunctionsServiceSessionTest {

    private fun list(id: Long, vararg values: Int) =
        HeapObject(id, HeapKind.LIST, version = 1, elements = values.map { WireValue.IntValue(it) })

    /**
     * A session that already holds the metamodel of [street], as every execution sends it first.
     */
    private fun session(vararg operations: Pair<String, ScriptFunctionOperation>) =
        ScriptFunctionsServiceSession(mapOf(*operations)).also {
            runBlocking { it.handle(ClientMessage.MetamodelPut(houses)) }
        }

    private val size = "size" to ScriptFunctionOperation { call -> call.argument<List<Any?>>(0).size }

    @Test
    fun `a double stays a double`() = runBlocking {
        val service = session("same" to ScriptFunctionOperation { call -> call.arguments[0] })
        val answer = service.handle(ClientMessage.Call(1, "same", emptyList(), listOf(WireValue.DoubleValue(2.0))))
        assertEquals(WireValue.DoubleValue(2.0), assertIs<ServiceMessage.Result>(answer).value)
    }

    @Test
    fun `an operation cannot change a collection it is given`() = runBlocking {
        val service = session("append" to ScriptFunctionOperation { call ->
            @Suppress("UNCHECKED_CAST")
            (call.arguments[0] as MutableList<Any?>).add(4)
            null
        })
        val answer = service.handle(ClientMessage.Call(1, "append", listOf(list(1, 1, 2, 3)), listOf(WireValue.Ref(1))))
        assertTrue(assertIs<ServiceMessage.Failure>(answer).message.contains("readonly"))
    }

    @Test
    fun `returning an argument returns it by id`() = runBlocking {
        val service = session("same" to ScriptFunctionOperation { call -> call.arguments[0] })
        val answer = assertIs<ServiceMessage.Result>(
            service.handle(ClientMessage.Call(1, "same", listOf(list(1, 1, 2)), listOf(WireValue.Ref(1))))
        )
        assertEquals(WireValue.Ref(1), answer.value)
        assertTrue(answer.objects.isEmpty())
    }

    @Test
    fun `a collection sent without content that is not held asks for a resend`() = runBlocking {
        val service = session(size)
        val answer = service.handle(
            ClientMessage.Call(1, "size", listOf(HeapObject(5, HeapKind.LIST, 1)), listOf(WireValue.Ref(5)))
        )
        assertEquals(ServiceMessage.Failure.UNKNOWN_OBJECT, assertIs<ServiceMessage.Failure>(answer).code)
    }

    @Test
    fun `a failed call keeps the collections other collections still hold`() = runBlocking {
        val outer = HeapObject(1, HeapKind.LIST, 1, elements = listOf(WireValue.Ref(2)))
        val inner = list(2, 1)
        val service = session(
            "boom" to ScriptFunctionOperation { error("no capacity") },
            "firstSize" to ScriptFunctionOperation { call -> (call.argument<List<Any?>>(0)[0] as List<*>).size }
        )
        service.handle(ClientMessage.Call(1, "firstSize", listOf(outer, inner), listOf(WireValue.Ref(1))))

        val failed = service.handle(ClientMessage.Call(2, "boom", listOf(list(2, 1, 4)), listOf(WireValue.Ref(2))))
        assertEquals("no capacity", assertIs<ServiceMessage.Failure>(failed).message)

        // The outer list is sent by id; the inner one again in full, as a client does after a failure.
        val answer = service.handle(
            ClientMessage.Call(
                3, "firstSize",
                listOf(HeapObject(1, HeapKind.LIST, 1), list(2, 1, 4)),
                listOf(WireValue.Ref(1))
            )
        )
        assertEquals(WireValue.IntValue(2), assertIs<ServiceMessage.Result>(answer).value)
    }

    @Test
    fun `a new collection never takes an id the execution still uses`() = runBlocking {
        val service = session("fresh" to ScriptFunctionOperation { mutableListOf(1) })
        val answer = service.handle(ClientMessage.Call(1, "fresh", listOf(list(-1, 7)), listOf(WireValue.Ref(-1))))
        val created = assertIs<ServiceMessage.Result>(answer).objects.single()
        assertTrue(created.id < -1, "got ${created.id}")
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
        val service = session(size)
        service.handle(ClientMessage.Call(1, "size", listOf(list(1, 1)), listOf(WireValue.Ref(1))))
        assertNull(service.handle(ClientMessage.Release(listOf(1))))
        val answer = service.handle(
            ClientMessage.Call(2, "size", listOf(HeapObject(1, HeapKind.LIST, 1)), listOf(WireValue.Ref(1)))
        )
        assertEquals(ServiceMessage.Failure.UNKNOWN_OBJECT, assertIs<ServiceMessage.Failure>(answer).code)
    }

    @Test
    fun `sets, bags and maps arrive readonly, in the order they were sent`() = runBlocking {
        val service = session("describe" to ScriptFunctionOperation { call ->
            val set = call.argument<Set<Any?>>(0)
            val bag = call.argument<List<Any?>>(1)
            val map = call.argument<Map<Any?, Any?>>(2)
            "$set $bag $map"
        }, "clear" to ScriptFunctionOperation { call ->
            @Suppress("UNCHECKED_CAST")
            (call.arguments[0] as MutableMap<Any?, Any?>).clear()
            null
        })
        fun s(v: String) = WireValue.StringValue(v)
        val objects = listOf(
            HeapObject(1, HeapKind.SET, 1, elements = listOf(s("a"), s("b"))),
            HeapObject(2, HeapKind.BAG, 1, elements = listOf(s("x"), s("x"))),
            HeapObject(3, HeapKind.MAP, 1, entries = listOf(s("k"), WireValue.IntValue(1), s("gone"), WireValue.IntValue(0)))
        )
        val answer = service.handle(
            ClientMessage.Call(1, "describe", objects, listOf(WireValue.Ref(1), WireValue.Ref(2), WireValue.Ref(3)))
        )
        assertEquals(s("[a, b] [x, x] {k=1, gone=0}"), assertIs<ServiceMessage.Result>(answer).value)

        val cleared = service.handle(
            ClientMessage.Call(2, "clear", listOf(HeapObject(3, HeapKind.MAP, 1)), listOf(WireValue.Ref(3)))
        )
        assertTrue(assertIs<ServiceMessage.Failure>(cleared).message.contains("readonly"))
    }

    private val houses = WireMetamodel(
        path = "/houses.mm",
        classes = listOf(
            WireClass("Building", isAbstract = true),
            WireClass("House", extends = listOf("Building")),
            WireClass("Street")
        ),
        associations = listOf(
            // Both ends have a property: every link is listed by both instances.
            WireAssociation(WireAssociationEnd("Street", "houses"), "<-->", WireAssociationEnd("House", "street", upper = 1)),
            // Only the target end has one.
            WireAssociation(WireAssociationEnd("House"), "<--", WireAssociationEnd("House", "neighbours")),
            WireAssociation(WireAssociationEnd("Street", "crosses"), "-->", WireAssociationEnd("Street"))
        ),
        subtypes = mapOf("Building" to listOf("Building", "House"), "House" to listOf("House"), "Street" to listOf("Street"))
    )

    private val street = WireModel(
        metamodelPath = "/houses.mm",
        instances = listOf(
            WireInstance("a", "House", attributes = mapOf("rooms" to listOf(WireValue.IntValue(3)))),
            WireInstance("b", "House", attributes = mapOf("rooms" to listOf(WireValue.IntValue(5))))
        )
    )

    @Test
    fun `every link is listed once, whichever of its ends have a property`() = runBlocking {
        val service = session()
        val linked = WireModel(
            metamodelPath = "/houses.mm",
            instances = listOf(
                WireInstance("main", "Street", references = mapOf("houses" to listOf("a", "b"), "crosses" to listOf("side"))),
                WireInstance("side", "Street"),
                WireInstance("a", "House", references = mapOf("street" to listOf("main"), "neighbours" to listOf("b"))),
                WireInstance("b", "House", references = mapOf("street" to listOf("main"), "neighbours" to listOf("a")))
            )
        )
        service.handle(ClientMessage.ModelPut(1, linked))

        val links = service.currentModel!!.links
        assertEquals(
            listOf("main houses a", "main houses b", "b neighbours a", "a neighbours b", "main crosses side"),
            links.map { "${it.source.name} ${it.association.source.name ?: it.association.target.name} ${it.target.name}" }
        )
        assertTrue(links.all { it.association in service.currentModel!!.metamodel.associations })
    }

    @Test
    fun `the metamodel is kept across models, and a model without it asks for an upload`() = runBlocking {
        val service = ScriptFunctionsServiceSession(mapOf("classes" to ScriptFunctionOperation { call ->
            call.model!!.metamodel.classes.size
        }))
        service.handle(ClientMessage.ModelPut(1, street))
        val missing = service.handle(ClientMessage.Call(1, "classes", emptyList(), emptyList(), modelId = 1))
        assertEquals(ServiceMessage.Failure.UNKNOWN_MODEL, assertIs<ServiceMessage.Failure>(missing).code)

        service.handle(ClientMessage.MetamodelPut(houses))
        service.handle(ClientMessage.ModelPut(2, street))
        val metamodel = service.currentModel!!.metamodel
        metamodel.cache["derived"] = true
        service.handle(ClientMessage.ModelPut(3, street))

        assertTrue(service.currentModel!!.metamodel === metamodel)
        assertEquals(true, metamodel.cache["derived"], "the metamodel's cache outlives every model")
        val answer = service.handle(ClientMessage.Call(2, "classes", emptyList(), emptyList(), modelId = 3))
        assertEquals(WireValue.IntValue(3), assertIs<ServiceMessage.Result>(answer).value)
    }

    @Test
    fun `a metamodel sent again drops the model built on the one it replaces`() = runBlocking {
        val service = session("count" to ScriptFunctionOperation { call -> call.model!!.instances.size })
        service.handle(ClientMessage.ModelPut(1, street))

        service.handle(ClientMessage.MetamodelPut(houses.copy(path = "/other.mm")))
        assertNotNull(service.currentModel, "a metamodel under another path leaves the model alone")
        service.handle(ClientMessage.MetamodelPut(houses))

        assertNull(service.currentModel)
        val answer = service.handle(ClientMessage.Call(1, "count", emptyList(), emptyList(), modelId = 1))
        assertEquals(ServiceMessage.Failure.UNKNOWN_MODEL, assertIs<ServiceMessage.Failure>(answer).code)
    }

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
        val service = session(size)
        service.handle(ClientMessage.ModelPut(1, street))
        val first = service.currentModel!!
        first.cache["index"] = "built"
        service.handle(ClientMessage.Call(1, "size", listOf(list(1, 1)), listOf(WireValue.Ref(1)), modelId = 1))

        service.handle(ClientMessage.ModelPut(2, street))

        assertTrue(service.currentModel !== first)
        assertTrue(service.currentModel!!.cache.isEmpty())
        val resent = service.handle(
            ClientMessage.Call(2, "size", listOf(HeapObject(1, HeapKind.LIST, 1)), listOf(WireValue.Ref(1)), modelId = 2)
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

    @Test
    fun `records arrive as values and go back whole`() = runBlocking {
        val service = session("move" to ScriptFunctionOperation { call ->
            val point = call.argument<RecordValue>(0)
            RecordValue("Point", mapOf("x" to (point["x"] as Double) + 1, "label" to point["label"]))
        })
        val answer = service.handle(
            ClientMessage.Call(
                1, "move", emptyList(),
                listOf(WireValue.RecordValue("Point", mapOf("x" to WireValue.DoubleValue(1.5), "label" to WireValue.StringValue("a"))))
            )
        )
        assertEquals(
            WireValue.RecordValue("Point", mapOf("x" to WireValue.DoubleValue(2.5), "label" to WireValue.StringValue("a"))),
            assertIs<ServiceMessage.Result>(answer).value
        )
    }

    @Test
    fun `a handle stands for the same state until it is released`() = runBlocking {
        val state = StringBuilder("index")
        val service = session(
            "build" to ScriptFunctionOperation { OpaqueValue("Index", state) },
            "use" to ScriptFunctionOperation { call -> assertTrue(call.arguments[0] === state); call.arguments[0] }
        )
        val built = assertIs<ServiceMessage.Result>(service.handle(ClientMessage.Call(1, "build", emptyList(), emptyList()))).value
        val handle = assertIs<WireValue.HandleValue>(built)

        val used = service.handle(ClientMessage.Call(2, "use", emptyList(), listOf(handle)))
        assertEquals(handle, assertIs<ServiceMessage.Result>(used).value, "returning the state returns the same handle")

        service.handle(ClientMessage.Release(emptyList(), handles = listOf(handle.id)))
        val released = service.handle(ClientMessage.Call(3, "use", emptyList(), listOf(handle)))
        assertTrue(assertIs<ServiceMessage.Failure>(released).message.contains("no longer held"))
    }

    @Test
    fun `a new model drops every handle`() = runBlocking {
        val service = session("build" to ScriptFunctionOperation { OpaqueValue("Index", Any()) }, "use" to ScriptFunctionOperation { null })
        service.handle(ClientMessage.ModelPut(1, street))
        val handle = assertIs<WireValue.HandleValue>(
            assertIs<ServiceMessage.Result>(service.handle(ClientMessage.Call(1, "build", emptyList(), emptyList(), modelId = 1))).value
        )
        service.handle(ClientMessage.ModelPut(2, street))
        val answer = service.handle(ClientMessage.Call(2, "use", emptyList(), listOf(handle), modelId = 2))
        assertIs<ServiceMessage.Failure>(answer)
    }
}
