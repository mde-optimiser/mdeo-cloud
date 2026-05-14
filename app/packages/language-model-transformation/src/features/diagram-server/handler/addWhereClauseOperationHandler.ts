import {
    BaseOperationHandler,
    OperationHandlerCommand,
    GCompartment,
    GHorizontalDivider,
    sharedImport
} from "@mdeo/language-shared";
import type { Command, GModelElement } from "@eclipse-glsp/server";
import type { AddWhereClauseOperation } from "@mdeo/protocol-model-transformation";
import { ModelTransformationElementType } from "@mdeo/protocol-model-transformation";
import type { ContextActionRequestContext, ContextItemProvider } from "@mdeo/language-shared";
import type { ContextItem } from "@mdeo/protocol-common";
import { InsertNewLabelAction } from "@mdeo/protocol-common";
import { GMatchNodeCompartments } from "../model/matchNodeCompartments.js";
import { GWhereClauseLabel } from "../model/whereClauseLabel.js";
import { getPatternFromMatchNode } from "../modelTransformationPatternUtils.js";

const { injectable } = sharedImport("inversify");
const { GrammarUtils } = sharedImport("langium");

/**
 * Prefix used to build unique IDs for new-where-clause placeholder labels.
 * The full label ID is `${NEW_WHERE_CLAUSE_LABEL_PREFIX}${matchNodeId}`.
 */
export const NEW_WHERE_CLAUSE_LABEL_PREFIX = "__new-label-whereclause-";

/**
 * Handler for adding where clause entries on match nodes.
 */
@injectable()
export class AddWhereClauseOperationHandler extends BaseOperationHandler implements ContextItemProvider {
    override readonly operationType = "addWhereClause";

    /**
     * Creates a command for add-where-clause operations.
     *
     * @param _operation The requested operation
     * @returns Undefined while insertion logic is delegated to label/edit workflow
     */
    override async createCommand(operation: AddWhereClauseOperation): Promise<Command | undefined> {
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
     * Returns context items for adding where clauses on match nodes.
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
                id: `add-where-clause-${element.id}`,
                label: "Add Where Clause",
                icon: "funnel-plus",
                sortString: "d",
                action: this.buildInsertWhereClauseAction(element)
            }
        ];
    }

    /**
     * Builds an {@link InsertNewLabelAction} for inserting a new where-clause label on the
     * given match node.
     *
     * Where-clauses must appear **before** variables in the `__compartments` container.
     * The method handles three cases:
     * - No `__compartments` container: creates the full container tree.
     * - `__compartments` exists but `__where-clauses` does not: inserts a new `__where-clauses`
     *   compartment **before** any existing `__variables` compartment (at index 1, after the
     *   top-divider), and also inserts an inter-compartment divider.
     * - `__where-clauses` already exists: appends the label to the end of that compartment.
     *
     * @param element The match-node GModel element.
     * @returns The {@link InsertNewLabelAction} to dispatch.
     */
    private buildInsertWhereClauseAction(element: GModelElement): InsertNewLabelAction {
        const nodeId = element.id;
        const labelId = `${NEW_WHERE_CLAUSE_LABEL_PREFIX}${nodeId}`;

        const label = GWhereClauseLabel.builder()
            .id(labelId)
            .text("")
            .isNewLabel(true)
            .newLabelOperationKind("add-where-clause")
            .newLabelParentElementId(nodeId)
            .build();
        label.editMode = true;

        const compartmentsId = `${nodeId}__compartments`;
        const whereClausesId = `${nodeId}__where-clauses`;
        const variablesId = `${nodeId}__variables`;

        const compartmentsContainer = element.children.find((c) => c.id === compartmentsId);

        if (compartmentsContainer == undefined) {
            const topDivider = GHorizontalDivider.builder()
                .type(ModelTransformationElementType.DIVIDER)
                .id(`${nodeId}__compartments-top-divider`)
                .build();

            const whereClausesCompartment = GCompartment.builder()
                .type(ModelTransformationElementType.COMPARTMENT)
                .id(whereClausesId)
                .build();
            whereClausesCompartment.children.push(label);

            const container = GMatchNodeCompartments.builder().id(compartmentsId).build();
            container.children.push(topDivider);
            container.children.push(whereClausesCompartment);

            return InsertNewLabelAction.create({
                parentElementId: nodeId,
                insertIndex: element.children.length,
                templates: [container],
                labelId
            });
        }

        const whereClausesCompartment = compartmentsContainer.children.find((c) => c.id === whereClausesId);

        if (whereClausesCompartment != undefined) {
            return InsertNewLabelAction.create({
                parentElementId: whereClausesId,
                insertIndex: whereClausesCompartment.children.length,
                templates: [label],
                labelId
            });
        }

        const newWhereClausesCompartment = GCompartment.builder()
            .type(ModelTransformationElementType.COMPARTMENT)
            .id(whereClausesId)
            .build();
        newWhereClausesCompartment.children.push(label);

        const templates: GModelElement[] = [newWhereClausesCompartment];

        const variablesCompartment = compartmentsContainer.children.find((c) => c.id === variablesId);
        if (variablesCompartment != undefined) {
            const divider = GHorizontalDivider.builder()
                .type(ModelTransformationElementType.DIVIDER)
                .id(`${nodeId}__compartment-divider-1`)
                .build();
            templates.push(divider);
        }

        return InsertNewLabelAction.create({
            parentElementId: compartmentsId,
            insertIndex: 1,
            templates,
            labelId
        });
    }
}
