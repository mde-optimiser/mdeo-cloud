import type { VNode } from "snabbdom";
import { sharedImport, generateIcon, iconButtonClasses } from "@mdeo/editor-shared";
import type { ModelTransformationToolbox } from "./modelTransformationToolbox.js";
import { NodeCreationMode } from "@mdeo/protocol-model-transformation";
import type { IconNode } from "lucide";

const { html } = sharedImport("@eclipse-glsp/sprotty");
const { Square, SquarePlus, SquareX, SquareCheck, SquareSlash } = sharedImport("lucide") as {
    Square: IconNode;
    SquarePlus: IconNode;
    SquareX: IconNode;
    SquareCheck: IconNode;
    SquareSlash: IconNode;
};

interface ModeButtonDef {
    mode: NodeCreationMode;
    icon: IconNode;
    title: string;
    textClass: string;
    bgClass: string;
}

const MODE_BUTTONS: ModeButtonDef[] = [
    {
        mode: NodeCreationMode.PERSIST,
        icon: Square,
        title: "Persist (no modifier)",
        textClass: "text-foreground",
        bgClass: "bg-foreground/15"
    },
    {
        mode: NodeCreationMode.CREATE,
        icon: SquarePlus,
        title: "Create",
        textClass: "text-create",
        bgClass: "bg-create/15"
    },
    {
        mode: NodeCreationMode.DELETE,
        icon: SquareX,
        title: "Delete",
        textClass: "text-delete",
        bgClass: "bg-delete/15"
    },
    {
        mode: NodeCreationMode.REQUIRE,
        icon: SquareCheck,
        title: "Require",
        textClass: "text-require",
        bgClass: "bg-require/15"
    },
    {
        mode: NodeCreationMode.FORBID,
        icon: SquareSlash,
        title: "Forbid",
        textClass: "text-forbid",
        bgClass: "bg-forbid/15"
    }
];

/**
 * Generates the node creation mode selection bar for the model transformation toolbox.
 * Renders 5 buttons controlling which modifier is applied to newly created pattern instances.
 *
 * @param context The model transformation toolbox context
 * @returns The mode extension container VNode
 */
export function generateNodeCreationModeExtensionView(context: ModelTransformationToolbox): VNode {
    return html(
        "div",
        {
            class: {
                flex: true,
                "flex-row": true,
                "px-3": true,
                "py-2": true,
                "border-b": true,
                "border-border": true,
                "justify-center": true,
                "gap-2.5": true
            }
        },
        ...MODE_BUTTONS.map((def) => generateModeButton(context, def))
    );
}

function generateModeButton(context: ModelTransformationToolbox, def: ModeButtonDef): VNode {
    const isActive = context.selectedMode === def.mode;

    return html(
        "button",
        {
            class: {
                ...iconButtonClasses,
                [def.bgClass]: isActive,
                [def.textClass]: true,
                "border-0": true,
                "cursor-pointer": true
            },
            attrs: {
                title: def.title,
                "aria-label": def.title,
                "aria-pressed": isActive ? "true" : "false"
            },
            on: {
                click: () => context.selectMode(def.mode)
            }
        },
        generateIcon(def.icon, ["h-4", "w-4"])
    );
}
