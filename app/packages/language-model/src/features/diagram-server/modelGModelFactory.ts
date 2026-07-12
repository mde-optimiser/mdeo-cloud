import type { GModelElement, GModelRoot } from "@eclipse-glsp/server";
import {
    sharedImport,
    BaseGModelFactory,
    GCompartment,
    GHorizontalDivider,
    EdgeLayoutMetadataUtil,
    NodeLayoutMetadataUtil,
    parseIdentifier,
    resolveRelativePath
} from "@mdeo/language-shared";
import type { ModelIdRegistry, GraphMetadata } from "@mdeo/language-shared";
import type { NodeLayoutMetadata, EdgeLayoutMetadata } from "@mdeo/protocol-common";
import { ID } from "@mdeo/language-common";
import { resolveClassChain, type ClassType } from "@mdeo/language-metamodel";
import type {
    PartialModel,
    PartialObjectInstance,
    PartialLink,
    PartialPropertyAssignment
} from "../../grammar/modelPartialTypes.js";
import { GObjectNode } from "./model/objectNode.js";
import { GObjectNameLabel } from "./model/objectNameLabel.js";
import { GPropertyLabel } from "./model/propertyLabel.js";
import { GLinkEdge } from "./model/linkEdge.js";
import { GLinkEndNode } from "./model/linkEndNode.js";
import { GLinkEndLabel } from "./model/linkEndLabel.js";
import { ModelElementType } from "@mdeo/protocol-model";
import { LinkAssociationResolver } from "./linkAssociationResolver.js";
import {
    EnumValue,
    ListValue,
    SimpleValue,
    type EnumValueType,
    type SingleValueType
} from "../../grammar/modelTypes.js";

const { injectable } = sharedImport("inversify");
const { GGraph } = sharedImport("@eclipse-glsp/server");

type GGraphType = ReturnType<typeof GGraph.builder>["proxy"];

/**
 * Factory for creating GLSP graph models from model AST.
 * Transforms the model source into a visual graph representation
 * with nodes for objects and edges for links.
 */
@injectable()
export class ModelGModelFactory extends BaseGModelFactory<PartialModel> {
    /**
     * Creates the internal GModel from the source model.
     *
     * @param sourceModel The model AST
     * @param idRegistry The ID registry for element ID generation
     * @returns The created GModelRoot
     */
    override async createModelInternal(sourceModel: PartialModel, idRegistry: ModelIdRegistry): Promise<GModelRoot> {
        const graph = GGraph.builder().id("model-graph").addCssClass("editor-model").build();

        const extracted = await this.extractElements(sourceModel);
        await this.createObjectNodes(graph, extracted.objects, idRegistry);
        await this.createLinkEdges(graph, extracted.links, idRegistry);
        await this.createCsvNodes(graph, sourceModel);
        return graph;
    }

    /**
     * Extracts objects and links from the model.
     *
     * @param model The model containing elements
     * @returns An object containing separate arrays of objects and links
     */
    private async extractElements(model: PartialModel): Promise<{
        objects: PartialObjectInstance[];
        links: PartialLink[];
    }> {
        const objects: PartialObjectInstance[] = [];
        const links: PartialLink[] = [];

        for (const obj of model.objects ?? []) {
            if (obj != undefined) {
                objects.push(obj);
            }
        }

        for (const link of model.links ?? []) {
            if (link != undefined) {
                links.push(link);
            }
        }

        return { objects, links };
    }

    /**
     * Creates visual nodes for all object instances.
     *
     * @param graph The graph to add nodes to
     * @param objects Array of objects to create nodes for
     * @param idRegistry The ID registry for AST node ID generation
     */
    private async createObjectNodes(
        graph: GGraphType,
        objects: PartialObjectInstance[],
        idRegistry: ModelIdRegistry
    ): Promise<void> {
        const validatedMetadata = await this.modelState.getValidatedMetadata();

        for (const obj of objects) {
            const nodeId = idRegistry.getId(obj);
            const metadata = validatedMetadata.nodes[nodeId].meta as NodeLayoutMetadata;

            graph.children.push(this.createObjectNode(obj, nodeId, metadata, idRegistry));
        }
    }

    /**
     * Creates a visual node for a single object instance.
     *
     * @param obj The object instance AST node
     * @param nodeId The unique node ID
     * @param metadata The layout metadata for the node
     * @param idRegistry The ID registry for AST node ID generation
     * @returns The created GObjectNode
     */
    createObjectNode(
        obj: PartialObjectInstance,
        nodeId: string,
        metadata: NodeLayoutMetadata,
        idRegistry: ModelIdRegistry
    ): GObjectNode {
        const objectName = obj.name ?? "unnamed";
        const classRef = obj.class;
        const typeName = classRef?.$refText ?? (classRef?.ref as { name?: string } | undefined)?.name ?? "Unknown";
        const resolvedClass = classRef?.ref as ClassType | undefined;
        const classHierarchy =
            resolvedClass != undefined ? resolveClassChain(resolvedClass, this.reflection).map((c) => c.name) : [];

        const node = GObjectNode.builder()
            .id(nodeId)
            .name(objectName)
            .typeName(typeName)
            .classHierarchy(classHierarchy)
            .meta(metadata)
            .build();

        node.children.push(
            ...this.createObjectHeader(nodeId, objectName, typeName),
            ...this.createPropertyAssignments(nodeId, obj, idRegistry)
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
            .id(`${nodeId}__header-compartment`)
            .build();

        const combinedLabel = GObjectNameLabel.builder().id(`${nodeId}__name`).text(`${name} : ${typeName}`).build();

        headerCompartment.children.push(combinedLabel);
        return [headerCompartment];
    }

    /**
     * Creates the property assignments compartment for an object node.
     *
     * @param nodeId The ID of the object node
     * @param obj The object containing property assignments
     * @param idRegistry The ID registry for AST node ID generation
     * @returns Array of GModelElements for the properties compartment
     */
    private createPropertyAssignments(
        nodeId: string,
        obj: PartialObjectInstance,
        idRegistry: ModelIdRegistry
    ): GModelElement[] {
        const children: GModelElement[] = [];
        const properties = obj.properties ?? [];

        if (properties.length === 0) {
            return children;
        }

        const divider = GHorizontalDivider.builder().type(ModelElementType.DIVIDER).id(`${nodeId}__divider`).build();
        children.push(divider);

        const propertiesCompartment = GCompartment.builder()
            .type(ModelElementType.COMPARTMENT)
            .id(`${nodeId}__properties-compartment`)
            .build();

        for (const prop of properties) {
            if (prop == undefined) {
                continue;
            }

            const propId = idRegistry.getId(prop);
            const propLabel = this.createPropertyLabel(propId, prop);
            propertiesCompartment.children.push(propLabel);
        }

        children.push(propertiesCompartment);
        return children;
    }

    /**
     * Creates a property assignment label.
     *
     * @param propId The ID for the property label
     * @param prop The property assignment
     * @returns The created GPropertyLabel
     */
    private createPropertyLabel(propId: string, prop: PartialPropertyAssignment): GPropertyLabel {
        const rawPropName = prop.name?.$refText ?? prop.name?.ref?.name ?? "unknown";
        const propName = this.modelState.languageServices.AstSerializer.serializePrimitive({ value: rawPropName }, ID);
        const valueStr = this.formatPropertyValue(prop);

        return GPropertyLabel.builder().id(propId).text(`${propName} = ${valueStr}`).build();
    }

    /**
     * Formats a property value to a string representation.
     *
     * @param prop The property assignment
     * @returns The formatted value string
     */
    private formatPropertyValue(prop: PartialPropertyAssignment): string {
        const value = prop.value as
            | {
                  $type?: string;
                  stringValue?: string;
                  numberValue?: number;
                  booleanValue?: boolean;
                  value?: { $refText?: string };
                  values?: unknown[];
              }
            | undefined;
        if (value == undefined) {
            return "?";
        }

        if (this.reflection.isInstance(value, SimpleValue)) {
            return this.formatSimpleValue(value);
        } else if (this.reflection.isInstance(value, EnumValue)) {
            return this.formatEnumValue(value);
        } else if (this.reflection.isInstance(value, ListValue)) {
            const items = (value.values ?? []).map((value) => this.formatSingleValue(value));
            return `[${items.join(", ")}]`;
        }

        return "?";
    }

    /**
     * Formats a simple value (string, number, or boolean).
     *
     * @param value The simple value
     * @returns The formatted string
     */
    private formatSimpleValue(value: { stringValue?: string; numberValue?: number; booleanValue?: boolean }): string {
        if (value.stringValue !== undefined) {
            return `"${value.stringValue}"`;
        }
        if (value.numberValue !== undefined) {
            return String(value.numberValue);
        }
        if (value.booleanValue !== undefined) {
            return String(value.booleanValue);
        }
        return "?";
    }

    /**
     * Formats an enum value using EnumName.Entry syntax.
     *
     * @param value The enum value
     * @returns The formatted string
     */
    private formatEnumValue(value: EnumValueType): string {
        const serializer = this.modelState.languageServices.AstSerializer;
        const rawEnumName =
            (value.enumRef?.ref as { name?: string } | undefined)?.name ??
            parseIdentifier(value.enumRef?.$refText ?? "?");
        const rawEntryName =
            (value.value?.ref as { name?: string } | undefined)?.name ?? parseIdentifier(value.value?.$refText ?? "?");
        const serializedEnumName = serializer.serializePrimitive({ value: rawEnumName }, ID);
        const serializedEntryName = serializer.serializePrimitive({ value: rawEntryName }, ID);
        return `${serializedEnumName}.${serializedEntryName}`;
    }

    /**
     * Formats a single value (simple or enum).
     *
     * @param value The single value
     * @returns The formatted string
     */
    private formatSingleValue(value: SingleValueType | undefined): string {
        if (value == undefined) {
            return "?";
        }
        if (this.reflection.isInstance(value, SimpleValue)) {
            return this.formatSimpleValue(value);
        }
        if (this.reflection.isInstance(value, EnumValue)) {
            return this.formatEnumValue(value);
        }
        return "?";
    }

    /**
     * Creates link edges for all links in the model.
     *
     * @param graph The graph to add edges to
     * @param links Array of links to create edges for
     * @param idRegistry The ID registry for AST node ID generation
     */
    private async createLinkEdges(graph: GGraphType, links: PartialLink[], idRegistry: ModelIdRegistry): Promise<void> {
        const validatedMetadata = await this.modelState.getValidatedMetadata();

        for (const link of links) {
            const edgeId = idRegistry.getId(link);
            const metadata = validatedMetadata.edges[edgeId]?.meta as EdgeLayoutMetadata | undefined;
            const edgeMeta: EdgeLayoutMetadata = metadata ?? EdgeLayoutMetadataUtil.create();

            const edge = await this.createLinkEdge(link, edgeId, edgeMeta, idRegistry);
            if (edge != undefined) {
                graph.children.push(edge);
            }
        }
    }

    /**
     * Creates a link edge between two objects.
     *
     * @param link The link AST node
     * @param edgeId The unique edge ID
     * @param metadata The edge layout metadata
     * @param idRegistry The ID registry for AST node ID generation
     * @returns The created GLinkEdge or undefined if invalid
     */
    private async createLinkEdge(
        link: PartialLink,
        edgeId: string,
        metadata: EdgeLayoutMetadata,
        idRegistry: ModelIdRegistry
    ): Promise<GLinkEdge | undefined> {
        const sourceObj = link.source?.object?.ref;
        const targetObj = link.target?.object?.ref;

        if (sourceObj == undefined || targetObj == undefined) {
            return undefined;
        }

        const sourceId = idRegistry.getId(sourceObj);
        const targetId = idRegistry.getId(targetObj);

        const sourceProperty = link.source?.property?.$refText;
        const targetProperty = link.target?.property?.$refText;

        const edgeBuilder = GLinkEdge.builder().id(edgeId).sourceId(sourceId).targetId(targetId).meta(metadata);

        if (sourceProperty) {
            edgeBuilder.sourceProperty(sourceProperty);
        }
        if (targetProperty) {
            edgeBuilder.targetProperty(targetProperty);
        }

        const sourceClassType = sourceObj.class?.ref as ClassType | undefined;
        const targetClassType = targetObj.class?.ref as ClassType | undefined;

        if (sourceClassType != undefined && targetClassType != undefined) {
            const resolver = new LinkAssociationResolver(this.reflection);
            const candidates = resolver.findCandidates(sourceClassType, targetClassType);
            const candidate = resolver.selectCandidate(candidates, { sourceProperty, targetProperty });
            if (candidate != undefined) {
                const srcClass = candidate.sourceEnd.class?.ref as ClassType | undefined;
                const tgtClass = candidate.targetEnd.class?.ref as ClassType | undefined;
                if (srcClass?.name != undefined) {
                    edgeBuilder.sourceClass(srcClass.name);
                }
                if (tgtClass?.name != undefined) {
                    edgeBuilder.targetClass(tgtClass.name);
                }
            }
        }

        const edge = edgeBuilder.build();
        await this.addLinkLabels(edge, edgeId, sourceProperty, targetProperty);

        return edge;
    }

    /**
     * Adds source and target label nodes to a link edge if properties are specified.
     * Creates nodes that wrap the labels to provide proper bounds handling.
     *
     * @param edge The link edge to add label nodes to
     * @param edgeId The edge ID
     * @param sourceProperty Optional source property name
     * @param targetProperty Optional target property name
     */
    private async addLinkLabels(
        edge: GLinkEdge,
        edgeId: string,
        sourceProperty: string | undefined,
        targetProperty: string | undefined
    ): Promise<void> {
        const validatedMetadata = await this.modelState.getValidatedMetadata();

        if (sourceProperty != undefined) {
            const sourceNodes = this.createLinkEndNodes(edgeId, sourceProperty, "target", validatedMetadata);
            edge.children.push(...sourceNodes);
        }

        if (targetProperty != undefined) {
            const targetNodes = this.createLinkEndNodes(edgeId, targetProperty, "source", validatedMetadata);
            edge.children.push(...targetNodes);
        }
    }

    /**
     * Creates nodes for a link endpoint with property name.
     *
     * @param edgeId The edge ID
     * @param property The property name
     * @param end Whether this is at "source" or "target" of the link
     * @param validatedMetadata The validated metadata containing node placement
     * @returns An array of created nodes (property node with label)
     */
    private createLinkEndNodes(
        edgeId: string,
        property: string,
        end: "source" | "target",
        validatedMetadata: GraphMetadata
    ): GModelElement[] {
        const nodes: GModelElement[] = [];

        const nodeId = `${edgeId}__${end}-node`;
        const nodeMeta = validatedMetadata.nodes[nodeId];
        const metadata =
            nodeMeta?.meta != undefined && NodeLayoutMetadataUtil.isValid(nodeMeta.meta)
                ? nodeMeta.meta
                : NodeLayoutMetadataUtil.create(0, 0);

        const endNode = GLinkEndNode.builder().id(nodeId).end(end).meta(metadata).build();

        const endLabel = GLinkEndLabel.builder().id(`${edgeId}__${end}-label`).text(property).readonly(true).build();

        endNode.children.push(endLabel);
        nodes.push(endNode);

        return nodes;
    }
    private async createCsvNodes(graph: GGraphType, model: PartialModel): Promise<void> {
        if (model.csvImport == undefined) return;
        const doc = this.modelState.sourceModel?.$document;
        if (!doc) return;

        let nodeIndex = 0;
        for (const entry of model.csvImport.imports ?? []) {
            const classRef = entry.class?.ref;
            if (classRef == undefined) continue;
            try {
                const uri = resolveRelativePath(doc, entry.file ?? "");
                const csvContent = await this.modelState.languageServices.shared.workspace.FileSystemProvider.readFile(uri);
                const rows = csvContent.split(/\r?\n/).filter((line: string) => line.trim() !== "").map((line: string) => line.split(","));
                if (rows.length < 2) continue;
                rows.slice(1).forEach((row: string[], rowIndex: number) => {
                    const instanceName = `${classRef.name}_${rowIndex}`;
                    const nodeId = `csv-node-${nodeIndex++}`;
                    const headerComp = GCompartment.builder()
                        .id(`${nodeId}__header`)
                        .addCssClass("object-header")
                        .add(GObjectNameLabel.builder().id(`${nodeId}__name`).text(`${instanceName} : ${classRef.name}`).build())
                        .build();
                    const node = GObjectNode.builder()
                        .id(nodeId)
                        .add(headerComp)
                        .build();
                    graph.children.push(node);
                });
            } catch {
                // skip unreadable CSV
            }
        }
    }

}
