import type { TypirLangiumSpecifics } from "typir-langium";
import type { ExpressionTypes } from "../grammar/expressionTypes.js";
import type { CustomValueType } from "../typir-extensions/kinds/custom-value/custom-value-type.js";
import { isCustomValueType } from "../typir-extensions/kinds/custom-value/custom-value-type.js";
import { isCustomFunctionType } from "../typir-extensions/kinds/custom-function/custom-function-type.js";
import { isCustomLambdaType } from "../typir-extensions/kinds/custom-lambda/custom-lambda-type.js";
import { PartialTypeSystem, type PrimitiveTypes } from "./partialTypeSystem.js";
import { inferMethodAccess, inferPropertyAccess } from "../typir-extensions/rules/inferMemberAccess.js";
import { inferCall } from "../typir-extensions/rules/inferCall.js";
import { validateMethodAccess, validatePropertyAccess } from "../typir-extensions/rules/validateMemberAccess.js";
import { validateCall } from "../typir-extensions/rules/validateCall.js";
import { findCommonParentType } from "../typir-extensions/rules/commonParentType.js";
import { assertUnreachable } from "@mdeo/language-common";
import type { ExpressionTypirServices } from "./services.js";
import type { TypeSystemConfig } from "./typeSystemConfig.js";

/**
 * Partial type system implementation for expression-related type inference and validation.
 * This class handles type inference and validation rules for literals, unary/binary/ternary expressions,
 * member access, and call expressions.
 *
 * @template Specifics The language-specific type system configuration extending TypirLangiumSpecifics
 */
export class ExpressionPartialTypeSystem<Specifics extends TypirLangiumSpecifics> extends PartialTypeSystem<
    Specifics,
    ExpressionTypes
> {
    constructor(
        typir: ExpressionTypirServices<Specifics>,
        types: ExpressionTypes,
        protected readonly primitiveTypes: PrimitiveTypes,
        protected readonly nullablePrimitiveTypes: PrimitiveTypes,
        protected readonly typeSystemConfig: TypeSystemConfig
    ) {
        super(typir, types);
    }

    override registerRules(): void {
        this.registerLiteralRules();
        this.registerIdentifierRules();
        this.registerMemberAccessRules();
        this.registerMemberCallRules();
        this.registerCallRules();
        this.registerUnaryExpressionRules();
        this.registerBinaryExpressionRules();
        this.registerTernaryExpressionRules();
        this.registerTypeCheckRules();
        this.registerTypeCastRules();
        this.registerAssertNonNullRules();
        this.registerListExpressionRules();
    }

    /**
     * Registers type inference rules for literal expressions.
     * Handles int, long, float, double, string, and boolean literals.
     */
    private registerLiteralRules(): void {
        this.registerInferenceRule(this.types.intLiteralExpressionType, () => this.primitiveTypes.int);
        this.registerInferenceRule(this.types.longLiteralExpressionType, () => this.primitiveTypes.long);
        this.registerInferenceRule(this.types.floatLiteralExpressionType, () => this.primitiveTypes.float);
        this.registerInferenceRule(this.types.doubleLiteralExpressionType, () => this.primitiveTypes.double);
        this.registerInferenceRule(this.types.stringLiteralExpressionType, () => this.primitiveTypes.string);
        this.registerInferenceRule(this.types.booleanLiteralExpressionType, () => this.primitiveTypes.boolean);
        this.registerInferenceRule(this.types.nullLiteralExpressionType, () =>
            this.typir.factory.CustomNull.getOrCreate()
        );
    }

    /**
     * Registers type inference and validation rules for identifier expressions.
     * Uses the scope provider to infer types and validates that identifiers exist and are initialized.
     */
    private registerIdentifierRules(): void {
        this.registerInferenceRule(this.types.identifierExpressionType, (node) => {
            const scope = this.typir.ScopeProvider.getScope(node);
            const entry = scope.getEntry(node.name);

            if (entry == undefined) {
                return {
                    $problem: this.inferenceProblem,
                    languageNode: node,
                    location: `Cannot find identifier '${node.name}'.`,
                    subProblems: []
                };
            }

            const inferredType = entry.inferType();
            if (Array.isArray(inferredType)) {
                return inferredType[0];
            }
            return inferredType;
        });

        this.registerValidationRule(this.types.identifierExpressionType, (node, accept) => {
            const scope = this.typir.ScopeProvider.getScope(node);
            const entry = scope.getEntry(node.name);

            if (!entry) {
                accept({
                    $problem: this.validationProblem,
                    severity: "error",
                    languageNode: node,
                    message: `Cannot find identifier '${node.name}'.`,
                    subProblems: []
                });
                return;
            }

            if (!scope.isEntryInitialized(entry)) {
                accept({
                    $problem: this.validationProblem,
                    severity: "error",
                    languageNode: node,
                    message: `Identifier '${node.name}' is used before being initialized.`,
                    subProblems: []
                });
            }
        });
    }

    /**
     * Registers type inference and validation rules for member access expressions.
     * Handles property and method access, including null-chaining operators.
     */
    private registerMemberAccessRules(): void {
        this.registerInferenceRule(this.types.memberAccessExpressionType, (node) => {
            const inferResult = inferPropertyAccess(node, node.expression, node.member, this.typir);
            if (node.isNullChaining && isCustomValueType(inferResult)) {
                return inferResult.asNullable;
            }
            if (Array.isArray(inferResult)) {
                return inferResult[0];
            }
            return inferResult;
        });

        this.registerValidationRule(this.types.memberAccessExpressionType, (node, accept) =>
            validatePropertyAccess(node, node.expression, node.member, node.isNullChaining, this.typir).forEach(accept)
        );
    }

    /**
     * Registers type inference and validation rules for member call expressions.
     * Handles combined member access + call (e.g. `obj.method(args)`) as a single AST node.
     */
    private registerMemberCallRules(): void {
        this.registerInferenceRule(this.types.memberCallExpressionType, (node) => {
            const methodType = inferMethodAccess(node, node.expression, node.member, this.typir);
            const callableType = isCustomFunctionType(methodType)
                ? methodType
                : (() => {
                      const propertyType = inferPropertyAccess(node, node.expression, node.member, this.typir);
                      return isCustomLambdaType(propertyType) ? propertyType : undefined;
                  })();
            if (callableType == undefined) {
                return Array.isArray(methodType)
                    ? methodType[0]!
                    : {
                          $problem: this.inferenceProblem,
                          languageNode: node,
                          location: `Member '${node.member}' is not callable.`,
                          subProblems: []
                      };
            }
            const inferResult = inferCall(
                node,
                callableType,
                node.genericArgs?.typeArguments ?? [],
                node.arguments,
                this.typir
            );
            if (Array.isArray(inferResult)) {
                return inferResult[0];
            }
            if (isCustomValueType(inferResult) && node.isNullChaining) {
                return inferResult.asNullable;
            }
            return inferResult;
        });

        this.registerValidationRule(this.types.memberCallExpressionType, (node, accept) => {
            const methodType = inferMethodAccess(node, node.expression, node.member, this.typir);
            if (isCustomFunctionType(methodType)) {
                validateMethodAccess(node, node.expression, node.member, node.isNullChaining, this.typir).forEach(
                    accept
                );
                validateCall(
                    node,
                    methodType,
                    node.genericArgs?.typeArguments ?? [],
                    node.arguments,
                    this.typir
                ).forEach(accept);
            } else {
                const propertyType = inferPropertyAccess(node, node.expression, node.member, this.typir);
                if (isCustomLambdaType(propertyType)) {
                    validatePropertyAccess(node, node.expression, node.member, node.isNullChaining, this.typir).forEach(
                        accept
                    );
                    validateCall(
                        node,
                        propertyType,
                        node.genericArgs?.typeArguments ?? [],
                        node.arguments,
                        this.typir
                    ).forEach(accept);
                } else {
                    validateMethodAccess(node, node.expression, node.member, node.isNullChaining, this.typir).forEach(
                        accept
                    );
                }
            }
        });
    }

    /**
     * Registers type inference and validation rules for call expressions.
     * Handles function and method calls with generic type arguments.
     */
    private registerCallRules(): void {
        this.registerInferenceRule(this.types.callExpressionType, (node) => {
            const functionType = this.inference.inferType(node.expression);
            if (!isCustomFunctionType(functionType) && !isCustomLambdaType(functionType)) {
                if (Array.isArray(functionType)) {
                    return functionType[0];
                }
                return {
                    $problem: this.inferenceProblem,
                    languageNode: node,
                    location: `Cannot call expression of non-function type '${functionType.getName()}'.`,
                    subProblems: []
                };
            }
            return inferCall(node, functionType, node.genericArgs?.typeArguments ?? [], node.arguments, this.typir);
        });

        this.registerValidationRule(this.types.callExpressionType, (node, accept) => {
            const functionType = this.inference.inferType(node.expression);
            if (!isCustomFunctionType(functionType) && !isCustomLambdaType(functionType)) {
                return;
            }
            validateCall(node, functionType, node.genericArgs?.typeArguments ?? [], node.arguments, this.typir).forEach(
                accept
            );
        });
    }

    /**
     * Registers type inference and validation rules for unary expressions.
     * Handles unary minus (-) and logical not (!) operators.
     */
    private registerUnaryExpressionRules(): void {
        this.registerInferenceRule(this.types.unaryExpressionType, (node) => {
            if (node.expression == undefined) {
                return {
                    $problem: this.inferenceProblem,
                    languageNode: node,
                    location: `Unary operator '${node.operator}' requires an operand.`,
                    subProblems: []
                };
            }
            const expressionType = this.inference.inferType(node.expression);
            if (Array.isArray(expressionType)) {
                return expressionType[0];
            }
            if (node.operator === "-") {
                if (this.assignability.isAssignable(expressionType, this.primitiveTypes.double)) {
                    return expressionType;
                } else {
                    return {
                        $problem: this.inferenceProblem,
                        languageNode: node,
                        location: `Type '${expressionType.getName()}' is not valid for unary operator '-'.`,
                        subProblems: []
                    };
                }
            } else if (node.operator === "!") {
                if (this.assignability.isAssignable(expressionType, this.primitiveTypes.boolean)) {
                    return this.primitiveTypes.boolean;
                } else {
                    return {
                        $problem: this.inferenceProblem,
                        languageNode: node,
                        location: `Type '${expressionType.getName()}' is not valid for unary operator '!'.`,
                        subProblems: []
                    };
                }
            } else {
                assertUnreachable(node.operator);
            }
        });

        this.registerValidationRule(this.types.unaryExpressionType, (node, accept) => {
            const type = this.inference.inferType(node);
            if (!Array.isArray(type) || node.expression == undefined) {
                return;
            }
            const expressionType = this.inference.inferType(node.expression);
            if (Array.isArray(expressionType)) {
                return;
            }
            accept({
                $problem: this.validationProblem,
                severity: "error",
                languageNode: node,
                message: `Type '${expressionType.getName()}' is not valid for unary operator '${node.operator}'.`,
                subProblems: []
            });
        });
    }

    /**
     * Registers type inference and validation rules for binary expressions.
     * Handles arithmetic (+, -, *, /, %), comparison (<, >, <=, >=), equality (==, !=),
     * and logical (&&, ||) operators.
     */
    private registerBinaryExpressionRules(): void {
        this.registerInferenceRule(this.types.binaryExpressionType, (node) => {
            const operator = node.operator;
            const leftType = this.inference.inferType(node.left);
            const rightType = this.inference.inferType(node.right);
            if (Array.isArray(leftType)) {
                return leftType[0];
            }
            if (Array.isArray(rightType)) {
                return rightType[0];
            }

            if (!isCustomValueType(leftType) || !isCustomValueType(rightType)) {
                return {
                    $problem: this.inferenceProblem,
                    languageNode: node,
                    location: `Binary operators require two class or lambda types.`,
                    subProblems: []
                };
            }

            if (operator === "+") {
                if (this.isNumberType(leftType) && this.isNumberType(rightType)) {
                    return this.findClosestNumberType(leftType, rightType);
                } else if (
                    this.assignability.isAssignable(leftType, this.primitiveTypes.string) ||
                    this.assignability.isAssignable(rightType, this.primitiveTypes.string)
                ) {
                    return this.primitiveTypes.string;
                } else {
                    return {
                        $problem: this.inferenceProblem,
                        languageNode: node,
                        location: `Types '${leftType.getName()}' and '${rightType.getName()}' are not valid for operator '+'.`,
                        subProblems: []
                    };
                }
            } else if (
                operator === "-" ||
                operator === "*" ||
                operator === "/" ||
                operator === "%" ||
                operator === "<" ||
                operator === ">" ||
                operator === "<=" ||
                operator === ">="
            ) {
                if (this.isNumberType(leftType) && this.isNumberType(rightType)) {
                    const resultType = this.findClosestNumberType(leftType, rightType);
                    if (operator === "<" || operator === ">" || operator === "<=" || operator === ">=") {
                        return this.primitiveTypes.boolean;
                    }
                    return resultType;
                }
                return {
                    $problem: this.inferenceProblem,
                    languageNode: node,
                    location: `Operator '${operator}' requires two numbers.`,
                    subProblems: []
                };
            } else if (operator === "&&" || operator === "||") {
                if (
                    this.assignability.isAssignable(leftType, this.primitiveTypes.boolean) &&
                    this.assignability.isAssignable(rightType, this.primitiveTypes.boolean)
                ) {
                    return this.primitiveTypes.boolean;
                }
                return {
                    $problem: this.inferenceProblem,
                    languageNode: node,
                    location: `Operator '${operator}' requires two booleans.`,
                    subProblems: []
                };
            } else if (operator === "==" || operator === "!=" || operator === "===" || operator === "!==") {
                return this.primitiveTypes.boolean;
            } else if (operator === "??") {
                const commonType = findCommonParentType(leftType.asNonNullable, rightType, this.typir);
                return commonType;
            } else {
                assertUnreachable(operator);
            }
        });

        this.registerValidationRule(this.types.binaryExpressionType, (node, accept) => {
            const type = this.inference.inferType(node);
            if (!Array.isArray(type)) {
                return;
            }
            const leftType = this.inference.inferType(node.left);
            const rightType = this.inference.inferType(node.right);
            if (Array.isArray(leftType) || Array.isArray(rightType)) {
                return;
            }
            accept({
                $problem: this.validationProblem,
                severity: "error",
                languageNode: node,
                message: `Types '${leftType.getName()}' and '${rightType.getName()}' are not valid for binary operator '${node.operator}'.`,
                subProblems: []
            });
        });
    }

    /**
     * Registers type inference and validation rules for ternary expressions.
     * Handles the conditional operator (condition ? trueExpr : falseExpr).
     */
    private registerTernaryExpressionRules(): void {
        this.registerInferenceRule(this.types.ternaryExpressionType, (node) => {
            const trueType = this.inference.inferType(node.trueExpression);
            const falseType = this.inference.inferType(node.falseExpression);
            if (!isCustomValueType(trueType) || !isCustomValueType(falseType)) {
                return this.primitiveTypes.Any;
            }
            const commonType = findCommonParentType(trueType, falseType, this.typir);
            if (commonType != undefined) {
                return commonType;
            } else {
                return {
                    $problem: this.inferenceProblem,
                    languageNode: node,
                    location: `No common parent type found between '${trueType.getName()}' and '${falseType.getName()}'.`,
                    subProblems: []
                };
            }
        });

        this.registerValidationRule(this.types.ternaryExpressionType, (node, accept) => {
            const conditionType = this.inference.inferType(node.condition);
            if (Array.isArray(conditionType)) {
                return;
            }
            const expectedConditionType = this.primitiveTypes.boolean;
            if (!this.typir.Assignability.isAssignable(conditionType, expectedConditionType)) {
                accept({
                    $problem: this.validationProblem,
                    severity: "error",
                    languageNode: node,
                    message: `Condition type '${conditionType.getName()}' is not a boolean'.`,
                    subProblems: []
                });
            }

            const trueType = this.inference.inferType(node.trueExpression);
            const falseType = this.inference.inferType(node.falseExpression);

            if (!Array.isArray(trueType) && !isCustomValueType(trueType)) {
                accept({
                    $problem: this.validationProblem,
                    severity: "error",
                    languageNode: node.trueExpression,
                    message: `True expression must evaluate to a value type. Type '${trueType.getName()}' is not a value type.`,
                    subProblems: []
                });
            }

            if (!Array.isArray(falseType) && !isCustomValueType(falseType)) {
                accept({
                    $problem: this.validationProblem,
                    severity: "error",
                    languageNode: node.falseExpression,
                    message: `False expression must evaluate to a value type. Type '${falseType.getName()}' is not a value type.`,
                    subProblems: []
                });
            }
        });
    }

    /**
     * Registers type inference and validation rules for type-check expressions.
     */
    private registerTypeCheckRules(): void {
        this.registerInferenceRule(this.types.typeCheckExpressionType, () => this.primitiveTypes.boolean);

        this.registerValidationRule(this.types.typeCheckExpressionType, (node, accept) => {
            const expressionType = this.inference.inferType(node.expression);
            if (Array.isArray(expressionType)) {
                return;
            }
            const targetType = this.inference.inferType(node.checkType);
            if (Array.isArray(targetType)) {
                return;
            }
            if (!isCustomValueType(expressionType)) {
                accept({
                    $problem: this.validationProblem,
                    severity: "error",
                    languageNode: node.expression,
                    message: `Type-check expression requires a class or lambda type.`,
                    subProblems: []
                });
            }
            if (!isCustomValueType(targetType)) {
                accept({
                    $problem: this.validationProblem,
                    severity: "error",
                    languageNode: node.checkType,
                    message: `Type-check target type must be a class or lambda type.`,
                    subProblems: []
                });
            }
        });
    }

    /**
     * Registers type inference and validation rules for type-cast expressions.
     * Handles both safe and unsafe type casts.
     */
    private registerTypeCastRules(): void {
        this.registerInferenceRule(this.types.typeCastExpressionType, (node) => {
            const targetType = this.inference.inferType(node.targetType);
            if (Array.isArray(targetType)) {
                return targetType[0];
            }
            return targetType;
        });

        this.registerValidationRule(this.types.typeCastExpressionType, (node, accept) => {
            const expressionType = this.inference.inferType(node.expression);
            if (Array.isArray(expressionType)) {
                return;
            }
            const targetType = this.inference.inferType(node.targetType);
            if (Array.isArray(targetType)) {
                return;
            }
            if (!isCustomValueType(expressionType)) {
                accept({
                    $problem: this.validationProblem,
                    severity: "error",
                    languageNode: node.expression,
                    message: `Can only cast from class or lambda types.`,
                    subProblems: []
                });
            }
            if (!isCustomValueType(targetType)) {
                accept({
                    $problem: this.validationProblem,
                    severity: "error",
                    languageNode: node.targetType,
                    message: `Can only cast to class or lambda types.`,
                    subProblems: []
                });
            }
        });
    }

    /**
     * Registers type inference and validation rules for assert-non-null expressions.
     * Handles the non-null assertion operator (!!).
     */
    private registerAssertNonNullRules(): void {
        this.registerInferenceRule(this.types.assertNonNullExpressionType, (node) => {
            const expressionType = this.inference.inferType(node.expression);
            if (Array.isArray(expressionType)) {
                return expressionType[0];
            }
            if (isCustomValueType(expressionType)) {
                return expressionType.asNonNullable;
            } else {
                return {
                    $problem: this.inferenceProblem,
                    languageNode: node,
                    location: `Assert-non-null operator requires a class or lambda type.`,
                    subProblems: []
                };
            }
        });
        this.registerValidationRule(this.types.assertNonNullExpressionType, (node, accept) => {
            const expressionType = this.inference.inferType(node.expression);
            if (Array.isArray(expressionType)) {
                return;
            }
            if (!isCustomValueType(expressionType)) {
                accept({
                    $problem: this.validationProblem,
                    severity: "error",
                    languageNode: node,
                    message: `Assert-non-null operator requires a class or lambda type.`,
                    subProblems: []
                });
            }
        });
    }

    /**
     * Registers type inference and validation rules for list expressions.
     * Infers the list type based on the common parent type of all elements.
     */
    private registerListExpressionRules(): void {
        this.registerInferenceRule(this.types.listExpressionType, (node) => {
            const elements = node.elements;

            if (elements.length === 0) {
                const listType = this.typeSystemConfig.createListType({
                    package: "builtin",
                    type: "Any",
                    isNullable: true
                });
                return this.typir.TypeDefinitions.resolveCustomClassOrLambdaType(listType);
            }

            const firstElementType = this.inference.inferType(elements[0]);
            if (Array.isArray(firstElementType)) {
                return firstElementType[0];
            }
            if (!isCustomValueType(firstElementType)) {
                return {
                    $problem: this.inferenceProblem,
                    languageNode: node,
                    location: `List element must be a value type.`,
                    subProblems: []
                };
            }

            let commonType: CustomValueType = firstElementType;
            for (let i = 1; i < elements.length; i++) {
                const elementType = this.inference.inferType(elements[i]);
                if (Array.isArray(elementType)) {
                    return elementType[0];
                }
                if (!isCustomValueType(elementType)) {
                    return {
                        $problem: this.inferenceProblem,
                        languageNode: elements[i],
                        location: `List element must be a value type.`,
                        subProblems: []
                    };
                }
                const newCommonType = findCommonParentType(commonType, elementType, this.typir);
                commonType = newCommonType;
            }

            const elementValueType = this.typir.TypeDefinitions.convertCustomTypeToValueType(commonType);
            const listType = this.typeSystemConfig.createListType(elementValueType);
            return this.typir.TypeDefinitions.resolveCustomClassOrLambdaType(listType);
        });

        this.registerValidationRule(this.types.listExpressionType, (node, accept) => {
            const elements = node.elements;
            for (const element of elements) {
                const elementType = this.inference.inferType(element);
                if (Array.isArray(elementType)) {
                    continue;
                }
                if (!isCustomValueType(elementType)) {
                    accept({
                        $problem: this.validationProblem,
                        severity: "error",
                        languageNode: element,
                        message: `List element must be a value type, got '${elementType.getName()}'.`,
                        subProblems: []
                    });
                }
            }
        });
    }

    /**
     * Helper function to check if a type is a number type (assignable to double).
     *
     * @param type The type to check
     * @returns True if the type is a number type, false otherwise
     */
    private isNumberType(type: CustomValueType): boolean {
        return this.assignability.isAssignable(type, this.primitiveTypes.double);
    }

    /**
     * Finds the closest assignable type between two number types.
     * Checks assignability in both directions and returns the one that works.
     *
     * @param left The left operand type
     * @param right The right operand type
     * @returns The closest assignable type
     * @throws Error if neither type is assignable to the other
     */
    private findClosestNumberType(left: CustomValueType, right: CustomValueType): CustomValueType {
        if (this.assignability.isAssignable(left, right)) {
            return right;
        } else if (this.assignability.isAssignable(right, left)) {
            return left;
        } else {
            throw new Error(
                `No assignable relationship found between number types '${left.getName()}' and '${right.getName()}'.`
            );
        }
    }
}
