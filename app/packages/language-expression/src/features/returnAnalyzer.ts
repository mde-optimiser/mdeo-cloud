import type { InferenceProblem, Type, TypirProblem, TypirSpecifics, ValidationProblem } from "typir";
import type { ExtendedTypirServices } from "../typir-extensions/service/extendedTypirServices.js";
import type { CustomValueType } from "../typir-extensions/kinds/custom-value/custom-value-type.js";
import { isCustomValueType } from "../typir-extensions/kinds/custom-value/custom-value-type.js";
import type { CustomVoidType } from "../typir-extensions/kinds/custom-void/custom-void-type.js";
import { isCustomVoidType } from "../typir-extensions/kinds/custom-void/custom-void-type.js";
import type { BoundScope } from "../typir-extensions/scope/scope.js";
import { findCommonParentType } from "../typir-extensions/rules/commonParentType.js";
import { sharedImport } from "@mdeo/language-shared";
import type { StatementControlFlow } from "./controlFlowAnalysis.js";
import type { StatementsScopeType } from "../grammar/statementTypes.js";

const { InferenceProblem: InferenceProblemConstant, ValidationProblem: ValidationProblemConstant } =
    sharedImport("typir");

/**
 * Interface for accessing return statement information from language nodes.
 * Implementations provide language-specific logic for identifying and extracting
 * information from return statements.
 *
 * @template Specifics Language-specific types extending TypirSpecifics
 */
export interface ReturnStatementAccessor<Specifics extends TypirSpecifics> {
    /**
     * Determines if a language node is a return statement.
     *
     * @param node The language node to check
     * @returns true if the node is a return statement, false otherwise
     */
    isReturnStatement(node: Specifics["LanguageType"]): boolean;

    /**
     * Extracts the returned expression from a return statement.
     * Should return undefined if the return statement has no expression (void return).
     *
     * @param node The return statement node
     * @returns The expression being returned, or undefined for void returns
     */
    getReturnExpression(node: Specifics["LanguageType"]): Specifics["LanguageType"] | undefined;
}

/**
 * Base abstract class for analyzing return statements in a statements block.
 * Handles both validation and inference modes.
 *
 * @template Specifics Language-specific types extending TypirSpecifics
 * @template TProblem The type of validation problems to collect
 */
export abstract class ReturnAnalyzerBase<Specifics extends TypirSpecifics, TProblem> {
    /**
     * List of validation/inference errors collected during analysis
     */
    readonly errors: TProblem[] = [];

    /**
     * The analyzed return type, if it could be determined
     */
    returnType: CustomValueType | CustomVoidType | undefined = undefined;

    /**
     * Whether all paths in the block return a value
     */
    allPathsReturn = false;

    /**
     * Creates a new return analyzer.
     *
     * @param scope The scope containing the statements to analyze
     * @param services Extended Typir services for type operations
     * @param accessor Language-specific accessor for return statement information
     * @param controlFlow The control flow analysis, which decides whether all paths return
     */
    constructor(
        protected readonly scope: BoundScope<Specifics>,
        readonly services: ExtendedTypirServices<Specifics>,
        protected readonly accessor: ReturnStatementAccessor<Specifics>,
        protected readonly controlFlow: StatementControlFlow
    ) {}

    /**
     * Creates an error/problem object for a validation/inference failure.
     * Must be implemented by subclasses to create language-specific error objects.
     *
     * @param languageNode The AST node where the error occurred
     * @param message Human-readable error message
     * @param subProblems Optional nested problems that caused this error
     * @returns A problem object of type TProblem
     */
    protected abstract createError(
        languageNode: Specifics["LanguageType"],
        message: string,
        subProblems?: TypirProblem[]
    ): TProblem;

    /**
     * Analyzes a return statement and updates the analyzer state.
     * Must be implemented by subclasses based on whether in inference or validation mode.
     *
     * @param statement The return statement node itself
     * @param returnExpr The expression being returned, or undefined for void returns
     */
    protected abstract analyzeReturnExpression(
        statement: Specifics["LanguageType"],
        returnExpr: Specifics["LanguageType"] | undefined
    ): void;

    /**
     * Analyzes every return statement of the body, reachable or not, and whether all paths return.
     * Should be called by subclasses after construction.
     */
    protected analyze(): void {
        const body = this.scope.scope.languageNode as StatementsScopeType | undefined;
        if (body == undefined) {
            return;
        }
        for (const statement of this.controlFlow.statementsOf(body)) {
            if (this.accessor.isReturnStatement(statement)) {
                this.analyzeReturnExpression(statement, this.accessor.getReturnExpression(statement));
            }
        }
        this.allPathsReturn = !this.controlFlow.completesNormally(body);
    }

    /**
     * Infers the return type from a return expression.
     *
     * @param returnExpr The expression being returned, or undefined for void returns
     * @returns The inferred type or a list of inference problems
     */
    protected inferReturnType(returnExpr: Specifics["LanguageType"]): Type | InferenceProblem<Specifics>[] {
        if (returnExpr != undefined) {
            return this.services.Inference.inferType(returnExpr);
        } else {
            return this.services.factory.CustomVoid.getOrCreate();
        }
    }
}

/**
 * Return analyzer for inference mode.
 * Infers the return type by finding the common parent type of all returned values.
 *
 * @template Specifics Language-specific types extending TypirSpecifics
 */
export class ReturnInferenceAnalyzer<Specifics extends TypirSpecifics> extends ReturnAnalyzerBase<
    Specifics,
    InferenceProblem<Specifics>
> {
    /**
     * Creates a new return inference analyzer.
     *
     * @param scope The scope containing the statements to analyze
     * @param services Extended Typir services for type operations
     * @param accessor Language-specific accessor for return statement information
     * @param controlFlow The control flow analysis, which decides whether all paths return
     */
    constructor(
        scope: BoundScope<Specifics>,
        services: ExtendedTypirServices<Specifics>,
        accessor: ReturnStatementAccessor<Specifics>,
        controlFlow: StatementControlFlow
    ) {
        super(scope, services, accessor, controlFlow);
        this.analyze();
    }

    /**
     * Analyzes a return statement and combines its type with previously seen return types.
     *
     * @param statement The return statement node itself
     * @param returnExpr The expression being returned, or undefined for void returns
     */
    protected override analyzeReturnExpression(
        statement: Specifics["LanguageType"],
        returnExpr: Specifics["LanguageType"] | undefined
    ): void {
        const inferredType = this.inferReturnType(returnExpr);

        if (Array.isArray(inferredType)) {
            this.errors.push(
                this.createError(returnExpr, `Return expression type could not be determined.`, inferredType)
            );
        } else if (isCustomValueType(inferredType)) {
            if (this.returnType == undefined) {
                this.returnType = inferredType;
            } else if (isCustomValueType(this.returnType)) {
                this.returnType = findCommonParentType(this.returnType, inferredType, this.services);
            }
        } else {
            this.errors.push(this.createError(returnExpr, `Return expression type is not a valid value type.`));
        }
    }

    /**
     * Creates an inference problem for a validation/inference failure.
     *
     * @param languageNode The AST node where the error occurred
     * @param message Human-readable error message
     * @param subProblems Optional nested problems that caused this error
     * @returns An inference problem object
     */
    protected override createError(
        languageNode: Specifics["LanguageType"],
        message: string,
        subProblems?: TypirProblem[]
    ): InferenceProblem<Specifics> {
        return {
            $problem: InferenceProblemConstant,
            languageNode,
            location: message,
            subProblems: subProblems ?? []
        };
    }
}

/**
 * Return analyzer for validation mode.
 * Validates that all return statements match the expected return type.
 *
 * @template Specifics Language-specific types extending TypirSpecifics
 */
export class ReturnValidationAnalyzer<Specifics extends TypirSpecifics> extends ReturnAnalyzerBase<
    Specifics,
    ValidationProblem<Specifics>
> {
    /**
     * Creates a new return validation analyzer.
     *
     * @param scope The scope containing the statements to analyze
     * @param expectedReturnType The expected return type to validate against
     * @param expectedReturnTypeLanguageNode The language node representing the expected return type (for error reporting)
     * @param services Extended Typir services for type operations
     * @param accessor Language-specific accessor for return statement information
     * @param controlFlow The control flow analysis, which decides whether all paths return
     */
    constructor(
        scope: BoundScope<Specifics>,
        private readonly expectedReturnType: CustomValueType | CustomVoidType,
        private readonly expectedReturnTypeLanguageNode: Specifics["LanguageType"],
        services: ExtendedTypirServices<Specifics>,
        accessor: ReturnStatementAccessor<Specifics>,
        controlFlow: StatementControlFlow
    ) {
        super(scope, services, accessor, controlFlow);
        this.returnType = expectedReturnType;
        this.analyze();
        this.validateAllPathsReturn();
    }

    /**
     * Analyzes a return statement and validates its type against the expected return type.
     *
     * @param statement The return statement node itself
     * @param returnExpr The expression being returned, or undefined for void returns
     */
    protected override analyzeReturnExpression(
        statement: Specifics["LanguageType"],
        returnExpr: Specifics["LanguageType"] | undefined
    ): void {
        const inferredType = this.inferReturnType(returnExpr);

        if (Array.isArray(inferredType)) {
            return;
        }
        if (!(isCustomValueType(inferredType) || isCustomVoidType(inferredType))) {
            this.errors.push(this.createError(statement, `Return expression type is not a valid value type.`));
            return;
        }

        const isAssignable = this.services.Assignability.isAssignable(inferredType, this.expectedReturnType);
        if (!isAssignable) {
            this.errors.push(
                this.createError(
                    statement,
                    `Return type '${inferredType.getName()}' is not assignable to expected return type '${this.expectedReturnType.getName()}'.`
                )
            );
        }
    }

    /**
     * Creates an error/problem object for a validation failure.
     *
     * @param languageNode The AST node where the error occurred
     * @param message Human-readable error message
     * @param subProblems Optional nested problems that caused this error
     * @returns A problem object of type ValidationProblem
     */
    protected override createError(
        languageNode: Specifics["LanguageType"],
        message: string,
        subProblems?: TypirProblem[]
    ): ValidationProblem<Specifics> {
        return {
            $problem: ValidationProblemConstant,
            languageNode,
            severity: "error",
            message,
            subProblems
        };
    }

    /**
     * Validates that all paths return a value if the expected return type is not void.
     */
    private validateAllPathsReturn(): void {
        const isVoidReturn = isCustomVoidType(this.expectedReturnType);

        if (!isVoidReturn && !this.allPathsReturn) {
            this.errors.push(
                this.createError(this.expectedReturnTypeLanguageNode, `Not all code paths return a value.`)
            );
        }
    }
}
