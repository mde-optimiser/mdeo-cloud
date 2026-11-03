import type { GModelElement, RenderingContext } from "@eclipse-glsp/sprotty";
import type { VNode } from "snabbdom";
import { sharedImport, GNodeView, type GNode } from "@mdeo/editor-shared";
import { hasDeclarationRows, renderDeclarationText } from "./declarationText.js";

const { injectable } = sharedImport("inversify");
const { html } = sharedImport("@eclipse-glsp/sprotty");

/**
 * View for rendering start nodes.
 * Renders a filled circle representing the start of a control flow, similar to UML activity diagrams.
 * Uses an HTML div with border-radius so the bounding box correctly includes the border.
 * A transformation with inputs lists them above the circle, as plain text.
 */
@injectable()
export class GStartNodeView extends GNodeView {
    /**
     * The radius of the start node circle
     */
    static readonly RADIUS = 12;

    /**
     * Renders the start node as a filled circle using an HTML div, with the input rows above it
     * when the transformation has inputs.
     *
     * @param _model The node model
     * @param context The rendering context
     * @param children The child elements (the compartment of input rows, if any)
     * @returns The rendered VNode
     */
    protected override renderForeignElement(
        _model: Readonly<GNode>,
        context: RenderingContext,
        children: readonly GModelElement[]
    ): VNode {
        if (hasDeclarationRows(children)) {
            return renderDeclarationText(this.renderCircle(GStartNodeView.RADIUS * 2), context, children, "above");
        }
        return this.renderCircle(GStartNodeView.RADIUS * 2);
    }

    /**
     * Renders the filled circle.
     *
     * @param diameter The circle's diameter
     * @returns The circle
     */
    private renderCircle(diameter: number): VNode {
        return html("div", {
            class: {
                "bg-foreground": true,
                "rounded-full": true,
                "box-border": true,
                "cursor-pointer": true
            },
            style: {
                width: `${diameter}px`,
                height: `${diameter}px`
            }
        });
    }
}
