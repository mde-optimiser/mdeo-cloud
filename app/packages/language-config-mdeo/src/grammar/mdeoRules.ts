import {
    action,
    createRule,
    createTerminal,
    or,
    many,
    optional,
    ref,
    createExternalTerminalRule,
    group
} from "@mdeo/language-common";
import {
    SearchSection,
    SolverSection,
    MutationsBlock,
    CrossoversBlock,
    UsingPath,
    OperatorArguments,
    OperatorArgument,
    ConfigLiteral,
    IntLiteral,
    LongLiteral,
    FloatLiteral,
    DoubleLiteral,
    StringLiteral,
    BooleanLiteral,
    NullLiteral,
    EnumLiteral,
    ListLiteral,
    MetamodelEnum,
    MetamodelEnumEntry,
    CrossoverBlock,
    ClassMutation,
    EdgeMutation,
    MetamodelClass,
    Property,
    MutationStepNumeric,
    MutationStepFixed,
    MutationStepFixedN,
    MutationStepInterval,
    MutationStepValue,
    MutationBlock,
    ArchiveBlock,
    AlgorithmParameters,
    TerminationBlock,
    RuntimeSection,
    RuntimeTimeoutBlock,
    RuntimeResourcesBlock
} from "./mdeoTypes.js";

/**
 * External terminal rules from the base language-common grammar.
 */
const ID = createExternalTerminalRule<string>("ID");
const INT = createExternalTerminalRule<number>("INT");
const FLOAT = createExternalTerminalRule<number>("FLOAT");
const STRING = createExternalTerminalRule<string>("STRING");
const NEWLINE = createExternalTerminalRule<string>("NEWLINE");

/**
 * The digits of a floating point literal, as in the model transformation language: a fraction
 * (`1.5`), a fraction with an exponent (`2.5E-3`) or an exponent alone (`1e3`).
 */
const FLOATING_POINT_DIGITS = /-?[0-9]+(?:\.[0-9]+(?:[eE][+-]?[0-9]+)?|[eE][+-]?[0-9]+)/.source;

/**
 * Long literal terminal (`42L`, `-1l`). Returns the literal as written.
 */
export const CONFIG_MDEO_LONG = createTerminal("CONFIG_MDEO_LONG")
    .returns(String)
    .as(/-?[0-9]+[Ll]/);

/**
 * Float literal terminal (`2.5f`, `2.5E-3F`). Returns the literal as written.
 */
export const CONFIG_MDEO_FLOAT = createTerminal("CONFIG_MDEO_FLOAT")
    .returns(String)
    .as(new RegExp(`${FLOATING_POINT_DIGITS}[Ff]`));

/**
 * Double literal terminal with its suffix (`2.5d`). A floating point literal without a suffix
 * (`2.5`) is a double as well; it is lexed by the common FLOAT terminal.
 */
export const CONFIG_MDEO_DOUBLE = createTerminal("CONFIG_MDEO_DOUBLE")
    .returns(String)
    .as(new RegExp(`${FLOATING_POINT_DIGITS}[Dd]`));

/**
 * The terminals the MDEO grammar adds to the config language, for the number literals of
 * operator arguments.
 */
export const MdeoTerminals = [CONFIG_MDEO_LONG, CONFIG_MDEO_FLOAT, CONFIG_MDEO_DOUBLE];

/**
 * A number written with or without a fraction, e.g. `0.9` or `1`.
 */
export const NumberRule = createRule("ConfigMdeoNumberRule")
    .returns(Number)
    .as(() => [or(FLOAT, INT)]);

/**
 * The word keywords of the MDEO sections that may also be the name of a transformation input
 * (e.g. `size`, `step`, `rate`), so that `with { size = 3 }` parses.
 */
export const ARGUMENT_NAME_KEYWORDS = [
    "using",
    "with",
    "create",
    "delete",
    "mutate",
    "add",
    "remove",
    "mutations",
    "crossovers",
    "mutation",
    "crossover",
    "fixed",
    "interval",
    "step",
    "strategy",
    "selection",
    "application",
    "credit",
    "repair",
    "random",
    "repetitive",
    "default",
    "archive",
    "size",
    "parameters",
    "population",
    "variation",
    "genetic",
    "rate",
    "parents",
    "bisections",
    "termination",
    "evolutions",
    "time",
    "delta",
    "iterations",
    "algorithm",
    "batches",
    "timeout",
    "script",
    "transformation",
    "backend",
    "resources",
    "threads",
    "nodes",
    "threadsPerNode"
] as const;

/**
 * The name of an operator argument: an identifier, or one of the {@link ARGUMENT_NAME_KEYWORDS}.
 */
export const ArgumentNameRule = createRule("ConfigMdeoArgumentNameRule")
    .returns(String)
    .as(() => [or(ID, ...ARGUMENT_NAME_KEYWORDS)]);

/**
 * Int literal: `4`, `-1`.
 */
export const IntLiteralRule = createRule("ConfigMdeoIntLiteralRule")
    .returns(IntLiteral)
    .as(({ set }) => [set("value", INT)]);

/**
 * Long literal: `42L`.
 */
export const LongLiteralRule = createRule("ConfigMdeoLongLiteralRule")
    .returns(LongLiteral)
    .as(({ set }) => [set("value", CONFIG_MDEO_LONG)]);

/**
 * Float literal: `2.5f`.
 */
export const FloatLiteralRule = createRule("ConfigMdeoFloatLiteralRule")
    .returns(FloatLiteral)
    .as(({ set }) => [set("value", CONFIG_MDEO_FLOAT)]);

/**
 * Double literal written with a suffix: `2.5d`.
 */
export const SuffixedDoubleLiteralRule = createRule("ConfigMdeoSuffixedDoubleLiteralRule")
    .returns(DoubleLiteral)
    .as(({ set }) => [set("value", CONFIG_MDEO_DOUBLE)]);

/**
 * The text of a double literal without a suffix (the FLOAT terminal converts to a number, the
 * literal keeps its text).
 */
export const PlainDoubleTextRule = createRule("ConfigMdeoPlainDoubleTextRule")
    .returns(String)
    .as(() => [FLOAT]);

/**
 * Double literal written without a suffix: `2.5`, `1e3`.
 */
export const PlainDoubleLiteralRule = createRule("ConfigMdeoPlainDoubleLiteralRule")
    .returns(DoubleLiteral)
    .as(({ set }) => [set("value", PlainDoubleTextRule)]);

/**
 * String literal: `"fast"`.
 */
export const StringLiteralRule = createRule("ConfigMdeoStringLiteralRule")
    .returns(StringLiteral)
    .as(({ set }) => [set("value", STRING)]);

/**
 * Boolean literal: `true` / `false`.
 */
export const BooleanLiteralRule = createRule("ConfigMdeoBooleanLiteralRule")
    .returns(BooleanLiteral)
    .as(({ set }) => [set("value", "true", "false")]);

/**
 * `null`, which is not a valid argument (the validator says to leave the argument out instead).
 */
export const NullLiteralRule = createRule("ConfigMdeoNullLiteralRule")
    .returns(NullLiteral)
    .as(() => [action(NullLiteral, () => ["null"])]);

/**
 * Enum literal of the problem's metamodel: `Kind.SERVICE`.
 */
export const EnumLiteralRule = createRule("ConfigMdeoEnumLiteralRule")
    .returns(EnumLiteral)
    .as(({ set }) => [set("enum", ref(MetamodelEnum, ID)), ".", set("entry", ref(MetamodelEnumEntry, ID))]);

/**
 * List literal: `[1, 2]`, `[Kind.SERVICE, Kind.STORE]`, `[]`. Line breaks are allowed after
 * `[` and `,` and before `]`.
 */
export const ListLiteralRule = createRule("ConfigMdeoListLiteralRule")
    .returns(ListLiteral)
    .as(({ add }) => [
        "[",
        many(NEWLINE),
        optional(
            add("elements", () => ConfigLiteralRule),
            many(NEWLINE),
            many(
                ",",
                many(NEWLINE),
                add("elements", () => ConfigLiteralRule),
                many(NEWLINE)
            )
        ),
        "]"
    ]);

/**
 * The value of an operator argument: a literal.
 */
export const ConfigLiteralRule = createRule("ConfigMdeoLiteralRule")
    .returns(ConfigLiteral)
    .as(() => [
        or(
            LongLiteralRule,
            FloatLiteralRule,
            SuffixedDoubleLiteralRule,
            PlainDoubleLiteralRule,
            IntLiteralRule,
            StringLiteralRule,
            BooleanLiteralRule,
            NullLiteralRule,
            EnumLiteralRule,
            ListLiteralRule
        )
    ]);

/**
 * One operator argument: `name = literal`.
 */
export const OperatorArgumentRule = createRule("ConfigMdeoOperatorArgumentRule")
    .returns(OperatorArgument)
    .as(({ set }) => [set("name", ArgumentNameRule), "=", set("value", ConfigLiteralRule)]);

/**
 * The arguments block of a using entry: `with { name = literal … }`, one argument per line.
 */
export const OperatorArgumentsRule = createRule("ConfigMdeoOperatorArgumentsRule")
    .returns(OperatorArguments)
    .as(({ add }) => ["with", "{", many(NEWLINE), many(add("arguments", OperatorArgumentRule), many(NEWLINE)), "}"]);

/**
 * Using path rule.
 * Matches: using "path/to/transformation.mt" [with { name = literal … }]
 */
export const UsingPathRule = createRule("ConfigMdeoUsingPathRule")
    .returns(UsingPath)
    .as(({ set }) => ["using", set("path", STRING), optional(set("with", OperatorArgumentsRule))]);

/**
 * Class mutation rule.
 * Matches: create ClassName | delete ClassName | mutate ClassName
 */
export const ClassMutationRule = createRule("ConfigMdeoClassMutationRule")
    .returns(ClassMutation)
    .as(({ set }) => [set("operator", "create", "delete", "mutate"), set("class", ref(MetamodelClass, ID))]);

/**
 * Edge mutation rule.
 * Matches: add ClassName.edgeName | remove ClassName.edgeName | mutate ClassName.edgeName
 */
export const EdgeMutationRule = createRule("ConfigMdeoEdgeMutationRule")
    .returns(EdgeMutation)
    .as(({ set }) => [
        set("operator", "add", "remove", "mutate"),
        set("class", ref(MetamodelClass, ID)),
        ".",
        set("edge", ref(Property, ID))
    ]);

/**
 * Mutations block rule.
 * Matches the mutations { ... } block inside search section.
 * Using paths and mutation operators can appear in any order.
 */
export const MutationsBlockRule = createRule("ConfigMdeoMutationsBlockRule")
    .returns(MutationsBlock)
    .as(({ add }) => [
        "mutations",
        "{",
        many(NEWLINE),
        many(
            or(
                add("usingPaths", UsingPathRule),
                add("edgeMutations", EdgeMutationRule),
                add("classMutations", ClassMutationRule)
            ),
            many(NEWLINE)
        ),
        "}"
    ]);

/**
 * Crossovers block rule.
 * Matches the crossovers { ... } block inside search section: crossover operators, as using
 * entries only (there are no generated crossover operators).
 */
export const CrossoversBlockRule = createRule("ConfigMdeoCrossoversBlockRule")
    .returns(CrossoversBlock)
    .as(({ add }) => ["crossovers", "{", many(NEWLINE), many(add("usingPaths", UsingPathRule), many(NEWLINE)), "}"]);

/**
 * Search section content rule (without keyword).
 * Parses the search section with mutations and crossovers blocks.
 * The keyword will be added by the wrapper rule in language-config composition.
 * Syntax:
 *   {
 *       mutations {
 *           using "path"
 *           create Test
 *           add Test.theEdge
 *       }
 *       crossovers {
 *           using "path" with { minShared = 2 }
 *       }
 *   }
 */
export const SearchSectionContentRule = createRule("SearchSectionContentRule")
    .returns(SearchSection)
    .as(({ add }) => [
        "{",
        many(NEWLINE),
        many(or(add("mutations", MutationsBlockRule), add("crossovers", CrossoversBlockRule)), many(NEWLINE)),
        "}"
    ]);

/**
 * Bare integer step size.
 * Example: step = 3
 */
export const MutationStepNumericRule = createRule("ConfigMdeoMutationStepNumericRule")
    .returns(MutationStepNumeric)
    .as(({ set }) => [set("value", INT)]);

/**
 * Fixed step size with no argument (runtime default = 1).
 * Example: step = fixed
 */
export const MutationStepFixedRule = createRule("ConfigMdeoMutationStepFixedRule")
    .returns(MutationStepFixed)
    .as(() => ["fixed"]);

/**
 * Fixed step size with an explicit integer argument.
 * Example: step = fixed(3)
 */
export const MutationStepFixedNRule = createRule("ConfigMdeoMutationStepFixedNRule")
    .returns(MutationStepFixedN)
    .as(({ set }) => ["fixed", "(", set("n", INT), ")"]);

/**
 * Interval step size — draws uniformly from [lower, upper) each call.
 * Example: step = interval(1, 5)
 */
export const MutationStepIntervalRule = createRule("ConfigMdeoMutationStepIntervalRule")
    .returns(MutationStepInterval)
    .as(({ set }) => ["interval", "(", set("lower", INT), ",", set("upper", INT), ")"]);

/**
 * Union rule dispatching to all valid mutation step value forms.
 * Order: interval → fixedN → fixed → numeric (most-specific first to avoid conflicts).
 */
export const MutationStepValueRule = createRule("ConfigMdeoMutationStepValueRule")
    .returns(MutationStepValue)
    .as(() => [or(MutationStepIntervalRule, MutationStepFixedNRule, MutationStepFixedRule, MutationStepNumericRule)]);

/**
 * Mutation block rule.
 * Represents all mutation.* configuration keys as a nested block.
 * Syntax:
 *   mutation {
 *       step = 1
 *       strategy = random
 *       selection = random
 *   }
 */
export const MutationBlockRule = createRule("ConfigMdeoMutationBlockRule")
    .returns(MutationBlock)
    .as(({ add }) => [
        "mutation",
        "{",
        many(NEWLINE),
        many(
            or(
                group("step", "=", add("step", MutationStepValueRule)),
                group("strategy", "=", add("strategy", "random", "repetitive")),
                group("selection", "=", add("selection", "random")),
                group("application", "=", add("application", "random")),
                group("credit", "=", add("credit", "random")),
                group("repair", "=", add("repair", "default"))
            ),
            many(NEWLINE)
        ),
        "}"
    ]);

/**
 * Archive block rule.
 * Used only by PESA2 and PAES algorithms.
 * Syntax:
 *   archive {
 *       size = 100
 *   }
 */
export const ArchiveBlockRule = createRule("ConfigMdeoArchiveBlockRule")
    .returns(ArchiveBlock)
    .as(({ add }) => ["archive", "{", many(NEWLINE), many(group("size", "=", add("size", INT)), many(NEWLINE)), "}"]);

/**
 * Crossover block rule.
 * Represents the crossover configuration keys as a nested block (used with variation = genetic).
 * Syntax:
 *   crossover {
 *       rate = 0.9
 *       parents = 2
 *       strategy = random
 *   }
 */
export const CrossoverBlockRule = createRule("ConfigMdeoCrossoverBlockRule")
    .returns(CrossoverBlock)
    .as(({ add }) => [
        "crossover",
        "{",
        many(NEWLINE),
        many(
            or(
                group("rate", "=", add("rate", NumberRule)),
                group("parents", "=", add("parents", INT)),
                group("strategy", "=", add("strategy", "random"))
            ),
            many(NEWLINE)
        ),
        "}"
    ]);

/**
 * Algorithm parameters block rule.
 * Contains population, variation, and optional mutation / archive sub-blocks.
 * Syntax:
 *   parameters {
 *       population = 40
 *       variation = mutation
 *       crossover {             (variation = genetic only)
 *           rate = 0.9
 *           parents = 2
 *       }
 *       mutation {
 *           step = 1
 *           strategy = random
 *       }
 *       bisections = 0      (PESA2 / PAES only)
 *       archive { size = 100 }  (PESA2 / PAES only)
 *   }
 */
export const AlgorithmParametersBlockRule = createRule("ConfigMdeoAlgorithmParametersBlockRule")
    .returns(AlgorithmParameters)
    .as(({ add }) => [
        "parameters",
        "{",
        many(NEWLINE),
        many(
            or(
                group("population", "=", add("population", INT)),
                group("variation", "=", add("variation", "mutation", "genetic")),
                add("crossover", CrossoverBlockRule),
                add("mutation", MutationBlockRule),
                group("bisections", "=", add("bisections", INT)),
                add("archive", ArchiveBlockRule)
            ),
            many(NEWLINE)
        ),
        "}"
    ]);

/**
 * Termination block rule.
 * Multiple conditions are combined as OR — the first to trigger stops the run.
 * Syntax:
 *   termination {
 *       evolutions = 500
 *       time = 60
 *       delta = 5
 *       iterations = 3
 *   }
 */
export const TerminationBlockRule = createRule("ConfigMdeoTerminationBlockRule")
    .returns(TerminationBlock)
    .as(({ add }) => [
        "termination",
        "{",
        many(NEWLINE),
        many(
            or(
                group("evolutions", "=", add("evolutions", INT)),
                group("time", "=", add("time", INT)),
                group("delta", "=", add("delta", INT)),
                group("iterations", "=", add("iterations", INT))
            ),
            many(NEWLINE)
        ),
        "}"
    ]);

/**
 * Solver section content rule (without keyword).
 * Models the solver configuration block exactly as specified in the MDE Optimiser grammar.
 * All entries appear at most once (unordered group semantics).
 * The keyword will be added by the wrapper rule in language-config composition.
 * Syntax:
 *   {
 *       algorithm = NSGAII
 *       parameters {
 *           population = 40
 *           variation = mutation
 *           mutation { step = 1 }
 *       }
 *       termination {
 *           evolutions = 500
 *       }
 *       batches = 1
 *   }
 */
export const SolverSectionContentRule = createRule("SolverSectionContentRule")
    .returns(SolverSection)
    .as(({ add }) => [
        "{",
        many(NEWLINE),
        many(
            or(
                group(
                    "algorithm",
                    "=",
                    add("algorithm", "NSGAII", "IBEA", "SPEA2", "SMSMOEA", "VEGA", "PESA2", "PAES")
                ),
                add("parameters", AlgorithmParametersBlockRule),
                add("termination", TerminationBlockRule),
                group("batches", "=", add("batches", INT))
            ),
            many(NEWLINE)
        ),
        "}"
    ]);

/**
 * Runtime timeout block rule.
 * Syntax:
 *   timeout {
 *       script = 1000
 *       transformation = 1000
 *   }
 */
export const RuntimeTimeoutBlockRule = createRule("ConfigMdeoRuntimeTimeoutBlockRule")
    .returns(RuntimeTimeoutBlock)
    .as(({ add }) => [
        "timeout",
        "{",
        many(NEWLINE),
        many(
            or(group("script", "=", add("script", INT)), group("transformation", "=", add("transformation", INT))),
            many(NEWLINE)
        ),
        "}"
    ]);

/**
 * Runtime resources block rule.
 * All fields are optional and represent upper bounds.
 * Syntax:
 *   resources {
 *       threads = 10
 *       nodes = 10
 *       threadsPerNode = 3
 *   }
 */
export const RuntimeResourcesBlockRule = createRule("ConfigMdeoRuntimeResourcesBlockRule")
    .returns(RuntimeResourcesBlock)
    .as(({ add }) => [
        "resources",
        "{",
        many(NEWLINE),
        many(
            or(
                group("threads", "=", add("threads", INT)),
                group("nodes", "=", add("nodes", INT)),
                group("threadsPerNode", "=", add("threadsPerNode", INT))
            ),
            many(NEWLINE)
        ),
        "}"
    ]);

/**
 * Runtime section content rule (without keyword).
 * Models the runtime configuration block.
 * Syntax:
 *   {
 *       timeout {
 *           script = 1000
 *           transformation = 1000
 *       }
 *       backend = Tinker
 *       resources {
 *           threads = 10
 *           nodes = 10
 *           threadsPerNode = 3
 *       }
 *   }
 */
export const RuntimeSectionContentRule = createRule("RuntimeSectionContentRule")
    .returns(RuntimeSection)
    .as(({ add }) => [
        "{",
        many(NEWLINE),
        many(
            or(
                add("timeout", RuntimeTimeoutBlockRule),
                group("backend", "=", add("backend", "MDEO", "Tinker")),
                add("resources", RuntimeResourcesBlockRule)
            ),
            many(NEWLINE)
        ),
        "}"
    ]);
