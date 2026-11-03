package com.mdeo.scriptfunctions.service

import com.mdeo.scriptfunctions.protocol.WireAttribute
import com.mdeo.scriptfunctions.protocol.WireClass
import com.mdeo.scriptfunctions.protocol.WireEnum
import com.mdeo.scriptfunctions.protocol.WireInstance
import com.mdeo.scriptfunctions.protocol.WireMetamodel
import com.mdeo.scriptfunctions.protocol.WireModel
import com.mdeo.scriptfunctions.protocol.WireScalars
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ScriptModelTest {

    @Test
    fun `enum attributes are enum values, also when a superclass declares them`() {
        val metamodel = ScriptMetamodel(
            WireMetamodel(
                path = "/houses.mm",
                classes = listOf(
                    WireClass("Building", isAbstract = true, attributes = listOf(WireAttribute("style", "Style", isEnum = true))),
                    WireClass("House", extends = listOf("Building"), attributes = listOf(WireAttribute("rooms", "int")))
                ),
                enums = listOf(WireEnum("Style", listOf("Modern", "Classic")))
            )
        )
        val model = ScriptModel(
            WireModel(
                "/houses.mm",
                listOf(
                    WireInstance(
                        "home", "House",
                        attributes = mapOf("style" to listOf(WireScalars.encode("Classic")!!), "rooms" to listOf(WireScalars.encode(4)!!))
                    )
                )
            ),
            metamodel
        )
        val home = model.instances.getValue("home")
        assertEquals(ScriptEnumValue("Style", "Classic"), home.attribute("style"))
        assertEquals(4, home.attribute("rooms"))
    }
}
