import type { GModelElement, RenderingContext } from "@eclipse-glsp/sprotty";
import type { VNode } from "snabbdom";
import { sharedImport } from "@mdeo/editor-shared";
import { ModelTransformationElementType } from "@mdeo/protocol-model-transformation";

const { html } = sharedImport("@eclipse-glsp/sprotty");

/**
 * Checks whether a start or end node lists declarations (its inputs or the outputs), i.e. has
 * the compartment of declaration rows, including one that is being added.
 *
 * @param children The node's children
 * @returns `true` when the node is drawn with its declarations
 */
export function hasDeclarationRows(children: readonly GModelElement[]): boolean {
    return children.some(
        (child) =>
            child.type === ModelTransformationElementType.COMPARTMENT &&
            (child as { children?: readonly GModelElement[] }).children?.some(
                (row) => row.type === ModelTransformationElementType.LABEL_DECLARATION
            ) === true
    );
}

/**
 * Renders a start or end node that lists declarations: the node's symbol with one line of plain
 * text per declaration, centered, above the symbol (the inputs of the start node) or below it (the
 * outputs of an end node), so the control flow still enters and leaves at the symbol.
 *
 * @param symbol The node's symbol (the start dot, the end bullseye)
 * @param context The rendering context
 * @param children The node's children (the compartment of rows)
 * @param position Where the text goes relative to the symbol
 * @returns The symbol with its text
 */
export function renderDeclarationText(
    symbol: VNode,
    context: RenderingContext,
    children: readonly GModelElement[],
    position: "above" | "below"
): VNode {
    const content = children
        .map((child) => context.renderElement(child))
        .filter((vnode): vnode is VNode => vnode !== undefined);
    const text = html(
        "div",
        {
            class: { "text-foreground": true },
            style: {
                display: "flex",
                flexDirection: "column",
                alignItems: "center",
                [position === "above" ? "paddingBottom" : "paddingTop"]: "4px"
            }
        },
        ...content
    );
    return html(
        "div",
        {
            class: { "box-border": true, flex: true, "flex-col": true, "items-center": true },
            style: { width: "max-content" }
        },
        ...(position === "above" ? [text, symbol] : [symbol, text])
    );
}
