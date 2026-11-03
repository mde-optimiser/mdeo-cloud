import { BaseOperationHandler, GCompartment, OperationHandlerCommand, sharedImport } from "@mdeo/language-shared";
import type { ContextActionRequestContext, ContextItemProvider } from "@mdeo/language-shared";
import type { Command, GModelElement } from "@eclipse-glsp/server";
import {
    AddHeaderDeclarationOperation,
    EndNodeKind,
    ModelTransformationElementType
} from "@mdeo/protocol-model-transformation";
import { InsertNewLabelAction, type ContextItem } from "@mdeo/protocol-common";
import type { ModelTransformationType } from "../../../grammar/modelTransformationTypes.js";
import { GDeclarationLabel } from "../model/declarationLabel.js";
import {
    declarationsCompartmentId,
    headerInsertEdit,
    headerLineKind,
    headerLineTemplates,
    NEW_DECLARATION_LABEL_PREFIX
} from "../modelTransformationHeader.js";

const { injectable } = sharedImport("inversify");

/**
 * Handler adding a line to the header of a transformation — an `in` / `out` declaration (or a
 * path import) — when a new row of the start node or of an end node is committed. The line goes
 * where the serializer puts it: imports after `using` and the other imports, declarations after
 * the imports, inputs before outputs.
 *
 * Also provides the context items adding a row: inputs on the start node, an output on every
 * successful end node.
 */
@injectable()
export class AddHeaderDeclarationOperationHandler extends BaseOperationHandler implements ContextItemProvider {
    override readonly operationType = AddHeaderDeclarationOperation.KIND;

    /**
     * Creates a workspace-edit command inserting the committed line into the header.
     *
     * @param operation The add-header-declaration operation
     * @returns A command wrapping the workspace edit, or undefined when there is nothing to add
     */
    override async createCommand(operation: AddHeaderDeclarationOperation): Promise<Command | undefined> {
        const text = operation.labelText?.trim();
        const root = this.modelState.sourceModel as ModelTransformationType | undefined;
        const document = root?.$document;
        if (!text || root == undefined || document == undefined) {
            return undefined;
        }
        const edit = headerInsertEdit(root, document, text);
        if (edit == undefined) {
            return undefined;
        }
        return new OperationHandlerCommand(
            this.modelState,
            { changes: { [document.uri.toString()]: [edit] } },
            undefined
        );
    }

    /**
     * Returns the items adding an input (a value, a model or a list of models) to the start node,
     * or an output to a successful end node.
     *
     * @param element The selected element
     * @param _context Additional request context
     * @returns The context items
     */
    getContextItems(element: GModelElement, _context: ContextActionRequestContext): ContextItem[] {
        const root = this.modelState.sourceModel as ModelTransformationType | undefined;
        if (root == undefined) {
            return [];
        }
        const templates = headerLineTemplates(root);
        let lines: [string, string, string, string][];
        if (element.type === ModelTransformationElementType.NODE_START) {
            lines = [
                ["input", "Add Input", "log-in", templates.input],
                ["model", "Add Model Input", "box", templates.model],
                ["model-list", "Add Model List Input", "boxes", templates.modelList]
            ];
        } else if (
            element.type === ModelTransformationElementType.NODE_END &&
            (element as { kind?: EndNodeKind }).kind === EndNodeKind.STOP
        ) {
            lines = [["output", "Add Output", "log-out", templates.output]];
        } else {
            return [];
        }
        const items: ContextItem[] = [];
        for (const [id, label, icon, text] of lines) {
            const action = buildInsertDeclarationAction(element, text);
            if (action != undefined) {
                items.push({ id: `add-${id}-${element.id}`, label, icon, sortString: `c${items.length}`, action });
            }
        }
        return items;
    }
}

/**
 * Builds the action that adds a new, prefilled declaration row to a node and starts editing it:
 * an input to the start node, an output to a successful end node. Committing the row adds the
 * line through {@link AddHeaderDeclarationOperation}.
 *
 * The row goes below the node's other rows; a node without rows gets its rows compartment with
 * it.
 *
 * @param node The start node (for an input) or a successful end node (for an output)
 * @param text The prefilled line, e.g. `in input1: int`
 * @returns The action, or undefined when the text is no declaration
 */
export function buildInsertDeclarationAction(node: GModelElement, text: string): InsertNewLabelAction | undefined {
    const kind = headerLineKind(text);
    if (kind !== "in" && kind !== "out") {
        return undefined;
    }
    const labelId = `${NEW_DECLARATION_LABEL_PREFIX}${kind}`;
    const label = GDeclarationLabel.builder()
        .id(labelId)
        .text(text)
        .isNewLabel(true)
        .newLabelOperationKind(AddHeaderDeclarationOperation.NEW_LABEL_KIND)
        .newLabelParentElementId(node.id)
        .build();

    const compartmentId = declarationsCompartmentId(node.id);
    const existing = node.children.find((child) => child.id === compartmentId);
    if (existing != undefined) {
        return InsertNewLabelAction.create({
            parentElementId: compartmentId,
            insertIndex: existing.children.length,
            templates: [label],
            labelId
        });
    }

    const compartment = GCompartment.builder()
        .type(ModelTransformationElementType.COMPARTMENT)
        .id(compartmentId)
        .build();
    compartment.children.push(label);
    return InsertNewLabelAction.create({
        parentElementId: node.id,
        insertIndex: node.children.length,
        templates: [compartment],
        labelId
    });
}
