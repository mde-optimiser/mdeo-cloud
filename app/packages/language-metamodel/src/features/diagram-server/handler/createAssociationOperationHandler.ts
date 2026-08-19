import type { GNode, CreateEdgeResult } from "@mdeo/language-shared";
import { sharedImport } from "@mdeo/language-shared";
import { CreateEdgeOperation } from "@mdeo/protocol-common";
import {
    Association,
    AssociationEnd,
    MetamodelAssociationOperators,
    type AssociationType,
    type AssociationEndType,
    type ClassType
} from "../../../grammar/metamodelTypes.js";
import { MetamodelElementType, EdgeCreationType } from "@mdeo/protocol-metamodel";
import { MetamodelBaseCreateEdgeOperationHandler } from "./metamodelBaseCreateEdgeOperationHandler.js";

const { injectable } = sharedImport("inversify");

/**
 * Maps each edge type offered by the toolbox to the association operator it writes.
 */
const EDGE_TYPE_TO_OPERATOR: Partial<Record<EdgeCreationType, MetamodelAssociationOperators>> = {
    [EdgeCreationType.UNIDIRECTIONAL]: MetamodelAssociationOperators.NAVIGABLE_TO_TARGET,
    [EdgeCreationType.BIDIRECTIONAL]: MetamodelAssociationOperators.BIDIRECTIONAL,
    [EdgeCreationType.COMPOSITION]: MetamodelAssociationOperators.COMPOSITION_SOURCE,
    [EdgeCreationType.NAVIGABLE_COMPOSITION]: MetamodelAssociationOperators.COMPOSITION_SOURCE_NAVIGABLE_TARGET
};

/**
 * Params shape embedded in the create-edge schema for metamodel edges.
 */
interface MetamodelCreateEdgeParams {
    edgeType?: EdgeCreationType;
    sourceLabel?: string;
    targetLabel?: string;
}

/**
 * Handles create-edge operations that produce association edges in the metamodel diagram.
 * Inserts a new top-level association declaration in the source model.
 */
@injectable()
export class CreateAssociationOperationHandler extends MetamodelBaseCreateEdgeOperationHandler {
    override readonly operationType = CreateEdgeOperation.KIND;
    override label = "Create association edge";
    readonly elementTypeIds = [MetamodelElementType.EDGE_ASSOCIATION];

    protected override async createEdge(
        operation: CreateEdgeOperation,
        sourceElement: GNode,
        targetElement: GNode
    ): Promise<CreateEdgeResult | undefined> {
        const sourceClass = this.resolveClass(sourceElement);
        if (sourceClass == undefined) {
            return undefined;
        }

        const targetClass = this.resolveClass(targetElement);
        if (targetClass == undefined) {
            return undefined;
        }

        const params = (operation.schema.params ?? {}) as MetamodelCreateEdgeParams;
        const edgeType = params.edgeType;
        const operator = edgeType ? EDGE_TYPE_TO_OPERATOR[edgeType] : undefined;

        if (operator === undefined) {
            return undefined;
        }

        return this.createAssociationEdge(sourceClass, targetClass, operator, params);
    }

    /**
     * Creates an inheritance edge by adding a class extension to the source class.
     *
     * @param sourceClass The source class AST node
     * @param targetClass The target class AST node
     * @returns A CreateEdgeResult with the workspace edit to apply, or undefined on failure
     */
    /**
     * Creates an association edge by inserting a new association declaration.
     *
     * @param sourceClass The source class AST node
     * @param targetClass The target class AST node
     * @param operator The association operator
     * @param params The creation parameters
     * @returns A CreateEdgeResult with the workspace edit to apply, or undefined on failure
     */
    private async createAssociationEdge(
        sourceClass: ClassType,
        targetClass: ClassType,
        operator: MetamodelAssociationOperators,
        params: MetamodelCreateEdgeParams
    ): Promise<CreateEdgeResult | undefined> {
        if (!sourceClass.name || !targetClass.name) {
            return undefined;
        }

        const sourceEnd: AssociationEndType = {
            $type: AssociationEnd.name,
            class: { $refText: sourceClass.name, ref: sourceClass },
            name: params.sourceLabel,
            multiplicity: undefined
        };

        const targetEnd: AssociationEndType = {
            $type: AssociationEnd.name,
            class: { $refText: targetClass.name, ref: targetClass },
            name: params.targetLabel,
            multiplicity: undefined
        };

        const associationNode: AssociationType = {
            $type: Association.name,
            source: sourceEnd,
            operator,
            target: targetEnd
        };

        const rootCstNode = this.modelState.sourceModel?.$cstNode;
        if (!rootCstNode) {
            throw new Error("Root CST node is not available.");
        }

        const document = this.modelState.sourceModel?.$document;
        const isEmpty = document?.textDocument.getText().trim().length === 0;

        const serialized = await this.serializeNode(associationNode);
        const workspaceEdit = await this.createInsertAfterNodeEdit(rootCstNode, serialized, !isEmpty);

        return {
            edgeType: MetamodelElementType.EDGE_ASSOCIATION,
            workspaceEdit,
            insertSpecification: {
                container: this.modelState.sourceModel!,
                property: "elements",
                elements: [associationNode]
            },
            insertedElement: {
                element: associationNode,
                edge: {
                    type: MetamodelElementType.EDGE_ASSOCIATION,
                    from: sourceClass,
                    to: targetClass
                }
            }
        };
    }
}
