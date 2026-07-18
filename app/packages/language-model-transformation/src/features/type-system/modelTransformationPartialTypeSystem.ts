import {
    PartialTypeSystem,
    type ExpressionTypirServices,
    type PrimitiveTypes,
    type CustomValueType,
    type IdentifierExpressionType,
    isCustomValueType,
    isCustomVoidType,
    isCustomLambdaType,
    type BaseExpressionType
} from "@mdeo/language-expression";
import type { TypirLangiumSpecifics } from "typir-langium";
import type { ValidationProblemAcceptor, Type } from "typir";
import {
    LambdaExpression,
    PatternVariable,
    PatternVariableReassignment,
    PatternPropertyAssignment,
    IfExpressionStatement,
    ElseIfBranch,
    WhileExpressionStatement,
    PatternObjectInstance,
    WhereClause,
    expressionTypes,
    type LambdaExpressionType,
    type PatternPropertyAssignmentType
} from "../../grammar/modelTransformationTypes.js";
import {
    Property,
    SingleMultiplicity,
    RangeMultiplicity,
    type PropertyType,
    type MultiplicityType
} from "@mdeo/language-metamodel";
import { ModelTransformationLambdaScope } from "./modelTransformationLambdaScope.js";

/**
 * Types configuration for the Model Transformation partial type system.
 */
interface ModelTransformationTypes {
    readonly lambdaExpressionType: typeof LambdaExpression;
    readonly patternVariableType: typeof PatternVariable;
    readonly patternPropertyAssignmentType: typeof PatternPropertyAssignment;
    readonly ifExpressionStatementType: typeof IfExpressionStatement;
    readonly elseIfBranchType: typeof ElseIfBranch;
    readonly whileExpressionStatementType: typeof WhileExpressionStatement;
    readonly whereClauseType: typeof WhereClause;
}

/**
 * Partial type system for Model Transformation-specific AST nodes.
 * Handles type inference and validation for transformation constructs including
 * lambda expressions, pattern variables, property assignments, and control flow.
 */
export class ModelTransformationPartialTypeSystem extends PartialTypeSystem<
    TypirLangiumSpecifics,
    ModelTransformationTypes
> {
    /**
     * The primitive types from the type system.
     */
    private readonly primitiveTypes: PrimitiveTypes;

    /**
     * Creates an instance of ModelTransformationPartialTypeSystem.
     *
     * @param typir The typir services for Model Transformation.
     * @param primitiveTypes The primitive types from the type system.
     */
    constructor(typir: ExpressionTypirServices<TypirLangiumSpecifics>, primitiveTypes: PrimitiveTypes) {
        super(typir, {
            lambdaExpressionType: LambdaExpression,
            patternVariableType: PatternVariable,
            patternPropertyAssignmentType: PatternPropertyAssignment,
            ifExpressionStatementType: IfExpressionStatement,
            elseIfBranchType: ElseIfBranch,
            whileExpressionStatementType: WhileExpressionStatement,
            whereClauseType: WhereClause
        });
        this.primitiveTypes = primitiveTypes;
    }

    /**
     * Registers all type inference and validation rules.
     */
    override registerRules(): void {
        this.registerLambdaInferenceRule();
        this.registerLambdaValidationRule();
        this.registerPatternVariableInferenceRule();
        this.registerPatternVariableValidationRule();
        this.registerPatternVariableReassignmentValidationRule();
        this.registerIdentifierExpressionValidationRule();
        this.registerPatternPropertyAssignmentValidationRule();
        this.registerControlFlowValidationRules();
        this.registerWhereClauseValidationRule();
        this.registerPatternObjectInstanceInferenceRule();
    }

    /**
     * Registers the type inference rule for lambda expressions.
     * Uses a two-step inference process:
     * 1. Get the lambda scope and its type inference result
     * 2. If the result is complete, use it; otherwise infer the return type from the lambda body
     */
    private registerLambdaInferenceRule(): void {
        this.registerInferenceRule(LambdaExpression, (node) => {
            return this.inferLambdaType(node);
        });
    }

    /**
     * Infers the type of a lambda expression.
     *
     * @param node The lambda expression node.
     * @returns The inferred lambda type or an inference problem.
     */
    private inferLambdaType(node: LambdaExpressionType) {
        const scope = this.typir.ScopeProvider.getScope(node).scope;

        if (!(scope instanceof ModelTransformationLambdaScope)) {
            return {
                $problem: this.inferenceProblem,
                languageNode: node,
                location: "Lambda scope could not be created.",
                subProblems: []
            };
        }

        const lambdaTypeInference = scope.lambdaTypeInference;
        if (Array.isArray(lambdaTypeInference)) {
            return lambdaTypeInference[0];
        }

        if ("type" in lambdaTypeInference) {
            return lambdaTypeInference.type;
        }

        return this.inferLambdaReturnType(node, lambdaTypeInference.parameterTypes);
    }

    /**
     * Infers the return type of a lambda expression from its expression body.
     *
     * @param node The lambda expression node.
     * @param parameterTypes The inferred parameter types.
     * @returns The inferred lambda type or an inference problem.
     */
    private inferLambdaReturnType(node: LambdaExpressionType, parameterTypes: CustomValueType[]) {
        if (node.expression == undefined) {
            return {
                $problem: this.inferenceProblem,
                languageNode: node,
                location: "Lambda must have an expression body.",
                subProblems: []
            };
        }

        const expressionType = this.inference.inferType(node.expression);
        if (Array.isArray(expressionType)) {
            return expressionType[0];
        }

        if (!isCustomValueType(expressionType) && !isCustomVoidType(expressionType)) {
            return {
                $problem: this.inferenceProblem,
                languageNode: node.expression,
                location: "Lambda expression must return a value type.",
                subProblems: []
            };
        }

        return this.typir.factory.CustomLambdas.getOrCreate({
            returnType: expressionType,
            parameterTypes,
            typeArgs: new Map(),
            isNullable: false
        });
    }

    /**
     * Registers the validation rule for lambda expressions.
     * Validates parameter count and return type assignability.
     */
    private registerLambdaValidationRule(): void {
        this.registerValidationRule(LambdaExpression, (node, accept) => {
            this.validateLambdaScope(node, accept);
            this.validateLambdaParameters(node, accept);
            this.validateLambdaReturnType(node, accept);
        });
    }

    /**
     * Validates that the lambda scope was created successfully.
     *
     * @param node The lambda expression node.
     * @param accept The validation problem acceptor.
     */
    private validateLambdaScope(
        node: LambdaExpressionType,
        accept: ValidationProblemAcceptor<TypirLangiumSpecifics>
    ): void {
        const scope = this.typir.ScopeProvider.getScope(node).scope;

        if (!(scope instanceof ModelTransformationLambdaScope)) {
            accept({
                languageNode: node,
                message: "Lambda scope could not be created.",
                severity: "error"
            });
        }
    }

    /**
     * Validates lambda parameters for duplicates and count.
     *
     * @param node The lambda expression node.
     * @param accept The validation problem acceptor.
     */
    private validateLambdaParameters(
        node: LambdaExpressionType,
        accept: ValidationProblemAcceptor<TypirLangiumSpecifics>
    ): void {
        const parameterNames = new Set<string>();
        for (const param of node.parameterList.parameters) {
            if (parameterNames.has(param.name)) {
                accept({
                    languageNode: param,
                    message: `Duplicate parameter name '${param.name}'.`,
                    severity: "error"
                });
            }
            parameterNames.add(param.name);
        }

        const scope = this.typir.ScopeProvider.getScope(node).scope;
        if (scope instanceof ModelTransformationLambdaScope) {
            const lambdaTypeInference = scope.lambdaTypeInference;
            if (!Array.isArray(lambdaTypeInference)) {
                const expectedParamCount =
                    "type" in lambdaTypeInference
                        ? lambdaTypeInference.type.details.parameterTypes.length
                        : lambdaTypeInference.parameterTypes.length;

                if (node.parameterList.parameters.length > expectedParamCount) {
                    accept({
                        languageNode: node,
                        message: `Lambda has too many parameters. Expected ${expectedParamCount}, got ${node.parameterList.parameters.length}.`,
                        severity: "error"
                    });
                }
            }
        }
    }

    /**
     * Validates that the lambda return type is assignable to expected type.
     *
     * @param node The lambda expression node.
     * @param accept The validation problem acceptor.
     */
    private validateLambdaReturnType(
        node: LambdaExpressionType,
        accept: ValidationProblemAcceptor<TypirLangiumSpecifics>
    ): void {
        if (node.expression == undefined) {
            return;
        }

        const inferredLambdaType = this.inference.inferType(node);
        if (Array.isArray(inferredLambdaType) || !isCustomLambdaType(inferredLambdaType)) {
            return;
        }

        const expressionType = this.inference.inferType(node.expression);
        if (Array.isArray(expressionType)) {
            return;
        }

        const expectedReturnType = inferredLambdaType.details.returnType;
        if (!this.assignability.isAssignable(expressionType, expectedReturnType)) {
            accept({
                languageNode: node.expression,
                message: `Return type '${expressionType.getName()}' is not assignable to expected return type '${expectedReturnType.getName()}'.`,
                severity: "error"
            });
        }
    }

    /**
     * Registers the type inference rule for pattern variable declarations.
     * Infers the type based on the type annotation if present.
     */
    private registerPatternVariableInferenceRule(): void {
        this.registerInferenceRule(PatternVariable, (node) => {
            if (node.type != undefined) {
                return node.type;
            }
            return node.value;
        });
    }

    /**
     * Registers the validation rule for pattern variable declarations.
     * Validates that the value type is assignable to the declared type.
     */
    private registerPatternVariableValidationRule(): void {
        this.registerValidationRule(PatternVariable, (node, accept) => {
            if (node.value == undefined || node.type == undefined) {
                return;
            }

            const valueType = this.inference.inferType(node.value);
            const declaredType = this.inference.inferType(node.type);

            if (Array.isArray(valueType) || Array.isArray(declaredType)) {
                return;
            }

            if (!this.assignability.isAssignable(valueType, declaredType)) {
                accept({
                    languageNode: node,
                    message: `Value type '${valueType.getName()}' is not assignable to declared type '${declaredType.getName()}'.`,
                    severity: "error"
                });
            }
        });
    }

    /**
     * Registers the validation rule for pattern variable reassignments.
     * Validates that the assigned value's type is assignable to the type of the variable
     * being reassigned. The variable's type is the one it was declared with (or inferred from
     * its declaration), so a reassignment cannot change the variable's type.
     */
    private registerPatternVariableReassignmentValidationRule(): void {
        this.registerValidationRule(PatternVariableReassignment, (node, accept) => {
            const variableRef = node.variable?.ref;
            if (node.value == undefined || variableRef == undefined) {
                return;
            }

            const valueType = this.inference.inferType(node.value);
            const variableType = this.inference.inferType(variableRef);

            if (Array.isArray(valueType) || Array.isArray(variableType)) {
                return;
            }

            if (!this.assignability.isAssignable(valueType, variableType)) {
                accept({
                    languageNode: node,
                    message: `Value type '${valueType.getName()}' is not assignable to variable type '${variableType.getName()}'.`,
                    severity: "error"
                });
            }
        });
    }

    /**
     * Registers the validation rule for identifier expressions.
     * Prevents references to pattern object instances annotated with forbid/require.
     */
    private registerIdentifierExpressionValidationRule(): void {
        this.registerValidationRule(expressionTypes.identifierExpressionType, (node, accept) => {
            this.validateIdentifierExpressionTarget(node, accept);
        });
    }

    /**
     * Validates that identifier expressions do not target forbid/require pattern object instances.
     *
     * @param node The identifier expression node.
     * @param accept The validation problem acceptor.
     */
    private validateIdentifierExpressionTarget(
        node: IdentifierExpressionType,
        accept: ValidationProblemAcceptor<TypirLangiumSpecifics>
    ): void {
        const scope = this.typir.ScopeProvider.getScope(node);
        const entry = scope.getEntry(node.name);
        if (entry?.languageNode == undefined) {
            return;
        }

        if (!this.astReflection.isInstance(entry.languageNode, PatternObjectInstance)) {
            return;
        }

        const instance = entry.languageNode;
        const modifier = instance.modifier?.modifier;
        if (modifier === "forbid" || modifier === "require") {
            accept({
                languageNode: node,
                message: `Identifier '${node.name}' cannot reference a '${modifier}' instance in expressions.`,
                severity: "error"
            });
        }
    }

    /**
     * Registers the validation rule for pattern property assignments.
     * Validates type assignability for property assignments, with relaxed rules for collections.
     */
    private registerPatternPropertyAssignmentValidationRule(): void {
        this.registerValidationRule(PatternPropertyAssignment, (node, accept) => {
            this.validatePropertyAssignment(node, accept);
        });
    }

    /**
     * Validates a property assignment in a pattern.
     * Dispatches to the appropriate validation method based on property multiplicity.
     *
     * @param node The property assignment node.
     * @param accept The validation problem acceptor.
     */
    private validatePropertyAssignment(
        node: PatternPropertyAssignmentType,
        accept: ValidationProblemAcceptor<TypirLangiumSpecifics>
    ): void {
        const propertyRef = node.name?.ref;
        if (propertyRef == undefined || node.value == undefined) {
            return;
        }

        if (!this.astReflection.isInstance(propertyRef, Property)) {
            return;
        }

        const property = propertyRef as PropertyType;
        const valueType = this.inference.inferType(node.value);

        if (Array.isArray(valueType)) {
            return;
        }

        if (this.hasUpperMultiplicityGreaterThanOne(property.multiplicity)) {
            this.validateCollectionPropertyAssignment(node, valueType, accept);
        } else {
            this.validateSinglePropertyAssignment(node, property, valueType, accept);
        }
    }

    /**
     * Validates an assignment to a single-valued property.
     * Checks that the value type is assignable to the property type.
     *
     * @param node The property assignment node.
     * @param property The property being assigned.
     * @param valueType The inferred type of the assigned value.
     * @param accept The validation problem acceptor.
     */
    private validateSinglePropertyAssignment(
        node: PatternPropertyAssignmentType,
        property: PropertyType,
        valueType: Type,
        accept: ValidationProblemAcceptor<TypirLangiumSpecifics>
    ): void {
        const propertyType = this.inference.inferType(property.type);
        if (Array.isArray(propertyType)) {
            return;
        }

        if (!this.assignability.isAssignable(valueType, propertyType)) {
            accept({
                languageNode: node.value,
                message: `Value type '${valueType.getName()}' is not assignable to property type '${propertyType.getName()}'.`,
                severity: "error"
            });
        }
    }

    /**
     * Validates an assignment to a collection property (multiplicity > 1).
     * Uses relaxed validation, only checking that the value is a valid value type.
     *
     * @param node The property assignment node.
     * @param valueType The inferred type of the assigned value.
     * @param accept The validation problem acceptor.
     */
    private validateCollectionPropertyAssignment(
        node: PatternPropertyAssignmentType,
        valueType: Type,
        accept: ValidationProblemAcceptor<TypirLangiumSpecifics>
    ): void {
        if (!isCustomValueType(valueType)) {
            accept({
                languageNode: node.value,
                message: `Property value must be a value type.`,
                severity: "error"
            });
        }
    }

    /**
     * Checks if a multiplicity specification has an upper bound greater than one.
     *
     * @param multiplicity The multiplicity specification to check.
     * @returns True if the upper bound is greater than one or unlimited.
     */
    private hasUpperMultiplicityGreaterThanOne(multiplicity: MultiplicityType | undefined): boolean {
        if (multiplicity == undefined) {
            return false;
        }

        if (this.astReflection.isInstance(multiplicity, SingleMultiplicity)) {
            const value = multiplicity.value;
            if (value === "*" || value === "+") {
                return true;
            }
            const numericValue = multiplicity.numericValue;
            return numericValue != undefined && numericValue > 1;
        }

        if (this.astReflection.isInstance(multiplicity, RangeMultiplicity)) {
            if (multiplicity.upper === "*") {
                return true;
            }
            return multiplicity.upperNumeric != undefined && multiplicity.upperNumeric > 1;
        }

        return false;
    }

    /**
     * Registers validation rules for control flow statements.
     * Validates that conditions are boolean expressions.
     */
    private registerControlFlowValidationRules(): void {
        this.registerValidationRule(IfExpressionStatement, (node, accept) => {
            this.validateBooleanCondition(node.condition, accept, "If statement condition must be of type boolean.");
        });

        this.registerValidationRule(ElseIfBranch, (node, accept) => {
            this.validateBooleanCondition(node.condition, accept, "Else-if condition must be of type boolean.");
        });

        this.registerValidationRule(WhileExpressionStatement, (node, accept) => {
            this.validateBooleanCondition(node.condition, accept, "While statement condition must be of type boolean.");
        });
    }

    /**
     * Validates that an expression is of boolean type.
     *
     * @param condition The condition expression to validate.
     * @param accept The validation problem acceptor.
     * @param message The error message if validation fails.
     */
    private validateBooleanCondition(
        condition: BaseExpressionType,
        accept: ValidationProblemAcceptor<TypirLangiumSpecifics>,
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
                languageNode: condition,
                message,
                severity: "error"
            });
        }
    }

    /**
     * Registers the validation rule for where clauses.
     * Validates that the where expression is of boolean type.
     */
    private registerWhereClauseValidationRule(): void {
        this.registerValidationRule(WhereClause, (node, accept) => {
            this.validateBooleanCondition(node.expression, accept, "Where clause expression must be of type boolean.");
        });
    }

    /**
     * Registers the type inference rule for pattern object instances.
     * The type is inferred based on the class reference.
     */
    private registerPatternObjectInstanceInferenceRule(): void {
        this.registerInferenceRule(PatternObjectInstance, (node) => {
            const classRef = node.class?.ref;
            if (classRef == undefined) {
                return {
                    $problem: this.inferenceProblem,
                    languageNode: node,
                    location: "Object instance class reference is undefined.",
                    subProblems: []
                };
            }

            const classType = this.inference.inferType(classRef);
            if (Array.isArray(classType)) {
                return classType[0];
            }

            return classType;
        });
    }
}
