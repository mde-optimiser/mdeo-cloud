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
    VoidType,
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
        validateContribution(plugin);
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
 * Resolves the classes every contribution defines into class types, after checking them.
 *
 * @param plugins The contribution plugins
 * @returns The resolved classes
 */
function resolveClasses(plugins: ScriptContributionPlugin[]): ResolvedContributedClass[] {
    const resolved: ResolvedContributedClass[] = [];
    for (const plugin of plugins) {
        const classes = plugin.classes ?? {};
        const typePackage = ContributedClass.packageOf(plugin.id);
        for (const [name, declaration] of Object.entries(classes)) {
            let classType: ClassType;
            if (declaration.kind === "record") {
                classType = createRecordClassType(
                    name,
                    typePackage,
                    declaration.fields.map((field) => ({
                        name: field.name,
                        hasDefault: field.defaultValue != undefined
                    })),
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
            resolved.push({ contributionId: plugin.id, name, declaration, types: plugin.types, classType });
        }
    }
    return resolved;
}

/**
 * Collection types, each with the read-only type a parameter of an external function takes
 * instead. The read-only types map to themselves.
 */
const COLLECTION_TYPES = new Map([
    ["Collection", "ReadonlyCollection"],
    ["OrderedCollection", "ReadonlyOrderedCollection"],
    ["List", "ReadonlyList"],
    ["Set", "ReadonlySet"],
    ["OrderedSet", "ReadonlyOrderedSet"],
    ["Bag", "ReadonlyBag"],
    ["Map", "ReadonlyMap"],
    ["ReadonlyCollection", "ReadonlyCollection"],
    ["ReadonlyOrderedCollection", "ReadonlyOrderedCollection"],
    ["ReadonlyList", "ReadonlyList"],
    ["ReadonlySet", "ReadonlySet"],
    ["ReadonlyOrderedSet", "ReadonlyOrderedSet"],
    ["ReadonlyBag", "ReadonlyBag"],
    ["ReadonlyMap", "ReadonlyMap"]
]);

/**
 * Built-in types other than collections that can be sent to and from a plugin's service.
 */
const SCALAR_TYPES = new Set(["int", "long", "float", "double", "boolean", "string", "Any"]);

/**
 * Where a type a contribution declares is used.
 */
interface TypeUse {
    /**
     * The contribution declaring the type
     */
    plugin: ScriptContributionPlugin;
    /**
     * Names the declaration in error messages, such as `Parameter 'a' of function 'f'`
     */
    where: string;
    /**
     * Whether values of the type are sent to or from the contribution's service: the types of
     * external functions and the fields of records
     */
    crossesWire: boolean;
    /**
     * Whether the type is that of a parameter, which an external function only reads
     */
    isParameter: boolean;
    /**
     * The generic type parameters in scope
     */
    generics: ReadonlySet<string>;
}

/**
 * Checks everything a contribution declares against one rule for types, used for parameters,
 * results and record fields alike.
 *
 * Every type may only refer to contributed classes the contribution defines itself. A type whose
 * values cross the boundary to the contribution's service (the signature of an external function,
 * or a record field, since records travel by value) must also be one the protocol can carry:
 * scalars, strings, `Any`, model instances, enum values, the contribution's own records and
 * opaque classes, declared generics, and collections of those, but no lambdas. The parameters of
 * an external function are only read, so they take read-only collections.
 *
 * @param plugin The contribution
 * @throws Error naming the first declaration that breaks the rule
 */
function validateContribution(plugin: ScriptContributionPlugin): void {
    const signatures: [string, ContributedFunctionSignature][] = [
        ...Object.entries(plugin.functions).flatMap(([name, contributed]) =>
            Object.values(contributed.signatures).map((signature): [string, ContributedFunctionSignature] => [
                name,
                signature
            ])
        ),
        ...Object.entries(plugin.expressions).map(([name, expression]): [string, ContributedFunctionSignature] => [
            name,
            expression.function
        ])
    ];

    for (const [name, { signature, implementation, defaultValues }] of signatures) {
        const crossesWire = ExternalImplementation.is(implementation);
        const generics = new Set(signature.generics ?? []);
        if (signature.isVarArgs === true) {
            throw new Error(
                `Function '${name}' in contribution '${plugin.id}' takes a variable number of arguments, ` +
                    `which contributed functions cannot.`
            );
        }
        for (const parameterName of Object.keys(defaultValues ?? {})) {
            if (!signature.parameters.some((parameter) => parameter.name === parameterName)) {
                throw new Error(
                    `Function '${name}' in contribution '${plugin.id}' has a default value for '${parameterName}', ` +
                        `which is not one of its parameters.`
                );
            }
        }
        for (const parameter of signature.parameters) {
            if (parameter.hasDefault === true && defaultValues?.[parameter.name] == undefined) {
                throw new Error(
                    `Parameter '${parameter.name}' of function '${name}' in contribution '${plugin.id}' is marked ` +
                        `as having a default, but the function gives it no default value.`
                );
            }
            checkType(parameter.type, {
                plugin,
                where: `Parameter '${parameter.name}' of function '${name}'`,
                crossesWire,
                isParameter: true,
                generics
            });
        }
        if (!VoidType.is(signature.returnType)) {
            checkType(signature.returnType, {
                plugin,
                where: `The result of function '${name}'`,
                crossesWire,
                isParameter: false,
                generics
            });
        }
    }

    for (const [name, declaration] of Object.entries(plugin.classes ?? {})) {
        if (declaration.kind !== "record") {
            continue;
        }
        for (const field of declaration.fields) {
            if (field.name === RECORD_COPY_METHOD) {
                throw new Error(
                    `Field '${field.name}' of record '${name}' in contribution '${plugin.id}' has the name of the ` +
                        `method that copies a record.`
                );
            }
            checkType(field.type, {
                plugin,
                where: `Field '${field.name}' of record '${name}'`,
                crossesWire: true,
                isParameter: false,
                generics: new Set()
            });
        }
    }

    const externals = signatures.filter(([, signature]) => ExternalImplementation.is(signature.implementation));
    if (externals.length > 0 && scriptFunctionsSessionName(plugin) == undefined) {
        throw new Error(
            `Contribution '${plugin.id}' declares external implementations for ` +
                `${externals.map(([name]) => `'${name}'`).join(", ")} but no '${SCRIPT_FUNCTIONS_PROTOCOL}' ` +
                `session. Add one to the contribution's 'sessions' so the calls can be answered.`
        );
    }
}

/**
 * Checks one type, and the types inside it, against the rule of {@link validateContribution}.
 *
 * @param type The type
 * @param use Where the type is used
 * @throws Error when the type breaks the rule
 */
function checkType(type: ReturnType, use: TypeUse): void {
    const refuse = (reason: string): never => {
        throw new Error(`${use.where} in contribution '${use.plugin.id}' ${reason}`);
    };
    if (VoidType.is(type)) {
        refuse("is void, which only a function's result can be.");
    }
    if (LambdaType.is(type)) {
        if (use.crossesWire) {
            // A lambda is code inside the execution process, which cannot be sent.
            refuse("is a lambda, which cannot cross the boundary to a plugin's service.");
        }
        type.parameters.forEach((parameter) => checkType(parameter.type, use));
        if (!VoidType.is(type.returnType)) {
            checkType(type.returnType, use);
        }
        return;
    }
    if (GenericTypeRef.is(type)) {
        if (!use.generics.has(type.generic)) {
            refuse(`refers to generic type '${type.generic}', which is not declared.`);
        }
        return;
    }
    if (!ClassTypeRef.is(type)) {
        refuse("has a type that cannot be resolved.");
        return;
    }
    if (type.package.startsWith(`${ContributedClass.PACKAGE_PREFIX}/`)) {
        if (
            type.package !== ContributedClass.packageOf(use.plugin.id) ||
            use.plugin.classes?.[type.type] == undefined
        ) {
            refuse(`refers to class '${type.type}' of '${type.package}', which the contribution does not define.`);
        }
        return;
    }
    if (type.package === "builtin") {
        const readonlyCollection = COLLECTION_TYPES.get(type.type);
        if (use.crossesWire && readonlyCollection == undefined && !SCALAR_TYPES.has(type.type)) {
            refuse(`has type '${type.type}', which cannot be sent to or from a plugin's service.`);
        }
        if (use.crossesWire && use.isParameter && readonlyCollection != undefined && readonlyCollection !== type.type) {
            refuse(
                `is a '${type.type}', but an external function cannot change its arguments. ` +
                    `Declare it as a '${readonlyCollection}'.`
            );
        }
        Object.values(type.typeArgs ?? {}).forEach((argument) => checkType(argument, use));
        return;
    }
    if (use.crossesWire && !type.package.startsWith("class/") && !type.package.startsWith("enum/")) {
        refuse(`has type '${type.type}' of '${type.package}', which cannot be sent to or from a plugin's service.`);
    }
    Object.values(type.typeArgs ?? {}).forEach((argument) => checkType(argument, use));
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
                        withDefaults(signature)
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
                    [FunctionSignature.DEFAULT_SIGNATURE]: withDefaults(contributedExpression.function)
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

/**
 * The signature of a contributed function, with every parameter it has a default value for marked
 * as one a call may leave out.
 *
 * @param contributed The contributed signature
 * @returns The signature the type system sees
 */
function withDefaults(contributed: ContributedFunctionSignature): FunctionSignature {
    const defaultValues = contributed.defaultValues ?? {};
    return {
        ...contributed.signature,
        parameters: contributed.signature.parameters.map((parameter) => ({
            ...parameter,
            hasDefault: defaultValues[parameter.name] != undefined
        }))
    };
}
