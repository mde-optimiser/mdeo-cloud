import type { InferenceProblem, TypirProblem, TypirSpecifics } from "typir";
import type { CustomFunctionType } from "../kinds/custom-function/custom-function-type.js";
import type { CustomLambdaType } from "../kinds/custom-lambda/custom-lambda-type.js";
import type { CustomValueType } from "../kinds/custom-value/custom-value-type.js";
import type { CustomVoidType } from "../kinds/custom-void/custom-void-type.js";
import type { ExtendedTypirServices } from "../service/extendedTypirServices.js";
import { CallValidationHelper, type NamedArgumentNode } from "./callValidationHelper.js";
import { sharedImport } from "@mdeo/language-shared";

const { InferenceProblem: InferenceProblemConstant } = sharedImport("typir");

/**
 * Infer the return type of a function or lambda call.
 * Performs type inference for the function reference and all arguments (if necessary),
 * resolves generic types, and validates argument compatibility.
 *
 * @template Specifics Language-specific types extending TypirSpecifics
 * @param languageNode The AST node representing the entire call expression
 * @param functionType The type of the function (must be a function type or lambda type)
 * @param genericArgumentsNodes AST nodes for explicit generic type arguments
 * @param namedArgumentNodes AST nodes for the named call arguments
 * @param argumentNodes AST nodes for the call arguments
 * @param services Extended Typir services for type operations
 * @returns The inferred return type, or an inference problem if inference fails
 */
export function inferCall<Specifics extends TypirSpecifics>(
    languageNode: Specifics["LanguageType"],
    functionType: CustomFunctionType | CustomLambdaType,
    genericArgumentsNodes: Specifics["LanguageType"][],
    argumentNodes: Specifics["LanguageType"][],
    namedArgumentNodes: NamedArgumentNode<Specifics>[],
    services: ExtendedTypirServices<Specifics>
): InferenceProblem<Specifics> | CustomValueType | CustomVoidType {
    const validator = new InferenceCallValidator<Specifics>(
        languageNode,
        functionType,
        genericArgumentsNodes,
        argumentNodes,
        namedArgumentNodes,
        services,
        true
    );
    return validator.inferredReturnType ?? validator.errors[0]!;
}

/**
 * Internal call validator for type inference mode.
 * Creates inference problems when validation fails.
 *
 * @template Specifics Language-specific types extending TypirSpecifics
 */
class InferenceCallValidator<Specifics extends TypirSpecifics> extends CallValidationHelper<
    Specifics,
    InferenceProblem<Specifics>
> {
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
