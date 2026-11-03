import { sharedImport, GEdgeView, type EdgeLabelElement, EdgeAttachmentPosition } from "@mdeo/editor-shared";
import type { GLinkEdge } from "../model/linkEdge.js";
import { GLinkEndNode } from "../model/linkEndNode.js";

const { injectable } = sharedImport("inversify");

/**
 * View for rendering link edges between objects.
 * Renders edges as polylines with labels at source/target ends.
 */
@injectable()
export class GLinkEdgeView extends GEdgeView {
    protected override getLabelElements(model: Readonly<GLinkEdge>): EdgeLabelElement[] {
        const labels: EdgeLabelElement[] = [];

        for (const child of model.children) {
            if (child instanceof GLinkEndNode) {
                labels.push({
                    element: child,
                    position:
                        child.end === "source" ? EdgeAttachmentPosition.SOURCE_LEFT : EdgeAttachmentPosition.TARGET_LEFT
                });
            }
        }

        return labels;
    }
}
