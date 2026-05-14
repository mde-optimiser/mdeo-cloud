import type { GModelElement, RenderingContext } from "@eclipse-glsp/sprotty";
import type { VNode } from "snabbdom";
import { sharedImport, GNodeView, type GNode } from "@mdeo/editor-shared";

const { injectable } = sharedImport("inversify");
const { html } = sharedImport("@eclipse-glsp/sprotty");

/**
 * View for rendering split nodes.
 * Renders a small filled diamond where control flow branches split back together.
 * Uses an HTML div (rotated inner square) so the bounding box correctly includes the border.
 */
@injectable()
export class GSplitNodeView extends GNodeView {
    /**
     * The bounding-box size of the split node diamond (width == height)
     */
    static readonly SIZE = 25;

    /**
     * Renders the split node as a filled diamond using a rotated HTML div.
     * The outer container defines the bounding box; an inner square rotated 45°
     * produces the diamond shape.
     *
     * @param _model The node model
     * @param _context The rendering context
     * @returns The rendered VNode
     */
    protected override renderForeignElement(
        _model: Readonly<GNode>,
        _context: RenderingContext,
        _children: readonly GModelElement[]
    ): VNode {
        const size = GSplitNodeView.SIZE;
        const innerSize = Math.round(size / Math.SQRT2);
        const offset = (size - innerSize) / 2;
        const innerDiamond = html("div", {
            class: {
                "border-2": true,
                "border-foreground": true,
                "box-border": true
            },
            style: {
                width: `${innerSize}px`,
                height: `${innerSize}px`,
                position: "absolute",
                top: `${offset}px`,
                left: `${offset}px`,
                transform: "rotate(45deg)"
            }
        });
        return html(
            "div",
            {
                class: {
                    "box-border": true,
                    "cursor-pointer": true
                },
                style: {
                    width: `${size}px`,
                    height: `${size}px`,
                    position: "relative"
                }
            },
            innerDiamond
        );
    }
}
