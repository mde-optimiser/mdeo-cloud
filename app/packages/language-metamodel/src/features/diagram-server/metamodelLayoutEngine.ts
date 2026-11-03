import type { GModelRoot } from "@eclipse-glsp/server";
import type { LayoutOperation } from "@mdeo/protocol-common";
import { AssociationEndKind } from "@mdeo/protocol-metamodel";
import { BaseLayoutEngine, GEdge, GNode, sharedImport } from "@mdeo/language-shared";
import type { EdgeLabelSpec, LayoutInput, LayoutInputEdge, LayoutMarker } from "@mdeo/language-shared";
import { GAssociationPropertyNode } from "./model/associationPropertyNode.js";
import { GAssociationMultiplicityNode } from "./model/associationMultiplicityNode.js";
import { GAssociationEdge } from "./model/associationEdge.js";
import { GInheritanceEdge } from "./model/inheritanceEdge.js";

const { injectable } = sharedImport("inversify");

/**
 * Marker of a composition (diamond), as rendered by the association edge view.
 */
const DIAMOND_MARKER: LayoutMarker = { length: 20, width: 12 };

/**
 * Marker of a navigable end (arrow), as rendered by the association edge view.
 */
const ARROW_MARKER: LayoutMarker = { length: 12, width: 12 };

/**
 * Marker of an inheritance edge (hollow triangle), as rendered by the inheritance edge view.
 */
const INHERITANCE_MARKER: LayoutMarker = { length: 15, width: 15 };

/**
 * Layout engine for metamodel diagrams.
 */
@injectable()
export class MetamodelLayoutEngine extends BaseLayoutEngine {
    protected override buildInput(model: GModelRoot, operation: LayoutOperation): LayoutInput {
        const bounds = operation.bounds;
        const input: LayoutInput = { nodes: [], edges: [], direction: "down" };
        for (const child of model.children) {
            if (child instanceof GNode) {
                input.nodes.push({ id: child.id, ...this.sizeOf(child.id, bounds) });
            } else if (child instanceof GEdge) {
                const labels: EdgeLabelSpec[] = [];
                for (const label of child.children) {
                    if (label instanceof GAssociationPropertyNode) {
                        labels.push(this.labelSpec(label, label.end, "left", bounds));
                    } else if (label instanceof GAssociationMultiplicityNode) {
                        labels.push(this.labelSpec(label, label.end, "right", bounds));
                    }
                }
                const edge: LayoutInputEdge = {
                    id: child.id,
                    sourceId: child.sourceId,
                    targetId: child.targetId,
                    labels
                };
                if (child instanceof GAssociationEdge) {
                    edge.sourceMarker = this.markerOf(child.sourceKind);
                    edge.targetMarker = this.markerOf(child.targetKind);
                } else if (child instanceof GInheritanceEdge) {
                    edge.targetMarker = INHERITANCE_MARKER;
                    // supertypes above their subtypes, inheritance edges join into one trunk at the supertype
                    edge.reversed = true;
                    edge.targetMergeKey = "inheritance";
                }
                input.edges.push(edge);
            }
        }
        return input;
    }

    /**
     * Returns the marker drawn for an association end kind.
     *
     * @param kind the end kind
     * @returns the marker, undefined if none is drawn
     */
    private markerOf(kind: AssociationEndKind): LayoutMarker | undefined {
        if (kind === AssociationEndKind.COMPOSITION) {
            return DIAMOND_MARKER;
        }
        if (kind === AssociationEndKind.ARROW) {
            return ARROW_MARKER;
        }
        return undefined;
    }
}
