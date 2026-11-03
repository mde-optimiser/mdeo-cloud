import type { ValidationAcceptor } from "langium";
import type { AstReflection } from "@mdeo/language-common";
import type { TransformationInputDescription } from "@mdeo/language-model-transformation";
import type { ConfigLiteralType, EnumLiteralType, OperatorArgumentType, UsingPathType } from "../grammar/mdeoTypes.js";
import {
    argumentName,
    enumLiteralNames,
    listElements,
    literalKind,
    literalText,
    parseNumberLiteral,
    type ConfigLiteralKind
} from "../features/operatorArguments.js";

/**
 * The smallest and largest value of a Kotlin `Int`.
 */
const INT_MIN = -2147483648;
const INT_MAX = 2147483647;

/**
 * The scalar types a literal of each number kind may be passed to, as the transformation
 * language converts numbers implicitly (int → long → float → double).
 */
const ASSIGNABLE_SCALARS: Record<ConfigLiteralKind, ReadonlySet<string>> = {
    int: new Set(["int", "long", "float", "double"]),
    long: new Set(["long", "float", "double"]),
    float: new Set(["float", "double"]),
    double: new Set(["double"]),
    string: new Set(["string"]),
    boolean: new Set(["boolean"]),
    enum: new Set(),
    list: new Set(),
    null: new Set()
};

/**
 * How each literal kind is named in messages.
 */
const KIND_NAMES: Record<ConfigLiteralKind, string> = {
    int: "an int literal",
    long: "a long literal",
    float: "a float literal",
    double: "a double literal",
    string: "a string literal",
    boolean: "a boolean literal",
    enum: "an enum literal",
    list: "a list",
    null: "null"
};

/**
 * Whether an input can be set from the config: scalars, enums and lists/sets of them.
 *
 * @param input The input
 * @returns True when a `with { }` block may pass it
 */
export function isPassableInput(input: TransformationInputDescription): boolean {
    return (
        input.kind === "scalar" ||
        input.kind === "enum" ||
        (input.kind === "collection" && (input.elementKind === "scalar" || input.elementKind === "enum"))
    );
}

/**
 * Checks the `with { }` block of a using entry against the inputs of the transformation it
 * names: every argument names a passable input once, and its literal is assignable to the
 * input's type (with the number conversions of the transformation language).
 */
export class OperatorArgumentsValidator {
    /**
     * @param reflection The AST reflection
     */
    constructor(private readonly reflection: AstReflection) {}

    /**
     * Validates the arguments of a using entry.
     *
     * @param usingPath The using entry
     * @param fileName The display name of the transformation (e.g. `move.mt`)
     * @param inputs The `in` declarations of the transformation
     * @param outputNames The names of its `out` declarations
     * @param accept The validation acceptor
     * @returns The names the `with` block sets (valid or not), which count as supplied
     */
    validate(
        usingPath: UsingPathType,
        fileName: string,
        inputs: TransformationInputDescription[],
        outputNames: ReadonlySet<string>,
        accept: ValidationAcceptor
    ): Set<string> {
        const supplied = new Set<string>();
        const withBlock = usingPath.with;
        if (withBlock == undefined) {
            return supplied;
        }
        if ((withBlock.arguments ?? []).length === 0) {
            accept("warning", "The 'with' block is empty, so it is redundant: remove it.", {
                node: withBlock,
                keyword: "with"
            });
            return supplied;
        }
        const byName = new Map(inputs.map((input) => [input.name, input]));
        for (const argument of withBlock.arguments) {
            const name = argumentName(argument);
            if (supplied.has(name)) {
                accept("error", `Duplicate argument '${name}': each input can be set only once.`, {
                    node: argument,
                    property: "name"
                });
                continue;
            }
            supplied.add(name);
            const input = byName.get(name);
            if (input == undefined) {
                this.reportUnknownInput(argument, name, fileName, inputs, outputNames, accept);
                continue;
            }
            if (!isPassableInput(input)) {
                this.reportNotPassable(argument, input, fileName, accept);
                continue;
            }
            this.checkValue(argument, input, accept);
        }
        return supplied;
    }

    /**
     * Reports an argument whose name is not an input of the transformation.
     */
    private reportUnknownInput(
        argument: OperatorArgumentType,
        name: string,
        fileName: string,
        inputs: TransformationInputDescription[],
        outputNames: ReadonlySet<string>,
        accept: ValidationAcceptor
    ): void {
        if (outputNames.has(name)) {
            accept("error", `'${name}' is an output of '${fileName}', not an input: only inputs can be set.`, {
                node: argument,
                property: "name"
            });
            return;
        }
        const passable = inputs.filter(isPassableInput).map((input) => `'${input.name}'`);
        const hint =
            passable.length > 0
                ? `Its inputs that the config can set: ${passable.join(", ")}.`
                : `It has no inputs that the config can set.`;
        accept("error", `'${name}' is not an input of '${fileName}'. ${hint}`, {
            node: argument,
            property: "name"
        });
    }

    /**
     * Reports an argument for an input that the config cannot set (elements, models).
     */
    private reportNotPassable(
        argument: OperatorArgumentType,
        input: TransformationInputDescription,
        fileName: string,
        accept: ValidationAcceptor
    ): void {
        let what: string;
        if (input.kind === "model" || input.kind === "modelList") {
            what = `is a model input (${input.typeName}) of '${fileName}', so it cannot be set from the config: the optimizer passes the other parents of a crossover to it`;
        } else if (input.kind === "element" || input.kind === "collection") {
            what = `is a model element input (${input.typeName}) of '${fileName}', so it cannot be set from the config: only scalar, enum and List/Set-of-scalar inputs can`;
        } else {
            what = `has the type ${input.typeName}, which cannot be set from the config: only scalar, enum and List/Set-of-scalar inputs can`;
        }
        accept("error", `'${input.name}' ${what}.`, { node: argument, property: "name" });
    }

    /**
     * Checks that the value of an argument is assignable to its input.
     */
    private checkValue(
        argument: OperatorArgumentType,
        input: TransformationInputDescription,
        accept: ValidationAcceptor
    ): void {
        const value = argument.value;
        const kind = literalKind(this.reflection, value);
        if (value == undefined || kind == undefined) {
            return;
        }
        if (kind === "null") {
            accept(
                "error",
                `'${input.name}' cannot be set to null: leave the argument out instead, so the input takes its default (or null, if it is optional).`,
                { node: argument, property: "value" }
            );
            return;
        }
        if (input.kind === "collection") {
            if (kind !== "list") {
                accept(
                    "error",
                    `'${input.name}' is a ${input.typeName}: write its value as a list, e.g. [${literalText(value)}].`,
                    { node: argument, property: "value" }
                );
                return;
            }
            for (const element of listElements(value)) {
                this.checkScalar(element, input, `An element of '${input.name}' (${input.typeName})`, accept);
            }
            return;
        }
        this.checkScalar(value, input, `'${input.name}'`, accept);
    }

    /**
     * Checks that a literal is assignable to the scalar or enum (element) type of an input.
     *
     * @param literal The literal
     * @param input The input
     * @param subject How the target is named in messages
     * @param accept The validation acceptor
     */
    private checkScalar(
        literal: ConfigLiteralType,
        input: TransformationInputDescription,
        subject: string,
        accept: ValidationAcceptor
    ): void {
        const kind = literalKind(this.reflection, literal);
        if (kind == undefined) {
            return;
        }
        const expected = input.baseTypeName;
        const isEnum = input.kind === "enum" || input.elementKind === "enum";
        const text = literalText(literal);
        if (kind === "null") {
            accept("error", `${subject} cannot be null.`, { node: literal });
            return;
        }
        if (kind === "list") {
            const hint =
                input.kind === "collection" ? "lists cannot be nested" : "lists are only for List and Set inputs";
            accept("error", `${subject} expects ${article(expected)}, but ${text} is a list: ${hint}.`, {
                node: literal
            });
            return;
        }
        if (isEnum) {
            if (kind !== "enum") {
                accept(
                    "error",
                    `${subject} expects a ${expected} literal, e.g. ${expected}.…, but ${text} is ${KIND_NAMES[kind]}.`,
                    {
                        node: literal
                    }
                );
                return;
            }
            if ((literal as EnumLiteralType).enum?.ref == undefined) {
                return;
            }
            const { enumName } = enumLiteralNames(literal);
            if (enumName !== expected) {
                accept("error", `${subject} expects a ${expected} literal, but ${text} is a ${enumName} literal.`, {
                    node: literal
                });
            }
            return;
        }
        if (!ASSIGNABLE_SCALARS[kind].has(expected)) {
            accept(
                "error",
                `${subject} expects ${article(expected)}, but ${text} is ${KIND_NAMES[kind]}${suffixHint(kind, expected, text)}.`,
                {
                    node: literal
                }
            );
            return;
        }
        this.checkNumberRange(literal, kind, accept);
    }

    /**
     * Checks that a number literal can be passed exactly: an int literal fits a Kotlin `Int`, a
     * long literal is a safe integer (the configuration travels as JSON numbers), every number is
     * finite.
     */
    private checkNumberRange(literal: ConfigLiteralType, kind: ConfigLiteralKind, accept: ValidationAcceptor): void {
        const raw = (literal as { value?: string | number }).value;
        if (raw == undefined || !["int", "long", "float", "double"].includes(kind)) {
            return;
        }
        const value = parseNumberLiteral(raw);
        const text = literalText(literal);
        if (kind === "int" && (value < INT_MIN || value > INT_MAX)) {
            accept("error", `${text} does not fit in an int; write ${text}L for a long input.`, { node: literal });
        } else if (kind === "long" && !Number.isSafeInteger(value)) {
            accept(
                "error",
                `${text} cannot be passed exactly: long arguments must lie between -${Number.MAX_SAFE_INTEGER} and ${Number.MAX_SAFE_INTEGER}.`,
                { node: literal }
            );
        } else if (!Number.isFinite(value)) {
            accept("error", `${text} is out of range.`, { node: literal });
        }
    }
}

/**
 * Names a type with its article, e.g. `an int`, `a double`, `a string`.
 */
function article(typeName: string): string {
    return /^[aeiou]/i.test(typeName) ? `an ${typeName}` : `a ${typeName}`;
}

/**
 * A hint how to write a number literal of the expected type, where it is only a suffix away.
 */
function suffixHint(kind: ConfigLiteralKind, expected: string, text: string): string {
    if (kind === "double" && expected === "float") {
        return ` (write ${text.replace(/[Dd]$/, "")}f for a float)`;
    }
    return "";
}
