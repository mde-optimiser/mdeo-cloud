import type { AstReflection } from "@mdeo/language-common";
import { sharedImport } from "@mdeo/language-shared";
import type { AstNode, LangiumDocument } from "langium";
import type { Range, TextEdit } from "vscode-languageserver-types";
import {
    ForInVariable,
    type ForInVariableType,
    type ModelTransformationType,
    type TransformationFileImportType,
    type TransformationParameterType
} from "../../grammar/modelTransformationTypes.js";
import { getModelShape } from "../transformationDeclarations.js";

const { AstUtils } = sharedImport("langium");

/**
 * Prefix of the ids of new (not yet committed) rows of a start or end node; followed by the
 * direction of the declaration the row adds (`in` or `out`).
 */
export const NEW_DECLARATION_LABEL_PREFIX = "__new-label-declaration-";

/**
 * Returns the primary successful end of a transformation among the ids of its `stop` end
 * nodes: the implicit end after the last statement when the flow reaches it, otherwise the
 * first `stop`. Every successful end lists the outputs, but only the rows of the primary end
 * carry the ids of the declarations themselves (validation markers of an output show there),
 * and the toolbox adds new outputs there.
 *
 * @param stopNodeIds The ids of the `stop` end nodes, in graph order
 * @returns The id, or undefined when the transformation never ends successfully
 */
export function primaryEndNodeId(stopNodeIds: readonly string[]): string | undefined {
    return stopNodeIds.includes("implicit_end") ? "implicit_end" : stopNodeIds[0];
}

/**
 * Returns the id of the compartment of a start or end node that holds its declaration rows.
 *
 * @param nodeId The id of the start or end node
 * @returns The compartment id
 */
export function declarationsCompartmentId(nodeId: string): string {
    return `${nodeId}__declarations`;
}

/**
 * Returns the id of the row listing a declaration in a node: the declaration's own id in the
 * start node and in the primary end, made unique for the other ends. Any of them resolves to the
 * declaration, since element ids are resolved up to their first `__`.
 *
 * @param declarationId The id of the declaration
 * @param nodeId The id of the start or end node listing it
 * @param primary Whether the node holds the declaration's own row
 * @returns The row id
 */
export function declarationRowId(declarationId: string, nodeId: string, primary: boolean): string {
    return primary ? declarationId : `${declarationId}__at-${nodeId}`;
}

/**
 * What a header line declares: a path import, an input or an output.
 */
export type HeaderLineKind = "import" | "in" | "out";

/**
 * The parts of a header line as written, see {@link parseHeaderLine}.
 */
export type ParsedHeaderLine =
    | {
          kind: "import";
          /** The path as written, without the quotes. */
          file: string;
          /** The alias as written. */
          name: string;
      }
    | {
          kind: "in" | "out";
          /** The name as written. */
          name: string;
          /** The type as written. */
          type: string;
          /** The default value as written, if any. */
          defaultValue?: string;
      };

/**
 * The lines the toolbox offers to add to the header, each with a name no other declaration of
 * the file uses yet.
 */
export interface HeaderLineTemplates {
    /** A scalar input, e.g. `in input1: int`. */
    input: string;
    /** A scalar output, e.g. `out output1: int`. */
    output: string;
    /** A model input, e.g. `in donor1: model`. */
    model: string;
    /** A list of model inputs, e.g. `in donors1: List<model>`. */
    modelList: string;
}

/**
 * Returns what a header line declares, from its first word.
 *
 * @param text The line
 * @returns The kind, or `undefined` when the line is no header line
 */
export function headerLineKind(text: string): HeaderLineKind | undefined {
    const keyword = /^\s*(import|in|out)\b/.exec(text)?.[1];
    return keyword as HeaderLineKind | undefined;
}

/**
 * Splits a header line into its parts. Only the shape is checked; whether the parts are valid
 * source is up to the parser (see {@link checkHeaderLineParses}).
 *
 * @param text The line, e.g. `in maxLoad: int = 3` or `import "./move.mt" as move`
 * @returns The parts, or `undefined` when the line does not have the shape of a header line
 */
export function parseHeaderLine(text: string): ParsedHeaderLine | undefined {
    const trimmed = text.trim();
    const importMatch = /^import\s+"([^"\n]*)"\s+as\s+(\S+)$/.exec(trimmed);
    if (importMatch != undefined) {
        return { kind: "import", file: importMatch[1], name: importMatch[2] };
    }
    const declarationMatch = /^(in|out)\s+(`[^`\n]+`|[^\s:`]+)\s*:\s*([^=]*?)\s*(?:=\s*(.*))?$/.exec(trimmed);
    if (declarationMatch == undefined || declarationMatch[3].length === 0) {
        return undefined;
    }
    const defaultValue = declarationMatch[4]?.trim();
    return {
        kind: declarationMatch[1] as "in" | "out",
        name: declarationMatch[2],
        type: declarationMatch[3],
        defaultValue: defaultValue != undefined && defaultValue.length > 0 ? defaultValue : undefined
    };
}

/**
 * Removes the backticks of an identifier written as `` `name` ``.
 *
 * @param name The identifier as written
 * @returns The name
 */
export function unquotedName(name: string): string {
    return name.startsWith("`") && name.endsWith("`") && name.length > 1 ? name.slice(1, -1) : name;
}

/**
 * Collects the names declared anywhere in a transformation: its imports and declarations, the
 * objects, variables and loop variables of its statements and the outputs of its calls. A new
 * header name has to differ from all of them, since names are file-global.
 *
 * @param root The transformation root
 * @returns The names
 */
export function declaredNames(root: ModelTransformationType): Set<string> {
    const names = new Set<string>();
    for (const node of AstUtils.streamAllContents(root)) {
        const name = (node as { name?: unknown }).name;
        if (typeof name === "string") {
            names.add(name);
        }
    }
    return names;
}

/**
 * Returns the first of `base1`, `base2`, … that is not used yet.
 *
 * @param base The name without its number
 * @param used The names in use
 * @returns The fresh name
 */
export function freshName(base: string, used: ReadonlySet<string>): string {
    for (let i = 1; ; i++) {
        const candidate = `${base}${i}`;
        if (!used.has(candidate)) {
            return candidate;
        }
    }
}

/**
 * Returns the header lines the toolbox adds, each with a fresh name.
 *
 * @param root The transformation root
 * @returns The lines
 */
export function headerLineTemplates(root: ModelTransformationType): HeaderLineTemplates {
    const used = declaredNames(root);
    return {
        input: `in ${freshName("input", used)}: int`,
        output: `out ${freshName("output", used)}: int`,
        model: `in ${freshName("donor", used)}: model`,
        modelList: `in ${freshName("donors", used)}: List<model>`
    };
}

/**
 * Derives the alias of an imported transformation from its path: the base name of the file in
 * camel case (`./generic/move-component.mt` becomes `moveComponent`), numbered when the name is
 * taken (`moveComponent2`).
 *
 * @param path The path of the imported file
 * @param used The names in use
 * @returns The alias
 */
export function importAlias(path: string, used: ReadonlySet<string>): string {
    const baseName = path.split("/").pop()?.replace(/\.mt$/, "") ?? "";
    const words = baseName.split(/[^\p{L}\p{N}_]+/u).filter((word) => word.length > 0);
    let alias = words
        .map(
            (word, index) => (index === 0 ? word.charAt(0).toLowerCase() : word.charAt(0).toUpperCase()) + word.slice(1)
        )
        .join("");
    if (alias.length === 0 || !/^\p{ID_Start}/u.test(alias)) {
        alias = `transformation${alias}`;
    }
    if (!used.has(alias)) {
        return alias;
    }
    for (let i = 2; ; i++) {
        if (!used.has(`${alias}${i}`)) {
            return `${alias}${i}`;
        }
    }
}

/**
 * Computes the edit inserting a new header line where it belongs, keeping the layout of the
 * serializer: the imports in one block after `using`, the declarations in one block after the
 * imports (inputs before outputs), blocks separated by a blank line.
 *
 * @param root The transformation root
 * @param document The transformation's document
 * @param text The line to insert
 * @returns The edit, or `undefined` when the line is no header line or the file has no `using`
 */
export function headerInsertEdit(
    root: ModelTransformationType,
    document: LangiumDocument,
    text: string
): TextEdit | undefined {
    const kind = headerLineKind(text);
    const using = root.import?.$cstNode;
    if (kind == undefined || using == undefined) {
        return undefined;
    }
    const line = text.trim();
    const imports = (root.imports ?? []).filter((node) => node.$cstNode != undefined);
    const parameters = (root.parameters ?? []).filter((node) => node.$cstNode != undefined);
    const inputs = parameters.filter((parameter) => parameter.direction === "in");
    const lastImportOrUsing: AstNode = imports[imports.length - 1] ?? root.import!;

    if (kind === "import") {
        return imports.length > 0
            ? insertAfterLine(document, lastImportOrUsing, line, false)
            : insertAfterLine(document, root.import!, line, true);
    }
    if (kind === "in") {
        if (inputs.length > 0) {
            return insertAfterLine(document, inputs[inputs.length - 1], line, false);
        }
        if (parameters.length > 0) {
            return insertBeforeLine(document, parameters[0], line);
        }
        return insertAfterLine(document, lastImportOrUsing, line, true);
    }
    return parameters.length > 0
        ? insertAfterLine(document, parameters[parameters.length - 1], line, false)
        : insertAfterLine(document, lastImportOrUsing, line, true);
}

/**
 * Inserts a line after the line on which a node ends (after a comment that ends the line too).
 *
 * @param document The document
 * @param node The node
 * @param line The line to insert
 * @param newBlock Whether the line starts a block of its own, separated by blank lines
 * @returns The edit
 */
function insertAfterLine(document: LangiumDocument, node: AstNode, line: string, newBlock: boolean): TextEdit {
    const textDocument = document.textDocument;
    const endLine = node.$cstNode!.range.end.line;
    const lineText = textDocument.getText({
        start: { line: endLine, character: 0 },
        end: { line: endLine + 1, character: 0 }
    });
    const position = { line: endLine, character: lineText.replace(/\r?\n$/, "").length };
    const rest = textDocument.getText().substring(textDocument.offsetAt(position));
    let newText = `${newBlock ? "\n\n" : "\n"}${line}`;
    if (newBlock && /^\r?\n[ \t]*\S/.test(rest)) {
        newText += "\n";
    }
    return { range: { start: position, end: position }, newText };
}

/**
 * Inserts a line before the line on which a node starts, and before the comment lines directly
 * above it, which belong to it.
 *
 * @param document The document
 * @param node The node
 * @param line The line to insert
 * @returns The edit
 */
function insertBeforeLine(document: LangiumDocument, node: AstNode, line: string): TextEdit {
    const textDocument = document.textDocument;
    let startLine = node.$cstNode!.range.start.line;
    while (startLine > 0) {
        const above = textDocument.getText({
            start: { line: startLine - 1, character: 0 },
            end: { line: startLine, character: 0 }
        });
        if (!/^\s*\/\//.test(above)) {
            break;
        }
        startLine--;
    }
    const position = { line: startLine, character: 0 };
    return { range: { start: position, end: position }, newText: `${line}\n` };
}

/**
 * Checks that a header line is valid source where it would go: the document with the line
 * inserted (or with the line replacing an existing header line) parses without more errors than
 * it has now.
 *
 * @param root The transformation root
 * @param document The transformation's document
 * @param parse Parses a text as a whole transformation and returns its lexer and parser errors
 * @param text The line
 * @param replaced The import or declaration the line replaces, if any
 * @returns The first new syntax error, or `undefined` when the line parses
 */
export function checkHeaderLineParses(
    root: ModelTransformationType,
    document: LangiumDocument,
    parse: (text: string) => { message: string }[],
    text: string,
    replaced?: TransformationFileImportType | TransformationParameterType
): string | undefined {
    const textDocument = document.textDocument;
    let edit: TextEdit | undefined;
    if (replaced?.$cstNode != undefined) {
        edit = { range: replaced.$cstNode.range, newText: text.trim() };
    } else {
        edit = headerInsertEdit(root, document, text);
    }
    if (edit == undefined) {
        return "A header line starts with 'import', 'in' or 'out'.";
    }
    const current = textDocument.getText();
    const start = textDocument.offsetAt(edit.range.start);
    const end = textDocument.offsetAt(edit.range.end);
    const changed = current.substring(0, start) + edit.newText + current.substring(end);
    const before = parse(current).length;
    const after = parse(changed);
    return after.length > before ? after[0].message : undefined;
}

/**
 * Returns the range of the lines a header line occupies, line break included, so that deleting
 * it leaves no empty line behind. When the line is the last one left of its block (the imports,
 * the declarations), the blank line separating the block from what follows goes with it, unless
 * that is the only blank line between what precedes and what follows the block.
 *
 * @param root The transformation root
 * @param document The transformation's document
 * @param node The import or declaration
 * @param deleted Every header line deleted along with this one
 * @returns The range to delete, or `undefined` without source
 */
export function headerLineDeletionRange(
    root: ModelTransformationType,
    document: LangiumDocument,
    node: TransformationFileImportType | TransformationParameterType,
    deleted: ReadonlySet<AstNode>
): Range | undefined {
    const cst = node.$cstNode;
    if (cst == undefined) {
        return undefined;
    }
    const textDocument = document.textDocument;
    const block: AstNode[] = (root.imports ?? []).includes(node as TransformationFileImportType)
        ? (root.imports ?? [])
        : (root.parameters ?? []);
    const lineText = (line: number) =>
        textDocument.getText({ start: { line, character: 0 }, end: { line: line + 1, character: 0 } });
    let endLine = cst.range.end.line + 1;
    const lastOfBlock = block.filter((other) => !deleted.has(other)).length === 0 && block[block.length - 1] === node;
    const firstLine = block[0]?.$cstNode?.range.start.line ?? cst.range.start.line;
    // the blank line after the block goes only when one before it separates what remains
    if (lastOfBlock && firstLine > 0 && lineText(firstLine - 1).trim().length === 0) {
        const next = lineText(endLine);
        if (next.length > 0 && next.trim().length === 0) {
            endLine++;
        }
    }
    return { start: { line: cst.range.start.line, character: 0 }, end: { line: endLine, character: 0 } };
}

/**
 * A model new pattern instances can be created in: a `model` input, or a loop variable over a
 * `List<model>` input.
 */
export interface TargetModel {
    /** The name of the model value. */
    name: string;
    /** The text the toolbox shows for it. */
    label: string;
}

/**
 * Lists the model values of a transformation that scope blocks can name: its `model` inputs
 * and the variables of its `for … in` loops over `List<model>` inputs (which are only visible
 * inside their loop).
 *
 * @param root The transformation root
 * @param reflection The AST reflection
 * @returns The model values, inputs first
 */
export function targetModels(root: ModelTransformationType, reflection: AstReflection): TargetModel[] {
    const result: TargetModel[] = [];
    const lists = new Set<string>();
    for (const parameter of root.parameters ?? []) {
        if (parameter.direction !== "in" || parameter.name == undefined) {
            continue;
        }
        const shape = getModelShape(reflection, parameter);
        if (shape === "single") {
            result.push({ name: parameter.name, label: parameter.name });
        } else if (shape === "list") {
            lists.add(parameter.name);
        }
    }
    const seen = new Set(result.map((model) => model.name));
    for (const node of AstUtils.streamAllContents(root)) {
        if (!reflection.isInstance(node, ForInVariable)) {
            continue;
        }
        const variable = node as ForInVariableType;
        const collection = (variable.$container as { collection?: { name?: string } } | undefined)?.collection?.name;
        if (
            variable.name != undefined &&
            collection != undefined &&
            lists.has(collection) &&
            !seen.has(variable.name)
        ) {
            seen.add(variable.name);
            result.push({ name: variable.name, label: `${variable.name} (each of ${collection})` });
        }
    }
    return result;
}
