import type { GModelElement, RenderingContext } from "@eclipse-glsp/sprotty";
import type { VNode } from "snabbdom";
import { sharedImport, GNodeView, type GNode } from "@mdeo/editor-shared";
import type { GEndNode } from "../model/endNode.js";
import { EndNodeKind } from "@mdeo/protocol-model-transformation";
import { hasDeclarationRows, renderDeclarationText } from "./declarationText.js";

const { injectable } = sharedImport("inversify");
const { html } = sharedImport("@eclipse-glsp/sprotty");

/**
 * View for rendering end nodes.
 * Renders differently based on kind:
 * - STOP: Filled circle with hollow outer ring (bullseye)
 * - KILL: X mark (cross)
 * Uses HTML divs so the bounding box correctly includes the border (box-border).
 * A successful end of a transformation with outputs lists them below the bullseye, as plain
 * text.
 */
@injectable()
export class GEndNodeView extends GNodeView {
    /**
     * The outer radius of the end node
     */
    static readonly OUTER_RADIUS = 14;

    /**
     * The size of the kill node
     */
    static readonly KILL_SIZE = 24;

    /**
     * Renders the end node based on its kind.
     *
     * @param model The node model
     * @param context The rendering context
     * @param children The child elements (the compartment of output rows, if any)
     * @returns The rendered VNode
     */
    protected override renderForeignElement(
        model: Readonly<GNode>,
        context: RenderingContext,
        children: readonly GModelElement[]
    ): VNode {
        const endModel = model as GEndNode;
        if (endModel.kind === EndNodeKind.KILL) {
            return this.renderKillNode();
        }
        if (hasDeclarationRows(children)) {
            return renderDeclarationText(
                this.renderStopNode(GEndNodeView.OUTER_RADIUS * 2),
                context,
                children,
                "below"
            );
        }
        return this.renderStopNode(GEndNodeView.OUTER_RADIUS * 2);
    }

    /**
     * Renders a stop node as a bullseye (outer ring div + inner filled circle div).
     *
     * @param outerDiameter The diameter of the outer ring
     * @returns VNode for the stop node
     */
    private renderStopNode(outerDiameter: number): VNode {
        const innerCircle = html("div", {
            class: {
                "bg-foreground": true,
                "rounded-full": true,
                "w-full": true,
                "h-full": true
            }
        });

        return html(
            "div",
            {
                class: {
                    "border-2": true,
                    "border-foreground": true,
                    "rounded-full": true,
                    "box-border": true,
                    "cursor-pointer": true,
                    "p-[3px]": true
                },
                style: {
                    width: `${outerDiameter}px`,
                    height: `${outerDiameter}px`,
                    position: "relative"
                }
            },
            innerCircle
        );
    }

    /**
     * Renders a kill node as an X mark using two rotated div bars.
     *
     * @returns VNode for the kill node
     */
    private renderKillNode(): VNode {
        const size = GEndNodeView.KILL_SIZE;
        const barThickness = 3;
        const padding = 4;
        const lineLength = Math.round(Math.SQRT2 * (size - 2 * padding));

        const barStyle = {
            position: "absolute",
            width: `${lineLength}px`,
            height: `${barThickness}px`,
            top: `${(size - barThickness) / 2}px`,
            left: `${(size - lineLength) / 2}px`,
            borderRadius: "2px"
        };

        const line1 = html("div", {
            class: { "bg-foreground": true, "box-border": true },
            style: { ...barStyle, transform: "rotate(45deg)" }
        });

        const line2 = html("div", {
            class: { "bg-foreground": true, "box-border": true },
            style: { ...barStyle, transform: "rotate(-45deg)" }
        });

        return html(
            "div",
            {
                class: { "box-border": true, "cursor-pointer": true },
                style: {
                    width: `${size}px`,
                    height: `${size}px`,
                    position: "relative"
                }
            },
            line1,
            line2
        );
    }
}
