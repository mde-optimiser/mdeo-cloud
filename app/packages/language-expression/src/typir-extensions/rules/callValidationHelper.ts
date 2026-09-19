import type { InferenceProblem, Type, TypeAssignability, TypeEquality, TypirProblem, TypirSpecifics } from "typir";
import type { ExtendedTypirServices } from "../service/extendedTypirServices.js";
import type { CustomFunctionType } from "../kinds/custom-function/custom-function-type.js";
import { isCustomFunctionType } from "../kinds/custom-function/custom-function-type.js";
import type { CustomLambdaType } from "../kinds/custom-lambda/custom-lambda-type.js";
import { isCustomLambdaType } from "../kinds/custom-lambda/custom-lambda-type.js";
import type { CustomValueType } from "../kinds/custom-value/custom-value-type.js";
import { isCustomValueType } from "../kinds/custom-value/custom-value-type.js";
import type { CustomVoidType } from "../kinds/custom-void/custom-void-type.js";
import { isCustomVoidType } from "../kinds/custom-void/custom-void-type.js";
import { isCustomNullType } from "../kinds/custom-null/custom-null-type.js";
import { isCustomClassType } from "../kinds/custom-class/custom-class-type.js";
import type { FunctionSignature, ValueType, FunctionType, ReturnType } from "../config/type.js";
import { ClassTypeRef, GenericTypeRef, LambdaType, VoidType } from "../config/type.js";
import type { TypeDefinitionService } from "../service/typeDefinitionService.js";
import { findCommonParentType, findSuperTypeWithTypeArgs } from "./commonParentType.js";
import { getClassTypeIdentifier } from "./util.js";
import { assertUnreachable } from "@mdeo/language-common";
import { sharedImport } from "@mdeo/language-shared";

const { InferenceProblem: InferenceProblemConstant, isSubTypeEdge, isConversionEdge } = sharedImport("typir");

/**
 * A named argument of a call, `name = value`.
 *
 * @template Specifics Language-specific types extending TypirSpecifics
 */
export type NamedArgumentNode<Specifics extends TypirSpecifics> = Specifics["LanguageType"] & {
    /**
     * The name of the parameter the argument is passed to
     */
    name: string;
    /**
     * The argument expression
     */
    value: Specifics["LanguageType"];
};

/**
 * An argument of a call together with the parameter it is passed to.
 *
 * @template Specifics Language-specific types extending TypirSpecifics
 */
interface BoundArgument<Specifics extends TypirSpecifics> {
    /**
     * The argument expression
     */
    node: Specifics["LanguageType"];
    /**
     * The index of the parameter the argument is passed to. For a positional argument this is its
     * position, which may lie beyond the declared parameters for varargs or a surplus argument.
     */
    parameterIndex: number;
}

/**
 * Result of validating a function signature against provided arguments.
 *
 * @template TProblem The type of problems/errors to collect
 */
interface FunctionSignatureValidationResult<TProblem> {
    /**
     * The name of the signature being validated
     */
    signatureName: string;
    /**
     * The function signature being validated
     */
    signature: FunctionSignature;
    /**
     * List of validation errors found for this signature
     */
    errors: TProblem[];
    /**
     * The resolved return type, if it could be determined
     */
    returnType: CustomValueType | CustomVoidType | undefined;
    /**
     * The generic resolver used for this signature, needed for score calculation
     */
    genericResolver: GenericResolver<any>;
    /**
     * The arguments with the parameters they are passed to, positional ones first
     */
    arguments: BoundArgument<any>[];
    /**
     * How many parameters are left out and take their default value
     */
    defaultsUsed: number;
}

/**
 * Helper class for validating function and lambda calls.
 * Handles type inference, generic type resolution, and argument validation.
 *
 * @template Specifics Language-specific types extending TypirSpecifics
 * @template TProblem The type of validation problems to collect
 */
export abstract class CallValidationHelper<Specifics extends TypirSpecifics, TProblem> {
    /**
     * Cache for inferred generic argument types
     */
    private cachedGenericArgumentTypes: (CustomValueType | InferenceProblem<Specifics>[])[] | undefined = undefined;

    /**
     * Gets the inferred types for generic type arguments.
     * Lazily computes and caches the result.
     */
    get genericArgumentTypes(): (CustomValueType | InferenceProblem<Specifics>[])[] {
        if (this.cachedGenericArgumentTypes == undefined) {
            this.cachedGenericArgumentTypes = this.genericArgumentsNodes.map((node) => {
                const inferredType = this.services.Inference.inferType(node);
                if (isCustomValueType(inferredType)) {
                    return inferredType;
                } else if (Array.isArray(inferredType)) {
                    return inferredType;
                } else {
                    return [
                        {
                            $problem: InferenceProblemConstant,
                            languageNode: node,
                            location: `Type '${inferredType.getName()}' is not a valid generic argument.`,
                            subProblems: []
                        }
                    ];
                }
            });
        }
        return this.cachedGenericArgumentTypes;
    }

    /**
     * List of validation errors collected during validation
     */
    readonly errors: TProblem[] = [];

    /**
     * The inferred return type of the call, if it could be determined
     */
    inferredReturnType: CustomValueType | CustomVoidType | undefined = undefined;

    /**
     * The name of the chosen function overload.
     * undefined if the call was to a lambda (not a function) or if no overload was chosen.
     */
    chosenOverloadName: string | undefined = undefined;

    /**
     * The resolved parameter types for each argument position in the chosen signature.
     * For generic functions, these are the types after generic substitution.
     * For non-generic functions and lambdas, these are the declared parameter types.
     * Populated alongside {@link chosenOverloadName}.
     */
    resolvedParameterTypes: (CustomValueType | undefined)[] = [];

    /**
     * For every argument, positional ones first and then named ones, the index of the parameter it
     * is passed to in the chosen signature. Populated alongside {@link chosenOverloadName}.
     */
    argumentParameterIndices: number[] = [];

    /**
     * Creates a new call validation helper.
     * Automatically validates the call during construction.
     *
     * @param languageNode The AST node representing the entire call expression
     * @param functionType The type of the function (must be a function type or lambda type)
     * @param genericArgumentsNodes AST nodes for explicit generic type arguments
     * @param argumentNodes AST nodes for the positional call arguments
     * @param namedArgumentNodes AST nodes for the named call arguments
     * @param services Extended Typir services for type operations
     * @param isInferenceMode Whether this is used for type inference (vs validation)
     */
    constructor(
        private readonly languageNode: Specifics["LanguageType"],
        functionType: CustomFunctionType | CustomLambdaType,
        private readonly genericArgumentsNodes: Specifics["LanguageType"][],
        private readonly argumentNodes: Specifics["LanguageType"][],
        private readonly namedArgumentNodes: NamedArgumentNode<Specifics>[],
        readonly services: ExtendedTypirServices<Specifics>,
        private readonly isInferenceMode: boolean
    ) {
        if (isCustomFunctionType(functionType)) {
            this.validateFunction(functionType);
        } else {
            this.validateLambda(functionType);
        }
    }

    /**
     * Validates a call to a custom function type.
     * Handles overload resolution and generic type inference.
     *
     * @param type The function type being called
     */
    private validateFunction(type: CustomFunctionType) {
        const definition = type.details.definition;

        if (this.trySimpleFunctionInference(type, definition)) {
            return;
        }

        const signatureValidationResults = this.validateAllSignatures(type, definition);

        this.processFunctionSignatureResults(signatureValidationResults);
    }

    /**
     * Attempts a fast-path inference for simple single-signature non-generic functions.
     *
     * @param type The function type being called
     * @param definition The function definition
     * @returns true if inference succeeded and no further validation is needed
     */
    private trySimpleFunctionInference(type: CustomFunctionType, definition: FunctionType): boolean {
        const signatureEntries = Object.entries(definition.signatures);
        if (
            this.isInferenceMode &&
            signatureEntries.length === 1 &&
            (signatureEntries[0]![1].generics ?? []).length === 0
        ) {
            const [signatureName, signature] = signatureEntries[0]!;
            const genericResolver = new GenericResolver(
                type,
                signature,
                false,
                this.services,
                this.genericArgumentTypes
            );
            const resolvedReturnType = genericResolver.resolveType(signature.returnType);
            if (!Array.isArray(resolvedReturnType)) {
                this.inferredReturnType = resolvedReturnType;
                this.chosenOverloadName = signatureName;
                this.resolvedParameterTypes = this.resolveSignatureParameterTypes(signature, genericResolver);
                this.argumentParameterIndices = this.bindArguments(signature, []).arguments.map(
                    (argument) => argument.parameterIndex
                );
                return true;
            }
        }
        return false;
    }

    /**
     * Validates all signatures of a function against the provided arguments.
     *
     * @param type The function type being called
     * @param definition The function definition containing all signatures
     * @returns Array of validation results for each signature
     */
    private validateAllSignatures(
        type: CustomFunctionType,
        definition: FunctionType
    ): FunctionSignatureValidationResult<TProblem>[] {
        const signatureValidationResults: FunctionSignatureValidationResult<TProblem>[] = [];

        for (const [signatureName, signature] of Object.entries(definition.signatures)) {
            const result = this.validateSingleSignature(type, signature, signatureName);
            signatureValidationResults.push(result);
        }

        return signatureValidationResults;
    }

    /**
     * Validates a single function signature against the provided arguments.
     *
     * @param type The function type being called
     * @param signature The specific signature to validate
     * @param signatureName The name of the signature
     * @returns Validation result including errors and return type
     */
    private validateSingleSignature(
        type: CustomFunctionType,
        signature: FunctionSignature,
        signatureName: string
    ): FunctionSignatureValidationResult<TProblem> {
        const genericResolver = new GenericResolver(
            type,
            signature,
            !this.isInferenceMode,
            this.services,
            this.genericArgumentTypes
        );
        const errors: TProblem[] = [];

        const binding = this.bindArguments(signature, errors);
        const args = binding.arguments.map((argument) => this.services.Inference.inferType(argument.node));
        this.validateSignatureArguments(args, binding.arguments, signature, genericResolver, errors);

        const resolvedReturnType = genericResolver.resolveType(signature.returnType);
        if (Array.isArray(resolvedReturnType)) {
            for (const problem of resolvedReturnType) {
                errors.push(this.createError(this.languageNode, `Return type could not be resolved: ${problem}`));
            }
        }

        return {
            signatureName,
            signature,
            errors,
            returnType: Array.isArray(resolvedReturnType) ? undefined : resolvedReturnType,
            genericResolver,
            arguments: binding.arguments,
            defaultsUsed: binding.defaultsUsed
        };
    }

    /**
     * Binds the arguments of the call to the parameters of a signature.
     *
     * Positional arguments bind by position, named arguments by name. Reports a named argument
     * that names no parameter, a parameter that already has a value, or the variable arguments of
     * a varargs signature, and parameters without a default that receive no value.
     *
     * @param signature The signature to bind to
     * @param errors Array to collect binding errors
     * @returns The bound arguments, positional ones first, and how many parameters take their default
     */
    private bindArguments(
        signature: FunctionSignature,
        errors: TProblem[]
    ): { arguments: BoundArgument<Specifics>[]; defaultsUsed: number } {
        const bound: BoundArgument<Specifics>[] = this.argumentNodes.map((node, index) => ({
            node,
            parameterIndex: index
        }));
        const parameters = signature.parameters;
        const isVarArgs = signature.isVarArgs === true;
        const assigned = new Set<number>();
        for (let i = 0; i < Math.min(this.argumentNodes.length, parameters.length); i++) {
            assigned.add(i);
        }

        for (const namedArgument of this.namedArgumentNodes) {
            const index = parameters.findIndex((parameter) => parameter.name === namedArgument.name);
            if (index < 0) {
                errors.push(this.createError(namedArgument, `No parameter named '${namedArgument.name}'.`));
                continue;
            }
            if (isVarArgs && index === parameters.length - 1) {
                // As in Kotlin, the variable arguments are passed by position only.
                errors.push(
                    this.createError(
                        namedArgument,
                        `The variable arguments '${namedArgument.name}' cannot be passed by name.`
                    )
                );
                continue;
            }
            if (assigned.has(index)) {
                errors.push(this.createError(namedArgument, `Parameter '${namedArgument.name}' already has a value.`));
                continue;
            }
            assigned.add(index);
            bound.push({ node: namedArgument.value, parameterIndex: index });
        }

        let defaultsUsed = 0;
        const requiredParameterCount = parameters.length - (isVarArgs ? 1 : 0);
        for (let i = 0; i < requiredParameterCount; i++) {
            if (assigned.has(i)) {
                continue;
            }
            if (parameters[i]!.hasDefault === true) {
                defaultsUsed++;
            } else {
                errors.push(
                    this.createError(this.languageNode, `No value passed for parameter '${parameters[i]!.name}'.`)
                );
            }
        }
        return { arguments: bound, defaultsUsed };
    }

    /**
     * Calculates a score for how well the arguments match the signature.
     * Lower scores are better matches.
     * - Exact type matches have cost 0
     * - Subtype relationships have cost 1 per edge
     * - Conversions have cost 2 per edge
     *
     * @param result The validation result of the signature, with its bound arguments
     * @returns The total cost/score for this signature match
     */
    private calculateSignatureScore(result: FunctionSignatureValidationResult<TProblem>): number {
        const { signature, genericResolver } = result;
        let totalScore = 0;

        for (const argument of result.arguments) {
            const argType = this.services.Inference.inferType(argument.node);
            if (Array.isArray(argType) || !isCustomValueType(argType)) {
                continue;
            }

            if (argument.parameterIndex >= signature.parameters.length) {
                if (signature.isVarArgs !== true) {
                    continue;
                }
            }

            const paramIndex = Math.min(argument.parameterIndex, signature.parameters.length - 1);
            const paramType = signature.parameters[paramIndex]!.type;
            const declaredType = genericResolver.resolveType(paramType);
            if (Array.isArray(declaredType)) {
                continue;
            }

            const assignabilityResult = this.services.Assignability.getAssignabilityResult(argType, declaredType);
            if (assignabilityResult.result) {
                for (const edge of assignabilityResult.path) {
                    if (isSubTypeEdge(edge)) {
                        totalScore += 1;
                    } else if (isConversionEdge(edge)) {
                        totalScore += 2;
                    } else {
                        assertUnreachable(edge);
                    }
                }
            }
        }

        return totalScore;
    }

    /**
     * Validates that all provided arguments match the signature's parameters.
     *
     * @param args The inferred types of the arguments
     * @param boundArguments The arguments with the parameters they are passed to, in the order of args
     * @param signature The signature being validated
     * @param genericResolver The generic type resolver
     * @param errors Array to collect validation errors
     */
    private validateSignatureArguments(
        args: (Type | InferenceProblem<Specifics>[])[],
        boundArguments: BoundArgument<Specifics>[],
        signature: FunctionSignature,
        genericResolver: GenericResolver<Specifics>,
        errors: TProblem[]
    ): void {
        for (let i = 0; i < args.length; i++) {
            const argType = args[i];
            const { node, parameterIndex } = boundArguments[i]!;
            if (Array.isArray(argType)) {
                errors.push(this.createError(node, `Argument type could not be determined.`, argType));
            } else if (!isCustomValueType(argType)) {
                errors.push(this.createError(node, `Argument type is not a valid type.`));
            } else if (parameterIndex >= signature.parameters.length && signature.isVarArgs !== true) {
                errors.push(this.createError(node, `Too many arguments provided for function call.`));
            } else if (!genericResolver.checkAndUpdateArgumentType(parameterIndex, argType)) {
                errors.push(
                    this.createError(
                        node,
                        `Argument type '${argType.getName()}' is not compatible with parameter type.`
                    )
                );
            }
        }
    }

    /**
     * Processes signature validation results to determine the best match.
     * Sets either the inferred return type or validation errors.
     *
     * @param signatureValidationResults All signature validation results
     */
    private processFunctionSignatureResults(
        signatureValidationResults: FunctionSignatureValidationResult<TProblem>[]
    ): void {
        const validResults = signatureValidationResults.filter((result) => result.errors.length === 0);

        if (validResults.length === 0) {
            this.handleNoValidSignatures(signatureValidationResults);
            return;
        }

        if (validResults.length === 1) {
            this.choose(validResults[0]!);
            return;
        }

        this.handleMultipleValidSignatures(validResults);
    }

    /**
     * Handles the case when there are no valid signatures.
     * Finds the best matching signatures (with fewest errors) and reports errors.
     *
     * @param signatureValidationResults All signature validation results
     */
    private handleNoValidSignatures(signatureValidationResults: FunctionSignatureValidationResult<TProblem>[]): void {
        signatureValidationResults.sort((a, b) => a.errors.length - b.errors.length);

        const bestResults = signatureValidationResults.filter(
            (result) => result.errors.length === signatureValidationResults[0]!.errors.length
        );

        const subErrors: TypirProblem[] = [];
        for (const result of bestResults) {
            subErrors.push(...(result.errors as TypirProblem[]));
        }

        this.errors.push(this.createError(this.languageNode, `No valid signature found for function call.`, subErrors));

        this.tryInferReturnTypeFromBestResults(bestResults);
    }

    /**
     * Handles the case when there are multiple valid signatures.
     * Uses scoring to find the best match(es).
     *
     * @param validResults All valid signature results
     */
    private handleMultipleValidSignatures(validResults: FunctionSignatureValidationResult<TProblem>[]): void {
        const resultsWithScores = validResults.map((result) => ({
            result,
            score: this.calculateSignatureScore(result)
        }));

        // Cheaper conversions win; between equally cheap signatures, the one that leaves fewer
        // parameters to their defaults does.
        resultsWithScores.sort((a, b) => a.score - b.score || a.result.defaultsUsed - b.result.defaultsUsed);

        const best = resultsWithScores[0]!;
        const bestScoringResults = resultsWithScores
            .filter((item) => item.score === best.score && item.result.defaultsUsed === best.result.defaultsUsed)
            .map((item) => item.result);

        if (bestScoringResults.length === 1) {
            this.choose(bestScoringResults[0]!);
            return;
        }

        this.reportAmbiguousSignatures();
        this.tryInferReturnTypeFromBestResults(bestScoringResults);
    }

    /**
     * Records a signature as the one the call resolves to.
     *
     * @param result The validation result of the chosen signature
     */
    private choose(result: FunctionSignatureValidationResult<TProblem>): void {
        this.inferredReturnType = result.returnType;
        this.chosenOverloadName = result.signatureName;
        this.resolvedParameterTypes = this.resolveSignatureParameterTypes(result.signature, result.genericResolver);
        this.argumentParameterIndices = result.arguments.map((argument) => argument.parameterIndex);
    }

    /**
     * Reports an error for ambiguous function calls with multiple best-matching signatures.
     */
    private reportAmbiguousSignatures(): void {
        this.errors.push(
            this.createError(this.languageNode, `Ambiguous function call: multiple valid signatures found.`)
        );
    }

    /**
     * Attempts to infer the return type from the best matching signatures,
     * even if they have errors, if all have the same return type.
     *
     * @param bestResults Signatures with the fewest errors
     */
    private tryInferReturnTypeFromBestResults(bestResults: FunctionSignatureValidationResult<TProblem>[]): void {
        const allHaveReturnType = bestResults.every((result) => result.returnType !== undefined);
        if (!allHaveReturnType) {
            return;
        }
        const allHaveSameReturnType = bestResults.every((result) =>
            this.services.Equality.areTypesEqual(result.returnType!, bestResults[0]!.returnType!)
        );
        if (allHaveSameReturnType) {
            this.inferredReturnType = bestResults[0]!.returnType;
        }
    }

    /**
     * Validates a call to a lambda type.
     *
     * @param type The lambda type being called
     */
    private validateLambda(type: CustomLambdaType) {
        this.inferredReturnType = type.details.returnType ?? undefined;
        this.resolvedParameterTypes = type.details.parameterTypes.filter(isCustomValueType);

        const args = this.argumentNodes.map((argNode) => this.services.Inference.inferType(argNode));
        this.argumentParameterIndices = args.map((_, index) => index);

        for (const namedArgument of this.namedArgumentNodes) {
            this.errors.push(this.createError(namedArgument, `Named arguments cannot be used when calling a lambda.`));
        }

        for (let i = 0; i < args.length; i++) {
            const argType = args[i];
            if (Array.isArray(argType)) {
                this.errors.push(
                    this.createError(this.argumentNodes[i], `Argument type could not be determined.`, argType)
                );
            } else if (!isCustomValueType(argType)) {
                this.errors.push(this.createError(this.argumentNodes[i], `Argument type is not a valid type.`));
            } else if (i >= type.details.parameterTypes.length) {
                this.errors.push(
                    this.createError(this.argumentNodes[i], `Too many arguments provided for lambda call.`)
                );
            } else {
                const paramType = type.details.parameterTypes[i]!;
                const isAssignable = this.services.Assignability.isAssignable(argType, paramType);
                if (!isAssignable) {
                    this.errors.push(
                        this.createError(
                            this.argumentNodes[i],
                            `Argument type '${argType.getName()}' is not compatible with parameter type '${paramType.getName()}'.`
                        )
                    );
                }
            }
        }

        const requiredParamCount = type.details.parameterTypes.length;
        if (args.length < requiredParamCount) {
            this.errors.push(
                this.createError(
                    this.languageNode,
                    `Not enough arguments provided for lambda call. Expected ${requiredParamCount} but got ${args.length}.`
                )
            );
        }
    }

    /**
     * Resolves the parameter types of a function signature using the generic resolver.
     * For varargs signatures, the last parameter type is used for all extra arguments.
     *
     * @param signature The function signature whose parameters to resolve
     * @param genericResolver The generic resolver with the resolved type mappings
     * @returns Array of resolved parameter types (one per declared parameter)
     */
    private resolveSignatureParameterTypes(
        signature: FunctionSignature,
        genericResolver: GenericResolver<Specifics>
    ): (CustomValueType | undefined)[] {
        return signature.parameters.map((param) => {
            const resolved = genericResolver.resolveType(param.type);
            return Array.isArray(resolved) ? undefined : resolved;
        });
    }

    /**
     * Creates an error/problem object for a validation failure.
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
}

/**
 * States for generic type resolution during call validation.
 */
/**
 * States for generic type resolution during call validation.
 */
enum TypeResolutionState {
    /**
     * No type known yet
     */
    Undefined,
    /**
     * Known to be nullable, but nothing else yet
     */
    Nullable,
    /**
     * Defined, but still generalizable
     */
    Defined,
    /**
     * Defined and cannot be changed anymore
     */
    DefinedInvariant,
    /**
     * Defined via generic argument or from parent type, cannot be changed
     */
    Fixed,
    /**
     * Has a conflict
     */
    Conflict
}

/**
 * Resolves generic type parameters during function call validation.
 * Manages type inference, variance checking, and type compatibility.
 *
 * @template Specifics Language-specific types extending TypirSpecifics
 */
export class GenericResolver<Specifics extends TypirSpecifics> {
    /**
     * Map of generic type names to their resolved types for this call
     */
    private readonly callResolvedGenericArguments = new Map<string, CustomValueType>();

    /**
     * Map of generic type names to their resolution states
     */
    private readonly callResolvedGenericArgumentStates = new Map<string, TypeResolutionState>();

    private readonly typeDefinitionsService: TypeDefinitionService;
    private readonly assignabilityService: TypeAssignability;
    private readonly equalityService: TypeEquality;

    /**
     * Creates a new generic resolver for a specific function call.
     *
     * @param functionType The function type being called
     * @param signature The specific signature being validated
     * @param strict Whether to perform strict validation
     * @param services The extended Typir services
     * @param genericArgumentTypes The explicit generic argument types provided in the call
     */
    constructor(
        readonly functionType: CustomFunctionType,
        readonly signature: FunctionSignature,
        readonly strict: boolean,
        readonly services: ExtendedTypirServices<Specifics>,
        genericArgumentTypes: (CustomValueType | InferenceProblem<Specifics>[])[]
    ) {
        this.typeDefinitionsService = services.TypeDefinitions;
        this.assignabilityService = services.Assignability;
        this.equalityService = services.Equality;
        for (const [name, type] of this.functionType.details.typeArgs) {
            this.callResolvedGenericArguments.set(name, type);
            this.callResolvedGenericArgumentStates.set(name, TypeResolutionState.Fixed);
        }
        if (this.signature.generics != undefined && genericArgumentTypes.length > 0) {
            for (const generic of this.signature.generics ?? []) {
                this.callResolvedGenericArgumentStates.set(generic, TypeResolutionState.Conflict);
            }
            if (!strict || genericArgumentTypes.length === this.signature.generics.length) {
                for (let i = 0; i < Math.min(genericArgumentTypes.length, this.signature.generics.length); i++) {
                    const type = genericArgumentTypes[i]!;
                    if (Array.isArray(type)) {
                        continue;
                    }
                    this.callResolvedGenericArguments.set(this.signature.generics![i]!, type);
                    this.callResolvedGenericArgumentStates.set(this.signature.generics![i]!, TypeResolutionState.Fixed);
                }
            }
        } else {
            for (const generic of this.signature.generics ?? []) {
                this.callResolvedGenericArgumentStates.set(generic, TypeResolutionState.Undefined);
            }
        }
    }

    /**
     * Checks if a value type is fully defined (all generic parameters resolved).
     *
     * @param type The value type to check
     * @returns true if all generic parameters are resolved
     */
    isFullyDefined(type: ReturnType): boolean {
        return this.getResolutionProblems(type).length === 0;
    }

    /**
     * Returns a list of problems explaining why a type is not fully resolved.
     * An empty list means the type is fully defined and can be resolved.
     *
     * @param type The type to check
     * @returns A list of {@link TypeResolutionProblem}s, or an empty array if the type is fully defined
     */
    private getResolutionProblems(type: ValueType | ReturnType): string[] {
        if (VoidType.is(type)) {
            return [];
        } else if (GenericTypeRef.is(type)) {
            const localStatus = this.callResolvedGenericArgumentStates.get(type.generic);
            if (localStatus == undefined) {
                return [`Generic type '${type.generic}' is not defined in this scope.`];
            }
            switch (localStatus) {
                case TypeResolutionState.Undefined:
                    return [`Generic type '${type.generic}' could not be inferred from the provided arguments.`];
                case TypeResolutionState.Conflict:
                    return [
                        `Generic type '${type.generic}' has conflicting type constraints from different arguments.`
                    ];
                case TypeResolutionState.Nullable:
                    return [
                        `Generic type '${type.generic}' is only known to be nullable but its base type could not be determined.`
                    ];
                default:
                    return [];
            }
        } else if (ClassTypeRef.is(type)) {
            const problems: string[] = [];
            for (const [argName, typeArgType] of Object.entries(type.typeArgs ?? {})) {
                for (const problem of this.getResolutionProblems(typeArgType)) {
                    problems.push(`In type argument '${argName}': ${problem}`);
                }
            }
            return problems;
        } else if (LambdaType.is(type)) {
            const problems: string[] = [];
            for (const problem of this.getResolutionProblems(type.returnType)) {
                problems.push(`In return type: ${problem}`);
            }
            for (const param of type.parameters) {
                for (const problem of this.getResolutionProblems(param.type)) {
                    problems.push(`In parameter '${param.name}': ${problem}`);
                }
            }
            return problems;
        } else {
            assertUnreachable(type);
        }
    }

    /**
     * Resolves a value type to a concrete CustomValueType using the current generic mappings.
     * Returns a non-empty array of strings if the type cannot be resolved.
     *
     * @param type The value type to resolve
     * @returns The resolved concrete type, or an array of problems explaining why resolution failed
     */
    resolveType(type: ValueType): CustomValueType | string[];
    resolveType(type: ReturnType): CustomValueType | CustomVoidType | string[];
    resolveType(type: ValueType | ReturnType): CustomValueType | CustomVoidType | string[] {
        if (VoidType.is(type)) {
            return this.services.factory.CustomVoid.getOrCreate();
        }
        const problems = this.getResolutionProblems(type);
        if (problems.length > 0) {
            return problems;
        }
        return this.typeDefinitionsService.resolveCustomClassOrLambdaType(
            type,
            new Map([...this.callResolvedGenericArguments.entries()])
        );
    }

    /**
     * Checks and updates the type for a specific argument index.
     *
     * @param index The argument index
     * @param type The actual type of the argument
     * @returns true if the argument type is compatible
     */
    checkAndUpdateArgumentType(index: number, type: CustomValueType): boolean {
        if (index < 0 || (index >= this.signature.parameters.length && this.signature.isVarArgs !== true)) {
            return false;
        }
        const paramType = this.signature.parameters[Math.min(index, this.signature.parameters.length - 1)]!.type;
        return this.checkAndUpdateType(paramType, type, true);
    }

    /**
     * Checks if an actual type matches a declared type and updates generic type resolution.
     * Dispatches to specific handlers based on the declared type's structure.
     *
     * @param declaredType The expected type (from the signature)
     * @param actualType The actual type being checked
     * @param allowSubtypes Whether subtypes are acceptable (covariance)
     * @returns true if the types are compatible
     */
    private checkAndUpdateType(declaredType: ValueType, actualType: CustomValueType, allowSubtypes: boolean): boolean {
        if (!allowSubtypes && isCustomNullType(actualType)) {
            throw new Error("Null type cannot be used in invariant position.");
        }
        if (GenericTypeRef.is(declaredType)) {
            return this.checkAndUpdateGenericType(declaredType, actualType, allowSubtypes);
        } else if (ClassTypeRef.is(declaredType)) {
            return this.checkAndUpdateClassType(declaredType, actualType, allowSubtypes);
        } else {
            return this.checkAndUpdateLambdaType(declaredType, actualType);
        }
    }

    /**
     * Checks and updates a class type, including type arguments.
     *
     * @param declaredType The expected class type
     * @param actualType The actual type being checked
     * @param allowSubtypes Whether to allow subtype relationships
     * @returns true if the types are compatible
     */
    private checkAndUpdateClassType(
        declaredType: ClassTypeRef,
        actualType: CustomValueType,
        allowSubtypes: boolean
    ): boolean {
        const declaredTypeDefinition = this.typeDefinitionsService.getClassType(
            declaredType.type,
            declaredType.package
        );
        if (actualType.isNullable && !declaredType.isNullable) {
            return false;
        }
        if (isCustomNullType(actualType)) {
            return declaredType.isNullable === true;
        }
        if (!isCustomClassType(actualType)) {
            return false;
        }

        let isAllowed: boolean;
        let actualTypeArgs: Map<string, CustomValueType> | undefined;

        if (!allowSubtypes) {
            isAllowed =
                getClassTypeIdentifier(actualType.details.definition) ===
                getClassTypeIdentifier(declaredTypeDefinition);
            actualTypeArgs = actualType.details.typeArgs;
        } else {
            const result = findSuperTypeWithTypeArgs(actualType, declaredTypeDefinition);
            isAllowed = result != undefined;
            actualTypeArgs = result?.typeArgs;
        }

        if (!isAllowed) {
            return false;
        }

        let isArgsValid = true;
        for (const [typeArgName, typeArgType] of Object.entries(declaredType.typeArgs ?? {})) {
            isArgsValid &&= this.checkAndUpdateType(typeArgType, actualTypeArgs!.get(typeArgName)!, false);
        }
        return isArgsValid;
    }

    /**
     * Checks and updates a lambda type, including parameter and return types.
     *
     * @param declaredType The expected lambda type
     * @param actualType The actual type being checked
     * @returns true if the types are compatible
     */
    private checkAndUpdateLambdaType(declaredType: LambdaType, actualType: CustomValueType): boolean {
        if (isCustomNullType(actualType)) {
            return declaredType.isNullable === true;
        }
        if (!isCustomLambdaType(actualType)) {
            return false;
        }
        if (actualType.isNullable && !declaredType.isNullable) {
            return false;
        }

        const declaredReturnType = declaredType.returnType;
        const actualReturnType = actualType.details.returnType;

        let returnTypeValid: boolean;
        if (VoidType.is(declaredReturnType)) {
            returnTypeValid = isCustomVoidType(actualReturnType);
        } else if (isCustomVoidType(actualReturnType)) {
            returnTypeValid = VoidType.is(declaredReturnType);
        } else {
            returnTypeValid = this.checkAndUpdateType(
                declaredReturnType as ValueType,
                actualReturnType as CustomValueType,
                false
            );
        }

        let paramsValid = true;
        for (let i = 0; i < Math.min(declaredType.parameters.length, actualType.details.parameterTypes.length); i++) {
            paramsValid &&= this.checkAndUpdateType(
                declaredType.parameters[i]!.type,
                actualType.details.parameterTypes[i]!,
                false
            );
        }

        return (
            returnTypeValid &&
            paramsValid &&
            declaredType.parameters.length === actualType.details.parameterTypes.length
        );
    }

    /**
     * Checks and updates a generic type parameter resolution.
     * Handles various states: undefined, defined, fixed, and conflict.
     * Supports both covariant (allowSubtypes=true) and invariant (allowSubtypes=false) checking.
     *
     * @param genericType The generic type reference
     * @param actualType The actual type being matched
     * @param allowSubtypes Whether to allow covariant matching
     * @returns true if the types are compatible
     */
    private checkAndUpdateGenericType(
        genericType: GenericTypeRef,
        actualType: CustomValueType,
        allowSubtypes: boolean
    ): boolean {
        const genericName = genericType.generic;
        const isNullable = genericType.isNullable === true;
        const state = this.callResolvedGenericArgumentStates.get(genericName);

        if (state === undefined) {
            return false;
        }

        if (state === TypeResolutionState.Conflict) {
            return false;
        }

        if (state === TypeResolutionState.Undefined) {
            return this.handleUndefinedGenericState(genericName, actualType, isNullable, allowSubtypes);
        }

        if (state === TypeResolutionState.Nullable) {
            return this.handleNullableGenericState(genericName, actualType, allowSubtypes);
        }

        return this.handleDefinedGenericState(genericName, actualType, isNullable, allowSubtypes, state);
    }

    /**
     * Handles the case when a generic type parameter is undefined.
     * Sets the initial type and state for the generic parameter.
     *
     * @param genericName The name of the generic parameter
     * @param actualType The actual type being assigned
     * @param isNullable Whether the generic reference is nullable
     * @param allowSubtypes Whether to allow covariant matching
     * @returns true (always succeeds)
     */
    private handleUndefinedGenericState(
        genericName: string,
        actualType: CustomValueType,
        isNullable: boolean,
        allowSubtypes: boolean
    ): boolean {
        if (isCustomNullType(actualType)) {
            if (isNullable) {
                return true;
            }
            this.callResolvedGenericArgumentStates.set(genericName, TypeResolutionState.Nullable);
            return true;
        }
        this.callResolvedGenericArguments.set(genericName, isNullable ? actualType.asNonNullable : actualType);
        this.callResolvedGenericArgumentStates.set(
            genericName,
            allowSubtypes ? TypeResolutionState.Defined : TypeResolutionState.DefinedInvariant
        );
        return true;
    }

    /**
     * Handles the case when a ganaric type parameter is known to be nullable, but not further defined.
     *
     * @param genericName The name of the generic parameter
     * @param actualType The actual type being assigned
     * @param allowSubtypes Whether to allow covariant matching
     * @returns true if compatible
     */
    private handleNullableGenericState(
        genericName: string,
        actualType: CustomValueType,
        allowSubtypes: boolean
    ): boolean {
        if (isCustomNullType(actualType)) {
            return true;
        }
        if (!allowSubtypes && !actualType.isNullable) {
            this.callResolvedGenericArgumentStates.set(genericName, TypeResolutionState.Conflict);
            return false;
        }
        this.callResolvedGenericArgumentStates.set(
            genericName,
            allowSubtypes ? TypeResolutionState.Defined : TypeResolutionState.DefinedInvariant
        );
        this.callResolvedGenericArguments.set(genericName, actualType.asNullable);
        return true;
    }

    /**
     * Handles the case when a generic type parameter is already defined.
     * This includes states Defined, DefinedInvariant, and Fixed.
     * Checks compatibility and potentially updates the generic type resolution.
     *
     * @param genericName The name of the generic parameter
     * @param actualType The actual type being checked
     * @param isNullable Whether the generic reference is nullable
     * @param allowSubtypes Whether to allow covariant matching
     * @param state The current resolution state
     * @returns true if the types are compatible
     */
    private handleDefinedGenericState(
        genericName: string,
        actualType: CustomValueType,
        isNullable: boolean,
        allowSubtypes: boolean,
        state: TypeResolutionState
    ): boolean {
        const current = this.callResolvedGenericArguments.get(genericName)!;
        const normalizedActualType = current.isNullable && isNullable ? actualType.asNonNullable : actualType;

        const isEqual = this.equalityService.areTypesEqual(current, normalizedActualType);
        if (isEqual) {
            if (!allowSubtypes && state === TypeResolutionState.Defined) {
                this.callResolvedGenericArgumentStates.set(genericName, TypeResolutionState.DefinedInvariant);
            }
            return true;
        }

        if (state === TypeResolutionState.DefinedInvariant) {
            return this.handleDefinedInvariantGenericState(genericName, actualType, isNullable, allowSubtypes, current);
        }

        if (state === TypeResolutionState.Fixed) {
            return this.handleFixedGenericState(actualType, isNullable, allowSubtypes, current);
        }

        return this.handleDefinedCovariantState(genericName, actualType, isNullable, allowSubtypes, current);
    }

    /**
     * Handles the DefinedInvariant state where the generic type was previously resolved invariantly.
     * In covariant mode, checks if the new type is assignable to the current type.
     * In invariant mode, marks as conflict.
     *
     * @param genericName The name of the generic parameter
     * @param actualType The actual type being checked
     * @param isNullable Whether the generic reference is nullable
     * @param allowSubtypes Whether to allow covariant matching
     * @param current The currently resolved type
     * @returns true if compatible
     */
    private handleDefinedInvariantGenericState(
        genericName: string,
        actualType: CustomValueType,
        isNullable: boolean,
        allowSubtypes: boolean,
        current: CustomValueType
    ): boolean {
        if (!allowSubtypes) {
            this.callResolvedGenericArgumentStates.set(genericName, TypeResolutionState.Conflict);
            return false;
        }

        if (isCustomNullType(actualType)) {
            if (!(isNullable || current.isNullable)) {
                this.callResolvedGenericArgumentStates.set(genericName, TypeResolutionState.Conflict);
                return false;
            }
            return true;
        }

        const isAssignable = this.assignabilityService.isAssignable(
            isNullable ? actualType.asNonNullable : actualType,
            current
        );

        if (!isAssignable) {
            this.callResolvedGenericArgumentStates.set(genericName, TypeResolutionState.Conflict);
            return false;
        }

        return true;
    }

    /**
     * Handles the Fixed state where the generic type was explicitly provided.
     * Only allows subtypes in covariant mode.
     *
     * @param actualType The actual type being checked
     * @param isNullable Whether the generic reference is nullable
     * @param allowSubtypes Whether to allow covariant matching
     * @param current The currently resolved type
     * @returns true if compatible
     */
    private handleFixedGenericState(
        actualType: CustomValueType,
        isNullable: boolean,
        allowSubtypes: boolean,
        current: CustomValueType
    ): boolean {
        if (!allowSubtypes) {
            return false;
        }

        if (isCustomNullType(actualType)) {
            if (!(isNullable || current.isNullable)) {
                return false;
            }
            return true;
        }

        const isAssignable = this.assignabilityService.isAssignable(
            isNullable ? actualType.asNonNullable : actualType,
            current
        );

        return isAssignable;
    }

    /**
     * Handles the Defined (covariant) state where the generic type was previously resolved covariantly.
     * In covariant mode, finds and sets the common parent type.
     * In invariant mode, checks if current is assignable to actual and narrows the type.
     *
     * @param genericName The name of the generic parameter
     * @param actualType The actual type being checked
     * @param isNullable Whether the generic reference is nullable
     * @param allowSubtypes Whether to allow covariant matching
     * @param current The currently resolved type
     * @returns true if compatible
     */
    private handleDefinedCovariantState(
        genericName: string,
        actualType: CustomValueType,
        isNullable: boolean,
        allowSubtypes: boolean,
        current: CustomValueType
    ): boolean {
        if (allowSubtypes) {
            return this.handleCovariantMerge(genericName, actualType, isNullable, current);
        } else {
            return this.handleInvariantNarrowing(genericName, actualType, isNullable, current);
        }
    }

    /**
     * Handles merging types in covariant mode by finding a common parent type.
     *
     * @param genericName The name of the generic parameter
     * @param actualType The actual type being checked
     * @param isNullable Whether the generic reference is nullable
     * @param current The currently resolved type
     * @returns true if a common parent type was found
     */
    private handleCovariantMerge(
        genericName: string,
        actualType: CustomValueType,
        isNullable: boolean,
        current: CustomValueType
    ): boolean {
        if (isCustomNullType(actualType)) {
            if (!(isNullable || current.isNullable)) {
                this.callResolvedGenericArguments.set(genericName, current.asNullable);
            }
            return true;
        }

        const commonType = findCommonParentType(current.asNonNullable, actualType.asNonNullable, this.services);

        if (commonType !== undefined) {
            this.callResolvedGenericArguments.set(
                genericName,
                current.isNullable || (actualType.isNullable && !isNullable) ? commonType.asNullable : commonType
            );
            return true;
        } else {
            this.callResolvedGenericArgumentStates.set(genericName, TypeResolutionState.Conflict);
            return false;
        }
    }

    /**
     * Handles narrowing the type in invariant mode.
     * Checks if the current type is assignable to the actual type and narrows if so.
     *
     * @param genericName The name of the generic parameter
     * @param actualType The actual type being checked
     * @param isNullable Whether the generic reference is nullable
     * @param current The currently resolved type
     * @returns true if the types are compatible and narrowing succeeded
     */
    private handleInvariantNarrowing(
        genericName: string,
        actualType: CustomValueType,
        isNullable: boolean,
        current: CustomValueType
    ): boolean {
        const normalizedActualType = current.isNullable && isNullable ? actualType.asNonNullable : actualType;
        const isCurrentAssignable = this.assignabilityService.isAssignable(current, normalizedActualType);

        if (isCurrentAssignable) {
            this.callResolvedGenericArguments.set(genericName, normalizedActualType);
            this.callResolvedGenericArgumentStates.set(genericName, TypeResolutionState.DefinedInvariant);
            return true;
        } else {
            this.callResolvedGenericArgumentStates.set(genericName, TypeResolutionState.Conflict);
            return false;
        }
    }
}
