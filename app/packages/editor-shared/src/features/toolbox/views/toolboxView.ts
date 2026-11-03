import type { VNode } from "snabbdom";
import { sharedImport } from "../../../sharedImport.js";
import type { Toolbox } from "../toolbox.js";
import { ToolType, isCreationTool } from "../toolboxTypes.js";
import { generateToolbarView } from "./toolbarView.js";
import { generateDetailsPanelView } from "./detailsPanelView.js";
import { generateErrorView } from "./errorView.js";

const { html, matchesKeystroke } = sharedImport("@eclipse-glsp/sprotty");

/**
 * Generates the complete toolbox view.
 *
 * @param context The toolbox context
 * @returns The complete toolbox VNode
 */
export function generateToolboxView(context: Toolbox): VNode {
    return html(
        "div",
        {
            class: {
                toolbox: true,
                absolute: true,
                "top-4": true,
                "right-4": true,
                flex: true,
                "flex-col": true,
                "z-50": true,
                "pointer-events-none": true
            }
        },
        generateToolboxInternal(context)
    );
}

/**
 * Generates the internal toolbox layout.
 *
 * @param context The toolbox context
 * @returns The internal toolbox VNode
 */
function generateToolboxInternal(context: Toolbox): VNode {
    const hasItems = context.hasToolboxItems();

    return html(
        "div",
        {
            class: {
                flex: true,
                "flex-col": true,
                "gap-2": true,
                "w-[calc(var(--editor-spacing)*68-2px)]": context.isOpen,
                "h-[50vh]": context.isOpen && context.isBottomPanelOpen && !context.layoutPanelOpen,
                "w-[calc(var(--editor-spacing)*10+2px)]": !context.isOpen,
                "h-[calc(var(--editor-spacing)*10+2px)]": !context.isOpen,
                "transition-all": true,
                "duration-300": true,
                "ease-in-out": true,
                "pointer-events-none": true
            },
            on: {
                keydown: (event: KeyboardEvent) => {
                    if (matchesKeystroke(event, "Escape") && context.toolType !== ToolType.HAND) {
                        context.updateTool(ToolType.POINTER);
                    }
                }
            }
        },
        html(
            "div",
            {
                class: {
                    "min-h-0": true,
                    flex: true,
                    "flex-col": true,
                    relative: true
                }
            },
            generateToolbarView(context),
            context.isOpen ? context.generateLayoutPanel() : undefined,
            context.isOpen && !context.layoutPanelOpen ? context.generateToolbarExtension() : undefined,
            !context.layoutPanelOpen &&
                hasItems &&
                context.toolType !== ToolType.HAND &&
                context.toolType !== ToolType.MARQUEE &&
                !isCreationTool(context.toolType)
                ? generateDetailsPanelView(context)
                : undefined,
            generateErrorView(context)
        )
    );
}
