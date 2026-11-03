import type { Args, PaletteItem } from "@eclipse-glsp/protocol";
import type { ModelState } from "@mdeo/language-shared";
import { BaseToolPaletteItemProvider, sharedImport } from "@mdeo/language-shared";
import type { ModelTransformationType } from "../../grammar/modelTransformationTypes.js";
import type { TriggerActionOperation } from "@mdeo/protocol-common";
import {
    EndNodeKind,
    ModelTransformationElementType,
    TARGET_MODELS_PALETTE_GROUP_ID
} from "@mdeo/protocol-model-transformation";
import type { GModelElement } from "@eclipse-glsp/server";
import { headerLineTemplates, primaryEndNodeId, targetModels } from "./modelTransformationHeader.js";
import { buildInsertDeclarationAction } from "./handler/addHeaderDeclarationOperationHandler.js";

/**
 * The action type of the dialog importing another transformation (see
 * {@link ImportTransformationActionHandler}).
 */
export const IMPORT_TRANSFORMATION_ACTION = "import-transformation";

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
 * - Declarations: Items adding a line to the header (an input, an output, a model input, a list
 *   of model inputs, an import of another transformation)
 * - Setup: Shown when the metamodel import is missing
 *
 * It also lists the models new pattern instances can be created in, in a group the toolbox shows
 * as its target-model selector (see {@link TARGET_MODELS_PALETTE_GROUP_ID}).
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

        const declarationItems = this.createDeclarationPaletteItems(sourceModel!);
        if (declarationItems.length > 0) {
            result.push({
                id: "declarations-group",
                label: "Declarations",
                actions: [],
                children: declarationItems,
                icon: "symbol-parameter",
                sortString: "C"
            });
        }

        result.push({
            id: TARGET_MODELS_PALETTE_GROUP_ID,
            label: "Target Models",
            actions: [],
            children: targetModels(sourceModel!, this.modelState.languageServices.shared.AstReflection).map(
                (model) => ({ id: model.name, label: model.label, sortString: model.name, actions: [] })
            ),
            sortString: "Z"
        });

        return result;
    }

    /**
     * Creates the items adding a line to the header of the transformation. An input inserts a
     * prefilled, fresh row into the start node, an output into the primary successful end node
     * (see {@link primaryEndNodeId}; without one there is no Output item), and starts editing
     * it; the import opens a dialog choosing the transformation to import.
     *
     * @param sourceModel The transformation
     * @returns The palette items
     */
    protected createDeclarationPaletteItems(sourceModel: ModelTransformationType): PaletteItem[] {
        const root = this.modelState.root;
        const start = root?.children.find((child) => child.type === ModelTransformationElementType.NODE_START);
        const stopNodes = (root?.children ?? []).filter(
            (child) =>
                child.type === ModelTransformationElementType.NODE_END &&
                (child as { kind?: EndNodeKind }).kind === EndNodeKind.STOP
        );
        const primaryEndId = primaryEndNodeId(stopNodes.map((node) => node.id));
        const end = stopNodes.find((node) => node.id === primaryEndId);

        const templates = headerLineTemplates(sourceModel);
        const items: PaletteItem[] = [];
        const lines: [string, string, string, GModelElement | undefined][] = [
            ["input", "Input", templates.input, start],
            ["output", "Output", templates.output, end],
            ["model", "Model Input", templates.model, start],
            ["model-list", "Model List Input", templates.modelList, start]
        ];
        for (const [id, label, text, node] of lines) {
            const action = node != undefined ? buildInsertDeclarationAction(node, text) : undefined;
            if (action != undefined) {
                items.push({
                    id: `declaration-${id}`,
                    sortString: `${items.length}`,
                    label,
                    actions: [action]
                });
            }
        }

        const importOperation: TriggerActionOperation = {
            kind: "triggerAction",
            actionType: IMPORT_TRANSFORMATION_ACTION,
            languageId: this.modelState.languageServices.LanguageMetaData.languageId,
            data: {
                uri: this.modelState.sourceUri ?? ""
            },
            isOperation: true
        };
        items.push({
            id: "declaration-import",
            sortString: `${items.length}`,
            label: "Import…",
            actions: [importOperation]
        });
        return items;
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
