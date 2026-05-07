import {
    sharedImport,
    MetadataManager,
    type GraphMetadata,
    type NodeMetadata,
    type EdgeMetadata,
    type ModelIdRegistry,
    DefaultModelIdRegistry,
    ModelIdProvider,
    type ModelIdProvider as ModelIdProviderType,
    EdgeLayoutMetadataUtil,
    NodeLayoutMetadataUtil
} from "@mdeo/language-shared";
import { ModelElementType } from "@mdeo/protocol-model";
import type { PartialModel, PartialObjectInstance, PartialLink } from "../../grammar/modelPartialTypes.js";

const { injectable, inject } = sharedImport("inversify");

/**
 * Manages metadata validation and synchronization for model diagrams.
 * Implements cost calculations based on semantic similarity between model elements.
 */
@injectable()
export class ModelMetadataManager extends MetadataManager<PartialModel> {
    @inject(ModelIdProvider)
    protected modelIdProvider!: ModelIdProviderType;

    protected override gedWorkerUrl = "/plugin/model/static/gedWorker.js";

    /**
     * Verifies and corrects invalid metadata for nodes and edges.
     *
     * @param model The node or edge metadata to verify
     * @returns Corrected metadata if invalid, undefined if valid
     */
    protected override verifyMetadata(model: NodeMetadata | EdgeMetadata): object | undefined {
        if (model.type === ModelElementType.NODE_OBJECT) {
            return NodeLayoutMetadataUtil.verify(model.meta, 250);
        }

        if (model.type === ModelElementType.NODE_LINK_END) {
            return NodeLayoutMetadataUtil.verify(model.meta);
        }

        if (model.type === ModelElementType.EDGE_LINK) {
            const edgeModel = model as EdgeMetadata;
            return EdgeLayoutMetadataUtil.verify(edgeModel.meta);
        }

        return undefined;
    }

    /**
     * Extracts graph metadata from the model source.
     *
     * @param sourceModel The model source
     * @returns Extracted graph metadata
     */
    protected extractGraphMetadata(sourceModel: PartialModel): GraphMetadata {
        const nodes: Record<string, NodeMetadata> = {};
        const edges: Record<string, EdgeMetadata> = {};

        const idRegistry = new DefaultModelIdRegistry(sourceModel, this.modelIdProvider);
        const { objects, links } = this.extractObjectsAndLinks(sourceModel);

        this.extractObjectMetadata(objects, idRegistry, nodes);
        this.extractLinkMetadata(links, idRegistry, edges, nodes);

        return { nodes, edges };
    }

    /**
     * Extracts objects and links from the model.
     *
     * @param sourceModel The model source
     * @returns Objects and links arrays
     */
    private extractObjectsAndLinks(sourceModel: PartialModel): {
        objects: PartialObjectInstance[];
        links: PartialLink[];
    } {
        const objects: PartialObjectInstance[] = [];
        const links: PartialLink[] = [];

        for (const obj of sourceModel.objects ?? []) {
            if (obj != undefined) {
                objects.push(obj);
            }
        }

        for (const link of sourceModel.links ?? []) {
            if (link != undefined) {
                links.push(link);
            }
        }

        return { objects, links };
    }

    /**
     * Extracts metadata for all objects.
     *
     * @param objects List of objects in the model
     * @param idRegistry Model ID registry
     * @param nodes Record to populate with node metadata
     */
    private extractObjectMetadata(
        objects: PartialObjectInstance[],
        idRegistry: ModelIdRegistry,
        nodes: Record<string, NodeMetadata>
    ): void {
        for (const obj of objects) {
            const nodeId = idRegistry.getId(obj);
            if (nodeId) {
                nodes[nodeId] = {
                    type: ModelElementType.NODE_OBJECT,
                    attrs: this.createObjectAttributes(obj)
                };
            }
        }
    }

    /**
     * Creates attributes for an object node.
     *
     * @param obj The object instance
     * @returns The object attributes
     */
    private createObjectAttributes(obj: PartialObjectInstance): Record<string, unknown> {
        const classRef = obj.class;
        const typeName = classRef?.$refText ?? (classRef?.ref as { name?: string } | undefined)?.name ?? "Unknown";
        return {
            name: obj.name ?? "unnamed",
            typeName
        };
    }

    /**
     * Extracts metadata for all links and their label nodes.
     *
     * @param links List of links in the model
     * @param idRegistry Model ID registry
     * @param edges Record to populate with edge metadata
     * @param nodes Record to populate with node metadata
     */
    private extractLinkMetadata(
        links: PartialLink[],
        idRegistry: ModelIdRegistry,
        edges: Record<string, EdgeMetadata>,
        nodes: Record<string, NodeMetadata>
    ): void {
        for (const link of links) {
            const edgeId = idRegistry.getId(link);
            const sourceObj = link.source?.object?.ref;
            const targetObj = link.target?.object?.ref;

            if (edgeId && sourceObj && targetObj) {
                edges[edgeId] = {
                    type: ModelElementType.EDGE_LINK,
                    from: idRegistry.getId(sourceObj),
                    to: idRegistry.getId(targetObj),
                    attrs: this.createLinkAttributes(link)
                };

                this.extractLinkLabelMetadata(link, edgeId, nodes);
            }
        }
    }

    /**
     * Extracts metadata for link label nodes.
     *
     * @param link The link definition
     * @param edgeId The edge ID
     * @param nodes Record to populate with node metadata
     */
    private extractLinkLabelMetadata(link: PartialLink, edgeId: string, nodes: Record<string, NodeMetadata>): void {
        if (link.source?.property != undefined) {
            nodes[`${edgeId}#source-node`] = {
                type: ModelElementType.NODE_LINK_END,
                attrs: {}
            };
        }

        if (link.target?.property != undefined) {
            nodes[`${edgeId}#target-node`] = {
                type: ModelElementType.NODE_LINK_END,
                attrs: {}
            };
        }
    }

    /**
     * Creates attributes for a link edge.
     *
     * @param link The link
     * @returns The link attributes
     */
    private createLinkAttributes(link: PartialLink): Record<string, unknown> {
        return {
            sourceProperty: link.source?.property?.$refText,
            targetProperty: link.target?.property?.$refText
        };
    }
}
