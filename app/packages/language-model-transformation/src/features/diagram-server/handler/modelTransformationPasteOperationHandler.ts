import type { PasteOperation } from "@eclipse-glsp/protocol";
import type { Point } from "@eclipse-glsp/protocol";
import type { WorkspaceEdit } from "vscode-languageserver-types";
import type { AstNode, CompositeCstNode } from "langium";
import {
    BasePasteOperationHandler,
    sharedImport,
    computeInsertionMetadata,
    type PasteInsertionResult,
    type ClipboardEdgeMetadata,
    type InsertedElementMetadata,
    type InsertSpecification,
    type ReferenceResolutionContext
} from "@mdeo/language-shared";
import type { MetadataEdits } from "@mdeo/language-shared";
import {
    MatchStatement,
    IfMatchStatement,
    WhileMatchStatement,
    UntilMatchStatement,
    ForMatchStatement,
    Pattern,
    PatternApplicationCondition,
    PatternObjectInstance,
    PatternObjectInstanceReference,
    PatternObjectInstanceDelete,
    PatternLink,
    type PatternType,
    type PatternObjectInstanceType,
    type PatternLinkType,
    type MatchStatementType,
    type IfMatchStatementType,
    type WhileMatchStatementType,
    type UntilMatchStatementType,
    type ForMatchStatementType
} from "../../../grammar/modelTransformationTypes.js";
import { ModelTransformationElementType } from "@mdeo/protocol-model-transformation";

const { injectable } = sharedImport("inversify");

/** $type values that represent pattern elements (can be inserted inside a match). */
const PATTERN_ELEMENT_TYPES = new Set([
    PatternObjectInstance.name,
    PatternObjectInstanceReference.name,
    PatternObjectInstanceDelete.name,
    PatternLink.name
]);

/** $type values that represent match-type statements (inserted at file level). */
const MATCH_STATEMENT_TYPES = new Set([
    MatchStatement.name,
    IfMatchStatement.name,
    WhileMatchStatement.name,
    UntilMatchStatement.name,
    ForMatchStatement.name
]);

/**
 * Model-transformation-specific paste operation handler.
 *
 * <p>Handles three paste scenarios:
 * <ol>
 *   <li><b>Match node selected + pattern elements in clipboard</b>: pattern elements
 *       are inserted into the selected match node's pattern block.</li>
 *   <li><b>No selection or only match statements in clipboard</b>: match statements
 *       are appended at the end of the file.</li>
 *   <li><b>Only pattern elements + no match node selected</b>: a new match statement
 *       wrapping all pattern elements is appended at the end of the file.</li>
 * </ol>
 *
 * <p>{@link PatternLink} nodes whose source or target object reference cannot be
 * resolved are silently dropped.
 */
@injectable()
export class ModelTransformationPasteOperationHandler extends BasePasteOperationHandler {
    /**
     * Resolves a cross-reference. For pattern elements, the targets are
     * PatternObjectInstance nodes. Looks first in the co-pasted nodes, then
     * in the current pattern (when pasting into a selected match block) or the
     * entire source model.
     *
     * Instances declared in an application condition block are not candidates: their names are
     * visible inside that block only, so a pasted element could not name them. A link that ends
     * on one is dropped by {@link validateNode} rather than pasted as a broken reference.
     */
    protected override resolveReference(
        refText: string,
        { allPastedNodes }: ReferenceResolutionContext
    ): AstNode | undefined {
        const pasted = allPastedNodes.find(
            (n) => n.$type === PatternObjectInstance.name && (n as PatternObjectInstanceType).name === refText
        );
        if (pasted) {
            return pasted;
        }
        // Search the entire source model tree for a matching PatternObjectInstance.
        return this.findPatternInstance(this.modelState.sourceModel!, refText);
    }

    private findPatternInstance(node: AstNode, name: string): AstNode | undefined {
        if (node.$type === PatternApplicationCondition.name) {
            return undefined;
        }
        if (node.$type === PatternObjectInstance.name && (node as PatternObjectInstanceType).name === name) {
            return node;
        }
        for (const [key, value] of Object.entries(node)) {
            if (key.startsWith("$")) continue;
            if (Array.isArray(value)) {
                for (const item of value) {
                    if (item && typeof item === "object" && "$type" in item) {
                        const found = this.findPatternInstance(item as AstNode, name);
                        if (found) return found;
                    }
                }
            } else if (value && typeof value === "object" && "$type" in value) {
                const found = this.findPatternInstance(value as AstNode, name);
                if (found) return found;
            }
        }
        return undefined;
    }

    /**
     * Validates a pasted node after reference resolution.
     * {@link PatternLink} nodes are dropped when either end's object reference
     * is unresolved.
     */
    protected override validateNode(node: AstNode): AstNode | undefined {
        if (node.$type === PatternLink.name) {
            const link = node as PatternLinkType;
            if (!link.source.object.ref || !link.target.object.ref) {
                return undefined;
            }
        }
        return node;
    }

    protected override async insertNodes(
        astNodes: AstNode[],
        operation: PasteOperation,
        offsetPositions: Map<string, Point>,
        offsetEdgeData: ClipboardEdgeMetadata[]
    ): Promise<PasteInsertionResult | undefined> {
        const rootCstNode = this.modelState.sourceModel?.$cstNode;
        if (!rootCstNode) {
            return undefined;
        }

        const patternElements = astNodes.filter((n) => PATTERN_ELEMENT_TYPES.has(n.$type));
        const matchStatements = astNodes.filter((n) => MATCH_STATEMENT_TYPES.has(n.$type));

        const selectedPattern = this.findSelectedPattern(operation.editorContext.selectedElementIds);

        if (selectedPattern && patternElements.length > 0) {
            return this.insertPatternElementsIntoMatch(
                selectedPattern,
                patternElements,
                matchStatements,
                offsetPositions,
                offsetEdgeData
            );
        }

        if (matchStatements.length > 0 && (patternElements.length === 0 || !selectedPattern)) {
            return this.insertMatchStatementsAtEnd(matchStatements, patternElements, offsetPositions);
        }

        if (patternElements.length > 0 && !selectedPattern) {
            return this.insertPatternElementsAsNewMatch(patternElements, offsetPositions);
        }

        return undefined;
    }

    /**
     * Finds the Pattern AST node for a selected match node from the selection IDs.
     * If the selection contains a single match node (or elements inside one match node),
     * returns that match node's pattern.
     */
    private findSelectedPattern(selectedIds: string[]): PatternType | undefined {
        if (selectedIds.length === 0) {
            return undefined;
        }

        for (const id of selectedIds) {
            const gmodelElement = this.modelState.index.get(id);
            if (!gmodelElement) {
                continue;
            }

            if (gmodelElement.type === ModelTransformationElementType.NODE_MATCH) {
                const astNode = this.index.getAstNode(gmodelElement);
                if (astNode) {
                    const pattern = this.extractPattern(astNode);
                    if (pattern) {
                        return pattern;
                    }
                }
            }

            let parent = gmodelElement.parent;
            while (parent) {
                if (parent.type === ModelTransformationElementType.NODE_MATCH) {
                    const parentAstNode = this.index.getAstNode(parent);
                    if (parentAstNode) {
                        const pattern = this.extractPattern(parentAstNode);
                        if (pattern) {
                            return pattern;
                        }
                    }
                }
                parent = parent.parent;
            }
        }

        return undefined;
    }

    /**
     * Extracts the Pattern from a statement AST node.
     */
    private extractPattern(node: AstNode): PatternType | undefined {
        if (this.reflection.isInstance(node, Pattern)) {
            return node as PatternType;
        }
        if (this.reflection.isInstance(node, MatchStatement)) {
            return (node as MatchStatementType).pattern;
        }
        if (this.reflection.isInstance(node, IfMatchStatement)) {
            return (node as IfMatchStatementType).ifBlock?.pattern;
        }
        if (this.reflection.isInstance(node, WhileMatchStatement)) {
            return (node as WhileMatchStatementType).pattern;
        }
        if (this.reflection.isInstance(node, UntilMatchStatement)) {
            return (node as UntilMatchStatementType).pattern;
        }
        if (this.reflection.isInstance(node, ForMatchStatement)) {
            return (node as ForMatchStatementType).pattern;
        }
        return undefined;
    }

    /**
     * Case 1: Insert pattern elements into a selected match node.
     * Also appends any match statements at the end of the file.
     */
    private async insertPatternElementsIntoMatch(
        pattern: PatternType,
        patternElements: AstNode[],
        matchStatements: AstNode[],
        offsetPositions: Map<string, Point>,
        offsetEdgeData: ClipboardEdgeMetadata[]
    ): Promise<PasteInsertionResult | undefined> {
        const patternCst = pattern.$cstNode as CompositeCstNode | undefined;
        if (!patternCst) {
            return undefined;
        }
        const content = patternCst.content;
        const openBrace = content[0];
        const closeBrace = content[content.length - 1];
        if (!openBrace || !closeBrace) {
            return undefined;
        }

        const hasContent = (pattern.elements?.length ?? 0) > 0;
        const edits: WorkspaceEdit[] = [];

        for (let i = 0; i < patternElements.length; i++) {
            const serializedText = await this.serializeNode(patternElements[i] as any);
            const edit = this.insertIntoScope(openBrace, closeBrace, hasContent || i > 0, serializedText);
            edits.push(edit);
        }

        if (matchStatements.length > 0) {
            const matchEdits = await this.insertMatchStatementsAtEndEdits(matchStatements);
            edits.push(...matchEdits);
        }

        if (edits.length === 0) {
            return undefined;
        }

        const insertSpec: InsertSpecification = {
            container: pattern,
            property: "elements",
            elements: patternElements
        };

        const instancesByName = new Map<string, AstNode>();
        const insertedElements: InsertedElementMetadata[] = [];

        for (const node of patternElements) {
            if (node.$type === PatternObjectInstance.name) {
                const instance = node as PatternObjectInstanceType;
                if (instance.name) {
                    instancesByName.set(instance.name, node);
                }
                const position = instance.name ? offsetPositions.get(instance.name) : undefined;
                if (position) {
                    insertedElements.push(
                        this.buildNodeElementMetadata(
                            node,
                            ModelTransformationElementType.NODE_PATTERN_INSTANCE,
                            position
                        )
                    );
                }
            }
        }

        for (const node of patternElements) {
            if (node.$type === PatternLink.name) {
                const link = node as PatternLinkType;
                // Both ends guaranteed resolved by validateNode.
                const edgeMeta = this.findEdgeMetadata(
                    link.source.object.ref!.name,
                    link.source.property?.$refText ?? "",
                    link.target.object.ref!.name,
                    link.target.property?.$refText ?? "",
                    offsetEdgeData
                );
                if (edgeMeta) {
                    insertedElements.push(
                        this.buildEdgeElementMetadata(
                            node,
                            ModelTransformationElementType.EDGE_PATTERN_LINK,
                            link.source.object.ref! as AstNode,
                            link.target.object.ref! as AstNode,
                            edgeMeta
                        )
                    );
                }
            }
        }

        const metadataEdits = computeInsertionMetadata(
            this.modelState.sourceModel!,
            this.idProvider,
            [insertSpec],
            insertedElements,
            this.modelState.metadata
        );

        return {
            workspaceEdit: this.mergeWorkspaceEdits(edits),
            metadataEdits
        };
    }

    /**
     * Case 2: Insert match statements at the end of the file, plus wrap any orphan
     * pattern elements in a new match.
     */
    private async insertMatchStatementsAtEnd(
        matchStatements: AstNode[],
        patternElements: AstNode[],
        _offsetPositions: Map<string, Point>
    ): Promise<PasteInsertionResult | undefined> {
        const rootCstNode = this.modelState.sourceModel?.$cstNode;
        if (!rootCstNode) {
            return undefined;
        }

        const document = this.modelState.sourceModel?.$document;
        const isEmpty = document?.textDocument.getText().trim().length === 0;

        const edits: WorkspaceEdit[] = [];

        for (const node of matchStatements) {
            const serializedText = await this.serializeNode(node as any);
            const edit = await this.createInsertAfterNodeEdit(
                rootCstNode,
                serializedText,
                !isEmpty || edits.length > 0
            );
            edits.push(edit);
        }

        if (patternElements.length > 0) {
            const wrappedText = await this.buildWrappedMatchText(patternElements);
            const edit = await this.createInsertAfterNodeEdit(rootCstNode, wrappedText, !isEmpty || edits.length > 0);
            edits.push(edit);
        }

        if (edits.length === 0) {
            return undefined;
        }

        const metadataEdits: MetadataEdits | undefined = undefined;

        return {
            workspaceEdit: this.mergeWorkspaceEdits(edits),
            metadataEdits
        };
    }

    /**
     * Case 3: Only pattern elements, no match selected → create a new match statement
     * wrapping all elements.
     */
    private async insertPatternElementsAsNewMatch(
        patternElements: AstNode[],
        _offsetPositions: Map<string, Point>
    ): Promise<PasteInsertionResult | undefined> {
        const rootCstNode = this.modelState.sourceModel?.$cstNode;
        if (!rootCstNode) {
            return undefined;
        }

        const document = this.modelState.sourceModel?.$document;
        const isEmpty = document?.textDocument.getText().trim().length === 0;

        const wrappedText = await this.buildWrappedMatchText(patternElements);
        const edit = await this.createInsertAfterNodeEdit(rootCstNode, wrappedText, !isEmpty);

        return {
            workspaceEdit: edit,
            metadataEdits: undefined
        };
    }

    /**
     * Builds match statement text that wraps the given pattern elements.
     */
    private async buildWrappedMatchText(patternElements: AstNode[]): Promise<string> {
        const elementTexts: string[] = [];
        for (const node of patternElements) {
            const text = await this.serializeNode(node as any);
            elementTexts.push(text);
        }

        if (elementTexts.length === 0) {
            return "match {}";
        }

        const indented = elementTexts.map((t) => "    " + t.split("\n").join("\n    ")).join("\n");
        return `match {\n${indented}\n}`;
    }

    /**
     * Creates workspace edits for inserting match statements at the end of the file.
     */
    private async insertMatchStatementsAtEndEdits(matchStatements: AstNode[]): Promise<WorkspaceEdit[]> {
        const rootCstNode = this.modelState.sourceModel?.$cstNode;
        if (!rootCstNode) {
            return [];
        }

        const document = this.modelState.sourceModel?.$document;
        const isEmpty = document?.textDocument.getText().trim().length === 0;

        const edits: WorkspaceEdit[] = [];
        for (const node of matchStatements) {
            const serializedText = await this.serializeNode(node as any);
            const edit = await this.createInsertAfterNodeEdit(
                rootCstNode,
                serializedText,
                !isEmpty || edits.length > 0
            );
            edits.push(edit);
        }
        return edits;
    }
}
