import { sharedImport, type GraphMetadata, type NodeMetadata, type EdgeMetadata } from "@mdeo/language-shared";
import { ModelElementType } from "@mdeo/protocol-model";
import type { PartialGeneratedModel } from "../../../grammar/generatedModelPartialTypes.js";
import type { ModelData, ModelDataInstance, ModelDataLink } from "../../modelData.js";
import { ModelMetadataManager } from "../modelMetadataManager.js";

const { injectable } = sharedImport("inversify");

/**
 * Manages metadata validation and synchronization for generated model diagrams.
 * Parses JSON content from the AST to extract graph metadata.
 * Extends ModelMetadataManager to reuse verification and cost calculation logic.
 */
@injectable()
export class GeneratedModelMetadataManager extends ModelMetadataManager {
    protected override extractGraphMetadata(sourceModel: PartialGeneratedModel): GraphMetadata {
        const nodes: Record<string, NodeMetadata> = {};
        const edges: Record<string, EdgeMetadata> = {};

        const modelData = this.parseModelData(sourceModel);
        if (modelData == undefined) {
            return { nodes, edges };
        }

        this.extractJsonInstanceMetadata(modelData.instances, nodes);
        this.extractJsonLinkMetadata(modelData.links, edges, nodes);

        return { nodes, edges };
    }

    /**
     * Parses the JSON content from the AST into ModelData.
     *
     * @param sourceModel The partial generated model containing the JSON content
     * @returns Parsed ModelData or undefined if parsing fails
     */
    private parseModelData(sourceModel: PartialGeneratedModel): ModelData | undefined {
        const content = sourceModel.content;
        if (content == undefined || content.trim().length === 0) {
            return undefined;
        }

        try {
            return JSON.parse(content) as ModelData;
        } catch {
            return undefined;
        }
    }

    /**
     * Extracts metadata for all instances from JSON data.
     *
     * @param instances List of instances in the model data
     * @param nodes Record to populate with node metadata
     */
    private extractJsonInstanceMetadata(instances: ModelDataInstance[], nodes: Record<string, NodeMetadata>): void {
        for (const instance of instances) {
            const nodeId = `GeneratedModel_instance_${instance.name}`;
            nodes[nodeId] = {
                type: ModelElementType.NODE_OBJECT,
                attrs: {
                    name: instance.name,
                    typeName: instance.className
                }
            };
        }
    }

    /**
     * Extracts metadata for all links from JSON data.
     *
     * @param links List of links in the model data
     * @param edges Record to populate with edge metadata
     * @param nodes Record to populate with node metadata
     */
    private extractJsonLinkMetadata(
        links: ModelDataLink[],
        edges: Record<string, EdgeMetadata>,
        nodes: Record<string, NodeMetadata>
    ): void {
        for (let i = 0; i < links.length; i++) {
            const link = links[i];
            const sourcePart = link.sourceProperty ? `${link.sourceName}_${link.sourceProperty}` : link.sourceName;
            const targetPart = link.targetProperty ? `${link.targetName}_${link.targetProperty}` : link.targetName;
            const edgeId = `GeneratedModel_link_${sourcePart}--${targetPart}_${i}`;

            const sourceId = `GeneratedModel_instance_${link.sourceName}`;
            const targetId = `GeneratedModel_instance_${link.targetName}`;

            edges[edgeId] = {
                type: ModelElementType.EDGE_LINK,
                from: sourceId,
                to: targetId,
                attrs: {
                    sourceProperty: link.sourceProperty,
                    targetProperty: link.targetProperty
                }
            };

            if (link.sourceProperty != null) {
                nodes[`${edgeId}__target-node`] = {
                    type: ModelElementType.NODE_LINK_END,
                    attrs: {}
                };
            }

            if (link.targetProperty != null) {
                nodes[`${edgeId}__source-node`] = {
                    type: ModelElementType.NODE_LINK_END,
                    attrs: {}
                };
            }
        }
    }
}
