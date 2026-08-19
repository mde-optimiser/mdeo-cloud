import type { IconNode } from "lucide";
import { sharedImport, DefaultIconRegistry } from "@mdeo/editor-shared";
import { PlainLineIcon, UnidirectionalAssociationIcon, CompositionIcon } from "./customIcons.js";
import { MetamodelIcon } from "@mdeo/protocol-metamodel";

const { injectable } = sharedImport("inversify");

/**
 * Icon registry for the metamodel editor.
 *
 * Extends the default lucide-backed registry with the custom SVG icons
 * used by the metamodel diagram (association arrows, composition diamond,
 * and the plain line representing a non-navigable end).
 *
 * Icon names are resolved in the following order:
 * 1. Built-in metamodel icons matched by name.
 * 2. Standard lucide icons via the parent implementation.
 *
 * The custom names are the members of {@link MetamodelIcon}; the server names the same
 * constants when it puts an icon on a context item.
 */
@injectable()
export class MetamodelIconRegistry extends DefaultIconRegistry {
    /**
     * Resolves a metamodel-specific icon name before falling back to the lucide
     * library via the parent class.
     *
     * @param iconName Icon name in lower-case kebab-case format
     * @returns The matching {@link IconNode}, or `undefined` when not found in
     *   either the metamodel palette or the lucide library
     */
    protected override getIconNode(iconName: string): IconNode | undefined {
        switch (iconName) {
            case MetamodelIcon.NONE_ASSOCIATION:
                return PlainLineIcon;
            case MetamodelIcon.UNIDIRECTIONAL_ASSOCIATION:
                return UnidirectionalAssociationIcon;
            case MetamodelIcon.COMPOSITION:
                return CompositionIcon;
            default:
                return super.getIconNode(iconName);
        }
    }
}
