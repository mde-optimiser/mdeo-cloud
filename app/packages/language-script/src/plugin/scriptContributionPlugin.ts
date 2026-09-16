import type { ClassType, FunctionSignature, FunctionType, ReturnType, ValueType } from "@mdeo/language-expression";
import type { ServerContributionPlugin } from "@mdeo/plugin";
import type { TypedCallableBody } from "./typedAst.js";
import type { Interface, ParserRule, SerializedGrammar } from "@mdeo/language-common";
import type { GenericAstNode } from "langium";

/**
 * Plugin for contributing stdlib and syntax extensions for the script language
 */
export interface ScriptContributionPlugin extends ServerContributionPlugin {
    /**
     * Identifies the plugin as a Script language contribution.
     */
    type: typeof ScriptContributionPlugin.TYPE;
    /**
     * Array of all types used in the program.
     * Generics are replaced by Any? due to type erasure.
     * Indexed by typeIndex in expressions.
     * Shared for all functions defined by this plugin
     */
    types: ReturnType[];
    /**
     * If used, the serialized grammar which is used for expression contributions.
     */
    grammar: SerializedGrammar | undefined;
    /**
     * Contributed functions by their names
     */
    functions: Record<string, ContributedFunction>;
    /**
     * Contributed expressions by their names
     * Note: expressions are currently syntactic sugar over functions, and thus are also
     * registered in the global scope as functions.
     */
    expressions: Record<string, ContributedExpression>;
    /**
     * Classes the contribution's functions take and return, by name.
     *
     * Signatures refer to one as `{ package: "contrib/<contribution id>", type: "<name>" }`.
     */
    classes?: Record<string, ContributedClass>;
}

export namespace ScriptContributionPlugin {
    /**
     * The type identifier for ScriptContributionPlugin
     */
    export const TYPE = "script-language-contribution";

    /**
     * Type guard for ScriptContributionPlugin
     *
     * @param value The value to check
     * @returns True if the value is a ScriptContributionPlugin, false otherwise
     */
    export function is(value: ServerContributionPlugin): value is ScriptContributionPlugin {
        return "type" in value && value.type === TYPE;
    }
}

/**
 * A class a contribution defines for its functions to exchange values with scripts.
 */
export type ContributedClass = ContributedRecord | ContributedOpaqueClass;

/**
 * A deeply immutable value with named fields, sent whole between the script and the service.
 *
 * Scripts read its fields as readonly properties and compare records by their content. They
 * cannot create records; only the contribution's functions return them.
 */
export interface ContributedRecord {
    /**
     * Marks the class as a record.
     */
    kind: "record";
    /**
     * The fields, in order. A field holds a scalar, a string, a model instance or enum value, a
     * record of the same contribution, or a readonly collection of those.
     */
    fields: ContributedRecordField[];
}

/**
 * One field of a {@link ContributedRecord}.
 */
export interface ContributedRecordField {
    /**
     * The field name, as scripts read it.
     */
    name: string;
    /**
     * The field type.
     */
    type: ValueType;
}

/**
 * A handle to state that stays on the contribution's service, such as an index built once and
 * queried by later calls. Scripts can only pass it back to the contribution's functions.
 */
export interface ContributedOpaqueClass {
    /**
     * Marks the class as opaque.
     */
    kind: "opaque";
}

export namespace ContributedClass {
    /**
     * The package of every contributed class, followed by `/<contribution id>`.
     */
    export const PACKAGE_PREFIX = "contrib";

    /**
     * Returns the package a contribution's classes are referred to by.
     *
     * @param contributionId The contribution id
     * @returns The package
     */
    export function packageOf(contributionId: string): string {
        return `${PACKAGE_PREFIX}/${contributionId}`;
    }
}

/**
 * A contributed function that
 */
export interface ContributedFunction {
    /**
     * The available signatures for the function.
     */
    signatures: Record<string, ContributedFunctionSignature>;
}

/**
 * An implementation that does not live in the platform at all.
 *
 * The body of a contributed function is normally a typed AST, which the execution service runs
 * exactly as it runs a user-written function. An external implementation says instead that the
 * function is answered by the service of the plugin that shipped the contribution, over the
 * `script-functions` [session](/develop/sessions) that contribution declares.
 *
 * The contract is copy-restore: arguments are sent, the service may change what it was given,
 * and only what actually changed comes back. Models are readonly on this path and can never be
 * edited through it.
 */
export interface ExternalImplementation {
    /**
     * Marks this implementation as external rather than a typed AST body.
     */
    kind: typeof ExternalImplementation.KIND;
    /**
     * Names the operation within the plugin's own protocol. The platform passes it through and
     * ascribes it no meaning; the plugin decides what it dispatches to.
     */
    operation: string;
    /**
     * Whether the operation needs the model, and in what form.
     *
     * `none` — the default — sends no model. `readonly` sends the model the script runs on, so
     * the operation can read it: uploaded once and reused until the script works on a different
     * model. A call that is passed model instances gets the model either way. The model is always
     * readonly.
     */
    model?: "none" | "readonly";
}

export namespace ExternalImplementation {
    /**
     * The discriminator distinguishing an external implementation from a typed AST body.
     */
    export const KIND = "external";

    /**
     * Type guard for an external implementation.
     *
     * @param value The implementation to check
     * @returns True when the implementation is external
     */
    export function is(value: ContributedImplementation): value is ExternalImplementation {
        return "kind" in value && value.kind === KIND;
    }
}

/**
 * What backs a contributed function: a typed AST the platform runs, or a call out to the
 * plugin's own service.
 */
export type ContributedImplementation = TypedCallableBody | ExternalImplementation;

/**
 * A contributed function signature with its implementation
 */
export interface ContributedFunctionSignature {
    /**
     * The signature of the function
     */
    signature: FunctionSignature;
    /**
     * The implementation of the function: the body of the function, or an
     * {@link ExternalImplementation} naming an operation the plugin's service answers
     */
    implementation: ContributedImplementation;
}

/**
 * A contributed DSL expression which is implemented by a function
 */
export interface ContributedExpression {
    /**
     * The name of the grammar rule that implements this expression
     */
    ruleName: string;
    /**
     * The name of the grammar type that implements this expression
     */
    interfaceName: string;
    /**
     * The function that implements this expression
     */
    function: ContributedFunctionSignature;
}

/**
 * Resolved plugins containing contributions of all plugins
 */
export interface ResolvedScriptContributionPlugins {
    /**
     * The resolved functions contributed by all the plugins
     * Contains functions from both 'functions' and 'expressions' of the plugins
     */
    functions: Map<string, ResolvedContributedFunction>;
    /**
     * The resolved expressions contributed by all the plugins
     */
    expressions: ResolvedContributedExpression[];
    /**
     * The rules for all the extensions, should be combined to one expression rule
     */
    rules: ParserRule<any>[];
    /**
     * The classes contributed by all the plugins
     */
    classes: ResolvedContributedClass[];
}

/**
 * Resolved variant of a contributed class
 */
export interface ResolvedContributedClass {
    /**
     * Id of the contribution that defines the class.
     */
    contributionId: string;
    /**
     * The class name.
     */
    name: string;
    /**
     * The declaration.
     */
    declaration: ContributedClass;
    /**
     * The class type registered with the type system.
     */
    classType: ClassType;
}

/**
 * Resolved variant of a contributed expression
 */
export interface ResolvedContributedExpression {
    /**
     * The signature used by the expression
     */
    signature: FunctionSignature;
    /**
     * The resolved type, can be used for inference/validation rules
     */
    interface: Interface<GenericAstNode>;
    /**
     * The name of the function that implements this expression
     */
    name: string;
}

/**
 * Resolved variant of a contributed function
 */
export interface ResolvedContributedFunction {
    /**
     * The function type
     */
    function: FunctionType;
    /**
     * The actual contributed function
     */
    contributedFunction: ContributedFunction;
    /**
     * The type lookup used by the implementations of the contributed function
     */
    types: ReturnType[];
    /**
     * Id of the contribution that shipped the function, which is the target an external
     * implementation of it is called on.
     */
    contributionId: string;
    /**
     * Name of the contribution's `script-functions` session, when it declares one.
     */
    sessionName?: string;
}
