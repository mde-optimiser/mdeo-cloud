import { BaseApplyLabelEditOperationHandler, parseIdentifier, sharedImport } from "@mdeo/language-shared";
import { ID } from "@mdeo/language-common";
import type { AstNode } from "langium";
import type { WorkspaceEdit } from "vscode-languageserver-types";
import type { ApplyLabelEditOperation } from "@eclipse-glsp/server";
import {
    PatternObjectInstance,
    PatternPropertyAssignment,
    WhereClause,
    PatternVariable,
    PatternVariableReassignment,
    PatternLink,
    IfExpressionStatement,
    WhileExpressionStatement,
    ElseIfBranch,
    type PatternObjectInstanceType,
    type PatternPropertyAssignmentType,
    type WhereClauseType,
    type PatternVariableType,
    type PatternVariableReassignmentType,
    type PatternLinkType,
    type IfExpressionStatementType,
    type WhileExpressionStatementType,
    type ElseIfBranchType
} from "../../../grammar/modelTransformationTypes.js";
import {
    parseInstanceLabel,
    parseModelTransformationPropertyLabel,
    parseVariableLabel,
    parseVariableReassignmentLabel,
    extractWhereClauseExpression
} from "../modelTransformationLabelParseUtils.js";

const { injectable } = sharedImport("inversify");
const { GrammarUtils } = sharedImport("langium");

/**
 * Handler for applying label edit operations in model transformation diagrams.
 *
 * Handles the following label kinds:
 * - Pattern instance name labels (`name : Type` or `name` format) for all locally-declared instances
 * - Pattern property assignment labels (`propName = expr` or `propName == expr`)
 * - Where clause expression labels (plain text replacement)
 * - Variable declaration labels (`var name[: type] = expr`, name and expression are updated)
 */
@injectable()
export class ModelTransformationApplyLabelEditOperationHandler extends BaseApplyLabelEditOperationHandler {
    /**
     * Dispatches the label edit to the appropriate handler based on the AST node type.
     *
     * @param node The AST node being edited
     * @param operation The apply label edit operation
     * @returns The workspace edit to apply, or undefined if not applicable
     */
    override async createLabelEdit(
        node: AstNode,
        operation: ApplyLabelEditOperation
    ): Promise<WorkspaceEdit | undefined> {
        if (this.reflection.isInstance(node, PatternLink)) {
            return await this.createLinkModifierEdit(node as PatternLinkType, operation.text);
        }
        if (this.reflection.isInstance(node, PatternObjectInstance)) {
            const labelId = operation.labelId ?? "";
            if (labelId.endsWith("__modifier-label")) {
                return await this.createPatternModifierEdit(node as PatternObjectInstanceType, operation.text);
            }
            return await this.createInstanceNameEdit(node as PatternObjectInstanceType, operation.text);
        }
        if (this.reflection.isInstance(node, PatternPropertyAssignment)) {
            return await this.createPropertyAssignmentEdit(node as PatternPropertyAssignmentType, operation.text);
        }
        if (this.reflection.isInstance(node, WhereClause)) {
            return await this.createWhereClauseEdit(node as WhereClauseType, operation.text);
        }
        if (this.reflection.isInstance(node, PatternVariable)) {
            return await this.createVariableEdit(node as PatternVariableType, operation.text);
        }
        if (this.reflection.isInstance(node, PatternVariableReassignment)) {
            return await this.createVariableReassignmentEdit(node as PatternVariableReassignmentType, operation.text);
        }
        if (this.reflection.isInstance(node, IfExpressionStatement)) {
            return await this.createIfConditionEdit(node as IfExpressionStatementType, operation.text);
        }
        if (this.reflection.isInstance(node, WhileExpressionStatement)) {
            return await this.createWhileConditionEdit(node as WhileExpressionStatementType, operation.text);
        }
        if (this.reflection.isInstance(node, ElseIfBranch)) {
            return await this.createElseIfConditionEdit(node as ElseIfBranchType, operation.text);
        }
        return undefined;
    }

    /**
     * Creates a workspace edit for renaming a pattern object instance.
     * Supports both `name : type` (updates name and class reference) and
     * `name`-only formats (the latter applies when the class reference is currently unresolved).
     * The instance name is renamed across all references in the file.
     *
     * @param node The PatternObjectInstance AST node
     * @param text The new label text in `name : type` or plain `name` format
     * @returns The rename workspace edit, or undefined if the label is empty
     */
    private async createInstanceNameEdit(
        node: PatternObjectInstanceType,
        text: string
    ): Promise<WorkspaceEdit | undefined> {
        const edits: WorkspaceEdit[] = [];
        const parsed = parseInstanceLabel(text);

        if (parsed != undefined) {
            const nameNode = GrammarUtils.findNodeForProperty(node.$cstNode, "name");
            if (nameNode != undefined) {
                const renameEdit = this.createRenameWorkspaceEdit(nameNode, parseIdentifier(parsed.name));
                if (renameEdit != undefined) {
                    edits.push(renameEdit);
                }
            }

            const classNode = GrammarUtils.findNodeForProperty(node.$cstNode, "class");
            if (classNode != undefined) {
                edits.push(await this.replaceCstNode(classNode, parseIdentifier(parsed.type)));
            }
        } else {
            const nameTrimmed = text.trim();
            if (nameTrimmed.length > 0) {
                const nameNode = GrammarUtils.findNodeForProperty(node.$cstNode, "name");
                if (nameNode != undefined) {
                    const renameEdit = this.createRenameWorkspaceEdit(nameNode, parseIdentifier(nameTrimmed));
                    if (renameEdit != undefined) {
                        edits.push(renameEdit);
                    }
                }
            }
        }

        return this.mergeWorkspaceEdits(edits);
    }

    /**
     * Creates a workspace edit for updating a pattern property assignment.
     * Parses the label to extract the property name, operator, and value expression.
     * Updates the property reference, operator keyword, and value expression in the CST.
     * Returns `undefined` (no-op) if any required CST node cannot be located.
     *
     * @param node The PatternPropertyAssignment AST node
     * @param text The new label text in `propName = expr` or `propName == expr` format
     * @returns The workspace edit, or undefined if the text cannot be parsed or CST nodes are missing
     */
    private async createPropertyAssignmentEdit(
        node: PatternPropertyAssignmentType,
        text: string
    ): Promise<WorkspaceEdit | undefined> {
        if (text.trim().length === 0) {
            if (node.$cstNode == undefined) {
                return undefined;
            }
            return this.deleteCstNode(node.$cstNode);
        }

        const parsed = parseModelTransformationPropertyLabel(text);
        if (typeof parsed === "string") {
            return undefined;
        }

        const nameNode = GrammarUtils.findNodeForProperty(node.$cstNode, "name");
        const operatorNode = GrammarUtils.findNodeForProperty(node.$cstNode, "operator");
        const valueNode = GrammarUtils.findNodeForProperty(node.$cstNode, "value");

        if (operatorNode == undefined) {
            return undefined;
        }

        const edits: WorkspaceEdit[] = [];

        if (nameNode != undefined) {
            const serializer = this.modelState.languageServices.AstSerializer;
            const serializedName = serializer.serializePrimitive({ value: parseIdentifier(parsed.name) }, ID);
            edits.push(await this.replaceCstNode(nameNode, serializedName));
        }

        edits.push(await this.replaceCstNode(operatorNode, parsed.operator));

        if (valueNode != undefined) {
            edits.push(await this.replaceCstNode(valueNode, parsed.value));
        }

        return this.mergeWorkspaceEdits(edits);
    }

    /**
     * Creates a workspace edit for updating a where clause expression.
     * The label text must start with `where ` followed by the expression.
     * The expression is replaced as plain text with no further validation.
     *
     * @param node The WhereClause AST node
     * @param text The new label text starting with `where `
     * @returns The workspace edit, or undefined if the expression cannot be extracted
     */
    private async createWhereClauseEdit(node: WhereClauseType, text: string): Promise<WorkspaceEdit | undefined> {
        if (text.trim().length === 0) {
            if (node.$cstNode == undefined) {
                return undefined;
            }
            return this.deleteCstNode(node.$cstNode);
        }

        const expressionText = extractWhereClauseExpression(text);
        if (expressionText == undefined) {
            return undefined;
        }

        const expressionNode = GrammarUtils.findNodeForProperty(node.$cstNode, "expression");
        if (expressionNode == undefined) {
            return undefined;
        }

        return await this.replaceCstNode(expressionNode, expressionText);
    }

    /**
     * Creates a workspace edit for updating a variable declaration.
     * Parses the label to extract the variable name and value expression.
     * The variable name is renamed across all references in the file;
     * the value expression is replaced as plain text with no further validation.
     *
     * @param node The PatternVariable AST node
     * @param text The new label text in `var name[: type] = expr` format
     * @returns The workspace edit, or undefined if the text cannot be parsed
     */
    private async createVariableEdit(node: PatternVariableType, text: string): Promise<WorkspaceEdit | undefined> {
        if (text.trim().length === 0) {
            if (node.$cstNode == undefined) {
                return undefined;
            }
            return this.deleteCstNode(node.$cstNode);
        }

        const parsed = parseVariableLabel(text);
        if (parsed == undefined) {
            return undefined;
        }

        const edits: WorkspaceEdit[] = [];

        const nameNode = GrammarUtils.findNodeForProperty(node.$cstNode, "name");
        if (nameNode != undefined) {
            const serializer = this.modelState.languageServices.AstSerializer;
            const serializedName = serializer.serializePrimitive({ value: parseIdentifier(parsed.name) }, ID);
            const renameEdit = this.createRenameWorkspaceEdit(nameNode, serializedName);
            if (renameEdit != undefined) {
                edits.push(renameEdit);
            }
        }

        const valueNode = GrammarUtils.findNodeForProperty(node.$cstNode, "value");
        if (valueNode != undefined) {
            edits.push(await this.replaceCstNode(valueNode, parsed.value));
        }

        return this.mergeWorkspaceEdits(edits);
    }

    /**
     * Creates a workspace edit for updating a variable reassignment (`name = expr`).
     * Parses the label to extract the target variable name and value expression.
     * Because the name is a reference to an already-declared variable (not a declaration),
     * it is replaced as plain reference text rather than renamed across the file; the value
     * expression is replaced as plain text with no further validation.
     *
     * @param node The PatternVariableReassignment AST node
     * @param text The new label text in `name = expr` format
     * @returns The workspace edit, or undefined if the text cannot be parsed
     */
    private async createVariableReassignmentEdit(
        node: PatternVariableReassignmentType,
        text: string
    ): Promise<WorkspaceEdit | undefined> {
        if (text.trim().length === 0) {
            if (node.$cstNode == undefined) {
                return undefined;
            }
            return this.deleteCstNode(node.$cstNode);
        }

        const parsed = parseVariableReassignmentLabel(text);
        if (parsed == undefined) {
            return undefined;
        }

        const edits: WorkspaceEdit[] = [];

        const variableNode = GrammarUtils.findNodeForProperty(node.$cstNode, "variable");
        if (variableNode != undefined) {
            const serializer = this.modelState.languageServices.AstSerializer;
            const serializedName = serializer.serializePrimitive({ value: parseIdentifier(parsed.name) }, ID);
            edits.push(await this.replaceCstNode(variableNode, serializedName));
        }

        const valueNode = GrammarUtils.findNodeForProperty(node.$cstNode, "value");
        if (valueNode != undefined) {
            edits.push(await this.replaceCstNode(valueNode, parsed.value));
        }

        return this.mergeWorkspaceEdits(edits);
    }

    /**
     * Strips guillemet characters (« »), trims, and lowercases the text to extract
     * the raw modifier value.
     */
    private parseModifier(text: string): string {
        return text
            .replace(/\u00ab/g, "")
            .replace(/\u00bb/g, "")
            .trim()
            .toLowerCase();
    }

    /**
     * Creates a workspace edit for updating the modifier on a pattern link.
     * When `text` is empty (after stripping guillemets), the modifier is removed from the AST.
     * Returns undefined when the modifier cannot be resolved or needs insertion.
     *
     * @param node The PatternLink AST node
     * @param text The new label text (may include « » guillemets); empty string removes the modifier
     * @returns The workspace edit, or undefined if not applicable
     */
    private async createLinkModifierEdit(node: PatternLinkType, text: string): Promise<WorkspaceEdit | undefined> {
        const modifierText = this.parseModifier(text);

        if (modifierText === "") {
            const modifierNode = node.modifier;
            if (modifierNode?.$cstNode == undefined) {
                return undefined;
            }
            return this.deleteCstNode(modifierNode.$cstNode);
        }

        const validModifiers = ["create", "delete", "forbid", "require"];
        if (!validModifiers.includes(modifierText)) {
            return undefined;
        }

        const modifierNode = node.modifier;
        if (modifierNode == undefined) {
            return undefined;
        }

        const modifierCstNode = GrammarUtils.findNodeForProperty(modifierNode.$cstNode, "modifier");
        if (modifierCstNode == undefined) {
            return undefined;
        }

        return await this.replaceCstNode(modifierCstNode, modifierText);
    }

    /**
     * Creates a workspace edit for updating the modifier on a pattern object instance.
     * When `text` is empty (after stripping guillemets), the modifier is removed from the AST.
     * Otherwise, also auto-updates the modifier on any adjacent connected links within the same pattern.
     *
     * @param node The PatternObjectInstance AST node
     * @param text The new label text (may include « » guillemets); empty string removes the modifier
     * @returns The workspace edit, or undefined if not applicable
     */
    private async createPatternModifierEdit(
        node: PatternObjectInstanceType,
        text: string
    ): Promise<WorkspaceEdit | undefined> {
        const modifierText = this.parseModifier(text);

        if (modifierText === "") {
            const instanceModifier = node.modifier;
            if (instanceModifier?.$cstNode == undefined) {
                return undefined;
            }
            return this.deleteCstNode(instanceModifier.$cstNode);
        }

        const validModifiers = ["create", "delete", "forbid", "require"];
        if (!validModifiers.includes(modifierText)) {
            return undefined;
        }

        const instanceModifier = node.modifier;
        if (instanceModifier == undefined) {
            return undefined;
        }
        const modifierCstNode = GrammarUtils.findNodeForProperty(instanceModifier.$cstNode, "modifier");
        if (modifierCstNode == undefined) {
            return undefined;
        }

        const edits: WorkspaceEdit[] = [];
        edits.push(await this.replaceCstNode(modifierCstNode, modifierText));

        const container = node.$container;
        if (container != undefined) {
            const elements = (container as unknown as { elements?: unknown[] }).elements ?? [];
            for (const element of elements) {
                if (element != undefined && this.reflection.isInstance(element, PatternLink)) {
                    const link = element as PatternLinkType;
                    const sourceRef = link.source?.object?.ref;
                    const targetRef = link.target?.object?.ref;
                    const isConnected = sourceRef === node || targetRef === node;
                    if (isConnected && link.modifier != undefined) {
                        const linkModCstNode = GrammarUtils.findNodeForProperty(link.modifier.$cstNode, "modifier");
                        if (linkModCstNode != undefined) {
                            edits.push(await this.replaceCstNode(linkModCstNode, modifierText));
                        }
                    }
                }
            }
        }

        return this.mergeWorkspaceEdits(edits);
    }

    /**
     * Creates a workspace edit for updating the condition of an if-expression statement.
     *
     * @param node The IfExpressionStatement AST node
     * @param text The new condition expression text
     * @returns The workspace edit, or undefined if not applicable
     */
    private async createIfConditionEdit(
        node: IfExpressionStatementType,
        text: string
    ): Promise<WorkspaceEdit | undefined> {
        const trimmed = text.trim();
        if (trimmed.length === 0) {
            return undefined;
        }
        const conditionNode = GrammarUtils.findNodeForProperty(node.$cstNode, "condition");
        if (conditionNode == undefined) {
            return undefined;
        }
        return await this.replaceCstNode(conditionNode, trimmed);
    }

    /**
     * Creates a workspace edit for updating the condition of a while-expression statement.
     *
     * @param node The WhileExpressionStatement AST node
     * @param text The new condition expression text
     * @returns The workspace edit, or undefined if not applicable
     */
    private async createWhileConditionEdit(
        node: WhileExpressionStatementType,
        text: string
    ): Promise<WorkspaceEdit | undefined> {
        const trimmed = text.trim();
        if (trimmed.length === 0) {
            return undefined;
        }
        const conditionNode = GrammarUtils.findNodeForProperty(node.$cstNode, "condition");
        if (conditionNode == undefined) {
            return undefined;
        }
        return await this.replaceCstNode(conditionNode, trimmed);
    }

    /**
     * Creates a workspace edit for updating the condition of an else-if branch.
     *
     * @param node The ElseIfBranch AST node
     * @param text The new condition expression text
     * @returns The workspace edit, or undefined if not applicable
     */
    private async createElseIfConditionEdit(node: ElseIfBranchType, text: string): Promise<WorkspaceEdit | undefined> {
        const trimmed = text.trim();
        if (trimmed.length === 0) {
            return undefined;
        }
        const conditionNode = GrammarUtils.findNodeForProperty(node.$cstNode, "condition");
        if (conditionNode == undefined) {
            return undefined;
        }
        return await this.replaceCstNode(conditionNode, trimmed);
    }
}
