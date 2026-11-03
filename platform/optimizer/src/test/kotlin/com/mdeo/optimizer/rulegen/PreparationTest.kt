package com.mdeo.optimizer.rulegen

import com.mdeo.metamodel.data.ModelData
import com.mdeo.metamodel.data.ModelDataInstance
import com.mdeo.metamodel.data.ModelDataLink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** What decides whether an optimisation with generated operators can start. */
class PreparationTest {

    private val metamodel = mm("Server", "Component") { assoc("Component.server[?]", "-->", "Server") }
    private val specs = listOf(spec("mutate Component.server"))
    private val refinements = listOf(refine("Component.server[1]"))

    private fun model(vararg links: Pair<String, String>) = ModelData(
        "/test.mm",
        listOf(ModelDataInstance("s1", "Server", emptyMap()), ModelDataInstance("c1", "Component", emptyMap()), ModelDataInstance("c2", "Component", emptyMap())),
        links.map { (c, s) -> ModelDataLink(c, "server", s, null) }
    )

    @Test
    fun `a model within the refinements gets the operators`() {
        val preparation = MutationRuleGenerator.prepare(metamodel, specs, refinements, model("c1" to "s1", "c2" to "s1"))
        assertTrue(preparation is Preparation.Ready, "$preparation")
        assertEquals(setOf("CHANGE_Component_server", "SWAP_Component_server"), (preparation as Preparation.Ready).result.mutations.map { it.name }.toSet())
    }

    @Test
    fun `a model outside a refinement is rejected with every violation`() {
        val preparation = MutationRuleGenerator.prepare(metamodel, specs, refinements, model())
        assertEquals(
            Preparation.Rejected("The initial model violates the goal's refinements: c1.server has 0 link(s), outside [1..1]; c2.server has 0 link(s), outside [1..1]"),
            preparation
        )
    }

    @Test
    fun `only refined ends are checked`() {
        // Without refinements the unset servers are fine, whatever the model lacks elsewhere.
        assertTrue(MutationRuleGenerator.prepare(metamodel, specs, emptyList(), model()) is Preparation.Ready)
    }

    @Test
    fun `a spec or refinement that cannot be generated is rejected with the reason`() {
        val unknown = MutationRuleGenerator.prepare(metamodel, listOf(spec("mutate Rack")), emptyList(), model())
        assertEquals(Preparation.Rejected("Cannot generate mutation operators: mutate Rack: unknown class 'Rack'"), unknown)
        val loosened = MutationRuleGenerator.prepare(metamodel, specs, listOf(refine("Component.server[0..2]")), model())
        assertTrue((loosened as Preparation.Rejected).reason.contains("does not lie within"), loosened.reason)
    }

    @Test
    fun `warnings become a Markdown list, and no page when there are none`() {
        assertEquals(null, GenerationResult(emptyList(), emptyList()).warningsReport())
        assertEquals("# Mutation generation warnings\n\n- one\n- two\n", GenerationResult(emptyList(), listOf("one", "two")).warningsReport())
    }
}
