import type { TypirProblem, TypirSpecifics, ValidationProblem } from "typir";
import type { CustomFunctionType } from "../kinds/custom-function/custom-function-type.js";
import type { CustomLambdaType } from "../kinds/custom-lambda/custom-lambda-type.js";
import type { ExtendedTypirServices } from "../service/extendedTypirServices.js";
import { CallValidationHelper, type NamedArgumentNode } from "./callValidationHelper.js";
import { sharedImport } from "@mdeo/language-shared";

const { ValidationProblem: ValidationProblemConstant } = sharedImport("typir");

/**
 * Validate a function or lambda call.
 * Checks that arguments match parameters, and all type constraints are satisfied.
 *
 * @template Specifics Language-specific types extending TypirSpecifics
 * @param languageNode The AST node representing the entire call expression
 * @param functionType The type of the function (must be a function type or lambda type)
 * @param genericArgumentsNodes AST nodes for explicit generic type arguments
 * @param namedArgumentNodes AST nodes for the named call arguments
 * @param argumentNodes AST nodes for the call arguments
 * @param services Extended Typir services for type operations
 * @returns Array of validation problems (empty if validation succeeds)
 */
export function validateCall<Specifics extends TypirSpecifics>(
    languageNode: Specifics["LanguageType"],
    functionType: CustomFunctionType | CustomLambdaType,
    genericArgumentsNodes: Specifics["LanguageType"][],
    argumentNodes: Specifics["LanguageType"][],
    namedArgumentNodes: NamedArgumentNode<Specifics>[],
    services: ExtendedTypirServices<Specifics>
): ValidationProblem<Specifics>[] {
    const validator = new InferenceCallValidator<Specifics>(
        languageNode,
        functionType,
        genericArgumentsNodes,
        argumentNodes,
        namedArgumentNodes,
        services,
        false
    );
    return validator.errors;
}

/**
 * Internal call validator for validation mode.
 * Creates validation problems when validation fails.
 *
 * @template Specifics Language-specific types extending TypirSpecifics
 */
class InferenceCallValidator<Specifics extends TypirSpecifics> extends CallValidationHelper<
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
