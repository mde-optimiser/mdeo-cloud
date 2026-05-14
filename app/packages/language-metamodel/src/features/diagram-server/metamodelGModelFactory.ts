import type { GModelElement, GModelRoot } from "@eclipse-glsp/server";
import {
    sharedImport,
    BaseGModelFactory,
    GCompartment,
    GHorizontalDivider,
    EdgeLayoutMetadataUtil,
    NodeLayoutMetadataUtil,
    type GraphMetadata,
    type ModelIdRegistry
} from "@mdeo/language-shared";
import type { NodeLayoutMetadata } from "@mdeo/protocol-common";
import { ID, type PartialAstNode } from "@mdeo/language-common";
import type { SingleMultiplicityType, RangeMultiplicityType } from "../../grammar/metamodelTypes.js";
import {
    Association,
    Class,
    Enum,
    EnumTypeReference,
    MetamodelAssociationOperators,
    PrimitiveType,
    RangeMultiplicity,
    SingleMultiplicity
} from "../../grammar/metamodelTypes.js";
import { getExportedEntitiesFromMetamodelFile } from "../importHelpers.js";
import type { LangiumDocument } from "langium";
import type {
    PartialMetaModel,
    PartialClass,
    PartialAssociation,
    PartialMultiplicity,
    PartialClassExtension,
    PartialAssociationEnd,
    PartialEnum,
    PartialPropertyTypeValue
} from "../../grammar/metamodelPartialTypes.js";
import { GClassNode } from "./model/classNode.js";
import { GEnumNode } from "./model/enumNode.js";
import { GInheritanceEdge } from "./model/inheritanceEdge.js";
import { GAssociationEdge } from "./model/associationEdge.js";
import { GClassLabel } from "./model/classLabel.js";
import { GEnumLabel } from "./model/enumLabel.js";
import { GEnumEntryLabel } from "./model/enumEntryLabel.js";
import { GPropertyLabel } from "./model/propertyLabel.js";
import { GAssociationPropertyNode } from "./model/associationPropertyNode.js";
import { GAssociationMultiplicityNode } from "./model/associationMultiplicityNode.js";
import { GAssociationPropertyLabel } from "./model/associationPropertyLabel.js";
import { GAssociationMultiplicityLabel } from "./model/associationMultiplicityLabel.js";
import { GEnumTitleCompartment } from "./model/enumTitleCompartment.js";
import { AssociationEndKind, MetamodelElementType } from "@mdeo/protocol-metamodel";

const { injectable } = sharedImport("inversify");
const { GGraph } = sharedImport("@eclipse-glsp/server");
const { AstUtils } = sharedImport("langium");

type GGraphType = ReturnType<typeof GGraph.builder>["proxy"];

/**
 * Extracted elements from a metamodel including local and imported entities.
 */
interface ExtractedElements {
    /**
     * Classes defined locally in this file
     */
    localClasses: PartialClass[];
    /**
     * Enums defined locally in this file
     */
    localEnums: PartialEnum[];
    /**
     * Associations defined locally in this file
     */
    localAssociations: PartialAssociation[];
    /**
     * Classes imported from other files
     */
    importedClasses: PartialClass[];
    /**
     * Enums imported from other files
     */
    importedEnums: PartialEnum[];
    /**
     * Associations imported from other files
     */
    importedAssociations: PartialAssociation[];
}

/**
 * Factory for creating GLSP graph models from metamodel AST.
 * Transforms the metamodel source model into a visual graph representation
 * with nodes for classes, enums, and edges for relationships.
 */
@injectable()
export class MetamodelGModelFactory extends BaseGModelFactory<PartialMetaModel> {
    /**
     * Creates the GLSP model from the metamodel source.
     *
     * @param sourceModel The metamodel source model
     * @param idRegistry The ID registry for AST node ID generation
     * @returns The created GLSP model root
     */
    override async createModelInternal(
        sourceModel: PartialMetaModel,
        idRegistry: ModelIdRegistry
    ): Promise<GModelRoot> {
        const graph = GGraph.builder().id("metamodel-graph").addCssClass("editor-metamodel").build();

        const extracted = this.extractElements(sourceModel);
        await this.createClassNodes(graph, extracted.localClasses, idRegistry, false);
        await this.createClassNodes(graph, extracted.importedClasses, idRegistry, true);
        await this.createEnumNodes(graph, extracted.localEnums, idRegistry, false);
        await this.createEnumNodes(graph, extracted.importedEnums, idRegistry, true);
        const allClasses = [...extracted.localClasses, ...extracted.importedClasses];
        const allAssociations = [...extracted.localAssociations, ...extracted.importedAssociations];
        await this.createInheritanceEdges(graph, allClasses, idRegistry);
        await this.createAssociationEdges(graph, allAssociations, idRegistry);

        return graph;
    }

    /**
     * Extracts and separates classes, enums, and associations from the metamodel.
     * Also collects imported classes and enums from imported files.
     *
     * @param metamodel The metamodel containing elements
     * @returns An object containing separate arrays of local and imported entities
     */
    private extractElements(metamodel: PartialMetaModel): ExtractedElements {
        const localClasses: PartialClass[] = [];
        const localEnums: PartialEnum[] = [];
        const localAssociations: PartialAssociation[] = [];

        this.extractLocalElements(metamodel, localClasses, localEnums, localAssociations);
        const { importedClasses, importedEnums, importedAssociations } = this.extractImportedEntities(
            metamodel,
            localClasses,
            localEnums,
            localAssociations
        );

        return { localClasses, localEnums, localAssociations, importedClasses, importedEnums, importedAssociations };
    }

    /**
     * Extracts local classes, enums, and associations from the metamodel.
     *
     * @param metamodel The metamodel containing elements
     * @param classes Array to populate with local classes
     * @param enums Array to populate with local enums
     * @param associations Array to populate with local associations
     */
    private extractLocalElements(
        metamodel: PartialMetaModel,
        classes: PartialClass[],
        enums: PartialEnum[],
        associations: PartialAssociation[]
    ): void {
        const items = metamodel.elements ?? [];
        for (const item of items) {
            if (this.reflection.isInstance(item, Class)) {
                classes.push(item as PartialClass);
            } else if (this.reflection.isInstance(item, Enum)) {
                enums.push(item as PartialEnum);
            } else if (this.reflection.isInstance(item, Association)) {
                associations.push(item as PartialAssociation);
            }
        }
    }

    /**
     * Extracts imported classes, enums, and associations from imported files.
     * Deduplicates entities that are already defined locally.
     *
     * @param metamodel The metamodel containing the imports
     * @param localClasses Local classes to exclude from imported entities
     * @param localEnums Local enums to exclude from imported entities
     * @param localAssociations Local associations to exclude from imported entities
     * @returns Object containing imported classes, enums, and associations
     */
    private extractImportedEntities(
        metamodel: PartialMetaModel,
        localClasses: PartialClass[],
        localEnums: PartialEnum[],
        localAssociations: PartialAssociation[]
    ): { importedClasses: PartialClass[]; importedEnums: PartialEnum[]; importedAssociations: PartialAssociation[] } {
        const document = this.getSourceDocument(metamodel);
        if (document == undefined) {
            return { importedClasses: [], importedEnums: [], importedAssociations: [] };
        }

        const exports = this.getExportsFromDocument(document);
        const importedClasses = this.filterImportedClasses(exports, localClasses);
        const importedEnums = this.filterImportedEnums(exports, localEnums);
        const importedAssociations = this.filterImportedAssociations(exports, localAssociations);

        return { importedClasses, importedEnums, importedAssociations };
    }

    /**
     * Gets the Langium document for the source metamodel.
     *
     * @param metamodel The metamodel AST node
     * @returns The Langium document or undefined
     */
    private getSourceDocument(metamodel: PartialMetaModel): LangiumDocument | undefined {
        return AstUtils.getDocument(metamodel);
    }

    /**
     * Gets exported entities from a document using the import helper.
     *
     * @param document The source document
     * @returns The collected metamodel exports
     */
    private getExportsFromDocument(document: LangiumDocument) {
        const documents = this.modelState.languageServices.shared.workspace.LangiumDocuments;
        return getExportedEntitiesFromMetamodelFile(document, documents);
    }

    /**
     * Filters imported classes to exclude locally defined ones.
     *
     * @param exports The metamodel exports
     * @param localClasses Classes defined locally
     * @returns Array of imported classes not in local scope
     */
    private filterImportedClasses(
        exports: ReturnType<typeof getExportedEntitiesFromMetamodelFile>,
        localClasses: PartialClass[]
    ): PartialClass[] {
        const local = new Set(localClasses);
        const importedClasses: PartialClass[] = [];

        for (const cls of exports.classes.values()) {
            if (!local.has(cls as PartialClass)) {
                importedClasses.push(cls as PartialClass);
            }
        }
        return importedClasses;
    }

    /**
     * Filters imported enums to exclude locally defined ones.
     *
     * @param exports The metamodel exports
     * @param localEnums Enums defined locally
     * @returns Array of imported enums not in local scope
     */
    private filterImportedEnums(
        exports: ReturnType<typeof getExportedEntitiesFromMetamodelFile>,
        localEnums: PartialEnum[]
    ): PartialEnum[] {
        const local = new Set(localEnums);
        const importedEnums: PartialEnum[] = [];

        for (const enumEntity of exports.enums.values()) {
            if (!local.has(enumEntity as PartialEnum)) {
                importedEnums.push(enumEntity as PartialEnum);
            }
        }
        return importedEnums;
    }

    /**
     * Filters imported associations to exclude locally defined ones.
     *
     * @param exports The metamodel exports
     * @param localAssociations Associations defined locally
     * @returns Array of imported associations not in local scope
     */
    private filterImportedAssociations(
        exports: ReturnType<typeof getExportedEntitiesFromMetamodelFile>,
        localAssociations: PartialAssociation[]
    ): PartialAssociation[] {
        const local = new Set(localAssociations);
        const importedAssociations: PartialAssociation[] = [];

        for (const association of exports.associations.values()) {
            if (!local.has(association as PartialAssociation)) {
                importedAssociations.push(association as PartialAssociation);
            }
        }
        return importedAssociations;
    }

    /**
     * Creates visual nodes for all classes with their properties.
     * Nodes are positioned based on metadata or default grid layout.
     *
     * @param graph The graph to add nodes to
     * @param classes Array of classes to create nodes for
     * @param idRegistry The ID registry for AST node ID generation
     * @param readonly Whether the nodes should be readonly (for imported entities)
     */
    private async createClassNodes(
        graph: GGraphType,
        classes: PartialClass[],
        idRegistry: ModelIdRegistry,
        readonly: boolean
    ): Promise<void> {
        const validatedMetadata = await this.modelState.getValidatedMetadata();

        for (const cls of classes) {
            if (cls == undefined) {
                continue;
            }

            const nodeId = idRegistry.getId(cls);
            const metadata = validatedMetadata.nodes[nodeId]?.meta as NodeLayoutMetadata;
            if (metadata == undefined) {
                continue;
            }

            graph.children.push(this.createClassNode(cls, nodeId, metadata, idRegistry, readonly));
        }
    }

    /**
     * Creates a visual node for a single class with its properties.
     *
     * @param cls the class AST node
     * @param nodeId the unique node ID
     * @param metadata the layout metadata for the node
     * @param idRegistry The ID registry for AST node ID generation
     * @param readonly Whether the node should be readonly (for imported entities)
     * @returns The created GClassNode
     */
    createClassNode(
        cls: PartialClass,
        nodeId: string,
        metadata: NodeLayoutMetadata,
        idRegistry: ModelIdRegistry,
        readonly: boolean = false
    ): GClassNode {
        const displayName = cls.name ?? "Unnamed";

        const nodeBuilder = GClassNode.builder()
            .id(nodeId)
            .name(displayName)
            .isAbstract(cls.isAbstract ?? false)
            .meta(metadata);

        if (readonly) {
            nodeBuilder.addCssClass("imported");
        }

        const node = nodeBuilder.build();

        node.children.push(
            ...this.createClassTitle(nodeId, displayName, readonly),
            ...this.createClassProperties(nodeId, cls, idRegistry, readonly)
        );
        return node;
    }

    /**
     * Creates a visual node for a single enum with its entries.
     *
     * @param enumDef the enum AST node
     * @param nodeId the unique node ID
     * @param metadata the layout metadata for the node
     * @param idRegistry The ID registry for AST node ID generation
     * @param readonly Whether the node should be readonly (for imported entities)
     * @returns The created GEnumNode
     */
    createEnumNode(
        enumDef: PartialEnum,
        nodeId: string,
        metadata: NodeLayoutMetadata,
        idRegistry: ModelIdRegistry,
        readonly: boolean = false
    ): GEnumNode {
        const displayName = enumDef.name ?? "Unnamed";

        const nodeBuilder = GEnumNode.builder().id(nodeId).name(displayName).meta(metadata);

        if (readonly) {
            nodeBuilder.addCssClass("imported");
        }

        const node = nodeBuilder.build();

        node.children.push(
            ...this.createEnumTitle(nodeId, displayName, readonly),
            ...this.createEnumEntries(nodeId, enumDef, idRegistry, readonly)
        );

        return node;
    }

    /**
     * Creates visual nodes for all enums with their entries.
     *
     * @param graph The graph to add nodes to
     * @param enums Array of enums to create nodes for
     * @param idRegistry The ID registry for AST node ID generation
     * @param readonly Whether the nodes should be readonly (for imported entities)
     */
    private async createEnumNodes(
        graph: GGraphType,
        enums: PartialEnum[],
        idRegistry: ModelIdRegistry,
        readonly: boolean
    ): Promise<void> {
        const validatedMetadata = await this.modelState.getValidatedMetadata();

        for (const enumDef of enums) {
            if (enumDef == undefined) {
                continue;
            }

            const nodeId = idRegistry.getId(enumDef);
            const metadata = validatedMetadata.nodes[nodeId]?.meta as NodeLayoutMetadata;
            if (metadata == undefined) {
                continue;
            }

            graph.children.push(this.createEnumNode(enumDef, nodeId, metadata, idRegistry, readonly));
        }
    }

    /**
     * Creates the title compartment containing the enum name label.
     *
     * @param nodeId The ID of the enum node
     * @param name The name of the enum
     * @param readonly Whether the label should be readonly
     * @returns An array with the title compartment GModelElement
     */
    private createEnumTitle(nodeId: string, name: string | undefined, readonly: boolean): GModelElement[] {
        const titleCompartment = GEnumTitleCompartment.builder().id(`${nodeId}__title-compartment`).build();

        const nameLabel = GEnumLabel.builder()
            .id(`${nodeId}__name`)
            .text(name ?? "Unnamed")
            .readonly(readonly)
            .build();

        titleCompartment.children.push(nameLabel);
        return [titleCompartment];
    }

    /**
     * Creates the entries compartment (and divider) for an enum node.
     *
     * @param nodeId The ID of the enum node
     * @param enumDef The enum containing the entries
     * @param idRegistry The ID registry for AST node ID generation
     * @param readonly Whether the entry labels should be readonly
     * @returns An array of GModelElements representing the entries compartment
     */
    private createEnumEntries(
        nodeId: string,
        enumDef: PartialEnum,
        idRegistry: ModelIdRegistry,
        readonly: boolean
    ): GModelElement[] {
        const children: GModelElement[] = [];
        const entries = enumDef.entries ?? [];
        if (entries.length === 0) {
            return children;
        }

        const divider = GHorizontalDivider.builder()
            .type(MetamodelElementType.DIVIDER)
            .id(`${nodeId}__divider`)
            .build();
        children.push(divider);

        const entriesCompartment = GCompartment.builder()
            .type(MetamodelElementType.COMPARTMENT)
            .id(`${nodeId}__entries-compartment`)
            .build();

        for (const entry of entries) {
            if (entry == undefined) {
                continue;
            }

            const entryId = readonly ? idRegistry.getIdOrUnresolved(entry) : idRegistry.getId(entry);
            const entryName = entry.name ?? "unnamed";

            const entryLabel = GEnumEntryLabel.builder().id(entryId).text(entryName).readonly(readonly).build();
            entriesCompartment.children.push(entryLabel);
        }

        children.push(entriesCompartment);
        return children;
    }

    /**
     * Creates the title compartment containing the class name label.
     *
     * @param nodeId The ID of the class node
     * @param name The name of the class
     * @param readonly Whether the label should be readonly
     * @returns An array with the title compartment GModelElement
     */
    private createClassTitle(nodeId: string, name: string | undefined, readonly: boolean): GModelElement[] {
        const titleCompartment = GCompartment.builder()
            .type(MetamodelElementType.COMPARTMENT)
            .id(`${nodeId}__title-compartment`)
            .build();

        const nameLabel = GClassLabel.builder()
            .id(`${nodeId}__name`)
            .text(name ?? "Unnamed")
            .readonly(readonly)
            .build();

        titleCompartment.children.push(nameLabel);
        return [titleCompartment];
    }

    /**
     * Creates the properties compartment (and divider) for a class node.
     *
     * @param nodeId The ID of the class node
     * @param cls The class containing the properties
     * @param idRegistry The ID registry for AST node ID generation
     * @param readonly Whether the property labels should be readonly
     * @returns An array of GModelElements representing the properties compartment
     */
    private createClassProperties(
        nodeId: string,
        cls: PartialClass,
        idRegistry: ModelIdRegistry,
        readonly: boolean
    ): GModelElement[] {
        const children: GModelElement[] = [];
        const properties = cls.properties ?? [];
        if (properties.length === 0) {
            return children;
        }

        const divider = GHorizontalDivider.builder()
            .type(MetamodelElementType.DIVIDER)
            .id(`${nodeId}__divider`)
            .build();
        children.push(divider);

        const propertiesCompartment = GCompartment.builder()
            .type(MetamodelElementType.COMPARTMENT)
            .id(`${nodeId}__properties-compartment`)
            .build();

        for (const prop of properties) {
            if (prop == undefined) {
                continue;
            }

            const propId = readonly ? idRegistry.getIdOrUnresolved(prop) : idRegistry.getId(prop);

            const multiplicityStr =
                prop.multiplicity != undefined ? `[${this.formatMultiplicity(prop.multiplicity)}]` : "";
            const propName = this.modelState.languageServices.AstSerializer.serializePrimitive(
                { value: prop.name ?? "unnamed" },
                ID
            );

            const typeName = this.getPropertyTypeName(prop.type);
            const propText = `${propName}: ${typeName}${multiplicityStr}`;

            const propLabel = GPropertyLabel.builder().id(propId).text(propText).readonly(readonly).build();
            propertiesCompartment.children.push(propLabel);
        }

        children.push(propertiesCompartment);
        return children;
    }

    /**
     * Gets the display name for a property type.
     *
     * @param propertyType The property type value (PrimitiveType or EnumTypeReference)
     * @returns The type name string
     */
    private getPropertyTypeName(propertyType: PartialPropertyTypeValue | undefined): string {
        if (propertyType == undefined || typeof propertyType !== "object") {
            return "unknown";
        }
        if (this.reflection.isInstance(propertyType, PrimitiveType)) {
            return propertyType.name ?? "unknown";
        }
        if (this.reflection.isInstance(propertyType, EnumTypeReference)) {
            const enumType = propertyType.enum?.ref;
            if (this.reflection.isInstance(enumType, Enum)) {
                return enumType.name ?? "unknown";
            }
        }
        return "unknown";
    }

    /**
     * Creates inheritance edges for all extends relationships.
     *
     * @param graph The graph to add edges to
     * @param classes Array of classes with extends relationships
     * @param idRegistry The ID registry for AST node ID generation
     */
    private async createInheritanceEdges(
        graph: GGraphType,
        classes: PartialClass[],
        idRegistry: ModelIdRegistry
    ): Promise<void> {
        const validatedMetadata = await this.modelState.getValidatedMetadata();

        for (const cls of classes) {
            if (cls == undefined) {
                continue;
            }

            const extendsRefs = cls.extensions?.extensions ?? [];
            for (const extendsDef of extendsRefs) {
                if (extendsDef == undefined) {
                    continue;
                }

                const superClassRef = extendsDef.class?.ref;
                if (superClassRef == undefined) {
                    continue;
                }

                const edge = this.createInheritanceEdge(extendsDef, cls, superClassRef, validatedMetadata, idRegistry);
                graph.children.push(edge);
            }
        }
    }

    /**
     * Creates a single inheritance edge with routing points from metadata.
     *
     * @param inheritance The inheritance definition
     * @param sourceClass The source class AST node
     * @param targetClass The target class AST node
     * @param validatedMetadata The validated metadata containing edge information
     * @param idRegistry The ID registry for AST node ID generation
     * @returns The created edge
     */
    private createInheritanceEdge(
        inheritance: PartialClassExtension,
        sourceClass: PartialClass,
        targetClass: PartialClass,
        validatedMetadata: GraphMetadata,
        idRegistry: ModelIdRegistry
    ): GInheritanceEdge {
        const edgeId = idRegistry.getId(inheritance);
        const sourceId = idRegistry.getId(sourceClass);
        const targetId = idRegistry.getId(targetClass);
        const metadata = validatedMetadata.edges[edgeId];
        const edge = GInheritanceEdge.builder().id(edgeId).sourceId(sourceId).targetId(targetId).build();

        this.applyRoutingPoints(edge, metadata);
        return edge;
    }

    /**
     * Creates association edges for all associations in the metamodel.
     *
     * @param graph The graph to add edges to
     * @param associations Array of associations to create edges for
     * @param idRegistry The ID registry for AST node ID generation
     */
    private async createAssociationEdges(
        graph: GGraphType,
        associations: PartialAssociation[],
        idRegistry: ModelIdRegistry
    ): Promise<void> {
        const validatedMetadata = await this.modelState.getValidatedMetadata();

        for (const assoc of associations) {
            if (assoc == undefined || assoc.source == undefined || assoc.target == undefined) {
                continue;
            }

            const startClassRef = assoc.source.class?.ref;
            const targetClassRef = assoc.target.class?.ref;

            if (startClassRef == undefined || targetClassRef == undefined) {
                continue;
            }

            const edge = this.createAssociationEdge(
                assoc,
                startClassRef,
                targetClassRef,
                validatedMetadata,
                idRegistry
            );
            graph.children.push(edge);
        }
    }

    /**
     * Creates a single association edge with all properties.
     *
     * @param assoc The association definition
     * @param sourceClass The source class AST node
     * @param targetClass The target class AST node
     * @param validatedMetadata The validated metadata containing edge information
     * @param idRegistry The ID registry for AST node ID generation
     * @returns The created edge
     */
    private createAssociationEdge(
        assoc: PartialAssociation,
        sourceClass: PartialClass,
        targetClass: PartialClass,
        validatedMetadata: GraphMetadata,
        idRegistry: ModelIdRegistry
    ): GAssociationEdge {
        const edgeId = idRegistry.getId(assoc);
        const sourceId = idRegistry.getId(sourceClass);
        const targetId = idRegistry.getId(targetClass);
        const metadata = validatedMetadata.edges[edgeId];
        const { sourceKind, targetKind } = this.getAssociationEndKinds(assoc.operator);
        const edge = GAssociationEdge.builder()
            .id(edgeId)
            .sourceId(sourceId)
            .targetId(targetId)
            .operator(assoc.operator ?? "--")
            .sourceKind(sourceKind)
            .targetKind(targetKind)
            .build();

        this.applyRoutingPoints(edge, metadata);

        if (assoc.source != undefined) {
            // Source end nodes are displayed near the target (end="target")
            // They are navigable when the targetKind indicates an arrow or composition
            const sourceEndNavigable = targetKind !== AssociationEndKind.NONE;
            const startNodes = this.createAssociationEndNodes(
                idRegistry,
                assoc.source,
                "target",
                validatedMetadata,
                sourceEndNavigable,
                targetKind === AssociationEndKind.COMPOSITION
            );
            edge.children.push(...startNodes);
        }

        if (assoc.target != undefined) {
            // Target end nodes are displayed near the source (end="source")
            // They are navigable when the sourceKind indicates an arrow or composition
            const targetEndNavigable = sourceKind !== AssociationEndKind.NONE;
            const targetNodes = this.createAssociationEndNodes(
                idRegistry,
                assoc.target,
                "source",
                validatedMetadata,
                targetEndNavigable,
                sourceKind === AssociationEndKind.COMPOSITION
            );
            edge.children.push(...targetNodes);
        }

        return edge;
    }

    /**
     * Creates nodes for an association endpoint with property name and/or multiplicity.
     *
     * The multiplicity node is always created when the end is navigable, using "0..1"
     * as the default for composition ends and "0..*" for all other ends when no explicit
     * multiplicity is defined in the AST.
     * If the end has no property name, the multiplicity label is shown as read-only
     * (since the grammar no longer allows multiplicity without a property name).
     *
     * @param idRegistry The ID registry for AST node ID generation
     * @param associationEnd The association end definition
     * @param target Whether this is at "source" or "target" of the association
     * @param validatedMetadata The validated metadata containing node placement
     * @param isNavigable Whether this end is navigable (determines if multiplicity is shown)
     * @param isComposition Whether this end carries the composition diamond
     * @returns An array of created nodes (property and/or multiplicity)
     */
    private createAssociationEndNodes(
        idRegistry: ModelIdRegistry,
        associationEnd: PartialAssociationEnd,
        target: "source" | "target",
        validatedMetadata: GraphMetadata,
        isNavigable: boolean,
        isComposition: boolean
    ): GModelElement[] {
        const nodes: GModelElement[] = [];
        const property = associationEnd.name;
        const multiplicity = associationEnd.multiplicity;
        const associationEndId = idRegistry.getId(associationEnd);

        if (property != undefined) {
            const propertyMeta = validatedMetadata.nodes[associationEndId];
            const metadata =
                propertyMeta?.meta != undefined && NodeLayoutMetadataUtil.isValid(propertyMeta.meta)
                    ? propertyMeta.meta
                    : NodeLayoutMetadataUtil.create(0, 0);

            const propertyNode = GAssociationPropertyNode.builder()
                .id(associationEndId)
                .end(target)
                .meta(metadata)
                .build();

            const propertyLabel = GAssociationPropertyLabel.builder()
                .id(`${associationEndId}__property-label`)
                .text(property)
                .build();

            propertyNode.children.push(propertyLabel);
            nodes.push(propertyNode);
        }

        if (isNavigable) {
            const multiplicityNodeId = `${associationEndId}__multiplicity`;
            const multiplicityMeta = validatedMetadata.nodes[multiplicityNodeId];
            const metadata =
                multiplicityMeta?.meta != undefined && NodeLayoutMetadataUtil.isValid(multiplicityMeta.meta)
                    ? multiplicityMeta.meta
                    : NodeLayoutMetadataUtil.create(0, 0);

            const multiplicityText =
                multiplicity != undefined ? this.formatMultiplicity(multiplicity) : isComposition ? "0..1" : "0..*";
            // Only allow editing the multiplicity if the end has a property name.
            // Without a property name, the grammar does not permit explicit multiplicity.
            const multiplicityReadonly = property == undefined;

            const multiplicityLabel = GAssociationMultiplicityLabel.builder()
                .id(`${associationEndId}__multiplicity-label`)
                .text(multiplicityText)
                .readonly(multiplicityReadonly)
                .build();

            const multiplicityNode = GAssociationMultiplicityNode.builder()
                .id(multiplicityNodeId)
                .end(target)
                .meta(metadata)
                .build();

            multiplicityNode.children.push(multiplicityLabel);
            nodes.push(multiplicityNode);
        }

        return nodes;
    }

    /**
     * Applies routing points to an edge from metadata if available.
     *
     * @param edge The edge to apply routing points to
     * @param metadata Metadata containing routing points
     */
    private applyRoutingPoints(
        edge: GInheritanceEdge | GAssociationEdge,
        metadata: { meta?: object } | undefined
    ): void {
        if (metadata != undefined && metadata.meta != undefined && EdgeLayoutMetadataUtil.isValid(metadata.meta)) {
            edge.meta = metadata.meta;
        }
    }

    /**
     * Formats multiplicity for display in labels.
     * Handles both single multiplicities (*, +, ?, or numeric) and range multiplicities.
     *
     * @param multiplicity The multiplicity to format
     * @returns Formatted string like "*", "1", "0..1"
     */
    private formatMultiplicity(multiplicity: PartialMultiplicity): string {
        if (this.reflection.isInstance(multiplicity, SingleMultiplicity)) {
            const single = multiplicity as PartialAstNode<SingleMultiplicityType>;
            if (single.value != undefined) {
                return single.value;
            }
            if (single.numericValue !== undefined && single.numericValue !== null) {
                return single.numericValue.toString();
            }
        } else if (this.reflection.isInstance(multiplicity, RangeMultiplicity)) {
            const range = multiplicity as PartialAstNode<RangeMultiplicityType>;
            const lower = range.lower ?? 0;
            const upper = range.upper ?? range.upperNumeric ?? "*";
            return `${lower}..${upper}`;
        }

        return "";
    }

    /**
     * Determines the source and target end kinds based on the association operator.
     * @param operator The association operator string
     * @returns Object with sourceKind and targetKind
     */
    private getAssociationEndKinds(operator: string | undefined): {
        sourceKind: AssociationEndKind;
        targetKind: AssociationEndKind;
    } {
        let sourceKind = AssociationEndKind.NONE;
        let targetKind = AssociationEndKind.NONE;

        switch (operator) {
            case MetamodelAssociationOperators.NAVIGABLE_TO_TARGET:
                targetKind = AssociationEndKind.ARROW;
                break;
            case MetamodelAssociationOperators.NAVIGABLE_TO_SOURCE:
                sourceKind = AssociationEndKind.ARROW;
                break;
            case MetamodelAssociationOperators.BIDIRECTIONAL:
                sourceKind = AssociationEndKind.ARROW;
                targetKind = AssociationEndKind.ARROW;
                break;
            case MetamodelAssociationOperators.COMPOSITION_SOURCE_NAVIGABLE_TARGET:
                sourceKind = AssociationEndKind.COMPOSITION;
                targetKind = AssociationEndKind.ARROW;
                break;
            case MetamodelAssociationOperators.COMPOSITION_SOURCE:
                sourceKind = AssociationEndKind.COMPOSITION;
                break;
            case MetamodelAssociationOperators.COMPOSITION_TARGET_NAVIGABLE_SOURCE:
                sourceKind = AssociationEndKind.ARROW;
                targetKind = AssociationEndKind.COMPOSITION;
                break;
            case MetamodelAssociationOperators.COMPOSITION_TARGET:
                targetKind = AssociationEndKind.COMPOSITION;
                break;
        }

        return { sourceKind, targetKind };
    }
}
