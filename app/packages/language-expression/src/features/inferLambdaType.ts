import type { InferenceProblem } from "typir";
import type { TypirLangiumSpecifics } from "typir-langium";
import type {
    CallExpressionType,
    ExpressionTypes,
    MemberCallExpressionType,
    NamedArgumentType
} from "../grammar/expressionTypes.js";
import type {
    AssignmentStatementType,
    StatementTypes,
    VariableDeclarationStatementType
} from "../grammar/statementTypes.js";
import type { CustomValueType } from "../typir-extensions/kinds/custom-value/custom-value-type.js";
import type { CustomVoidType } from "../typir-extensions/kinds/custom-void/custom-void-type.js";
import type { CustomFunctionType } from "../typir-extensions/kinds/custom-function/custom-function-type.js";
import { isCustomFunctionType } from "../typir-extensions/kinds/custom-function/custom-function-type.js";
import type { CustomLambdaType } from "../typir-extensions/kinds/custom-lambda/custom-lambda-type.js";
import { isCustomLambdaType } from "../typir-extensions/kinds/custom-lambda/custom-lambda-type.js";
import {
    GenericTypeRef,
    LambdaType,
    type ReturnType,
    type ValueType,
    VoidType
} from "../typir-extensions/config/type.js";
import type { ExpressionTypirServices } from "../type-system/services.js";
import { sharedImport } from "@mdeo/language-shared";
import { inferMethodAccess, inferPropertyAccess } from "../typir-extensions/rules/inferMemberAccess.js";

const { InferenceProblem: InferenceProblemConstant } = sharedImport("typir");

/**
 * Result of lambda type inference containing complete lambda type information.
 */
export interface LambdaTypeInferenceComplete {
    /**
     * The inferred lambda type
     */
    type: CustomLambdaType;
}

/**
 * Result of lambda type inference containing only parameter types.
 * Used when the return type cannot be fully determined yet.
 */
export interface LambdaTypeInferenceParametersOnly {
    /**
     * The inferred parameter types of the lambda
     */
    parameterTypes: CustomValueType[];
}

/**
 * Result of lambda type inference.
 * Can be either complete (with return type), parameters-only (without return type),
 * or an inference problem if inference is not possible.
 */
export type LambdaTypeInferenceResult<Specifics extends TypirLangiumSpecifics> =
    LambdaTypeInferenceComplete | LambdaTypeInferenceParametersOnly | InferenceProblem<Specifics>[];

/**
 * Infers the type of a lambda expression based on its context.
 *
 * This function examines the parent node of the lambda to determine its expected type:
 * - For assignments: uses the type of the assigned variable
 * - For variable declarations: uses the declared type
 * - For function/lambda call arguments: uses the parameter type from the function signature
 *
 * @template Specifics The language-specific type system configuration extending TypirLangiumSpecifics
 * @param lambdaNode The lambda expression AST node
 * @param services Extended Typir services for type inference and analysis
 * @param expressionTypes The expression type definitions
 * @param statementTypes The statement type definitions
 * @returns The inferred lambda type, or an inference problem if inference is not possible
 */
export function inferLambdaTypeFromContext<Specifics extends TypirLangiumSpecifics>(
    lambdaNode: Specifics["LanguageType"],
    services: ExpressionTypirServices<Specifics>,
    expressionTypes: ExpressionTypes,
    statementTypes: StatementTypes | undefined
): LambdaTypeInferenceResult<Specifics> {
    const astReflection = services.langium.LangiumServices.AstReflection;
    const container = lambdaNode.$container as Specifics["LanguageType"] | undefined;

    if (container == undefined) {
        return createInferenceProblem(services, lambdaNode, "Lambda expression has no container.");
    }

    if (statementTypes != undefined) {
        if (astReflection.isInstance(container, statementTypes.assignmentStatementType)) {
            return inferFromAssignment(lambdaNode, container, services);
        }

        if (astReflection.isInstance(container, statementTypes.variableDeclarationStatementType)) {
            return inferFromVariableDeclaration(lambdaNode, container, services);
        }
    }

    if (astReflection.isInstance(container, expressionTypes.callExpressionType)) {
        return inferFromCallExpression(lambdaNode, container, positionalArgument(lambdaNode), services);
    }

    if (astReflection.isInstance(container, expressionTypes.memberCallExpressionType)) {
        return inferFromMemberCallExpression(lambdaNode, container, positionalArgument(lambdaNode), services);
    }

    if (astReflection.isInstance(container, expressionTypes.namedArgumentType)) {
        const call = container.$container as Specifics["LanguageType"] | undefined;
        const argument = { name: (container as NamedArgumentType).name };
        if (call != undefined && astReflection.isInstance(call, expressionTypes.callExpressionType)) {
            return inferFromCallExpression(lambdaNode, call, argument, services);
        }
        if (call != undefined && astReflection.isInstance(call, expressionTypes.memberCallExpressionType)) {
            return inferFromMemberCallExpression(lambdaNode, call, argument, services);
        }
    }

    return createInferenceProblem(
        services,
        lambdaNode,
        "Lambda expression is in an unsupported context for type inference."
    );
}

/**
 * Where a lambda is passed in a call: by position, or by name.
 */
type ArgumentPlace = { index: number | undefined } | { name: string };

/**
 * Describes where a lambda that is a positional argument is passed.
 *
 * @param lambdaNode The lambda expression node
 * @returns The lambda's position in the argument list
 */
function positionalArgument(lambdaNode: { $containerIndex?: number }): ArgumentPlace {
    return { index: lambdaNode.$containerIndex };
}

/**
 * Infers lambda type from an assignment statement context.
 *
 * @param lambdaNode The lambda expression node
 * @param assignmentNode The assignment statement node
 * @param services Extended Typir services
 * @returns The inferred lambda type or an inference problem
 */
function inferFromAssignment<Specifics extends TypirLangiumSpecifics>(
    lambdaNode: Specifics["LanguageType"],
    assignmentNode: AssignmentStatementType,
    services: ExpressionTypirServices<Specifics>
): LambdaTypeInferenceResult<Specifics> {
    const leftType = services.Inference.inferType(assignmentNode.left);

    if (Array.isArray(leftType)) {
        return createInferenceProblem(services, lambdaNode, "Cannot infer type of assignment target.", leftType);
    }

    if (!isCustomLambdaType(leftType)) {
        return createInferenceProblem(
            services,
            lambdaNode,
            `Assignment target is not a lambda type, but '${leftType.getName()}'.`
        );
    }

    return {
        type: leftType
    };
}

/**
 * Infers lambda type from a variable declaration context.
 *
 * @param lambdaNode The lambda expression node
 * @param variableDeclarationNode The variable declaration statement node
 * @param services Extended Typir services
 * @returns The inferred lambda type or an inference problem
 */
function inferFromVariableDeclaration<Specifics extends TypirLangiumSpecifics>(
    lambdaNode: Specifics["LanguageType"],
    variableDeclarationNode: VariableDeclarationStatementType,
    services: ExpressionTypirServices<Specifics>
): LambdaTypeInferenceResult<Specifics> {
    const typeNode = variableDeclarationNode.type;

    if (typeNode == undefined) {
        return createInferenceProblem(services, lambdaNode, "Variable declaration has no explicit type annotation.");
    }

    const declaredType = services.Inference.inferType(typeNode);

    if (Array.isArray(declaredType)) {
        return createInferenceProblem(services, lambdaNode, "Cannot infer declared type of variable.", declaredType);
    }

    if (!isCustomLambdaType(declaredType)) {
        return createInferenceProblem(
            services,
            lambdaNode,
            `Declared type is not a lambda type, but '${declaredType.getName()}'.`
        );
    }

    return {
        type: declaredType
    };
}

/**
 * Infers lambda type from a call expression context (as an argument).
 *
 * @param lambdaNode The lambda expression node
 * @param callExpressionNode The call expression node
 * @param place Where the lambda is passed
 * @param services Extended Typir services
 * @returns The inferred lambda type or an inference problem
 */
function inferFromCallExpression<Specifics extends TypirLangiumSpecifics>(
    lambdaNode: Specifics["LanguageType"],
    callExpressionNode: CallExpressionType,
    place: ArgumentPlace,
    services: ExpressionTypirServices<Specifics>
): LambdaTypeInferenceResult<Specifics> {
    if ("index" in place && place.index == undefined) {
        return createInferenceProblem(services, lambdaNode, "Cannot determine argument position.");
    }

    const targetExpression = callExpressionNode.expression;
    const targetType = services.Inference.inferType(targetExpression);

    if (Array.isArray(targetType)) {
        return createInferenceProblem(services, lambdaNode, "Cannot infer type of call target.", targetType);
    }

    if (isCustomLambdaType(targetType)) {
        return inferFromLambdaTarget(lambdaNode, place, targetType, services);
    }

    if (isCustomFunctionType(targetType)) {
        return inferFromFunctionTarget(lambdaNode, place, targetType, services);
    }

    return createInferenceProblem(
        services,
        lambdaNode,
        `Call target is neither a function nor a lambda type, but '${targetType.getName()}'.`
    );
}

/**
 * Infers lambda type from a member call expression context (as an argument).
 *
 * @param lambdaNode The lambda expression node
 * @param memberCallExpressionNode The member call expression node
 * @param place Where the lambda is passed
 * @param services Extended Typir services
 * @return The inferred lambda type or an inference problem
 */
function inferFromMemberCallExpression<Specifics extends TypirLangiumSpecifics>(
    lambdaNode: Specifics["LanguageType"],
    memberCallExpressionNode: MemberCallExpressionType,
    place: ArgumentPlace,
    services: ExpressionTypirServices<Specifics>
): LambdaTypeInferenceResult<Specifics> {
    if ("index" in place && place.index == undefined) {
        return createInferenceProblem(services, lambdaNode, "Cannot determine argument position.");
    }

    const targetType = inferMethodAccess(
        memberCallExpressionNode,
        memberCallExpressionNode.expression,
        memberCallExpressionNode.member,
        services
    );

    if (isCustomFunctionType(targetType)) {
        return inferFromFunctionTarget(lambdaNode, place, targetType, services);
    }

    const propertyTargetType = inferPropertyAccess(
        memberCallExpressionNode,
        memberCallExpressionNode.expression,
        memberCallExpressionNode.member,
        services
    );

    if (isCustomLambdaType(propertyTargetType)) {
        return inferFromLambdaTarget(lambdaNode, place, propertyTargetType, services);
    }

    if (Array.isArray(propertyTargetType)) {
        return createInferenceProblem(services, lambdaNode, "Cannot infer type of call target");
    }

    return createInferenceProblem(
        services,
        lambdaNode,
        `Member call target is neither a function nor a lambda type, but '${propertyTargetType.getName()}'.`
    );
}

/**
 * Infers lambda type when the call target is a lambda.
 *
 * @param lambdaNode The lambda expression node
 * @param place Where the lambda is passed
 * @param targetType The lambda type being called
 * @param services Extended Typir services
 * @returns The inferred lambda type or an inference problem
 */
function inferFromLambdaTarget<Specifics extends TypirLangiumSpecifics>(
    lambdaNode: Specifics["LanguageType"],
    place: ArgumentPlace,
    targetType: CustomLambdaType,
    services: ExpressionTypirServices<Specifics>
): LambdaTypeInferenceResult<Specifics> {
    if (!("index" in place) || place.index == undefined) {
        return createInferenceProblem(services, lambdaNode, "Named arguments cannot be used when calling a lambda.");
    }
    const argumentIndex = place.index;
    if (argumentIndex >= targetType.details.parameterTypes.length) {
        return createInferenceProblem(
            services,
            lambdaNode,
            `Argument index ${argumentIndex} is out of bounds for lambda with ${targetType.details.parameterTypes.length} parameters.`
        );
    }

    const parameterType = targetType.details.parameterTypes[argumentIndex]!;

    if (!isCustomLambdaType(parameterType)) {
        return createInferenceProblem(
            services,
            lambdaNode,
            `Parameter at index ${argumentIndex} is not a lambda type, but '${parameterType.getName()}'.`
        );
    }

    return {
        type: parameterType
    };
}

/**
 * Infers lambda type when the call target is a function.
 *
 * This handles the complex case of function overloads and generic type parameters.
 *
 * @param lambdaNode The lambda expression node
 * @param place Where the lambda is passed
 * @param targetType The function type being called
 * @param services Extended Typir services
 * @returns The inferred lambda type or an inference problem
 */
function inferFromFunctionTarget<Specifics extends TypirLangiumSpecifics>(
    lambdaNode: Specifics["LanguageType"],
    place: ArgumentPlace,
    targetType: CustomFunctionType,
    services: ExpressionTypirServices<Specifics>
): LambdaTypeInferenceResult<Specifics> {
    const definition = targetType.details.definition;
    const candidateLambdaTypes: LambdaTypeInferenceResult<Specifics>[] = [];

    for (const signature of Object.values(definition.signatures)) {
        let paramIndex: number;
        if ("name" in place) {
            paramIndex = signature.parameters.findIndex((parameter) => parameter.name === place.name);
            if (paramIndex < 0 || signature.isVarArgs === true) {
                continue;
            }
        } else {
            const argumentIndex = place.index!;
            if (argumentIndex >= signature.parameters.length && !signature.isVarArgs) {
                continue;
            }
            paramIndex = Math.min(argumentIndex, signature.parameters.length - 1);
        }
        const parameterType = signature.parameters[paramIndex]!.type;

        if (LambdaType.is(parameterType)) {
            const lambdaTypeResult = resolveLambdaType(parameterType, targetType, signature.generics, services);
            candidateLambdaTypes.push(lambdaTypeResult);
        }
    }

    if (candidateLambdaTypes.length === 0) {
        return createInferenceProblem(
            services,
            lambdaNode,
            "name" in place
                ? `No function signature expects a lambda for parameter '${place.name}'.`
                : `No function signature expects a lambda at argument position ${place.index}.`
        );
    }

    return validateAndUnifyCandidateLambdaTypes(lambdaNode, candidateLambdaTypes, services);
}

/**
 * Validates and unifies candidate lambda types from multiple function signatures.
 *
 * @param lambdaNode The lambda expression node
 * @param candidateLambdaTypes All candidate lambda types from different signatures
 * @param services Extended Typir services
 * @returns The unified lambda type or an inference problem
 */
function validateAndUnifyCandidateLambdaTypes<Specifics extends TypirLangiumSpecifics>(
    lambdaNode: Specifics["LanguageType"],
    candidateLambdaTypes: LambdaTypeInferenceResult<Specifics>[],
    services: ExpressionTypirServices<Specifics>
): LambdaTypeInferenceResult<Specifics> {
    const inferenceProblem = candidateLambdaTypes.find((result) => Array.isArray(result));
    if (inferenceProblem !== undefined) {
        return inferenceProblem as InferenceProblem<Specifics>[];
    }

    const completeTypes = candidateLambdaTypes.filter((type): type is LambdaTypeInferenceComplete => "type" in type);
    const parameterOnlyTypes = candidateLambdaTypes.filter(
        (type): type is LambdaTypeInferenceParametersOnly => "parameterTypes" in type
    );

    if (parameterOnlyTypes.length === 0) {
        const firstType = completeTypes[0]!;
        const allTypesEqual = completeTypes.every((type) => services.Equality.areTypesEqual(type.type, firstType.type));

        if (!allTypesEqual) {
            return createInferenceProblem(
                services,
                lambdaNode,
                "Multiple function signatures expect different lambda types."
            );
        }

        return firstType;
    }

    return validateParameterTypes(completeTypes, parameterOnlyTypes, lambdaNode, services);
}

/**
 * Unifies parameter type lists from complete and parameter-only lambda candidates.
 *
 * Returns the common parameter types if all candidate signatures agree on both
 * the number of parameters and their respective types. If there is a mismatch
 * in counts or types, an inference problem array describing the conflict is
 * returned so the caller can propagate the error.
 *
 * @param completeTypes Candidate lambda types that include return type information.
 * @param parameterOnlyTypes Candidate lambda types that only provide parameter types.
 * @param lambdaNode The language AST node for the lambda, used for error location.
 * @param services Typir expression services used for equality checks and creating problems.
 */
function validateParameterTypes<Specifics extends TypirLangiumSpecifics>(
    completeTypes: LambdaTypeInferenceComplete[],
    parameterOnlyTypes: LambdaTypeInferenceParametersOnly[],
    lambdaNode: Specifics["LanguageType"],
    services: ExpressionTypirServices<Specifics>
): LambdaTypeInferenceParametersOnly | InferenceProblem<Specifics>[] {
    const allParamTypes = [
        ...completeTypes.map((type) => type.type.details.parameterTypes),
        ...parameterOnlyTypes.map((type) => type.parameterTypes)
    ];

    const firstParamTypes = allParamTypes[0]!;

    if (!allParamTypes.every((paramTypes) => paramTypes.length === firstParamTypes.length)) {
        return createInferenceProblem(
            services,
            lambdaNode,
            "Multiple function signatures expect different lambda parameter counts."
        );
    }

    const allParametersEqual = allParamTypes.every((paramTypes) =>
        paramTypes.every((param, idx) => services.Equality.areTypesEqual(param, firstParamTypes[idx]!))
    );

    if (!allParametersEqual) {
        return createInferenceProblem(
            services,
            lambdaNode,
            "Multiple function signatures expect different lambda parameter types."
        );
    }

    return {
        parameterTypes: firstParamTypes
    };
}

/**
 * Resolves a lambda type definition to concrete types.
 *
 * @param lambdaType The lambda type definition from the signature
 * @param functionType The function type containing generic type arguments
 * @param signatureGenerics The generic parameters defined by this signature
 * @param services Extended Typir services
 * @returns The resolved lambda type or an inference problem
 */
function resolveLambdaType<Specifics extends TypirLangiumSpecifics>(
    lambdaType: LambdaType,
    functionType: CustomFunctionType,
    signatureGenerics: string[] | undefined,
    services: ExpressionTypirServices<Specifics>
): LambdaTypeInferenceResult<Specifics> {
    const parameterTypes: CustomValueType[] = [];

    for (const param of lambdaType.parameters) {
        const resolvedParam = resolveValueType(param.type, functionType, signatureGenerics, services);
        if (Array.isArray(resolvedParam)) {
            return resolvedParam;
        }
        parameterTypes.push(resolvedParam);
    }

    const resolvedReturn = resolveReturnType(lambdaType.returnType, functionType, signatureGenerics, services);

    if (Array.isArray(resolvedReturn)) {
        return {
            parameterTypes
        };
    }

    return {
        type: services.TypeDefinitions.resolveCustomClassOrLambdaType(
            lambdaType,
            functionType.details.typeArgs
        ) as CustomLambdaType
    };
}

/**
 * Checks if a value type is fully defined (all generic parameters resolved).
 * A type is fully defined if it doesn't reference any signature-level generics
 * and all its generic parameters are available in the function's typeArgs.
 *
 * @param valueType The value type to check
 * @param functionType The function type containing generic type arguments
 * @param signatureGenerics The generic parameters defined by the signature
 * @returns true if the type is fully defined
 */
function isFullyDefined(
    valueType: ValueType,
    functionType: CustomFunctionType,
    signatureGenerics: string[] | undefined
): boolean {
    if (GenericTypeRef.is(valueType)) {
        if (signatureGenerics?.includes(valueType.generic)) {
            return false;
        }
        return functionType.details.typeArgs.has(valueType.generic);
    } else if (LambdaType.is(valueType)) {
        if (!isFullyDefinedReturnType(valueType.returnType, functionType, signatureGenerics)) {
            return false;
        }
        for (const param of valueType.parameters) {
            if (!isFullyDefined(param.type, functionType, signatureGenerics)) {
                return false;
            }
        }
        return true;
    } else {
        if (valueType.typeArgs != undefined) {
            for (const typeArg of Object.values(valueType.typeArgs)) {
                if (!isFullyDefined(typeArg, functionType, signatureGenerics)) {
                    return false;
                }
            }
        }
        return true;
    }
}

/**
 * Checks if a return type is fully defined.
 *
 * @param returnType The return type to check
 * @param functionType The function type containing generic type arguments
 * @param signatureGenerics The generic parameters defined by the signature
 * @returns true if the return type is fully defined
 */
function isFullyDefinedReturnType(
    returnType: ReturnType,
    functionType: CustomFunctionType,
    signatureGenerics: string[] | undefined
): boolean {
    if (VoidType.is(returnType)) {
        return true;
    }
    return isFullyDefined(returnType, functionType, signatureGenerics);
}

/**
 * Resolves a value type to a concrete CustomValueType.
 *
 * @param valueType The value type to resolve
 * @param functionType The function type containing generic type arguments
 * @param signatureGenerics The generic parameters defined by the signature
 * @param services Extended Typir services
 * @returns The resolved type or an inference problem
 */
function resolveValueType<Specifics extends TypirLangiumSpecifics>(
    valueType: ValueType,
    functionType: CustomFunctionType,
    signatureGenerics: string[] | undefined,
    services: ExpressionTypirServices<Specifics>
): CustomValueType | InferenceProblem<Specifics>[] {
    if (!isFullyDefined(valueType, functionType, signatureGenerics)) {
        return createInferenceProblem(services, undefined, `Type contains unresolved generic parameters.`);
    }

    return services.TypeDefinitions.resolveCustomClassOrLambdaType(valueType, functionType.details.typeArgs);
}

/**
 * Resolves a return type to a concrete type.
 *
 * @param returnType The return type to resolve
 * @param functionType The function type containing generic type arguments
 * @param signatureGenerics The generic parameters defined by the signature
 * @param services Extended Typir services
 * @returns The resolved type or an inference problem
 */
function resolveReturnType<Specifics extends TypirLangiumSpecifics>(
    returnType: ReturnType,
    functionType: CustomFunctionType,
    signatureGenerics: string[] | undefined,
    services: ExpressionTypirServices<Specifics>
): CustomValueType | CustomVoidType | InferenceProblem<Specifics>[] {
    if (VoidType.is(returnType)) {
        return services.factory.CustomVoid.getOrCreate();
    }

    return resolveValueType(returnType, functionType, signatureGenerics, services);
}

/**
 * Creates an inference problem result.
 *
 * @param services Extended Typir services
 * @param languageNode The AST node where the problem occurred
 * @param message The error message
 * @param subProblems Optional nested problems
 * @returns An array containing the inference problem
 */
function createInferenceProblem<Specifics extends TypirLangiumSpecifics>(
    services: ExpressionTypirServices<Specifics>,
    languageNode: Specifics["LanguageType"] | undefined,
    message: string,
    subProblems?: InferenceProblem<Specifics>[]
): InferenceProblem<Specifics>[] {
    return [
        {
            $problem: InferenceProblemConstant,
            languageNode,
            location: message,
            subProblems: subProblems ?? []
        } as InferenceProblem<Specifics>
    ];
}
