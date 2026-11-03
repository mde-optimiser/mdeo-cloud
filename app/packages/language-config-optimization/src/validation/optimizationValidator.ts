import type {
    ValidationAcceptor,
    ValidationChecks,
    LangiumDocuments,
    LangiumDocument,
    AstNode,
    Properties
} from "langium";
import type { ExtendedLangiumServices } from "@mdeo/language-common";
import { resolveRelativeDocument, sharedImport } from "@mdeo/language-shared";
import { MetaModel, isMetamodelCompatible, type AssociationEndType } from "@mdeo/language-metamodel";
import { Model } from "@mdeo/language-model";
import {
    AssociationEnd,
    RangeMultiplicity,
    SingleMultiplicity,
    type ProblemSectionType,
    type GoalSectionType,
    type ObjectiveType,
    type ConstraintReferenceType,
    type RefinementType,
    type MultiplicityType,
    type RangeMultiplicityType,
    type SingleMultiplicityType,
    type FunctionFileImportType
} from "../grammar/optimizationTypes.js";
import { findProblemSection, getMetamodelUri } from "../features/util.js";
import { multiplicityBounds, refinementBoundsProblem, repeatedRefinements } from "./refinementBounds.js";
import type { Range } from "vscode-languageserver-types";
import { Script, type ScriptType } from "@mdeo/language-script";

const { AstUtils, CstUtils, isLeafCstNode, GrammarUtils } = sharedImport("langium");

/**
 * Numeric type names accepted for objective and constraint function return types.
 * These correspond to the primitive types supported by the Script language.
 */
const NUMERIC_TYPE_NAMES = new Set(["int", "double", "float", "long"]);

/**
 * Interface mapping for optimization AST types used in validation checks.
 */
interface OptimizationAstTypes {
    ConfigProblemSection: ProblemSectionType;
    ConfigGoalSection: GoalSectionType;
    ConfigRefinement: RefinementType;
    ConfigOptimizationFunctionFileImport: FunctionFileImportType;
}

/**
 * Registers validation checks for the config-optimization language.
 *
 * @param services The extended Langium services
 */
export function registerOptimizationValidationChecks(services: ExtendedLangiumServices): void {
    const registry = services.validation.ValidationRegistry;
    const validator = new OptimizationValidator(services);

    const checks: ValidationChecks<OptimizationAstTypes> = {
        ConfigProblemSection: validator.validateProblemSection.bind(validator),
        ConfigGoalSection: validator.validateGoalSection.bind(validator),
        ConfigRefinement: validator.validateRefinement.bind(validator),
        ConfigOptimizationFunctionFileImport: validator.validateFunctionFileImport.bind(validator)
    };

    registry.register(checks, validator);
}

/**
 * Validator for config-optimization language constructs.
 * Checks path validity, cross-file references, and function signatures
 * using only AST-level information (no type system).
 */
export class OptimizationValidator {
    private readonly documents: LangiumDocuments;

    /**
     * Constructs a new OptimizationValidator.
     *
     * @param services The extended Langium services
     */
    constructor(private readonly services: ExtendedLangiumServices) {
        this.documents = services.shared.workspace.LangiumDocuments;
    }

    /**
     * Validates a refinement node.
     * Checks that if a range multiplicity is used, the upper bound is >= the lower bound, mirroring
     * the multiplicity validation from the metamodel language, and that the refinement names an
     * association end on the class declaring it and only tightens the end's bounds.
     *
     * @param refinement The refinement node to validate
     * @param accept The validation acceptor for reporting diagnostics
     */
    validateRefinement(refinement: RefinementType, accept: ValidationAcceptor): void {
        if (refinement.multiplicity) {
            this.validateMultiplicityRange(refinement.multiplicity, refinement, accept);
        }
        const field = refinement.field?.ref;
        if (field == undefined || !this.services.shared.AstReflection.isInstance(field, AssociationEnd)) {
            return;
        }
        const end = field as AssociationEndType;
        const declaring = end.class?.ref;
        const refined = refinement.class?.ref;
        if (declaring != undefined && refined != undefined && declaring !== refined) {
            accept("error", `'${end.name}' is declared on '${declaring.name}'; refine it there.`, {
                node: refinement,
                property: "class"
            });
            return;
        }
        if (refinement.multiplicity == undefined) {
            return;
        }
        const problem = refinementBoundsProblem(
            multiplicityBounds(refinement.multiplicity),
            multiplicityBounds(end.multiplicity)
        );
        if (problem != undefined) {
            accept("error", problem, { node: refinement, property: "multiplicity" });
        }
    }

    /**
     * Validates a problem section:
     * - Both metamodel and model paths must be provided
     * - The metamodel path must resolve to a valid metamodel document
     * - The model path must resolve to a valid model document
     * - The model must reference the same metamodel
     *
     * @param problem The problem section node to validate
     * @param accept The validation acceptor for reporting diagnostics
     */
    validateProblemSection(problem: ProblemSectionType, accept: ValidationAcceptor): void {
        this.checkNoDuplicateKeys(problem, "metamodel", accept);
        this.checkNoDuplicateKeys(problem, "model", accept);
        this.validatePathsPresent(problem, accept);
        this.validateMetamodelPath(problem, accept);
        this.validateModelPath(problem, accept);
    }

    /**
     * Validates a goal section:
     * - At least one objective must be defined
     * - All referenced objective functions must take no arguments and return a numeric type
     * - All referenced constraint functions must take no arguments and return a numeric type
     *   (0.0 = constraint satisfied; any non-zero value = magnitude of violation)
     *
     * @param goal The goal section node to validate
     * @param accept The validation acceptor for reporting diagnostics
     */
    validateGoalSection(goal: GoalSectionType, accept: ValidationAcceptor): void {
        this.validateAtLeastOneObjective(goal, accept);
        this.validateObjectiveFunctions(goal, accept);
        this.validateConstraintFunctions(goal, accept);
        this.validateDistinctRefinements(goal, accept);
    }

    /**
     * Checks that no end is refined twice: the optimizer would reject the run.
     *
     * @param goal The goal section to check
     * @param accept The validation acceptor
     */
    private validateDistinctRefinements(goal: GoalSectionType, accept: ValidationAcceptor): void {
        const ends = goal.refinements.map((r) => `${r.class?.$refText}.${r.field?.$refText}`);
        for (const i of repeatedRefinements(ends)) {
            accept("error", `'${ends[i]}' is already refined; keep one refinement per end.`, {
                node: goal.refinements[i]
            });
        }
    }

    /**
     * Checks that both metamodel and model paths are provided in the problem section.
     *
     * @param problem The problem section to check
     * @param accept The validation acceptor
     */
    private validatePathsPresent(problem: ProblemSectionType, accept: ValidationAcceptor): void {
        if (problem.metamodel.length === 0) {
            accept("error", "A metamodel path is required in the problem section.", {
                node: problem,
                range: this.findSectionRange(problem)
            });
        }
        if (problem.model.length === 0) {
            accept("error", "A model path is required in the problem section.", {
                node: problem.$container!,
                range: this.findSectionRange(problem)
            });
        }
    }

    /**
     * Checks that the metamodel path resolves to a document whose root is a MetaModel node.
     *
     * @param problem The problem section with the metamodel path
     * @param accept The validation acceptor
     */
    private validateMetamodelPath(problem: ProblemSectionType, accept: ValidationAcceptor): void {
        if (problem.metamodel.length === 0) {
            return;
        }
        const metamodel = problem.metamodel[0];

        const document = AstUtils.getDocument(problem);
        const targetDoc = this.resolveDocument(document, metamodel);

        if (targetDoc == undefined) {
            accept("error", `Cannot resolve metamodel path '${metamodel}'. The file does not exist or is not loaded.`, {
                node: problem,
                property: "metamodel",
                index: 0
            });
            return;
        }

        const root = targetDoc.parseResult?.value;
        if (root == undefined || root.$type !== MetaModel.name) {
            accept("error", `The path '${metamodel}' does not point to a metamodel file.`, {
                node: problem,
                property: "metamodel",
                index: 0
            });
        }
    }

    /**
     * Checks that the model path resolves to a document whose root is a Model node,
     * and that the model references the same metamodel as the problem section.
     *
     * @param problem The problem section with metamodel and model paths
     * @param accept The validation acceptor
     */
    private validateModelPath(problem: ProblemSectionType, accept: ValidationAcceptor): void {
        if (problem.model.length === 0) {
            return;
        }
        const model = problem.model[0];

        const document = AstUtils.getDocument(problem);
        const targetDoc = this.resolveDocument(document, model);

        if (targetDoc == undefined) {
            accept("error", `Cannot resolve model path '${model}'. The file does not exist or is not loaded.`, {
                node: problem,
                property: "model",
                index: 0
            });
            return;
        }

        const root = targetDoc.parseResult?.value;
        if (root == undefined || root.$type !== Model.name) {
            accept("error", `The path '${model}' does not point to a model file.`, {
                node: problem,
                property: "model",
                index: 0
            });
            return;
        }

        if (problem.metamodel.length > 0) {
            this.validateModelMetamodelConsistency(problem, document, targetDoc, accept);
        }
    }

    /**
     * Checks that the model's metamodel is compatible with the problem section's metamodel.
     * A metamodel is compatible if it is the same or is transitively imported by the expected metamodel.
     *
     * @param problem The problem section
     * @param sourceDocument The config document (used to resolve paths)
     * @param modelDocument The resolved model document
     * @param accept The validation acceptor
     */
    private validateModelMetamodelConsistency(
        problem: ProblemSectionType,
        sourceDocument: LangiumDocument,
        modelDocument: LangiumDocument,
        accept: ValidationAcceptor
    ): void {
        const modelRoot = modelDocument.parseResult.value as { import?: { file?: string } };
        const modelMetamodelRelativePath = modelRoot.import?.file;

        if (!modelMetamodelRelativePath) {
            accept("error", `The model at '${problem.model[0]}' does not reference any metamodel.`, {
                node: problem,
                property: "model",
                index: 0
            });
            return;
        }

        const expectedMetamodelDoc = this.resolveDocument(sourceDocument, problem.metamodel[0]);
        const actualMetamodelDoc = this.resolveDocument(modelDocument, modelMetamodelRelativePath);

        if (expectedMetamodelDoc == undefined || actualMetamodelDoc == undefined) {
            return;
        }

        if (!isMetamodelCompatible(actualMetamodelDoc, expectedMetamodelDoc, this.documents)) {
            accept(
                "error",
                `The model at '${problem.model[0]}' references a metamodel that is not compatible with the one specified in the problem section.`,
                {
                    node: problem,
                    property: "model",
                    index: 0
                }
            );
        }
    }

    /**
     * Checks that the goal section defines at least one objective.
     *
     * @param goal The goal section to check
     * @param accept The validation acceptor
     */
    private validateAtLeastOneObjective(goal: GoalSectionType, accept: ValidationAcceptor): void {
        if (!goal.objectives || goal.objectives.length === 0) {
            accept("error", "At least one objective must be defined in the goal section.", {
                node: goal
            });
        }
    }

    /**
     * Validates that all functions referenced in objectives take no arguments and return a numeric type.
     * Uses only AST-level information (no type system).
     *
     * @param goal The goal section containing objectives
     * @param accept The validation acceptor
     */
    private validateObjectiveFunctions(goal: GoalSectionType, accept: ValidationAcceptor): void {
        for (const objectiveRef of goal.objectives ?? []) {
            this.validateObjectiveFunction(objectiveRef, accept);
        }
    }

    /**
     * Validates a single objective's function reference signature.
     *
     * @param objective The objective node to check
     * @param accept The validation acceptor
     */
    private validateObjectiveFunction(objective: ObjectiveType, accept: ValidationAcceptor): void {
        const fn = objective.objective?.ref;
        if (fn == undefined) {
            return;
        }

        const parameters = fn.parameterList?.parameters ?? [];
        if (parameters.length > 0) {
            accept(
                "error",
                `Objective function '${fn.name}' must take no arguments, but has ${parameters.length} parameter(s).`,
                {
                    node: objective,
                    property: "objective"
                }
            );
        }

        const returnType = fn.returnType;
        if (returnType == undefined) {
            accept(
                "error",
                `Objective function '${fn.name}' must declare a return type (int, double, float, or long).`,
                {
                    node: objective,
                    property: "objective"
                }
            );
            return;
        }

        const typeName = this.extractTypeName(returnType);
        if (typeName == undefined || !NUMERIC_TYPE_NAMES.has(typeName)) {
            accept(
                "error",
                `Objective function '${fn.name}' must return a numeric type (int, double, float, or long), but returns '${typeName ?? "unknown"}'.`,
                {
                    node: objective,
                    property: "objective"
                }
            );
        }
    }

    /**
     * Validates that all functions referenced in constraints take no arguments and return a numeric
     * type. A return value of 0.0 means the constraint is satisfied; any non-zero value represents
     * the magnitude of violation.
     *
     * @param goal The goal section containing constraints
     * @param accept The validation acceptor
     */
    private validateConstraintFunctions(goal: GoalSectionType, accept: ValidationAcceptor): void {
        for (const constraintRef of goal.constraints ?? []) {
            this.validateConstraintFunction(constraintRef, accept);
        }
    }

    /**
     * Validates a single constraint's function reference signature.
     * The function must return a numeric type where 0.0 = satisfied and non-zero = magnitude of violation.
     *
     * @param constraintRef The constraint reference node to check
     * @param accept The validation acceptor
     */
    private validateConstraintFunction(constraintRef: ConstraintReferenceType, accept: ValidationAcceptor): void {
        const fn = constraintRef.constraint?.ref;
        if (fn == undefined) {
            return;
        }

        const parameters = fn.parameterList?.parameters ?? [];
        if (parameters.length > 0) {
            accept(
                "error",
                `Constraint function '${fn.name}' must take no arguments, but has ${parameters.length} parameter(s).`,
                {
                    node: constraintRef,
                    property: "constraint"
                }
            );
        }

        const returnType = fn.returnType;
        if (returnType == undefined) {
            accept(
                "error",
                `Constraint function '${fn.name}' must declare a return type (int, double, float, or long).`,
                {
                    node: constraintRef,
                    property: "constraint"
                }
            );
            return;
        }

        const typeName = this.extractTypeName(returnType);
        if (typeName == undefined || !NUMERIC_TYPE_NAMES.has(typeName)) {
            accept(
                "error",
                `Constraint function '${fn.name}' must return a numeric type (int, double, float, or long), but returns '${typeName ?? "unknown"}'. A return value of 0.0 means satisfied; any non-zero value represents the magnitude of violation.`,
                {
                    node: constraintRef,
                    property: "constraint"
                }
            );
        }
    }

    /**
     * Validates a multiplicity: checks that bounds are non-negative, and for range
     * multiplicities also checks that the upper bound is >= the lower bound.
     * Mirrors the checks from the metamodel language.
     *
     * @param multiplicity The multiplicity node to check
     * @param node The parent AST node used for error reporting
     * @param accept The validation acceptor
     */
    private validateMultiplicityRange(multiplicity: MultiplicityType, node: AstNode, accept: ValidationAcceptor): void {
        if (multiplicity.$type === SingleMultiplicity.name) {
            const single = multiplicity as SingleMultiplicityType;
            if (single.numericValue !== undefined && single.numericValue < 0) {
                accept("error", `Multiplicity value (${single.numericValue}) must not be negative.`, {
                    node,
                    property: "multiplicity"
                });
            }
        } else if (multiplicity.$type === RangeMultiplicity.name) {
            const range = multiplicity as RangeMultiplicityType;
            if (range.lower < 0) {
                accept("error", `Multiplicity lower bound (${range.lower}) must not be negative.`, {
                    node,
                    property: "multiplicity"
                });
            }
            if (range.upperNumeric !== undefined && range.upperNumeric < 0) {
                accept("error", `Multiplicity upper bound (${range.upperNumeric}) must not be negative.`, {
                    node,
                    property: "multiplicity"
                });
            }
            if (range.upper === "*") {
                return;
            }
            if (range.upperNumeric !== undefined && range.upperNumeric < range.lower) {
                accept(
                    "error",
                    `Invalid multiplicity range: upper bound (${range.upperNumeric}) must be >= lower bound (${range.lower}).`,
                    { node, property: "multiplicity" }
                );
            }
        }
    }

    /**
     * Extracts the type name from a return type AST node.
     * Handles both class types (with a name field) and void types.
     *
     * @param returnType The return type AST node (a union of base type and void type)
     * @returns The type name string or undefined if it cannot be determined
     */
    private extractTypeName(returnType: { $type: string; name?: string }): string | undefined {
        if ("name" in returnType && typeof returnType.name === "string") {
            return returnType.name;
        }
        if (returnType.$type.toLowerCase().includes("void")) {
            return "void";
        }
        return undefined;
    }

    /**
     * Resolves a relative file path from a document and returns the corresponding LangiumDocument.
     *
     * @param fromDocument The document from which the relative path is resolved
     * @param relativePath The relative path to resolve
     * @returns The resolved LangiumDocument or undefined
     */
    private resolveDocument(fromDocument: LangiumDocument, relativePath: string): LangiumDocument | undefined {
        return resolveRelativeDocument(fromDocument, relativePath, this.documents);
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

    /**
     * Validates a function file import.
     * Checks that imported functions come from script files that either:
     * - Have no `using` statement (no metamodel dependency), or
     * - Have a `using` statement with a compatible metamodel
     *
     * @param fileImport The function file import to validate
     * @param accept The validation acceptor
     */
    validateFunctionFileImport(fileImport: FunctionFileImportType, accept: ValidationAcceptor): void {
        const document = AstUtils.getDocument(fileImport);
        const scriptDoc = this.resolveDocument(document, fileImport.file);

        if (scriptDoc == undefined) {
            accept(
                "error",
                `Cannot resolve import path '${fileImport.file}'. The file does not exist or is not loaded.`,
                {
                    node: fileImport,
                    property: "file"
                }
            );
            return;
        }

        const scriptRoot = scriptDoc.parseResult?.value;
        if (scriptRoot == undefined || scriptRoot.$type !== Script.name) {
            return;
        }

        const scriptNode = scriptRoot as ScriptType;
        const scriptMetamodelPath = scriptNode.metamodelImport?.file;

        if (scriptMetamodelPath == undefined) {
            return;
        }

        const problemSection = findProblemSection(fileImport);
        const configMetamodelUri = problemSection != undefined ? getMetamodelUri(document, problemSection) : undefined;
        const configMetamodelDoc =
            configMetamodelUri != undefined ? this.documents.getDocument(configMetamodelUri) : undefined;

        if (configMetamodelDoc == undefined) {
            accept(
                "error",
                `Cannot validate imported function from '${fileImport.file}': no metamodel specified in problem section.`,
                {
                    node: fileImport,
                    property: "file"
                }
            );
            return;
        }

        const scriptMetamodelDoc = this.resolveDocument(scriptDoc, scriptMetamodelPath);
        if (scriptMetamodelDoc == undefined) {
            accept(
                "error",
                `Cannot resolve script file's metamodel path '${scriptMetamodelPath}'. The file does not exist or is not loaded.`,
                {
                    node: fileImport,
                    property: "file"
                }
            );
            return;
        }

        if (!isMetamodelCompatible(scriptMetamodelDoc, configMetamodelDoc, this.documents)) {
            accept(
                "error",
                `The script file '${fileImport.file}' uses a metamodel that is not compatible with the problem section's metamodel.`,
                {
                    node: fileImport,
                    property: "file"
                }
            );
        }
    }
}
