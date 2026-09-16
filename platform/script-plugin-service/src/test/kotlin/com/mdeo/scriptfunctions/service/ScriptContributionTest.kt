package com.mdeo.scriptfunctions.service

import com.mdeo.expression.ast.types.BuiltinTypes
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

    private val listOfString = genericClassType("builtin", "List", typeArgs = mapOf("T" to BuiltinTypes.STRING))

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
                    "parameters": [{ "name": "stops", "type": { "package": "builtin", "type": "List", "isNullable": false,
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
