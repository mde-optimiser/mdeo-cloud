import {
    GrammarDeserializer,
    isTerminalRule,
    type GrammarDeserializationContext,
    type Interface,
    type ParserRule
} from "@mdeo/language-common";
import type {
    ResolvedContributedClass,
    ContributedFunctionSignature,
    ResolvedContributedExpression,
    ResolvedScriptContributionPlugins,
    ScriptContributionPlugin,
    ResolvedContributedFunction
} from "./scriptContributionPlugin.js";
import { ContributedClass, ExternalImplementation } from "./scriptContributionPlugin.js";
import {
    ClassTypeRef,
    FunctionSignature,
    GenericTypeRef,
    LambdaType,
    type ClassType,
    type ReturnType
} from "@mdeo/language-expression";
import { createRecordClassType, RECORD_COPY_METHOD } from "../features/records.js";

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

    const functions = resolveFunctions(plugins);
    const classes = resolveClasses(plugins);
    const recordNames = new Set<string>();
    for (const contributed of classes) {
        if (contributed.declaration.kind !== "record") {
            continue;
        }
        // A record's constructor is a global function named like the record.
        if (functions.has(contributed.name) || recordNames.has(contributed.name)) {
            throw new Error(
                `Record '${contributed.name}' of contribution '${contributed.contributionId}' has the name of ` +
                    `another contributed function or record.`
            );
        }
        recordNames.add(contributed.name);
    }

    return {
        functions,
        expressions: expressions,
        rules: extensionRules,
        classes
    };
}

/**
 * Collection types a record field may hold.
 */
const COLLECTION_TYPES = new Set([
    "Collection",
    "OrderedCollection",
    "List",
    "Set",
    "OrderedSet",
    "Bag",
    "Map",
    "ReadonlyCollection",
    "ReadonlyOrderedCollection",
    "ReadonlyList",
    "ReadonlySet",
    "ReadonlyOrderedSet",
    "ReadonlyBag",
    "ReadonlyMap"
]);

/**
 * Scalar types a record field may hold.
 */
const SCALAR_TYPES = new Set(["int", "long", "float", "double", "boolean", "string"]);

/**
 * Resolves the classes every contribution defines into class types, after checking them.
 *
 * @param plugins The contribution plugins
 * @returns The resolved classes
 * @throws Error if a record field has a type a record cannot hold, or a signature refers to a
 *         contributed class its contribution does not define
 */
function resolveClasses(plugins: ScriptContributionPlugin[]): ResolvedContributedClass[] {
    const resolved: ResolvedContributedClass[] = [];
    for (const plugin of plugins) {
        const classes = plugin.classes ?? {};
        const typePackage = ContributedClass.packageOf(plugin.id);
        for (const [name, declaration] of Object.entries(classes)) {
            let classType: ClassType;
            if (declaration.kind === "record") {
                for (const field of declaration.fields) {
                    if (!isRecordFieldType(field.type, plugin)) {
                        throw new Error(
                            `Field '${field.name}' of record '${name}' in contribution '${plugin.id}' has a type a ` +
                                `record cannot hold. Use scalars, strings, model instances, enum values, records of ` +
                                `the same contribution, or collections of those.`
                        );
                    }
                    if (field.name === RECORD_COPY_METHOD) {
                        throw new Error(
                            `Field '${field.name}' of record '${name}' in contribution '${plugin.id}' has the name of ` +
                                `the method that copies a record.`
                        );
                    }
                }
                classType = createRecordClassType(
                    name,
                    typePackage,
                    declaration.fields.map((field) => ({ name: field.name, hasDefault: false })),
                    (index) => declaration.fields[index]!.type
                );
            } else {
                classType = {
                    name,
                    package: typePackage,
                    properties: {},
                    methods: {},
                    superTypes: [{ package: "builtin", type: "Any" }]
                };
            }
            resolved.push({ contributionId: plugin.id, name, declaration, classType });
        }
        validateClassReferences(plugin);
    }
    return resolved;
}

/**
 * Reports whether a record field may have a type.
 *
 * @param type The field type
 * @param plugin The contribution defining the record
 * @returns True when a record can hold values of the type
 */
function isRecordFieldType(type: ReturnType, plugin: ScriptContributionPlugin): boolean {
    if (!ClassTypeRef.is(type)) {
        return false;
    }
    if (type.package === "builtin") {
        if (SCALAR_TYPES.has(type.type)) {
            return true;
        }
        if (!COLLECTION_TYPES.has(type.type)) {
            return false;
        }
        return Object.values(type.typeArgs ?? {}).every((arg) => isRecordFieldType(arg, plugin));
    }
    if (type.package.startsWith(`${ContributedClass.PACKAGE_PREFIX}/`)) {
        return type.package === ContributedClass.packageOf(plugin.id) && plugin.classes?.[type.type]?.kind === "record";
    }
    return type.package.startsWith("class/") || type.package.startsWith("enum/");
}

/**
 * Checks that every contributed class a contribution's signatures and records name is one the
 * contribution defines itself.
 *
 * @param plugin The contribution
 * @throws Error naming the first reference that does not resolve
 */
function validateClassReferences(plugin: ScriptContributionPlugin): void {
    for (const [functionName, contributedFunction] of Object.entries(plugin.functions)) {
        for (const signature of Object.values(contributedFunction.signatures)) {
            const defaulted = signature.signature.parameters.find((parameter) => parameter.hasDefault === true);
            if (defaulted != undefined) {
                throw new Error(
                    `Parameter '${defaulted.name}' of function '${functionName}' in contribution '${plugin.id}' ` +
                        `declares a default, but contributed functions cannot have default values.`
                );
            }
        }
    }
    const check = (type: ReturnType, where: string): void => {
        if (GenericTypeRef.is(type) || !ClassTypeRef.is(type)) {
            if (LambdaType.is(type)) {
                check(type.returnType, where);
                type.parameters.forEach((parameter) => check(parameter.type, where));
            }
            return;
        }
        if (
            type.package.startsWith(`${ContributedClass.PACKAGE_PREFIX}/`) &&
            (type.package !== ContributedClass.packageOf(plugin.id) || plugin.classes?.[type.type] == undefined)
        ) {
            throw new Error(
                `${where} in contribution '${plugin.id}' refers to class '${type.type}' of '${type.package}', ` +
                    `which the contribution does not define.`
            );
        }
        Object.values(type.typeArgs ?? {}).forEach((arg) => check(arg, where));
    };
    for (const [functionName, contributedFunction] of Object.entries(plugin.functions)) {
        for (const signature of Object.values(contributedFunction.signatures)) {
            signature.signature.parameters.forEach((parameter) => check(parameter.type, `Function '${functionName}'`));
            check(signature.signature.returnType, `Function '${functionName}'`);
        }
    }
    for (const [name, declaration] of Object.entries(plugin.classes ?? {})) {
        if (declaration.kind === "record") {
            declaration.fields.forEach((field) => check(field.type, `Record '${name}'`));
        }
    }
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
