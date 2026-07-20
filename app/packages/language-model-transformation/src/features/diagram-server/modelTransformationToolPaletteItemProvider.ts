import type { Args, PaletteItem } from "@eclipse-glsp/protocol";
import type { ModelState } from "@mdeo/language-shared";
import { BaseToolPaletteItemProvider, sharedImport } from "@mdeo/language-shared";
import type { ModelTransformationType } from "../../grammar/modelTransformationTypes.js";
import type { TriggerActionOperation } from "@mdeo/protocol-common";

const { injectable, inject } = sharedImport("inversify");
const { ModelState: ModelStateKey } = sharedImport("@eclipse-glsp/server");

/**
 * Tool palette item provider for the model transformation diagram.
 * Extends BaseToolPaletteItemProvider to inherit automatic handling of
 * ToolboxItemProviders (including CreatePatternInstanceOperationHandler).
 *
 * The palette is organized into sections:
 * - Create: One item per class for creating new pattern instances (abstract classes are
 *   only offered when the selected modifier is not "create", since they cannot be instantiated)
 * - Add Instance: Items for referencing/deleting already-declared instances (persist/delete mode only)
 * - Setup: Shown when the metamodel import is missing
 */
@injectable()
export class ModelTransformationToolPaletteItemProvider extends BaseToolPaletteItemProvider {
    @inject(ModelStateKey)
    protected modelState!: ModelState;

    private counter = 0;

    /**
     * Constructs the list of palette items for the model transformation diagram.
     * Shows a "Setup" section if the import is missing, otherwise shows
     * grouped create / add-instance sections based on the mode arg.
     *
     * @param args Optional arguments including `mode` (NodeCreationMode string)
     * @returns A promise resolving to the ordered list of top-level `PaletteItem` groups
     */
    override async getItems(args?: Args): Promise<PaletteItem[]> {
        this.counter = 0;
        const sourceModel = this.modelState.sourceModel as ModelTransformationType | undefined;
        const hasImport = sourceModel?.import != undefined;

        if (!hasImport) {
            return [
                {
                    id: "setup-group",
                    label: "Setup",
                    actions: [],
                    children: this.createSetupPaletteItems(),
                    icon: "settings",
                    sortString: "A"
                }
            ];
        }

        const grouped = await this.getGroupedItems(args);
        const result: PaletteItem[] = [];

        const createItems = grouped.get("create-group");
        if (createItems && createItems.length > 0) {
            result.push({
                id: "create-group",
                label: "Create",
                actions: [],
                children: createItems,
                icon: "add",
                sortString: "A"
            });
        }

        const addItems = grouped.get("add-group");
        if (addItems && addItems.length > 0) {
            result.push({
                id: "add-group",
                label: "Add Instance",
                actions: [],
                children: addItems,
                icon: "add",
                sortString: "B"
            });
        }

        return result;
    }

    /**
     * Creates setup palette items shown when the metamodel import is missing.
     * Produces a single "Setup Transformation" item that triggers a `new-file` action,
     * allowing the user to select or create the metamodel import.
     *
     * @returns An array containing the single setup palette item
     */
    protected createSetupPaletteItems(): PaletteItem[] {
        const languageId = this.modelState.languageServices.LanguageMetaData.languageId;

        const setupOperation: TriggerActionOperation = {
            kind: "triggerAction",
            actionType: "new-file",
            languageId,
            data: {
                uri: this.modelState.sourceUri ?? ""
            },
            isOperation: true
        };

        this.counter++;
        return [
            {
                id: `palette-item-${this.counter}`,
                sortString: "A",
                label: "Setup Transformation",
                icon: "settings",
                actions: [setupOperation]
            }
        ];
    }
}
