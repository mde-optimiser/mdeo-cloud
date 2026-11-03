import type { VNode } from "snabbdom";
import { sharedImport } from "../../../sharedImport.js";
import type { Toolbox } from "../toolbox.js";
import { toolboxTools } from "../tools.js";
import type { ToolDefinition } from "../toolboxTypes.js";
import { ToolType, isCreationTool } from "../toolboxTypes.js";
import { iconButtonClasses } from "./styles.js";

const { html } = sharedImport("@eclipse-glsp/sprotty");
const { X, PencilRuler, PanelBottomOpen, PanelBottomClose } = sharedImport("lucide");

import { generateIcon } from "./iconView.js";

/**
 * Generates the toolbar view with tool buttons.
 *
 * @param context The toolbox context
 * @returns The toolbar VNode
 */
export function generateToolbarView(context: Toolbox): VNode {
    return html(
        "div",
        {
            class: {
                "toolbox-toolbar": true,
                flex: true,
                "flex-row": true,
                "rounded-md": true,
                border: true,
                "border-border": true,
                "bg-toolbox": true,
                "shadow-[0_8px_16px_rgba(0,0,0,0.12)]": true,
                "h-10": true,
                relative: true,
                "box-content": true,
                "flex-initial": true,
                "shrink-0": true,
                "overflow-hidden": true,
                "pointer-events-auto": true
            }
        },
        generateToolbarContent(context)
    );
}

/**
 * Generates a single tool button.
 *
 * @param context The toolbox context
 * @param tool The tool definition
 * @returns The tool button VNode
 */
function generateToolButton(context: Toolbox, tool: ToolDefinition): VNode {
    const isActive = context.layoutPanelOpen ? tool.id === ToolType.LAYOUT : context.toolType === tool.id;
    const hasItems = context.hasToolboxItems();

    const isDisabled =
        (tool.id === ToolType.BOTTOM_PANEL_TOGGLE &&
            !context.layoutPanelOpen &&
            (!hasItems ||
                context.toolType === ToolType.HAND ||
                context.toolType === ToolType.MARQUEE ||
                isCreationTool(context.toolType))) ||
        (tool.id === ToolType.CREATE_EDGE && context.isLayoutable);
    const isInteractable = context.isOpen && !isDisabled;
    const isPanelToggle = tool.id === ToolType.BOTTOM_PANEL_TOGGLE;
    const title = isPanelToggle ? (context.isBottomPanelOpen ? "Hide Bottom Panel" : "Show Bottom Panel") : tool.title;

    return html(
        "button",
        {
            class: {
                ...iconButtonClasses,
                "toolbox-tool-button": true,
                relative: true,
                "bg-accent": isActive,
                "text-accent-foreground": isActive,
                "border-0": true,
                "cursor-pointer": !isDisabled,
                "cursor-not-allowed": isDisabled,
                "opacity-50": isDisabled,
                "transition-all": true,
                "duration-300": true,
                "pointer-events-none": !isInteractable
            },
            attrs: {
                title,
                "aria-label": title,
                "aria-pressed": (isPanelToggle ? context.isBottomPanelOpen : isActive) ? "true" : "false",
                "aria-disabled": isDisabled ? "true" : "false",
                disabled: isDisabled
            },
            on: {
                click: () => {
                    if (!isDisabled) {
                        context.onToolClick(tool);
                    }
                }
            }
        },
        generateIcon(isPanelToggle ? (context.isBottomPanelOpen ? PanelBottomClose : PanelBottomOpen) : tool.icon, [
            "h-4",
            "w-4"
        ])
    );
}

/**
 * Generates the toolbar content based on open/closed state.
 *
 * @param context The toolbox context
 * @returns Array of toolbar content VNodes
 */
function generateToolbarContent(context: Toolbox): VNode {
    const toolButtons = toolboxTools.map((tool) => generateToolButton(context, tool));
    const divider = html("div", {
        class: {
            "toolbox-divider": true,
            "w-px": true,
            "h-6": true,
            "bg-border": true,
            "mx-1": true,
            "transition-all": true,
            "duration-30": true,
            "opacity-0": !context.isOpen,
            "pointer-events-none": !context.isOpen,
            "opacity-100": context.isOpen
        }
    });

    return html(
        "div",
        {
            class: {
                absolute: true,
                flex: true,
                "right-0": true,
                "flex-row": true,
                "items-center": true,
                "gap-1": true,
                "p-1": true
            }
        },
        ...toolButtons,
        divider,
        generateToggleButton(context)
    );
}

/**
 * Generates the toggle button for showing/hiding the details panel.
 *
 * @param context The toolbox context
 * @returns The toggle button VNode
 */
function generateToggleButton(context: Toolbox): VNode {
    const label = context.isOpen ? "Close toolbox" : "Open toolbox";
    return html(
        "button",
        {
            class: {
                ...iconButtonClasses,
                "toolbox-toggle-button": true,
                "transition-all": true,
                "border-0": true,
                "cursor-pointer": true
            },
            attrs: {
                title: label,
                "aria-label": label,
                "aria-expanded": context.isOpen ? "true" : "false"
            },
            on: {
                click: () => {
                    context.toggleToolbox();
                }
            }
        },
        generateIcon(context.isOpen ? X : PencilRuler, ["h-4", "w-4"])
    );
}
