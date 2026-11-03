import type { Args, PaletteItem } from "@eclipse-glsp/protocol";
import type { TriggerActionOperation } from "@mdeo/protocol-common";
import type { ModelState } from "@mdeo/language-shared";
import { BaseToolPaletteItemProvider, sharedImport } from "@mdeo/language-shared";
import type { GModelIndex } from "@mdeo/language-shared";
import type { AstNode } from "langium";
import { AddToMetamodelViewOperation } from "@mdeo/protocol-metamodel";
import type { PartialMetaModel } from "../../grammar/metamodelPartialTypes.js";
import { Class, Enum } from "../../grammar/metamodelTypes.js";
import { getExportedEntitiesFromMetamodelFile } from "../importHelpers.js";
import { MetamodelViewState, type ResolvedView } from "./metamodelViews.js";
import { MetamodelViewEdits } from "./metamodelViewEdits.js";

const { injectable, inject } = sharedImport("inversify");
const { ModelState: ModelStateKey, GModelIndex: GModelIndexKey } = sharedImport("@eclipse-glsp/server");

/**
 * Custom tool palette item provider for the metamodel diagram.
 * Extends BaseToolPaletteItemProvider to inherit automatic handling of CreateOperationHandlers
 * and ToolboxItemProviders, while adding custom import functionality.
 *
 * The palette is organized into sections:
 * - Create: Automatically populated from CreateOperationHandlers (Class and Enum nodes)
 * - Import: Custom import actions for importing classes and enums from other files
 */
@injectable()
export class MetamodelToolPaletteItemProvider extends BaseToolPaletteItemProvider {
    @inject(ModelStateKey)
    protected modelState!: ModelState;

    @inject(GModelIndexKey)
    protected index!: GModelIndex;

    @inject(MetamodelViewState)
    protected viewState!: MetamodelViewState;

    /**
     * Counter to generate unique IDs for palette items.
     */
    private counter = 0;

    /**
     * Constructs the list of palette items for the metamodel diagram.
     * Creates two top-level groups: Create and Import.
     *
     * @param _args Optional arguments (unused)
     * @returns Array of palette items organized by section
     */
    override async getItems(_args?: Args): Promise<PaletteItem[]> {
        this.counter = 0;

        const items = await this.getGroupedItems(_args);
        const importItems = this.createImportPaletteItems();

        const groups: PaletteItem[] = [
            {
                id: "create-group",
                label: "Create",
                actions: [],
                children: items.get("create-group"),
                icon: "add",
                sortString: "A"
            },
            {
                id: "import-group",
                label: "Import",
                actions: [],
                children: importItems,
                icon: "cloud-download",
                sortString: "B"
            }
        ];

        const view = this.viewState.resolveActiveView(this.modelState.sourceModel as PartialMetaModel | undefined);
        const addToViewItems = view != undefined ? this.createAddToViewPaletteItems(view) : [];
        if (addToViewItems.length > 0) {
            groups.push({
                id: "add-to-view-group",
                label: "Add to View",
                actions: [],
                children: addToViewItems,
                icon: "layers",
                sortString: "C"
            });
        }
        return groups;
    }

    /**
     * Creates one palette item for every class and enum the active view does not show yet,
     * local or imported, adding it to the view.
     *
     * @param view The active view
     * @returns The palette items, sorted by name
     */
    protected createAddToViewPaletteItems(view: ResolvedView): PaletteItem[] {
        const candidates: AstNode[] = [];
        const metamodel = this.modelState.sourceModel as PartialMetaModel | undefined;
        for (const element of metamodel?.elements ?? []) {
            if (element != undefined && (element.$type === Class.name || element.$type === Enum.name)) {
                candidates.push(element);
            }
        }
        const document = this.modelState.sourceModel?.$document;
        if (document != undefined) {
            const exports = getExportedEntitiesFromMetamodelFile(
                document,
                this.modelState.languageServices.shared.workspace.LangiumDocuments
            );
            candidates.push(...exports.classes.values(), ...exports.enums.values());
        }

        const items = new Map<string, PaletteItem>();
        for (const element of candidates) {
            const name = MetamodelViewEdits.memberName(element);
            const elementId = this.index.getElementId(element);
            if (name == undefined || elementId == undefined || view.members.has(element) || items.has(name)) {
                continue;
            }
            items.set(name, {
                id: `add-to-view-${elementId}`,
                sortString: name,
                label: name,
                actions: [AddToMetamodelViewOperation.create([elementId], view.name)]
            });
        }
        return [...items.values()].sort((a, b) => a.label.localeCompare(b.label));
    }

    /**
     * Creates the Import palette group with import actions.
     *
     * @returns A palette item representing the Import group
     */
    protected createImportGroup(): PaletteItem {
        const importItems = this.createImportPaletteItems();

        return {
            id: "import-group",
            label: "Import",
            actions: [],
            children: importItems,
            icon: "cloud-download",
            sortString: "B"
        };
    }

    /**
     * Creates palette items for import operations.
     * Includes a single "Import File" item that triggers the file import action.
     *
     * @returns Array of palette items for import operations
     */
    protected createImportPaletteItems(): PaletteItem[] {
        const languageId = this.modelState.languageServices.LanguageMetaData.languageId;

        const importFileOperation: TriggerActionOperation = {
            kind: "triggerAction",
            actionType: "import-file",
            languageId,
            data: {
                uri: this.modelState.sourceUri ?? ""
            },
            isOperation: true
        };

        this.counter++;
        const importFileItem: PaletteItem = {
            id: `palette-item-${this.counter}`,
            sortString: "A",
            label: "Import File",
            icon: "cloud-download",
            actions: [importFileOperation]
        };

        return [importFileItem];
    }
}
