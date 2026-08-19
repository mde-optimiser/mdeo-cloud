import type { VNode } from "snabbdom";
import { sharedImport, Toolbox } from "@mdeo/editor-shared";
import type { Args } from "@eclipse-glsp/sprotty";
import { generateNodeCreationModeExtensionView } from "./nodeCreationModeExtensionView.js";
import { NodeCreationMode } from "@mdeo/protocol-model-transformation";

const { injectable } = sharedImport("inversify");

/**
 * Custom toolbox for the model transformation editor.
 * Extends the base Toolbox with a mode selector for controlling which modifier
 * (persist, create, delete, require, forbid) is applied to newly created
 * pattern object instances.
 */
@injectable()
export class ModelTransformationToolbox extends Toolbox {
    /**
     * The currently selected node creation mode.
     * Defaults to PERSIST (no modifier).
     */
    public selectedMode: NodeCreationMode = NodeCreationMode.PERSIST;

    /**
     * Changes the active mode and re-requests toolbox items to reflect
     * the new mode (e.g. show/hide "add instance" items).
     *
     * @param mode The new node creation mode to activate
     */
    selectMode(mode: NodeCreationMode): void {
        this.selectedMode = mode;
        this.reloadPaletteBody();
    }

    /**
     * Injects the node creation mode selection bar above the search input.
     *
     * @returns VNodes for the mode selection buttons row
     */
    override generateDetailsExtension(): VNode[] {
        return [generateNodeCreationModeExtensionView(this)];
    }

    /**
     * Passes the current node creation mode to the server so it can generate
     * the appropriate toolbox items (e.g. only show "add instance" for
     * persist/delete modes).
     *
     * @returns Args object containing the selected mode
     */
    override generateRequestItemsArgs(): Args | undefined {
        return { mode: this.selectedMode };
    }
}
