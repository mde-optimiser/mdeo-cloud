import {
    DefaultScope,
    type BoundScope,
    type Scope,
    type ScopeEntry,
    type ScopeLocalInitialization,
    type LambdaTypeInferenceResult
} from "@mdeo/language-expression";
import type { TypirLangiumSpecifics } from "typir-langium";

/**
 * A scope for lambda expressions in the Model Transformation language.
 * This scope extends the default scope behavior and stores the result of lambda type
 * inference, making it accessible during type checking and validation.
 */
export class ModelTransformationLambdaScope extends DefaultScope<TypirLangiumSpecifics> {
    /**
     * The result of lambda type inference for this scope.
     */
    readonly lambdaTypeInference: LambdaTypeInferenceResult<TypirLangiumSpecifics>;

    /**
     * Creates a new Model Transformation lambda scope.
     *
     * @param parent The parent scope containing this lambda scope.
     * @param entriesProvider Function that provides the scope entries (parameters).
     * @param localInitializations Initial values for locally initialized entries.
     * @param languageNode The lambda expression AST node.
     * @param lambdaTypeInference The result of lambda type inference.
     */
    constructor(
        parent: BoundScope<TypirLangiumSpecifics> | undefined,
        entriesProvider: (scope: Scope<TypirLangiumSpecifics>) => ScopeEntry<TypirLangiumSpecifics>[],
        localInitializations: ScopeLocalInitialization[],
        languageNode: TypirLangiumSpecifics["LanguageType"] | undefined,
        lambdaTypeInference: LambdaTypeInferenceResult<TypirLangiumSpecifics>
    ) {
        super(parent, entriesProvider, localInitializations, languageNode);
        this.lambdaTypeInference = lambdaTypeInference;
    }
}
