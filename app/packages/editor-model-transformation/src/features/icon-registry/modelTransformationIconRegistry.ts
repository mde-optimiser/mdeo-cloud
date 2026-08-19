import type { IconNode } from "lucide";
import { sharedImport, DefaultIconRegistry } from "@mdeo/editor-shared";
import { VariablePlus } from "./customIcons.js";
import { ModelTransformationIcon } from "@mdeo/protocol-model-transformation";

const { injectable } = sharedImport("inversify");

/**
 * Icon registry for the model transformation editor.
 *
 * Extends the default lucide-backed registry with the custom SVG icons
 * used by the model transformation diagram.
 *
 * Icon names are resolved in the following order:
 * 1. Built-in model transformation icons matched by name.
 * 2. Standard lucide icons via the parent implementation.
 *
 * The custom names are the members of {@link ModelTransformationIcon}; the server names the
 * same constants when it puts an icon on a context item.
 */
@injectable()
export class ModelTransformationIconRegistry extends DefaultIconRegistry {
    protected override getIconNode(iconName: string): IconNode | undefined {
        switch (iconName) {
            case ModelTransformationIcon.VARIABLE_PLUS:
                return VariablePlus;
            default:
                return super.getIconNode(iconName);
        }
    }
}
