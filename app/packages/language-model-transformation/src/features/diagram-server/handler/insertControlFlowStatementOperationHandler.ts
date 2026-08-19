import {
    BaseOperationHandler,
    OperationHandlerCommand,
    sharedImport,
    computeInsertIds,
    buildIdChangeMetadataEdits,
    mergeMetadataEdits,
    type ModelIdProvider
} from "@mdeo/language-shared";
import { ModelIdProvider as ModelIdProviderKey } from "@mdeo/language-shared";
import type { MetadataEdits } from "@mdeo/language-shared";
import type { InsertSpecification } from "@mdeo/language-shared";
import type { Command, GModelElement } from "@eclipse-glsp/server";
import {
    InsertControlFlowStatementOperation,
    ModelTransformationElementType
} from "@mdeo/protocol-model-transformation";
import type { InsertControlFlowStatementKind } from "@mdeo/protocol-model-transformation";
import { EdgeAttachmentPosition, type ContextItem, type NodeLayoutMetadata } from "@mdeo/protocol-common";
import type { ContextActionRequestContext, ContextItemProvider, GEdge } from "@mdeo/language-shared";
import type { WorkspaceEdit } from "vscode-languageserver-types";
import type { Point } from "@eclipse-glsp/protocol";
import type { CstNode, AstNode } from "langium";
import {
    MatchStatement,
    IfMatchStatement,
    ForMatchStatement,
    WhileMatchStatement,
    UntilMatchStatement,
    IfExpressionStatement,
    WhileExpressionStatement,
    StopStatement,
    Pattern,
    IfMatchConditionAndBlock,
    ModelTransformation,
    StatementsScope,
    type BaseTransformationStatementType,
    type ForMatchStatementType,
    type IfMatchStatementType,
    type IfMatchConditionAndBlockType,
    type PatternType,
    type ModelTransformationType
} from "../../../grammar/modelTransformationTypes.js";

const { injectable, inject } = sharedImport("inversify");
const { Range } = sharedImport("vscode-languageserver-types");
const { AstUtils, GrammarUtils } = sharedImport("langium");

/**
 * Intermediate result of computing the workspace edit and insertion context
 * for a new control-flow statement.
 */
interface StatementInsertResult {
    /**
     * The workspace edit that inserts the statement text.
     */
    edit: WorkspaceEdit;
    /**
     * The AstNode container whose array property the statement will appear in after re-parsing.
     */
    container: AstNode;
    /**
     * The name of the array property on the container (typically {@code "statements"}).
     */
    property: string;
    /**
     * The index within the array at which the new statement will appear.
     */
    index: number;
}

/**
 * Handler for inserting control-flow statements from edge context actions.
 *
 * Positions newly inserted match-type statements at the midpoint of the edge
 * they are inserted on, using the {@link InsertSpecification} system to compute
 * the correct ids and any id shifts for existing statement nodes.
 */
@injectable()
export class InsertControlFlowStatementOperationHandler extends BaseOperationHandler implements ContextItemProvider {
    override readonly operationType = InsertControlFlowStatementOperation.KIND;

    @inject(ModelIdProviderKey)
    protected idProvider!: ModelIdProvider;

    /**
     * Creates a command that inserts a new control-flow statement on the selected edge.
     *
     * The insertion position is determined from {@link sourceNodeId} and {@link targetNodeId}
     * using the following priority order:
     * 1. If the target node maps to a transformation statement, insert before it.
     * 2. Otherwise, if the source node maps to a statement, insert after it.
     * 3. If the source is the graph start node, insert after the import statement.
     * 4. If the source is a merge node, find the containing structural statement and insert after it.
     *
     * For match-type statements, the newly created match node is positioned at the
     * midpoint of the edge. Id shifts of existing statement nodes are tracked and
     * included in the metadata edits.
     *
     * @param operation The insert-control-flow-statement operation
     * @returns A command wrapping the workspace edit, or `undefined` when the insertion
     *          position cannot be resolved
     */
    override async createCommand(operation: InsertControlFlowStatementOperation): Promise<Command | undefined> {
        const { sourceNodeId, targetNodeId, statementKind } = operation;

        if (sourceNodeId == undefined || targetNodeId == undefined) {
            return undefined;
        }

        const sourceModel = this.modelState.sourceModel as ModelTransformationType | undefined;
        if (sourceModel == undefined) {
            return undefined;
        }

        const newText = this.buildStatementText(statementKind);

        let insertResult: StatementInsertResult | undefined;

        // Self-loop edges arise from for-match statements with an empty do-block.
        if (sourceNodeId === targetNodeId) {
            insertResult = await this.computeSelfLoopInsertion(sourceNodeId, newText, sourceModel);
        } else {
            insertResult = await this.computeEdgeInsertion(sourceNodeId, targetNodeId, newText, sourceModel);
        }

        if (insertResult == undefined) {
            return undefined;
        }

        const metadata = this.computeInsertedStatementMetadata(operation, statementKind, insertResult);

        return new OperationHandlerCommand(this.modelState, insertResult.edit, metadata);
    }

    /**
     * Returns insertion context items for control-flow edges.
     *
     * @param element The selected element
     * @param _context Additional request context
     * @returns Context actions for this handler
     */
    getContextItems(element: GModelElement, _context: ContextActionRequestContext): ContextItem[] {
        if (element.type !== ModelTransformationElementType.EDGE_CONTROL_FLOW) {
            return [];
        }

        const statementKinds: { label: string; kind: InsertControlFlowStatementKind }[] = [
            { label: "Match", kind: "match" },
            { label: "For Match", kind: "for-match" },
            { label: "If Match", kind: "if-match" },
            { label: "While Match", kind: "while-match" },
            { label: "Until Match", kind: "until-match" },
            { label: "If", kind: "if" },
            { label: "While", kind: "while" },
            { label: "Stop", kind: "stop" },
            { label: "Kill", kind: "kill" }
        ];

        const edge = element as GEdge;

        return [
            {
                id: `insert-control-flow-${element.id}`,
                label: "Insert Statement",
                icon: "between-horizontal-start",
                sortString: "a",
                position: EdgeAttachmentPosition.MIDDLE,
                children: statementKinds.map((statementKind) => ({
                    id: `insert-control-flow-${element.id}-${statementKind.kind}`,
                    label: statementKind.label,
                    action: InsertControlFlowStatementOperation.create({
                        edgeId: element.id,
                        statementKind: statementKind.kind,
                        sourceNodeId: edge.sourceId,
                        targetNodeId: edge.targetId
                    })
                }))
            }
        ];
    }

    /**
     * Computes the insertion result for a non-self-loop edge.
     *
     * Priority:
     * 1. Target maps to a statement → insert before it.
     * 2. Source maps to a statement → insert after it.
     * 3. Source is the start node → insert after the import statement.
     * 4. Source is a merge node → insert after the containing structural statement.
     */
    private async computeEdgeInsertion(
        sourceNodeId: string,
        targetNodeId: string,
        newText: string,
        sourceModel: ModelTransformationType
    ): Promise<StatementInsertResult | undefined> {
        // Case 1: target maps to a statement
        const targetStmt = this.findOuterStatementForNodeId(targetNodeId);
        if (targetStmt?.$cstNode != undefined) {
            const prevCst = this.findInsertAfterCstForStatement(targetStmt);
            const edit =
                prevCst != undefined
                    ? await this.createInsertAfterNodeEdit(prevCst, newText)
                    : this.createInsertBeforeStatementEdit(targetStmt.$cstNode, newText);
            return {
                edit,
                container: targetStmt.$container!,
                property: (targetStmt.$containerProperty as string) ?? "statements",
                index: this.getStatementIndex(targetStmt)
            };
        }

        // Case 2: source maps to a statement
        const sourceStmt = this.findOuterStatementForNodeId(sourceNodeId);
        if (sourceStmt?.$cstNode != undefined) {
            const edit = await this.createInsertAfterNodeEdit(sourceStmt.$cstNode, newText);
            return {
                edit,
                container: sourceStmt.$container!,
                property: (sourceStmt.$containerProperty as string) ?? "statements",
                index: this.getStatementIndex(sourceStmt) + 1
            };
        }

        // Case 3: source is the start node
        if (sourceNodeId === "start") {
            const importCst = sourceModel.import?.$cstNode;
            if (importCst != undefined) {
                const edit = await this.createInsertAfterNodeEdit(importCst, newText);
                return { edit, container: sourceModel, property: "statements", index: 0 };
            }
        }

        // Case 4: source is a merge node
        const stmtIdFromMerge = this.extractStmtIdFromMergeNode(sourceNodeId);
        if (stmtIdFromMerge != undefined) {
            const containingStmt = this.findStatementById(stmtIdFromMerge, sourceModel);
            if (containingStmt?.$cstNode != undefined) {
                const edit = await this.createInsertAfterNodeEdit(containingStmt.$cstNode, newText);
                return {
                    edit,
                    container: containingStmt.$container!,
                    property: (containingStmt.$containerProperty as string) ?? "statements",
                    index: this.getStatementIndex(containingStmt) + 1
                };
            }
        }

        return undefined;
    }

    /**
     * Computes the insertion result for a self-loop edge.
     *
     * Self-loop edges are generated for `for-match` statements whose do-block is empty.
     * The new statement is inserted directly inside the do-block scope.
     *
     * @param loopNodeId The match-node ID — both source and target of the self-loop.
     * @param newText The source text to insert.
     * @param sourceModel The transformation root AST node.
     * @returns The insertion result, or `undefined` when the for-match
     *          statement or its do-block CST cannot be located.
     */
    private async computeSelfLoopInsertion(
        loopNodeId: string,
        newText: string,
        sourceModel: ModelTransformationType
    ): Promise<StatementInsertResult | undefined> {
        let forMatchStmt: ForMatchStatementType | undefined;

        const outerStmt = this.findOuterStatementForNodeId(loopNodeId);
        if (outerStmt != undefined && this.reflection.isInstance(outerStmt, ForMatchStatement)) {
            forMatchStmt = outerStmt as ForMatchStatementType;
        }

        // Fall back: for the synthetic no-pattern node (id = "${stmtId}_no-pattern"),
        // locate the ForMatchStatement directly via the registry ID encoded in the node ID.
        if (forMatchStmt == undefined) {
            const noPatternSuffix = "_no-pattern";
            if (loopNodeId.endsWith(noPatternSuffix)) {
                const stmtId = loopNodeId.slice(0, -noPatternSuffix.length);
                const found = this.findStatementById(stmtId, sourceModel);
                if (found != undefined && this.reflection.isInstance(found, ForMatchStatement)) {
                    forMatchStmt = found as ForMatchStatementType;
                }
            }
        }

        if (forMatchStmt == undefined) {
            return undefined;
        }

        const doBlockCst = forMatchStmt.doBlock?.$cstNode;
        if (doBlockCst == undefined) {
            return undefined;
        }

        const openBrace = GrammarUtils.findNodeForKeyword(doBlockCst, "{");
        const closeBrace = GrammarUtils.findNodeForKeyword(doBlockCst, "}");
        if (openBrace == undefined || closeBrace == undefined) {
            return undefined;
        }

        const edit = this.insertIntoScope(openBrace, closeBrace, false, newText);
        return { edit, container: forMatchStmt.doBlock!, property: "statements", index: 0 };
    }

    /**
     * Computes metadata edits for the newly inserted statement.
     *
     * For match-type statements, the new match node is positioned at the midpoint
     * of the edge. Also detects id shifts in existing nodes and produces rename edits.
     *
     * @param operation The operation containing the edge ids
     * @param statementKind The kind of statement being inserted
     * @param insertResult The insertion context (container, property, index)
     * @returns Metadata edits, or `undefined` if no metadata is needed
     */
    private computeInsertedStatementMetadata(
        operation: InsertControlFlowStatementOperation,
        statementKind: InsertControlFlowStatementKind,
        insertResult: StatementInsertResult
    ): MetadataEdits | undefined {
        const statementType = this.getStatementAstType(statementKind);

        // Create a dummy statement node for InsertSpecification.
        const stmtDummy = { $type: statementType } as unknown as AstNode;
        const spec: InsertSpecification = {
            container: insertResult.container,
            property: insertResult.property,
            elements: [stmtDummy],
            index: insertResult.index
        };

        const { insertedIds, idChanges } = computeInsertIds(this.modelState.sourceModel!, this.idProvider, [spec]);

        // Build rename edits for any pre-existing nodes whose ids shifted.
        const renameEdits = buildIdChangeMetadataEdits(idChanges, this.modelState.metadata);

        // For non-match kinds (if, while, stop, kill), only emit rename edits.
        if (!this.isMatchKind(statementKind)) {
            return renameEdits;
        }

        // Derive the Pattern id from the statement id.
        // The Pattern receives the same hierarchical name as its parent statement.
        const stmtId = insertedIds.get(stmtDummy);
        if (stmtId == undefined) {
            return renameEdits;
        }
        const typePrefix = statementType + "_";
        const stmtName = stmtId.startsWith(typePrefix) ? stmtId.slice(typePrefix.length) : stmtId;
        const patternId = `Pattern_${stmtName}`;

        // Position the new match node at the edge midpoint.
        const position = this.computeEdgeMidpoint(operation.sourceNodeId, operation.targetNodeId);
        if (position == undefined) {
            return renameEdits;
        }

        const nodeEdits: MetadataEdits = {
            nodes: {
                [patternId]: {
                    type: ModelTransformationElementType.NODE_MATCH,
                    meta: { position } as NodeLayoutMetadata
                }
            }
        };

        return mergeMetadataEdits(renameEdits, nodeEdits);
    }

    /**
     * Computes the midpoint between the source and target nodes of the edge
     * using position data from the metadata store.
     *
     * @returns The midpoint, or `undefined` if positions are not available
     */
    private computeEdgeMidpoint(sourceNodeId: string | undefined, targetNodeId: string | undefined): Point | undefined {
        if (sourceNodeId == undefined || targetNodeId == undefined) {
            return undefined;
        }

        const sourceMeta = this.modelState.metadata.nodes[sourceNodeId];
        const targetMeta = this.modelState.metadata.nodes[targetNodeId];
        const sourcePos = (sourceMeta?.meta as NodeLayoutMetadata | undefined)?.position;
        const targetPos = (targetMeta?.meta as NodeLayoutMetadata | undefined)?.position;

        if (sourcePos != undefined && targetPos != undefined) {
            return {
                x: (sourcePos.x + targetPos.x) / 2,
                y: (sourcePos.y + targetPos.y) / 2
            };
        }

        return undefined;
    }

    /**
     * Maps a statement kind to the corresponding AST node `$type` string.
     */
    private getStatementAstType(kind: InsertControlFlowStatementKind): string {
        switch (kind) {
            case "match":
                return MatchStatement.name;
            case "for-match":
                return ForMatchStatement.name;
            case "if-match":
                return IfMatchStatement.name;
            case "while-match":
                return WhileMatchStatement.name;
            case "until-match":
                return UntilMatchStatement.name;
            case "if":
                return IfExpressionStatement.name;
            case "while":
                return WhileExpressionStatement.name;
            case "stop":
                return StopStatement.name;
            case "kill":
                return StopStatement.name;
        }
    }

    /**
     * Returns `true` when the statement kind produces a match node that benefits
     * from position metadata (all match-variant kinds).
     */
    private isMatchKind(kind: InsertControlFlowStatementKind): boolean {
        return (
            kind === "match" ||
            kind === "for-match" ||
            kind === "if-match" ||
            kind === "while-match" ||
            kind === "until-match"
        );
    }

    /**
     * Returns the index of a statement within its parent container's statements array.
     */
    private getStatementIndex(stmt: AstNode): number {
        const container = stmt.$container;
        if (container == undefined) return 0;
        const prop = stmt.$containerProperty as string;
        if (prop == undefined) return 0;
        const arr = (container as unknown as Record<string, unknown>)[prop];
        if (Array.isArray(arr)) {
            const idx = arr.indexOf(stmt);
            return idx >= 0 ? idx : 0;
        }
        return 0;
    }

    /**
     * Returns the placeholder text for a new statement of the given kind.
     * extra leading indentation (the workspace-edit utilities add the correct
     * indentation when inserting the text).
     *
     * @param kind The statement kind to generate text for
     * @returns The placeholder source text
     */
    private buildStatementText(kind: InsertControlFlowStatementKind): string {
        switch (kind) {
            case "match":
                return "match {}";
            case "for-match":
                return "for match {} do {}";
            case "if-match":
                return "if match {} then {\n} else {\n}";
            case "while-match":
                return "while match {} do {}";
            case "until-match":
                return "until match {} do {}";
            case "if":
                return "if (true) {\n} else {\n}";
            case "while":
                return "while (false) {}";
            case "stop":
                return "stop";
            case "kill":
                return "kill";
        }
    }

    /**
     * Tries to map a CFG node ID to its enclosing transformation statement.
     * Returns `undefined` for synthetic nodes (start, end, split, merge) that
     * do not correspond directly to an AST statement.
     *
     * @param nodeId The CFG node ID
     * @returns The enclosing transformation statement, or `undefined`
     */
    private findOuterStatementForNodeId(nodeId: string): BaseTransformationStatementType | undefined {
        const element = this.modelState.index.find(nodeId);
        if (element == undefined) {
            return undefined;
        }
        const astNode = this.index.getAstNode(element);
        if (astNode == undefined) {
            return undefined;
        }
        return this.toOuterStatement(astNode);
    }

    /**
     * Resolves an AST node to its enclosing transformation statement.
     *
     * Handles two cases:
     * - The node is itself a statement (e.g. `MatchStatement`, `IfExpressionStatement`).
     * - The node is a `Pattern` embedded inside an if-match / for-match / while-match /
     *   until-match statement; the method then ascends to the containing statement.
     *
     * @param astNode An AST node returned by {@link GModelIndex.getAstNode}
     * @returns The enclosing transformation statement, or `undefined`
     */
    private toOuterStatement(astNode: AstNode): BaseTransformationStatementType | undefined {
        if (
            this.reflection.isInstance(astNode, MatchStatement) ||
            this.reflection.isInstance(astNode, IfMatchStatement) ||
            this.reflection.isInstance(astNode, ForMatchStatement) ||
            this.reflection.isInstance(astNode, WhileMatchStatement) ||
            this.reflection.isInstance(astNode, UntilMatchStatement) ||
            this.reflection.isInstance(astNode, IfExpressionStatement) ||
            this.reflection.isInstance(astNode, WhileExpressionStatement) ||
            this.reflection.isInstance(astNode, StopStatement)
        ) {
            return astNode as BaseTransformationStatementType;
        }

        if (this.reflection.isInstance(astNode, Pattern)) {
            const patternNode = astNode as PatternType;
            const container = patternNode.$container;
            if (this.reflection.isInstance(container, IfMatchConditionAndBlock)) {
                return (container as IfMatchConditionAndBlockType).$container as IfMatchStatementType;
            }
            return container as BaseTransformationStatementType;
        }

        return undefined;
    }

    /**
     * Returns the CST node to be used as the "previous item" reference when inserting
     * new content before `targetStmt`.
     *
     * - At the top level: returns the previous statement's CST, or the import's CST when
     *   `targetStmt` is the very first top-level statement.
     * - Inside a nested scope: returns the previous statement's CST when one exists,
     *   otherwise `undefined` (the caller falls back to
     *   {@link createInsertBeforeStatementEdit}).
     *
     * @param targetStmt The statement before which insertion is desired
     * @param sourceModel The transformation root AST node
     * @returns The CST node to insert after, or `undefined`
     */
    private findInsertAfterCstForStatement(targetStmt: BaseTransformationStatementType): CstNode | undefined {
        const parent = targetStmt.$container;

        if (this.reflection.isInstance(parent, ModelTransformation)) {
            const idx = parent.statements.indexOf(targetStmt);
            if (idx > 0) {
                return parent.statements[idx - 1]!.$cstNode;
            }
            return parent.import?.$cstNode;
        }

        if (this.reflection.isInstance(parent, StatementsScope)) {
            const idx = parent.statements.indexOf(targetStmt);
            if (idx > 0) {
                return parent.statements[idx - 1]!.$cstNode;
            }
            return undefined;
        }

        return undefined;
    }

    /**
     * Creates a workspace edit that inserts `text` on a new line immediately before
     * the line on which `targetCst` starts, using the same indentation as the target
     * statement.  A blank line is added after the new text to separate it visually.
     *
     * @param targetCst The CST node whose line the new text should precede
     * @param text The source text to insert (first line should carry no leading indentation)
     * @returns The workspace edit
     */
    private createInsertBeforeStatementEdit(targetCst: CstNode, text: string): WorkspaceEdit {
        const document = this.getSourceDocument();
        const uri = document.uri.toString();

        const indentation = this.getIndentationForLine(targetCst.range.start);
        const indentedLines = text.split("\n").map((line) => indentation + line);
        const indentedText = indentedLines.join("\n");

        const insertPos = { line: targetCst.range.start.line, character: 0 };
        return {
            changes: {
                [uri]: [
                    {
                        range: Range.create(insertPos, insertPos),
                        newText: indentedText + "\n\n"
                    }
                ]
            }
        };
    }

    /**
     * Scans all AST nodes of the source model looking for a transformation statement
     * whose registry ID equals `stmtId`.
     *
     * @param stmtId The registry ID to search for
     * @param sourceModel The transformation root AST node
     * @returns The matching statement, or `undefined`
     */
    private findStatementById(
        stmtId: string,
        sourceModel: ModelTransformationType
    ): BaseTransformationStatementType | undefined {
        for (const node of AstUtils.streamAllContents(sourceModel)) {
            if (this.index.getElementId(node) === stmtId) {
                return this.toOuterStatement(node);
            }
        }
        return undefined;
    }

    /**
     * If `nodeId` ends with `"_merge"`, returns the base statement ID (the registry ID
     * of the structural statement that owns the merge node).  Otherwise returns `undefined`.
     *
     * @param nodeId A CFG node ID
     * @returns The base statement ID, or `undefined`
     */
    private extractStmtIdFromMergeNode(nodeId: string): string | undefined {
        const suffix = "_merge";
        if (nodeId.endsWith(suffix)) {
            return nodeId.slice(0, -suffix.length);
        }
        return undefined;
    }
}
