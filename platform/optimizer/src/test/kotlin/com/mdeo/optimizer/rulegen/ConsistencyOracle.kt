package com.mdeo.optimizer.rulegen

import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.metamodel.data.ModelData
import com.mdeo.optimizer.config.RefinementConfig
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import kotlin.random.Random

/**
 * The test oracle for generated operators: applies every operator for [specs] to random models
 * that respect the (refined) metamodel, on every graph [backends], and fails when
 *
 * - an application leaves the model inconsistent (see [problemsOf]),
 * - an application changes the model in a way its operator may not (see [shapeProblem]),
 * - the engine throws, or
 * - an operator never applies to any of the models or along the random walks.
 *
 * Each operator is applied [repeats] times in a row to each of [models] random models; then
 * random walks of [walk] steps apply random operators. Operators that have not applied yet are
 * tried at every step of further walks of up to [searchSteps] steps, which also start from unions
 * of two to four models, where several objects can play each role.
 */
class ConsistencyOracle(
    private val metamodel: MetamodelData,
    private val specs: List<MutationRuleSpec>,
    private val refinements: List<RefinementConfig> = emptyList(),
    private val models: Int = 12,
    private val repeats: Int = 4,
    private val walk: Int = 40,
    private val maxPerClass: Int = 7,
    private val searchSteps: Int = 300,
    private val backends: List<Backend> = Backend.entries,
    private val seed: Int = 7
) {
    private val generated = MutationRuleGenerator.generate(metamodel, specs, refinements)
    private val info = MetamodelInfo(metamodel, refinements.map { MultiplicityOverride(it.className, it.fieldName, it.lower, it.upper) })
    private val operatorSpecs = operatorSpecs(info, specs)

    /** The operators under test. */
    val mutations: List<GeneratedMutation> get() = generated.mutations

    fun check() {
        val randomModels = RandomModels(info, metamodel, seed)
        val start = (1..models).mapNotNull { randomModels.next(maxPerClass) }
        assertTrue(start.size == models, "could not build $models consistent models")
        start.forEach { model -> problemsOf(model, info).let { assertTrue(it.isEmpty(), "a random model is inconsistent: $it") } }
        for (backend in backends) checkOn(backend, start)
    }

    private fun checkOn(backend: Backend, start: List<ModelData>) {
        val runner = RuleRunner(metamodel, backend)
        val applied = mutableMapOf<String, Int>()
        val problems = mutableListOf<String>()

        fun step(mutation: GeneratedMutation, before: ModelData): ModelData? {
            val after = try {
                runner.apply(mutation, before)
            } catch (e: Exception) {
                problems += "${mutation.name} threw ${e::class.simpleName}: ${e.message}"
                return null
            }
            if (after !is Applied.Yes) return null
            applied.merge(mutation.name, 1, Int::plus)
            val found = problemsOf(after.model, info) +
                listOfNotNull(shapeProblem(operatorSpecs.getValue(mutation.name), before, after.model, info))
            if (found.isNotEmpty()) {
                problems += "${mutation.name} on $backend: ${found.joinToString()}\n  before: ${before.render()}\n  after:  ${after.model.render()}"
                return null
            }
            return after.model
        }

        for (mutation in generated.mutations) {
            for (model in start) {
                var current = model
                repeat(repeats) { current = step(mutation, current) ?: return@repeat }
            }
        }
        val random = Random(11)
        for (model in start) {
            var current = model
            repeat(walk) {
                val mutation = generated.mutations.random(random)
                current = step(mutation, current) ?: current
            }
        }

        // Operators that need a rarer state get a longer search: random walks that try them at
        // every step.
        val search = Random(13)
        val unions = (2..4).flatMap { k ->
            start.windowed(k) { group -> group.drop(1).foldIndexed(group.first()) { i, union, model -> union.union(model, "_$i") } }
        }
        for (model in start + unions) {
            var current = model
            repeat(searchSteps) {
                val pending = generated.mutations.filter { it.name !in applied }
                if (pending.isEmpty()) return@repeat
                pending.forEach { step(it, current) }
                current = step(generated.mutations.random(search), current) ?: current
            }
        }

        if (problems.isNotEmpty()) fail<Unit>(problems.distinct().take(5).joinToString("\n"))
        val dead = generated.mutations.map { it.name }.filter { it !in applied }
        assertTrue(dead.isEmpty(), "never applied on $backend: $dead\nwarnings: ${generated.warnings}")
    }
}
