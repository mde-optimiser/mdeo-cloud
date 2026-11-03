import {
    DefaultScope,
    type BoundScope,
    type Scope,
    type ScopeEntry,
    type ScopeLocalInitialization,
    type LambdaTypeInferenceResult
} from "@mdeo/language-expression";
import type { ScriptTypirSpecifics } from "../../plugin.js";

/**
 * A scope for lambda expressions that exposes the lambda type inference result.
 * This scope extends the default scope behavior and stores the result of lambda type
 * inference, making it accessible during type checking and validation.
 */
export class LambdaScope extends DefaultScope<ScriptTypirSpecifics> {
    /**
     * Creates a new lambda scope.
     *
     * @param parent The parent scope containing this lambda scope
     * @param entriesProvider Function that provides the scope entries (parameters)
     * @param localInitializations Initial values for locally initialized entries
     * @param languageNode The lambda expression AST node
     * @param lambdaTypeInference The result of lambda type inference
     */
    constructor(
        parent: BoundScope<ScriptTypirSpecifics> | undefined,
        entriesProvider: (scope: Scope<ScriptTypirSpecifics>) => ScopeEntry<ScriptTypirSpecifics>[],
        localInitializations: ScopeLocalInitialization[],
        languageNode: ScriptTypirSpecifics["LanguageType"] | undefined,
        readonly lambdaTypeInference: LambdaTypeInferenceResult<ScriptTypirSpecifics>
    ) {
        super(parent, entriesProvider, localInitializations, languageNode);
        this.lambdaTypeInference = lambdaTypeInference;
    }
}
