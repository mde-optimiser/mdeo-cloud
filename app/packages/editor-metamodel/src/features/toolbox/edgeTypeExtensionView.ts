import type { VNode } from "snabbdom";
import { sharedImport, generateIcon, iconButtonClasses } from "@mdeo/editor-shared";
import type { MetamodelToolbox } from "./metamodelToolbox.js";
import {
    UnidirectionalAssociationIcon,
    BidirectionalAssociationIcon,
    CompositionIcon,
    NavigableCompositionIcon,
    ExtendsIcon
} from "../icon-registry/customIcons.js";
import type { IconNode } from "lucide";
import { EdgeCreationType } from "@mdeo/protocol-metamodel";

const { html } = sharedImport("@eclipse-glsp/sprotty");

interface EdgeTypeButtonDef {
    type: EdgeCreationType;
    icon: IconNode;
    title: string;
}

const EDGE_TYPE_BUTTONS: EdgeTypeButtonDef[] = [
    {
        type: EdgeCreationType.UNIDIRECTIONAL,
        icon: UnidirectionalAssociationIcon,
        title: "Unidirectional Association"
    },
    {
        type: EdgeCreationType.BIDIRECTIONAL,
        icon: BidirectionalAssociationIcon,
        title: "Bidirectional Association"
    },
    {
        type: EdgeCreationType.COMPOSITION,
        icon: CompositionIcon,
        title: "Composition"
    },
    {
        type: EdgeCreationType.NAVIGABLE_COMPOSITION,
        icon: NavigableCompositionIcon,
        title: "Navigable Composition"
    },
    {
        type: EdgeCreationType.EXTENDS,
        icon: ExtendsIcon,
        title: "Extends"
    }
];

/**
 * Generates the edge type selection bar for the metamodel toolbox.
 * Renders 5 buttons that control which type of edge is created with the connection tool.
 *
 * @param context The metamodel toolbox context
 * @returns The edge type extension container VNode
 */
export function generateEdgeTypeExtensionView(context: MetamodelToolbox): VNode {
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
        ...EDGE_TYPE_BUTTONS.map((def) => generateEdgeTypeButton(context, def))
    );
}

function generateEdgeTypeButton(context: MetamodelToolbox, def: EdgeTypeButtonDef): VNode {
    const isActive = context.selectedEdgeType === def.type;

    return html(
        "button",
        {
            class: {
                ...iconButtonClasses,
                "bg-accent": isActive,
                "text-accent-foreground": isActive,
                "border-0": true,
                "cursor-pointer": true
            },
            attrs: {
                title: def.title,
                "aria-label": def.title,
                "aria-pressed": isActive ? "true" : "false"
            },
            on: {
                click: () => context.selectEdgeType(def.type)
            }
        },
        generateIcon(def.icon, ["h-4", "w-4"])
    );
}
