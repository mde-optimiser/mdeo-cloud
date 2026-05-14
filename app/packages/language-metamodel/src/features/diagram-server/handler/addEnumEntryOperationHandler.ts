import {
    BaseOperationHandler,
    OperationHandlerCommand,
    sharedImport,
    GCompartment,
    GHorizontalDivider
} from "@mdeo/language-shared";
import { Enum, EnumEntry, type EnumType } from "../../../grammar/metamodelTypes.js";
import type { AddEnumEntryOperation } from "@mdeo/protocol-metamodel";
import { MetamodelElementType } from "@mdeo/protocol-metamodel";
import type { AstNode } from "langium";
import type { Command, GModelElement } from "@eclipse-glsp/server";
import type { ContextActionRequestContext, ContextItemProvider } from "@mdeo/language-shared";
import type { ContextItem } from "@mdeo/protocol-common";
import { InsertNewLabelAction } from "@mdeo/protocol-common";
import { isImportedElement } from "./metamodelHandlerUtils.js";
import { GEnumEntryLabel } from "../model/enumEntryLabel.js";

const { injectable } = sharedImport("inversify");
const { GrammarUtils } = sharedImport("langium");

/**
 * Prefix used to build unique IDs for new-enum-entry placeholder labels.
 * The full label ID is `${NEW_ENUM_ENTRY_LABEL_PREFIX}${enumNodeId}`.
 */
export const NEW_ENUM_ENTRY_LABEL_PREFIX = "__new-label-entry-";

/**
 * Handler for adding a new entry to an enum.
 */
@injectable()
export class AddEnumEntryOperationHandler extends BaseOperationHandler implements ContextItemProvider {
    override readonly operationType = "addEnumEntry";

    /**
     * Creates a workspace-edit command that inserts a new enum entry.
     *
     * @param operation The add-enum-entry operation
     * @returns A command wrapping the workspace edit, or undefined
     */
    override async createCommand(operation: AddEnumEntryOperation): Promise<Command | undefined> {
        const gmodelElement = this.modelState.index.get(operation.enumId);
        if (gmodelElement == undefined) {
            return undefined;
        }

        const astNode = this.index.getAstNode(gmodelElement);
        if (astNode == undefined || !this.reflection.isInstance(astNode, Enum)) {
            return undefined;
        }

        const enumAstNode = astNode as EnumType;
        const cstNode = enumAstNode.$cstNode;
        if (cstNode == undefined) {
            return undefined;
        }

        const openBrace = GrammarUtils.findNodeForKeyword(cstNode, "{");
        const closeBrace = GrammarUtils.findNodeForKeyword(cstNode, "}");
        if (openBrace == undefined || closeBrace == undefined) {
            return undefined;
        }

        const entryName = operation.entryName!.trim();
        const entryAst = {
            $type: EnumEntry.name,
            name: entryName
        };

        const serialized = await this.serializeNode(entryAst as AstNode);
        const edit = this.insertIntoScope(openBrace, closeBrace, true, serialized);
        return new OperationHandlerCommand(this.modelState, edit, undefined);
    }

    /**
     * Returns context items for adding entries to enum nodes.
     *
     * @param element The selected element
     * @param _context Request context
     * @returns Context actions provided by this handler
     */
    getContextItems(element: GModelElement, _context: ContextActionRequestContext): ContextItem[] {
        if (element.type !== MetamodelElementType.NODE_ENUM) {
            return [];
        }

        if (isImportedElement(element)) {
            return [];
        }

        return [
            {
                id: `add-entry-${element.id}`,
                label: "Add Entry",
                icon: "plus",
                sortString: "a",
                action: this.buildInsertEntryAction(element)
            }
        ];
    }

    /**
     * Builds an {@link InsertNewLabelAction} whose templates represent the new enum entry
     * label (and, if no entries exist yet, the surrounding divider and compartment).
     *
     * If an entries compartment already exists the label is appended directly to it.
     * Otherwise a horizontal divider and a new compartment (containing the label) are
     * appended to the enum node so that the visual result matches what the
     * {@link MetamodelGModelFactory} would produce for a persisted entry.
     *
     * @param element The enum node GModel element.
     * @returns The {@link InsertNewLabelAction} to dispatch.
     */
    private buildInsertEntryAction(element: GModelElement): InsertNewLabelAction {
        const nodeId = element.id;
        const labelId = `${NEW_ENUM_ENTRY_LABEL_PREFIX}${nodeId}`;

        const label = GEnumEntryLabel.builder()
            .id(labelId)
            .text("")
            .isNewLabel(true)
            .newLabelOperationKind("enum-entry-edit")
            .newLabelParentElementId(nodeId)
            .build();

        const compartmentId = `${nodeId}__entries-compartment`;
        const existingCompartment = element.children.find((c) => c.id === compartmentId);

        if (existingCompartment != undefined) {
            return InsertNewLabelAction.create({
                parentElementId: compartmentId,
                insertIndex: existingCompartment.children.length,
                templates: [label],
                labelId
            });
        }

        const divider = GHorizontalDivider.builder()
            .type(MetamodelElementType.DIVIDER)
            .id(`${nodeId}__divider`)
            .build();

        const compartment = GCompartment.builder().type(MetamodelElementType.COMPARTMENT).id(compartmentId).build();
        compartment.children.push(label);

        return InsertNewLabelAction.create({
            parentElementId: nodeId,
            insertIndex: element.children.length,
            templates: [divider, compartment],
            labelId
        });
    }
}
