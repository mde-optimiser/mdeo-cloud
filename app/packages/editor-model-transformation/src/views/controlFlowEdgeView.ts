import {
    sharedImport,
    GEdgeView,
    type EdgeMarkerData,
    type GEdge,
    type EdgeLabelElement,
    EdgeAttachmentPosition
} from "@mdeo/editor-shared";
import { GControlFlowLabelNode } from "../model/controlFlowLabelNode.js";

const { injectable } = sharedImport("inversify");
const { svg } = sharedImport("@eclipse-glsp/sprotty");

/**
 * View for rendering control flow edges.
 * Renders edges with an arrow marker at the target end.
 */
@injectable()
export class GControlFlowEdgeView extends GEdgeView {
    protected override getLabelElements(model: Readonly<GEdge>): EdgeLabelElement[] {
        const labels: EdgeLabelElement[] = [];

        for (const child of model.children) {
            if (child instanceof GControlFlowLabelNode) {
                labels.push({ element: child, position: EdgeAttachmentPosition.MIDDLE_LEFT });
            }
        }

        return labels;
    }

    protected override renderTargetMarker(): EdgeMarkerData {
        const arrow = svg("polygon", {
            class: {
                "fill-foreground": true,
                "stroke-foreground": true,
                "stroke-[1px]": true
            },
            attrs: {
                points: "0,0 -12,-6 -12,6"
            }
        });

        return {
            marker: arrow,
            strokeOffset: 12,
            elementOffset: 6
        };
    }
}
