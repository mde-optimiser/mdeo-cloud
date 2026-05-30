import type { TypirLangiumSpecifics } from "typir-langium";
import { PartialTypeSystem, type PrimitiveTypes } from "./partialTypeSystem.js";
import { sharedImport } from "@mdeo/language-shared";
import type {
    ForStatementType,
    ForStatementVariableDeclarationType,
    StatementTypes
} from "../grammar/statementTypes.js";
import type { ExpressionTypirServices } from "./services.js";
import type { CustomClassType } from "../typir-extensions/kinds/custom-class/custom-class-type.js";
import { isCustomClassType } from "../typir-extensions/kinds/custom-class/custom-class-type.js";
import { isCustomValueType } from "../typir-extensions/kinds/custom-value/custom-value-type.js";
import { isCustomVoidType } from "../typir-extensions/kinds/custom-void/custom-void-type.js";
import type { InferenceProblem, TypeInferenceResultWithoutInferringChildren, ValidationProblemAcceptor } from "typir";
import type {
    BaseExpressionType,
    ExpressionTypes,
    IdentifierExpressionType,
    MemberAccessExpressionType
} from "../grammar/expressionTypes.js";
import type { CustomValueType } from "../typir-extensions/kinds/custom-value/custom-value-type.js";
import type { ClassType } from "../typir-extensions/config/type.js";
import type { AstNode } from "langium";

const { AstUtils } = sharedImport("langium");
const { InferenceProblem: InferenceProblemConstant } = sharedImport("typir");

/**
 * Partial type syste m implementation for statement-related AST nodes.
 *
 * @template Specifics The language-specific type system configuration extending TypirLangiumSpecifics
 */
export class StatementPartialTypeSystem<Specifics extends TypirLangiumSpecifics> extends PartialTypeSystem<
    Specifics,
    StatementTypes
> {
    constructor(
        typir: ExpressionTypirServices<Specifics>,
        types: StatementTypes,
        protected readonly expressionTypes: ExpressionTypes,
        protected readonly primitiveTypes: PrimitiveTypes,
        protected readonly nullablePrimitiveTypes: PrimitiveTypes,
        protected readonly iterableType: ClassType
    ) {
        super(typir, types);
    }

    override registerRules(): void {
        this.registerVariableDeclarationRules();
        this.registerControlFlowStatementRules();
        this.registerAssignmentRules();
        this.registerBreakContinueStatementRules();
        this.registerExpressionStatementRules();
        this.registerStatementsScopeRules();

        this.registerInferenceRule(this.types.forStatementVariableDeclarationType, (node) => {
            return this.inferForStatementVariableDeclarationType(node);
        });
    }

    /**
     * Registers inference and validation rules for variable declaration statements.
     */
    private registerVariableDeclarationRules(): void {
        this.registerInferenceRule(this.types.variableDeclarationStatementType, (node) => {
            if (node.type != undefined) {
                return node.type;
            } else {
                return node.initialValue!;
            }
        });

        this.registerValidationRule(this.types.variableDeclarationStatementType, (node, accept) => {
            if (node.initialValue != undefined && node.type != undefined) {
                const initialValueType = this.inference.inferType(node.initialValue);
                const declarationType = this.inference.inferType(node.type);
                if (Array.isArray(initialValueType) || Array.isArray(declarationType)) {
                    return;
                }
                if (!this.assignability.isAssignable(initialValueType, declarationType)) {
                    accept({
                        $problem: this.validationProblem,
                        languageNode: node,
                        message: `Initial value type '${initialValueType.getName()}' is not assignable to variable type '${declarationType.getName()}'.`,
                        severity: "error"
                    });
                }
            }

            if (node.type == undefined && node.initialValue != undefined) {
                const initialValueType = this.inference.inferType(node.initialValue);
                if (!Array.isArray(initialValueType) && !isCustomValueType(initialValueType)) {
                    accept({
                        $problem: this.validationProblem,
                        languageNode: node,
                        message: `Only value types can be assigned to variables. Type '${initialValueType.getName()}' is not a value type.`,
                        severity: "error"
                    });
                }
            }

            const variableType =
                node.type != undefined
                    ? this.inference.inferType(node.type)
                    : node.initialValue != undefined
                      ? this.inference.inferType(node.initialValue)
                      : undefined;
            if (
                variableType != undefined &&
                !Array.isArray(variableType) &&
                isCustomClassType(variableType) &&
                variableType.details.definition.isVirtual === true
            ) {
                accept({
                    $problem: this.validationProblem,
                    languageNode: node,
                    message: `Cannot declare a variable with a virtual type.`,
                    severity: "error"
                });
            }
        });
    }

    /**
     * Registers validation rules for control flow statements like if, else-if, while, and do-while.
     */
    private registerControlFlowStatementRules(): void {
        this.registerValidationRule(this.types.ifStatementType, (node, accept) => {
            this.validateBooleanCondition(node.condition, accept, "If statement condition must be of type boolean.");
        });

        this.registerValidationRule(this.types.elseIfClauseType, (node, accept) => {
            this.validateBooleanCondition(node.condition, accept, "Else-if clause condition must be of type boolean.");
        });

        this.registerValidationRule(this.types.whileStatementType, (node, accept) => {
            this.validateBooleanCondition(node.condition, accept, "While statement condition must be of type boolean.");
        });
    }

    /**
     * Registers validation rules for assignment statements.
     */
    private registerAssignmentRules(): void {
        this.registerValidationRule(this.types.assignmentStatementType, (node, accept) => {
            const isIdentifier = this.astReflection.isInstance(
                node.left,
                this.expressionTypes.identifierExpressionType
            );
            const isMemberAccess = this.astReflection.isInstance(
                node.left,
                this.expressionTypes.memberAccessExpressionType
            );
            if (!isIdentifier && !isMemberAccess) {
                accept({
                    $problem: this.validationProblem,
                    languageNode: node,
                    message: "Assignment left-hand side must be an identifier or member access expression.",
                    severity: "error"
                });
                return;
            }

            if (node.left == undefined || node.right == undefined) {
                return;
            }

            const rightType = this.inference.inferType(node.right);
            const leftType = this.inference.inferType(node.left);
            if (Array.isArray(rightType) || Array.isArray(leftType)) {
                return;
            }
            if (!this.assignability.isAssignable(rightType, leftType)) {
                accept({
                    $problem: this.validationProblem,
                    languageNode: node,
                    message: `Cannot assign value of type '${rightType.getName()}' to expression of type '${leftType.getName()}'.`,
                    severity: "error"
                });
            }
            let isReadonly = false;
            if (isIdentifier) {
                const scope = this.typir.ScopeProvider.getScope(node);
                const entry = scope.getEntry((node.left as IdentifierExpressionType).name);
                if (entry != undefined && entry.readonly === true) {
                    isReadonly = true;
                }
            } else {
                const memberAccessLeft = node.left as MemberAccessExpressionType;
                const expressionType = this.inference.inferType(memberAccessLeft.expression);
                if (isCustomValueType(expressionType)) {
                    const prop = expressionType.getProperty(memberAccessLeft.member);
                    if (prop != undefined && prop.readonly === true) {
                        isReadonly = true;
                    }
                }
            }
            if (isReadonly) {
                accept({
                    $problem: this.validationProblem,
                    languageNode: node,
                    message: `Cannot assign to readonly property.`,
                    severity: "error"
                });
            }
        });
    }

    /**
     * Helper to validate that a condition expression is assignable to boolean.
     * Keeps the repeated logic in one place to reduce duplication.
     *
     * @param condition The condition expression to validate
     * @param accept The validation problem acceptor function
     * @param message The error message to use if validation fails
     */
    private validateBooleanCondition(
        condition: BaseExpressionType,
        accept: ValidationProblemAcceptor<Specifics>,
        message: string
    ): void {
        if (condition == undefined) {
            return;
        }
        const conditionType = this.inference.inferType(condition);
        if (Array.isArray(conditionType)) {
            return;
        }
        if (!this.assignability.isAssignable(conditionType, this.primitiveTypes.boolean)) {
            accept({
                $problem: this.validationProblem,
                languageNode: condition,
                message,
                severity: "error"
            });
        }
    }

    /**
     * Infers the type of a for statement variable declaration by examining the iterable type.
     *
     * This method performs the following steps:
     * 1. Validates that the container is a ForStatement
     * 2. Infers the type of the iterable expression
     * 3. Finds the iterable type in the type hierarchy (direct or inherited)
     * 4. Extracts the first generic type argument from the iterable type
     *
     * @param node The for statement variable declaration node
     * @returns The inferred element type from the iterable, or an inference problem if inference fails
     */
    private inferForStatementVariableDeclarationType(
        node: ForStatementVariableDeclarationType
    ): TypeInferenceResultWithoutInferringChildren<Specifics> {
        const container = node.$container;

        if (!this.astReflection.isInstance(container, this.types.forStatementType)) {
            return <InferenceProblem<Specifics>>{
                $problem: InferenceProblemConstant,
                languageNode: node,
                location: "For statement variable declaration must be within a for statement.",
                subProblems: []
            };
        }

        const iterableType = this.inferIterableType(node, container);
        if ("$problem" in iterableType) {
            return iterableType;
        }

        const actualIterableType = this.findIterableTypeInHierarchy(node, iterableType);
        if ("$problem" in actualIterableType) {
            return actualIterableType;
        }

        return this.extractFirstGenericTypeArgument(actualIterableType);
    }

    /**
     * Infers the type of the iterable expression and validates it is a CustomValueType.
     *
     * @param node The for statement variable declaration node
     * @param container The containing ForStatement node
     * @returns The inferred iterable type, or an inference problem if inference fails
     */
    private inferIterableType(
        node: ForStatementVariableDeclarationType,
        container: ForStatementType
    ): CustomClassType | InferenceProblem<Specifics> {
        const iterableType = this.inference.inferType(container.iterable);

        if (Array.isArray(iterableType)) {
            return <InferenceProblem<Specifics>>{
                $problem: InferenceProblemConstant,
                languageNode: node,
                location: "Cannot infer type of iterable expression.",
                subProblems: iterableType
            };
        }

        if (!isCustomClassType(iterableType)) {
            return <InferenceProblem<Specifics>>{
                $problem: InferenceProblemConstant,
                languageNode: node,
                location: `Type '${iterableType.getName()}' is not iterable.`,
                subProblems: []
            };
        }

        return iterableType;
    }

    /**
     * Finds the iterable type in the type hierarchy, checking both the type itself and its parent types.
     *
     * @param node The for statement variable declaration node
     * @param iterableType The inferred type to search for iterable capability
     * @returns The iterable type from the hierarchy, or an inference problem if not found
     */
    private findIterableTypeInHierarchy(
        node: ForStatementVariableDeclarationType,
        iterableType: CustomClassType
    ): CustomClassType | InferenceProblem<Specifics> {
        if (iterableType.details.definition === this.iterableType) {
            return iterableType;
        }

        for (const superClass of iterableType.allSuperClasses) {
            if (superClass.details.definition === this.iterableType) {
                return superClass;
            }
        }

        return <InferenceProblem<Specifics>>{
            $problem: InferenceProblemConstant,
            languageNode: node,
            location: `Type '${iterableType.getName()}' is not iterable.`,
            subProblems: []
        };
    }

    /**
     * Extracts the first generic type argument from an iterable type.
     *
     * @param iterableType The iterable type with generic type arguments
     * @returns The first generic type argument
     * @throws Error if the iterable type has no generic type arguments
     */
    private extractFirstGenericTypeArgument(iterableType: CustomClassType): CustomValueType {
        const typeArgs = iterableType.details.typeArgs;

        if (typeArgs.size !== 1) {
            throw new Error(`Iterable type '${iterableType.getName()}' does not have any generic type arguments.`);
        }

        return typeArgs.values().next().value!;
    }

    /**
     * Registers validation rules for break and continue statements.
     *
     * These statements must be contained (directly or indirectly) within a loop statement
     * (for, while, or do-while). Uses Langium's AstUtils.getContainerOfType to traverse
     * the AST hierarchy.
     */
    private registerBreakContinueStatementRules(): void {
        this.registerValidationRule(this.types.breakStatementType, (node, accept) => {
            this.validateInsideLoop(node, accept, "Break statement must be inside a loop (for, while, or do-while).");
        });

        this.registerValidationRule(this.types.continueStatementType, (node, accept) => {
            this.validateInsideLoop(
                node,
                accept,
                "Continue statement must be inside a loop (for, while, or do-while)."
            );
        });
    }

    /**
     * Helper to validate that a statement is contained within a loop statement.
     *
     * Uses Langium's AstUtils.getContainerOfType to find the nearest ancestor matching
     * one of the loop statement types (for, while, do-while).
     *
     * @param node The statement node to validate
     * @param accept The validation problem acceptor function
     * @param message The error message to use if validation fails
     */
    private validateInsideLoop(node: AstNode, accept: ValidationProblemAcceptor<Specifics>, message: string): void {
        const isInsideLoop =
            AstUtils.getContainerOfType(
                node,
                (n: AstNode) =>
                    this.astReflection.isInstance(n, this.types.forStatementType) ||
                    this.astReflection.isInstance(n, this.types.whileStatementType)
            ) !== undefined;

        if (!isInsideLoop) {
            accept({
                $problem: this.validationProblem,
                languageNode: node,
                message,
                severity: "error"
            });
        }
    }

    /**
     * Registers validation rules for expression statements.
     *
     * Expression statements must evaluate to either void or a custom value type.
     * This prevents expressions that evaluate to class types from being used as statements.
     */
    private registerExpressionStatementRules(): void {
        this.registerValidationRule(this.types.expressionStatementType, (node, accept) => {
            const expressionType = this.inference.inferType(node.expression);

            if (Array.isArray(expressionType)) {
                return;
            }

            const isVoid = isCustomVoidType(expressionType);
            const isCustomValue = isCustomValueType(expressionType);

            if (!isVoid && !isCustomValue) {
                accept({
                    $problem: this.validationProblem,
                    languageNode: node,
                    message: `Expression statement must evaluate to void or a value type, but got '${expressionType.getName()}'.`,
                    severity: "error"
                });
            }
        });
    }

    /**
     * Registers validation rules for statements scopes.
     * Validates that variable names are unique within the scope.
     */
    private registerStatementsScopeRules(): void {
        this.registerValidationRule(this.types.statementsScopeType, (node, accept) => {
            const declaredNames = new Set<string>();
            for (const statement of node.statements ?? []) {
                if (!this.astReflection.isInstance(statement, this.types.variableDeclarationStatementType)) {
                    continue;
                }
                if (declaredNames.has(statement.name)) {
                    accept({
                        $problem: this.validationProblem,
                        languageNode: statement,
                        message: `Variable '${statement.name}' is already declared in this scope.`,
                        severity: "error"
                    });
                }
                declaredNames.add(statement.name);
            }
        });
    }
}
