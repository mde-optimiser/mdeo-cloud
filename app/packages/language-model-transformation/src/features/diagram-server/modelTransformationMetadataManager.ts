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
    NodeLayoutMetadataUtil,
    EdgeLayoutMetadataUtil
} from "@mdeo/language-shared";
import { ModelTransformationElementType } from "@mdeo/protocol-model-transformation";
import type {
    ModelTransformationType,
    PatternType,
    PatternObjectInstanceType,
    PatternLinkType,
    PatternObjectInstanceReferenceType,
    PatternObjectInstanceDeleteType
} from "../../grammar/modelTransformationTypes.js";
import {
    PatternObjectInstance,
    PatternLink,
    PatternObjectInstanceReference,
    PatternObjectInstanceDelete
} from "../../grammar/modelTransformationTypes.js";
import {
    ModelTransformationControlFlowConverter,
    type ControlFlowNode,
    type ControlFlowMatchNode,
    type ControlFlowEdge
} from "./modelTransformationControlFlowConverter.js";
import { ModelTransformationIdGenerator } from "./modelTransformationIdGenerator.js";

const { injectable, inject } = sharedImport("inversify");

/**
 * Manages metadata validation and synchronization for model transformation diagrams.
 * Implements cost calculations based on semantic similarity between model elements.
 */
@injectable()
export class ModelTransformationMetadataManager extends MetadataManager<ModelTransformationType> {
    @inject(ModelIdProvider)
    protected modelIdProvider!: ModelIdProviderType;

    /**
     * Tracks pattern instances declared in previous matches for reference resolution.
     * Maps instance name to the node ID of the pattern instance.
     */
    private declaredInstances = new Map<string, string>();

    /**
     * Verifies and corrects invalid metadata for nodes and edges.
     *
     * @param model The node or edge metadata to verify
     * @returns Corrected metadata if invalid, undefined if valid
     */
    protected override verifyMetadata(model: NodeMetadata | EdgeMetadata): object | undefined {
        if (
            model.type === ModelTransformationElementType.NODE_START ||
            model.type === ModelTransformationElementType.NODE_END ||
            model.type === ModelTransformationElementType.NODE_MATCH ||
            model.type === ModelTransformationElementType.NODE_SPLIT ||
            model.type === ModelTransformationElementType.NODE_MERGE ||
            model.type === ModelTransformationElementType.NODE_PATTERN_INSTANCE
        ) {
            return NodeLayoutMetadataUtil.verify(model.meta, 250);
        }

        if (
            model.type === ModelTransformationElementType.NODE_CONTROL_FLOW_LABEL ||
            model.type === ModelTransformationElementType.NODE_PATTERN_LINK_END ||
            model.type === ModelTransformationElementType.NODE_PATTERN_LINK_MODIFIER
        ) {
            return NodeLayoutMetadataUtil.verify(model.meta);
        }

        if (
            model.type === ModelTransformationElementType.EDGE_CONTROL_FLOW ||
            model.type === ModelTransformationElementType.EDGE_PATTERN_LINK
        ) {
            const edgeModel = model as EdgeMetadata;
            return EdgeLayoutMetadataUtil.verify(edgeModel.meta);
        }

        return undefined;
    }

    /**
     * Extracts the graph metadata from the source model using the control-flow
     * graph converter, then populates nodes and edges from the resulting graph.
     *
     * @param sourceModel The source model to extract metadata from.
     * @returns The computed graph metadata.
     */
    protected extractGraphMetadata(sourceModel: ModelTransformationType): GraphMetadata {
        const nodes: Record<string, NodeMetadata> = {};
        const edges: Record<string, EdgeMetadata> = {};

        this.declaredInstances.clear();

        const idRegistry = new DefaultModelIdRegistry(sourceModel, this.modelIdProvider);
        const converter = new ModelTransformationControlFlowConverter(sourceModel, idRegistry, this.reflection);
        const cfg = converter.convert();

        for (const cfgNode of cfg.nodes) {
            this.addCFGNodeMetadata(cfgNode, nodes, edges, idRegistry);
        }
        for (const cfgEdge of cfg.edges) {
            this.addCFGEdgeMetadata(cfgEdge, nodes, edges);
        }

        return { nodes, edges };
    }

    /**
     * Adds metadata for a single control-flow node to the node accumulator.
     * For match nodes, also delegates pattern-content metadata to
     * {@link addMatchNode}.
     *
     * @param node The control-flow node.
     * @param nodes The node metadata accumulator.
     * @param edges The edge metadata accumulator.
     * @param idRegistry The model ID registry.
     */
    private addCFGNodeMetadata(
        node: ControlFlowNode,
        nodes: Record<string, NodeMetadata>,
        edges: Record<string, EdgeMetadata>,
        idRegistry: ModelIdRegistry
    ): void {
        switch (node.kind) {
            case "start":
                nodes[node.id] = { type: ModelTransformationElementType.NODE_START, attrs: {} };
                break;
            case "end":
                nodes[node.id] = {
                    type: ModelTransformationElementType.NODE_END,
                    attrs: { kind: node.endKind }
                };
                break;
            case "match": {
                const matchNode = node as ControlFlowMatchNode;
                this.addMatchNode(matchNode.id, matchNode.matchName, matchNode.pattern, idRegistry, nodes, edges);
                break;
            }
            case "split":
                nodes[node.id] = { type: ModelTransformationElementType.NODE_SPLIT, attrs: {} };
                break;
            case "merge":
                nodes[node.id] = { type: ModelTransformationElementType.NODE_MERGE, attrs: {} };
                break;
        }
    }

    /**
     * Adds metadata for a single control-flow edge to the edge accumulator.
     * When the edge carries a label, a label-wrapper node is also registered
     * so that the label's position is persisted across diagram updates.
     *
     * @param edge The control-flow edge.
     * @param nodes The node metadata accumulator.
     * @param edges The edge metadata accumulator.
     */
    private addCFGEdgeMetadata(
        edge: ControlFlowEdge,
        nodes: Record<string, NodeMetadata>,
        edges: Record<string, EdgeMetadata>
    ): void {
        edges[edge.id] = {
            type: ModelTransformationElementType.EDGE_CONTROL_FLOW,
            from: edge.sourceId,
            to: edge.targetId,
            attrs: { label: edge.label }
        };
        if (edge.label != undefined) {
            nodes[`${edge.id}#label-node`] = {
                type: ModelTransformationElementType.NODE_CONTROL_FLOW_LABEL,
                attrs: {}
            };
        }
    }

    /**
     * Adds a match node with its pattern instances to the metadata.
     * Also adds edges from instances TO the match node for graph edit distance.
     */
    private addMatchNode(
        matchNodeId: string,
        matchName: string,
        pattern: PatternType | undefined,
        idRegistry: ModelIdRegistry,
        nodes: Record<string, NodeMetadata>,
        edges: Record<string, EdgeMetadata>
    ): void {
        nodes[matchNodeId] = {
            type: ModelTransformationElementType.NODE_MATCH
        };

        const localInstances = new Map<string, PatternObjectInstanceType>();
        const referencedInstanceNodes = new Map<string, PatternObjectInstanceReferenceType>();
        const referencedInstanceImplicit = new Set<string>();
        const deletedInstances = new Set<string>();
        const deletedInstanceNodes = new Map<string, PatternObjectInstanceDeleteType>();

        if (pattern?.elements != undefined) {
            for (const element of pattern.elements) {
                if (this.reflection.isInstance(element, PatternObjectInstance)) {
                    if (element.name != undefined) {
                        localInstances.set(element.name, element);
                    }
                }
                if (this.reflection.isInstance(element, PatternObjectInstanceReference)) {
                    const instanceName = element.instance?.ref?.name;
                    if (instanceName) {
                        referencedInstanceNodes.set(instanceName, element);
                    }
                }
                if (this.reflection.isInstance(element, PatternObjectInstanceDelete)) {
                    const instanceName = element.instance?.ref?.name ?? element.instance?.$refText;
                    if (instanceName) {
                        deletedInstances.add(instanceName);
                        deletedInstanceNodes.set(instanceName, element);
                    }
                }
            }
            for (const element of pattern.elements) {
                if (this.reflection.isInstance(element, PatternLink)) {
                    const sourceInstanceName = element.source?.object?.ref?.name;
                    const targetInstanceName = element.target?.object?.ref?.name;
                    for (const name of [sourceInstanceName, targetInstanceName]) {
                        if (name != undefined) {
                            referencedInstanceImplicit.add(name);
                        }
                    }
                }
            }

            const instanceNodeIdsInPattern = new Map<string, string>();

            for (const [instanceName, instance] of localInstances) {
                const instanceNodeId = idRegistry.getId(instance);
                const typeName = instance.class?.$refText ?? instance.class?.ref?.name;

                nodes[instanceNodeId] = {
                    type: ModelTransformationElementType.NODE_PATTERN_INSTANCE,
                    attrs: {
                        name: instanceName,
                        typeName: typeName ?? undefined
                    }
                };

                this.addInstanceToMatchEdge(instanceNodeId, matchNodeId, edges);
                this.declaredInstances.set(instanceName, instanceNodeId);
                instanceNodeIdsInPattern.set(instanceName, instanceNodeId);
            }

            for (const [instanceName, ref] of referencedInstanceNodes) {
                if (!localInstances.has(instanceName) && !deletedInstances.has(instanceName)) {
                    const previousNodeId = this.declaredInstances.get(instanceName);
                    if (previousNodeId != undefined) {
                        const refNodeId = idRegistry.getId(ref);
                        nodes[refNodeId] = {
                            type: ModelTransformationElementType.NODE_PATTERN_INSTANCE,
                            attrs: {
                                name: instanceName,
                                isReference: true
                            }
                        };

                        this.addInstanceToMatchEdge(refNodeId, matchNodeId, edges);
                        instanceNodeIdsInPattern.set(instanceName, refNodeId);
                    }
                }
            }

            for (const instanceName of referencedInstanceImplicit) {
                if (
                    !localInstances.has(instanceName) &&
                    !deletedInstances.has(instanceName) &&
                    !referencedInstanceNodes.has(instanceName)
                ) {
                    const refNodeId = `PatternObjectInstanceReference_${matchName}_ref_${instanceName}`;
                    nodes[refNodeId] = {
                        type: ModelTransformationElementType.NODE_PATTERN_INSTANCE,
                        attrs: {
                            name: instanceName,
                            isReference: true
                        }
                    };

                    this.addInstanceToMatchEdge(refNodeId, matchNodeId, edges);
                    instanceNodeIdsInPattern.set(instanceName, refNodeId);
                }
            }

            for (const [instanceName, deleteElement] of deletedInstanceNodes) {
                if (!localInstances.has(instanceName)) {
                    const referencedInstance = deleteElement.instance?.ref;
                    const typeName = referencedInstance?.class?.$refText ?? referencedInstance?.class?.ref?.name;

                    const delNodeId = idRegistry.getId(deleteElement);
                    nodes[delNodeId] = {
                        type: ModelTransformationElementType.NODE_PATTERN_INSTANCE,
                        attrs: {
                            name: instanceName,
                            typeName: typeName ?? undefined,
                            isReference: true,
                            modifier: "delete"
                        }
                    };

                    this.addInstanceToMatchEdge(delNodeId, matchNodeId, edges);
                    instanceNodeIdsInPattern.set(instanceName, delNodeId);
                }
            }

            for (const element of pattern.elements) {
                if (this.reflection.isInstance(element, PatternLink)) {
                    this.addPatternLinkEdge(
                        element as PatternLinkType,
                        instanceNodeIdsInPattern,
                        idRegistry,
                        nodes,
                        edges
                    );
                }
            }
        }
    }

    /**
     * Adds an edge from a pattern instance TO its containing match node.
     * These edges are NOT important for layout but are NECESSARY for graph edit distance
     * to calculate a useful minimum and prevent undesired matches between instances
     * in different match nodes.
     *
     * @param instanceNodeId The metadata node ID of the pattern instance.
     * @param matchNodeId The metadata node ID of the containing match node.
     * @param edges The edge metadata accumulator to add the edge to.
     */
    private addInstanceToMatchEdge(
        instanceNodeId: string,
        matchNodeId: string,
        edges: Record<string, EdgeMetadata>
    ): void {
        const edgeId = ModelTransformationIdGenerator.instanceToMatchEdge(instanceNodeId, matchNodeId);
        edges[edgeId] = {
            type: ModelTransformationElementType.EDGE_CONTROL_FLOW,
            from: instanceNodeId,
            to: matchNodeId,
            attrs: { isContainment: true }
        };
    }

    /**
     * Adds a pattern link edge between two pattern instance nodes.
     *
     * @param link The pattern link AST element.
     * @param instanceNodeIdsInPattern Map from instance name to node ID within this pattern.
     * @param idRegistry The model ID registry used to derive the edge ID.
     * @param nodes The node metadata accumulator (receives optional endpoint nodes).
     * @param edges The edge metadata accumulator to add the link edge to.
     */
    private addPatternLinkEdge(
        link: PatternLinkType,
        instanceNodeIdsInPattern: Map<string, string>,
        idRegistry: ModelIdRegistry,
        nodes: Record<string, NodeMetadata>,
        edges: Record<string, EdgeMetadata>
    ): void {
        const edgeId = idRegistry.getId(link);
        const sourceInstanceName = link.source?.object?.ref?.name;
        const targetInstanceName = link.target?.object?.ref?.name;

        if (sourceInstanceName == undefined || targetInstanceName == undefined) {
            return;
        }

        const sourceId = this.resolveInstanceNodeId(sourceInstanceName, instanceNodeIdsInPattern);
        const targetId = this.resolveInstanceNodeId(targetInstanceName, instanceNodeIdsInPattern);

        if (sourceId == undefined || targetId == undefined) {
            return;
        }

        edges[edgeId] = {
            type: ModelTransformationElementType.EDGE_PATTERN_LINK,
            from: sourceId,
            to: targetId,
            attrs: {
                sourceProperty: link.source?.property?.$refText,
                targetProperty: link.target?.property?.$refText
            }
        };

        if (link.source?.property != undefined) {
            nodes[`${edgeId}#source-node`] = {
                type: ModelTransformationElementType.NODE_PATTERN_LINK_END,
                attrs: {}
            };
        }
        if (link.target?.property != undefined) {
            nodes[`${edgeId}#target-node`] = {
                type: ModelTransformationElementType.NODE_PATTERN_LINK_END,
                attrs: {}
            };
        }
    }

    /**
     * Resolves a pattern instance node ID by name.
     * Checks the current pattern's instance map first, then falls back to declaredInstances.
     *
     * @param instanceName The name of the pattern instance to resolve.
     * @param instanceNodeIdsInPattern Map from instance name to node ID within the current pattern.
     * @returns The node ID for the named instance, or undefined if it cannot be resolved.
     */
    private resolveInstanceNodeId(
        instanceName: string,
        instanceNodeIdsInPattern: Map<string, string>
    ): string | undefined {
        return instanceNodeIdsInPattern.get(instanceName) ?? this.declaredInstances.get(instanceName);
    }
}
