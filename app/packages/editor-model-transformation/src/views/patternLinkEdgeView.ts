import type { Point } from "@eclipse-glsp/sprotty";
import {
    sharedImport,
    GEdgeView,
    type EdgeLabelElement,
    EdgeAttachmentPosition,
    type GEdge
} from "@mdeo/editor-shared";
import type { GPatternLinkEdge } from "../model/patternLinkEdge.js";
import { GPatternLinkEndNode } from "../model/patternLinkEndNode.js";
import { GPatternLinkModifierNode } from "../model/patternLinkModifierNode.js";
import { PatternModifierKind } from "@mdeo/protocol-model-transformation";
import type { VNode } from "snabbdom";

const { injectable } = sharedImport("inversify");
const { svg } = sharedImport("@eclipse-glsp/sprotty");

/**
 * Gets CSS classes for stroke based on the modifier kind.
 *
 * @param modifier The pattern modifier kind
 * @returns CSS class string for the stroke color
 */
function getStrokeClassForModifier(modifier: PatternModifierKind): string {
    switch (modifier) {
        case PatternModifierKind.CREATE:
            return "stroke-create";
        case PatternModifierKind.DELETE:
            return "stroke-delete";
        case PatternModifierKind.FORBID:
            return "stroke-forbid";
        case PatternModifierKind.REQUIRE:
            return "stroke-require";
        default:
            return "stroke-foreground";
    }
}

/**
 * View for rendering pattern link edges between instances.
 * Renders edges as polylines with labels at source/target ends.
 * The edge color changes based on the modifier (create/delete/forbid/require).
 */
@injectable()
export class GPatternLinkEdgeView extends GEdgeView {
    protected override getLabelElements(model: Readonly<GEdge>): EdgeLabelElement[] {
        const labels: EdgeLabelElement[] = [];

        for (const child of model.children) {
            if (child instanceof GPatternLinkEndNode) {
                labels.push({
                    element: child,
                    position:
                        child.end === "source" ? EdgeAttachmentPosition.SOURCE_LEFT : EdgeAttachmentPosition.TARGET_LEFT
                });
            } else if (child instanceof GPatternLinkModifierNode) {
                labels.push({ element: child, position: EdgeAttachmentPosition.MIDDLE_LEFT });
            }
        }

        return labels;
    }

    protected override renderVisiblePath(route: Point[], model: Readonly<GEdge>): VNode {
        const pathData = this.createPathData(route);

        return svg("path", {
            class: {
                [getStrokeClassForModifier((model as GPatternLinkEdge).modifier)]: true,
                "fill-none": true,
                "stroke-[1.5px]": true
            },
            attrs: {
                d: pathData
            }
        });
    }
}
