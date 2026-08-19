import { BaseOperationHandler, OperationHandlerCommand, sharedImport } from "@mdeo/language-shared";
import type { Command, GModelElement } from "@eclipse-glsp/server";
import {
    PatternLink,
    PatternObjectInstance,
    type PatternLinkEndType,
    type PatternLinkType,
    type PatternObjectInstanceType
} from "../../../grammar/modelTransformationTypes.js";
import { ChangePropertyTypeLinkOperation, ModelTransformationElementType } from "@mdeo/protocol-model-transformation";
import { EdgeAttachmentPosition, type ContextItem } from "@mdeo/protocol-common";
import type { ContextActionRequestContext, ContextItemProvider, GEdge } from "@mdeo/language-shared";
import { resolveClassChain, type ClassType } from "@mdeo/language-metamodel";
import { AssociationEndCache } from "@mdeo/language-model";
import type { AstNode } from "langium";

const { injectable } = sharedImport("inversify");

/**
 * Handler for changing pattern-link end property mappings.
 */
@injectable()
export class ChangeLinkTypeOperationHandler extends BaseOperationHandler implements ContextItemProvider {
    override readonly operationType = ChangePropertyTypeLinkOperation.KIND;

    /**
     * Creates a workspace-edit command that updates source and target pattern-link-end property references.
     *
     * @param operation The link-type change operation
     * @returns A command wrapping the workspace edit, or undefined when the update is not possible
     */
    override async createCommand(operation: ChangePropertyTypeLinkOperation): Promise<Command | undefined> {
        const gmodelElement = this.modelState.index.get(operation.linkId);
        if (gmodelElement == undefined) {
            return undefined;
        }

        const astNode = this.index.getAstNode(gmodelElement);
        if (astNode == undefined || !this.reflection.isInstance(astNode, PatternLink)) {
            return undefined;
        }

        const linkNode = astNode as PatternLinkType;
        const cstNode = linkNode.$cstNode;
        if (cstNode == undefined) {
            return undefined;
        }

        const sourceProperty = operation.sourceProperty?.trim();
        const targetProperty = operation.targetProperty?.trim();

        const updated: PatternLinkType = {
            ...linkNode,
            source: this.updateEndProperty(linkNode.source, sourceProperty),
            target: this.updateEndProperty(linkNode.target, targetProperty)
        };

        const edit = await this.replaceCstNode(cstNode, updated as unknown as AstNode);
        return new OperationHandlerCommand(this.modelState, edit, undefined);
    }

    /**
     * Returns source/target link-type context menus for pattern-link edges.
     *
     * @param element The selected element
     * @param _context Additional request context
     * @returns Context actions for this handler
     */
    getContextItems(element: GModelElement, _context: ContextActionRequestContext): ContextItem[] {
        if (element.type !== ModelTransformationElementType.EDGE_PATTERN_LINK) {
            return [];
        }

        const edge = element as GEdge;
        const linkNode = this.getPatternLink(edge.id);
        if (linkNode == undefined) {
            return [];
        }

        const sourceOptions = this.getPropertyOptionsForEnd(linkNode, "source");
        const targetOptions = this.getPropertyOptionsForEnd(linkNode, "target");

        const items: ContextItem[] = [];

        if (sourceOptions.length > 1) {
            items.push({
                id: `change-pattern-link-type-${edge.id}-source`,
                label: "Source Type",
                icon: "settings-2",
                sortString: "a",
                position: EdgeAttachmentPosition.START,
                children: sourceOptions.map((propertyName) => ({
                    id: `change-pattern-link-type-${edge.id}-source-${propertyName || "none"}`,
                    label: propertyName || "(none)",
                    action: ChangePropertyTypeLinkOperation.create({
                        linkId: edge.id,
                        sourceProperty: propertyName,
                        targetProperty: linkNode.target?.property?.$refText,
                        endPosition: "source"
                    })
                }))
            });
        }

        if (targetOptions.length > 1) {
            items.push({
                id: `change-pattern-link-type-${edge.id}-target`,
                label: "Target Type",
                icon: "settings-2",
                sortString: "a",
                position: EdgeAttachmentPosition.END,
                children: targetOptions.map((propertyName) => ({
                    id: `change-pattern-link-type-${edge.id}-target-${propertyName || "none"}`,
                    label: propertyName || "(none)",
                    action: ChangePropertyTypeLinkOperation.create({
                        linkId: edge.id,
                        sourceProperty: linkNode.source?.property?.$refText,
                        targetProperty: propertyName,
                        endPosition: "target"
                    })
                }))
            });
        }

        return items;
    }

    /**
     * Resolves a pattern link AST node from a diagram edge id.
     *
     * @param edgeId The edge identifier
     * @returns Pattern link node if available
     */
    private getPatternLink(edgeId: string): PatternLinkType | undefined {
        const edge = this.modelState.index.get(edgeId);
        if (edge == undefined) {
            return undefined;
        }

        const astNode = this.index.getAstNode(edge);
        if (astNode == undefined || !this.reflection.isInstance(astNode, PatternLink)) {
            return undefined;
        }

        return astNode as PatternLinkType;
    }

    /**
     * Computes available association-end property names for a specific link end.
     *
     * Uses the AssociationEndCache (the same source as the scope provider) to collect
     * all named association ends that reference each class in the object's inheritance chain.
     *
     * @param linkNode The pattern link node
     * @param end The requested link end
     * @returns Sorted unique association-end name options
     */
    private getPropertyOptionsForEnd(linkNode: PatternLinkType, end: "source" | "target"): string[] {
        const instance = end === "source" ? linkNode.source?.object?.ref : linkNode.target?.object?.ref;
        if (instance == undefined || !this.reflection.isInstance(instance, PatternObjectInstance)) {
            return [];
        }

        const classType = (instance as PatternObjectInstanceType).class?.ref as ClassType | undefined;
        if (classType == undefined) {
            return [];
        }

        const cache = new AssociationEndCache(this.modelState.languageServices);
        const names = new Set<string>();
        for (const cls of resolveClassChain(classType, this.reflection)) {
            for (const associationEnd of cache.getAssociationEndsForClass(cls)) {
                if (associationEnd.name != undefined) {
                    names.add(associationEnd.name);
                }
            }
        }

        return [...names].sort((left, right) => left.localeCompare(right));
    }

    /**
     * Updates a pattern link end's property cross-reference.
     *
     * @param end The existing pattern link end
     * @param propertyName The new association-end name, or undefined/empty to clear
     * @returns Updated pattern link end
     */
    private updateEndProperty(end: PatternLinkEndType, propertyName: string | undefined): PatternLinkEndType {
        const normalized = propertyName != undefined && propertyName.length > 0 ? propertyName : undefined;
        return {
            ...end,
            property:
                normalized == undefined
                    ? undefined
                    : {
                          $refText: normalized,
                          ref: undefined
                      }
        } as PatternLinkEndType;
    }
}
