import {
    BaseOperationHandler,
    OperationHandlerCommand,
    sharedImport,
    GCompartment,
    GHorizontalDivider
} from "@mdeo/language-shared";
import { ObjectInstance, type ObjectInstanceType } from "../../../grammar/modelTypes.js";
import type { AddPropertyValueOperation } from "@mdeo/protocol-model";
import { ModelElementType } from "@mdeo/protocol-model";
import type { Command, GModelElement } from "@eclipse-glsp/server";
import type { ContextActionRequestContext, ContextItemProvider } from "@mdeo/language-shared";
import type { ContextItem } from "@mdeo/protocol-common";
import { InsertNewLabelAction } from "@mdeo/protocol-common";
import { resolveClassChain, type ClassType, type PropertyType } from "@mdeo/language-metamodel";
import { GPropertyLabel } from "../model/propertyLabel.js";
import { ID } from "@mdeo/language-common";

const { injectable } = sharedImport("inversify");
const { GrammarUtils } = sharedImport("langium");

/**
 * Prefix used to build unique IDs for new property-value placeholder labels.
 *
 * Label ID formats:
 * - Root (empty) action: {@code ${NEW_PROPERTY_VALUE_LABEL_PREFIX}${objectNodeId}}
 * - Pre-selected property child: {@code ${NEW_PROPERTY_VALUE_LABEL_PREFIX}${objectNodeId}__${propertyName}}
 *
 * The {@code __} separator is used only for uniqueness; validators extract the
 * {@code objectNodeId} by splitting on it and parse the property name from the
 * edited text itself.
 */
export const NEW_PROPERTY_VALUE_LABEL_PREFIX = "__new-label-propval-";

/**
 * Handler for adding a new property assignment to model object instances.
 */
@injectable()
export class AddPropertyValueOperationHandler extends BaseOperationHandler implements ContextItemProvider {
    override readonly operationType = "addPropertyValue";

    /**
     * Creates a workspace-edit command that inserts a new property assignment.
     *
     * @param operation The add-property-value operation
     * @returns A command wrapping the workspace edit, or undefined when insertion is not possible
     */
    override async createCommand(operation: AddPropertyValueOperation): Promise<Command | undefined> {
        const text = operation.labelText?.trim();
        if (!text || text.length === 0) {
            return undefined;
        }

        const gmodelElement = this.modelState.index.get(operation.objectId);
        if (gmodelElement == undefined) {
            return undefined;
        }

        const astNode = this.index.getAstNode(gmodelElement);
        if (astNode == undefined || !this.reflection.isInstance(astNode, ObjectInstance)) {
            return undefined;
        }

        const objectNode = astNode as ObjectInstanceType;
        const cstNode = objectNode.$cstNode;
        if (cstNode == undefined) {
            return undefined;
        }

        const openBrace = GrammarUtils.findNodeForKeyword(cstNode, "{");
        const closeBrace = GrammarUtils.findNodeForKeyword(cstNode, "}");
        if (openBrace == undefined || closeBrace == undefined) {
            return undefined;
        }

        const edit = this.insertIntoScope(openBrace, closeBrace, true, text);
        return new OperationHandlerCommand(this.modelState, edit, undefined);
    }

    /**
     * Returns context items for adding property assignments on object nodes.
     *
     * Always returns a root "Add Property Value" item (even when no typed properties are
     * known), so the user can type a free-form assignment.  Any known properties are
     * always listed as children regardless of count.
     *
     * @param element The selected diagram element
     * @param _context Additional request context
     * @returns Context actions for this handler
     */
    getContextItems(element: GModelElement, _context: ContextActionRequestContext): ContextItem[] {
        if (element.type !== ModelElementType.NODE_OBJECT) {
            return [];
        }

        const astNode = this.index.getAstNode(element);
        if (astNode == undefined || !this.reflection.isInstance(astNode, ObjectInstance)) {
            return [];
        }

        const objectNode = astNode as ObjectInstanceType;
        const available = this.getAvailableProperties(objectNode)
            .map((property) => property.name)
            .filter((name): name is string => name != undefined && name.length > 0)
            .sort((left, right) => left.localeCompare(right));

        return [
            {
                id: `add-property-value-${element.id}`,
                label: "Add Property Value",
                icon: "plus",
                sortString: "a",
                action: this.buildInsertPropertyValueAction(element),
                children: available.map((propertyName) => ({
                    id: `add-property-value-${element.id}-${propertyName}`,
                    label: propertyName,
                    action: this.buildInsertPropertyValueAction(element, propertyName)
                }))
            }
        ];
    }

    /**
     * Builds an {@link InsertNewLabelAction} that places a new property-value label on the
     * given object node.
     *
     * When {@code propertyName} is provided the label is pre-filled with
     * {@code propName = } so the user only has to type the value.
     * When omitted the label starts empty and the user types the full
     * {@code propName = value} assignment from scratch.
     *
     * If a properties compartment already exists the label is appended directly to it.
     * Otherwise a horizontal divider and a new compartment (containing the label) are
     * appended to the object node so that the visual result matches what the
     * {@link ModelGModelFactory} would produce for a persisted property assignment.
     *
     * @param element      The object node GModel element.
     * @param propertyName The name of the property to pre-fill, or undefined for empty.
     * @returns The {@link InsertNewLabelAction} to dispatch.
     */
    private buildInsertPropertyValueAction(element: GModelElement, propertyName?: string): InsertNewLabelAction {
        const nodeId = element.id;
        const labelId =
            propertyName != undefined
                ? `${NEW_PROPERTY_VALUE_LABEL_PREFIX}${nodeId}__${propertyName}`
                : `${NEW_PROPERTY_VALUE_LABEL_PREFIX}${nodeId}`;

        let prefillText = "";
        if (propertyName != undefined) {
            const serializer = this.modelState.languageServices.AstSerializer;
            const serializedName = serializer.serializePrimitive({ value: propertyName }, ID);
            prefillText = `${serializedName} = `;
        }

        const label = GPropertyLabel.builder()
            .id(labelId)
            .text(prefillText)
            .isNewLabel(true)
            .newLabelOperationKind("property-value-edit")
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

        const divider = GHorizontalDivider.builder().type(ModelElementType.DIVIDER).id(`${nodeId}__divider`).build();

        const compartment = GCompartment.builder().type(ModelElementType.COMPARTMENT).id(compartmentId).build();
        compartment.children.push(label);

        return InsertNewLabelAction.create({
            parentElementId: nodeId,
            insertIndex: element.children.length,
            templates: [divider, compartment],
            labelId
        });
    }

    /**
     * Computes class properties not yet assigned on the given object instance.
     *
     * @param objectNode The object instance
     * @returns Property definitions available for insertion
     */
    private getAvailableProperties(objectNode: ObjectInstanceType): PropertyType[] {
        const classType = objectNode.class?.ref as ClassType | undefined;
        if (classType == undefined) {
            return [];
        }

        const assignedNames = new Set(
            (objectNode.properties ?? [])
                .map((assignment) => assignment.name?.$refText ?? assignment.name?.ref?.name)
                .filter((name): name is string => name != undefined)
        );

        const chain = resolveClassChain(classType, this.reflection);
        const available: PropertyType[] = [];

        for (const cls of chain) {
            for (const property of cls.properties ?? []) {
                if (property.name != undefined && !assignedNames.has(property.name)) {
                    available.push(property);
                }
            }
        }

        return available;
    }
}
