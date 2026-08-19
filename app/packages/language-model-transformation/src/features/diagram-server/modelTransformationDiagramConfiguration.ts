import type { DiagramConfiguration, EdgeTypeHint, GModelElementConstructor, ShapeTypeHint } from "@eclipse-glsp/server";
import { sharedImport } from "@mdeo/language-shared";
import { ModelTransformationElementType } from "@mdeo/protocol-model-transformation";
import { GStartNode } from "./model/startNode.js";
import { GEndNode } from "./model/endNode.js";
import { GMatchNode } from "./model/matchNode.js";
import { GSplitNode } from "./model/splitNode.js";
import { GMergeNode } from "./model/mergeNode.js";
import { GControlFlowEdge } from "./model/controlFlowEdge.js";
import { GControlFlowLabelNode } from "./model/controlFlowLabelNode.js";
import { GControlFlowLabel } from "./model/controlFlowLabel.js";
import { GPatternInstanceNode } from "./model/patternInstanceNode.js";
import { GPatternInstanceNameLabel } from "./model/patternInstanceNameLabel.js";
import { GPatternPropertyLabel } from "./model/patternPropertyLabel.js";
import { GPatternLinkEdge } from "./model/patternLinkEdge.js";
import { GPatternLinkEndNode } from "./model/patternLinkEndNode.js";
import { GPatternLinkEndLabel } from "./model/patternLinkEndLabel.js";
import { GWhereClauseLabel } from "./model/whereClauseLabel.js";
import { GVariableLabel } from "./model/variableLabel.js";

const { injectable } = sharedImport("inversify");
const { ServerLayoutKind, getDefaultMapping, DefaultTypes } = sharedImport("@eclipse-glsp/server");

/**
 * Configuration for model transformation diagrams defining layout behavior and element type mappings.
 * Specifies manual layout with client-side rendering and animated updates.
 */
@injectable()
export class ModelTransformationDiagramConfiguration implements DiagramConfiguration {
    readonly layoutKind = ServerLayoutKind.MANUAL;
    readonly animatedUpdate = true;
    readonly needsClientLayout = true;
    readonly needsServerLayout = false;

    /**
     * Returns the mapping of element type IDs to their corresponding GModelElement constructors.
     *
     * @returns Map from type ID to GModelElementConstructor
     */
    get typeMapping(): Map<string, GModelElementConstructor> {
        const mapping = getDefaultMapping();

        mapping.set(ModelTransformationElementType.NODE_START, GStartNode);
        mapping.set(ModelTransformationElementType.NODE_END, GEndNode);
        mapping.set(ModelTransformationElementType.NODE_MATCH, GMatchNode);
        mapping.set(ModelTransformationElementType.NODE_SPLIT, GSplitNode);
        mapping.set(ModelTransformationElementType.NODE_MERGE, GMergeNode);

        mapping.set(ModelTransformationElementType.EDGE_CONTROL_FLOW, GControlFlowEdge);
        mapping.set(ModelTransformationElementType.NODE_CONTROL_FLOW_LABEL, GControlFlowLabelNode);
        mapping.set(ModelTransformationElementType.LABEL_CONTROL_FLOW, GControlFlowLabel);

        mapping.set(ModelTransformationElementType.NODE_PATTERN_INSTANCE, GPatternInstanceNode);
        mapping.set(ModelTransformationElementType.LABEL_PATTERN_INSTANCE_NAME, GPatternInstanceNameLabel);
        mapping.set(ModelTransformationElementType.LABEL_PATTERN_PROPERTY, GPatternPropertyLabel);

        mapping.set(ModelTransformationElementType.EDGE_PATTERN_LINK, GPatternLinkEdge);
        mapping.set(ModelTransformationElementType.NODE_PATTERN_LINK_END, GPatternLinkEndNode);
        mapping.set(ModelTransformationElementType.LABEL_PATTERN_LINK_END, GPatternLinkEndLabel);

        mapping.set(ModelTransformationElementType.LABEL_WHERE_CLAUSE, GWhereClauseLabel);
        mapping.set(ModelTransformationElementType.LABEL_VARIABLE, GVariableLabel);

        return mapping;
    }

    /**
     * Returns the shape type hints for model transformation diagram elements.
     *
     * @returns Array of shape type hints
     */
    get shapeTypeHints(): ShapeTypeHint[] {
        return [
            {
                elementTypeId: DefaultTypes.GRAPH,
                repositionable: false,
                deletable: false,
                resizable: false,
                reparentable: false,
                containableElementTypeIds: [
                    ModelTransformationElementType.NODE_START,
                    ModelTransformationElementType.NODE_END,
                    ModelTransformationElementType.NODE_MATCH,
                    ModelTransformationElementType.NODE_SPLIT,
                    ModelTransformationElementType.NODE_MERGE
                ]
            },
            {
                elementTypeId: ModelTransformationElementType.NODE_START,
                repositionable: true,
                deletable: false,
                resizable: false,
                reparentable: false
            },
            {
                elementTypeId: ModelTransformationElementType.NODE_END,
                repositionable: true,
                deletable: true,
                resizable: false,
                reparentable: false
            },
            {
                elementTypeId: ModelTransformationElementType.NODE_MATCH,
                repositionable: true,
                deletable: true,
                resizable: true,
                reparentable: false,
                containableElementTypeIds: [ModelTransformationElementType.NODE_PATTERN_INSTANCE]
            },
            {
                elementTypeId: ModelTransformationElementType.NODE_SPLIT,
                repositionable: true,
                deletable: true,
                resizable: false,
                reparentable: false
            },
            {
                elementTypeId: ModelTransformationElementType.NODE_MERGE,
                repositionable: true,
                deletable: false,
                resizable: false,
                reparentable: false
            },
            {
                elementTypeId: ModelTransformationElementType.NODE_PATTERN_INSTANCE,
                repositionable: true,
                deletable: true,
                resizable: true,
                reparentable: false
            }
        ];
    }

    /**
     * Returns the edge type hints for model transformation diagram elements.
     * Does NOT specify the link edge so that this can be handled correctly by the canConnect logic in the editor
     *
     * @returns Array of edge type hints
     */
    get edgeTypeHints(): EdgeTypeHint[] {
        return [
            {
                elementTypeId: ModelTransformationElementType.EDGE_CONTROL_FLOW,
                repositionable: false,
                deletable: false,
                routable: true,
                sourceElementTypeIds: [
                    ModelTransformationElementType.NODE_START,
                    ModelTransformationElementType.NODE_MATCH,
                    ModelTransformationElementType.NODE_SPLIT,
                    ModelTransformationElementType.NODE_MERGE
                ],
                targetElementTypeIds: [
                    ModelTransformationElementType.NODE_END,
                    ModelTransformationElementType.NODE_MATCH,
                    ModelTransformationElementType.NODE_SPLIT,
                    ModelTransformationElementType.NODE_MERGE
                ]
            }
        ];
    }
}
