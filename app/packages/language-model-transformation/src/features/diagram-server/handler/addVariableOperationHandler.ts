import {
    BaseOperationHandler,
    OperationHandlerCommand,
    GCompartment,
    GHorizontalDivider,
    sharedImport
} from "@mdeo/language-shared";
import type { Command, GModelElement } from "@eclipse-glsp/server";
import type { AddVariableOperation } from "@mdeo/protocol-model-transformation";
import { ModelTransformationElementType } from "@mdeo/protocol-model-transformation";
import type { ContextActionRequestContext, ContextItemProvider } from "@mdeo/language-shared";
import type { ContextItem } from "@mdeo/protocol-common";
import { InsertNewLabelAction } from "@mdeo/protocol-common";
import { GMatchNodeCompartments } from "../model/matchNodeCompartments.js";
import { GVariableLabel } from "../model/variableLabel.js";
import { getPatternFromMatchNode } from "../modelTransformationPatternUtils.js";

const { injectable } = sharedImport("inversify");
const { GrammarUtils } = sharedImport("langium");

/**
 * Prefix used to build unique IDs for new-variable placeholder labels.
 * The full label ID is `${NEW_VARIABLE_LABEL_PREFIX}${matchNodeId}`.
 */
export const NEW_VARIABLE_LABEL_PREFIX = "__new-label-variable-";

/**
 * Prefix used to build unique IDs for new-reassignment placeholder labels.
 * The full label ID is `${NEW_VARIABLE_REASSIGNMENT_LABEL_PREFIX}${matchNodeId}`.
 *
 * A reassignment placeholder reuses the variable-label element type and the
 * {@link AddVariableOperation} for insertion (its text is inserted into the pattern
 * verbatim), so only the ID prefix distinguishes it — the label-edit validator uses the
 * prefix to validate the in-progress text as `name = expression` rather than a declaration.
 */
export const NEW_VARIABLE_REASSIGNMENT_LABEL_PREFIX = "__new-label-variable-reassignment-";

/**
 * Handler for adding variable declarations on match nodes.
 */
@injectable()
export class AddVariableOperationHandler extends BaseOperationHandler implements ContextItemProvider {
    override readonly operationType = "addVariable";

    /**
     * Creates a workspace-edit command that inserts a new variable into the match pattern.
     *
     * @param operation The add-variable operation
     * @returns A command wrapping the workspace edit, or undefined
     */
    override async createCommand(operation: AddVariableOperation): Promise<Command | undefined> {
        const text = operation.labelText?.trim();
        if (!text || text.length === 0) {
            return undefined;
        }

        const gmodelElement = this.modelState.index.get(operation.matchNodeId);
        if (gmodelElement == undefined) {
            return undefined;
        }

        const astNode = this.index.getAstNode(gmodelElement);
        if (astNode == undefined) {
            return undefined;
        }

        const patternNode = getPatternFromMatchNode(astNode, this.reflection);
        if (patternNode == undefined) {
            return undefined;
        }
        const cstNode = patternNode.$cstNode;
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
     * Returns context items for adding variables on match nodes.
     *
     * @param element The selected element
     * @param _context Additional request context
     * @returns Context actions for this handler
     */
    getContextItems(element: GModelElement, _context: ContextActionRequestContext): ContextItem[] {
        if (element.type !== ModelTransformationElementType.NODE_MATCH) {
            return [];
        }

        return [
            {
                id: `add-variable-${element.id}`,
                label: "Add Variable",
                icon: "variable-plus",
                sortString: "c",
                action: this.buildInsertVariableAction(element, `${NEW_VARIABLE_LABEL_PREFIX}${element.id}`)
            },
            {
                id: `add-variable-reassignment-${element.id}`,
                label: "Add Variable Assignment",
                icon: "equal",
                sortString: "d",
                action: this.buildInsertVariableAction(
                    element,
                    `${NEW_VARIABLE_REASSIGNMENT_LABEL_PREFIX}${element.id}`
                )
            }
        ];
    }

    /**
     * Builds an {@link InsertNewLabelAction} for inserting a new variable label on the
     * given match node.
     *
     * Both variable declarations and reassignments use this builder: they share the variable
     * label element type, the variables compartment, and the {@link AddVariableOperation} used
     * on commit. Only [labelId] differs (via its prefix), which lets the label-edit validator
     * validate the in-progress text as a declaration or a reassignment.
     *
     * @param element The match-node GModel element.
     * @param labelId The placeholder label ID (its prefix selects declaration vs. reassignment).
     * @returns The {@link InsertNewLabelAction} to dispatch.
     */
    private buildInsertVariableAction(element: GModelElement, labelId: string): InsertNewLabelAction {
        const nodeId = element.id;

        const label = GVariableLabel.builder()
            .id(labelId)
            .text("")
            .isNewLabel(true)
            .newLabelOperationKind("add-variable")
            .newLabelParentElementId(nodeId)
            .build();

        const compartmentsId = `${nodeId}__compartments`;
        const variablesId = `${nodeId}__variables`;
        const whereClausesId = `${nodeId}__where-clauses`;

        const compartmentsContainer = element.children.find((c) => c.id === compartmentsId);

        if (compartmentsContainer == undefined) {
            const topDivider = GHorizontalDivider.builder()
                .type(ModelTransformationElementType.DIVIDER)
                .id(`${nodeId}__compartments-top-divider`)
                .build();

            const variablesCompartment = GCompartment.builder()
                .type(ModelTransformationElementType.COMPARTMENT)
                .id(variablesId)
                .build();
            variablesCompartment.children.push(label);

            const container = GMatchNodeCompartments.builder().id(compartmentsId).build();
            container.children.push(topDivider);
            container.children.push(variablesCompartment);

            return InsertNewLabelAction.create({
                parentElementId: nodeId,
                insertIndex: element.children.length,
                templates: [container],
                labelId
            });
        }

        const variablesCompartment = compartmentsContainer.children.find((c) => c.id === variablesId);

        if (variablesCompartment != undefined) {
            return InsertNewLabelAction.create({
                parentElementId: variablesId,
                insertIndex: variablesCompartment.children.length,
                templates: [label],
                labelId
            });
        }

        const templates: GModelElement[] = [];
        const whereClausesCompartment = compartmentsContainer.children.find((c) => c.id === whereClausesId);

        if (whereClausesCompartment != undefined) {
            const divider = GHorizontalDivider.builder()
                .type(ModelTransformationElementType.DIVIDER)
                .id(`${nodeId}__compartment-divider-1`)
                .build();
            templates.push(divider);
        }

        const newVariablesCompartment = GCompartment.builder()
            .type(ModelTransformationElementType.COMPARTMENT)
            .id(variablesId)
            .build();
        newVariablesCompartment.children.push(label);
        templates.push(newVariablesCompartment);

        return InsertNewLabelAction.create({
            parentElementId: compartmentsId,
            insertIndex: compartmentsContainer.children.length,
            templates,
            labelId
        });
    }
}
