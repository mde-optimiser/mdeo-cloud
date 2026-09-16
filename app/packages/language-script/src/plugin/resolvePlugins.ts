import {
    GrammarDeserializer,
    isTerminalRule,
    type GrammarDeserializationContext,
    type Interface,
    type ParserRule
} from "@mdeo/language-common";
import type {
    ContributedFunctionSignature,
    ResolvedContributedExpression,
    ResolvedScriptContributionPlugins,
    ScriptContributionPlugin,
    ResolvedContributedFunction
} from "./scriptContributionPlugin.js";
import { ExternalImplementation } from "./scriptContributionPlugin.js";
import { FunctionSignature, LambdaType, type ReturnType } from "@mdeo/language-expression";

/**
 * Protocol an external implementation is answered over.
 *
 * A contribution that declares an external implementation must also declare a session speaking
 * this protocol, because that session is the only way the call can be answered.
 */
export const SCRIPT_FUNCTIONS_PROTOCOL = "script-functions";

/**
 *  Resolves the contribution plugins into a unified structure.
 *
 * @param plugins The contribution plugins
 * @param deserializationContext The deserialization context
 * @returns The resolved plugins
 * @throws Error if a plugin with expression contributions does not define a grammar
 */
export function resolvePlugins(
    plugins: ScriptContributionPlugin[],
    deserializationContext: GrammarDeserializationContext
): ResolvedScriptContributionPlugins {
    const extensionRules: ParserRule<any>[] = [];
    const expressions: ResolvedContributedExpression[] = [];
    for (const plugin of plugins) {
        validateExternalImplementations(plugin);
    }
    for (const plugin of plugins) {
        if (Object.keys(plugin.expressions).length == 0) {
            continue;
        }
        if (plugin.grammar == undefined) {
            throw new Error("Plugin with expression contributions must define a grammar.");
        }
        const deserializer = new GrammarDeserializer(plugin.grammar, deserializationContext);
        const grammar = deserializer.deserializeGrammar();
        const rulesLookup = new Map<string, ParserRule<any>>();
        for (const rule of grammar.rules) {
            if (!isTerminalRule(rule)) {
                rulesLookup.set(rule.name, rule);
            }
        }
        const interfacesLookup = new Map<string, Interface<any>>();
        for (const type of grammar.interfaces) {
            interfacesLookup.set(type.name, type);
        }
        for (const [expressionName, expression] of Object.entries(plugin.expressions)) {
            const rule = rulesLookup.get(expression.ruleName);
            const type = interfacesLookup.get(expression.interfaceName);
            if (rule == undefined) {
                throw new Error(`Expression rule '${expression.ruleName}' not found in plugin grammar.`);
            }
            if (type == undefined) {
                throw new Error(`Expression interface '${expression.interfaceName}' not found in plugin grammar.`);
            }
            extensionRules.push(rule);
            expressions.push({
                signature: expression.function.signature,
                interface: type,
                name: expressionName
            });
        }
    }

    return {
        functions: resolveFunctions(plugins),
        expressions: expressions,
        rules: extensionRules
    };
}

/**
 * Checks every external implementation a plugin declares against what it can actually deliver.
 *
 * Both failures here are wiring mistakes the author can fix, and both are worth refusing at
 * service start rather than at execution time: an execution that discovers them has already
 * cost a user a run, and the error it could produce would name a script line rather than the
 * contribution that is actually wrong.
 *
 * @param plugin The contribution to check
 * @throws Error if an external implementation takes or returns a lambda, or if the contribution
 *         declares no session that could answer it
 */
function validateExternalImplementations(plugin: ScriptContributionPlugin): void {
    const externals: [string, ContributedFunctionSignature][] = [];
    for (const [functionName, contributedFunction] of Object.entries(plugin.functions)) {
        for (const signature of Object.values(contributedFunction.signatures)) {
            if (ExternalImplementation.is(signature.implementation)) {
                externals.push([functionName, signature]);
            }
        }
    }
    for (const [expressionName, expression] of Object.entries(plugin.expressions)) {
        if (ExternalImplementation.is(expression.function.implementation)) {
            externals.push([expressionName, expression.function]);
        }
    }

    if (externals.length === 0) {
        return;
    }

    for (const [name, signature] of externals) {
        // A lambda is a reference to code inside the execution process. It cannot be sent, and
        // the service on the other side has no way to call back into a running script, so a
        // signature that takes or returns one could never be answered.
        const lambdaParameter = signature.signature.parameters.find((parameter) => isLambda(parameter.type));
        if (lambdaParameter) {
            throw new Error(
                `External function '${name}' takes a lambda parameter '${lambdaParameter.name}'. ` +
                    `Lambdas cannot cross the boundary to a plugin's service.`
            );
        }
        if (isLambda(signature.signature.returnType)) {
            throw new Error(
                `External function '${name}' returns a lambda. ` +
                    `Lambdas cannot cross the boundary to a plugin's service.`
            );
        }
    }

    if (scriptFunctionsSessionName(plugin) == undefined) {
        throw new Error(
            `Contribution '${plugin.id}' declares external implementations for ` +
                `${externals.map(([name]) => `'${name}'`).join(", ")} but no '${SCRIPT_FUNCTIONS_PROTOCOL}' ` +
                `session. Add one to the contribution's 'sessions' so the calls can be answered.`
        );
    }
}

/**
 * Finds the session a contribution answers `script-functions` calls on.
 *
 * @param plugin The contribution
 * @returns The session name, or undefined when the contribution declares none
 */
function scriptFunctionsSessionName(plugin: ScriptContributionPlugin): string | undefined {
    return Object.entries(plugin.sessions ?? {}).find(
        ([, session]) => session.protocol === SCRIPT_FUNCTIONS_PROTOCOL
    )?.[0];
}

/**
 * Reports whether a type is a lambda type.
 *
 * @param type The type to check
 * @returns True when the type is a lambda
 */
function isLambda(type: ReturnType): boolean {
    return LambdaType.is(type);
}

/**
 * Extracts functions contributed by plugins into a map of function members by name.
 *
 * @param plugins The contribution plugins
 * @returns The resolved contributed functions
 * @throws Error if there are duplicate function or expression names
 */
function resolveFunctions(plugins: ScriptContributionPlugin[]): Map<string, ResolvedContributedFunction> {
    const functions = new Map<string, ResolvedContributedFunction>();
    for (const plugin of plugins) {
        const origin = { contributionId: plugin.id, sessionName: scriptFunctionsSessionName(plugin) };
        for (const [functionName, contributedFunction] of Object.entries(plugin.functions)) {
            const func = {
                signatures: Object.fromEntries(
                    Object.entries(contributedFunction.signatures).map(([signatureName, signature]) => [
                        signatureName,
                        signature.signature
                    ])
                )
            };
            if (functions.has(functionName)) {
                throw new Error(`Duplicate function or expression name '${functionName}' contributed by plugins.`);
            }
            functions.set(functionName, { function: func, contributedFunction, types: plugin.types, ...origin });
        }
        for (const [expressionName, contributedExpression] of Object.entries(plugin.expressions)) {
            const func = {
                signatures: {
                    [FunctionSignature.DEFAULT_SIGNATURE]: contributedExpression.function.signature
                }
            };
            if (functions.has(expressionName)) {
                throw new Error(`Duplicate function or expression name '${expressionName}' contributed by plugins.`);
            }
            functions.set(expressionName, {
                function: func,
                contributedFunction: {
                    signatures: { [FunctionSignature.DEFAULT_SIGNATURE]: contributedExpression.function }
                },
                types: plugin.types,
                ...origin
            });
        }
    }
    return functions;
}
