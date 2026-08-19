import { BaseOperationHandler, OperationHandlerCommand, sharedImport } from "@mdeo/language-shared";
import {
    Association,
    AssociationEnd,
    type AssociationEndType,
    type AssociationType,
    type MetamodelAssociationOperators
} from "../../../grammar/metamodelTypes.js";
import {
    AssociationEndKind,
    ChangeAssociationEndOperation,
    MetamodelElementType,
    MetamodelIcon
} from "@mdeo/protocol-metamodel";
import type { AstNode } from "langium";
import type { Command, GModelElement } from "@eclipse-glsp/server";
import type { WorkspaceEdit } from "vscode-languageserver-types";
import type { ContextItem } from "@mdeo/protocol-common";
import { EdgeAttachmentPosition } from "@mdeo/protocol-common";
import type { ContextActionRequestContext, ContextItemProvider, GEdge } from "@mdeo/language-shared";
import { OPERATOR_TO_KINDS, KINDS_TO_OPERATOR } from "./associationEndKinds.js";
import { generateDefaultPropertyName, isImportedElement } from "./metamodelHandlerUtils.js";

const { injectable } = sharedImport("inversify");
const { GrammarUtils } = sharedImport("langium");

/**
 * Handler for changing association end decorations.
 */
@injectable()
export class ChangeAssociationEndOperationHandler extends BaseOperationHandler implements ContextItemProvider {
    override readonly operationType = ChangeAssociationEndOperation.KIND;

    /**
     * Creates a workspace-edit command that updates the association operator and, when
     * navigability changes, also inserts or removes property names on the affected ends.
     *
     * The property-name rules derived from the grammar validator are:
     * - {@code source.name} is required when {@code newTargetKind != NONE}
     * - {@code target.name} is required when {@code newSourceKind != NONE}
     *
     * When a property name must be added, a default name is generated from the referenced
     * class name (lower-casing the first letter) via {@link generateDefaultPropertyName}.
     *
     * @param operation The change operation
     * @returns A command wrapping the merged workspace edit, or undefined
     */
    override async createCommand(operation: ChangeAssociationEndOperation): Promise<Command | undefined> {
        const edgeId = operation.associationId;
        const gmodelElement = this.modelState.index.get(edgeId);
        if (gmodelElement == undefined) {
            return undefined;
        }

        const astNode = this.index.getAstNode(gmodelElement);
        if (astNode == undefined || !this.reflection.isInstance(astNode, Association)) {
            return undefined;
        }

        const assocNode = astNode as AssociationType;
        const currentOperator = assocNode.operator;
        if (currentOperator == undefined) {
            return undefined;
        }

        const endPosition = operation.endPosition;
        const newKind = operation.newEndType;

        const currentKinds = this.getEndKindsFromOperator(currentOperator);
        const newSourceKind = endPosition === "source" ? newKind : currentKinds.sourceKind;
        const newTargetKind = endPosition === "target" ? newKind : currentKinds.targetKind;

        const newOperator = this.getOperatorFromEndKinds(newSourceKind, newTargetKind);
        if (newOperator == undefined || newOperator === currentOperator) {
            return undefined;
        }

        const cstNode = assocNode.$cstNode;
        if (cstNode == undefined) {
            return undefined;
        }

        const operatorCstNode = GrammarUtils.findNodeForProperty(cstNode, "operator");
        if (operatorCstNode == undefined) {
            return undefined;
        }

        const edits: WorkspaceEdit[] = [];

        // Replace the operator itself
        edits.push(await this.replaceCstNode(operatorCstNode, newOperator));

        // Determine which ends need a property name under the new operator
        const needsSourceName = newTargetKind !== AssociationEndKind.NONE;
        const needsTargetName = newSourceKind !== AssociationEndKind.NONE;

        const sourceEnd = assocNode.source as AssociationEndType | undefined;
        const targetEnd = assocNode.target as AssociationEndType | undefined;

        const sourceEndEdit = await this.buildEndNameEdit(
            sourceEnd,
            needsSourceName,
            assocNode.target?.class?.$refText ?? ""
        );
        if (sourceEndEdit != undefined) {
            edits.push(sourceEndEdit);
        }

        const targetEndEdit = await this.buildEndNameEdit(
            targetEnd,
            needsTargetName,
            assocNode.source?.class?.$refText ?? ""
        );
        if (targetEndEdit != undefined) {
            edits.push(targetEndEdit);
        }

        const mergedEdit = this.mergeWorkspaceEdits(edits);
        return new OperationHandlerCommand(this.modelState, mergedEdit, undefined);
    }

    /**
     * Builds a workspace edit to add or remove the property name on one association end,
     * if the current state of that end does not match what is required.
     *
     * @param end The association-end AST node to examine
     * @param needsName Whether a property name is required on this end
     * @param referencedClassName The name of the class referenced by the opposite end,
     *   used to derive a default property name when one must be inserted
     * @returns A workspace edit, or {@code undefined} when no change is needed
     */
    private async buildEndNameEdit(
        end: AssociationEndType | undefined,
        needsName: boolean,
        referencedClassName: string
    ): Promise<WorkspaceEdit | undefined> {
        if (end == undefined || end.$cstNode == undefined) {
            return undefined;
        }

        const hasName = end.name != undefined;

        if (hasName === needsName) {
            return undefined;
        }

        const newEnd: AssociationEndType = {
            $type: AssociationEnd.name,
            class: end.class,
            name: needsName ? generateDefaultPropertyName(referencedClassName) : undefined,
            multiplicity: end.multiplicity
        };

        return this.replaceCstNode(end.$cstNode, newEnd as unknown as AstNode);
    }

    /**
     * Returns context items for changing association source/target end decorations.
     * No items are returned for edges belonging to imported (read-only) associations.
     *
     * @param element The selected element
     * @param _context Request context
     * @returns Context actions provided by this handler
     */
    getContextItems(element: GModelElement, _context: ContextActionRequestContext): ContextItem[] {
        if (element.type !== MetamodelElementType.EDGE_ASSOCIATION) {
            return [];
        }

        if (isImportedElement(element)) {
            return [];
        }

        const edge = element as GEdge;
        const association = this.getAssociationForEdge(edge.id);
        const currentKinds = this.getEndKindsFromOperator(association?.operator);
        const items: ContextItem[] = [];
        const endTypes = [
            { kind: AssociationEndKind.NONE, label: "None", icon: MetamodelIcon.NONE_ASSOCIATION },
            { kind: AssociationEndKind.ARROW, label: "Arrow", icon: MetamodelIcon.UNIDIRECTIONAL_ASSOCIATION },
            { kind: AssociationEndKind.COMPOSITION, label: "Composition", icon: MetamodelIcon.COMPOSITION }
        ];

        for (const endType of ["source", "target"] as const) {
            const position = endType === "source" ? EdgeAttachmentPosition.START : EdgeAttachmentPosition.END;
            const submenu: ContextItem[] = endTypes
                .filter((endKind) => {
                    if (endType === "source") {
                        return this.getOperatorFromEndKinds(endKind.kind, currentKinds.targetKind) != undefined;
                    }
                    return this.getOperatorFromEndKinds(currentKinds.sourceKind, endKind.kind) != undefined;
                })
                .map((endKind) => ({
                    id: `change-assoc-end-${edge.id}-${endType}-${endKind.kind}`,
                    label: endKind.label,
                    icon: endKind.icon,
                    action: ChangeAssociationEndOperation.create({
                        associationId: edge.id,
                        endPosition: endType,
                        newEndType: endKind.kind
                    })
                }));

            if (submenu.length === 0) {
                continue;
            }

            items.push({
                id: `change-assoc-end-${edge.id}-${endType}`,
                icon: "settings-2",
                label: endType === "source" ? "Source End" : "Target End",
                sortString: "a",
                position,
                children: submenu
            });
        }

        return items;
    }

    /**
     * Resolves source and target end kinds from an association operator.
     *
     * @param operator The operator text
     * @returns Source and target end kinds
     */
    private getEndKindsFromOperator(operator: string | undefined): {
        sourceKind: AssociationEndKind;
        targetKind: AssociationEndKind;
    } {
        if (operator == undefined) {
            return {
                sourceKind: AssociationEndKind.NONE,
                targetKind: AssociationEndKind.ARROW
            };
        }

        return (
            OPERATOR_TO_KINDS[operator] ?? {
                sourceKind: AssociationEndKind.NONE,
                targetKind: AssociationEndKind.ARROW
            }
        );
    }

    /**
     * Resolves an association operator from source and target end kinds.
     *
     * @param sourceKind Desired source end kind
     * @param targetKind Desired target end kind
     * @returns Matching operator, or undefined when unsupported
     */
    private getOperatorFromEndKinds(
        sourceKind: AssociationEndKind,
        targetKind: AssociationEndKind
    ): MetamodelAssociationOperators | undefined {
        return KINDS_TO_OPERATOR[`${sourceKind},${targetKind}`];
    }

    /**
     * Resolves the association AST node for a diagram edge id.
     *
     * @param edgeId The diagram edge identifier
     * @returns The association AST node if available
     */
    private getAssociationForEdge(edgeId: string): AssociationType | undefined {
        const edge = this.modelState.index.get(edgeId);
        if (edge == undefined) {
            return undefined;
        }

        const astNode = this.index.getAstNode(edge);
        if (astNode == undefined || !this.reflection.isInstance(astNode, Association)) {
            return undefined;
        }

        return astNode as AssociationType;
    }
}
