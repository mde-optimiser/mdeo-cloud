import type { AstReflection } from "@mdeo/language-common";
import { parseIdentifier, resolveRelativeDocument, sharedImport } from "@mdeo/language-shared";
import {
    ModelTransformation,
    parameterTypeName,
    renderParameter,
    type ModelTransformationType,
    type ParameterTypeName,
    type TransformationParameterType
} from "@mdeo/language-model-transformation";
import type { LangiumDocuments } from "langium";
import {
    BooleanLiteral,
    DoubleLiteral,
    EnumLiteral,
    FloatLiteral,
    IntLiteral,
    ListLiteral,
    LongLiteral,
    NullLiteral,
    StringLiteral,
    type ConfigLiteralType,
    type EnumLiteralType,
    type ListLiteralType,
    type OperatorArgumentType,
    type UsingPathType
} from "../grammar/mdeoTypes.js";

const { AstUtils } = sharedImport("langium");

/**
 * The default of `crossover { rate }`: the probability that crossover is applied to a group of
 * parents.
 */
export const DEFAULT_CROSSOVER_RATE = 0.9;

/**
 * The default of `crossover { parents }`: the number of parents per crossover.
 */
export const DEFAULT_CROSSOVER_PARENTS = 2;

/**
 * The kind of a literal of an operator argument, as written.
 */
export type ConfigLiteralKind = "int" | "long" | "float" | "double" | "string" | "boolean" | "enum" | "list" | "null";

/**
 * Returns the name of an operator argument, without the backticks of an escaped identifier.
 *
 * @param argument The argument
 * @returns The name of the input it sets
 */
export function argumentName(argument: OperatorArgumentType): string {
    return parseIdentifier(argument.name ?? "");
}

/**
 * Returns the kind of a literal, as written: `4` is an int literal even where a double is
 * expected.
 *
 * @param reflection The AST reflection
 * @param literal The literal
 * @returns Its kind, or undefined for an incomplete node
 */
export function literalKind(
    reflection: AstReflection,
    literal: ConfigLiteralType | undefined
): ConfigLiteralKind | undefined {
    if (reflection.isInstance(literal, IntLiteral)) {
        return "int";
    }
    if (reflection.isInstance(literal, LongLiteral)) {
        return "long";
    }
    if (reflection.isInstance(literal, FloatLiteral)) {
        return "float";
    }
    if (reflection.isInstance(literal, DoubleLiteral)) {
        return "double";
    }
    if (reflection.isInstance(literal, StringLiteral)) {
        return "string";
    }
    if (reflection.isInstance(literal, BooleanLiteral)) {
        return "boolean";
    }
    if (reflection.isInstance(literal, EnumLiteral)) {
        return "enum";
    }
    if (reflection.isInstance(literal, ListLiteral)) {
        return "list";
    }
    if (reflection.isInstance(literal, NullLiteral)) {
        return "null";
    }
    return undefined;
}

/**
 * Returns the number a number literal stands for: the literal as written without its `L`, `f`
 * or `d` suffix.
 *
 * @param text The literal as written, e.g. `42L`, `2.5E-3f`, `2.5`
 * @returns The number
 */
export function parseNumberLiteral(text: string | number): number {
    if (typeof text === "number") {
        return text;
    }
    return Number(text.replace(/[LlFfDd]$/, ""));
}

/**
 * Returns the source text of a literal, for messages.
 *
 * @param literal The literal
 * @returns e.g. `4`, `"fast"`, `Kind.SERVICE`, `[1, 2]`
 */
export function literalText(literal: ConfigLiteralType | undefined): string {
    return literal?.$cstNode?.text ?? "?";
}

/**
 * Returns the elements of a list literal.
 *
 * @param literal The list literal
 * @returns Its elements
 */
export function listElements(literal: ConfigLiteralType): ConfigLiteralType[] {
    return (literal as ListLiteralType).elements ?? [];
}

/**
 * Returns the enum name and entry name of an enum literal, as written.
 *
 * @param literal The enum literal
 * @returns The names
 */
export function enumLiteralNames(literal: ConfigLiteralType): { enumName: string; literal: string } {
    const typed = literal as EnumLiteralType;
    return {
        enumName: typed.enum?.ref?.name ?? typed.enum?.$refText ?? "",
        literal: typed.entry?.ref?.name ?? typed.entry?.$refText ?? ""
    };
}

/**
 * Returns the transformation a using entry names, if its file is loaded and is a
 * transformation.
 *
 * @param usingPath The using entry
 * @param documents The Langium documents
 * @param reflection The AST reflection
 * @returns The transformation's root
 */
export function findUsedTransformation(
    usingPath: UsingPathType,
    documents: LangiumDocuments,
    reflection: AstReflection
): ModelTransformationType | undefined {
    if (usingPath.path == undefined) {
        return undefined;
    }
    const target = resolveRelativeDocument(AstUtils.getDocument(usingPath), usingPath.path, documents);
    const root = target?.parseResult?.value;
    return root != undefined && reflection.isInstance(root, ModelTransformation)
        ? (root as ModelTransformationType)
        : undefined;
}

/**
 * Renders the declaration of a transformation parameter for hovers and completions, e.g.
 * `in maxCandidates: int = 0`. Uses the source text where the transformation is a workspace
 * document; for a transformation loaded as an AST only (without source text) the type is
 * rendered from the AST and a default value as `…`.
 *
 * @param parameter The parameter
 * @param reflection The AST reflection
 * @returns The declaration
 */
export function renderDeclaration(parameter: TransformationParameterType, reflection: AstReflection): string {
    if (parameter.type?.$cstNode != undefined) {
        return renderParameter(parameter);
    }
    const typeName = parameterTypeName(reflection, parameter);
    const typeText = typeName != undefined ? renderTypeName(typeName) : "?";
    const defaultText = parameter.defaultValue != undefined ? ` = ${parameter.defaultValue.$cstNode?.text ?? "…"}` : "";
    return `${parameter.direction} ${parameter.name}: ${typeText}${defaultText}`;
}

/**
 * Renders a type name as written, e.g. `List<Kind>` or `Container?`.
 */
function renderTypeName(typeName: ParameterTypeName): string {
    const args = typeName.typeArgs.length > 0 ? `<${typeName.typeArgs.map(renderTypeName).join(", ")}>` : "";
    return `${typeName.name}${args}${typeName.isNullable ? "?" : ""}`;
}
