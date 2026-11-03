import type { AstSerializer, AstSerializerAdditionalServices, WorkspaceEditService } from "@mdeo/language-common";
import type { AstNode, CstNode, LangiumCoreServices, LangiumDocument } from "langium";
import type { WorkspaceEdit, Position as PositionType } from "vscode-languageserver-types";
import { sharedImport } from "../sharedImport.js";

const { Range, Position } = sharedImport("vscode-languageserver-types");

/**
 * Default implementation of the WorkspaceEditService.
 * Provides utility methods for creating workspace edits.
 */
export class DefaultWorkspaceEditService implements WorkspaceEditService {
    /**
     * The AST serializer used for serializing AST nodes.
     */
    private readonly serializer: AstSerializer;

    constructor(services: LangiumCoreServices & AstSerializerAdditionalServices) {
        this.serializer = services.AstSerializer;
    }

    mergeWorkspaceEdits(edits: WorkspaceEdit[]): WorkspaceEdit {
        const mergedEdit: WorkspaceEdit = { changes: {} };
        for (const edit of edits) {
            if (edit.changes) {
                for (const uri of Object.keys(edit.changes)) {
                    if (!mergedEdit.changes![uri]) {
                        mergedEdit.changes![uri] = [];
                    }
                    mergedEdit.changes![uri]!.push(...edit.changes[uri]!);
                }
            }
        }
        return mergedEdit;
    }

    deleteCstNode(cstNode: CstNode, document: LangiumDocument): WorkspaceEdit {
        const uri = document.uri.toString();
        const text = document.textDocument.getText();

        const start = cstNode.range.start;
        const end = cstNode.range.end;

        const startOfStartLine = Position.create(start.line, 0);
        const removalEnd = Position.create(end.line + 1, 0);

        const textDocument = document.textDocument;
        const prefixBeforeNode = text.substring(textDocument.offsetAt(startOfStartLine), textDocument.offsetAt(start));
        const suffixAfterNode = text.substring(textDocument.offsetAt(end), textDocument.offsetAt(removalEnd));

        const onlyWhitespaceBefore = /^\s*$/.test(prefixBeforeNode);
        const onlyWhitespaceAfter = /^\s*$/.test(suffixAfterNode);

        if (onlyWhitespaceBefore && onlyWhitespaceAfter) {
            const removalRange = Range.create(startOfStartLine, removalEnd);
            return {
                changes: {
                    [uri]: [
                        {
                            range: removalRange,
                            newText: ""
                        }
                    ]
                }
            };
        }

        return {
            changes: {
                [uri]: [
                    {
                        range: cstNode.range,
                        newText: ""
                    }
                ]
            }
        };
    }

    async replaceCstNode(
        cstNode: CstNode,
        newContent: AstNode | string,
        document: LangiumDocument
    ): Promise<WorkspaceEdit> {
        let replacementText: string;
        if (typeof newContent === "string") {
            replacementText = newContent;
        } else {
            replacementText = await this.serializeNode(newContent, document);
        }
        const indentation = this.getIndentationForLine(cstNode.range.start, document);
        const indentedText = this.applyIndentation(replacementText, indentation);
        const edit: WorkspaceEdit = {
            changes: {
                [document.uri.toString()]: [
                    {
                        range: cstNode.range,
                        newText: indentedText
                    }
                ]
            }
        };
        return edit;
    }

    getIndentationForLine(position: PositionType, document: LangiumDocument): string {
        const line = document.textDocument.getText(Range.create(position.line, 0, position.line + 1, 0));
        const match = line.match(/^\s*/);
        return match ? match[0] : "";
    }

    async createInsertAfterNodeEdit(
        cstNode: CstNode,
        content: string,
        document: LangiumDocument,
        ensureNewlineBefore: boolean = true
    ): Promise<WorkspaceEdit> {
        const text = document.textDocument.getText();
        const insertPosition = cstNode.range.end;

        const indentation = this.getIndentationForLine(cstNode.range.start, document);
        const indentedContent = this.applyIndentation(content, indentation);

        const prefix = this.calculateNewlinePrefix(text, insertPosition, document, ensureNewlineBefore);

        const edit: WorkspaceEdit = {
            changes: {
                [document.uri.toString()]: [
                    {
                        range: Range.create(insertPosition, insertPosition),
                        newText: prefix + indentedContent
                    }
                ]
            }
        };

        return edit;
    }

    async serializeNode(node: AstNode, document: LangiumDocument): Promise<string> {
        return await this.serializer.serializeNode(node, document, this.serializer.guessFormattingOptions(document));
    }

    insertIntoScope(
        startNode: CstNode,
        endNode: CstNode,
        ensureNewlineBefore: boolean,
        text: string,
        document: LangiumDocument
    ): WorkspaceEdit {
        const { insertSpaces, tabSize } = this.serializer.guessFormattingOptions(document);
        const indentStep = insertSpaces ? " ".repeat(tabSize) : "\t";

        const openingIndentation = this.getIndentationForLine(startNode.range.start, document);
        const closingIndentation = this.getIndentationForLine(endNode.range.start, document);
        const contentIndentation = openingIndentation + indentStep;

        const indentedContent = contentIndentation + this.applyIndentation(text, contentIndentation);

        const onSameLine = startNode.range.start.line === endNode.range.start.line;

        // when the closing delimiter starts its line, the content goes before the delimiter's
        // line rather than between its indentation and the delimiter, so that no line holding
        // nothing but that indentation is left behind
        const atLineStart = !onSameLine && closingIndentation.length === endNode.range.start.character;
        const insertPosition: PositionType = atLineStart
            ? Position.create(endNode.range.start.line, 0)
            : endNode.range.start;
        const suffix = atLineStart ? "\n" : "\n" + closingIndentation;

        let prefix: string;
        if (onSameLine) {
            prefix = "\n";
        } else if (ensureNewlineBefore) {
            const docText = document.textDocument.getText();
            const endOffset = document.textDocument.offsetAt(insertPosition);
            const textBefore = docText.substring(0, endOffset);
            const hasBlankLineBefore = textBefore.endsWith("\n\n") || textBefore.endsWith("\r\n\r\n");
            prefix = hasBlankLineBefore ? "" : "\n";
        } else {
            prefix = "";
        }

        const uri = document.uri.toString();

        return {
            changes: {
                [uri]: [
                    {
                        range: { start: insertPosition, end: insertPosition },
                        newText: prefix + indentedContent + suffix
                    }
                ]
            }
        };
    }

    private applyIndentation(content: string, indentation: string): string {
        return content
            .split("\n")
            .map((line, index) => (index === 0 ? line : indentation + line))
            .join("\n");
    }

    private calculateNewlinePrefix(
        text: string,
        insertPosition: PositionType,
        document: LangiumDocument,
        ensureNewlineBefore: boolean
    ): string {
        if (!ensureNewlineBefore || text.length === 0) {
            return "";
        }

        const textAfterNode = text.substring(document.textDocument.offsetAt(insertPosition));

        const startsWithNewline = textAfterNode.startsWith("\n");
        const startsWithDoubleNewline = textAfterNode.startsWith("\n\n");

        if (!startsWithNewline) {
            return "\n\n";
        } else if (!startsWithDoubleNewline) {
            return "\n";
        }
        return "";
    }
}
