import type { GModelElement, RenderingContext } from "@eclipse-glsp/sprotty";
import type { VNode } from "snabbdom";
import { sharedImport, GNodeView, type GNode } from "@mdeo/editor-shared";
import type { GCallNode } from "../model/callNode.js";

const { injectable } = sharedImport("inversify");
const { html } = sharedImport("@eclipse-glsp/sprotty");

/**
 * View for call nodes: a box holding the statement's text. A call has a double left edge (in
 * the activity-diagram style of story diagrams) and an editable text; the head of a loop and an
 * assignment are plain boxes.
 */
@injectable()
export class GCallNodeView extends GNodeView {
    protected override renderForeignElement(
        model: Readonly<GNode>,
        context: RenderingContext,
        children: readonly GModelElement[]
    ): VNode {
        const callModel = model as GCallNode;
        const isCall = (callModel.variant ?? "call") === "call";
        const content = children
            .map((child) => context.renderElement(child))
            .filter((vnode): vnode is VNode => vnode !== undefined);
        return html(
            "div",
            {
                class: {
                    "border-2": true,
                    "border-foreground": true,
                    "box-border": true,
                    "cursor-pointer": true,
                    "bg-background": true
                },
                style: {
                    borderLeftWidth: isCall ? "6px" : "2px",
                    borderLeftStyle: isCall ? "double" : "solid",
                    borderRadius: "6px",
                    padding: "4px 10px",
                    fontFamily: "monospace",
                    fontSize: "12px",
                    whiteSpace: "nowrap"
                }
            },
            ...(content.length > 0 ? content : [callModel.text ?? ""])
        );
    }
}
