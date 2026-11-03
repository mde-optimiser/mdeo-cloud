import type { VNode } from "snabbdom";
import type { IconNode } from "lucide";
import { sharedImport, generateIcon, inputClasses } from "@mdeo/editor-shared";
import type { MetamodelToolbox } from "./metamodelToolbox.js";

const { html } = sharedImport("@eclipse-glsp/sprotty");
const { Layers, Plus, Pencil, Trash2, ChevronDown, ChevronUp } = sharedImport("lucide") as {
    Layers: IconNode;
    Plus: IconNode;
    Pencil: IconNode;
    Trash2: IconNode;
    ChevronDown: IconNode;
    ChevronUp: IconNode;
};

/**
 * Generates the view switcher shown below the toolbar of the metamodel toolbox.
 *
 * It lists "All" (the whole metamodel) and every view as chips; clicking a chip shows that
 * view. When the metamodel can be edited, the header offers creating a view and renaming or
 * deleting the active one; double-clicking a view's chip renames it as well. A button in the
 * corner collapses the panel to its header, which then names the view shown.
 *
 * @param context The metamodel toolbox
 * @returns The view switcher VNode
 */
export function generateViewSwitcherView(context: MetamodelToolbox): VNode {
    const allChip = generateChip(context, undefined, "All");
    // Without views there is nothing to switch between: "All" fits into the header row.
    const chips =
        context.viewsCollapsed || context.views.length === 0
            ? undefined
            : html(
                  "div",
                  { class: { flex: true, "flex-wrap": true, "gap-1.5": true }, attrs: { role: "tablist" } },
                  allChip,
                  ...context.views.map((view) =>
                      context.renamingView === view ? generateRenameInput(context) : generateChip(context, view, view)
                  )
              );

    return html(
        "div",
        {
            class: {
                "toolbox-views": true,
                flex: true,
                "flex-col": true,
                "gap-2": true,
                "mt-2": true,
                "px-3": true,
                "py-2": true,
                "rounded-md": true,
                border: true,
                "border-border": true,
                "bg-toolbox": true,
                "shadow-[0_8px_16px_rgba(0,0,0,0.12)]": true,
                "shrink-0": true,
                "pointer-events-auto": true
            }
        },
        generateHeader(context, chips == undefined && !context.viewsCollapsed ? allChip : undefined),
        chips
    );
}

/**
 * Generates the header row: the title and the view actions.
 *
 * @param context The metamodel toolbox
 * @param inlineChip A chip shown in the header itself, when the panel has no second row
 * @returns The header VNode
 */
function generateHeader(context: MetamodelToolbox, inlineChip: VNode | undefined): VNode {
    const activeView = context.activeView;
    const actions: VNode[] = [];
    if (context.canEditViews && !context.viewsCollapsed) {
        if (activeView != undefined) {
            actions.push(
                generateHeaderButton(Pencil, `Rename view "${activeView}"`, () => context.startRename(activeView)),
                generateHeaderButton(Trash2, `Delete view "${activeView}"`, () => context.deleteView(activeView))
            );
        }
        actions.push(generateHeaderButton(Plus, "New view", () => context.createView()));
    }
    actions.push(
        context.viewsCollapsed
            ? generateHeaderButton(ChevronDown, "Expand views", () => context.toggleViewsCollapsed())
            : generateHeaderButton(ChevronUp, "Collapse views", () => context.toggleViewsCollapsed())
    );

    return html(
        "div",
        { class: { flex: true, "items-center": true, "gap-2": true, "min-h-7": true } },
        generateIcon(Layers, ["h-4", "w-4", "text-muted-foreground"]),
        html(
            "span",
            {
                class: {
                    "flex-1": inlineChip == undefined && !context.viewsCollapsed,
                    "text-xs": true,
                    "font-medium": true,
                    "text-muted-foreground": true
                }
            },
            "Views"
        ),
        inlineChip != undefined
            ? html("div", { class: { "flex-1": true, flex: true }, attrs: { role: "tablist" } }, inlineChip)
            : undefined,
        context.viewsCollapsed
            ? html(
                  "span",
                  {
                      class: { "flex-1": true, "min-w-0": true, truncate: true, "text-xs": true, "font-medium": true },
                      attrs: { title: activeView ?? "All" }
                  },
                  activeView ?? "All"
              )
            : undefined,
        ...actions
    );
}

/**
 * Generates a small icon button of the header.
 *
 * @param icon The icon
 * @param title The tooltip and accessible label
 * @param onClick The click handler
 * @returns The button VNode
 */
function generateHeaderButton(icon: IconNode, title: string, onClick: () => void): VNode {
    return html(
        "button",
        {
            class: {
                "size-6": true,
                "inline-flex": true,
                "items-center": true,
                "justify-center": true,
                "rounded-md": true,
                "border-0": true,
                "bg-transparent": true,
                "text-muted-foreground": true,
                "cursor-pointer": true,
                "hover:bg-accent": true,
                "hover:text-accent-foreground": true
            },
            attrs: { type: "button", title, "aria-label": title },
            on: { click: onClick }
        },
        generateIcon(icon, ["h-3.5", "w-3.5"])
    );
}

/**
 * Generates the chip of a view, or of the whole metamodel.
 *
 * @param context The metamodel toolbox
 * @param view The view, or `undefined` for the whole metamodel
 * @param label The text of the chip
 * @returns The chip VNode
 */
function generateChip(context: MetamodelToolbox, view: string | undefined, label: string): VNode {
    const isActive = context.activeView === view;
    return html(
        "button",
        {
            key: view ?? "__all",
            class: {
                "h-7": true,
                "max-w-full": true,
                "px-2.5": true,
                "rounded-md": true,
                border: true,
                "border-border": !isActive,
                "border-transparent": isActive,
                "text-xs": true,
                "font-medium": true,
                truncate: true,
                "cursor-pointer": true,
                "transition-colors": true,
                "bg-accent": isActive,
                "text-accent-foreground": isActive,
                "bg-transparent": !isActive,
                "hover:bg-accent": true,
                "hover:text-accent-foreground": true
            },
            attrs: {
                type: "button",
                role: "tab",
                title: view == undefined ? "Show the whole metamodel" : `Show view "${view}"`,
                "aria-selected": isActive ? "true" : "false"
            },
            on: {
                click: () => context.selectView(view),
                dblclick: () => {
                    if (view != undefined && context.canEditViews) {
                        context.startRename(view);
                    }
                }
            }
        },
        label
    );
}

/**
 * Generates the input replacing the chip of the view being renamed.
 * Enter or leaving the input renames the view, Escape cancels.
 *
 * @param context The metamodel toolbox
 * @returns The input VNode
 */
function generateRenameInput(context: MetamodelToolbox): VNode {
    return html("input", {
        key: "__rename",
        class: { ...inputClasses, "h-7": true, "w-36": true, "px-2": true, "text-xs": true, "bg-background": true },
        props: { value: context.renameDraft },
        attrs: { type: "text", "aria-label": "View name", spellcheck: "false" },
        hook: {
            insert: (vnode) => {
                const input = vnode.elm as HTMLInputElement;
                input.focus();
                input.select();
            }
        },
        on: {
            input: (event: Event) => {
                context.renameDraft = (event.target as HTMLInputElement).value;
            },
            keydown: (event: KeyboardEvent) => {
                event.stopPropagation();
                if (event.key === "Enter") {
                    event.preventDefault();
                    context.commitRename();
                } else if (event.key === "Escape") {
                    event.preventDefault();
                    context.cancelRename();
                }
            },
            blur: () => context.commitRename()
        }
    });
}
