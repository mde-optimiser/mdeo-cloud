import type { VNode } from "snabbdom";
import { sharedImport, generateIcon, iconButtonClasses, inputClasses } from "@mdeo/editor-shared";
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
 * Value of the target-model selector standing for the transformed model.
 */
const TRANSFORMED_MODEL_OPTION = "";

/**
 * Generates the node creation mode selection bar for the model transformation toolbox.
 * Renders 5 buttons controlling which modifier is applied to newly created pattern instances
 * and, when the transformation has input models, a selector choosing the model they are
 * created in (create and delete are not available in an input model, which is read-only).
 *
 * @param context The model transformation toolbox context
 * @returns The mode extension container VNode
 */
export function generateNodeCreationModeExtensionView(context: ModelTransformationToolbox): VNode {
    const modeBar = html(
        "div",
        {
            class: {
                flex: true,
                "flex-row": true,
                "justify-center": true,
                "gap-2.5": true
            }
        },
        ...MODE_BUTTONS.map((def) => generateModeButton(context, def))
    );
    return html(
        "div",
        {
            class: {
                flex: true,
                "flex-col": true,
                "gap-2": true,
                "px-3": true,
                "py-2": true,
                "border-b": true,
                "border-border": true
            }
        },
        modeBar,
        ...(context.targetModels.length > 0 ? [generateTargetModelSelector(context)] : [])
    );
}

/**
 * Generates the selector of the model new pattern instances are created in: the transformed
 * model or one of the input models the server listed.
 *
 * @param context The model transformation toolbox context
 * @returns The selector VNode
 */
function generateTargetModelSelector(context: ModelTransformationToolbox): VNode {
    const selected = context.selectedModel ?? TRANSFORMED_MODEL_OPTION;
    const options = [
        { value: TRANSFORMED_MODEL_OPTION, label: "transformed model" },
        ...context.targetModels.map((model) => ({ value: model.name, label: `in ${model.label}` }))
    ];
    return html(
        "label",
        {
            class: {
                flex: true,
                "flex-row": true,
                "items-center": true,
                "gap-2": true,
                "text-sm": true,
                "text-muted-foreground": true
            }
        },
        html("span", { class: { "shrink-0": true } }, "Create in"),
        html(
            "select",
            {
                class: {
                    ...inputClasses,
                    "h-8": true,
                    "h-9": false,
                    "px-2": true,
                    "px-3": false,
                    "text-foreground": true,
                    "bg-background": true,
                    "cursor-pointer": true
                },
                attrs: {
                    title: "The model new pattern instances are created in",
                    "aria-label": "Target model"
                },
                props: { value: selected },
                on: {
                    change: (event: Event) => {
                        const value = (event.target as HTMLSelectElement).value;
                        context.selectModel(value === TRANSFORMED_MODEL_OPTION ? undefined : value);
                    }
                }
            },
            ...options.map((option) =>
                html(
                    "option",
                    { attrs: { value: option.value }, props: { selected: option.value === selected } },
                    option.label
                )
            )
        )
    );
}

function generateModeButton(context: ModelTransformationToolbox, def: ModeButtonDef): VNode {
    const isActive = context.selectedMode === def.mode;
    const isAvailable = context.isModeAvailable(def.mode);
    const title = isAvailable ? def.title : `${def.title} (not available in an input model, which is read-only)`;

    return html(
        "button",
        {
            class: {
                ...iconButtonClasses,
                [def.bgClass]: isActive,
                [def.textClass]: true,
                "border-0": true,
                "cursor-pointer": isAvailable
            },
            attrs: {
                title,
                "aria-label": title,
                "aria-pressed": isActive ? "true" : "false",
                disabled: !isAvailable
            },
            on: {
                click: () => context.selectMode(def.mode)
            }
        },
        generateIcon(def.icon, ["h-4", "w-4"])
    );
}
