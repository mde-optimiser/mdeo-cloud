import type { VNode } from "snabbdom";
import { sharedImport, Toolbox } from "@mdeo/editor-shared";
import type { Args } from "@eclipse-glsp/sprotty";
import { generateNodeCreationModeExtensionView } from "./nodeCreationModeExtensionView.js";
import { NodeCreationMode, TARGET_MODELS_PALETTE_GROUP_ID } from "@mdeo/protocol-model-transformation";

const { injectable } = sharedImport("inversify");

/**
 * A model new pattern instances can be created in, as the server lists it.
 */
export interface TargetModelOption {
    /**
     * The name of the model value (a `model` input or a loop variable over `List<model>`).
     */
    name: string;
    /**
     * The text shown for it.
     */
    label: string;
}

/**
 * Custom toolbox for the model transformation editor.
 * Extends the base Toolbox with a mode selector for controlling which modifier
 * (persist, create, delete, require, forbid) is applied to newly created
 * pattern object instances, and with a target-model selector choosing whether they are
 * objects of the transformed model or of one of the transformation's input models.
 */
@injectable()
export class ModelTransformationToolbox extends Toolbox {
    /**
     * The currently selected node creation mode.
     * Defaults to PERSIST (no modifier).
     */
    public selectedMode: NodeCreationMode = NodeCreationMode.PERSIST;

    /**
     * The input model new pattern instances are created in, or undefined for the transformed
     * model (the default).
     */
    public selectedModel: string | undefined = undefined;

    /**
     * The input models the transformation can create pattern instances in, as listed by the
     * server with the palette items.
     */
    public targetModels: TargetModelOption[] = [];

    /**
     * Changes the active mode and re-requests toolbox items to reflect
     * the new mode (e.g. show/hide "add instance" items).
     *
     * @param mode The new node creation mode to activate
     */
    selectMode(mode: NodeCreationMode): void {
        if (!this.isModeAvailable(mode)) {
            return;
        }
        this.selectedMode = mode;
        this.reloadPaletteBody();
    }

    /**
     * Changes the model new pattern instances are created in and re-requests the toolbox
     * items. An input model is read-only, so selecting one leaves the create and delete modes
     * for persist.
     *
     * @param model The name of the input model, or undefined for the transformed model
     */
    selectModel(model: string | undefined): void {
        this.selectedModel = model;
        if (!this.isModeAvailable(this.selectedMode)) {
            this.selectedMode = NodeCreationMode.PERSIST;
        }
        this.reloadPaletteBody();
    }

    /**
     * Checks whether a creation mode can be used with the selected target model: creating and
     * deleting are not available in an input model, which is read-only.
     *
     * @param mode The mode
     * @returns `true` when the mode can be selected
     */
    isModeAvailable(mode: NodeCreationMode): boolean {
        return (
            this.selectedModel == undefined || (mode !== NodeCreationMode.CREATE && mode !== NodeCreationMode.DELETE)
        );
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
     * Passes the current node creation mode and target model to the server so it can generate
     * the appropriate toolbox items (e.g. only show "add instance" for persist/delete modes,
     * create in a scope block of the selected input model).
     *
     * @returns Args object containing the selected mode and model
     */
    override generateRequestItemsArgs(): Args | undefined {
        const args: Args = { mode: this.selectedMode };
        if (this.selectedModel != undefined) {
            args.model = this.selectedModel;
        }
        return args;
    }

    /**
     * Requests the palette items and takes the target models out of them: the server lists
     * them as a palette group of their own, which the toolbox shows as its target-model
     * selector rather than as items. When the selected model is gone (its declaration was
     * removed or renamed), the toolbox goes back to the transformed model.
     */
    protected override async setPaletteItems(): Promise<void> {
        await super.setPaletteItems();
        this.extractTargetModels();
        if (this.selectedModel != undefined && !this.targetModels.some((model) => model.name === this.selectedModel)) {
            this.selectedModel = undefined;
            await super.setPaletteItems();
            this.extractTargetModels();
        }
    }

    /**
     * Moves the target-model group of the palette items into {@link targetModels}.
     */
    private extractTargetModels(): void {
        const group = this.paletteItems.find((item) => item.id === TARGET_MODELS_PALETTE_GROUP_ID);
        this.paletteItems = this.paletteItems.filter((item) => item !== group);
        this.targetModels = (group?.children ?? []).map((item) => ({ name: item.id, label: item.label }));
    }
}
