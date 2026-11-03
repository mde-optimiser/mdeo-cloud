/**
 * Data for a class mutation (create/delete/mutate).
 * Converted to MutationRuleSpec generate entries when building the optimizer execution request.
 */
export interface ClassMutationData {
    operator: "create" | "delete" | "mutate";
    className: string;
}

/**
 * Data for an edge mutation (add/remove/mutate).
 * Converted to MutationRuleSpec generate entries when building the optimizer execution request.
 */
export interface EdgeMutationData {
    operator: "add" | "remove" | "mutate";
    className: string;
    edgeName: string;
}

/**
 * The value of an operator argument, as written in the config.
 * Maps to the Kotlin sealed class ConfigLiteral; `type` matches its @SerialName discriminators.
 * The kind is the literal's own (an int literal stays `int` for a double input): the engine
 * converts numbers to the input's type.
 */
export type ConfigLiteralData =
    | { type: "int"; value: number }
    | { type: "long"; value: number }
    | { type: "float"; value: number }
    | { type: "double"; value: number }
    | { type: "string"; value: string }
    | { type: "boolean"; value: boolean }
    | { type: "enum"; enumName: string; literal: string }
    | { type: "list"; elements: ConfigLiteralData[] };

/**
 * A hand-written operator: a `using` entry of the mutations or crossovers block.
 * Maps to Kotlin OperatorRef. Each entry is its own operator, even when two name the same file.
 */
export interface OperatorRefData {
    /**
     * Absolute path of the model transformation file.
     */
    path: string;
    /**
     * The values the `with { }` block passes to the transformation's inputs, by input name.
     */
    arguments: Record<string, ConfigLiteralData>;
}

/**
 * Data for the mutations block.
 * `operators` matches Kotlin MutationsConfig.operators; the class and edge mutations are
 * converted to its `generate` list when building the optimizer execution request.
 */
export interface MutationsBlockData {
    /**
     * The hand-written mutation operators.
     */
    operators: OperatorRefData[];
    classMutations: ClassMutationData[];
    edgeMutations: EdgeMutationData[];
}

/**
 * Data for the crossovers block.
 * Maps to Kotlin CrossoversConfig.
 */
export interface CrossoversBlockData {
    /**
     * The crossover operators.
     */
    operators: OperatorRefData[];
}

/**
 * Data extracted from the search section.
 */
export interface SearchSectionData {
    mutations?: MutationsBlockData;
    crossovers?: CrossoversBlockData;
}

/**
 * Mutation step configuration.
 * `type` discriminator matches Kotlin @SerialName values ("Fixed" / "Interval").
 */
export type MutationStepConfig = { type: "Fixed"; n: number } | { type: "Interval"; lower: number; upper: number };

/**
 * Mutation parameters block.
 * Maps to Kotlin MutationParameters.
 * `strategy` matches Kotlin MutationStrategy enum names (uppercase).
 */
export interface MutationBlockData {
    step?: MutationStepConfig;
    strategy?: "RANDOM" | "REPETITIVE";
}

/**
 * Crossover parameters block.
 * Maps to Kotlin CrossoverParameters. Every crossover yields one child per parent, so
 * `children` always equals `parents`.
 * `strategy` matches Kotlin CrossoverStrategy enum names (uppercase).
 */
export interface CrossoverBlockData {
    rate: number;
    parents: number;
    children: number;
    strategy: "RANDOM";
}

/**
 * Algorithm parameters block.
 * Maps to Kotlin AlgorithmParameters.
 * `variation` matches Kotlin VariationType enum names (uppercase).
 * `archiveSize` matches Kotlin AlgorithmParameters.archiveSize directly.
 */
export interface AlgorithmParametersData {
    population?: number;
    variation?: "MUTATION" | "GENETIC";
    crossover?: CrossoverBlockData;
    mutation?: MutationBlockData;
    bisections?: number;
    archiveSize?: number;
}

/**
 * Termination block.
 * Maps to Kotlin TerminationConfig.
 */
export interface TerminationBlockData {
    evolutions?: number;
    time?: number;
    delta?: number;
    iterations?: number;
}

/**
 * Solver section data.
 * Maps to Kotlin SolverConfig.
 * `provider` and `algorithm` match Kotlin enum names (uppercase).
 */
export interface SolverSectionData {
    algorithm?: "NSGAII" | "IBEA" | "SPEA2" | "SMSMOEA" | "VEGA" | "PESA2" | "PAES";
    parameters?: AlgorithmParametersData;
    termination?: TerminationBlockData;
    batches?: number;
}

/**
 * Timeout configuration block inside the runtime section.
 * Maps to Kotlin RuntimeConfig.TimeoutConfig.
 * Values are in seconds.
 */
export interface RuntimeTimeoutData {
    script?: number;
    transformation?: number;
}

/**
 * Resource constraints block inside the runtime section.
 * Maps to Kotlin RuntimeConfig.ResourcesConfig.
 * All values are upper bounds; absent means unbound.
 */
export interface RuntimeResourcesData {
    threads?: number;
    nodes?: number;
    threadsPerNode?: number;
}

/**
 * Runtime section data.
 * Maps to Kotlin RuntimeConfig.
 */
export interface RuntimeSectionData {
    timeout?: RuntimeTimeoutData;
    backend?: "MDEO" | "Tinker";
    resources?: RuntimeResourcesData;
}

/**
 * Combined response from the MDEO request handler.
 * Keys match Kotlin config classes directly so no conversion
 * is needed in the execution handler.
 */
export interface MdeoRequestResponse {
    search?: SearchSectionData;
    solver?: SolverSectionData;
    runtime?: RuntimeSectionData;
}
