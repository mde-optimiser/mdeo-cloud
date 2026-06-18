package com.mdeo.optimizer.config

import com.mdeo.optimizer.rulegen.MutationRuleSpec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Top-level optimizer execution payload, normalised from config problem/goal/search/solver/runtime sections.
 */
@Serializable
data class OptimizationConfig(
    val problem: ProblemConfig,
    val goal: GoalConfig,
    val search: SearchConfig,
    val solver: SolverConfig,
    val runtime: RuntimeConfig = RuntimeConfig()
)

/**
 * Problem section: metamodel and initial model references.
 *
 * @param metamodelPath Path to the metamodel resource.
 * @param modelPath Path to the initial model resource.
 */
@Serializable
data class ProblemConfig(
    val metamodelPath: String,
    val modelPath: String
)

/**
 * Goal section: objectives, constraints, and optional multiplicty refinements.
 *
 * @param objectives Objective functions to optimise.
 * @param constraints Constraint functions that must be satisfied.
 * @param refinements Optional metamodel refinements used for rule generation.
 */
@Serializable
data class GoalConfig(
    val objectives: List<ObjectiveConfig>,
    val constraints: List<ConstraintConfig>,
    val refinements: List<RefinementConfig> = emptyList()
)

/**
 * Single objective or constraint function configuration.
 *
 * @param type Whether to minimise or maximise the function value.
 * @param path Path to the script file containing the function.
 * @param functionName Name of the function within the script.
 */
@Serializable
data class ObjectiveConfig(
    val type: ObjectiveTendency,
    val path: String,
    val functionName: String
)

@Serializable
enum class ObjectiveTendency {
    MAXIMIZE,
    MINIMIZE;

    /**
     * Returns the numerical direction: -1 for minimise, +1 for maximise.
     * MOEA framework uses minimisation internally, so maximisation objectives are negated.
     */
    fun numericalDirection(): Double = when (this) {
        MINIMIZE -> 1.0
        MAXIMIZE -> -1.0
    }
}

/**
 * Single constraint function configuration.
 *
 * @param path Path to the script file containing the function.
 * @param functionName Name of the function within the script.
 */
@Serializable
data class ConstraintConfig(
    val path: String,
    val functionName: String
)

/**
 * Multiplicity refinement that tightens the lower or upper bound of a metamodel reference
 * for the purpose of rule generation.
 *
 * When a non-empty list of refinements is passed to
 * [com.mdeo.optimizer.rulegen.MutationRuleGenerator.generate], a second generation pass is
 * performed using a modified [com.mdeo.optimizer.rulegen.MetamodelInfo] where the specified
 * reference multiplicities are replaced by the values declared here.  Rules produced in the
 * second pass are prefixed with `"S_"` to distinguish them from the base rules.
 *
 * @param className The metamodel class that owns the reference.
 * @param fieldName The reference (field) name to refine.
 * @param lower     The tightened lower bound (>= original lower).
 * @param upper     The tightened upper bound (<= original upper, or -1 for unbounded).
 */
@Serializable
data class RefinementConfig(
    val className: String,
    val fieldName: String,
    val lower: Int,
    val upper: Int
)

/** Search section: mutation transformation configuration. */
@Serializable
data class SearchConfig(
    val mutations: MutationsConfig
)

/**
 * Mutation operator source configuration.
 *
 * @param usingPaths Paths to hand-written transformation files.
 * @param generate Auto-generation specs; when non-empty, [com.mdeo.optimizer.rulegen.MutationRuleGenerator]
 *   synthesises additional operators from the metamodel and merges them with [usingPaths].
 */
@Serializable
data class MutationsConfig(
    val usingPaths: List<String> = emptyList(),
    val generate: List<MutationRuleSpec> = emptyList()
)

/**
 * The underlying solver framework to use.
 * Currently only MOEA Framework is supported.
 */
@Serializable
enum class SolverProvider { MOEA }

/**
 * Solver section: algorithm configuration + termination.
 *
 * @property provider Underlying solver framework.
 * @property algorithm Evolutionary algorithm to use.
 * @property parameters Algorithm hyper-parameters.
 * @property termination Termination conditions for the search.
 * @property batches Number of independent optimization runs.
 */
@Serializable
data class SolverConfig(
    val provider: SolverProvider = SolverProvider.MOEA,
    val algorithm: AlgorithmType = AlgorithmType.NSGAII,
    val parameters: AlgorithmParameters = AlgorithmParameters(),
    val termination: TerminationConfig = TerminationConfig(),
    val batches: Int = 1
)

/** Supported evolutionary algorithm types. */
@Serializable
enum class AlgorithmType {
    NSGAII, IBEA, SPEA2, SMSMOEA, VEGA, PESA2, PAES
}

/**
 * Algorithm hyper-parameters.
 *
 * @param population Population size.
 * @param variation Variation operator type.
 * @param mutation Mutation configuration.
 * @param bisections Bisections for PESA2/PAES archive grid.
 * @param archiveSize Archive capacity for PESA2/PAES.
 */
@Serializable
data class AlgorithmParameters(
    val population: Int = 40,
    val variation: VariationType = VariationType.MUTATION,
    val mutation: MutationParameters = MutationParameters(),
    val bisections: Int? = null,
    val archiveSize: Int? = null
)

/** Variation operator type. */
@Serializable
enum class VariationType { MUTATION, GENETIC, PROBABILISTIC }

/**
 * Mutation operator parameters.
 *
 * @param step How many mutation steps to apply per offspring.
 * @param strategy The operator selection strategy.
 */
@Serializable
data class MutationParameters(
    val step: MutationStepConfig = MutationStepConfig.Fixed(1),
    val strategy: MutationStrategy = MutationStrategy.RANDOM
)

/** Operator selection strategy for mutation. */
@Serializable
enum class MutationStrategy { RANDOM, REPETITIVE }

/** Configures the number of mutation steps applied per offspring, either fixed or random in an interval. */
@Serializable
sealed class MutationStepConfig {
    /**
     * Always apply exactly [n] steps. 
     */
    @Serializable
    @SerialName("Fixed")
    data class Fixed(val n: Int) : MutationStepConfig()

    /**
     * Randomly pick a count from [[lower], [upper]). 
     */
    @Serializable
    @SerialName("Interval")
    data class Interval(val lower: Int, val upper: Int) : MutationStepConfig()
}

/**
 * Termination conditions for the evolutionary search.
 * All non-null conditions form a compound OR termination.
 *
 * @param evolutions Stop after this many generations (multiplied by population size for evaluations).
 * @param time Stop after this many seconds.
 * @param delta Reserved for convergence-based stopping (not yet implemented).
 * @param iterations Reserved for iteration-based stopping (not yet implemented).
 */
@Serializable
data class TerminationConfig(
    val evolutions: Int? = null,
    val time: Int? = null,
    val delta: Double? = null,
    val iterations: Int? = null
)

/**
 * Configuration option for how the ScriptModel should convert model data.
 */
@Serializable
enum class ModelConversionStrategy {
    /**
     * Convert all model data to ScriptModel instances eagerly at binding time. 
     */
    EAGER,
    /**
     * Convert model data lazily on first access. 
     */
    LAZY
}

/**
 * Runtime configuration for the optimization execution.
 *
 * @property timeout Timeout configuration for script and transformation evaluation.
 * @property backend The graph backend to use. Defaults to MDEO when absent.
 * @property resources Resource constraints for distributed execution.
 */
@Serializable
data class RuntimeConfig(
    val timeout: TimeoutConfig? = null,
    val backend: GraphBackendType? = null,
    val resources: ResourcesConfig? = null
) {
    /**
     * Timeout configuration for per-evaluation operations.
     * Values arrive from the config DSL in seconds and are converted to milliseconds
     * at the platform boundary.
     *
     * @property script Per-script evaluation timeout in seconds. Null means use env var default.
     * @property transformation Per-transformation execution timeout in seconds. Null means use env var default.
     */
    @Serializable
    data class TimeoutConfig(
        val script: Int? = null,
        val transformation: Int? = null
    ) {
        /**
         * Returns the effective script timeout in milliseconds, converting from seconds.
         * Falls back to [fallbackMs] when no value is configured.
         *
         * @param fallbackMs Fallback timeout in milliseconds from environment configuration.
         * @return The effective timeout in milliseconds.
         */
        fun effectiveScriptTimeoutMs(fallbackMs: Long): Long =
            script?.let { it.toLong() * 1000L } ?: fallbackMs

        /**
         * Returns the effective transformation timeout in milliseconds, converting from seconds.
         * Falls back to [fallbackMs] when no value is configured.
         *
         * @param fallbackMs Fallback timeout in milliseconds from environment configuration.
         * @return The effective timeout in milliseconds.
         */
        fun effectiveTransformationTimeoutMs(fallbackMs: Long): Long =
            transformation?.let { it.toLong() * 1000L } ?: fallbackMs
    }

    /**
     * Resource constraints for distributed optimization.
     * All values are upper bounds; null means unbound.
     *
     * @property threads Maximum total threads across all nodes.
     * @property nodes Maximum number of worker nodes to use.
     * @property threadsPerNode Maximum threads per individual node.
     */
    @Serializable
    data class ResourcesConfig(
        val threads: Int? = null,
        val nodes: Int? = null,
        val threadsPerNode: Int? = null
    )
}

/**
 * Supported graph backend implementations.
 */
@Serializable
enum class GraphBackendType {
    MDEO,
    Tinker
}
