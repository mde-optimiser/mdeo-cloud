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
 * Data for the mutations block.
 * `usingPaths` is a flat list of absolute file-system paths, matching
 * Kotlin MutationsConfig.usingPaths (List<String>).
 */
export interface MutationsBlockData {
    /**
     * Absolute paths to the model transformation files.
     */
    usingPaths: string[];
    classMutations: ClassMutationData[];
    edgeMutations: EdgeMutationData[];
}

/**
 * Data extracted from the search section.
 */
export interface SearchSectionData {
    mutations?: MutationsBlockData;
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
 * Algorithm parameters block.
 * Maps to Kotlin AlgorithmParameters.
 * `variation` matches Kotlin VariationType enum names (uppercase).
 * `archiveSize` matches Kotlin AlgorithmParameters.archiveSize directly.
 */
export interface AlgorithmParametersData {
    population?: number;
    variation?: "MUTATION" | "GENETIC" | "PROBABILISTIC";
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
