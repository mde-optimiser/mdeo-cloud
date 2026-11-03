package com.mdeo.optimizer.config

import com.mdeo.modeltransformation.runtime.EnumLiteral
import com.mdeo.optimizer.rulegen.MutationRuleSpec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

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
 * Goal section: objectives, constraints, and optional multiplicity refinements.
 *
 * @param objectives Objective functions to optimise.
 * @param constraints Constraint functions that must be satisfied.
 * @param refinements Tighter association end bounds the generated mutation operators keep.
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
 * Multiplicity refinement that tightens the bounds of an association end for the search.
 *
 * The generated mutation operators are built against the metamodel with every refinement
 * applied, so they keep the refined bounds; the initial model must already satisfy them.
 *
 * @param className The class declaring the association end.
 * @param fieldName The association end to refine.
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

/**
 * Search section: the operators that vary solutions.
 *
 * @param mutations Mutation operators: change one solution.
 * @param crossovers Crossover operators: change a copy of one parent using others as read-only
 *   model inputs. Only used with [VariationType.GENETIC].
 */
@Serializable
data class SearchConfig(
    val mutations: MutationsConfig,
    val crossovers: CrossoversConfig = CrossoversConfig()
)

/**
 * Mutation operator source configuration.
 *
 * @param operators Hand-written transformation files (`using "path.mt" [with { … }]`), each its own
 *   operator, also when two name the same file.
 * @param generate Auto-generation specs; when non-empty, [com.mdeo.optimizer.rulegen.MutationRuleGenerator]
 *   synthesises additional operators from the metamodel and merges them with [operators].
 */
@Serializable
data class MutationsConfig(
    val operators: List<OperatorRef> = emptyList(),
    val generate: List<MutationRuleSpec> = emptyList()
)

/**
 * Crossover operator source configuration (`search { crossovers { using "path.mt" } }`).
 *
 * @param operators Hand-written crossover transformation files: their required inputs are model
 *   inputs only (`in donor: model`, or one `in donors: List<model>`), the other parents of a group.
 */
@Serializable
data class CrossoversConfig(
    val operators: List<OperatorRef> = emptyList()
)

/**
 * One hand-written operator of the config: a transformation file and the values its inputs are
 * given (`using "./move-component.mt" with { maxCandidates = 4 }`).
 *
 * Operators are identified by their index in the list of all operators of their kind sorted by
 * [path] and then [canonicalArguments] ([com.mdeo.optimizer.operators.OperatorCatalog]): stable,
 * identical on every node, and distinct for two entries of one file with different arguments.
 *
 * @param path Absolute project path of the `.mt` file (for a generated mutation, its rule name).
 * @param arguments Values of the file's scalar, enum and collection inputs, by input name; the
 *   others take their defaults. Bound like the arguments of a `call`.
 */
@Serializable
data class OperatorRef(
    val path: String,
    val arguments: Map<String, ConfigLiteral> = emptyMap()
) {
    /**
     * The arguments sorted by name as `name = value`, joined by `, `; empty without arguments.
     * The second sort key of operator ids.
     */
    val canonicalArguments: String
        get() = arguments.entries.sortedBy { it.key }.joinToString(", ") { (name, value) -> "$name = ${value.render()}" }

    /** The operator for people: the file's base name and its arguments, e.g. `move-component.mt (maxCandidates = 4)`. */
    val label: String
        get() {
            val name = path.substringAfterLast('/')
            return if (arguments.isEmpty()) name else "$name ($canonicalArguments)"
        }

    /** The arguments as the engine takes them ([com.mdeo.modeltransformation.runtime.TransformationEngine.execute]). */
    fun engineArguments(): Map<String, Any?> = arguments.mapValues { (_, value) -> value.toEngineValue() }
}

/**
 * A literal of an operator argument in the config, as written: the kind is the literal's, not the
 * parameter's (an int literal stays [IntValue] for a `double` input; the engine converts it).
 */
@Serializable
sealed class ConfigLiteral {

    /** The literal in config syntax (`4`, `4L`, `2.5f`, `2.5`, `"a"`, `true`, `Kind.SERVICE`, `[a, b]`). */
    abstract fun render(): String

    /** The value passed to the engine for this literal. */
    abstract fun toEngineValue(): Any?

    /** An `int` literal. */
    @Serializable
    @SerialName("int")
    data class IntValue(val value: Int) : ConfigLiteral() {
        override fun render(): String = value.toString()
        override fun toEngineValue(): Any = value
    }

    /** A `long` literal (`42L`). */
    @Serializable
    @SerialName("long")
    data class LongValue(val value: Long) : ConfigLiteral() {
        override fun render(): String = "${value}L"
        override fun toEngineValue(): Any = value
    }

    /** A `float` literal (`2.5f`). */
    @Serializable
    @SerialName("float")
    data class FloatValue(val value: Float) : ConfigLiteral() {
        override fun render(): String = "${value}f"
        override fun toEngineValue(): Any = value
    }

    /** A `double` literal. */
    @Serializable
    @SerialName("double")
    data class DoubleValue(val value: Double) : ConfigLiteral() {
        override fun render(): String = value.toString()
        override fun toEngineValue(): Any = value
    }

    /** A `string` literal. */
    @Serializable
    @SerialName("string")
    data class StringValue(val value: String) : ConfigLiteral() {
        override fun render(): String = JsonPrimitive(value).toString()
        override fun toEngineValue(): Any = value
    }

    /** `true` or `false`. */
    @Serializable
    @SerialName("boolean")
    data class BooleanValue(val value: Boolean) : ConfigLiteral() {
        override fun render(): String = value.toString()
        override fun toEngineValue(): Any = value
    }

    /** An enum literal of the problem metamodel (`Kind.SERVICE`). */
    @Serializable
    @SerialName("enum")
    data class EnumValue(val enumName: String, val literal: String) : ConfigLiteral() {
        override fun render(): String = "$enumName.$literal"
        override fun toEngineValue(): Any = EnumLiteral(enumName, literal)
    }

    /** A list literal (`[a, b]`) for a `List<T>` or `Set<T>` input; the engine de-duplicates for a set. */
    @Serializable
    @SerialName("list")
    data class ListValue(val elements: List<ConfigLiteral>) : ConfigLiteral() {
        override fun render(): String = elements.joinToString(", ", "[", "]") { it.render() }
        override fun toEngineValue(): Any = elements.map { it.toEngineValue() }
    }
}

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
 * @param crossover Crossover configuration, used with [VariationType.GENETIC].
 * @param bisections Bisections for PESA2/PAES archive grid.
 * @param archiveSize Archive capacity for PESA2/PAES.
 */
@Serializable
data class AlgorithmParameters(
    val population: Int = 40,
    val variation: VariationType = VariationType.MUTATION,
    val mutation: MutationParameters = MutationParameters(),
    val crossover: CrossoverParameters = CrossoverParameters(),
    val bisections: Int? = null,
    val archiveSize: Int? = null
)

/**
 * How offspring are made.
 *
 * - [MUTATION]: every offspring is a mutated copy of one parent.
 * - [GENETIC]: parents are selected in groups of [CrossoverParameters.parents]; every group yields
 *   one child per parent (a crossover of that parent with the others, or a copy of it), and every
 *   child is then mutated like an offspring of [MUTATION].
 */
@Serializable
enum class VariationType { MUTATION, GENETIC }

/**
 * Crossover parameters (`crossover { rate, parents, strategy }`).
 *
 * @param rate Probability, in `[0, 1]`, that crossover is attempted for a group of parents;
 *   otherwise every child of the group is a copy of its parent (and is still mutated).
 * @param parents Parents per group, at least 2.
 * @param children Children per group: always [parents] (one child per parent); kept so the
 *   payload states it.
 * @param strategy How a crossover operator is chosen for a child.
 */
@Serializable
data class CrossoverParameters(
    val rate: Double = 0.9,
    val parents: Int = 2,
    val children: Int = 2,
    val strategy: CrossoverStrategy = CrossoverStrategy.RANDOM
)

/** How a crossover operator is chosen for a child. */
@Serializable
enum class CrossoverStrategy {
    /** Uniformly among the operators that accept the group's parent count; another untried one if it fails. */
    RANDOM
}

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
