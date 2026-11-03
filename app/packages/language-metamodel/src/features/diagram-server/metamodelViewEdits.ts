import type { AstNode, CstNode, LangiumDocument } from "langium";
import type { WorkspaceEdit } from "vscode-languageserver-types";
import { ID } from "@mdeo/language-common";
import type { ModelState } from "@mdeo/language-shared";
import { sharedImport } from "@mdeo/language-shared";
import { View, ViewEntry, type ViewType } from "../../grammar/metamodelTypes.js";
import type { PartialMetaModel, PartialView } from "../../grammar/metamodelPartialTypes.js";
import { resolveViews } from "./metamodelViews.js";

const { GrammarUtils } = sharedImport("langium");

/**
 * Base name of views created from the diagram.
 */
const NEW_VIEW_NAME = "NewView";

/**
 * Builds the text edits that change the views of a metamodel.
 *
 * Views refer to classes and enums by name only, so these edits are plain insertions and
 * deletions of view entries; the elements themselves are never touched.
 */
export class MetamodelViewEdits {
    /**
     * Creates the edit builder for the source model of a diagram.
     *
     * @param modelState The model state of the diagram
     */
    constructor(private readonly modelState: ModelState) {}

    /**
     * The document the edits apply to.
     */
    private get document(): LangiumDocument {
        const document = this.modelState.sourceModel?.$document;
        if (document == undefined) {
            throw new Error("Source model document is not available.");
        }
        return document;
    }

    /**
     * Returns the name a view entry uses for a class or enum.
     *
     * @param element The class or enum
     * @returns The name, or `undefined` when the element has none
     */
    static memberName(element: AstNode): string | undefined {
        const name = (element as { name?: unknown }).name;
        return typeof name === "string" && name.length > 0 ? name : undefined;
    }

    /**
     * Finds a name for a new view that no view of the metamodel uses yet.
     *
     * @param metamodel The metamodel
     * @returns `NewView`, or `NewView` followed by the first free number
     */
    static uniqueViewName(metamodel: PartialMetaModel | undefined): string {
        const names = new Set((metamodel?.views ?? []).map((view) => view?.name));
        if (!names.has(NEW_VIEW_NAME)) {
            return NEW_VIEW_NAME;
        }
        let suffix = 1;
        while (names.has(`${NEW_VIEW_NAME}${suffix}`)) {
            suffix++;
        }
        return `${NEW_VIEW_NAME}${suffix}`;
    }

    /**
     * Creates the edit adding classes and enums to a view.
     * Elements the view already shows are skipped.
     *
     * @param view The view
     * @param elements The classes and enums to add
     * @returns The edit, or `undefined` when there is nothing to add
     */
    addToView(view: PartialView, elements: AstNode[]): WorkspaceEdit | undefined {
        const members = resolveViews(this.modelState.sourceModel as PartialMetaModel).find(
            (resolved) => resolved.node === view
        )?.members;
        const names = this.uniqueNames(elements.filter((element) => !(members?.has(element) ?? false)));
        const braces = this.braces(view);
        if (names.length === 0 || braces == undefined) {
            return undefined;
        }
        const text = names.map((name) => this.serializeName(name)).join("\n");
        return this.modelState.languageServices.workspace.WorkspaceEdit.insertIntoScope(
            braces.open,
            braces.close,
            false,
            text,
            this.document
        );
    }

    /**
     * Creates the edits removing classes and enums from a view.
     * Every entry naming one of them is removed, duplicates included.
     *
     * @param view The view
     * @param elements The classes and enums to remove
     * @returns The edits, empty when the view shows none of them
     */
    removeFromView(view: PartialView, elements: ReadonlySet<AstNode>): WorkspaceEdit[] {
        const edits: WorkspaceEdit[] = [];
        for (const entry of view.entries ?? []) {
            const element = entry?.element?.ref;
            if (element != undefined && elements.has(element) && entry.$cstNode != undefined) {
                edits.push(
                    this.modelState.languageServices.workspace.WorkspaceEdit.deleteCstNode(
                        entry.$cstNode,
                        this.document
                    )
                );
            }
        }
        return edits;
    }

    /**
     * Creates the edits removing classes and enums from every view of the metamodel, as is
     * needed when they are deleted.
     *
     * @param elements The classes and enums
     * @returns The edits, empty when no view shows any of them
     */
    removeFromAllViews(elements: ReadonlySet<AstNode>): WorkspaceEdit[] {
        const metamodel = this.modelState.sourceModel as PartialMetaModel | undefined;
        return (metamodel?.views ?? []).flatMap((view) =>
            view != undefined ? this.removeFromView(view, elements) : []
        );
    }

    /**
     * Creates the edit appending a new view to the end of the document.
     *
     * @param name The name of the view
     * @param elements The classes and enums it shows
     * @returns The edit
     */
    async createView(name: string, elements: AstNode[]): Promise<WorkspaceEdit> {
        const view: ViewType = {
            $type: View.name,
            name,
            entries: []
        } as unknown as ViewType;
        view.entries = this.uniqueNames(elements).map(
            (memberName) =>
                ({
                    $type: ViewEntry.name,
                    $container: view,
                    element: { $refText: memberName, ref: undefined }
                }) as unknown as ViewType["entries"][number]
        );

        const workspaceEdit = this.modelState.languageServices.workspace.WorkspaceEdit;
        const serialized = await workspaceEdit.serializeNode(view, this.document);
        const root = this.modelState.sourceModel?.$cstNode;
        if (root == undefined) {
            throw new Error("Root CST node is not available.");
        }
        const isEmpty = this.document.textDocument.getText().trim().length === 0;
        return workspaceEdit.createInsertAfterNodeEdit(root, serialized, this.document, !isEmpty);
    }

    /**
     * Creates the edit renaming a view.
     *
     * @param view The view
     * @param newName The new name
     * @returns The edit, or `undefined` when the name cannot be located
     */
    renameView(view: PartialView, newName: string): WorkspaceEdit | undefined {
        const nameNode = GrammarUtils.findNodeForProperty(view.$cstNode, "name");
        if (nameNode == undefined) {
            return undefined;
        }
        return {
            changes: {
                [this.document.uri.toString()]: [{ range: nameNode.range, newText: this.serializeName(newName) }]
            }
        };
    }

    /**
     * Creates the edit deleting a view.
     *
     * @param view The view
     * @returns The edit, or `undefined` when the view has no text
     */
    deleteView(view: PartialView): WorkspaceEdit | undefined {
        if (view.$cstNode == undefined) {
            return undefined;
        }
        return this.modelState.languageServices.workspace.WorkspaceEdit.deleteCstNode(view.$cstNode, this.document);
    }

    /**
     * Serializes a name as an identifier, escaping it when it is not a plain one.
     *
     * @param name The name
     * @returns The identifier text
     */
    private serializeName(name: string): string {
        return this.modelState.languageServices.AstSerializer.serializePrimitive({ value: name }, ID);
    }

    /**
     * Returns the names of the given elements, each once.
     *
     * @param elements The classes and enums
     * @returns Their names, in order of first occurrence
     */
    private uniqueNames(elements: AstNode[]): string[] {
        const names = elements
            .map((element) => MetamodelViewEdits.memberName(element))
            .filter((name): name is string => name != undefined);
        return [...new Set(names)];
    }

    /**
     * Finds the braces of a view.
     *
     * @param view The view
     * @returns The opening and closing brace, or `undefined` when the view is incomplete
     */
    private braces(view: PartialView): { open: CstNode; close: CstNode } | undefined {
        const cstNode = view.$cstNode;
        if (cstNode == undefined) {
            return undefined;
        }
        const open = GrammarUtils.findNodeForKeyword(cstNode, "{");
        const close = GrammarUtils.findNodeForKeyword(cstNode, "}");
        return open != undefined && close != undefined ? { open, close } : undefined;
    }
}
