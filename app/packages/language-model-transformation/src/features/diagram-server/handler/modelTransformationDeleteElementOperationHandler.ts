import type { DeleteElementOperation, GModelElement } from "@eclipse-glsp/server";
import { BaseDeleteElementOperationHandler, type DeleteOperationResult, sharedImport } from "@mdeo/language-shared";
import {
    Pattern,
    PatternApplicationCondition,
    PatternLink,
    PatternObjectInstance,
    PatternObjectInstanceReference,
    PatternObjectInstanceDelete,
    MatchStatement,
    IfMatchStatement,
    WhileMatchStatement,
    UntilMatchStatement,
    ForMatchStatement,
    IfExpressionStatement,
    WhileExpressionStatement,
    BaseTransformationStatement,
    IfMatchConditionAndBlock,
    type PatternType,
    type PatternLinkType,
    type PatternObjectInstanceType,
    type PatternObjectInstanceReferenceType,
    type PatternObjectInstanceDeleteType,
    type BaseTransformationStatementType,
    type IfExpressionStatementType,
    type PatternApplicationConditionType
} from "../../../grammar/modelTransformationTypes.js";
import { ModelTransformationIdGenerator } from "../modelTransformationIdGenerator.js";
import { findContainingPattern } from "../modelTransformationPatternUtils.js";
import type { AstNode } from "langium";
import type { WorkspaceEdit } from "vscode-languageserver-types";

const { injectable } = sharedImport("inversify");
const { AstUtils } = sharedImport("langium");

/**
 * Operation handler for deleting elements in a model transformation diagram.
 *
 * Supports deletion of:
 * - Pattern links ({@link PatternLink})
 * - Pattern instances ({@link PatternObjectInstance}, {@link PatternObjectInstanceReference},
 *   {@link PatternObjectInstanceDelete})
 * - Manual kill/stop nodes ({@link StopStatement}) — the automatically generated implicit
 *   final stop node cannot be deleted
 * - Match pattern nodes (which deletes the entire containing statement block)
 * - Split nodes for if/while expression statements (which deletes the entire statement)
 *
 * Transitive closure rules:
 * - Deleting an instance also deletes all pattern links in the same pattern that reference
 *   that instance by name, wherever they are declared: a condition block anchors its graph to
 *   the match with such a link.
 * - Deleting the last element of an application condition block also deletes the block.
 * - Deleting a match pattern node (for if/while/until/for match) deletes the whole
 *   containing statement, including all nested patterns and statements.
 * - Implicitly deleted descendants are tracked and excluded from explicit CST deletion
 *   so that only the topmost affected AST nodes are explicitly removed.
 */
@injectable()
export class ModelTransformationDeleteElementOperationHandler extends BaseDeleteElementOperationHandler {
    protected override async executeDelete(operation: DeleteElementOperation): Promise<DeleteOperationResult> {
        const directElements = this.convertIdsToElements(operation.elementIds);
        const allElementsToDelete = this.formTransitiveClosure(directElements);
        const explicitElementsToDelete = this.removeAutoDeletedElements(allElementsToDelete);
        const workspaceEdit = await this.createDeleteEdits(explicitElementsToDelete);
        const deletedElements = this.collectDeletedGModelElements(allElementsToDelete);
        return { workspaceEdit, deletedElements };
    }

    /**
     * Converts GModel element IDs to AST nodes.
     *
     * Handles three special cases:
     * - The implicit end node ("implicit_end") cannot be deleted and is skipped.
     * - Split nodes (id ending in "_split") have no direct AST node; the underlying
     *   if/while expression statement is looked up instead.
     *
     * @param elementIds The GModel element IDs from the operation
     * @returns The corresponding AST nodes
     */
    private convertIdsToElements(elementIds: string[]): AstNode[] {
        const elements: AstNode[] = [];
        for (const id of elementIds) {
            if (id === "implicit_end") {
                continue;
            }

            const gElement = this.modelState.index.find(id);
            if (gElement == undefined) {
                continue;
            }

            const astNode = this.index.getAstNode(gElement);
            if (astNode != undefined) {
                elements.push(astNode);
                continue;
            }

            if (id.endsWith("_split")) {
                const stmtId = id.slice(0, -"_split".length);
                const sourceModel = this.modelState.sourceModel;
                if (sourceModel != undefined) {
                    for (const node of AstUtils.streamAst(sourceModel)) {
                        if (this.index.getElementId(node) === stmtId) {
                            elements.push(node);
                            break;
                        }
                    }
                }
            }
        }
        return elements;
    }

    /**
     * Computes the transitive closure of elements that must be deleted.
     *
     * Three phases:
     * 1. Elevate Pattern nodes to their containing statement (deleting a pattern
     *    means deleting the entire block that uses it).
     * 2. Cascade deleted statements: add all descendant AST nodes so that their
     *    GModel elements can be collected for metadata cleanup.
     * 3. For each deleted instance, add all PatternLinks of the same Pattern that
     *    reference that instance, including the links of its application condition blocks.
     * 4. Add every condition block whose elements are all being deleted.
     *
     * @param elements The directly requested AST nodes
     * @returns The full set of AST nodes to logically delete
     */
    private formTransitiveClosure(elements: AstNode[]): Set<AstNode> {
        const result = new Set<AstNode>(elements);

        const statementsToProcess = new Set<BaseTransformationStatementType>();

        for (const element of elements) {
            if (this.reflection.isInstance(element, Pattern)) {
                const stmt = this.findContainingStatement(element as PatternType);
                if (stmt != undefined) {
                    result.add(stmt);
                    statementsToProcess.add(stmt);
                }
            } else if (this.reflection.isInstance(element, BaseTransformationStatement)) {
                statementsToProcess.add(element as BaseTransformationStatementType);
            }
        }

        for (const stmt of statementsToProcess) {
            for (const descendant of AstUtils.streamAst(stmt)) {
                if (descendant !== stmt) {
                    result.add(descendant);
                }
            }
        }

        const deletedInstanceNamesPerPattern = new Map<PatternType, Set<string>>();

        for (const element of result) {
            if (this.reflection.isInstance(element, PatternObjectInstance)) {
                const instance = element as PatternObjectInstanceType;
                if (instance.name != undefined) {
                    this.addInstanceName(deletedInstanceNamesPerPattern, instance, instance.name);
                }
            } else if (this.reflection.isInstance(element, PatternObjectInstanceReference)) {
                const ref = element as PatternObjectInstanceReferenceType;
                const name = ref.instance?.ref?.name ?? ref.instance?.$refText;
                if (name != undefined) {
                    this.addInstanceName(deletedInstanceNamesPerPattern, ref, name);
                }
            } else if (this.reflection.isInstance(element, PatternObjectInstanceDelete)) {
                const del = element as PatternObjectInstanceDeleteType;
                const name = del.instance?.ref?.name ?? del.instance?.$refText;
                if (name != undefined) {
                    this.addInstanceName(deletedInstanceNamesPerPattern, del, name);
                }
            }
        }

        const allDeletedInstanceNames = new Set<string>();
        for (const names of deletedInstanceNamesPerPattern.values()) {
            for (const name of names) {
                allDeletedInstanceNames.add(name);
            }
        }

        const sourceModel = this.modelState.sourceModel;
        if (sourceModel != undefined && allDeletedInstanceNames.size > 0) {
            for (const node of AstUtils.streamAst(sourceModel)) {
                if (result.has(node)) {
                    continue;
                }
                if (this.reflection.isInstance(node, PatternObjectInstanceReference)) {
                    const ref = node as PatternObjectInstanceReferenceType;
                    const name = ref.instance?.ref?.name ?? ref.instance?.$refText;
                    if (name != undefined && allDeletedInstanceNames.has(name)) {
                        result.add(node);
                        this.addInstanceName(deletedInstanceNamesPerPattern, ref, name);
                    }
                } else if (this.reflection.isInstance(node, PatternObjectInstanceDelete)) {
                    const del = node as PatternObjectInstanceDeleteType;
                    const name = del.instance?.ref?.name ?? del.instance?.$refText;
                    if (name != undefined && allDeletedInstanceNames.has(name)) {
                        result.add(node);
                        this.addInstanceName(deletedInstanceNamesPerPattern, del, name);
                    }
                }
            }
        }

        if (sourceModel != undefined) {
            for (const node of AstUtils.streamAst(sourceModel)) {
                if (this.reflection.isInstance(node, PatternLink)) {
                    const link = node as PatternLinkType;
                    const pattern = findContainingPattern(link, this.reflection);
                    const instanceNames =
                        pattern != undefined ? deletedInstanceNamesPerPattern.get(pattern) : undefined;
                    if (instanceNames != undefined) {
                        const srcRef = link.source?.object?.$refText;
                        const tgtRef = link.target?.object?.$refText;
                        if (
                            (srcRef != undefined && instanceNames.has(srcRef)) ||
                            (tgtRef != undefined && instanceNames.has(tgtRef))
                        ) {
                            result.add(link);
                        }
                    }
                }
            }
        }

        this.addEmptiedConditionBlocks(result);

        return result;
    }

    /**
     * Adds every application condition block whose elements are all being deleted.
     *
     * A block is not drawn as an element of its own - its members carry its stereotype - so an
     * emptied block could neither be selected nor deleted from the diagram, while an empty
     * `forbid` / `require` block is not a valid condition. Deleting the last member therefore
     * deletes the block with it.
     *
     * @param result The deletion set, extended in place
     */
    private addEmptiedConditionBlocks(result: Set<AstNode>): void {
        const sourceModel = this.modelState.sourceModel;
        if (sourceModel == undefined) {
            return;
        }

        for (const node of AstUtils.streamAst(sourceModel)) {
            if (!this.reflection.isInstance(node, PatternApplicationCondition) || result.has(node)) {
                continue;
            }
            const elements = (node as PatternApplicationConditionType).elements ?? [];
            if (elements.length > 0 && elements.every((element) => result.has(element as AstNode))) {
                result.add(node);
            }
        }
    }

    /**
     * Finds the statement that directly owns the given Pattern node.
     *
     * For {@link MatchStatement}, {@link WhileMatchStatement}, {@link UntilMatchStatement},
     * and {@link ForMatchStatement} the pattern is a direct child. For
     * {@link IfMatchStatement} the pattern is nested inside an
     * {@link IfMatchConditionAndBlock} wrapper.
     *
     * @param pattern The Pattern whose containing statement is required
     * @returns The containing statement or undefined if not found
     */
    private findContainingStatement(pattern: PatternType): BaseTransformationStatementType | undefined {
        const container = pattern.$container;
        if (container == undefined) {
            return undefined;
        }

        if (
            this.reflection.isInstance(container, MatchStatement) ||
            this.reflection.isInstance(container, WhileMatchStatement) ||
            this.reflection.isInstance(container, UntilMatchStatement) ||
            this.reflection.isInstance(container, ForMatchStatement)
        ) {
            return container as BaseTransformationStatementType;
        }

        if (this.reflection.isInstance(container, IfMatchConditionAndBlock)) {
            const grandContainer = (container as AstNode).$container;
            if (grandContainer != undefined && this.reflection.isInstance(grandContainer, IfMatchStatement)) {
                return grandContainer as BaseTransformationStatementType;
            }
        }

        return undefined;
    }

    /**
     * Removes elements that will be implicitly deleted as part of a parent's deletion,
     * preventing double-deletion of nested content.
     *
     * An element is implicitly deleted if any of its `$container` ancestors is itself a
     * {@link BaseTransformationStatement} or a {@link PatternApplicationCondition} that is
     * being explicitly deleted.
     *
     * @param elements The full logical deletion set
     * @returns Only the elements that must be explicitly deleted via a CST edit
     */
    private removeAutoDeletedElements(elements: Set<AstNode>): Set<AstNode> {
        const deletedContainers = new Set<AstNode>();
        for (const element of elements) {
            if (
                this.reflection.isInstance(element, BaseTransformationStatement) ||
                this.reflection.isInstance(element, PatternApplicationCondition)
            ) {
                deletedContainers.add(element);
            }
        }

        const result = new Set<AstNode>();
        for (const element of elements) {
            if (!this.isDescendantOfAny(element, deletedContainers)) {
                result.add(element);
            }
        }
        return result;
    }

    /**
     * Tests whether {@code node} has any element from {@code ancestors} in its
     * `$container` chain.
     *
     * @param node The AST node to test
     * @param ancestors The set of candidate ancestors
     * @returns True if any ancestor is found in the container chain
     */
    private isDescendantOfAny(node: AstNode, ancestors: Set<AstNode>): boolean {
        let current = (node as AstNode).$container;
        while (current != undefined) {
            if (ancestors.has(current)) {
                return true;
            }
            current = (current as AstNode).$container;
        }
        return false;
    }

    /**
     * Creates the workspace edits that remove the CST nodes for all explicitly
     * deleted elements.
     *
     * @param elements The elements that must be explicitly deleted
     * @returns The merged workspace edit
     */
    private async createDeleteEdits(elements: Set<AstNode>): Promise<WorkspaceEdit> {
        const edits: WorkspaceEdit[] = [];
        for (const element of elements) {
            if (element.$cstNode != undefined) {
                edits.push(this.deleteCstNode(element.$cstNode));
            }
        }
        return this.mergeWorkspaceEdits(edits);
    }

    /**
     * Collects GModel elements corresponding to all logically deleted AST nodes.
     *
     * Also includes derived GModel elements (split nodes, merge nodes) that have
     * no direct AST node mapping but whose metadata must be cleaned up.
     *
     * @param elements The full logical deletion set
     * @returns Array of GModel elements for metadata cleanup
     */
    private collectDeletedGModelElements(elements: Set<AstNode>): GModelElement[] {
        const deletedElements: GModelElement[] = [];

        for (const element of elements) {
            const elementId = this.index.getElementId(element);
            if (elementId != undefined) {
                const gElement = this.modelState.index.find(elementId);
                if (gElement != undefined) {
                    deletedElements.push(gElement);
                }
            }

            if (
                this.reflection.isInstance(element, IfExpressionStatement) ||
                this.reflection.isInstance(element, WhileExpressionStatement)
            ) {
                const stmtId = this.index.getElementId(element);
                if (stmtId != undefined) {
                    const splitElement = this.modelState.index.find(`${stmtId}_split`);
                    if (splitElement != undefined) {
                        deletedElements.push(splitElement);
                    }
                }
            }

            if (this.reflection.isInstance(element, IfExpressionStatement)) {
                const stmtId = this.index.getElementId(element);
                const ifExpr = element as IfExpressionStatementType;
                const branchCount = ifExpr.elseIfBranches?.length ?? 0;
                if (stmtId != undefined) {
                    for (let i = 0; i < branchCount; i++) {
                        const branchSplitId = `${stmtId}_elseif_${i}_split`;
                        const branchSplitElement = this.modelState.index.find(branchSplitId);
                        if (branchSplitElement != undefined) {
                            deletedElements.push(branchSplitElement);
                        }
                    }
                }
            }

            if (
                this.reflection.isInstance(element, IfMatchStatement) ||
                this.reflection.isInstance(element, IfExpressionStatement)
            ) {
                const stmtId = this.index.getElementId(element);
                if (stmtId != undefined) {
                    const mergeElement = this.modelState.index.find(ModelTransformationIdGenerator.mergeNode(stmtId));
                    if (mergeElement != undefined) {
                        deletedElements.push(mergeElement);
                    }
                }
            }
        }

        return deletedElements;
    }

    /**
     * Helper to add an instance name to the per-pattern tracking map.
     *
     * The name is tracked under the enclosing pattern even when the node is declared in an
     * application condition block, so that a deleted instance and the links that reference it
     * end up under the same key no matter which of the two lives in a block.
     *
     * @param map The map to update
     * @param node The declaring node the name was taken from
     * @param name The instance name to track
     */
    private addInstanceName(map: Map<PatternType, Set<string>>, node: AstNode, name: string): void {
        const pattern = findContainingPattern(node, this.reflection);
        if (pattern == undefined) {
            return;
        }
        if (!map.has(pattern)) {
            map.set(pattern, new Set());
        }
        map.get(pattern)!.add(name);
    }
}
