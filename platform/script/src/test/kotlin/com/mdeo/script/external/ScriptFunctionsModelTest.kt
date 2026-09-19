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
import com.mdeo.scriptfunctions.protocol.ServiceMessage
import com.mdeo.scriptfunctions.protocol.WireValue
import com.mdeo.scriptfunctions.service.ScriptFunctionCall
import com.mdeo.scriptfunctions.service.ScriptEnumValue
import com.mdeo.scriptfunctions.service.ScriptModelInstance
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Models on the `script-functions` protocol: their metamodel sent once per connection, each model
 * uploaded once, dropped with everything derived from it when the script moves on to another, and
 * never changed through a call.
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

        client.call("inspect", arrayOf(), model(), javaClass.classLoader)

        assertEquals("[a, b] Style.Modern [a, b] main", seen)
    }

    @Test
    fun `the service reads the metamodel, and counts each link once`() {
        var seen = ""
        val loopback = Loopback(mapOf("inspect" to { call ->
            val model = call.model!!
            val metamodel = model.metamodel
            val house = metamodel.classes.getValue("House")
            val houses = metamodel.associations.single()
            seen = "${metamodel.path} ${metamodel.subtypesOf("Building")} ${house.extends} " +
                    "${house.attributes.map { "${it.name}:${it.type}${if (it.isEnum) "!" else ""}" }} ${metamodel.enums} " +
                    "${houses.source.name}${houses.operator}${houses.target.name} " +
                    "${model.links.map { "${it.source.name}-${it.target.name}" }}"
            null
        }))
        val client = ScriptFunctionsClient(loopback, mapOf("inspect" to spec("inspect", any, true)))

        client.call("inspect", arrayOf(), model(), javaClass.classLoader)

        // Both ends of houses/street have a property, so each instance lists the link; it is one link.
        assertEquals(
            "/houses.mm [Building, House] [Building] [rooms:int, style:Style!] {Style=[Modern, Classic]} houses--street [main-a, main-b]",
            seen
        )
    }

    @Test
    fun `several calls on the same model content upload it once`() {
        val loopback = Loopback(mapOf("total" to totalRooms))
        val client = ScriptFunctionsClient(loopback, mapOf("total" to spec("total", int, true)))

        // An optimizer builds a new Model object for every guidance function on one solution.
        assertEquals(8, client.call("total", arrayOf(), model(), javaClass.classLoader))
        assertEquals(8, client.call("total", arrayOf(), model(), javaClass.classLoader))
        val same = model()
        assertEquals(8, client.call("total", arrayOf(), same, javaClass.classLoader))
        assertEquals(8, client.call("total", arrayOf(), same, javaClass.classLoader))

        assertEquals(1, loopback.received.count { it is ClientMessage.MetamodelPut })
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

        assertEquals(8, client.call("remember", arrayOf(), model(rooms = 3), javaClass.classLoader))
        assertEquals(8, client.call("remember", arrayOf(), model(rooms = 3), javaClass.classLoader))
        assertEquals(10, client.call("remember", arrayOf(), model(rooms = 5), javaClass.classLoader))

        assertEquals(listOf<Any?>(null, 8, null), cachedOnEntry.toList(), "the cache lives exactly as long as its model")
        assertEquals(2, loopback.received.count { it is ClientMessage.ModelPut })
        assertEquals(1, loopback.received.count { it is ClientMessage.MetamodelPut }, "the metamodel is sent once")

        // Collections the service held are dropped with the model too, so they are sent in full again.
        withList.call("remember", arrayOf(list), model(rooms = 5), javaClass.classLoader)
        withList.call("remember", arrayOf(list), model(rooms = 7), javaClass.classLoader)
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

        assertSame(b, client.call("bigger", arrayOf(a, b), model, javaClass.classLoader))
        assertEquals(1, loopback.received.count { it is ClientMessage.ModelPut }, "instances need the model")
    }

    @Test
    fun `a result referring to an instance outside the model is rejected`() {
        val first: (ScriptFunctionCall) -> Any? = { call -> call.model!!.instances.getValue("a") }
        val loopback = Loopback(
            mapOf("first" to first),
            rewriteAnswer = { answer ->
                if (answer is ServiceMessage.Result) answer.copy(value = WireValue.InstanceValue("ghost")) else answer
            }
        )
        val client = ScriptFunctionsClient(loopback, mapOf("first" to spec("first", any, true)))

        val error = assertFailsWith<ExternalCallException> { client.call("first", arrayOf(), model(), javaClass.classLoader) }
        assertTrue(error.message!!.contains("ghost"))
    }

    @Test
    fun `a service that lost the model gets it again`() {
        val loopback = Loopback(mapOf("total" to totalRooms))
        val client = ScriptFunctionsClient(loopback, mapOf("total" to spec("total", int, true)))
        val model = model()

        assertEquals(8, client.call("total", arrayOf(), model, javaClass.classLoader))
        loopback.service.clear() // as after a restart the client did not notice
        assertEquals(8, client.call("total", arrayOf(), model, javaClass.classLoader))

        assertEquals(2, loopback.received.count { it is ClientMessage.MetamodelPut })
        assertEquals(2, loopback.received.count { it is ClientMessage.ModelPut })
        assertTrue(loopback.answered.any { it is ServiceMessage.Failure && it.code == ServiceMessage.Failure.UNKNOWN_MODEL })
    }

    @Test
    fun `a new connection is sent the metamodel again`() {
        val loopback = Loopback(mapOf("total" to totalRooms))
        val client = ScriptFunctionsClient(loopback, mapOf("total" to spec("total", int, true)))
        val model = model()

        assertEquals(8, client.call("total", arrayOf(), model, javaClass.classLoader))
        loopback.dropBeforeCall = 2
        assertEquals(8, client.call("total", arrayOf(), model, javaClass.classLoader))
        assertEquals(8, client.call("total", arrayOf(), model, javaClass.classLoader))

        assertEquals(2, loopback.received.count { it is ClientMessage.MetamodelPut })
        assertEquals(2, loopback.received.count { it is ClientMessage.ModelPut })
    }

    @Test
    fun `enum values go out by enum and entry, need no model, and come back as the script's own entries`() {
        val style = ClassTypeRef("enum/houses.mm", "Style", false)
        val styles = ClassTypeRef("builtin", "ReadonlyList", false, mapOf("T" to style))
        var received: Any? = null
        val loopback = Loopback(mapOf("other" to { call ->
            received = call.arguments
            val given = call.argument<List<*>>(1).single() as ScriptEnumValue
            ScriptEnumValue(given.enumName, if (given.entry == "Modern") "Classic" else "Modern")
        }))
        val client = ScriptFunctionsClient(loopback, mapOf("other" to spec("other", style, false, style, styles)))
        val modern = metamodel.resolveEnumValue("Style", "Modern")
        val classic = metamodel.resolveEnumValue("Style", "Classic")

        assertSame(classic, client.call("other", arrayOf(modern, ListImpl(listOf(modern))), model(), metamodel.classLoader))
        assertEquals(listOf(ScriptEnumValue("Style", "Modern"), listOf(ScriptEnumValue("Style", "Modern"))), received)
        assertTrue(loopback.received.none { it is ClientMessage.ModelPut }, "enum values need no model")
    }

    @Test
    fun `a returned enum entry the metamodel does not declare is rejected`() {
        val style = ClassTypeRef("enum/houses.mm", "Style", false)
        val loopback = Loopback(mapOf("bad" to { _ -> ScriptEnumValue("Style", "Gothic") }))
        val client = ScriptFunctionsClient(loopback, mapOf("bad" to spec("bad", style, false)))

        val error = assertFailsWith<ExternalCallException> { client.call("bad", arrayOf(), model(), metamodel.classLoader) }
        assertTrue(error.message!!.contains("Style.Gothic"), error.message)
    }

    @Test
    fun `a function that reads the model cannot be called without one`() {
        val loopback = Loopback(mapOf("total" to totalRooms))
        val client = ScriptFunctionsClient(loopback, mapOf("total" to spec("total", int, true)))

        assertFailsWith<ExternalCallException> { client.call("total", arrayOf(), null, javaClass.classLoader) }
        assertTrue(loopback.received.isEmpty())
        assertNull(loopback.service.currentModel)
    }
}
