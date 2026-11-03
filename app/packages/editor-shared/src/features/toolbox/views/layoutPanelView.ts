import type { VNode } from "snabbdom";
import type { IconNode } from "lucide";
import { sharedImport } from "../../../sharedImport.js";
import type { Toolbox } from "../toolbox.js";
import { generateIcon } from "./iconView.js";
import { listItemClasses } from "./styles.js";
import type { LayoutAlgorithmOption } from "../layoutAlgorithmPreference.js";

const { html } = sharedImport("@eclipse-glsp/sprotty");
const { Check } = sharedImport("lucide") as { Check: IconNode };

/**
 * Generates the layout panel, shown in place of the bottom panel while the "Auto Layout" tool is active.
 * It lists the layout algorithms; the active (default) one is marked, clicking another one lays the diagram
 * out again with it and makes it the default.
 *
 * @param context The toolbox
 * @returns The layout panel VNode
 */
export function generateLayoutPanelView(context: Toolbox): VNode {
    return html(
        "div",
        {
            key: "__layout-panel",
            class: {
                flex: true,
                "flex-col": true,
                "mt-2": true,
                "overflow-hidden": true,
                "shadow-[0_8px_16px_rgba(0,0,0,0.12)]": true,
                "rounded-md": true,
                "shrink-0": true,
                "pointer-events-auto": true
            }
        },
        html(
            "div",
            {
                class: {
                    "toolbox-layout": true,
                    "rounded-md": true,
                    border: true,
                    "border-border": true,
                    "bg-toolbox": true,
                    flex: true,
                    "flex-col": true,
                    "px-1": true,
                    "py-2": true
                }
            },
            html(
                "div",
                {
                    class: {
                        "px-2": true,
                        "py-1": true,
                        "text-xs": true,
                        "font-medium": true,
                        "text-muted-foreground": true,
                        uppercase: true,
                        "tracking-wider": true
                    }
                },
                "Layout algorithm"
            ),
            html(
                "div",
                { attrs: { role: "listbox", "aria-label": "Layout algorithm" } },
                ...context.layoutAlgorithmOptions().map((option) => generateAlgorithmItem(context, option))
            )
        )
    );
}

/**
 * Generates the list item of one algorithm.
 *
 * @param context The toolbox
 * @param option The algorithm
 * @returns The item VNode
 */
function generateAlgorithmItem(context: Toolbox, option: LayoutAlgorithmOption): VNode {
    const isActive = context.layoutAlgorithm === option.id;
    return html(
        "button",
        {
            key: option.id,
            class: {
                ...listItemClasses,
                "items-start": true,
                "bg-accent": isActive,
                "text-accent-foreground": isActive
            },
            attrs: {
                type: "button",
                role: "option",
                "aria-selected": isActive ? "true" : "false",
                "aria-label": `${option.label} layout`
            },
            on: { click: () => context.selectLayoutAlgorithm(option.id) }
        },
        html(
            "span",
            { class: { "flex-1": true, flex: true, "flex-col": true, "min-w-0": true } },
            html("span", { class: { "font-medium": true } }, option.label),
            html("span", { class: { "text-xs": true, "text-muted-foreground": true } }, option.description)
        ),
        isActive ? generateIcon(Check, ["h-4", "w-4", "mt-0.5", "shrink-0"]) : undefined
    );
}
