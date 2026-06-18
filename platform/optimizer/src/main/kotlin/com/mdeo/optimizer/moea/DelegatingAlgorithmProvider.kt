package com.mdeo.optimizer.moea

import com.mdeo.optimizer.config.SolverConfig
import org.moeaframework.algorithm.Algorithm
import org.moeaframework.core.TypedProperties
import org.moeaframework.core.fitness.HypervolumeFitnessEvaluator
import org.moeaframework.core.initialization.RandomInitialization
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
        return props
    }

    override fun getAlgorithm(name: String, properties: TypedProperties, problem: Problem): Algorithm {
        val populationSize = properties.getInt("populationSize", 40)
        val variation = DelegatingVariation()
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
            "SMSEMOA" -> DelegatingSMSEMOA(
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
                DelegatingPAES(problem, variation, bisections, archiveSize, coordinator)
            }
            else -> throw IllegalArgumentException(
                "Unsupported delegating algorithm: $name. " +
                    "Supported: NSGAII, SPEA2, IBEA, SMSEMOA, VEGA, PESA2, PAES"
            )
        }
    }
}
