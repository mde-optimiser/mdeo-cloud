import { BaseOperationHandler, OperationHandlerCommand, sharedImport } from "@mdeo/language-shared";
import { Link, type LinkEndType, type LinkType, type ObjectInstanceType } from "../../../grammar/modelTypes.js";
import { ChangePropertyTypeLinkOperation, ModelElementType } from "@mdeo/protocol-model";
import { EdgeAttachmentPosition, type ContextItem } from "@mdeo/protocol-common";
import type { AstNode } from "langium";
import type { Command, GModelElement } from "@eclipse-glsp/server";
import type { ContextActionRequestContext, ContextItemProvider, GEdge } from "@mdeo/language-shared";
import { LinkAssociationResolver, type LinkAssociationCandidate } from "../linkAssociationResolver.js";

const { injectable } = sharedImport("inversify");

/**
 * Handler for changing link type association-end mappings on model links.
 */
@injectable()
export class ChangeLinkTypeOperationHandler extends BaseOperationHandler implements ContextItemProvider {
    override readonly operationType = ChangePropertyTypeLinkOperation.KIND;

    /**
     * Creates a workspace-edit command that updates source and target link-end properties.
     *
     * @param operation The link-type change operation
     * @returns A command wrapping the workspace edit, or undefined when update is not possible
     */
    override async createCommand(operation: ChangePropertyTypeLinkOperation): Promise<Command | undefined> {
        const gmodelElement = this.modelState.index.get(operation.linkId);
        if (gmodelElement == undefined) {
            return undefined;
        }

        const astNode = this.index.getAstNode(gmodelElement);
        if (astNode == undefined || !this.reflection.isInstance(astNode, Link)) {
            return undefined;
        }

        const linkNode = astNode as LinkType;
        const cstNode = linkNode.$cstNode;
        if (cstNode == undefined) {
            return undefined;
        }

        const sourceProperty = operation.sourceProperty?.trim();
        const targetProperty = operation.targetProperty?.trim();

        const updated: LinkType = {
            ...linkNode,
            source: this.updateEndProperty(linkNode.source, sourceProperty),
            target: this.updateEndProperty(linkNode.target, targetProperty)
        };

        const edit = await this.replaceCstNode(cstNode, updated as unknown as AstNode);
        return new OperationHandlerCommand(this.modelState, edit, undefined);
    }

    /**
     * Returns context items for changing source and target link-end types.
     *
     * @param element The selected element
     * @param _context Additional request context
     * @returns Context actions for this handler
     */
    getContextItems(element: GModelElement, _context: ContextActionRequestContext): ContextItem[] {
        if (element.type !== ModelElementType.EDGE_LINK) {
            return [];
        }

        const edge = element as GEdge;
        const linkNode = this.getLink(edge.id);
        if (linkNode == undefined) {
            return [];
        }

        const candidates = this.getCandidates(linkNode);
        if (candidates.length <= 1) {
            return [];
        }

        const sourceItems = this.createEndItems(edge.id, "source", candidates);
        const targetItems = this.createEndItems(edge.id, "target", candidates);

        const items: ContextItem[] = [];
        if (sourceItems.length > 1) {
            items.push({
                id: `change-link-type-${edge.id}-source`,
                label: "Source Type",
                icon: "settings-2",
                sortString: "a",
                position: EdgeAttachmentPosition.END,
                children: sourceItems
            });
        }
        if (targetItems.length > 1) {
            items.push({
                id: `change-link-type-${edge.id}-target`,
                label: "Target Type",
                icon: "settings-2",
                sortString: "a",
                position: EdgeAttachmentPosition.START,
                children: targetItems
            });
        }

        return items;
    }

    /**
     * Resolves the link AST node for a diagram edge id.
     *
     * @param edgeId The diagram edge id
     * @returns Link AST node when available
     */
    private getLink(edgeId: string): LinkType | undefined {
        const edge = this.modelState.index.get(edgeId);
        if (edge == undefined) {
            return undefined;
        }

        const astNode = this.index.getAstNode(edge);
        if (astNode == undefined || !this.reflection.isInstance(astNode, Link)) {
            return undefined;
        }

        return astNode as LinkType;
    }

    /**
     * Resolves oriented association candidates for the given link.
     *
     * @param linkNode The link AST node
     * @returns Candidate association orientations
     */
    private getCandidates(linkNode: LinkType): LinkAssociationCandidate[] {
        const sourceClass = (linkNode.source?.object?.ref as ObjectInstanceType | undefined)?.class?.ref;
        const targetClass = (linkNode.target?.object?.ref as ObjectInstanceType | undefined)?.class?.ref;
        if (sourceClass == undefined || targetClass == undefined) {
            return [];
        }

        const resolver = new LinkAssociationResolver(this.reflection);
        return resolver.findCandidates(sourceClass, targetClass);
    }

    /**
     * Creates submenu context items for one link end.
     *
     * @param linkId The link id
     * @param end The end to generate items for
     * @param candidates Candidate association orientations
     * @returns End-specific action items
     */
    private createEndItems(
        linkId: string,
        end: "source" | "target",
        candidates: LinkAssociationCandidate[]
    ): ContextItem[] {
        const byDisplayName = new Map<string, LinkAssociationCandidate>();

        for (const candidate of candidates) {
            const endName = end === "source" ? candidate.sourceEnd.name : candidate.targetEnd.name;
            const displayName = endName == undefined || endName.length === 0 ? "(none)" : endName;
            if (!byDisplayName.has(displayName)) {
                byDisplayName.set(displayName, candidate);
            }
        }

        return [...byDisplayName.entries()]
            .sort(([left], [right]) => left.localeCompare(right))
            .map(([displayName, candidate]) => ({
                id: `change-link-type-${linkId}-${end}-${displayName}`,
                label: displayName,
                action: ChangePropertyTypeLinkOperation.create({
                    linkId,
                    sourceProperty: candidate.sourceEnd.name,
                    targetProperty: candidate.targetEnd.name,
                    endPosition: end
                })
            }));
    }

    /**
     * Updates a link end property reference.
     *
     * @param end Existing link end
     * @param propertyName New property name
     * @returns Updated link end
     */
    private updateEndProperty(end: LinkEndType | undefined, propertyName: string | undefined): LinkEndType {
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
        } as LinkEndType;
    }
}
