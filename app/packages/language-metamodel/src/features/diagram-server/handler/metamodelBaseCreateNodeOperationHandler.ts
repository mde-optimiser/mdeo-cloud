import { BaseCreateNodeOperationHandler, sharedImport } from "@mdeo/language-shared";
import type { AstNode } from "langium";
import type { WorkspaceEdit } from "vscode-languageserver-types";
import type { PartialMetaModel } from "../../../grammar/metamodelPartialTypes.js";
import { MetamodelViewState, scopeIdToView, type ResolvedView } from "../metamodelViews.js";
import { MetamodelViewEdits } from "../metamodelViewEdits.js";

const { injectable, inject } = sharedImport("inversify");

/**
 * Base handler for creating classes and enums in the metamodel diagram.
 *
 * A node created while the diagram shows a view becomes part of that view, and it is placed
 * where it was dropped in that view; the diagram of the whole metamodel places it next to its
 * other elements.
 */
@injectable()
export abstract class MetamodelBaseCreateNodeOperationHandler extends BaseCreateNodeOperationHandler {
    /**
     * Which view the diagram shows.
     */
    @inject(MetamodelViewState)
    protected viewState!: MetamodelViewState;

    /**
     * The view the diagram shows, or `undefined` for the whole metamodel.
     */
    protected get activeView(): ResolvedView | undefined {
        return this.viewState.resolveActiveView(this.modelState.sourceModel as PartialMetaModel | undefined);
    }

    protected override mapInsertedId(id: string): string {
        const view = this.activeView;
        return view != undefined ? scopeIdToView(id, view.key) : id;
    }

    /**
     * Extends the edit inserting a new class or enum so that the active view shows it.
     *
     * @param edit The edit inserting the element
     * @param element The new class or enum
     * @returns The extended edit, or `edit` itself when no view is active
     */
    protected addToActiveView(edit: WorkspaceEdit, element: AstNode): WorkspaceEdit {
        const view = this.activeView;
        if (view == undefined) {
            return edit;
        }
        const viewEdit = new MetamodelViewEdits(this.modelState).addToView(view.node, [element]);
        return viewEdit != undefined ? this.mergeWorkspaceEdits([edit, viewEdit]) : edit;
    }
}
