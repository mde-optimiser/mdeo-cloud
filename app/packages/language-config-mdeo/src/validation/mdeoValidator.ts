import type {
    ValidationAcceptor,
    ValidationChecks,
    AstNode,
    Properties,
    LangiumDocument,
    LangiumDocuments
} from "langium";
import type { AstReflection, ExtendedLangiumServices } from "@mdeo/language-common";
import { sharedImport, resolveRelativePath } from "@mdeo/language-shared";
import {
    type SearchSectionType,
    type SolverSectionType,
    type MutationsBlockType,
    type CrossoversBlockType,
    type CrossoverBlockType,
    type UsingPathType,
    type MutationBlockType,
    type AlgorithmParametersType,
    type ArchiveBlockType,
    type RuntimeSectionType,
    type RuntimeTimeoutBlockType,
    type RuntimeResourcesBlockType,
    MutationStepNumeric,
    MutationStepFixedN,
    MutationStepInterval
} from "../grammar/mdeoTypes.js";
import {
    ModelTransformation,
    describeTransformationInputs,
    transformationDisplayName,
    type ModelTransformationType,
    type TransformationInputDescription
} from "@mdeo/language-model-transformation";
import { getWrapperInterfaceName } from "@mdeo/language-config";
import { MDEO_PLUGIN_NAME } from "../plugin/mdeoContributionPlugin.js";
import { DEFAULT_CROSSOVER_PARENTS } from "../features/operatorArguments.js";
import { OperatorArgumentsValidator, isPassableInput } from "./operatorArgumentsValidator.js";
import { isMetamodelCompatible } from "@mdeo/language-metamodel";
import type { MdeoMetamodelResolver } from "../features/mdeoMetamodelResolver.js";
import type { Range } from "vscode-languageserver-types";

const { AstUtils, CstUtils, isLeafCstNode, GrammarUtils } = sharedImport("langium");

/**
 * Valid algorithm names for the solver section.
 */
const VALID_ALGORITHMS = new Set(["NSGAII", "IBEA", "SPEA2", "SMSMOEA", "VEGA", "PESA2", "PAES"]);

/**
 * Valid variation values for mutation configuration.
 */
const VALID_VARIATIONS = new Set(["mutation", "genetic"]);

/**
 * Valid crossover strategy values.
 */
const VALID_CROSSOVER_STRATEGIES = new Set(["random"]);

/**
 * Valid mutation strategy values.
 */
const VALID_MUTATION_STRATEGIES = new Set(["random", "repetitive"]);

/**
 * Valid mutation selection values.
 */
const VALID_MUTATION_SELECTIONS = new Set(["random"]);

/**
 * Parameters that are required for specific algorithms.
 */
const PESA2_PAES_ALGORITHMS = new Set(["PESA2", "PAES"]);

/**
 * Interface mapping for MDEO AST types used in validation checks.
 */
interface MdeoAstTypes {
    ConfigMdeoSearchSection: SearchSectionType;
    ConfigMdeoSolverSection: SolverSectionType;
    ConfigMdeoRuntimeSection: RuntimeSectionType;
}

/**
 * Registers validation checks for the config-mdeo language.
 *
 * @param services The extended Langium services
 */
export function registerMdeoValidationChecks(
    services: ExtendedLangiumServices & { MdeoMetamodelResolver: MdeoMetamodelResolver }
): void {
    const registry = services.validation.ValidationRegistry;
    const validator = new MdeoValidator(services);

    const checks: ValidationChecks<MdeoAstTypes> = {
        ConfigMdeoSearchSection: validator.validateSearchSection.bind(validator),
        ConfigMdeoSolverSection: validator.validateSolverSection.bind(validator),
        ConfigMdeoRuntimeSection: validator.validateRuntimeSection.bind(validator)
    };

    registry.register(checks, validator);
}

/**
 * Validator for config-mdeo language constructs.
 * Checks path validity, parameter values, and required fields.
 */
export class MdeoValidator {
    private readonly services: ExtendedLangiumServices;
    private readonly metamodelResolver: MdeoMetamodelResolver;
    private readonly reflection: AstReflection;
    private readonly argumentsValidator: OperatorArgumentsValidator;

    /**
     * Constructs a new MdeoValidator.
     *
     * @param services The extended Langium services
     */
    constructor(services: ExtendedLangiumServices & { MdeoMetamodelResolver: MdeoMetamodelResolver }) {
        this.services = services;
        this.metamodelResolver = services.MdeoMetamodelResolver;
        this.reflection = services.shared.AstReflection;
        this.argumentsValidator = new OperatorArgumentsValidator(this.reflection);
    }

    /**
     * Validates a search section.
     * Checks the mutations and crossovers blocks: their using paths resolve to transformation
     * files that are operators of the block's kind, and their arguments fit the inputs.
     *
     * @param search The search section node to validate
     * @param accept The validation acceptor for reporting diagnostics
     */
    validateSearchSection(search: SearchSectionType, accept: ValidationAcceptor): void {
        this.checkNoDuplicateNodes(search.mutations, "mutations", accept);
        this.checkNoDuplicateNodes(search.crossovers, "crossovers", accept);
        const mutations = search.mutations[0];
        if (mutations != undefined) {
            this.validateMutationsBlock(mutations, accept);
        }
        const crossovers = search.crossovers[0];
        if (crossovers != undefined) {
            this.validateCrossoversBlock(crossovers, accept);
        }
    }

    /**
     * Validates a mutations block.
     * Checks that using paths reference valid model transformation files.
     *
     * @param mutations The mutations block to validate
     * @param accept The validation acceptor
     */
    private validateMutationsBlock(mutations: MutationsBlockType, accept: ValidationAcceptor): void {
        for (const usingPath of mutations.usingPaths ?? []) {
            const operator = this.validateUsingPath(usingPath, accept);
            if (operator != undefined) {
                this.validateMutationOperator(usingPath, operator, accept);
            }
        }

        if (
            (mutations.classMutations?.length ?? 0) === 0 &&
            (mutations.edgeMutations?.length ?? 0) === 0 &&
            (mutations.usingPaths?.length ?? 0) === 0
        ) {
            accept("warning", "Mutations block has no mutation operators defined.", {
                node: mutations
            });
        }
    }

    /**
     * Validates a crossovers block.
     * Checks that its using paths are crossover operators that accept the configured number of
     * parents, and that the block is used (`variation = genetic`) and not empty then.
     *
     * @param crossovers The crossovers block to validate
     * @param accept The validation acceptor
     */
    private validateCrossoversBlock(crossovers: CrossoversBlockType, accept: ValidationAcceptor): void {
        const solver = this.findSolverSettings(crossovers);
        for (const usingPath of crossovers.usingPaths ?? []) {
            const operator = this.validateUsingPath(usingPath, accept);
            if (operator != undefined) {
                this.validateCrossoverOperator(usingPath, operator, solver, accept);
            }
        }

        if (solver.variation !== "genetic") {
            accept(
                "warning",
                "The crossovers block is unused: crossover operators only run with 'variation = genetic' in the solver parameters.",
                { node: crossovers, keyword: "crossovers" }
            );
        } else if ((crossovers.usingPaths?.length ?? 0) === 0) {
            accept(
                "error",
                "The crossovers block has no crossover operators, but 'variation = genetic' needs at least one.",
                { node: crossovers, keyword: "crossovers" }
            );
        }
    }

    /**
     * Checks that a transformation listed under `mutations` is a mutation operator: it has no
     * model inputs, and every required input is passed by the `with` block.
     *
     * @param usingPath The using path being validated
     * @param operator The transformation and what the `with` block supplies
     * @param accept The validation acceptor
     */
    private validateMutationOperator(
        usingPath: UsingPathType,
        operator: ResolvedOperator,
        accept: ValidationAcceptor
    ): void {
        const models = operator.inputs.filter((input) => isModelInput(input));
        if (models.length > 0) {
            accept(
                "error",
                `The transformation at '${usingPath.path}' has ${plural(models.length, "the model input", "the model inputs")} ${quoteNames(models)}, so it is a crossover operator, not a mutation operator: list it in the crossovers block of the search section.`,
                { node: usingPath, property: "path" }
            );
            return;
        }
        this.validateRequiredInputsSupplied(usingPath, operator, "a mutation operator", accept);
    }

    /**
     * Checks that a transformation listed under `crossovers` is a crossover operator (D.1): its
     * required inputs are model inputs (at least one) and inputs the `with` block passes, its
     * model inputs are either single `model` inputs or exactly one `List<model>`, and it accepts
     * the configured number of parents.
     *
     * @param usingPath The using path being validated
     * @param operator The transformation and what the `with` block supplies
     * @param solver The crossover settings of the solver section
     * @param accept The validation acceptor
     */
    private validateCrossoverOperator(
        usingPath: UsingPathType,
        operator: ResolvedOperator,
        solver: SolverSettings,
        accept: ValidationAcceptor
    ): void {
        const singles = operator.inputs.filter((input) => input.kind === "model");
        const lists = operator.inputs.filter((input) => input.kind === "modelList");
        if (singles.length === 0 && lists.length === 0) {
            accept(
                "error",
                `The transformation at '${usingPath.path}' has no model input, so it is not a crossover operator: a crossover operator receives the other parents as 'in donor: model' or 'in donors: List<model>'. List mutation operators in the mutations block.`,
                { node: usingPath, property: "path" }
            );
            return;
        }
        if (singles.length > 0 && lists.length > 0) {
            accept(
                "error",
                `The crossover operator at '${usingPath.path}' mixes single model inputs (${quoteNames(singles)}) with a list of models (${quoteNames(lists)}): declare either single 'model' inputs, one per other parent, or exactly one 'List<model>' input.`,
                { node: usingPath, property: "path" }
            );
            return;
        }
        if (lists.length > 1) {
            accept(
                "error",
                `The crossover operator at '${usingPath.path}' has more than one 'List<model>' input (${quoteNames(lists)}): a crossover operator for any number of parents has exactly one.`,
                { node: usingPath, property: "path" }
            );
            return;
        }
        this.validateRequiredInputsSupplied(usingPath, operator, "a crossover operator", accept);

        if (singles.length > 0 && solver.parents >= 2 && singles.length !== solver.parents - 1) {
            const takes = singles.length + 1;
            const configured = solver.parentsWritten
                ? `'crossover { parents = ${solver.parents} }' groups ${solver.parents} parents`
                : `crossovers group ${solver.parents} parents (the default of 'crossover { parents }')`;
            accept(
                "error",
                `The crossover operator at '${usingPath.path}' takes ${takes} parents (the model it runs on and ${plural(singles.length, "the model input", "the model inputs")} ${quoteNames(singles)}), but ${configured}. Use a transformation with ${solver.parents - 1} model ${solver.parents - 1 === 1 ? "input" : "inputs"} or one 'List<model>' input, or set 'parents = ${takes}'.`,
                { node: usingPath, property: "path" }
            );
        }
    }

    /**
     * Checks that every required input of an operator that is not a model input is passed by
     * the `with` block (an input that the config cannot set has to have a default or be
     * optional).
     *
     * @param usingPath The using path being validated
     * @param operator The transformation and what the `with` block supplies
     * @param role How the operator is named in messages, e.g. `a mutation operator`
     * @param accept The validation acceptor
     */
    private validateRequiredInputsSupplied(
        usingPath: UsingPathType,
        operator: ResolvedOperator,
        role: string,
        accept: ValidationAcceptor
    ): void {
        for (const input of operator.inputs) {
            if (input.optional || isModelInput(input) || operator.supplied.has(input.name)) {
                continue;
            }
            const fixes: string[] = [];
            if (isPassableInput(input)) {
                fixes.push(`add 'with { ${input.name} = … }'`);
            }
            if (input.kind === "scalar" || input.kind === "enum") {
                fixes.push("give the input a default");
            }
            if (input.kind === "element") {
                fixes.push(`make the input optional ('${input.typeName}?')`);
            }
            fixes.push("call the transformation from an operator with 'call'");
            const reason = isPassableInput(input)
                ? "which this entry does not pass"
                : "which cannot be set from the config";
            accept(
                "error",
                `The transformation at '${usingPath.path}' has the required input '${input.name}' (${input.typeName}), ${reason}, so it cannot be ${role}: ${joinAlternatives(fixes)}.`,
                { node: usingPath, property: "path" }
            );
        }
    }

    /**
     * Reads the solver settings that the search section's checks depend on.
     *
     * @param node Any node of the config
     * @returns The variation and the number of parents per crossover
     */
    private findSolverSettings(node: AstNode): SolverSettings {
        const solver = findSiblingSection<SolverSectionType>(node, "solver");
        const params = solver?.parameters?.[0];
        const parents = params?.crossover?.[0]?.parents?.[0];
        return {
            variation: params?.variation?.[0],
            parents: parents ?? DEFAULT_CROSSOVER_PARENTS,
            parentsWritten: parents != undefined
        };
    }

    /**
     * Validates a using path.
     * Checks that the path resolves to a valid model transformation file, that the
     * transformation's metamodel is compatible with the problem section's metamodel and that
     * the arguments of its `with` block fit its inputs.
     *
     * @param usingPath The using path to validate
     * @param accept The validation acceptor
     * @returns The transformation's inputs and the inputs the entry passes, if it resolves
     */
    private validateUsingPath(usingPath: UsingPathType, accept: ValidationAcceptor): ResolvedOperator | undefined {
        if (usingPath.path == undefined) {
            return undefined;
        }
        const document = AstUtils.getDocument(usingPath);
        const targetUri = resolveRelativePath(document, usingPath.path);
        const documents = this.services.shared.workspace.LangiumDocuments;
        const targetDoc = documents.getDocument(targetUri);

        if (targetDoc == undefined) {
            accept("error", `Cannot resolve path '${usingPath.path}'. The file does not exist or is not loaded.`, {
                node: usingPath,
                property: "path"
            });
            return undefined;
        }

        const root = targetDoc.parseResult?.value;
        if (root == undefined || root.$type !== ModelTransformation.name) {
            accept("error", `The path '${usingPath.path}' does not point to a model transformation file.`, {
                node: usingPath,
                property: "path"
            });
            return undefined;
        }

        this.validateTransformationMetamodelCompatibility(usingPath, document, targetDoc, documents, accept);

        const transformation = root as ModelTransformationType;
        const inputs = describeTransformationInputs(transformation, this.reflection, documents);
        const outputs = new Set(
            (transformation.parameters ?? []).filter((p) => p.direction === "out").map((p) => p.name)
        );
        const supplied = this.argumentsValidator.validate(
            usingPath,
            transformationDisplayName(transformation),
            inputs,
            outputs,
            accept
        );
        return { inputs, supplied };
    }

    /**
     * Validates that a model transformation's metamodel is compatible with the problem section's metamodel.
     *
     * @param usingPath The using path being validated
     * @param document The current document
     * @param targetDoc The target model transformation document
     * @param documents The Langium documents service
     * @param accept The validation acceptor
     */
    private validateTransformationMetamodelCompatibility(
        usingPath: UsingPathType,
        document: LangiumDocument,
        targetDoc: LangiumDocument,
        documents: LangiumDocuments,
        accept: ValidationAcceptor
    ): void {
        const transformation = targetDoc.parseResult?.value as ModelTransformationType;
        const transformationMetamodelPath = transformation.import?.file;

        if (transformationMetamodelPath == undefined) {
            accept(
                "error",
                `The model transformation at '${usingPath.path}' does not specify an import for its metamodel, so compatibility cannot be determined.`,
                {
                    node: usingPath,
                    property: "path"
                }
            );
            return;
        }

        const configMetamodelUri = this.metamodelResolver.getMetamodelUri(document);
        if (configMetamodelUri == undefined) {
            accept(
                "error",
                `The problem section does not specify a metamodel, so compatibility cannot be determined.`,
                {
                    node: usingPath,
                    property: "path"
                }
            );
            return;
        }
        const configMetamodelDoc = documents.getDocument(configMetamodelUri);
        if (configMetamodelDoc == undefined) {
            accept("error", `The problem section's metamodel at '${configMetamodelUri}' could not be resolved.`, {
                node: usingPath,
                property: "path"
            });
            return;
        }

        const transformationMetamodelDoc = this.resolveDocument(targetDoc, transformationMetamodelPath, documents);
        if (transformationMetamodelDoc == undefined) {
            accept(
                "error",
                `The model transformation's metamodel at '${transformationMetamodelPath}' could not be resolved.`,
                {
                    node: usingPath,
                    property: "path"
                }
            );
            return;
        }

        if (!isMetamodelCompatible(transformationMetamodelDoc, configMetamodelDoc, documents)) {
            accept(
                "error",
                `The model transformation at '${usingPath.path}' uses a metamodel that is not compatible with the problem section's metamodel.`,
                {
                    node: usingPath,
                    property: "path"
                }
            );
        }
    }

    /**
     * Resolves a relative path to a document.
     *
     * @param fromDocument The document from which to resolve
     * @param relativePath The relative path
     * @param documents The Langium documents service
     * @returns The resolved document, or undefined if not found
     */
    private resolveDocument(
        fromDocument: LangiumDocument,
        relativePath: string,
        documents: LangiumDocuments
    ): LangiumDocument | undefined {
        const uri = resolveRelativePath(fromDocument, relativePath);
        return documents.getDocument(uri);
    }

    /**
     * Validates a solver section.
     * Checks provider, algorithm, parameters block, and termination block.
     *
     * @param solver The solver section node to validate
     * @param accept The validation acceptor for reporting diagnostics
     */
    validateSolverSection(solver: SolverSectionType, accept: ValidationAcceptor): void {
        this.checkNoDuplicateKeys(solver, "algorithm", accept);
        this.checkNoDuplicateNodes(solver.parameters, "parameters", accept);
        this.checkNoDuplicateNodes(solver.termination, "termination", accept);
        this.checkNoDuplicateKeys(solver, "batches", accept);
        this.validateAlgorithm(solver, accept);
        this.validateParameters(solver, accept);
        this.validateTermination(solver, accept);
    }

    /**
     * Validates a runtime section.
     * Checks for duplicate blocks and validates timeout / resources values.
     *
     * @param runtime The runtime section node to validate
     * @param accept The validation acceptor for reporting diagnostics
     */
    validateRuntimeSection(runtime: RuntimeSectionType, accept: ValidationAcceptor): void {
        this.checkNoDuplicateNodes(runtime.timeout, "timeout", accept);
        this.checkNoDuplicateKeys(runtime, "backend", accept);
        this.checkNoDuplicateNodes(runtime.resources, "resources", accept);

        const timeout = runtime.timeout[0];
        if (timeout != undefined) {
            this.validateRuntimeTimeoutBlock(timeout, accept);
        }
        const resources = runtime.resources[0];
        if (resources != undefined) {
            this.validateRuntimeResourcesBlock(resources, accept);
        }
    }

    /**
     * Validates the timeout sub-block of the runtime section.
     * Ensures no duplicate keys and that values are positive.
     *
     * @param timeout The timeout block to validate
     * @param accept The validation acceptor
     */
    private validateRuntimeTimeoutBlock(timeout: RuntimeTimeoutBlockType, accept: ValidationAcceptor): void {
        this.checkNoDuplicateKeys(timeout, "script", accept);
        this.checkNoDuplicateKeys(timeout, "transformation", accept);

        const script = timeout.script[0];
        if (script != undefined && script <= 0) {
            accept("error", "Script timeout must be a positive integer (in seconds).", {
                node: timeout,
                property: "script",
                index: 0
            });
        }
        const transformation = timeout.transformation[0];
        if (transformation != undefined && transformation <= 0) {
            accept("error", "Transformation timeout must be a positive integer (in seconds).", {
                node: timeout,
                property: "transformation",
                index: 0
            });
        }
    }

    /**
     * Validates the resources sub-block of the runtime section.
     * Ensures no duplicate keys and that values are positive.
     *
     * @param resources The resources block to validate
     * @param accept The validation acceptor
     */
    private validateRuntimeResourcesBlock(resources: RuntimeResourcesBlockType, accept: ValidationAcceptor): void {
        this.checkNoDuplicateKeys(resources, "threads", accept);
        this.checkNoDuplicateKeys(resources, "nodes", accept);
        this.checkNoDuplicateKeys(resources, "threadsPerNode", accept);

        const threads = resources.threads[0];
        if (threads != undefined && threads <= 0) {
            accept("error", "Thread count must be a positive integer.", {
                node: resources,
                property: "threads",
                index: 0
            });
        }
        const nodes = resources.nodes[0];
        if (nodes != undefined && nodes <= 0) {
            accept("error", "Node count must be a positive integer.", {
                node: resources,
                property: "nodes",
                index: 0
            });
        }
        const threadsPerNode = resources.threadsPerNode[0];
        if (threadsPerNode != undefined && threadsPerNode <= 0) {
            accept("error", "Threads per node must be a positive integer.", {
                node: resources,
                property: "threadsPerNode",
                index: 0
            });
        }
    }

    /**
     * Validates the algorithm value.
     *
     * @param solver The solver section
     * @param accept The validation acceptor
     */
    private validateAlgorithm(solver: SolverSectionType, accept: ValidationAcceptor): void {
        const algorithm = solver.algorithm[0];
        if (algorithm == undefined) {
            accept("error", "An algorithm must be specified in the solver section.", {
                node: solver,
                range: this.findSectionRange(solver)
            });
            return;
        }
        if (!VALID_ALGORITHMS.has(algorithm)) {
            accept(
                "error",
                `Invalid algorithm '${algorithm}'. Must be one of: ${Array.from(VALID_ALGORITHMS).join(", ")}.`,
                { node: solver, property: "algorithm", index: 0 }
            );
        }
    }

    /**
     * Validates the parameters block.
     * Checks required parameters (population, variation) and algorithm-specific ones
     * (bisections, archive.size for PESA2/PAES).
     *
     * @param solver The solver section
     * @param accept The validation acceptor
     */
    private validateParameters(solver: SolverSectionType, accept: ValidationAcceptor): void {
        const params = solver.parameters[0];
        if (params == undefined) {
            accept("error", "A parameters block is required in the solver section.", {
                node: solver,
                range: this.findSectionRange(solver)
            });
            return;
        }

        this.checkNoDuplicateKeys(params, "population", accept);
        this.checkNoDuplicateKeys(params, "variation", accept);
        this.checkNoDuplicateNodes(params.crossover, "crossover", accept);
        this.checkNoDuplicateNodes(params.mutation, "mutation", accept);
        this.checkNoDuplicateKeys(params, "bisections", accept);
        this.checkNoDuplicateNodes(params.archive, "archive", accept);

        const population = params.population[0];
        if (population == undefined) {
            accept("error", "Required parameter 'population' must be specified in the parameters block.", {
                node: params,
                keyword: "parameters"
            });
        }

        const variation = params.variation[0];
        if (variation == undefined) {
            accept("error", "Required parameter 'variation' must be specified in the parameters block.", {
                node: params,
                keyword: "parameters"
            });
        } else if (!VALID_VARIATIONS.has(variation)) {
            accept(
                "error",
                `Invalid variation '${variation}'. Must be one of: ${Array.from(VALID_VARIATIONS).join(", ")}.`,
                { node: params, property: "variation", index: 0 }
            );
        } else if (variation === "genetic") {
            this.validateGeneticVariation(solver, params, accept);
        }

        const crossover = params.crossover[0];
        if (crossover != undefined) {
            this.validateCrossoverBlock(crossover, variation, accept);
        }

        const mutation = params.mutation[0];
        if (mutation != undefined) {
            this.validateMutationBlock(mutation, accept);
        }

        const algorithm = solver.algorithm[0];
        if (algorithm != undefined && PESA2_PAES_ALGORITHMS.has(algorithm)) {
            const bisections = params.bisections[0];
            if (bisections == undefined) {
                accept("error", `Required parameter 'bisections' must be specified for algorithm '${algorithm}'.`, {
                    node: params,
                    keyword: "parameters"
                });
            }
            const archive = params.archive[0];
            if (archive == undefined) {
                accept("error", `An archive block with 'size' is required for algorithm '${algorithm}'.`, {
                    node: params,
                    keyword: "parameters"
                });
            } else {
                this.validateArchiveBlock(archive, algorithm, accept);
            }
        }
    }

    /**
     * Validates `variation = genetic`: the algorithm must take a crossover (PAES does not), and
     * the search section must list crossover operators.
     *
     * @param solver The solver section
     * @param params The parameters block
     * @param accept The validation acceptor
     */
    private validateGeneticVariation(
        solver: SolverSectionType,
        params: AlgorithmParametersType,
        accept: ValidationAcceptor
    ): void {
        const algorithm = solver.algorithm[0];
        if (algorithm === "PAES") {
            accept(
                "error",
                "PAES evolves a single solution with one mutation operator per step, so it cannot use 'variation = genetic': use 'variation = mutation' or another algorithm.",
                { node: params, property: "variation", index: 0 }
            );
        }
        const search = findSiblingSection<SearchSectionType>(solver, "search");
        if ((search?.crossovers?.length ?? 0) === 0) {
            accept(
                "error",
                "'variation = genetic' needs crossover operators: add a 'crossovers { using \"….mt\" }' block to the search section.",
                { node: params, property: "variation", index: 0 }
            );
        }
    }

    /**
     * Validates the crossover sub-block: each key at most once, `rate` in [0, 1], `parents` at
     * least 2, and a warning when the block is unused (variation other than `genetic`).
     *
     * @param crossover The crossover block to validate
     * @param variation The configured variation, if any
     * @param accept The validation acceptor
     */
    private validateCrossoverBlock(
        crossover: CrossoverBlockType,
        variation: string | undefined,
        accept: ValidationAcceptor
    ): void {
        this.checkNoDuplicateKeys(crossover, "rate", accept);
        this.checkNoDuplicateKeys(crossover, "parents", accept);
        this.checkNoDuplicateKeys(crossover, "strategy", accept);

        if (variation !== "genetic") {
            accept("warning", "The crossover block is unused: it only applies with 'variation = genetic'.", {
                node: crossover,
                keyword: "crossover"
            });
        }

        const rate = crossover.rate[0];
        if (rate != undefined && (rate < 0 || rate > 1)) {
            accept("error", `The crossover rate is a probability: it must lie between 0 and 1, not ${rate}.`, {
                node: crossover,
                property: "rate",
                index: 0
            });
        }
        const parents = crossover.parents[0];
        if (parents != undefined && parents < 2) {
            accept("error", `A crossover needs at least 2 parents, not ${parents}.`, {
                node: crossover,
                property: "parents",
                index: 0
            });
        }
        const strategy = crossover.strategy[0];
        if (strategy != undefined && !VALID_CROSSOVER_STRATEGIES.has(strategy)) {
            accept(
                "error",
                `Invalid crossover strategy '${strategy}'. Must be one of: ${Array.from(VALID_CROSSOVER_STRATEGIES).join(", ")}.`,
                { node: crossover, property: "strategy", index: 0 }
            );
        }
    }

    /**
     * Validates the archive sub-block.
     *
     * @param archive The archive block to validate
     * @param algorithm The current algorithm name (for error messages)
     * @param accept The validation acceptor
     */
    private validateArchiveBlock(archive: ArchiveBlockType, algorithm: string, accept: ValidationAcceptor): void {
        this.checkNoDuplicateKeys(archive, "size", accept);
        const size = archive.size[0];
        if (size == undefined) {
            accept("error", `Required parameter 'archive.size' must be specified for algorithm '${algorithm}'.`, {
                node: archive,
                keyword: "archive"
            });
        }
    }

    /**
     * Validates the mutation step: step counts are not negative (0 applies no mutation step), and an
     * interval draws from [lower, upper), so its upper bound must be above its lower one.
     *
     * @param mutation The mutation block to validate
     * @param accept The validation acceptor
     */
    private validateMutationStep(mutation: MutationBlockType, accept: ValidationAcceptor): void {
        const step = mutation.step[0];
        if (step == undefined) {
            return;
        }
        const negative = (value: number, property: string) => {
            if (value < 0) {
                accept("error", "A mutation step count cannot be negative.", { node: step, property });
            }
        };
        if (this.reflection.isInstance(step, MutationStepNumeric)) {
            negative(step.value, "value");
        } else if (this.reflection.isInstance(step, MutationStepFixedN)) {
            negative(step.n, "n");
        } else if (this.reflection.isInstance(step, MutationStepInterval)) {
            negative(step.lower, "lower");
            negative(step.upper, "upper");
            if (step.upper <= step.lower) {
                accept(
                    "error",
                    `interval(lower, upper) draws from [lower, upper), so upper must be above lower: write interval(${step.lower}, ${step.lower + 1}) or fixed(${step.lower}) for exactly ${step.lower}.`,
                    { node: step, property: "upper" }
                );
            }
        }
    }

    /**
     * Validates the mutation sub-block.
     * Checks strategy and selection values against allowed identifiers.
     *
     * @param mutation The mutation block to validate
     * @param accept The validation acceptor
     */
    private validateMutationBlock(mutation: MutationBlockType, accept: ValidationAcceptor): void {
        this.checkNoDuplicateKeys(mutation, "step", accept);
        this.checkNoDuplicateKeys(mutation, "strategy", accept);
        this.checkNoDuplicateKeys(mutation, "selection", accept);
        this.checkNoDuplicateKeys(mutation, "application", accept);
        this.checkNoDuplicateKeys(mutation, "credit", accept);
        this.checkNoDuplicateKeys(mutation, "repair", accept);

        this.validateMutationStep(mutation, accept);

        const strategy = mutation.strategy[0];
        if (strategy != undefined && !VALID_MUTATION_STRATEGIES.has(strategy)) {
            accept(
                "error",
                `Invalid mutation strategy '${strategy}'. Must be one of: ${Array.from(VALID_MUTATION_STRATEGIES).join(", ")}.`,
                { node: mutation, property: "strategy", index: 0 }
            );
        }
        const selection = mutation.selection[0];
        if (selection != undefined && !VALID_MUTATION_SELECTIONS.has(selection)) {
            accept(
                "error",
                `Invalid mutation selection '${selection}'. Must be one of: ${Array.from(VALID_MUTATION_SELECTIONS).join(", ")}.`,
                { node: mutation, property: "selection", index: 0 }
            );
        }
    }

    /**
     * Validates the termination block.
     * Checks that at least one termination condition is present.
     *
     * @param solver The solver section
     * @param accept The validation acceptor
     */
    private validateTermination(solver: SolverSectionType, accept: ValidationAcceptor): void {
        const termination = solver.termination[0];
        if (termination == undefined) {
            accept("error", "A termination block is required in the solver section.", {
                node: solver,
                range: this.findSectionRange(solver)
            });
            return;
        }

        this.checkNoDuplicateKeys(termination, "evolutions", accept);
        this.checkNoDuplicateKeys(termination, "time", accept);
        this.checkNoDuplicateKeys(termination, "delta", accept);
        this.checkNoDuplicateKeys(termination, "iterations", accept);

        const hasCondition =
            termination.evolutions.length > 0 || termination.time.length > 0 || termination.delta.length > 0;

        if (!hasCondition) {
            accept("error", "Termination block must contain at least one condition: evolutions, time, or delta.", {
                node: termination,
                keyword: "termination"
            });
        }
    }

    /**
     * Reports an error for every duplicate entry after the first in a scalar-array field
     * whose elements are primitive values (strings, numbers).
     * Index 0 is accepted; indices >= 1 are reported as duplicates.
     *
     * @param node The parent AST node
     * @param property The name of the array property holding primitive values
     * @param accept The validation acceptor
     */
    private checkNoDuplicateKeys<T extends AstNode>(
        node: T,
        property: Properties<T> & keyof T,
        accept: ValidationAcceptor
    ): void {
        const arr = node[property];
        if (!Array.isArray(arr)) {
            return;
        }
        for (let i = 1; i < arr.length; i++) {
            const cstNode = GrammarUtils.findNodeForKeyword(node.$cstNode, property, i);
            accept("error", `Duplicate '${property}' key.`, { node, range: cstNode?.range });
        }
    }

    /**
     * Reports an error for every duplicate entry after the first in an array field
     * whose elements are AST nodes. Reports the error directly at the duplicate node.
     *
     * @param arr The array of AST nodes
     * @param label The field label used in the error message
     * @param accept The validation acceptor
     */
    private checkNoDuplicateNodes(arr: AstNode[], label: string, accept: ValidationAcceptor): void {
        for (let i = 1; i < arr.length; i++) {
            accept("error", `Duplicate '${label}' block.`, { node: arr[i] });
        }
    }

    /**
     * Finds the range of the section keyword for error reporting
     *
     * @param section the section node to find the keyword range for
     * @returns the range of the section keyword, or undefined if it cannot be determined
     */
    private findSectionRange(section: AstNode): Range | undefined {
        const cstNode = section.$container?.$cstNode;
        if (cstNode == undefined) {
            return undefined;
        }
        const keywordNode = CstUtils.streamCst(cstNode).find((node) => isLeafCstNode(node) && !node.hidden);
        if (keywordNode == undefined) {
            return undefined;
        }
        return keywordNode.range;
    }
}

/**
 * A transformation named by a using entry: its inputs and the names its `with` block sets.
 */
interface ResolvedOperator {
    inputs: TransformationInputDescription[];
    supplied: ReadonlySet<string>;
}

/**
 * The solver settings that the checks of the search section depend on.
 */
interface SolverSettings {
    variation: string | undefined;
    parents: number;
    parentsWritten: boolean;
}

/**
 * Finds the content of another MDEO section of the config a node belongs to.
 *
 * @param node Any node of the config
 * @param sectionName The section, e.g. `solver`
 * @returns The section's content node, if the config has the section
 */
function findSiblingSection<T>(node: AstNode, sectionName: string): T | undefined {
    const root = AstUtils.findRootNode(node) as { sections?: (AstNode & { content?: AstNode })[] };
    const wrapperType = getWrapperInterfaceName(sectionName, MDEO_PLUGIN_NAME);
    return root.sections?.find((section) => section.$type === wrapperType)?.content as T | undefined;
}

/**
 * Whether an input is a model input (`model` or `List<model>`).
 */
function isModelInput(input: TransformationInputDescription): boolean {
    return input.kind === "model" || input.kind === "modelList";
}

/**
 * Quotes the names of inputs for messages, e.g. `'a', 'b'`.
 */
function quoteNames(inputs: TransformationInputDescription[]): string {
    return inputs.map((input) => `'${input.name}'`).join(", ");
}

/**
 * Picks the singular or plural form of a phrase.
 */
function plural(count: number, singular: string, pluralForm: string): string {
    return count === 1 ? singular : pluralForm;
}

/**
 * Joins alternatives for a message, e.g. `a, b, or c`.
 */
function joinAlternatives(alternatives: string[]): string {
    if (alternatives.length <= 1) {
        return alternatives.join("");
    }
    if (alternatives.length === 2) {
        return `${alternatives[0]} or ${alternatives[1]}`;
    }
    return `${alternatives.slice(0, -1).join(", ")}, or ${alternatives[alternatives.length - 1]}`;
}
