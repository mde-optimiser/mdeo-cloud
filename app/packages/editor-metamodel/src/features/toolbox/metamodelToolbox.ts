import type { VNode } from "snabbdom";
import type { Action } from "@eclipse-glsp/sprotty";
import type { Args } from "@eclipse-glsp/protocol";
import { sharedImport, Toolbox, ToolType, ChoiceDialog } from "@mdeo/editor-shared";
import { generateEdgeTypeExtensionView } from "./edgeTypeExtensionView.js";
import { generateViewSwitcherView } from "./viewSwitcherView.js";
import {
    CreateMetamodelViewOperation,
    DeleteMetamodelViewOperation,
    EdgeCreationType,
    RenameMetamodelViewOperation,
    SelectMetamodelViewAction,
    readMetamodelViewInfo
} from "@mdeo/protocol-metamodel";

const { injectable, inject } = sharedImport("inversify");
const { SetModelAction, UpdateModelAction } = sharedImport("@eclipse-glsp/sprotty");

/**
 * Local storage key remembering whether the view switcher is collapsed.
 */
const VIEWS_COLLAPSED_KEY = "mdeo.metamodel.viewsCollapsed";

/**
 * Reads whether the view switcher was collapsed last time.
 *
 * @returns The remembered state; expanded when nothing is remembered
 */
function readViewsCollapsed(): boolean {
    try {
        return localStorage.getItem(VIEWS_COLLAPSED_KEY) === "true";
    } catch {
        return false;
    }
}

/**
 * Extended toolbox for the metamodel editor.
 * Adds edge type selection to control which kind of association or inheritance
 * edge is created when using the connection tool, and a view switcher.
 */
@injectable()
export class MetamodelToolbox extends Toolbox {
    @inject(ChoiceDialog)
    protected choiceDialog!: ChoiceDialog;

    public selectedEdgeType: EdgeCreationType = EdgeCreationType.UNIDIRECTIONAL;

    /**
     * The names of the views of the metamodel, as published by the server.
     */
    public views: string[] = [];

    /**
     * The view the diagram shows, or `undefined` for the whole metamodel.
     */
    public activeView: string | undefined;

    /**
     * Whether the view switcher is collapsed to its header. Remembered in the browser.
     */
    public viewsCollapsed: boolean = readViewsCollapsed();

    /**
     * The view whose name is being edited, if any.
     */
    public renamingView: string | undefined;

    /**
     * The name typed so far while renaming a view.
     */
    public renameDraft: string = "";

    /**
     * Whether views can be created, renamed and deleted, which changes the metamodel's text.
     * Switching between views is always possible.
     */
    get canEditViews(): boolean {
        return !this.editorContext.isReadonly && !this.isLayoutable;
    }

    /**
     * Updates the selected edge creation type and re-renders the toolbox.
     *
     * @param edgeType The edge creation type to select
     */
    selectEdgeType(edgeType: EdgeCreationType): void {
        this.selectedEdgeType = edgeType;
        if (this.toolType !== ToolType.CREATE_EDGE) {
            this.updateTool(ToolType.CREATE_EDGE);
        } else {
            this.update();
        }
    }

    /**
     * Collapses the view switcher to its header, or expands it again.
     */
    toggleViewsCollapsed(): void {
        this.viewsCollapsed = !this.viewsCollapsed;
        this.renamingView = undefined;
        try {
            localStorage.setItem(VIEWS_COLLAPSED_KEY, String(this.viewsCollapsed));
        } catch {
            // Storage may be unavailable; the state then lasts as long as the editor.
        }
        this.update();
    }

    /**
     * Shows another view.
     *
     * @param view The view, or `undefined` for the whole metamodel
     */
    selectView(view: string | undefined): void {
        if (view === this.activeView) {
            return;
        }
        this.actionDispatcher.dispatch(SelectMetamodelViewAction.create(view));
    }

    /**
     * Creates an empty view and shows it.
     */
    createView(): void {
        this.actionDispatcher.dispatch(CreateMetamodelViewOperation.create([], true));
    }

    /**
     * Starts editing the name of a view.
     *
     * @param view The view
     */
    startRename(view: string): void {
        this.renamingView = view;
        this.renameDraft = view;
        this.update();
    }

    /**
     * Renames the view being edited to the name typed, unless the name is empty or unchanged.
     */
    commitRename(): void {
        const view = this.renamingView;
        if (view == undefined) {
            return;
        }
        const newName = this.renameDraft.trim();
        this.renamingView = undefined;
        this.update();
        if (newName.length > 0 && newName !== view) {
            this.actionDispatcher.dispatch(RenameMetamodelViewOperation.create(view, newName));
        }
    }

    /**
     * Stops editing the name of a view without renaming it.
     */
    cancelRename(): void {
        this.renamingView = undefined;
        this.update();
    }

    /**
     * Asks whether a view is to be deleted, and deletes it if so.
     *
     * @param view The view
     */
    deleteView(view: string): void {
        this.choiceDialog.show({
            title: `Delete view "${view}"?`,
            message: "The classes and enums it shows stay in the metamodel.",
            choices: [
                { label: "Delete View", variant: "destructive", actions: [DeleteMetamodelViewOperation.create(view)] }
            ]
        });
    }

    override generateDetailsExtension(): VNode[] {
        return [generateEdgeTypeExtensionView(this)];
    }

    override generateToolbarExtension(): VNode | undefined {
        return generateViewSwitcherView(this);
    }

    /**
     * Picks up the views published on the graph root of every new model.
     *
     * @param action The action dispatched by the GLSP framework
     */
    override handle(action: Action): void {
        if ((SetModelAction.is(action) || UpdateModelAction.is(action)) && action.newRoot != undefined) {
            const info = readMetamodelViewInfo((action.newRoot as { args?: Args }).args);
            this.views = info.views;
            this.activeView = info.activeView;
            if (this.renamingView != undefined && !this.views.includes(this.renamingView)) {
                this.renamingView = undefined;
            }
            this.update();
        }
        super.handle(action);
    }
}
