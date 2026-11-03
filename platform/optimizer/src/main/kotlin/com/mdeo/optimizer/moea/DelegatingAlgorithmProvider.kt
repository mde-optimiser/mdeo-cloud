package com.mdeo.optimizer.moea

import com.mdeo.optimizer.config.SolverConfig
import com.mdeo.optimizer.config.VariationType
import org.moeaframework.algorithm.Algorithm
import org.moeaframework.core.TypedProperties
import org.moeaframework.core.fitness.HypervolumeFitnessEvaluator
import org.moeaframework.core.initialization.RandomInitialization
import org.moeaframework.core.operator.Variation
import org.moeaframework.core.population.NondominatedPopulation
import org.moeaframework.core.spi.AlgorithmProvider
import org.moeaframework.problem.Problem

/**
 * MOEA Framework [AlgorithmProvider] that creates delegating algorithm subclasses.
 *
 * Each returned algorithm overrides `evaluateAll`/`evaluate` to dispatch work through
 * a shared [EvaluationCoordinator], and overrides `iterate` to track solution lifecycle.
 * This provider supports the same algorithm names as [OptimizationAlgorithmProvider]:
 * NSGAII, SPEA2, IBEA, SMSEMOA, VEGA, PESA2, and PAES.
 *
 * The variation follows [com.mdeo.optimizer.config.AlgorithmParameters.variation]: a
 * [DelegatingVariation] (arity 1, one offspring per parent) for `mutation`, a
 * [DelegatingCrossoverVariation] (arity `parents`, `parents` offspring per group) for `genetic`.
 * How each algorithm consumes `parents` offspring per call is documented at its delegating
 * subclass in `DelegatingAlgorithms.kt`; PAES takes a mutation operator only and rejects
 * `genetic`.
 *
 * @param coordinator The evaluation coordinator shared by all algorithm instances.
 */
class DelegatingAlgorithmProvider(
    private val coordinator: EvaluationCoordinator
) : AlgorithmProvider() {

    /**
     * Builds MOEA [TypedProperties] from the given [SolverConfig].
     *
     * Extracts population size and optional algorithm-specific parameters (bisections,
     * archive size) into the properties map expected by MOEA algorithm constructors.
     *
     * @param solverConfig The solver configuration to extract properties from.
     * @return A [TypedProperties] instance suitable for [getAlgorithm].
     */
    fun buildProperties(solverConfig: SolverConfig): TypedProperties {
        val props = TypedProperties()
        props.setInt("populationSize", solverConfig.parameters.population)
        solverConfig.parameters.bisections?.let { props.setInt("bisections", it) }
        solverConfig.parameters.archiveSize?.let { props.setInt("archive.size", it) }
        props.setString(VARIATION, solverConfig.parameters.variation.name)
        props.setDouble(CROSSOVER_RATE, solverConfig.parameters.crossover.rate)
        props.setInt(CROSSOVER_PARENTS, solverConfig.parameters.crossover.parents)
        return props
    }

    /**
     * The variation of [properties]: [DelegatingVariation] for `mutation`,
     * [DelegatingCrossoverVariation] for `genetic`.
     *
     * @throws IllegalArgumentException For `genetic` with PAES, which takes a mutation operator only.
     */
    private fun createVariation(name: String, properties: TypedProperties): Variation {
        val type = VariationType.valueOf(properties.getString(VARIATION, VariationType.MUTATION.name))
        return when (type) {
            VariationType.MUTATION -> DelegatingVariation()
            VariationType.GENETIC -> {
                require(name != "PAES") {
                    "PAES is a (1+1) strategy that only takes a mutation operator; " +
                        "variation = genetic is not supported with PAES (use variation = mutation)"
                }
                DelegatingCrossoverVariation(
                    parents = properties.getInt(CROSSOVER_PARENTS, 2),
                    rate = properties.getDouble(CROSSOVER_RATE, 0.9)
                )
            }
        }
    }

    override fun getAlgorithm(name: String, properties: TypedProperties, problem: Problem): Algorithm {
        val populationSize = properties.getInt("populationSize", 40)
        val variation = createVariation(name, properties)
        val initialization = RandomInitialization(problem)

        return when (name) {
            "NSGAII" -> DelegatingNSGAII(
                problem, populationSize, variation, initialization, coordinator
            )
            "SPEA2" -> DelegatingSPEA2(
                problem, populationSize, initialization, variation,
                populationSize, 1, coordinator
            )
            "IBEA" -> DelegatingIBEA(
                problem, populationSize, initialization, variation,
                HypervolumeFitnessEvaluator(problem), coordinator
            )
            // The config's AlgorithmType spells it SMSMOEA; MOEA Framework names it SMSEMOA.
            "SMSEMOA", "SMSMOEA" -> DelegatingSMSEMOA(
                problem, populationSize, initialization, variation,
                HypervolumeFitnessEvaluator(problem), coordinator
            )
            "VEGA" -> DelegatingVEGA(
                problem, populationSize, initialization, variation, coordinator
            )
            "PESA2" -> {
                val bisections = properties.getInt("bisections", 8)
                val archiveSize = properties.getInt("archive.size", 100)
                DelegatingPESA2(
                    problem, populationSize, variation, initialization,
                    bisections, archiveSize, coordinator
                )
            }
            "PAES" -> {
                val bisections = properties.getInt("bisections", 8)
                val archiveSize = properties.getInt("archive.size", 100)
                DelegatingPAES(problem, variation as DelegatingVariation, bisections, archiveSize, coordinator)
            }
            else -> throw IllegalArgumentException(
                "Unsupported delegating algorithm: $name. " +
                    "Supported: NSGAII, SPEA2, IBEA, SMSEMOA, VEGA, PESA2, PAES"
            )
        }
    }

    private companion object {
        /** Property holding the [VariationType] name. */
        const val VARIATION = "variation"

        /** Property holding [com.mdeo.optimizer.config.CrossoverParameters.rate]. */
        const val CROSSOVER_RATE = "crossover.rate"

        /** Property holding [com.mdeo.optimizer.config.CrossoverParameters.parents]. */
        const val CROSSOVER_PARENTS = "crossover.parents"
    }
}
