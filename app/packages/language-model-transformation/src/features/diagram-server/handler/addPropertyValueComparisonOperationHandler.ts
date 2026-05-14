import {
    BaseOperationHandler,
    OperationHandlerCommand,
    GCompartment,
    GHorizontalDivider,
    sharedImport
} from "@mdeo/language-shared";
import type { Command, GModelElement } from "@eclipse-glsp/server";
import { ID } from "@mdeo/language-common";
import { PatternObjectInstance, type PatternObjectInstanceType } from "../../../grammar/modelTransformationTypes.js";
import type { AddPropertyValueComparisonOperation } from "@mdeo/protocol-model-transformation";
import { ModelTransformationElementType } from "@mdeo/protocol-model-transformation";
import type { ContextActionRequestContext, ContextItemProvider } from "@mdeo/language-shared";
import type { ContextItem } from "@mdeo/protocol-common";
import { InsertNewLabelAction } from "@mdeo/protocol-common";
import { resolveClassChain, type ClassType, type PropertyType } from "@mdeo/language-metamodel";
import { GPatternPropertyLabel } from "../model/patternPropertyLabel.js";

const { injectable } = sharedImport("inversify");
const { GrammarUtils } = sharedImport("langium");

/**
 * Prefix used to build unique IDs for new pattern property-value comparison
 * placeholder labels.
 *
 * Label ID formats:
 * - Root (empty) action: {@code ${NEW_PROPERTY_COMPARISON_LABEL_PREFIX}${instanceNodeId}}
 * - Pre-selected property child: {@code ${NEW_PROPERTY_COMPARISON_LABEL_PREFIX}${instanceNodeId}__${propertyName}}
 *
 * The {@code __} separator is used only for uniqueness; validators extract the
 * {@code instanceNodeId} by splitting on it and parse the property name from the
 * edited text itself.
 */
export const NEW_PROPERTY_COMPARISON_LABEL_PREFIX = "__new-label-patprop-";

/**
 * Handler for adding property value/comparison entries on pattern instances.
 */
@injectable()
export class AddPropertyValueComparisonOperationHandler extends BaseOperationHandler implements ContextItemProvider {
    override readonly operationType = "addPropertyValueComparison";

    /**
     * Creates a command for add-property-value-comparison operations.
     *
     * @param _operation The requested operation
     * @returns Undefined while insertion logic is delegated to label/edit workflow
     */
    override async createCommand(operation: AddPropertyValueComparisonOperation): Promise<Command | undefined> {
        const text = operation.labelText?.trim();
        if (!text || text.length === 0) {
            return undefined;
        }

        const gmodelElement = this.modelState.index.get(operation.instanceId);
        if (gmodelElement == undefined) {
            return undefined;
        }

        const astNode = this.index.getAstNode(gmodelElement);
        if (astNode == undefined || !this.reflection.isInstance(astNode, PatternObjectInstance)) {
            return undefined;
        }

        const instanceNode = astNode as PatternObjectInstanceType;
        const cstNode = instanceNode.$cstNode;
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
     * Returns context items for adding property comparisons on pattern instance nodes.
     *
     * Always returns a root "Add Property Value/Comparison" item (even when no typed
     * properties are known).  All known properties are always listed as children
     * regardless of count.
     *
     * @param element The selected element
     * @param _context Additional request context
     * @returns Context actions for this handler
     */
    getContextItems(element: GModelElement, _context: ContextActionRequestContext): ContextItem[] {
        if (element.type !== ModelTransformationElementType.NODE_PATTERN_INSTANCE) {
            return [];
        }

        const astNode = this.index.getAstNode(element);
        if (astNode == undefined || !this.reflection.isInstance(astNode, PatternObjectInstance)) {
            return [];
        }

        const instance = astNode as PatternObjectInstanceType;
        const available = this.getAvailableProperties(instance)
            .map((property) => property.name)
            .filter((name): name is string => name != undefined && name.length > 0)
            .sort((left, right) => left.localeCompare(right));

        return [
            {
                id: `add-pattern-property-${element.id}`,
                label: "Add Property Value/Comparison",
                icon: "plus",
                sortString: "a",
                action: this.buildInsertPropertyComparisonAction(element),
                children: available.map((propertyName) => ({
                    id: `add-pattern-property-${element.id}-${propertyName}`,
                    label: propertyName,
                    action: this.buildInsertPropertyComparisonAction(element, propertyName)
                }))
            }
        ];
    }

    /**
     * Builds an {@link InsertNewLabelAction} that places a new pattern-property label on the
     * given pattern instance node.
     *
     * When {@code propertyName} is provided the label is pre-filled with
     * {@code propName = } so the user only has to type the value or comparison expression.
     * When omitted the label starts empty and the user types the full expression from scratch.
     *
     * If a properties compartment already exists (id {@code ${nodeId}__properties}) the label
     * is appended directly to it.  Otherwise a horizontal divider and a new compartment
     * (containing the label) are appended to the node, mirroring what the
     * {@link ModelTransformationGModelFactory} would produce for a persisted assignment.
     *
     * @param element      The pattern-instance node GModel element.
     * @param propertyName The name of the property to pre-fill, or undefined for empty.
     * @returns The {@link InsertNewLabelAction} to dispatch.
     */
    private buildInsertPropertyComparisonAction(element: GModelElement, propertyName?: string): InsertNewLabelAction {
        const nodeId = element.id;
        const labelId =
            propertyName != undefined
                ? `${NEW_PROPERTY_COMPARISON_LABEL_PREFIX}${nodeId}__${propertyName}`
                : `${NEW_PROPERTY_COMPARISON_LABEL_PREFIX}${nodeId}`;

        let prefillText = "";
        if (propertyName != undefined) {
            const serializer = this.modelState.languageServices.AstSerializer;
            const serializedName = serializer.serializePrimitive({ value: propertyName }, ID);
            prefillText = `${serializedName} = `;
        }

        const label = GPatternPropertyLabel.builder()
            .id(labelId)
            .text(prefillText)
            .isNewLabel(true)
            .newLabelOperationKind("property-value-comparison-edit")
            .newLabelParentElementId(nodeId)
            .build();

        const compartmentId = `${nodeId}__properties`;
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
            .type(ModelTransformationElementType.DIVIDER)
            .id(`${nodeId}__divider`)
            .build();

        const compartment = GCompartment.builder()
            .type(ModelTransformationElementType.COMPARTMENT)
            .id(compartmentId)
            .build();
        compartment.children.push(label);

        return InsertNewLabelAction.create({
            parentElementId: nodeId,
            insertIndex: element.children.length,
            templates: [divider, compartment],
            labelId
        });
    }

    /**
     * Computes class properties not yet assigned on the given pattern instance.
     *
     * @param instance Pattern object instance
     * @returns Property definitions available for insertion
     */
    private getAvailableProperties(instance: PatternObjectInstanceType): PropertyType[] {
        const classType = instance.class?.ref as ClassType | undefined;
        if (classType == undefined) {
            return [];
        }

        const assignedNames = new Set(
            (instance.properties ?? [])
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
