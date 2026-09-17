import type { TypirProblem, TypirSpecifics, ValidationProblem } from "typir";
import type { CustomFunctionType } from "../kinds/custom-function/custom-function-type.js";
import type { CustomValueType } from "../kinds/custom-value/custom-value-type.js";
import type { ExtendedTypirServices } from "../service/extendedTypirServices.js";
import { CallValidationHelper, type NamedArgumentNode } from "./callValidationHelper.js";
import { sharedImport } from "@mdeo/language-shared";

const { ValidationProblem: ValidationProblemConstant } = sharedImport("typir");

/**
 * Result of resolving a function call, including overload selection and
 * resolved parameter types after generic substitution.
 */
export interface ResolvedCallInfo {
    /**
     * The name of the chosen overload.
     */
    overloadName: string;
    /**
     * The resolved parameter types for each declared parameter position.
     * For generic functions, these are the types after generic substitution.
     */
    resolvedParameterTypes: CustomValueType[];
    /**
     * For every argument, positional ones first and then named ones, the index of the parameter it
     * is passed to.
     */
    argumentParameterIndices: number[];
    /**
     * Whether the chosen signature uses varargs.
     */
    isVarArgs: boolean;
    /**
     * The index at which varargs start (i.e., the number of regular parameters).
     * Only meaningful when {@link isVarArgs} is true.
     */
    varArgsStartIndex: number;
}

/**
 * Resolves a function call and returns the overload name along with the resolved parameter types.
 * For generic functions, parameter types are substituted with the inferred generic types.
 * Returns undefined if the call is to a lambda or if no overload could be determined.
 *
 * @template Specifics Language-specific types extending TypirSpecifics
 * @param languageNode The AST node representing the entire call expression
 * @param functionType The type of the function being called
 * @param genericArgumentsNodes AST nodes for explicit generic type arguments
 * @param namedArgumentNodes AST nodes for the named call arguments
 * @param argumentNodes AST nodes for the call arguments
 * @param services Extended Typir services for type operations
 * @returns The resolved call info including overload name, resolved parameter types, and varargs info;
 *          or undefined if resolution failed
 */
export function getResolvedCallInfo<Specifics extends TypirSpecifics>(
    languageNode: Specifics["LanguageType"],
    functionType: CustomFunctionType,
    genericArgumentsNodes: Specifics["LanguageType"][],
    argumentNodes: Specifics["LanguageType"][],
    namedArgumentNodes: NamedArgumentNode<Specifics>[],
    services: ExtendedTypirServices<Specifics>
): ResolvedCallInfo | undefined {
    const helper = new OverloadCallHelper<Specifics>(
        languageNode,
        functionType,
        genericArgumentsNodes,
        argumentNodes,
        namedArgumentNodes,
        services,
        false
    );
    if (helper.chosenOverloadName == undefined || helper.resolvedParameterTypes.includes(undefined)) {
        return undefined;
    }
    const signature = functionType.details.definition.signatures[helper.chosenOverloadName];
    const isVarArgs = signature?.isVarArgs === true;
    const varArgsStartIndex = isVarArgs ? Math.max(0, (signature?.parameters.length ?? 1) - 1) : 0;
    return {
        overloadName: helper.chosenOverloadName,
        resolvedParameterTypes: helper.resolvedParameterTypes as CustomValueType[],
        argumentParameterIndices: helper.argumentParameterIndices,
        isVarArgs,
        varArgsStartIndex
    };
}

/**
 * Internal call helper for extracting overload information.
 * Creates validation problems when validation fails.
 *
 * @template Specifics Language-specific types extending TypirSpecifics
 */
class OverloadCallHelper<Specifics extends TypirSpecifics> extends CallValidationHelper<
    Specifics,
    ValidationProblem<Specifics>
> {
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
}
