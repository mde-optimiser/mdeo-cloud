package com.mdeo.optimizer.operators

import com.mdeo.metamodel.data.ModelDataPropertyValue
import com.mdeo.optimizer.config.ConfigLiteral
import com.mdeo.optimizer.config.CrossoversConfig
import com.mdeo.optimizer.config.MutationsConfig
import com.mdeo.optimizer.config.OperatorRef
import com.mdeo.optimizer.crossover.CrossoverFixtures
import com.mdeo.optimizer.crossover.CrossoverFixtures.COPY_NAMES
import com.mdeo.optimizer.crossover.CrossoverFixtures.MIXED
import com.mdeo.optimizer.crossover.CrossoverFixtures.MOVE
import com.mdeo.optimizer.crossover.CrossoverFixtures.TAG
import com.mdeo.optimizer.crossover.CrossoverFixtures.TAKE_HOSTING
import com.mdeo.optimizer.crossover.CrossoverFixtures.VOTE
import com.mdeo.optimizer.crossover.CrossoverFixtures.asts
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Operators of the config (Part G): operator ids sort by path and canonical arguments, every
 * entry is its own operator, the arguments reach the engine, and operators are classified and
 * checked against their files.
 */
class OperatorCatalogTest {

    private val lenientJson = Json { ignoreUnknownKeys = true }

    private val tagArguments = mapOf(
        "weight" to ConfigLiteral.IntValue(3),
        "kind" to ConfigLiteral.EnumValue("Kind", "LARGE"),
        "labels" to ConfigLiteral.ListValue(listOf("p", "q", "p").map { ConfigLiteral.StringValue(it) })
    )

    @Test
    fun `ids sort by path then by canonical arguments, and two entries of one file are two operators`() {
        val refs = listOf(
            OperatorRef(TAG, tagArguments),
            OperatorRef(MOVE),
            OperatorRef(TAG, tagArguments + ("weight" to ConfigLiteral.IntValue(1)))
        )
        val operators = OperatorCatalog.mutationOperators(refs, asts)

        assertEquals(listOf(0, 1, 2), operators.map { it.id })
        assertEquals(asts[MOVE], operators[0].ast)
        assertEquals(1, operators[1].arguments["weight"], "weight = 1 sorts before weight = 3")
        assertEquals(3, operators[2].arguments["weight"])

        val shuffled = OperatorCatalog.mutationOperators(refs.reversed(), asts)
        assertEquals(operators, shuffled, "the ids do not depend on the order of the config")
    }

    @Test
    fun `canonical rendering and labels follow the contract`() {
        val ref = OperatorRef(
            "/p/move-component.mt",
            mapOf(
                "s" to ConfigLiteral.StringValue("a\"b"),
                "b" to ConfigLiteral.BooleanValue(true),
                "l" to ConfigLiteral.LongValue(4),
                "f" to ConfigLiteral.FloatValue(2.5f),
                "d" to ConfigLiteral.DoubleValue(2.5),
                "i" to ConfigLiteral.IntValue(4),
                "e" to ConfigLiteral.EnumValue("Kind", "SERVICE"),
                "xs" to ConfigLiteral.ListValue(listOf(ConfigLiteral.IntValue(1), ConfigLiteral.IntValue(2)))
            )
        )
        assertEquals(
            "b = true, d = 2.5, e = Kind.SERVICE, f = 2.5f, i = 4, l = 4L, s = \"a\\\"b\", xs = [1, 2]",
            ref.canonicalArguments
        )
        assertEquals("move-component.mt (maxCandidates = 4)", OperatorRef("/p/move-component.mt", mapOf("maxCandidates" to ConfigLiteral.IntValue(4))).label)
        assertEquals("move-component.mt", OperatorRef("/p/move-component.mt").label)
        assertEquals("", OperatorRef("/p/move-component.mt").canonicalArguments)
    }

    @Test
    fun `the config JSON of the contract decodes`() {
        val json = """
            {
              "mutations": {
                "operators": [
                  {"path": "/a.mt", "arguments": {
                    "i": {"type": "int", "value": 4}, "l": {"type": "long", "value": 4},
                    "f": {"type": "float", "value": 2.5}, "d": {"type": "double", "value": 2.5},
                    "s": {"type": "string", "value": "x"}, "b": {"type": "boolean", "value": true},
                    "e": {"type": "enum", "enumName": "Kind", "literal": "SERVICE"},
                    "xs": {"type": "list", "elements": [{"type": "int", "value": 1}]}
                  }},
                  {"path": "/b.mt"}
                ]
              },
              "crossovers": {"operators": [{"path": "/c.mt"}]}
            }
        """.trimIndent()
        val search = lenientJson.decodeFromString(com.mdeo.optimizer.config.SearchConfig.serializer(), json)

        val arguments = search.mutations.operators[0].arguments
        assertEquals(ConfigLiteral.IntValue(4), arguments["i"])
        assertEquals(ConfigLiteral.LongValue(4), arguments["l"])
        assertEquals(ConfigLiteral.FloatValue(2.5f), arguments["f"])
        assertEquals(ConfigLiteral.DoubleValue(2.5), arguments["d"])
        assertEquals(ConfigLiteral.StringValue("x"), arguments["s"])
        assertEquals(ConfigLiteral.BooleanValue(true), arguments["b"])
        assertEquals(ConfigLiteral.EnumValue("Kind", "SERVICE"), arguments["e"])
        assertEquals(ConfigLiteral.ListValue(listOf(ConfigLiteral.IntValue(1))), arguments["xs"])
        assertEquals(emptyMap<String, ConfigLiteral>(), search.mutations.operators[1].arguments)
        assertEquals(listOf(OperatorRef("/c.mt")), search.crossovers.operators)

        val withoutCrossovers = Json.decodeFromString(com.mdeo.optimizer.config.SearchConfig.serializer(), """{"mutations": {}}""")
        assertEquals(CrossoversConfig(), withoutCrossovers.crossovers)
        assertEquals(MutationsConfig(), withoutCrossovers.mutations)
        assertTrue(Json.encodeToJsonElement(ConfigLiteral.serializer(), ConfigLiteral.IntValue(4)).jsonObject["type"].toString() == "\"int\"")
    }

    @Test
    fun `bound argument values reach the engine`() {
        val operator = OperatorCatalog.mutationOperators(listOf(OperatorRef(TAG, tagArguments)), asts).single()
        CrossoverFixtures.solution().use { solution ->
            val result = TransformationAttemptRunner().tryApply(solution, operator)

            assertTrue(result.isApplied, result.toString())
            val tagged = solution.modelGraph.toModelData().instances.single {
                (it.properties["name"] as? ModelDataPropertyValue.StringValue)?.value == "tagged"
            }
            val weight = tagged.properties["weight"] as ModelDataPropertyValue.NumberValue
            assertEquals(3.0, weight.value, "the int literal 3 became the double 3.0")
            assertEquals("LARGE", (tagged.properties["kind"] as ModelDataPropertyValue.EnumValue).enumEntry)
            assertEquals(2, (tagged.properties["rank"] as ModelDataPropertyValue.NumberValue).value.toInt(),
                "the list [p, q, p] became the set {p, q}")
        }
    }

    @Test
    fun `operators are classified and checked against their files`() {
        val crossovers = OperatorCatalog.crossoverOperators(
            listOf(OperatorRef(VOTE), OperatorRef(TAKE_HOSTING), OperatorRef(COPY_NAMES)), asts, parents = 2
        )
        assertEquals(listOf(COPY_NAMES, TAKE_HOSTING, VOTE).map { asts[it] }, crossovers.map { it.operator.ast })
        assertEquals(listOf(0, 1, 2), crossovers.map { it.id }, "crossover ids are their own id space")
        assertEquals(2, crossovers[1].parentCount)
        assertTrue(crossovers[2].isVariadic && crossovers[2].accepts(3) && crossovers[2].accepts(7))
        assertFalse(crossovers[1].accepts(3))

        val wrongArity = assertThrows<IllegalArgumentException> {
            OperatorCatalog.crossoverOperators(listOf(OperatorRef(TAKE_HOSTING)), asts, parents = 3)
        }
        assertTrue("takes 2 parents" in wrongArity.message!!, wrongArity.message)
        assertThrows<IllegalArgumentException> { OperatorCatalog.crossoverOperators(listOf(OperatorRef(MOVE)), asts, 2) }
        assertThrows<IllegalArgumentException> { OperatorCatalog.crossoverOperators(listOf(OperatorRef(MIXED)), asts, 2) }

        val crossoverAsMutation = assertThrows<IllegalArgumentException> {
            OperatorCatalog.mutationOperators(listOf(OperatorRef(TAKE_HOSTING)), asts)
        }
        assertTrue("crossovers" in crossoverAsMutation.message!!, crossoverAsMutation.message)

        val missing = assertThrows<IllegalArgumentException> { OperatorCatalog.mutationOperators(listOf(OperatorRef(TAG)), asts) }
        assertTrue("'weight'" in missing.message!! && "'kind'" in missing.message!!, missing.message)

        val unknown = assertThrows<IllegalArgumentException> {
            OperatorCatalog.mutationOperators(listOf(OperatorRef(MOVE, mapOf("nope" to ConfigLiteral.IntValue(1)))), asts)
        }
        assertTrue("'nope'" in unknown.message!!, unknown.message)
        assertNotEquals(OperatorRef(TAG, tagArguments).canonicalArguments, OperatorRef(TAG).canonicalArguments)
    }

    @Test
    fun `variation values are mutation and genetic only`() {
        assertEquals(listOf("MUTATION", "GENETIC"), com.mdeo.optimizer.config.VariationType.entries.map { it.name })
        assertThrows<Exception> {
            Json.decodeFromString(com.mdeo.optimizer.config.AlgorithmParameters.serializer(), """{"variation": "PROBABILISTIC"}""")
        }
        val parameters = Json.decodeFromString(
            com.mdeo.optimizer.config.AlgorithmParameters.serializer(),
            """{"variation": "GENETIC", "crossover": {"rate": 0.5, "parents": 3, "children": 3, "strategy": "RANDOM"}}"""
        )
        assertEquals(0.5, parameters.crossover.rate)
        assertEquals(3, parameters.crossover.parents)
    }
}
