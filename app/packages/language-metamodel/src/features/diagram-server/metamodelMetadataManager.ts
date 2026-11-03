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
import type { Attributes } from "@mdeo/language-shared";
import type { AstNode, LangiumDocument } from "langium";
import { MetamodelElementType } from "@mdeo/protocol-metamodel";
import type {
    PartialMetaModel,
    PartialClass,
    PartialAssociation,
    PartialEnum
} from "../../grammar/metamodelPartialTypes.js";
import { Association, Class, Enum } from "../../grammar/metamodelTypes.js";
import { getExportedEntitiesFromMetamodelFile } from "../importHelpers.js";
import {
    ViewScopedModelIdRegistry,
    isExtensionInView,
    resolveViews,
    restrictToView,
    viewKeyOfId,
    type ResolvedView
} from "./metamodelViews.js";
import type { NodeLayoutMetadata } from "@mdeo/protocol-common";

/**
 * Preferred width assumed for a class or enum node whose metadata does not state one.
 */
const DEFAULT_NODE_WIDTH = 250;

/**
 * Horizontal gap left between nodes that are placed next to the existing ones.
 */
const NEW_NODE_GAP = 60;

const { injectable, inject } = sharedImport("inversify");
const { AstUtils } = sharedImport("langium");

/**
 * Manages metadata validation and synchronization for metamodel diagrams.
 * Implements cost calculations based on semantic similarity between model elements.
 */
@injectable()
export class MetamodelMetadataManager extends MetadataManager<PartialMetaModel> {
    @inject(ModelIdProvider)
    protected modelIdProvider!: ModelIdProviderType;

    protected override verifyMetadata(model: NodeMetadata | EdgeMetadata): object | undefined {
        if (model.type === MetamodelElementType.NODE_CLASS || model.type === MetamodelElementType.NODE_ENUM) {
            return NodeLayoutMetadataUtil.verify(model.meta, 250);
        }

        if (
            model.type === MetamodelElementType.NODE_ASSOCIATION_PROPERTY ||
            model.type === MetamodelElementType.NODE_ASSOCIATION_MULTIPLICITY
        ) {
            return NodeLayoutMetadataUtil.verify(model.meta);
        }

        if (
            model.type === MetamodelElementType.EDGE_INHERITANCE ||
            model.type === MetamodelElementType.EDGE_ASSOCIATION
        ) {
            const edgeModel = model as EdgeMetadata;
            return EdgeLayoutMetadataUtil.verify(edgeModel.meta);
        }

        return undefined;
    }

    /**
     * Extracts graph metadata from the metamodel source model.
     *
     * The metadata covers the diagram of the whole metamodel and every view: an element shown
     * in a view has an id of its own there (see {@link ViewScopedModelIdRegistry}), so each
     * view keeps a layout of its own while all of them are stored in one metadata file.
     *
     * @param sourceModel the metamodel source model
     * @returns extracted graph metadata
     */
    protected extractGraphMetadata(sourceModel: PartialMetaModel): GraphMetadata {
        const nodes: Record<string, NodeMetadata> = {};
        const edges: Record<string, EdgeMetadata> = {};

        const idRegistry = new DefaultModelIdRegistry(sourceModel, this.modelIdProvider);
        const { localClasses, localEnums, localAssociations, importedClasses, importedEnums, importedAssociations } =
            this.extractAllElements(sourceModel);

        const all = {
            classes: [...localClasses, ...importedClasses],
            enums: [...localEnums, ...importedEnums],
            associations: [...localAssociations, ...importedAssociations]
        };

        this.extractDiagramMetadata(all, undefined, idRegistry, nodes, edges);
        for (const view of resolveViews(sourceModel)) {
            const viewRegistry = new ViewScopedModelIdRegistry(idRegistry, view.key);
            this.extractDiagramMetadata(restrictToView(all, view), view, viewRegistry, nodes, edges);
        }

        return { nodes, edges };
    }

    /**
     * Extracts the metadata of one diagram: the whole metamodel or one view.
     *
     * @param contents The classes, enums and associations the diagram shows
     * @param view The view, or `undefined` for the diagram of the whole metamodel
     * @param idRegistry The id registry of the diagram
     * @param nodes record to populate with node metadata
     * @param edges record to populate with edge metadata
     */
    private extractDiagramMetadata(
        contents: { classes: PartialClass[]; enums: PartialEnum[]; associations: PartialAssociation[] },
        view: ResolvedView | undefined,
        idRegistry: ModelIdRegistry,
        nodes: Record<string, NodeMetadata>,
        edges: Record<string, EdgeMetadata>
    ): void {
        this.extractClassMetadata(contents.classes, idRegistry, nodes);
        this.extractEnumMetadata(contents.enums, idRegistry, nodes);
        this.extractInheritanceMetadata(contents.classes, idRegistry, edges, view);
        this.extractAssociationMetadata(contents.associations, idRegistry, nodes, edges);
    }

    /**
     * Reconciles the layout of every diagram on its own.
     *
     * The diagram of the whole metamodel and each view are separate partitions of the metadata.
     * Reconciling them together would let the graph edit distance match an element of one view
     * with the same element in another and swap their positions, e.g. after a class is renamed.
     *
     * Classes and enums that appear in a diagram whose other elements are laid out already are
     * placed to the right of them instead of on top of whatever lies at the origin.
     *
     * @param newMetadata The metadata extracted from the source model (layout absent)
     * @param currentMetadata The current graph metadata
     * @param lastValidMetadata The last valid graph metadata for an error-free model
     * @returns Updated metadata or undefined if valid
     */
    protected override async reconcileMetadata(
        newMetadata: GraphMetadata,
        currentMetadata: GraphMetadata,
        lastValidMetadata: GraphMetadata
    ): Promise<GraphMetadata | undefined> {
        const newParts = partitionMetadata(newMetadata);
        const currentParts = partitionMetadata(currentMetadata);
        const lastValidParts = partitionMetadata(lastValidMetadata);

        const result: GraphMetadata = { nodes: {}, edges: {} };
        let changed = false;
        for (const key of new Set([...newParts.keys(), ...currentParts.keys()])) {
            const newPart = newParts.get(key);
            if (newPart == undefined) {
                // The view is gone; its layout goes with it.
                changed = true;
                continue;
            }
            const currentPart = currentParts.get(key) ?? emptyMetadata();
            const lastValidPart = lastValidParts.get(key) ?? emptyMetadata();

            const reconciled = await super.reconcileMetadata(newPart, currentPart, lastValidPart);
            const part = reconciled ?? currentPart;
            const placed = this.placeNewNodes(part, currentPart);
            changed = changed || reconciled != undefined || placed;

            Object.assign(result.nodes, part.nodes);
            Object.assign(result.edges, part.edges);
        }
        return changed ? result : undefined;
    }

    /**
     * Places classes and enums that have just appeared in a diagram next to its other elements.
     *
     * A node counts as new when the diagram did not know its id before and it sits at the
     * origin, which is where elements without a layout end up. Nothing is moved when no class
     * or enum of the diagram has left the origin yet: the editor lays out such a diagram as a
     * whole when it is shown.
     *
     * @param part The reconciled metadata of one diagram, updated in place
     * @param previous The metadata the diagram had before
     * @returns `true` when a node was placed
     */
    private placeNewNodes(part: GraphMetadata, previous: GraphMetadata): boolean {
        const placed: NodeLayoutMetadata[] = [];
        const fresh: string[] = [];
        for (const [id, node] of Object.entries(part.nodes)) {
            if (node.type !== MetamodelElementType.NODE_CLASS && node.type !== MetamodelElementType.NODE_ENUM) {
                continue;
            }
            const meta = node.meta as NodeLayoutMetadata | undefined;
            const atOrigin = meta?.position == undefined || (meta.position.x === 0 && meta.position.y === 0);
            if (!(id in previous.nodes) && atOrigin) {
                fresh.push(id);
            } else if (!atOrigin) {
                placed.push(meta!);
            }
        }
        if (fresh.length === 0 || placed.length === 0) {
            return false;
        }

        let x = Math.max(...placed.map((meta) => meta.position!.x + (meta.prefWidth ?? DEFAULT_NODE_WIDTH)));
        const y = Math.min(...placed.map((meta) => meta.position!.y));
        for (const id of fresh) {
            x += NEW_NODE_GAP;
            const node = part.nodes[id];
            const meta: NodeLayoutMetadata = { ...(node.meta as NodeLayoutMetadata), position: { x, y } };
            part.nodes[id] = { ...node, meta };
            x += meta.prefWidth ?? DEFAULT_NODE_WIDTH;
        }
        return true;
    }

    /**
     * Extracts all elements including local and imported entities.
     *
     * @param metamodel the metamodel source model
     * @returns object containing local and imported entities
     */
    private extractAllElements(metamodel: PartialMetaModel): {
        localClasses: PartialClass[];
        localEnums: PartialEnum[];
        localAssociations: PartialAssociation[];
        importedClasses: PartialClass[];
        importedEnums: PartialEnum[];
        importedAssociations: PartialAssociation[];
    } {
        const {
            classes: localClasses,
            enums: localEnums,
            associations: localAssociations
        } = this.extractClassesAndAssociations(metamodel);
        const { importedClasses, importedEnums, importedAssociations } = this.extractImportedEntities(
            metamodel,
            localClasses,
            localEnums,
            localAssociations
        );

        return { localClasses, localEnums, localAssociations, importedClasses, importedEnums, importedAssociations };
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
        const documents = this.languageServices.shared.workspace.LangiumDocuments;
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
        const localKeys = new Set(localClasses.map((c) => this.getNodeKey(c)));
        const importedClasses: PartialClass[] = [];

        for (const cls of exports.classes.values()) {
            const key = this.getNodeKey(cls as PartialClass);
            if (!localKeys.has(key)) {
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
        const localKeys = new Set(localEnums.map((e) => this.getNodeKey(e)));
        const importedEnums: PartialEnum[] = [];

        for (const enumEntity of exports.enums.values()) {
            const key = this.getNodeKey(enumEntity as PartialEnum);
            if (!localKeys.has(key)) {
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
        const localKeys = new Set(localAssociations.map((a) => this.getAssociationKey(a)));
        const importedAssociations: PartialAssociation[] = [];

        for (const association of exports.associations.values()) {
            const key = this.getAssociationKey(association as PartialAssociation);
            if (!localKeys.has(key)) {
                importedAssociations.push(association as PartialAssociation);
            }
        }
        return importedAssociations;
    }

    /**
     * Creates a unique key for an AST node based on its document path and name.
     *
     * @param node The class or enum node
     * @returns A unique string key for deduplication
     */
    private getNodeKey(node: PartialClass | PartialEnum): string {
        const document = AstUtils.getDocument(node);
        const path = document.uri.path;
        return `${path}#${node.name ?? ""}`;
    }

    /**
     * Creates a unique key for an association based on its document path and involved classes.
     *
     * @param association The association node
     * @returns A unique string key for deduplication
     */
    private getAssociationKey(association: PartialAssociation): string {
        const document = AstUtils.getDocument(association);
        const path = document.uri.path;
        const sourceName = association.source?.class?.ref?.name ?? "";
        const targetName = association.target?.class?.ref?.name ?? "";
        const operator = association.operator ?? "";
        return `${path}#${sourceName}${operator}${targetName}`;
    }

    /**
     * Extracts metadata for all classes.
     *
     * @param classes list of classes in the metamodel
     * @param idRegistry model ID registry
     * @param nodes record to populate with node metadata
     */
    private extractClassMetadata(
        classes: PartialClass[],
        idRegistry: ModelIdRegistry,
        nodes: Record<string, NodeMetadata>
    ): void {
        for (const cls of classes) {
            const nodeId = idRegistry.getId(cls);
            if (nodeId) {
                nodes[nodeId] = {
                    type: MetamodelElementType.NODE_CLASS,
                    attrs: this.createClassAttributes(cls)
                };
            }
        }
    }

    /**
     * Extracts metadata for all enums.
     *
     * @param enums list of enums in the metamodel
     * @param idRegistry model ID registry
     * @param nodes record to populate with node metadata
     */
    private extractEnumMetadata(
        enums: PartialEnum[],
        idRegistry: ModelIdRegistry,
        nodes: Record<string, NodeMetadata>
    ): void {
        for (const enumeration of enums) {
            const nodeId = idRegistry.getId(enumeration);
            if (nodeId) {
                nodes[nodeId] = {
                    type: MetamodelElementType.NODE_ENUM,
                    attrs: this.createEnumAttributes(enumeration)
                };
            }
        }
    }

    /**
     * Extracts metadata for all inheritance relationships.
     *
     * @param classes list of classes in the metamodel
     * @param idRegistry model ID registry
     * @param edges record to populate with edge metadata
     * @param view The view, or `undefined` for the diagram of the whole metamodel
     */
    private extractInheritanceMetadata(
        classes: PartialClass[],
        idRegistry: ModelIdRegistry,
        edges: Record<string, EdgeMetadata>,
        view: ResolvedView | undefined
    ): void {
        for (const cls of classes) {
            const extensions = cls.extensions?.extensions ?? [];
            if (extensions.length === 0) {
                continue;
            }

            for (const extendsDef of extensions) {
                if (extendsDef?.class?.ref == undefined || !isExtensionInView(cls, extendsDef, view)) {
                    continue;
                }

                const superClass = extendsDef.class.ref;
                if (!this.reflection.isInstance(superClass, Class)) {
                    continue;
                }
                const sourceId = idRegistry.getId(cls);
                const targetId = idRegistry.getId(superClass as PartialClass);

                if (sourceId == undefined || targetId == undefined) {
                    continue;
                }

                const edgeId = idRegistry.getId(extendsDef);
                edges[edgeId] = {
                    type: MetamodelElementType.EDGE_INHERITANCE,
                    from: sourceId,
                    to: targetId,
                    attrs: {}
                };
            }
        }
    }

    /**
     * Extracts metadata for all associations.
     *
     * @param associations list of associations in the metamodel
     * @param idRegistry model ID registry
     * @param nodes record to populate with node metadata
     * @param edges record to populate with edge metadata
     */
    private extractAssociationMetadata(
        associations: PartialAssociation[],
        idRegistry: ModelIdRegistry,
        nodes: Record<string, NodeMetadata>,
        edges: Record<string, EdgeMetadata>
    ): void {
        for (const assoc of associations) {
            if (assoc.source == undefined || assoc.target == undefined) {
                continue;
            }

            const startClass = assoc.source.class?.ref;
            const targetClass = assoc.target.class?.ref;

            if (startClass == undefined || targetClass == undefined) {
                continue;
            }

            const startId = this.getClassId(startClass, idRegistry);
            const targetId = this.getClassId(targetClass, idRegistry);

            if (startId == undefined || targetId == undefined) {
                continue;
            }

            const edgeId = idRegistry.getId(assoc);
            edges[edgeId] = {
                type: MetamodelElementType.EDGE_ASSOCIATION,
                from: startId,
                to: targetId,
                attrs: this.createAssociationAttributes(assoc)
            };

            this.extractAssociationLabelMetadata(assoc, idRegistry, nodes);
        }
    }

    /**
     * Extracts metadata for association end labels.
     *
     * @param assoc the association definition
     * @param idRegistry model ID registry
     * @param nodes record to populate with node metadata
     */
    private extractAssociationLabelMetadata(
        assoc: PartialAssociation,
        idRegistry: ModelIdRegistry,
        nodes: Record<string, NodeMetadata>
    ): void {
        if (assoc.source != undefined) {
            const startId = idRegistry.getId(assoc.source);
            if (assoc.source.name != undefined) {
                nodes[`${startId}__property`] = {
                    type: MetamodelElementType.NODE_ASSOCIATION_PROPERTY,
                    attrs: {}
                };
            }
            if (assoc.source.multiplicity != undefined) {
                nodes[`${startId}__multiplicity`] = {
                    type: MetamodelElementType.NODE_ASSOCIATION_MULTIPLICITY,
                    attrs: {}
                };
            }
        }

        if (assoc.target != undefined) {
            const targetId = idRegistry.getId(assoc.target);
            if (assoc.target.name != undefined) {
                nodes[`${targetId}__property`] = {
                    type: MetamodelElementType.NODE_ASSOCIATION_PROPERTY,
                    attrs: {}
                };
            }
            if (assoc.target.multiplicity != undefined) {
                nodes[`${targetId}__multiplicity`] = {
                    type: MetamodelElementType.NODE_ASSOCIATION_MULTIPLICITY,
                    attrs: {}
                };
            }
        }
    }

    /**
     * Extracts classes, associations from the metamodel.
     *
     * @param metamodel the metamodel source model
     * @returns extracted classes, enums, and associations
     */
    private extractClassesAndAssociations(metamodel: PartialMetaModel): {
        classes: PartialClass[];
        enums: PartialEnum[];
        associations: PartialAssociation[];
    } {
        const classes: PartialClass[] = [];
        const enums: PartialEnum[] = [];
        const associations: PartialAssociation[] = [];

        if (!metamodel.elements) {
            return { classes, enums, associations };
        }

        for (const item of metamodel.elements) {
            if (!item) {
                continue;
            }

            if (this.reflection.isInstance(item, Class)) {
                classes.push(item);
            } else if (this.reflection.isInstance(item, Enum)) {
                enums.push(item);
            } else if (this.reflection.isInstance(item, Association)) {
                associations.push(item);
            }
        }

        return { classes, enums, associations };
    }

    /**
     * Gets the ID for a class.
     *
     * @param classNode the class AST node
     * @param idRegistry model ID registry
     * @returns the class ID or undefined
     */
    private getClassId(classNode: AstNode, idRegistry: ModelIdRegistry): string | undefined {
        if (!classNode) {
            return undefined;
        }

        if (this.reflection.isInstance(classNode, Class)) {
            return idRegistry.getId(classNode);
        }

        return undefined;
    }

    /**
     * Creates node attributes for a class.
     * Includes property names for similarity comparison.
     *
     * @param cls the class definition
     * @returns node attributes
     */
    private createClassAttributes(cls: PartialClass): Attributes {
        const properties = cls.properties?.filter((p) => p?.name).map((p) => p.name!) ?? [];

        return {
            name: cls.name ?? "",
            isAbstract: cls.isAbstract ?? false,
            properties
        };
    }

    /**
     * Creates enum attributes for an enumeration.
     * Includes entry names for similarity comparison.
     *
     * @param enumeration the enum definition
     * @returns enum attributes
     */
    private createEnumAttributes(enumeration: PartialEnum): Attributes {
        const entries = enumeration.entries?.filter((e) => e?.name).map((e) => e.name!) ?? [];

        return {
            name: enumeration.name ?? "",
            entries
        };
    }

    /**
     * Creates edge attributes for an association.
     * Includes property names for similarity comparison.
     *
     * @param assoc the association definition
     * @returns edge attributes
     */
    private createAssociationAttributes(assoc: PartialAssociation): Attributes {
        return {
            operator: assoc.operator ?? "",
            startProperty: assoc.source?.name ?? "",
            targetProperty: assoc.target?.name ?? ""
        };
    }
}

/**
 * Returns empty graph metadata.
 *
 * @returns metadata without nodes and edges
 */
function emptyMetadata(): GraphMetadata {
    return { nodes: {}, edges: {} };
}

/**
 * Splits graph metadata into the metadata of each diagram, keyed by view key; the diagram of
 * the whole metamodel has the key `""`.
 *
 * @param metadata The metadata to split
 * @returns The metadata of each diagram that has any
 */
export function partitionMetadata(metadata: GraphMetadata): Map<string, GraphMetadata> {
    const parts = new Map<string, GraphMetadata>();
    const partOf = (id: string): GraphMetadata => {
        const key = viewKeyOfId(id) ?? "";
        let part = parts.get(key);
        if (part == undefined) {
            part = emptyMetadata();
            parts.set(key, part);
        }
        return part;
    };
    for (const [id, node] of Object.entries(metadata.nodes)) {
        partOf(id).nodes[id] = node;
    }
    for (const [id, edge] of Object.entries(metadata.edges)) {
        partOf(id).edges[id] = edge;
    }
    return parts;
}
