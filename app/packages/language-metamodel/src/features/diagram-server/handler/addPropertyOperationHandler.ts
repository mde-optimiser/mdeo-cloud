import {
    BaseOperationHandler,
    OperationHandlerCommand,
    sharedImport,
    GCompartment,
    GHorizontalDivider
} from "@mdeo/language-shared";
import { Class, type ClassType } from "../../../grammar/metamodelTypes.js";
import type { AddPropertyOperation } from "@mdeo/protocol-metamodel";
import { MetamodelElementType } from "@mdeo/protocol-metamodel";
import type { Command, GModelElement } from "@eclipse-glsp/server";
import type { ContextActionRequestContext, ContextItemProvider } from "@mdeo/language-shared";
import type { ContextItem } from "@mdeo/protocol-common";
import { InsertNewLabelAction } from "@mdeo/protocol-common";
import { isImportedElement } from "./metamodelHandlerUtils.js";
import { GPropertyLabel } from "../model/propertyLabel.js";

const { injectable } = sharedImport("inversify");
const { GrammarUtils } = sharedImport("langium");

/**
 * Prefix used to build unique IDs for new-property placeholder labels.
 * The full label ID is `${NEW_PROPERTY_LABEL_PREFIX}${classNodeId}`.
 */
export const NEW_PROPERTY_LABEL_PREFIX = "__new-label-prop-";

/**
 * Handler for adding a new property to a class.
 */
@injectable()
export class AddPropertyOperationHandler extends BaseOperationHandler implements ContextItemProvider {
    override readonly operationType = "addProperty";

    /**
     * Creates a workspace-edit command that inserts a new property into a class body.
     *
     * @param operation The add-property operation
     * @returns A command wrapping the workspace edit, or undefined
     */
    override async createCommand(operation: AddPropertyOperation): Promise<Command | undefined> {
        const gmodelElement = this.modelState.index.get(operation.classId);
        if (gmodelElement == undefined) {
            return undefined;
        }

        const astNode = this.index.getAstNode(gmodelElement);
        if (astNode == undefined || !this.reflection.isInstance(astNode, Class)) {
            return undefined;
        }

        const classAstNode = astNode as ClassType;
        const cstNode = classAstNode.$cstNode;
        if (cstNode == undefined) {
            return undefined;
        }

        const openBrace = GrammarUtils.findNodeForKeyword(cstNode, "{");
        const closeBrace = GrammarUtils.findNodeForKeyword(cstNode, "}");
        if (openBrace == undefined || closeBrace == undefined) {
            return undefined;
        }

        const propText = operation.propertyName!.trim();
        if (propText.length === 0) {
            return undefined;
        }

        const edit = this.insertIntoScope(openBrace, closeBrace, true, propText);
        return new OperationHandlerCommand(this.modelState, edit, undefined);
    }

    /**
     * Returns context items for adding a property to class nodes.
     *
     * @param element The selected element
     * @param _context Request context
     * @returns Context actions provided by this handler
     */
    getContextItems(element: GModelElement, _context: ContextActionRequestContext): ContextItem[] {
        if (element.type !== MetamodelElementType.NODE_CLASS) {
            return [];
        }

        if (isImportedElement(element)) {
            return [];
        }

        return [
            {
                id: `add-property-${element.id}`,
                label: "Add Property",
                icon: "plus",
                sortString: "a",
                action: this.buildInsertPropertyAction(element)
            }
        ];
    }

    /**
     * Builds an {@link InsertNewLabelAction} whose templates represent the new property
     * label (and, if no properties exist yet, the surrounding divider and compartment).
     *
     * If a properties compartment already exists the label is appended directly to it.
     * Otherwise a horizontal divider and a new compartment (containing the label) are
     * appended to the class node so that the visual result matches what the
     * {@link MetamodelGModelFactory} would produce for a persisted property.
     *
     * @param element The class node GModel element.
     * @returns The {@link InsertNewLabelAction} to dispatch.
     */
    private buildInsertPropertyAction(element: GModelElement): InsertNewLabelAction {
        const nodeId = element.id;
        const labelId = `${NEW_PROPERTY_LABEL_PREFIX}${nodeId}`;

        const label = GPropertyLabel.builder()
            .id(labelId)
            .text("")
            .isNewLabel(true)
            .newLabelOperationKind("property-name-edit")
            .newLabelParentElementId(nodeId)
            .build();

        const compartmentId = `${nodeId}__properties-compartment`;
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
