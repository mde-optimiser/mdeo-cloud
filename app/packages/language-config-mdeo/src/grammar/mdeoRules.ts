import { createRule, or, many, ref, createExternalTerminalRule, group } from "@mdeo/language-common";
import {
    SearchSection,
    SolverSection,
    MutationsBlock,
    UsingPath,
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
const STRING = createExternalTerminalRule<string>("STRING");
const NEWLINE = createExternalTerminalRule<string>("NEWLINE");

/**
 * Using path rule.
 * Matches: using "path/to/transformation.mt"
 */
export const UsingPathRule = createRule("ConfigMdeoUsingPathRule")
    .returns(UsingPath)
    .as(({ set }) => ["using", set("path", STRING)]);

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
 * Search section content rule (without keyword).
 * Parses the search section with mutations block.
 * The keyword will be added by the wrapper rule in language-config composition.
 * Syntax:
 *   {
 *       mutations {
 *           using "path"
 *           create Test
 *           add Test.theEdge
 *       }
 *   }
 */
export const SearchSectionContentRule = createRule("SearchSectionContentRule")
    .returns(SearchSection)
    .as(({ add }) => ["{", many(NEWLINE), many(add("mutations", MutationsBlockRule), many(NEWLINE)), "}"]);

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
 * Algorithm parameters block rule.
 * Contains population, variation, and optional mutation / archive sub-blocks.
 * Syntax:
 *   parameters {
 *       population = 40
 *       variation = mutation
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
                group("variation", "=", add("variation", "mutation", "genetic", "probabilistic")),
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
