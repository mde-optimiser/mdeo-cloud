import type { Action, Command, GModelElement, MaybePromise } from "@eclipse-glsp/server";
import type { AstNode } from "langium";
import type { WorkspaceEdit } from "vscode-languageserver-types";
import { BaseOperationHandler, OperationHandlerCommand, sharedImport } from "@mdeo/language-shared";
import type { ContextActionRequestContext, ContextItemProvider, ModelState } from "@mdeo/language-shared";
import type { ContextItem } from "@mdeo/protocol-common";
import {
    AddToMetamodelViewOperation,
    CreateMetamodelViewOperation,
    DeleteMetamodelViewOperation,
    MetamodelElementType,
    RemoveFromMetamodelViewOperation,
    RenameMetamodelViewOperation,
    SelectMetamodelViewAction
} from "@mdeo/protocol-metamodel";
import { Class, Enum } from "../../../grammar/metamodelTypes.js";
import type { PartialMetaModel } from "../../../grammar/metamodelPartialTypes.js";
import { MetamodelViewState, findView, resolveViews } from "../metamodelViews.js";
import { MetamodelViewEdits } from "../metamodelViewEdits.js";

const { injectable, inject } = sharedImport("inversify");
const { ModelSubmissionHandler, ModelState: ModelStateKey } = sharedImport("@eclipse-glsp/server");

/**
 * Handler switching the diagram to another view.
 *
 * Which view is shown is state of the diagram session, not of the metamodel: switching
 * changes no text, it only rebuilds the graph model.
 */
@injectable()
export class SelectMetamodelViewActionHandler {
    readonly actionKinds = [SelectMetamodelViewAction.KIND];

    @inject(MetamodelViewState)
    protected viewState!: MetamodelViewState;

    @inject(ModelSubmissionHandler)
    protected submissionHandler!: InstanceType<typeof ModelSubmissionHandler>;

    @inject(ModelStateKey)
    protected modelState!: ModelState;

    /**
     * Shows the requested view and sends the rebuilt graph model to the client.
     *
     * @param action The select-view action
     * @returns The actions updating the client's model
     */
    execute(action: SelectMetamodelViewAction): MaybePromise<Action[]> {
        const view = findView(this.modelState.sourceModel as PartialMetaModel | undefined, action.view);
        this.viewState.activeView = view?.name;
        return this.submissionHandler.submitModel();
    }
}

/**
 * Common base of the handlers that change the views of a metamodel.
 */
@injectable()
abstract class BaseMetamodelViewOperationHandler extends BaseOperationHandler {
    @inject(MetamodelViewState)
    protected viewState!: MetamodelViewState;

    /**
     * The metamodel of the diagram.
     */
    protected get metamodel(): PartialMetaModel | undefined {
        return this.modelState.sourceModel as PartialMetaModel | undefined;
    }

    /**
     * The builder for the text edits changing views.
     */
    protected get viewEdits(): MetamodelViewEdits {
        return new MetamodelViewEdits(this.modelState);
    }

    /**
     * Resolves element ids to the classes and enums behind them.
     *
     * The ids need not belong to elements of the graph shown right now: a class that is not in
     * the active view is named by the id it has in any diagram.
     *
     * @param elementIds The element ids
     * @returns The classes and enums, each once
     */
    protected resolveMembers(elementIds: string[]): AstNode[] {
        const members = new Set<AstNode>();
        for (const id of elementIds) {
            const node = this.index.getAstNodeById(id);
            if (
                node != undefined &&
                (this.reflection.isInstance(node, Class) || this.reflection.isInstance(node, Enum))
            ) {
                members.add(node);
            }
        }
        return [...members];
    }

    /**
     * Wraps a list of edits into a command.
     *
     * @param edits The edits
     * @returns The command, or `undefined` when there is nothing to change
     */
    protected commandFor(edits: (WorkspaceEdit | undefined)[]): Command | undefined {
        const defined = edits.filter((edit): edit is WorkspaceEdit => edit != undefined);
        if (defined.length === 0) {
            return undefined;
        }
        return new OperationHandlerCommand(this.modelState, this.mergeWorkspaceEdits(defined), undefined);
    }

    /**
     * Checks whether a name can be given to a new or renamed view.
     *
     * @param name The candidate name
     * @param except The view being renamed, whose own name does not count as taken
     * @returns `true` when the name is non-empty and no other view has it
     */
    protected isAvailableViewName(name: string, except?: string): boolean {
        if (name.length === 0 || /[`\r\n]/.test(name)) {
            return false;
        }
        return !resolveViews(this.metamodel).some((view) => view.name === name && view.name !== except);
    }
}

/**
 * Handler adding classes and enums to a view, and owner of the "Views" menu of the context
 * rail.
 *
 * The menu lists every view with a check mark on the ones that show the element; picking a
 * view adds the element to it or removes it again. "New View" creates a view holding the
 * element, the way a `forbid` block is created for a pattern element.
 */
@injectable()
export class AddToMetamodelViewOperationHandler
    extends BaseMetamodelViewOperationHandler
    implements ContextItemProvider
{
    override readonly operationType = AddToMetamodelViewOperation.KIND;

    override async createCommand(operation: AddToMetamodelViewOperation): Promise<Command | undefined> {
        const view = findView(this.metamodel, operation.view);
        if (view == undefined) {
            return undefined;
        }
        return this.commandFor([this.viewEdits.addToView(view.node, this.resolveMembers(operation.elementIds))]);
    }

    /**
     * Returns the "Views" menu for a class or enum.
     *
     * @param element The selected element
     * @param _context Additional request context
     * @returns The menu, or an empty array for other elements
     */
    getContextItems(element: GModelElement, _context: ContextActionRequestContext): ContextItem[] {
        if (element.type !== MetamodelElementType.NODE_CLASS && element.type !== MetamodelElementType.NODE_ENUM) {
            return [];
        }
        const [member] = this.resolveMembers([element.id]);
        if (member == undefined) {
            return [];
        }

        const id = `views-${element.id}`;
        const newView: ContextItem = {
            id: `${id}-new`,
            label: "New View",
            icon: "plus",
            action: CreateMetamodelViewOperation.create([element.id], false)
        };
        const views = resolveViews(this.metamodel);
        if (views.length === 0) {
            return [{ ...newView, id, label: "Add to New View", icon: "layers", sortString: "c" }];
        }

        const children: ContextItem[] = views.map((view) => {
            const isMember = view.members.has(member);
            return {
                id: `${id}-${view.key}`,
                label: view.name,
                icon: isMember ? "square-check" : "square",
                action: isMember
                    ? RemoveFromMetamodelViewOperation.create([element.id], view.name)
                    : AddToMetamodelViewOperation.create([element.id], view.name)
            };
        });
        children.push(newView);
        return [{ id, label: "Views", icon: "layers", sortString: "c", children }];
    }
}

/**
 * Handler removing classes and enums from a view. They stay in the metamodel.
 */
@injectable()
export class RemoveFromMetamodelViewOperationHandler extends BaseMetamodelViewOperationHandler {
    override readonly operationType = RemoveFromMetamodelViewOperation.KIND;

    override async createCommand(operation: RemoveFromMetamodelViewOperation): Promise<Command | undefined> {
        const view = findView(this.metamodel, operation.view);
        if (view == undefined) {
            return undefined;
        }
        const members = new Set(this.resolveMembers(operation.elementIds));
        return this.commandFor(this.viewEdits.removeFromView(view.node, members));
    }
}

/**
 * Handler creating a new view, named `NewView` (numbered when taken).
 */
@injectable()
export class CreateMetamodelViewOperationHandler extends BaseMetamodelViewOperationHandler {
    override readonly operationType = CreateMetamodelViewOperation.KIND;

    override async createCommand(operation: CreateMetamodelViewOperation): Promise<Command | undefined> {
        const name = MetamodelViewEdits.uniqueViewName(this.metamodel);
        const edit = await this.viewEdits.createView(name, this.resolveMembers(operation.elementIds));
        if (operation.select) {
            // The view exists once the edit is applied; until then the diagram keeps showing
            // the whole metamodel.
            this.viewState.activeView = name;
        }
        return this.commandFor([edit]);
    }
}

/**
 * Handler renaming a view. A diagram showing the view keeps showing it.
 */
@injectable()
export class RenameMetamodelViewOperationHandler extends BaseMetamodelViewOperationHandler {
    override readonly operationType = RenameMetamodelViewOperation.KIND;

    override async createCommand(operation: RenameMetamodelViewOperation): Promise<Command | undefined> {
        const view = findView(this.metamodel, operation.view);
        const newName = operation.newName.trim();
        if (view == undefined || newName === view.name || !this.isAvailableViewName(newName, view.name)) {
            return undefined;
        }
        const command = this.commandFor([this.viewEdits.renameView(view.node, newName)]);
        if (command != undefined && this.viewState.activeView === view.name) {
            this.viewState.activeView = newName;
        }
        return command;
    }
}

/**
 * Handler deleting a view. The classes and enums it shows stay in the metamodel; a diagram
 * showing the view goes back to the whole metamodel.
 */
@injectable()
export class DeleteMetamodelViewOperationHandler extends BaseMetamodelViewOperationHandler {
    override readonly operationType = DeleteMetamodelViewOperation.KIND;

    override async createCommand(operation: DeleteMetamodelViewOperation): Promise<Command | undefined> {
        const view = findView(this.metamodel, operation.view);
        if (view == undefined) {
            return undefined;
        }
        const command = this.commandFor([this.viewEdits.deleteView(view.node)]);
        if (command != undefined && this.viewState.activeView === view.name) {
            this.viewState.activeView = undefined;
        }
        return command;
    }
}
