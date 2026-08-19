import { BaseOperationHandler, OperationHandlerCommand, sharedImport } from "@mdeo/language-shared";
import type { Command, GModelElement } from "@eclipse-glsp/server";
import {
    ConvertMatchNodeOperation,
    ModelTransformationElementType,
    type MatchNodeConversionKind
} from "@mdeo/protocol-model-transformation";
import type { ContextActionRequestContext, ContextItemProvider } from "@mdeo/language-shared";
import type { ContextItem } from "@mdeo/protocol-common";
import {
    MatchStatement,
    WhileMatchStatement,
    UntilMatchStatement,
    ForMatchStatement,
    IfMatchConditionAndBlock,
    Pattern,
    type MatchStatementType,
    type IfMatchStatementType,
    type WhileMatchStatementType,
    type UntilMatchStatementType,
    type ForMatchStatementType,
    type IfMatchConditionAndBlockType,
    type PatternType
} from "../../../grammar/modelTransformationTypes.js";
import type { CstNode } from "langium";
import type { WorkspaceEdit } from "vscode-languageserver-types";

const { injectable } = sharedImport("inversify");
const { GrammarUtils } = sharedImport("langium");
const { TextEdit } = sharedImport("vscode-languageserver-types");

/**
 * Handler for converting regular match nodes into other match statement forms.
 */
@injectable()
export class ConvertMatchNodeOperationHandler extends BaseOperationHandler implements ContextItemProvider {
    override readonly operationType = ConvertMatchNodeOperation.KIND;

    /**
     * Creates a command that converts a match-node statement into the requested form by applying
     * minimal text edits — inserting or replacing only the prefix keyword and suffix block,
     * without re-serializing the pattern content.
     *
     * The following conversions are supported (in both directions and between any two forms):
     * - `match {}` ↔ `for match {} do {}`
     * - `match {}` ↔ `while match {} do {}`
     * - `match {}` ↔ `until match {} do {}`
     * - `match {}` ↔ `if match {} then {}`
     *
     * @param operation The convert-match-node operation containing the node ID and target kind.
     * @returns A command applying the workspace edit, or `undefined` when the element cannot be
     *          resolved, is already in the target form, or the CST nodes required for editing are
     *          unavailable.
     */
    override async createCommand(operation: ConvertMatchNodeOperation): Promise<Command | undefined> {
        const element = this.modelState.index.find(operation.nodeId);
        if (element == undefined) {
            return undefined;
        }
        const astNode = this.index.getAstNode(element);
        if (astNode == undefined) {
            return undefined;
        }

        // Determine the current kind, the statement CST node, and the pattern CST node.
        // For MatchStatement, the element ID equals the statement ID, so getAstNode returns the
        // statement itself. For all other match forms, the element ID equals the inner Pattern ID.
        let currentKind: MatchNodeConversionKind;
        let statementCst: CstNode;
        let patternCst: CstNode;

        if (this.reflection.isInstance(astNode, MatchStatement)) {
            const stmt = astNode as MatchStatementType;
            if (stmt.$cstNode == undefined || stmt.pattern?.$cstNode == undefined) {
                return undefined;
            }
            currentKind = "match";
            statementCst = stmt.$cstNode;
            patternCst = stmt.pattern.$cstNode;
        } else if (this.reflection.isInstance(astNode, Pattern)) {
            const patternNode = astNode as PatternType;
            if (patternNode.$cstNode == undefined) {
                return undefined;
            }
            patternCst = patternNode.$cstNode;

            const container = patternNode.$container;
            if (this.reflection.isInstance(container, ForMatchStatement)) {
                const stmt = container as ForMatchStatementType;
                if (stmt.$cstNode == undefined) {
                    return undefined;
                }
                currentKind = "for-match";
                statementCst = stmt.$cstNode;
            } else if (this.reflection.isInstance(container, WhileMatchStatement)) {
                const stmt = container as WhileMatchStatementType;
                if (stmt.$cstNode == undefined) {
                    return undefined;
                }
                currentKind = "while-match";
                statementCst = stmt.$cstNode;
            } else if (this.reflection.isInstance(container, UntilMatchStatement)) {
                const stmt = container as UntilMatchStatementType;
                if (stmt.$cstNode == undefined) {
                    return undefined;
                }
                currentKind = "until-match";
                statementCst = stmt.$cstNode;
            } else if (this.reflection.isInstance(container, IfMatchConditionAndBlock)) {
                const ifBlock = container as IfMatchConditionAndBlockType;
                const ifStmt = ifBlock.$container as IfMatchStatementType | undefined;
                if (ifStmt?.$cstNode == undefined) {
                    return undefined;
                }
                currentKind = "if-match";
                statementCst = ifStmt.$cstNode;
            } else {
                return undefined;
            }
        } else {
            return undefined;
        }

        if (currentKind === operation.targetKind) {
            return undefined;
        }

        // Locate the `match` keyword within the statement's CST to split prefix from pattern.
        const matchKeyword = GrammarUtils.findNodeForKeyword(statementCst, "match");
        if (matchKeyword == undefined) {
            return undefined;
        }

        const { newPrefix, newSuffix } = this.conversionTexts(operation.targetKind);
        const uri = this.getSourceDocument().uri.toString();

        // The prefix range spans from the statement start to the match keyword start.
        // For plain `match`, this is an empty range (insert). For others it covers the keyword(s).
        const prefixRange = { start: statementCst.range.start, end: matchKeyword.range.start };

        // The suffix range spans from the end of the pattern block to the end of the statement.
        // For plain `match`, this is an empty range (insert). For others it covers ` do/then {...}`.
        const suffixRange = { start: patternCst.range.end, end: statementCst.range.end };

        const workspaceEdit: WorkspaceEdit = {
            changes: {
                [uri]: [TextEdit.replace(prefixRange, newPrefix), TextEdit.replace(suffixRange, newSuffix)]
            }
        };

        return new OperationHandlerCommand(this.modelState, workspaceEdit, undefined);
    }

    /**
     * Returns the prefix keyword text and suffix block text for the given match node target kind.
     * The prefix is inserted before the `match` keyword; the suffix is inserted after the closing
     * brace of the pattern block.
     *
     * @param targetKind The desired match statement form.
     * @returns An object with `newPrefix` and `newSuffix` strings to replace the existing prefix
     *          and suffix with.
     */
    private conversionTexts(targetKind: MatchNodeConversionKind): { newPrefix: string; newSuffix: string } {
        switch (targetKind) {
            case "match":
                return { newPrefix: "", newSuffix: "" };
            case "for-match":
                return { newPrefix: "for ", newSuffix: " do {}" };
            case "while-match":
                return { newPrefix: "while ", newSuffix: " do {}" };
            case "until-match":
                return { newPrefix: "until ", newSuffix: " do {}" };
            case "if-match":
                return { newPrefix: "if ", newSuffix: " then {}" };
        }
    }

    /**
     * Returns conversion context items for match nodes.
     *
     * @param element The selected element
     * @param _context Additional request context
     * @returns Context actions for this handler
     */
    getContextItems(element: GModelElement, _context: ContextActionRequestContext): ContextItem[] {
        if (element.type !== ModelTransformationElementType.NODE_MATCH) {
            return [];
        }

        const options = [
            { label: "Match", targetKind: "match" as const },
            { label: "If Match", targetKind: "if-match" as const },
            { label: "For Match", targetKind: "for-match" as const },
            { label: "While Match", targetKind: "while-match" as const },
            { label: "Until Match", targetKind: "until-match" as const }
        ];

        return [
            {
                id: `convert-match-node-${element.id}`,
                label: "Convert Match Node",
                icon: "settings-2",
                sortString: "b",
                children: options.map((option) => ({
                    id: `convert-match-node-${element.id}-${option.targetKind}`,
                    label: option.label,
                    action: ConvertMatchNodeOperation.create({
                        nodeId: element.id,
                        targetKind: option.targetKind
                    })
                }))
            }
        ];
    }
}
