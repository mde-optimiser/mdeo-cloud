import {
    createInterface,
    createType,
    createExternalInterface,
    Optional,
    Ref,
    Union,
    type ASTType
} from "@mdeo/language-common";
import type { ClassType, EnumEntryType, EnumType, PropertyType } from "@mdeo/language-metamodel";
import type { ModelTransformationType } from "@mdeo/language-model-transformation";

/**
 * External reference to Class interface from language-metamodel.
 * Used for referencing classes in mutation operators.
 */
export const MetamodelClass = createExternalInterface<ClassType>("Class");

/**
 * External reference to Property interface from language-metamodel.
 * Used for referencing edge properties in mutation operators.
 */
export const Property = createExternalInterface<PropertyType>("Property");

/**
 * External reference to Enum interface from language-metamodel.
 * Used for enum literals in operator arguments.
 */
export const MetamodelEnum = createExternalInterface<EnumType>("Enum");

/**
 * External reference to EnumEntry interface from language-metamodel.
 * Used for enum literals in operator arguments.
 */
export const MetamodelEnumEntry = createExternalInterface<EnumEntryType>("EnumEntry");

/**
 * External reference to ModelTransformation interface from language-model-transformation.
 * Used for referencing model transformation files in the search section.
 */
export const ModelTransformation = createExternalInterface<ModelTransformationType>("ModelTransformation");

/**
 * The value of an operator argument: a literal (no expressions, no `null`).
 */
export const ConfigLiteral = createInterface("ConfigMdeoLiteral").attrs({});
export type ConfigLiteralType = ASTType<typeof ConfigLiteral>;

/**
 * An int literal of an operator argument, e.g. `4` or `-1`.
 */
export const IntLiteral = createInterface("ConfigMdeoIntLiteral").extends(ConfigLiteral).attrs({
    value: Number
});
export type IntLiteralType = ASTType<typeof IntLiteral>;

/**
 * A long literal of an operator argument, e.g. `42L`. The value is the literal as written.
 */
export const LongLiteral = createInterface("ConfigMdeoLongLiteral").extends(ConfigLiteral).attrs({
    value: String
});
export type LongLiteralType = ASTType<typeof LongLiteral>;

/**
 * A float literal of an operator argument, e.g. `2.5f` or `2.5E-3f`. The value is the literal as
 * written.
 */
export const FloatLiteral = createInterface("ConfigMdeoFloatLiteral").extends(ConfigLiteral).attrs({
    value: String
});
export type FloatLiteralType = ASTType<typeof FloatLiteral>;

/**
 * A double literal of an operator argument, e.g. `2.5`, `1e3` or `2.5d`. The value is the
 * literal as written.
 */
export const DoubleLiteral = createInterface("ConfigMdeoDoubleLiteral").extends(ConfigLiteral).attrs({
    value: String
});
export type DoubleLiteralType = ASTType<typeof DoubleLiteral>;

/**
 * A string literal of an operator argument.
 */
export const StringLiteral = createInterface("ConfigMdeoStringLiteral").extends(ConfigLiteral).attrs({
    value: String
});
export type StringLiteralType = ASTType<typeof StringLiteral>;

/**
 * A boolean literal of an operator argument (`true` / `false`).
 */
export const BooleanLiteral = createInterface("ConfigMdeoBooleanLiteral")
    .extends(ConfigLiteral)
    .attrs({
        value: Union("true", "false")
    });
export type BooleanLiteralType = ASTType<typeof BooleanLiteral>;

/**
 * `null` written as an operator argument. It parses so that the validator can explain that an
 * argument is left out instead; it is never a valid value.
 */
export const NullLiteral = createInterface("ConfigMdeoNullLiteral").extends(ConfigLiteral).attrs({});
export type NullLiteralType = ASTType<typeof NullLiteral>;

/**
 * An enum literal of an operator argument, `Enum.ENTRY`, resolved in the problem's metamodel.
 */
export const EnumLiteral = createInterface("ConfigMdeoEnumLiteral")
    .extends(ConfigLiteral)
    .attrs({
        enum: Ref(() => MetamodelEnum),
        entry: Ref(() => MetamodelEnumEntry)
    });
export type EnumLiteralType = ASTType<typeof EnumLiteral>;

/**
 * A list literal of an operator argument, `[a, b, …]`, for `List<T>` and `Set<T>` inputs.
 */
export const ListLiteral = createInterface("ConfigMdeoListLiteral")
    .extends(ConfigLiteral)
    .attrs({
        elements: [ConfigLiteral]
    });
export type ListLiteralType = ASTType<typeof ListLiteral>;

/**
 * One argument of a `with { }` block: `name = literal`, binding the `in` declaration `name` of
 * the operator's transformation.
 */
export const OperatorArgument = createInterface("ConfigMdeoOperatorArgument").attrs({
    name: String,
    value: ConfigLiteral
});
export type OperatorArgumentType = ASTType<typeof OperatorArgument>;

/**
 * The `with { … }` block of a `using` entry.
 */
export const OperatorArguments = createInterface("ConfigMdeoOperatorArguments").attrs({
    arguments: [OperatorArgument]
});
export type OperatorArgumentsType = ASTType<typeof OperatorArguments>;

/**
 * A "using" path declaration for model transformation files, optionally with arguments for the
 * transformation's inputs. Each entry is its own operator, even when two name the same file.
 * Format: using "path/to/transformation.mt" [with { name = literal … }]
 */
export const UsingPath = createInterface("ConfigMdeoUsingPath").attrs({
    path: String,
    with: Optional(OperatorArguments)
});

/**
 * Type representing a UsingPath AST node.
 */
export type UsingPathType = ASTType<typeof UsingPath>;

/**
 * A class-based mutation operator.
 * Supports: create ClassName, delete ClassName, mutate ClassName
 */
export const ClassMutation = createInterface("ConfigMdeoClassMutation").attrs({
    operator: Union("create", "delete", "mutate"),
    class: Ref(() => MetamodelClass)
});

/**
 * Type representing a ClassMutation AST node.
 */
export type ClassMutationType = ASTType<typeof ClassMutation>;

/**
 * An edge-based mutation operator.
 * Supports: add ClassName.edgeName, remove ClassName.edgeName, mutate ClassName.edgeName
 */
export const EdgeMutation = createInterface("ConfigMdeoEdgeMutation").attrs({
    operator: Union("add", "remove", "mutate"),
    class: Ref(() => MetamodelClass),
    edge: Ref(() => Property)
});

/**
 * Type representing an EdgeMutation AST node.
 */
export type EdgeMutationType = ASTType<typeof EdgeMutation>;

/**
 * Mutations block in the search section.
 * Contains using paths and mutation operators.
 */
export const MutationsBlock = createInterface("ConfigMdeoMutationsBlock").attrs({
    usingPaths: [UsingPath],
    classMutations: [ClassMutation],
    edgeMutations: [EdgeMutation]
});

/**
 * Type representing a MutationsBlock AST node.
 */
export type MutationsBlockType = ASTType<typeof MutationsBlock>;

/**
 * Crossovers block in the search section.
 * Contains the crossover operators (transformations with model inputs), used with
 * `variation = genetic`.
 */
export const CrossoversBlock = createInterface("ConfigMdeoCrossoversBlock").attrs({
    usingPaths: [UsingPath]
});

/**
 * Type representing a CrossoversBlock AST node.
 */
export type CrossoversBlockType = ASTType<typeof CrossoversBlock>;

/**
 * Search section interface.
 * Defines the mutations and crossovers blocks for the search operators.
 */
export const SearchSection = createInterface("ConfigMdeoSearchSection").attrs({
    mutations: [MutationsBlock],
    crossovers: [CrossoversBlock]
});

/**
 * Type representing a SearchSection AST node.
 */
export type SearchSectionType = ASTType<typeof SearchSection>;

/**
 * Bare integer step size.
 * Example: step = 3
 */
export const MutationStepNumeric = createInterface("ConfigMdeoMutationStepNumeric").attrs({
    value: Number
});
export type MutationStepNumericType = ASTType<typeof MutationStepNumeric>;

/**
 * Fixed step size with no argument (defaults to 1 at runtime).
 * Example: step = fixed
 */
export const MutationStepFixed = createInterface("ConfigMdeoMutationStepFixed").attrs({});
export type MutationStepFixedType = ASTType<typeof MutationStepFixed>;

/**
 * Fixed step size with an explicit integer argument.
 * Example: step = fixed(3)
 */
export const MutationStepFixedN = createInterface("ConfigMdeoMutationStepFixedN").attrs({
    n: Number
});
export type MutationStepFixedNType = ASTType<typeof MutationStepFixedN>;

/**
 * Random step size drawn uniformly from [lower, upper) each call.
 * Example: step = interval(1, 5)
 */
export const MutationStepInterval = createInterface("ConfigMdeoMutationStepInterval").attrs({
    lower: Number,
    upper: Number
});
export type MutationStepIntervalType = ASTType<typeof MutationStepInterval>;

/**
 * Union of all valid mutation step value forms.
 */
export const MutationStepValue = createType("ConfigMdeoMutationStepValue").types(
    MutationStepNumeric,
    MutationStepFixed,
    MutationStepFixedN,
    MutationStepInterval
);
export type MutationStepValueType = ASTType<typeof MutationStepValue>;

/**
 * The `mutation { }` nested block inside the `parameters` block.
 * Collects all mutation.* configuration keys as direct named fields.
 */
export const MutationBlock = createInterface("ConfigMdeoMutationBlock").attrs({
    step: [MutationStepValue],
    strategy: [Union("random", "repetitive")],
    selection: [Union("random")],
    application: [Union("random")],
    credit: [Union("random")],
    repair: [Union("default")]
});
export type MutationBlockType = ASTType<typeof MutationBlock>;

/**
 * The `archive { }` nested block inside the `parameters` block.
 * Used only by PESA2 and PAES algorithms.
 */
export const ArchiveBlock = createInterface("ConfigMdeoArchiveBlock").attrs({
    size: [Number]
});
export type ArchiveBlockType = ASTType<typeof ArchiveBlock>;

/**
 * The `crossover { }` nested block inside the `parameters` block, used with
 * `variation = genetic`.
 *
 * - `rate`     — probability that crossover is applied to a group of parents, in [0, 1]
 * - `parents`  — parents per crossover (≥ 2); every crossover yields one child per parent
 * - `strategy` — how a crossover operator is chosen
 */
export const CrossoverBlock = createInterface("ConfigMdeoCrossoverBlock").attrs({
    rate: [Number],
    parents: [Number],
    strategy: [Union("random")]
});
export type CrossoverBlockType = ASTType<typeof CrossoverBlock>;

/**
 * The `parameters { }` block in the solver section.
 * Contains population, variation, and optional sub-blocks.
 */
export const AlgorithmParameters = createInterface("ConfigMdeoAlgorithmParameters").attrs({
    population: [Number],
    variation: [Union("mutation", "genetic")],
    crossover: [CrossoverBlock],
    mutation: [MutationBlock],
    bisections: [Number],
    archive: [ArchiveBlock]
});
export type AlgorithmParametersType = ASTType<typeof AlgorithmParameters>;

/**
 * The `termination { }` block in the solver section.
 * Multiple conditions may be specified; they are combined as OR (first hit stops the run).
 *
 * - `evolutions` — number of generations (actual evaluations = evolutions × population)
 * - `time`       — wall-clock time limit in seconds
 * - `delta`      — convergence threshold (percentage)
 * - `iterations` — consecutive steps below delta before terminating (default 3)
 */
export const TerminationBlock = createInterface("ConfigMdeoTerminationBlock").attrs({
    evolutions: [Number],
    time: [Number],
    delta: [Number],
    iterations: [Number]
});
export type TerminationBlockType = ASTType<typeof TerminationBlock>;

/**
 * The solver `{ }` section content.
 *
 * - `algorithm`     — algorithm name (`NSGAII` | `IBEA` | … | `PAES`)
 * - `parameters`    — algorithm parameters block
 * - `termination`   — termination condition block
 * - `batches`       — number of independent runs
 */
export const SolverSection = createInterface("ConfigMdeoSolverSection").attrs({
    algorithm: [Union("NSGAII", "IBEA", "SPEA2", "SMSMOEA", "VEGA", "PESA2", "PAES")],
    parameters: [AlgorithmParameters],
    termination: [TerminationBlock],
    batches: [Number]
});
export type SolverSectionType = ASTType<typeof SolverSection>;

/**
 * The `timeout { }` block in the runtime section.
 *
 * - `script`         — per-script evaluation timeout in seconds; optional,
 *                      defaults to 30 s on the backend
 * - `transformation` — per-transformation execution timeout in seconds; optional,
 *                      unbound by default
 */
export const RuntimeTimeoutBlock = createInterface("ConfigMdeoRuntimeTimeoutBlock").attrs({
    script: [Number],
    transformation: [Number]
});
export type RuntimeTimeoutBlockType = ASTType<typeof RuntimeTimeoutBlock>;

/**
 * The `resources { }` block in the runtime section.
 *
 * All fields are optional and represent upper bounds rather than exact values.
 *
 * - `threads`        — maximum total threads across all nodes; unbound when absent
 * - `nodes`          — maximum number of worker nodes to use; unbound when absent
 * - `threadsPerNode` — maximum threads per individual node; unbound when absent
 */
export const RuntimeResourcesBlock = createInterface("ConfigMdeoRuntimeResourcesBlock").attrs({
    threads: [Number],
    nodes: [Number],
    threadsPerNode: [Number]
});
export type RuntimeResourcesBlockType = ASTType<typeof RuntimeResourcesBlock>;

/**
 * The runtime `{ }` section content.
 *
 * - `timeout`   — script and transformation execution timeout configuration
 * - `backend`   — the graph backend to use (`MDEO` or `Tinker`); defaults to `MDEO`
 * - `resources` — resource constraints for the distributed execution
 */
export const RuntimeSection = createInterface("ConfigMdeoRuntimeSection").attrs({
    timeout: [RuntimeTimeoutBlock],
    backend: [Union("MDEO", "Tinker")],
    resources: [RuntimeResourcesBlock]
});
export type RuntimeSectionType = ASTType<typeof RuntimeSection>;
