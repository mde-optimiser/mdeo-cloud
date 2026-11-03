package com.mdeo.optimizer.rulegen

import com.mdeo.metamodel.Metamodel
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.metamodel.data.ModelData
import com.mdeo.metamodel.data.ModelDataInstance
import com.mdeo.metamodel.data.ModelDataLink
import com.mdeo.modeltransformation.ast.TransformationOperator
import com.mdeo.optimizer.config.MutationParameters
import com.mdeo.optimizer.config.MutationStepConfig
import com.mdeo.optimizer.config.MutationStrategy
import com.mdeo.optimizer.config.RefinementConfig
import com.mdeo.optimizer.operators.MutationStrategyFactory
import com.mdeo.optimizer.solution.FailedOperatorsMetadata
import com.mdeo.optimizer.solution.Solution
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.random.Random

/**
 * Generated operators in the optimizer's own mutation loop: the mutation strategies apply them
 * in steps of one to three to copies of a population of solutions, as the local evaluator does,
 * on both graph backends. Every child must be consistent, every parent must stay as it was, and
 * every operator the strategy records as failing deterministically on a solution must indeed
 * not apply to it. A step in which every attempt fails, some after changing the model, must
 * leave the solution as it was.
 */
class GeneratedOperatorSearchTest {

    private data class Case(val name: String, val metamodel: MetamodelData, val specs: List<String>, val refinements: List<RefinementConfig> = emptyList())

    private val cases = listOf(
        Case("scrum", ExampleMetamodels.sprintPlanning, ExampleMetamodels.sprintPlanning.classes.map { "mutate ${it.name}" }),
        Case("cra", ExampleMetamodels.cra, ExampleMetamodels.cra.classes.map { "mutate ${it.name}" }),
        Case("project allocation", ExampleMetamodels.projectAllocation, ExampleMetamodels.projectAllocation.classes.map { "mutate ${it.name}" }),
        Case("recursive", mm("Root", "Node") {
            assoc("Root.nodes[*]", "*-->", "Node.root[?]")
            assoc("Node.children[0..2]", "*-->", "Node.parent[?]")
        }, listOf("mutate Root", "mutate Node")),
        Case("palladio allocation", mm("ResourceEnvironment", "ResourceContainer", "AllocationContext") {
            assoc("ResourceEnvironment.resourceContainer[*]", "*-->", "ResourceContainer.resourceEnvironment[?]")
            assoc("ResourceContainer.nestedResourceContainers[*]", "*-->", "ResourceContainer.parentResourceContainer[?]")
            assoc("AllocationContext.resourceContainer[?]", "-->", "ResourceContainer")
        }, listOf("mutate AllocationContext.resourceContainer", "mutate ResourceContainer"), listOf(refine("AllocationContext.resourceContainer[1]")))
    )

    @TestFactory
    fun `the mutation strategies keep every solution consistent`(): List<DynamicTest> =
        cases.flatMap { case ->
            MutationStrategy.entries.flatMap { strategy ->
                Backend.entries.map { backend ->
                    DynamicTest.dynamicTest("${case.name}, ${strategy.name.lowercase()}, ${backend.name.lowercase()}") { run(case, strategy, backend) }
                }
            }
        }

    @TestFactory
    fun `a step whose every attempt fails after changing the model leaves the solution as it was`(): List<DynamicTest> =
        MutationStrategy.entries.flatMap { strategy ->
            Backend.entries.map { backend ->
                DynamicTest.dynamicTest("${strategy.name.lowercase()}, ${backend.name.lowercase()}") {
                    // The only house cannot go: its room would have nowhere to move. The delete
                    // operator unlinks the room before it finds that out, and then gives up.
                    val metamodel = mm("House", "Room") { assoc("House.rooms[*]", "*-->", "Room.house") }
                    val delete = MutationRuleGenerator.generate(metamodel, listOf(spec("delete House"))).mutations.single()
                    val mutation = MutationStrategyFactory.create(
                        MutationParameters(MutationStepConfig.Fixed(1), strategy),
                        listOf(TransformationOperator(0, delete.typedAst))
                    )
                    val model = ModelData(
                        "/test.mm",
                        listOf(ModelDataInstance("h1", "House", emptyMap()), ModelDataInstance("r1", "Room", emptyMap())),
                        listOf(ModelDataLink("h1", "rooms", "r1", "house"))
                    )
                    val parent = Solution(backend.graph(model, Metamodel.compile(metamodel)))
                    repeat(5) {
                        val child = mutation.mutate(parent.copy())
                        assertEquals(model.key(), child.modelGraph.toModelData().key())
                        assertTrue(child.modelGraph !== parent.modelGraph)
                        child.close()
                    }
                    parent.close()
                }
            }
        }

    private fun run(case: Case, strategy: MutationStrategy, backend: Backend) {
        val specs = case.specs.map { spec(it) }
        val generated = MutationRuleGenerator.generate(case.metamodel, specs, case.refinements).mutations.sortedBy { it.name }
        val operators = generated.mapIndexed { id, m -> TransformationOperator(id, m.typedAst) }
        val info = MetamodelInfo(case.metamodel, case.refinements.map { MultiplicityOverride(it.className, it.fieldName, it.lower, it.upper) })
        val metamodel = Metamodel.compile(case.metamodel)
        val runner = RuleRunner(case.metamodel, backend)
        val mutation = MutationStrategyFactory.create(MutationParameters(MutationStepConfig.Interval(1, 3), strategy), operators)

        val randomModels = RandomModels(info, case.metamodel, seed = 17)
        val population = (1..POPULATION).map { Solution(backend.graph(randomModels.next(maxPerClass = 4)!!, metamodel)) }.toMutableList()
        val random = Random(23)
        var changed = 0
        repeat(STEPS) { step ->
            val parent = population.random(random)
            val before = parent.modelGraph.toModelData()
            val child = mutation.mutate(parent.copy())
            val after = child.modelGraph.toModelData()

            assertEquals(before.key(), parent.modelGraph.toModelData().key(), "step $step changed the parent")
            val problems = problemsOf(after, info)
            assertTrue(problems.isEmpty()) {
                val applied = child.transformationsChain.lastOrNull().orEmpty().map { generated[it].name }
                "step $step applied $applied: $problems\n  before: ${before.render()}\n  after:  ${after.render()}"
            }
            val beforeClasses = before.instances.associate { it.name to it.className }
            after.instances.filter { it.name in beforeClasses }.forEach {
                assertEquals(beforeClasses[it.name], it.className, "step $step: ${it.name} changed its class")
            }
            if (after.key() != before.key()) changed++

            val failed = (parent.modelGraph.metadata as? FailedOperatorsMetadata)?.failedDeterministicOperators?.toList().orEmpty()
            for (id in failed) {
                assertEquals(Applied.No, runner.apply(generated[id], before)) { "${generated[id].name} is recorded as failing on ${before.render()}" }
            }

            population += child
            if (population.size > POPULATION * 2) population.removeAt(0).close()
        }
        // Constrained metamodels leave many steps without an applicable operator; the search
        // must still move.
        assertTrue(changed > STEPS / 5, "only $changed of $STEPS steps changed a model")
        population.forEach { it.close() }
    }

    private companion object {
        const val POPULATION = 6
        const val STEPS = 150
    }
}
