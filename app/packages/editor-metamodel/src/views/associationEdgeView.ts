import {
    sharedImport,
    GEdgeView,
    type EdgeLabelElement,
    EdgeAttachmentPosition,
    type EdgeMarkerData
} from "@mdeo/editor-shared";
import type { GAssociationEdge } from "../model/associationEdge.js";
import { GAssociationPropertyNode } from "../model/associationPropertyNode.js";
import { GAssociationMultiplicityNode } from "../model/associationMultiplicityNode.js";
import { AssociationEndKind } from "@mdeo/protocol-metamodel";

const { injectable } = sharedImport("inversify");
const { svg } = sharedImport("@eclipse-glsp/sprotty");

/**
 * View for rendering association edges.
 * Renders edges as polylines with association styling and attachment labels for multiplicity and properties.
 * Supports rendering composition diamonds and navigability arrows at edge ends.
 */
@injectable()
export class GAssociationEdgeView extends GEdgeView {
    protected override getLabelElements(model: Readonly<GAssociationEdge>): EdgeLabelElement[] {
        const labels: EdgeLabelElement[] = [];

        for (const child of model.children) {
            if (child instanceof GAssociationPropertyNode) {
                labels.push({
                    element: child,
                    position:
                        child.end == "source" ? EdgeAttachmentPosition.SOURCE_LEFT : EdgeAttachmentPosition.TARGET_LEFT
                });
            } else if (child instanceof GAssociationMultiplicityNode) {
                labels.push({
                    element: child,
                    position:
                        child.end == "source"
                            ? EdgeAttachmentPosition.SOURCE_RIGHT
                            : EdgeAttachmentPosition.TARGET_RIGHT
                });
            }
        }

        return labels;
    }

    protected override renderSourceMarker(model: Readonly<GAssociationEdge>): EdgeMarkerData | undefined {
        return this.createMarkerForKind(model.sourceKind, true);
    }

    protected override renderTargetMarker(model: Readonly<GAssociationEdge>): EdgeMarkerData | undefined {
        return this.createMarkerForKind(model.targetKind, false);
    }

    /**
     * Creates marker data for the given end kind.
     *
     * @param kind The kind of decoration to render
     * @param isSource Whether this is the source end (affects orientation)
     * @returns Edge marker data or undefined if no marker is needed
     */
    private createMarkerForKind(kind: AssociationEndKind, isSource: boolean): EdgeMarkerData | undefined {
        if (kind === AssociationEndKind.COMPOSITION) {
            return this.createDiamondMarker(isSource);
        }
        if (kind === AssociationEndKind.ARROW) {
            return this.createArrowMarker(isSource);
        }
        return undefined;
    }

    /**
     * Creates a filled diamond marker for composition.
     *
     * @param isSource Whether this is the source end
     * @returns Edge marker data with diamond shape
     */
    private createDiamondMarker(isSource: boolean): EdgeMarkerData {
        const points = isSource ? "0,0 10,-6 20,0 10,6" : "0,0 -10,-6 -20,0 -10,6";
        const diamond = svg("polygon", {
            class: {
                "fill-foreground": true,
                "stroke-foreground": true,
                "stroke-[1px]": true
            },
            attrs: {
                points
            }
        });

        return {
            marker: diamond,
            strokeOffset: 20,
            elementOffset: 14
        };
    }

    /**
     * Creates an arrow marker for navigability.
     *
     * @param isSource Whether this is the source end
     * @returns Edge marker data with arrow shape
     */
    private createArrowMarker(isSource: boolean): EdgeMarkerData {
        const points = isSource ? "0,0 12,-6 12,6" : "0,0 -12,-6 -12,6";
        const arrow = svg("polygon", {
            class: {
                "fill-foreground": true,
                "stroke-foreground": true,
                "stroke-[1px]": true
            },
            attrs: {
                points
            }
        });

        return {
            marker: arrow,
            strokeOffset: 12,
            elementOffset: 6
        };
    }
}
