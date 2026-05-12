import type { GModelElement, GModelRoot } from "@eclipse-glsp/server";
import {
    sharedImport,
    BaseGModelFactory,
    GCompartment,
    GHorizontalDivider,
    EdgeLayoutMetadataUtil,
    NodeLayoutMetadataUtil
} from "@mdeo/language-shared";
import type { ModelIdRegistry, GraphMetadata } from "@mdeo/language-shared";
import type { NodeLayoutMetadata, EdgeLayoutMetadata } from "@mdeo/protocol-common";
import type { PartialGeneratedModel } from "../../../grammar/generatedModelPartialTypes.js";
import type { ModelData, ModelDataInstance, ModelDataLink, ModelDataPropertyValue } from "../../modelData.js";
import { GObjectNode } from "../model/objectNode.js";
import { GObjectNameLabel } from "../model/objectNameLabel.js";
import { GPropertyLabel } from "../model/propertyLabel.js";
import { GLinkEdge } from "../model/linkEdge.js";
import { GLinkEndNode } from "../model/linkEndNode.js";
import { GLinkEndLabel } from "../model/linkEndLabel.js";
import { ModelElementType } from "@mdeo/protocol-model";

const { injectable } = sharedImport("inversify");
const { GGraph } = sharedImport("@eclipse-glsp/server");

type GGraphType = ReturnType<typeof GGraph.builder>["proxy"];

/**
 * Factory for creating GLSP graph models from generated model JSON content.
 * Parses the JSON ModelData from the AST content field and creates a visual
 * graph representation similar to the regular ModelGModelFactory.
 */
@injectable()
export class GeneratedModelGModelFactory extends BaseGModelFactory<PartialGeneratedModel> {
    /**
     * Creates the internal GModel from the generated model source.
     * Parses the JSON content from the AST and builds the graph.
     *
     * @param sourceModel The generated model AST
     * @param idRegistry The ID registry for element ID generation
     * @returns The created GModelRoot
     */
    override async createModelInternal(sourceModel: PartialGeneratedModel, idRegistry: ModelIdRegistry): Promise<GModelRoot> {
        const graph = GGraph.builder().id("model-graph").addCssClass("editor-model").build();

        const modelData = this.parseModelData(sourceModel);
        if (modelData == undefined) {
            return graph;
        }

        await this.createInstanceNodes(graph, modelData.instances, idRegistry);
        await this.createLinkEdges(graph, modelData.links, modelData.instances, idRegistry);

        return graph;
    }

    /**
     * Parses the JSON content from the AST into ModelData.
     *
     * @param sourceModel The generated model AST
     * @returns The parsed ModelData or undefined if invalid
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
     * Creates visual nodes for all model data instances.
     *
     * @param graph The graph to add nodes to
     * @param instances Array of instances to create nodes for
     * @param idRegistry The ID registry for element ID generation
     */
    private async createInstanceNodes(graph: GGraphType, instances: ModelDataInstance[], _idRegistry: ModelIdRegistry): Promise<void> {
        const validatedMetadata = await this.modelState.getValidatedMetadata();

        for (const instance of instances) {
            const nodeId = `GeneratedModel_instance_${instance.name}`;
            const metadata = validatedMetadata.nodes[nodeId]?.meta as NodeLayoutMetadata | undefined;
            const nodeMeta: NodeLayoutMetadata = metadata ?? { position: { x: 0, y: 0 } };

            graph.children.push(this.createInstanceNode(instance, nodeId, nodeMeta));
        }
    }

    /**
     * Creates a visual node for a single model data instance.
     *
     * @param instance The model data instance
     * @param nodeId The unique node ID
     * @param metadata The layout metadata for the node
     * @returns The created GObjectNode
     */
    private createInstanceNode(instance: ModelDataInstance, nodeId: string, metadata: NodeLayoutMetadata): GObjectNode {
        const objectName = instance.name;
        const typeName = instance.className;

        const node = GObjectNode.builder().id(nodeId).name(objectName).typeName(typeName).meta(metadata).build();

        node.children.push(
            ...this.createObjectHeader(nodeId, objectName, typeName),
            ...this.createPropertyAssignments(nodeId, instance)
        );

        return node;
    }

    /**
     * Creates the header compartment containing the combined name and type label.
     *
     * @param nodeId The ID of the object node
     * @param name The name of the object
     * @param typeName The type name of the object
     * @returns Array of GModelElements for the header
     */
    private createObjectHeader(nodeId: string, name: string, typeName: string): GModelElement[] {
        const headerCompartment = GCompartment.builder()
            .type(ModelElementType.COMPARTMENT)
            .id(`${nodeId}#header-compartment`)
            .build();

        const combinedLabel = GObjectNameLabel.builder().id(`${nodeId}#name`).text(`${name} : ${typeName}`).build();

        headerCompartment.children.push(combinedLabel);
        return [headerCompartment];
    }

    /**
     * Creates the property assignments compartment for an instance node.
     *
     * @param nodeId The ID of the object node
     * @param instance The model data instance containing property assignments
     * @returns Array of GModelElements for the properties compartment
     */
    private createPropertyAssignments(nodeId: string, instance: ModelDataInstance): GModelElement[] {
        const children: GModelElement[] = [];
        const entries = Object.entries(instance.properties);

        if (entries.length === 0) {
            return children;
        }

        const divider = GHorizontalDivider.builder().type(ModelElementType.DIVIDER).id(`${nodeId}#divider`).build();
        children.push(divider);

        const propertiesCompartment = GCompartment.builder()
            .type(ModelElementType.COMPARTMENT)
            .id(`${nodeId}#properties-compartment`)
            .build();

        for (const [propName, propValue] of entries) {
            const propId = `${nodeId}_prop_${propName}`;
            const propLabel = this.createPropertyLabel(propId, propName, propValue);
            propertiesCompartment.children.push(propLabel);
        }

        children.push(propertiesCompartment);
        return children;
    }

    /**
     * Creates a property assignment label.
     *
     * @param propId The ID for the property label
     * @param propName The property name
     * @param propValue The property value
     * @returns The created GPropertyLabel
     */
    private createPropertyLabel(
        propId: string,
        propName: string,
        propValue: ModelDataPropertyValue | ModelDataPropertyValue[]
    ): GPropertyLabel {
        const valueStr = this.formatPropertyValue(propValue);
        return GPropertyLabel.builder().id(propId).text(`${propName} = ${valueStr}`).build();
    }

    /**
     * Formats a property value to a string representation.
     *
     * @param value The property value
     * @returns The formatted value string
     */
    private formatPropertyValue(value: ModelDataPropertyValue | ModelDataPropertyValue[]): string {
        if (Array.isArray(value)) {
            const items = value.map((v) => this.formatSingleValue(v));
            return `[${items.join(", ")}]`;
        }
        return this.formatSingleValue(value);
    }

    /**
     * Formats a single property value.
     *
     * @param value The single property value
     * @returns The formatted string
     */
    private formatSingleValue(value: ModelDataPropertyValue): string {
        if (value === null) {
            return "null";
        }
        if (typeof value === "string") {
            return `"${value}"`;
        }
        if (typeof value === "number" || typeof value === "boolean") {
            return String(value);
        }
        if (typeof value === "object" && "enum" in value) {
            return value.enum;
        }
        return "?";
    }

    /**
     * Creates link edges for all links in the model data.
     *
     * @param graph The graph to add edges to
     * @param links Array of links to create edges for
     * @param instances Array of instances for resolving references
     * @param idRegistry The ID registry for element ID generation
     */
    private async createLinkEdges(
        graph: GGraphType,
        links: ModelDataLink[],
        _instances: ModelDataInstance[],
        _idRegistry: ModelIdRegistry
    ): Promise<void> {
        const validatedMetadata = await this.modelState.getValidatedMetadata();

        for (let i = 0; i < links.length; i++) {
            const link = links[i];
            const edgeId = this.buildLinkEdgeId(link, i);
            const metadata = validatedMetadata.edges[edgeId]?.meta as EdgeLayoutMetadata | undefined;
            const edgeMeta: EdgeLayoutMetadata = metadata ?? EdgeLayoutMetadataUtil.create();

            const edge = await this.createLinkEdge(link, edgeId, edgeMeta);
            if (edge != undefined) {
                graph.children.push(edge);
            }
        }
    }

    /**
     * Builds a deterministic edge ID from a link.
     *
     * @param link The link
     * @param index The link index for disambiguation
     * @returns The edge ID
     */
    private buildLinkEdgeId(link: ModelDataLink, index: number): string {
        const sourcePart = link.sourceProperty ? `${link.sourceName}_${link.sourceProperty}` : link.sourceName;
        const targetPart = link.targetProperty ? `${link.targetName}_${link.targetProperty}` : link.targetName;
        return `GeneratedModel_link_${sourcePart}--${targetPart}_${index}`;
    }

    /**
     * Creates a link edge between two instances.
     *
     * @param link The model data link
     * @param edgeId The unique edge ID
     * @param metadata The edge layout metadata
     * @returns The created GLinkEdge or undefined if invalid
     */
    private async createLinkEdge(link: ModelDataLink, edgeId: string, metadata: EdgeLayoutMetadata): Promise<GLinkEdge | undefined> {
        const sourceId = `GeneratedModel_instance_${link.sourceName}`;
        const targetId = `GeneratedModel_instance_${link.targetName}`;

        const edgeBuilder = GLinkEdge.builder().id(edgeId).sourceId(sourceId).targetId(targetId).meta(metadata);

        if (link.sourceProperty) {
            edgeBuilder.sourceProperty(link.sourceProperty);
        }
        if (link.targetProperty) {
            edgeBuilder.targetProperty(link.targetProperty);
        }

        const edge = edgeBuilder.build();
        await this.addLinkLabels(edge, edgeId, link.sourceProperty, link.targetProperty);

        return edge;
    }

    /**
     * Adds source and target label nodes to a link edge if properties are specified.
     *
     * @param edge The link edge to add labels to
     * @param edgeId The edge ID
     * @param sourceProperty Optional source property name
     * @param targetProperty Optional target property name
     */
    private async addLinkLabels(
        edge: GLinkEdge,
        edgeId: string,
        sourceProperty: string | null,
        targetProperty: string | null
    ): Promise<void> {
        const validatedMetadata = await this.modelState.getValidatedMetadata();

        if (sourceProperty) {
            const sourceNodes = this.createLinkEndNodes(edgeId, sourceProperty, "target", validatedMetadata);
            edge.children.push(...sourceNodes);
        }

        if (targetProperty) {
            const targetNodes = this.createLinkEndNodes(edgeId, targetProperty, "source", validatedMetadata);
            edge.children.push(...targetNodes);
        }
    }

    /**
     * Creates the GModel nodes that represent a link endpoint (source or target side)
     * with its associated property-name label.
     *
     * @param edgeId            ID of the parent edge element.
     * @param property          The property name to display on the endpoint label.
     * @param end               Whether this is the `"source"` or `"target"` end.
     * @param validatedMetadata Current validated layout metadata for position lookup.
     * @returns An array of GModel elements representing the endpoint node and its label.
     */
    private createLinkEndNodes(
        edgeId: string,
        property: string,
        end: "source" | "target",
        validatedMetadata: GraphMetadata
    ): GModelElement[] {
        const nodes: GModelElement[] = [];

        const nodeId = `${edgeId}#${end}-node`;
        const nodeMeta = validatedMetadata.nodes[nodeId];
        const metadata =
            nodeMeta?.meta != undefined && NodeLayoutMetadataUtil.isValid(nodeMeta.meta)
                ? nodeMeta.meta
                : NodeLayoutMetadataUtil.create(0, 0);

        const endNode = GLinkEndNode.builder().id(nodeId).end(end).meta(metadata).build();

        const endLabel = GLinkEndLabel.builder().id(`${edgeId}#${end}-label`).text(property).readonly(true).build();

        endNode.children.push(endLabel);
        nodes.push(endNode);

        return nodes;
    }
}
