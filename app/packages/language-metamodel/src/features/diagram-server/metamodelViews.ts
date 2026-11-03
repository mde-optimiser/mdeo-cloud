import type { AstNode } from "langium";
import { BaseModelIdProvider, sharedImport, type ModelIdRegistry } from "@mdeo/language-shared";
import type {
    PartialAssociation,
    PartialClass,
    PartialClassExtension,
    PartialMetaModel,
    PartialView
} from "../../grammar/metamodelPartialTypes.js";

const { injectable } = sharedImport("inversify");

/**
 * Separator between the id of an element and the key of the view it is shown in.
 *
 * Every view is a diagram of its own with a layout of its own, so an element shown in a view
 * gets an id of its own: the id it has in the diagram of the whole metamodel, followed by this
 * separator and the key of the view. Because the separator starts with `__`, the model index
 * still finds the AST node behind such an id, and ids derived from it (`<id>__name`, ...) keep
 * working unchanged.
 */
export const VIEW_ID_SEPARATOR = "__view_";

/**
 * A view of the metamodel with the classes and enums it shows.
 */
export interface ResolvedView {
    /**
     * The name of the view.
     */
    readonly name: string;

    /**
     * The key of the view in element ids; unique among the views of the metamodel.
     */
    readonly key: string;

    /**
     * The view AST node.
     */
    readonly node: PartialView;

    /**
     * The classes and enums the view shows.
     */
    readonly members: ReadonlySet<AstNode>;
}

/**
 * Resolves the views of a metamodel.
 *
 * Views without a name are skipped, and of several views with the same name only the first one
 * counts; the validator reports the others. Entries whose reference cannot be resolved are
 * ignored.
 *
 * @param metamodel The metamodel
 * @returns The views in declaration order
 */
export function resolveViews(metamodel: PartialMetaModel | undefined): ResolvedView[] {
    const result: ResolvedView[] = [];
    const names = new Set<string>();
    const keys = new Set<string>();

    for (const view of metamodel?.views ?? []) {
        const name = view?.name;
        if (name == undefined || name.length === 0 || names.has(name)) {
            continue;
        }
        names.add(name);

        const key = uniqueKey(BaseModelIdProvider.escapeIdPart(name), keys);
        keys.add(key);

        const members = new Set<AstNode>();
        for (const entry of view.entries ?? []) {
            const element = entry?.element?.ref;
            if (element != undefined) {
                members.add(element);
            }
        }
        result.push({ name, key, node: view, members });
    }
    return result;
}

/**
 * Finds a view by name.
 *
 * @param metamodel The metamodel
 * @param name The name of the view, or `undefined`
 * @returns The view, or `undefined` when there is none of that name
 */
export function findView(metamodel: PartialMetaModel | undefined, name: string | undefined): ResolvedView | undefined {
    if (name == undefined) {
        return undefined;
    }
    return resolveViews(metamodel).find((view) => view.name === name);
}

/**
 * Makes a key unique among the given ones by appending a counter.
 *
 * Two view names can escape to the same key (`` `a b` `` and `a_b`), and two views sharing
 * a key would share their layout.
 *
 * @param key The escaped view name
 * @param used The keys already taken
 * @returns A key not in `used`
 */
function uniqueKey(key: string, used: ReadonlySet<string>): string {
    if (!used.has(key)) {
        return key;
    }
    let counter = 2;
    while (used.has(`${key}_${counter}`)) {
        counter++;
    }
    return `${key}_${counter}`;
}

/**
 * Returns the id an element has in a view.
 *
 * @param id The id of the element in the diagram of the whole metamodel
 * @param viewKey The key of the view
 * @returns The id of the element in the view
 */
export function scopeIdToView(id: string, viewKey: string): string {
    return `${id}${VIEW_ID_SEPARATOR}${viewKey}`;
}

/**
 * Returns the key of the view an element id belongs to.
 *
 * Derived ids such as `<id>__view_V__multiplicity` belong to the view of the id they derive
 * from: the key ends at the next `__`, which an escaped view name never contains.
 *
 * @param id An element id
 * @returns The key of the view, or `undefined` for an element of the diagram of the whole metamodel
 */
export function viewKeyOfId(id: string): string | undefined {
    const start = id.indexOf(VIEW_ID_SEPARATOR);
    if (start < 0) {
        return undefined;
    }
    const keyStart = start + VIEW_ID_SEPARATOR.length;
    const keyEnd = id.indexOf("__", keyStart);
    return keyEnd < 0 ? id.substring(keyStart) : id.substring(keyStart, keyEnd);
}

/**
 * Id registry that assigns every element the id it has in one view.
 * Wraps the registry of the diagram of the whole metamodel.
 */
export class ViewScopedModelIdRegistry implements ModelIdRegistry {
    /**
     * Creates a registry for one view.
     *
     * @param base The registry of the diagram of the whole metamodel
     * @param viewKey The key of the view
     */
    constructor(
        private readonly base: ModelIdRegistry,
        readonly viewKey: string
    ) {}

    getId(node: AstNode): string {
        return scopeIdToView(this.base.getId(node), this.viewKey);
    }

    getName(node: AstNode): string | undefined {
        return this.base.getName(node);
    }

    getIdOrUnresolved(node: AstNode): string {
        return scopeIdToView(this.base.getIdOrUnresolved(node), this.viewKey);
    }

    hasId(node: AstNode): boolean {
        return this.base.hasId(node);
    }

    getRootNodes(rootNode: AstNode): AstNode[] {
        return this.base.getRootNodes(rootNode);
    }
}

/**
 * The part of a metamodel a diagram shows.
 */
export interface ViewContents<C, E, A> {
    classes: C[];
    enums: E[];
    associations: A[];
}

/**
 * Restricts the classes, enums and associations of a metamodel to those a view shows.
 *
 * A view lists classes and enums; an association belongs to it when both its ends do. Which
 * inheritance relations it shows is answered by {@link isExtensionInView}.
 *
 * @param contents All classes, enums and associations
 * @param view The view, or `undefined` for the diagram of the whole metamodel
 * @returns The classes, enums and associations the view shows
 */
export function restrictToView<C extends AstNode, E extends AstNode, A extends PartialAssociation>(
    contents: ViewContents<C, E, A>,
    view: ResolvedView | undefined
): ViewContents<C, E, A> {
    if (view == undefined) {
        return contents;
    }
    const members = view.members;
    return {
        classes: contents.classes.filter((cls) => members.has(cls)),
        enums: contents.enums.filter((enumDef) => members.has(enumDef)),
        associations: contents.associations.filter((assoc) => {
            const source = assoc.source?.class?.ref;
            const target = assoc.target?.class?.ref;
            return source != undefined && target != undefined && members.has(source) && members.has(target);
        })
    };
}

/**
 * Checks whether a view shows an inheritance relation: it does when it shows both classes.
 *
 * @param owner The subclass
 * @param extension The extension pointing at the superclass
 * @param view The view, or `undefined` for the diagram of the whole metamodel
 * @returns `true` when the relation is shown
 */
export function isExtensionInView(
    owner: PartialClass,
    extension: PartialClassExtension,
    view: ResolvedView | undefined
): boolean {
    if (view == undefined) {
        return true;
    }
    const superClass = extension.class?.ref;
    return superClass != undefined && view.members.has(owner) && view.members.has(superClass);
}

/**
 * Session state of a metamodel diagram: which view it shows.
 *
 * The state lives as long as the diagram session. A view that no longer exists (it was renamed
 * or deleted in the text) is simply not shown; the diagram falls back to the whole metamodel.
 */
@injectable()
export class MetamodelViewState {
    /**
     * The name of the view to show, or `undefined` for the whole metamodel.
     */
    activeView: string | undefined;

    /**
     * Resolves the view the diagram shows.
     *
     * @param metamodel The metamodel
     * @returns The view, or `undefined` when the diagram shows the whole metamodel
     */
    resolveActiveView(metamodel: PartialMetaModel | undefined): ResolvedView | undefined {
        return findView(metamodel, this.activeView);
    }
}
