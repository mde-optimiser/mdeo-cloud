import type { Action, Args, Operation } from "@eclipse-glsp/protocol";

/**
 * Name of the graph argument listing the views of the metamodel, as a JSON array of names.
 */
export const METAMODEL_VIEWS_ARG = "metamodelViews";

/**
 * Name of the graph argument holding the view the diagram currently shows.
 * Absent when the diagram shows the whole metamodel.
 */
export const ACTIVE_METAMODEL_VIEW_ARG = "activeMetamodelView";

/**
 * The views of a metamodel diagram, as the server publishes them on the graph root.
 */
export interface MetamodelViewInfo {
    /**
     * The names of all views, in declaration order.
     */
    views: string[];

    /**
     * The view the diagram shows, or `undefined` for the whole metamodel.
     */
    activeView: string | undefined;
}

/**
 * Reads the view information from the arguments of a graph root.
 *
 * @param args The arguments of the graph root
 * @returns The views and the active view; no views when the arguments carry none
 */
export function readMetamodelViewInfo(args: Args | undefined): MetamodelViewInfo {
    const rawViews = args?.[METAMODEL_VIEWS_ARG];
    let views: string[] = [];
    if (typeof rawViews === "string") {
        try {
            const parsed: unknown = JSON.parse(rawViews);
            if (Array.isArray(parsed)) {
                views = parsed.filter((view): view is string => typeof view === "string");
            }
        } catch {
            views = [];
        }
    }
    const rawActive = args?.[ACTIVE_METAMODEL_VIEW_ARG];
    const activeView = typeof rawActive === "string" && views.includes(rawActive) ? rawActive : undefined;
    return { views, activeView };
}

/**
 * Action asking the server to show another view of the metamodel.
 */
export interface SelectMetamodelViewAction extends Action {
    kind: typeof SelectMetamodelViewAction.KIND;

    /**
     * The view to show, or `undefined` for the whole metamodel.
     */
    view?: string;
}

/**
 * Namespace helpers for {@link SelectMetamodelViewAction}.
 */
export namespace SelectMetamodelViewAction {
    export const KIND = "selectMetamodelView";

    /**
     * Creates a select-view action.
     *
     * @param view The view to show, or `undefined` for the whole metamodel
     * @returns The action
     */
    export function create(view: string | undefined): SelectMetamodelViewAction {
        return { kind: KIND, view };
    }

    /**
     * Checks whether an action is a select-view action.
     *
     * @param action The action to check
     * @returns True when the kind matches
     */
    export function is(action: Action): action is SelectMetamodelViewAction {
        return action.kind === KIND;
    }
}

/**
 * Operation adding classes and enums to an existing view.
 */
export interface AddToMetamodelViewOperation extends Operation {
    kind: typeof AddToMetamodelViewOperation.KIND;

    /**
     * The diagram elements of the classes and enums to add.
     */
    elementIds: string[];

    /**
     * The view they are added to.
     */
    view: string;
}

/**
 * Namespace helpers for {@link AddToMetamodelViewOperation}.
 */
export namespace AddToMetamodelViewOperation {
    export const KIND = "addToMetamodelView";

    /**
     * Creates an add-to-view operation.
     *
     * @param elementIds The diagram elements of the classes and enums to add
     * @param view The view they are added to
     * @returns The operation
     */
    export function create(elementIds: string[], view: string): AddToMetamodelViewOperation {
        return { kind: KIND, isOperation: true, elementIds, view };
    }

    /**
     * Checks whether an operation is an add-to-view operation.
     *
     * @param operation The operation to check
     * @returns True when the kind matches
     */
    export function is(operation: Operation): operation is AddToMetamodelViewOperation {
        return operation.kind === KIND;
    }
}

/**
 * Operation removing classes and enums from a view, leaving them in the metamodel.
 */
export interface RemoveFromMetamodelViewOperation extends Operation {
    kind: typeof RemoveFromMetamodelViewOperation.KIND;

    /**
     * The diagram elements of the classes and enums to remove.
     */
    elementIds: string[];

    /**
     * The view they are removed from.
     */
    view: string;
}

/**
 * Namespace helpers for {@link RemoveFromMetamodelViewOperation}.
 */
export namespace RemoveFromMetamodelViewOperation {
    export const KIND = "removeFromMetamodelView";

    /**
     * Creates a remove-from-view operation.
     *
     * @param elementIds The diagram elements of the classes and enums to remove
     * @param view The view they are removed from
     * @returns The operation
     */
    export function create(elementIds: string[], view: string): RemoveFromMetamodelViewOperation {
        return { kind: KIND, isOperation: true, elementIds, view };
    }

    /**
     * Checks whether an operation is a remove-from-view operation.
     *
     * @param operation The operation to check
     * @returns True when the kind matches
     */
    export function is(operation: Operation): operation is RemoveFromMetamodelViewOperation {
        return operation.kind === KIND;
    }
}

/**
 * Operation creating a new view, optionally holding some classes and enums from the start.
 */
export interface CreateMetamodelViewOperation extends Operation {
    kind: typeof CreateMetamodelViewOperation.KIND;

    /**
     * The diagram elements of the classes and enums the new view holds.
     */
    elementIds: string[];

    /**
     * Whether the diagram switches to the new view once it exists.
     */
    select: boolean;
}

/**
 * Namespace helpers for {@link CreateMetamodelViewOperation}.
 */
export namespace CreateMetamodelViewOperation {
    export const KIND = "createMetamodelView";

    /**
     * Creates a create-view operation.
     *
     * @param elementIds The diagram elements of the classes and enums the new view holds
     * @param select Whether the diagram switches to the new view
     * @returns The operation
     */
    export function create(elementIds: string[], select: boolean): CreateMetamodelViewOperation {
        return { kind: KIND, isOperation: true, elementIds, select };
    }

    /**
     * Checks whether an operation is a create-view operation.
     *
     * @param operation The operation to check
     * @returns True when the kind matches
     */
    export function is(operation: Operation): operation is CreateMetamodelViewOperation {
        return operation.kind === KIND;
    }
}

/**
 * Operation renaming a view.
 */
export interface RenameMetamodelViewOperation extends Operation {
    kind: typeof RenameMetamodelViewOperation.KIND;

    /**
     * The current name of the view.
     */
    view: string;

    /**
     * The new name of the view.
     */
    newName: string;
}

/**
 * Namespace helpers for {@link RenameMetamodelViewOperation}.
 */
export namespace RenameMetamodelViewOperation {
    export const KIND = "renameMetamodelView";

    /**
     * Creates a rename-view operation.
     *
     * @param view The current name of the view
     * @param newName The new name of the view
     * @returns The operation
     */
    export function create(view: string, newName: string): RenameMetamodelViewOperation {
        return { kind: KIND, isOperation: true, view, newName };
    }

    /**
     * Checks whether an operation is a rename-view operation.
     *
     * @param operation The operation to check
     * @returns True when the kind matches
     */
    export function is(operation: Operation): operation is RenameMetamodelViewOperation {
        return operation.kind === KIND;
    }
}

/**
 * Operation deleting a view. The classes and enums it shows stay in the metamodel.
 */
export interface DeleteMetamodelViewOperation extends Operation {
    kind: typeof DeleteMetamodelViewOperation.KIND;

    /**
     * The name of the view to delete.
     */
    view: string;
}

/**
 * Namespace helpers for {@link DeleteMetamodelViewOperation}.
 */
export namespace DeleteMetamodelViewOperation {
    export const KIND = "deleteMetamodelView";

    /**
     * Creates a delete-view operation.
     *
     * @param view The name of the view to delete
     * @returns The operation
     */
    export function create(view: string): DeleteMetamodelViewOperation {
        return { kind: KIND, isOperation: true, view };
    }

    /**
     * Checks whether an operation is a delete-view operation.
     *
     * @param operation The operation to check
     * @returns True when the kind matches
     */
    export function is(operation: Operation): operation is DeleteMetamodelViewOperation {
        return operation.kind === KIND;
    }
}

/**
 * Client-side action asking the user whether elements deleted inside a view are removed from
 * the view only or deleted from the metamodel.
 *
 * The editor answers it with a dialog that dispatches either a
 * {@link RemoveFromMetamodelViewOperation} or a plain delete operation.
 */
export interface ConfirmViewDeletionAction extends Action {
    kind: typeof ConfirmViewDeletionAction.KIND;

    /**
     * All elements the user asked to delete.
     */
    elementIds: string[];

    /**
     * The elements among them that a view can drop: classes and enums.
     */
    viewMemberIds: string[];

    /**
     * The view the diagram shows.
     */
    view: string;
}

/**
 * Namespace helpers for {@link ConfirmViewDeletionAction}.
 */
export namespace ConfirmViewDeletionAction {
    export const KIND = "confirmMetamodelViewDeletion";

    /**
     * Creates a confirm-view-deletion action.
     *
     * @param elementIds All elements the user asked to delete
     * @param viewMemberIds The classes and enums among them
     * @param view The view the diagram shows
     * @returns The action
     */
    export function create(elementIds: string[], viewMemberIds: string[], view: string): ConfirmViewDeletionAction {
        return { kind: KIND, elementIds, viewMemberIds, view };
    }

    /**
     * Checks whether an action is a confirm-view-deletion action.
     *
     * @param action The action to check
     * @returns True when the kind matches
     */
    export function is(action: Action): action is ConfirmViewDeletionAction {
        return action.kind === KIND;
    }
}
