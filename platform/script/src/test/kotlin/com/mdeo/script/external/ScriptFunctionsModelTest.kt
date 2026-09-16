package com.mdeo.script.external

import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.expression.ast.types.ReturnType
import com.mdeo.metamodel.Metamodel
import com.mdeo.metamodel.Model
import com.mdeo.metamodel.data.AssociationData
import com.mdeo.metamodel.data.AssociationEndData
import com.mdeo.metamodel.data.ClassData
import com.mdeo.metamodel.data.EnumData
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.metamodel.data.ModelData
import com.mdeo.metamodel.data.ModelDataInstance
import com.mdeo.metamodel.data.ModelDataLink
import com.mdeo.metamodel.data.ModelDataPropertyValue
import com.mdeo.metamodel.data.MultiplicityData
import com.mdeo.metamodel.data.PropertyData
import com.mdeo.script.ast.ExternalImplementation
import com.mdeo.script.compiler.ExternalCallSpec
import com.mdeo.script.stdlib.impl.collections.ListImpl
import com.mdeo.scriptfunctions.protocol.ClientMessage
import com.mdeo.scriptfunctions.protocol.Delta
import com.mdeo.scriptfunctions.protocol.ServiceMessage
import com.mdeo.scriptfunctions.protocol.WireValue
import com.mdeo.scriptfunctions.service.ScriptFunctionCall
import com.mdeo.scriptfunctions.service.ScriptModelInstance
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Models on the `script-functions` protocol: uploaded once per model, dropped with everything
 * derived from it when the script moves on to another, and never changed through a call.
 */
class ScriptFunctionsModelTest {

    private val metamodelData = MetamodelData(
        path = "/houses.mm",
        classes = listOf(
            ClassData(name = "Building", isAbstract = true),
            ClassData(
                name = "House", isAbstract = false, extends = listOf("Building"),
                properties = listOf(
                    PropertyData(name = "rooms", primitiveType = "int", multiplicity = MultiplicityData.single()),
                    PropertyData(name = "style", enumType = "Style", multiplicity = MultiplicityData.single())
                )
            ),
            ClassData(name = "Street", isAbstract = false)
        ),
        enums = listOf(EnumData(name = "Style", entries = listOf("Modern", "Classic"))),
        associations = listOf(
            AssociationData(
                source = AssociationEndData(className = "Street", name = "houses", multiplicity = MultiplicityData.many()),
                operator = "--",
                target = AssociationEndData(className = "House", name = "street", multiplicity = MultiplicityData.optional())
            )
        )
    )
    private val metamodel = Metamodel.compile(metamodelData)

    private fun model(rooms: Int = 3): Model = metamodel.loadModel(
        ModelData(
            metamodelPath = "/houses.mm",
            instances = listOf(
                ModelDataInstance("main", "Street", emptyMap()),
                ModelDataInstance(
                    "a", "House",
                    mapOf("rooms" to ModelDataPropertyValue.NumberValue(rooms.toDouble()), "style" to ModelDataPropertyValue.EnumValue("Modern"))
                ),
                ModelDataInstance("b", "House", mapOf("rooms" to ModelDataPropertyValue.NumberValue(5.0)))
            ),
            links = listOf(
                ModelDataLink("main", "houses", "a", "street"),
                ModelDataLink("main", "houses", "b", "street")
            )
        )
    )

    private val any = ClassTypeRef("builtin", "Any", true)
    private val int = ClassTypeRef("builtin", "int", false)
    private val house = ClassTypeRef("class/houses.mm", "House", false)

    private fun spec(operation: String, returnType: ReturnType, readsModel: Boolean, vararg params: ReturnType) =
        ExternalCallSpec(
            operation, operation, "", operation,
            if (readsModel) ExternalImplementation.MODEL_READONLY else ExternalImplementation.MODEL_NONE,
            params.toList(), returnType
        )

    private val totalRooms: (ScriptFunctionCall) -> Any? = { call ->
        call.model!!.instancesOf("Building").sumOf { it.attribute("rooms") as Int }
    }

    @Test
    fun `the service reads the model, including subclasses, attributes, enums and references`() {
        var seen = ""
        val loopback = Loopback(mapOf("inspect" to { call ->
            val model = call.model!!
            val a = model.instances.getValue("a")
            seen = "${model.instancesOf("Building").map { it.name }} ${a.attribute("style")} " +
                    "${model.instances.getValue("main").references("houses").map { it.name }} ${a.reference("street")?.name}"
            null
        }))
        val client = ScriptFunctionsClient(loopback, mapOf("inspect" to spec("inspect", any, true)))

        client.call("inspect", arrayOf(), model())

        assertEquals("[a, b] Modern [a, b] main", seen)
    }

    @Test
    fun `several calls on the same model content upload it once`() {
        val loopback = Loopback(mapOf("total" to totalRooms))
        val client = ScriptFunctionsClient(loopback, mapOf("total" to spec("total", int, true)))

        // An optimizer builds a new Model object for every guidance function on one solution.
        assertEquals(8, client.call("total", arrayOf(), model()))
        assertEquals(8, client.call("total", arrayOf(), model()))
        val same = model()
        assertEquals(8, client.call("total", arrayOf(), same))
        assertEquals(8, client.call("total", arrayOf(), same))

        assertEquals(1, loopback.received.count { it is ClientMessage.ModelPut })
    }

    @Test
    fun `a different model is uploaded in full and nothing derived from the previous one survives`() {
        val cachedOnEntry = mutableListOf<Any?>()
        val loopback = Loopback(mapOf("remember" to { call ->
            val model = call.model!!
            cachedOnEntry += model.cache["total"]
            model.cache["total"] = totalRooms(call)
            model.cache["total"]
        }))
        val client = ScriptFunctionsClient(loopback, mapOf("remember" to spec("remember", int, true)))
        val list = ListImpl(listOf(1, 2))
        val withList = ScriptFunctionsClient(
            loopback,
            mapOf("remember" to spec("remember", int, true, ClassTypeRef("builtin", "ReadonlyList", false, mapOf("T" to int))))
        )

        assertEquals(8, client.call("remember", arrayOf(), model(rooms = 3)))
        assertEquals(8, client.call("remember", arrayOf(), model(rooms = 3)))
        assertEquals(10, client.call("remember", arrayOf(), model(rooms = 5)))

        assertEquals(listOf<Any?>(null, 8, null), cachedOnEntry.toList(), "the cache lives exactly as long as its model")
        assertEquals(2, loopback.received.count { it is ClientMessage.ModelPut })

        // Collections the service held are dropped with the model too, so they are sent in full again.
        withList.call("remember", arrayOf(list), model(rooms = 5))
        withList.call("remember", arrayOf(list), model(rooms = 7))
        val calls = loopback.received.filterIsInstance<ClientMessage.Call>().takeLast(2)
        assertTrue(calls.all { it.objects.single().elements != null })
    }

    @Test
    fun `instances go out by name and come back as the script's own instances`() {
        val loopback = Loopback(mapOf("bigger" to { call ->
            val first = call.arguments[0] as ScriptModelInstance
            val second = call.arguments[1] as ScriptModelInstance
            if ((first.attribute("rooms") as Int) >= (second.attribute("rooms") as Int)) first else second
        }))
        val client = ScriptFunctionsClient(loopback, mapOf("bigger" to spec("bigger", house, false, house, house)))
        val model = model()
        val a = model.instancesByName.getValue("a")
        val b = model.instancesByName.getValue("b")

        assertSame(b, client.call("bigger", arrayOf(a, b), model))
        assertEquals(1, loopback.received.count { it is ClientMessage.ModelPut }, "instances need the model")
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `a result referring to an instance outside the model is rejected and nothing is applied`() {
        val fill: (ScriptFunctionCall) -> Any? = { call ->
            (call.arguments[0] as MutableList<Any?>).add(call.model!!.instances.getValue("a"))
            null
        }
        val loopback = Loopback(
            mapOf("fill" to fill),
            rewriteAnswer = { answer ->
                if (answer is ServiceMessage.Result) {
                    answer.copy(deltas = answer.deltas.map { delta ->
                        if (delta is Delta.Splice) delta.copy(insert = listOf(WireValue.InstanceValue("ghost"))) else delta
                    })
                } else answer
            }
        )
        val listOfAny = ClassTypeRef("builtin", "List", false, mapOf("T" to any))
        val client = ScriptFunctionsClient(loopback, mapOf("fill" to spec("fill", any, true, listOfAny)))
        val list = ListImpl<Any?>()

        val error = assertFailsWith<ExternalCallException> { client.call("fill", arrayOf(list), model()) }
        assertTrue(error.message!!.contains("ghost"))
        assertEquals(0, list.size())
    }

    @Test
    fun `a service that lost the model gets it again`() {
        val loopback = Loopback(mapOf("total" to totalRooms))
        val client = ScriptFunctionsClient(loopback, mapOf("total" to spec("total", int, true)))
        val model = model()

        assertEquals(8, client.call("total", arrayOf(), model))
        loopback.service.clear() // as after a reconnect
        assertEquals(8, client.call("total", arrayOf(), model))

        assertEquals(2, loopback.received.count { it is ClientMessage.ModelPut })
        assertTrue(loopback.answered.any { it is ServiceMessage.Failure && it.code == ServiceMessage.Failure.UNKNOWN_MODEL })
    }

    @Test
    fun `a function that reads the model cannot be called without one`() {
        val loopback = Loopback(mapOf("total" to totalRooms))
        val client = ScriptFunctionsClient(loopback, mapOf("total" to spec("total", int, true)))

        assertFailsWith<ExternalCallException> { client.call("total", arrayOf(), null) }
        assertTrue(loopback.received.isEmpty())
        assertNull(loopback.service.currentModel)
    }
}
