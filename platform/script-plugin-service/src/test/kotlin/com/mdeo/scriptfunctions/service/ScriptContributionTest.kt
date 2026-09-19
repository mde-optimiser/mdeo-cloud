package com.mdeo.scriptfunctions.service

import com.mdeo.expression.ast.types.BuiltinTypes
import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.expression.ast.types.GenericTypeRef
import com.mdeo.expression.ast.types.genericClassType
import com.mdeo.expression.ast.types.lambdaType
import com.mdeo.pluginservice.PluginDefinition
import com.mdeo.pluginservice.icon
import com.mdeo.scriptfunctions.protocol.ScriptFunctionsProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScriptContributionTest {

    private val listOfString = genericClassType("builtin", "ReadonlyList", typeArgs = mapOf("T" to BuiltinTypes.STRING))

    private val routing = scriptContribution("routing") {
        description = "Route planning"
        function("shortestTour") {
            parameter("stops", listOfString)
            implementation { null }
        }
        function("distance", overload = "pair") {
            parameter("from", BuiltinTypes.STRING)
            parameter("to", BuiltinTypes.STRING)
            returns(BuiltinTypes.DOUBLE)
            implementation { 1.0 }
        }
        function("first") {
            generics("T")
            parameter("items", genericClassType("builtin", "ReadonlyList", typeArgs = mapOf("T" to GenericTypeRef("T"))))
            returns(GenericTypeRef("T"))
            implementation { call -> call.argument<List<Any?>>(0).first() }
        }
    }

    @Test
    fun `the manifest carries the payload the script language reads`() {
        val definition = PluginDefinition("routing-service", "Routing", "Routes", icon(), listOf(routing))
        val manifest = definition.manifest()
        val entry = manifest["contributionPlugins"]!!.jsonArray.single().jsonObject
        val payload = entry["serverContributionPlugins"]!!.jsonArray.single()

        val expected = Json.parseToJsonElement(
            """
            {
              "type": "script-language-contribution",
              "types": [],
              "functions": {
                "shortestTour": { "signatures": { "": {
                  "signature": {
                    "parameters": [{ "name": "stops", "type": { "package": "builtin", "type": "ReadonlyList", "isNullable": false,
                                     "typeArgs": { "T": { "package": "builtin", "type": "string", "isNullable": false } } } }],
                    "returnType": { "kind": "void" }
                  },
                  "implementation": { "kind": "external", "operation": "shortestTour" }
                } } },
                "distance": { "signatures": { "pair": {
                  "signature": {
                    "parameters": [
                      { "name": "from", "type": { "package": "builtin", "type": "string", "isNullable": false } },
                      { "name": "to", "type": { "package": "builtin", "type": "string", "isNullable": false } }
                    ],
                    "returnType": { "package": "builtin", "type": "double", "isNullable": false }
                  },
                  "implementation": { "kind": "external", "operation": "distance/pair" }
                } } },
                "first": { "signatures": { "": {
                  "signature": {
                    "parameters": [{ "name": "items", "type": { "package": "builtin", "type": "ReadonlyList", "isNullable": false,
                                     "typeArgs": { "T": { "generic": "T" } } } }],
                    "returnType": { "generic": "T" },
                    "generics": ["T"]
                  },
                  "implementation": { "kind": "external", "operation": "first" }
                } } }
              },
              "expressions": {},
              "id": "routing",
              "sessions": { "functions": { "protocol": "script-functions", "versions": [1] } }
            }
            """
        )
        assertEquals(expected, payload)
        assertEquals("script", entry["languageId"].toString().trim('"'))
    }

    @Test
    fun `a function that reads the model says so in its implementation`() {
        val contribution = scriptContribution("x") {
            function("f") {
                readsModel = true
                implementation { null }
            }
        }
        val implementation = contribution.payload()["functions"]!!.jsonObject["f"]!!.jsonObject["signatures"]!!
            .jsonObject[""]!!.jsonObject["implementation"]!!
        assertEquals(Json.parseToJsonElement("""{"kind":"external","operation":"f","model":"readonly"}"""), implementation)
    }

    @Test
    fun `records and opaque classes are declared in the payload`() {
        lateinit var point: RecordType
        lateinit var index: OpaqueType
        val contribution = scriptContribution("geo") {
            point = record("Point") {
                field("x", BuiltinTypes.DOUBLE)
                field("tags", genericClassType("builtin", "ReadonlyList", typeArgs = mapOf("T" to BuiltinTypes.STRING)))
            }
            index = opaque("Index")
            function("nearest") {
                parameter("index", index.type)
                returns(point.type)
                implementation { null }
            }
        }

        assertEquals(ClassTypeRef("contrib/geo", "Point", false), point.type)
        val classes = contribution.payload()["classes"]!!
        assertEquals(
            Json.parseToJsonElement(
                """
                {
                  "Point": { "kind": "record", "fields": [
                    { "name": "x", "type": { "package": "builtin", "type": "double", "isNullable": false } },
                    { "name": "tags", "type": { "package": "builtin", "type": "ReadonlyList", "isNullable": false,
                                                "typeArgs": { "T": { "package": "builtin", "type": "string", "isNullable": false } } } }
                  ] },
                  "Index": { "kind": "opaque" }
                }
                """
            ),
            classes
        )
        assertEquals(RecordValue("Point", mapOf("x" to 1.0, "tags" to listOf("a"))), point.of("x" to 1.0, "tags" to listOf("a")))
        assertFailsWith<IllegalArgumentException> { point.of("x" to 1.0) }
    }

    @Test
    fun `parameters, results and record fields follow one type rule`() {
        scriptContribution("geo") {
            val index = opaque("Index")
            // A record may name one declared after it, hold `Any`, and hold an opaque handle.
            record("Route") { field("stops", genericClassType("builtin", "List", typeArgs = mapOf("T" to ClassTypeRef("contrib/geo", "Stop", false)))) }
            record("Stop") {
                field("index", index.type)
                field("extra", genericClassType("builtin", "Map", typeArgs = mapOf("K" to BuiltinTypes.STRING, "V" to BuiltinTypes.ANY)))
            }
            function("plan") {
                parameter("index", index.type)
                returns(genericClassType("builtin", "List", typeArgs = mapOf("T" to BuiltinTypes.STRING)))
                implementation { null }
            }
        }
        fun refused(init: ScriptContributionBuilder.() -> Unit) =
            assertFailsWith<IllegalArgumentException> { scriptContribution("geo", init) }.message!!

        assertTrue(
            "Declare it as a 'ReadonlyList'" in refused {
                function("f") {
                    parameter("items", genericClassType("builtin", "List", typeArgs = mapOf("T" to BuiltinTypes.INT)))
                    implementation { null }
                }
            }
        )
        assertTrue(
            "lambda" in refused {
                record("Bad") {
                    field("callbacks", genericClassType("builtin", "ReadonlyList", typeArgs = mapOf("T" to lambdaType(BuiltinTypes.INT, emptyList()))))
                }
            }
        )
        assertTrue("copies a record" in refused { record("Bad") { field("with", BuiltinTypes.INT) } })
        assertTrue(
            "does not define" in refused {
                function("f") {
                    returns(ClassTypeRef("contrib/geo", "Missing", false))
                    implementation { null }
                }
            }
        )
        assertTrue(
            "not declared" in refused {
                function("f") {
                    parameter("x", GenericTypeRef("T"))
                    implementation { null }
                }
            }
        )
    }

    @Test
    fun `default values become typed literals in the payload`() {
        val contribution = scriptContribution("geo") {
            record("Tag") {
                field("name", BuiltinTypes.STRING)
                field("weight", BuiltinTypes.INT, 5)
            }
            function("area") {
                parameter("w", BuiltinTypes.DOUBLE)
                parameter("h", BuiltinTypes.DOUBLE, 1.0)
                parameter("label", BuiltinTypes.STRING.copy(isNullable = true), null)
                returns(BuiltinTypes.DOUBLE)
                implementation { null }
            }
        }
        val payload = contribution.payload()
        val types = payload["types"]!!.jsonArray
        val defaults = payload["functions"]!!.jsonObject["area"]!!.jsonObject["signatures"]!!.jsonObject[""]!!
            .jsonObject["defaultValues"]!!.jsonObject
        assertEquals(setOf("h", "label"), defaults.keys)
        fun typeOf(literal: kotlinx.serialization.json.JsonElement) =
            types[literal.jsonObject["evalType"].toString().toInt()]
        assertEquals(Json.parseToJsonElement("""{"kind":"doubleLiteral","evalType":${defaults["h"]!!.jsonObject["evalType"]},"value":"1.0"}"""), defaults["h"])
        assertEquals(Json.parseToJsonElement("""{"package":"builtin","type":"double","isNullable":false}"""), typeOf(defaults["h"]!!))
        assertEquals("\"nullLiteral\"", defaults["label"]!!.jsonObject["kind"].toString())
        val weight = payload["classes"]!!.jsonObject["Tag"]!!.jsonObject["fields"]!!.jsonArray[1].jsonObject["defaultValue"]!!
        assertEquals("\"5\"", weight.jsonObject["value"].toString())
        assertEquals(Json.parseToJsonElement("""{"package":"builtin","type":"int","isNullable":false}"""), typeOf(weight))

        assertFailsWith<IllegalArgumentException> {
            scriptContribution("geo") { function("f") { parameter("x", BuiltinTypes.INT, 1.5); implementation { null } } }
        }
        assertFailsWith<IllegalArgumentException> {
            scriptContribution("geo") { function("f") { parameter("x", BuiltinTypes.INT, null); implementation { null } } }
        }
    }

    @Test
    fun `every function is registered with the session`() {
        assertEquals(setOf("shortestTour", "distance/pair", "first"), routing.operations.keys)
        val session = routing.sessions.getValue(DEFAULT_SCRIPT_FUNCTIONS_SESSION)
        assertEquals(ScriptFunctionsProtocol.NAME, session.type.protocol)
        assertTrue(session.handler is ScriptFunctionService)
    }

    @Test
    fun `mistakes are reported when the contribution is declared`() {
        val lambda = lambdaType(BuiltinTypes.INT, emptyList())
        assertFailsWith<IllegalArgumentException> {
            scriptContribution("x") { function("f") { parameter("callback", lambda); implementation { null } } }
        }.also { assertTrue(it.message!!.contains("lambda")) }
        assertFailsWith<IllegalArgumentException> {
            scriptContribution("x") { function("f") { parameter("a", BuiltinTypes.INT) } }
        }.also { assertTrue(it.message!!.contains("no implementation")) }
        assertFailsWith<IllegalArgumentException> {
            scriptContribution("x") {
                function("f") { implementation { null } }
                function("f") { implementation { null } }
            }
        }
    }
}
