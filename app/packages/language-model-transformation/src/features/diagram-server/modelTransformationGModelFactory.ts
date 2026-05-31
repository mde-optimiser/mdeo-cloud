import type { GModelElement, GModelRoot } from "@eclipse-glsp/server";
import {
    sharedImport,
    BaseGModelFactory,
    DefaultModelIdRegistry,
    GCompartment,
    GHorizontalDivider,
    NodeLayoutMetadataUtil,
    EdgeLayoutMetadataUtil
} from "@mdeo/language-shared";
import type { ModelIdRegistry, GraphMetadata } from "@mdeo/language-shared";
import type { NodeLayoutMetadata } from "@mdeo/protocol-common";
import {
    type ModelTransformationType,
    type PatternObjectInstanceType,
    type PatternLinkType,
    type PatternPropertyAssignmentType,
    type PatternObjectInstanceReferenceType,
    type PatternObjectInstanceDeleteType,
    PatternObjectInstance,
    PatternLink,
    WhereClause,
    PatternVariable,
    PatternObjectInstanceReference,
    PatternObjectInstanceDelete
} from "../../grammar/modelTransformationTypes.js";
import {
    ModelTransformationControlFlowConverter,
    type ControlFlowNode,
    type ControlFlowMatchNode,
    type ControlFlowSplitNode,
    type ControlFlowEndNode
} from "./modelTransformationControlFlowConverter.js";
import { resolveClassChain, type ClassType } from "@mdeo/language-metamodel";
import { LinkAssociationResolver } from "@mdeo/language-model";
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
import { GPatternModifierTitleCompartment } from "./model/patternModifierTitleCompartment.js";
import { GMatchNodeCompartments } from "./model/matchNodeCompartments.js";
import { GPatternPropertyLabel } from "./model/patternPropertyLabel.js";
import { GPatternLinkEdge } from "./model/patternLinkEdge.js";
import { GPatternLinkEndNode } from "./model/patternLinkEndNode.js";
import { GPatternLinkEndLabel } from "./model/patternLinkEndLabel.js";
import { GPatternLinkModifierLabel } from "./model/patternLinkModifierLabel.js";
import { GPatternLinkModifierNode } from "./model/patternLinkModifierNode.js";
import { GPatternModifierLabel } from "./model/patternModifierLabel.js";
import { GWhereClauseLabel } from "./model/whereClauseLabel.js";
import { GVariableLabel } from "./model/variableLabel.js";
import { EndNodeKind, ModelTransformationElementType, PatternModifierKind } from "@mdeo/protocol-model-transformation";
import { ID } from "@mdeo/language-common";
import { ModelTransformationIdGenerator } from "./modelTransformationIdGenerator.js";
import { adaptGeneratedModelTransformationText } from "./generated/generatedModelTransformationAstAdapter.js";

const { injectable } = sharedImport("inversify");
const { GGraph } = sharedImport("@eclipse-glsp/server");

type GGraphType = ReturnType<typeof GGraph.builder>["proxy"];

/**
 * Factory for creating graph models from model transformation source models.
 * Creates a control flow graph with nested pattern elements.
 */
@injectable()
export class ModelTransformationGModelFactory extends BaseGModelFactory<ModelTransformationType> {
    /**
     * Tracks instances that have been referenced or deleted in the current match.
     * Maps instance name to the node ID assigned to the ref/delete visual node.
     * Used to resolve PatternLink endpoints to the correct node in the current match.
     */
    private referencedInstancesInCurrentMatch = new Map<string, string>();

    /**
     * Creates the graph model from the transformation source model.
     *
     * @param sourceModel The transformation source model
     * @param idRegistry The model ID registry
     * @returns The created graph model root
     */
    override async createModelInternal(
        sourceModel: ModelTransformationType,
        idRegistry: ModelIdRegistry
    ): Promise<GModelRoot> {
        const parsedDocument = sourceModel.$document;
        const parserHasErrors =
            (parsedDocument?.parseResult.parserErrors.length ?? 0) > 0 ||
            (parsedDocument?.parseResult.lexerErrors.length ?? 0) > 0;

        const fallbackModel =
            parserHasErrors && parsedDocument != undefined
                ? adaptGeneratedModelTransformationText(parsedDocument.textDocument.getText())
                : undefined;

        const effectiveSourceModel = fallbackModel ?? sourceModel;
        const effectiveIdRegistry =
            fallbackModel != undefined
                ? new DefaultModelIdRegistry(effectiveSourceModel, this.modelIdProvider)
                : idRegistry;

        const graph = GGraph.builder().id("transformation-graph").addCssClass("editor-model-transformation").build();

        const converter = new ModelTransformationControlFlowConverter(
            effectiveSourceModel,
            effectiveIdRegistry,
            this.reflection
        );
        const cfg = converter.convert();

        for (const node of cfg.nodes) {
            await this.createCFGNode(graph, node, effectiveIdRegistry);
        }
        for (const edge of cfg.edges) {
            await this.createControlFlowEdge(graph, edge.sourceId, edge.targetId, edge.label, edge.labelElementId);
        }

        return graph;
    }

    /**
     * Dispatches a single control-flow graph node to the appropriate GModel creator.
     *
     * @param graph The graph to add elements to.
     * @param node The control-flow node to render.
     * @param idRegistry The model ID registry, required for match-node pattern content.
     */
    private async createCFGNode(graph: GGraphType, node: ControlFlowNode, idRegistry: ModelIdRegistry): Promise<void> {
        const validatedMetadata = await this.modelState.getValidatedMetadata();
        switch (node.kind) {
            case "start": {
                const metadata = this.getNodeMetadata(validatedMetadata, node.id);
                graph.children.push(GStartNode.builder().id(node.id).meta(metadata).build());
                break;
            }
            case "end": {
                const cfgEndNode = node as ControlFlowEndNode;
                const metadata = this.getNodeMetadata(validatedMetadata, cfgEndNode.id);
                const kind = cfgEndNode.endKind === "kill" ? EndNodeKind.KILL : EndNodeKind.STOP;
                graph.children.push(GEndNode.builder().id(cfgEndNode.id).kind(kind).meta(metadata).build());
                break;
            }
            case "match": {
                const cfgMatchNode = node as ControlFlowMatchNode;
                await this.createMatchNode(graph, cfgMatchNode, idRegistry);
                break;
            }
            case "split": {
                const cfgSplitNode = node as ControlFlowSplitNode;
                await this.createSplitNode(graph, cfgSplitNode.id, cfgSplitNode.conditionText);
                break;
            }
            case "merge": {
                await this.createMergeNode(graph, node.id);
                break;
            }
        }
    }

    /**
     * Creates a match node with its pattern elements.
     *
     * @param graph The graph to add the node to
     * @param cfgMatchNode The control flow match node to render
     * @param idRegistry The model ID registry
     */
    private async createMatchNode(
        graph: GGraphType,
        cfgMatchNode: ControlFlowMatchNode,
        idRegistry: ModelIdRegistry
    ): Promise<void> {
        const validatedMetadata = await this.modelState.getValidatedMetadata();
        const metadata = this.getNodeMetadata(validatedMetadata, cfgMatchNode.id);

        const node = GMatchNode.builder().id(cfgMatchNode.id).multiple(cfgMatchNode.multiple).meta(metadata).build();

        this.referencedInstancesInCurrentMatch = new Map<string, string>();

        const localInstances = new Map<string, PatternObjectInstanceType>();
        const referencedInstanceNodes = new Map<string, PatternObjectInstanceReferenceType | null>();
        const deletedInstances = new Set<string>();
        const deletedInstanceNodes = new Map<string, PatternObjectInstanceDeleteType>();

        if (cfgMatchNode.pattern?.elements != undefined) {
            for (const element of cfgMatchNode.pattern.elements) {
                if (this.reflection.isInstance(element, PatternObjectInstance)) {
                    const instance = element as PatternObjectInstanceType;
                    if (instance.name) {
                        localInstances.set(instance.name, instance);
                    }
                }
                if (this.reflection.isInstance(element, PatternObjectInstanceReference)) {
                    const ref = element as PatternObjectInstanceReferenceType;
                    if (ref.instance?.ref != undefined) {
                        const instanceRef = ref.instance.ref;
                        if (instanceRef.name) {
                            referencedInstanceNodes.set(instanceRef.name, ref);
                        }
                    }
                }
                if (this.reflection.isInstance(element, PatternObjectInstanceDelete)) {
                    const del = element as PatternObjectInstanceDeleteType;
                    const instanceName = del.instance?.ref?.name ?? del.instance?.$refText;
                    if (instanceName) {
                        deletedInstances.add(instanceName);
                        deletedInstanceNodes.set(instanceName, del);
                    }
                }
                if (this.reflection.isInstance(element, PatternLink)) {
                    const link = element as PatternLinkType;
                    const sourceRef = link.source?.object?.ref;
                    const targetRef = link.target?.object?.ref;
                    if (sourceRef?.name && !localInstances.has(sourceRef.name)) {
                        if (!referencedInstanceNodes.has(sourceRef.name)) {
                            referencedInstanceNodes.set(sourceRef.name, null);
                        }
                    }
                    if (targetRef?.name && !localInstances.has(targetRef.name)) {
                        if (!referencedInstanceNodes.has(targetRef.name)) {
                            referencedInstanceNodes.set(targetRef.name, null);
                        }
                    }
                }
            }
        }

        if (cfgMatchNode.pattern?.elements != undefined) {
            for (const element of cfgMatchNode.pattern.elements) {
                if (this.reflection.isInstance(element, PatternObjectInstance)) {
                    await this.addPatternInstanceNode(node, element, idRegistry);
                }
            }
        }

        for (const [instanceName, reference] of referencedInstanceNodes) {
            if (!localInstances.has(instanceName) && !deletedInstances.has(instanceName)) {
                await this.createReferencedInstanceNode(
                    node,
                    instanceName,
                    cfgMatchNode.matchName,
                    reference ?? undefined,
                    idRegistry
                );
            }
        }

        for (const [instanceName, deleteElement] of deletedInstanceNodes) {
            if (!localInstances.has(instanceName)) {
                await this.createDeletedInstanceNode(node, instanceName, deleteElement, idRegistry);
            }
        }

        if (cfgMatchNode.pattern?.elements != undefined) {
            for (const element of cfgMatchNode.pattern.elements) {
                if (this.reflection.isInstance(element, PatternLink)) {
                    await this.createPatternLinkEdge(node, element, idRegistry);
                }
            }
        }

        if (cfgMatchNode.pattern?.elements != undefined) {
            const container = this.createConstraintCompartments(
                cfgMatchNode.id,
                cfgMatchNode.pattern.elements,
                idRegistry
            );
            if (container != undefined) {
                node.children.push(container);
            }
        }

        graph.children.push(node);
    }

    /**
     * Creates a pattern instance node.
     *
     * @param parent The parent node (match node)
     * @param instance The pattern object instance
     * @param idRegistry The model ID registry
     */
    /**
     * Creates a visual node for a single pattern object instance.
     * This method is the canonical factory method used for both regular diagram rendering
     * and ghost/preview element creation, analogous to {@link ModelGModelFactory.createObjectNode}.
     *
     * @param instance The pattern object instance AST node
     * @param nodeId The unique node ID
     * @param metadata The layout metadata for the node
     * @param idRegistry The ID registry for element ID generation
     * @returns The constructed GPatternInstanceNode
     */
    createPatternInstanceNode(
        instance: PatternObjectInstanceType,
        nodeId: string,
        metadata: NodeLayoutMetadata,
        idRegistry: ModelIdRegistry
    ): GPatternInstanceNode {
        const name = instance.name ?? "unnamed";
        const typeName = instance.class?.$refText ?? instance.class?.ref?.name ?? undefined;
        const modifier = this.getPatternModifierKind(instance.modifier?.modifier);
        const resolvedClass = instance.class?.ref as ClassType | undefined;
        const classHierarchy =
            resolvedClass != undefined
                ? resolveClassChain(resolvedClass, this.reflection).map((c) => c.name)
                : undefined;

        const node = GPatternInstanceNode.builder().id(nodeId).name(name).modifier(modifier).meta(metadata).build();

        if (typeName != undefined) {
            node.typeName = typeName;
        }
        if (classHierarchy != undefined) {
            node.classHierarchy = classHierarchy;
        }

        if (modifier !== PatternModifierKind.NONE) {
            const modifierCompartment = GPatternModifierTitleCompartment.builder()
                .id(`${nodeId}__modifier-title`)
                .build();

            const modifierLabel = GPatternModifierLabel.builder()
                .id(`${nodeId}__modifier-label`)
                .text(`\u00ab${modifier}\u00bb`)
                .build();
            modifierCompartment.children.push(modifierLabel);

            const labelText = typeName != undefined ? `${name} : ${typeName}` : name;
            const label = GPatternInstanceNameLabel.builder().id(`${nodeId}__name`).text(labelText).build();
            modifierCompartment.children.push(label);
            node.children.push(modifierCompartment);
        } else {
            const headerChildren = this.createPatternInstanceHeader(nodeId, name, typeName);
            node.children.push(...headerChildren);
        }

        const propertyChildren = this.createPatternPropertyAssignments(nodeId, instance.properties ?? [], idRegistry);
        if (propertyChildren.length > 0) {
            node.children.push(...propertyChildren);
        }

        return node;
    }

    /**
     * Adds a pattern instance node for a locally declared instance to the given parent match node.
     *
     * @param parent The parent match node to add the instance node to
     * @param instance The pattern object instance AST node to create the visual node for
     * @param idRegistry The model ID registry, used to obtain the unique node ID for the instance
     */
    private async addPatternInstanceNode(
        parent: GMatchNode,
        instance: PatternObjectInstanceType,
        idRegistry: ModelIdRegistry
    ): Promise<void> {
        const nodeId = idRegistry.getId(instance);
        const validatedMetadata = await this.modelState.getValidatedMetadata();
        const metadata = this.getNodeMetadata(validatedMetadata, nodeId);
        parent.children.push(this.createPatternInstanceNode(instance, nodeId, metadata, idRegistry));
    }

    /**
     * Creates a standalone reference instance node representing a re-use of an existing instance.
     * This is the canonical factory method used for both diagram rendering and ghost/preview creation.
     *
     * @param instanceName The name of the referenced instance
     * @param nodeId A unique node ID
     * @param metadata Layout metadata for the node
     * @param idRegistry The ID registry for child element IDs
     * @param reference Optional PatternObjectInstanceReference AST node for class resolution and properties
     * @returns A GPatternInstanceNode with NONE modifier
     */
    createReferenceInstanceNode(
        instanceName: string,
        nodeId: string,
        metadata: NodeLayoutMetadata,
        idRegistry: ModelIdRegistry,
        reference?: PatternObjectInstanceReferenceType
    ): GPatternInstanceNode {
        const resolvedClassForRef = reference?.instance?.ref?.class?.ref as ClassType | undefined;
        const classHierarchyForRef =
            resolvedClassForRef != undefined
                ? resolveClassChain(resolvedClassForRef, this.reflection).map((c) => c.name)
                : undefined;

        const node = GPatternInstanceNode.builder()
            .id(nodeId)
            .name(instanceName)
            .modifier(PatternModifierKind.NONE)
            .meta(metadata)
            .build();

        if (classHierarchyForRef != undefined) {
            node.classHierarchy = classHierarchyForRef;
        }

        const headerChildren = this.createPatternInstanceHeader(nodeId, instanceName, undefined);
        node.children.push(...headerChildren);

        const propertyChildren = this.createPatternPropertyAssignments(nodeId, reference?.properties ?? [], idRegistry);
        if (propertyChildren.length > 0) {
            node.children.push(...propertyChildren);
        }

        return node;
    }

    /**
     * Creates a referenced instance node (instance from a previous match that is re-used here).
     *
     * @param parent        The parent match node to add the child to.
     * @param instanceName  The name of the referenced instance.
     * @param matchName     The name of the match that originally declared the instance.
     * @param reference     The `PatternObjectInstanceReference` AST node, or `undefined` if unresolved.
     * @param idRegistry    The model ID registry used to obtain the element's graph ID.
     */
    private async createReferencedInstanceNode(
        parent: GMatchNode,
        instanceName: string,
        matchName: string,
        reference: PatternObjectInstanceReferenceType | undefined,
        idRegistry: ModelIdRegistry
    ): Promise<void> {
        const nodeId =
            reference != undefined
                ? idRegistry.getId(reference)
                : `PatternObjectInstanceReference_${matchName}_ref_${instanceName}`;
        const validatedMetadata = await this.modelState.getValidatedMetadata();
        const metadata = this.getNodeMetadata(validatedMetadata, nodeId);

        const node = this.createReferenceInstanceNode(instanceName, nodeId, metadata, idRegistry, reference);
        parent.children.push(node);
        this.referencedInstancesInCurrentMatch.set(instanceName, nodeId);
    }

    /**
     * Creates a standalone delete instance node representing the deletion of an existing instance.
     * This is the canonical factory method used for both diagram rendering and ghost/preview creation.
     *
     * @param instanceName The name of the instance being deleted
     * @param nodeId A unique node ID
     * @param metadata Layout metadata for the node
     * @param deleteElement Optional PatternObjectInstanceDelete AST node for class resolution
     * @returns A GPatternInstanceNode with DELETE modifier
     */
    createDeleteInstanceNode(
        instanceName: string,
        nodeId: string,
        metadata: NodeLayoutMetadata,
        deleteElement?: PatternObjectInstanceDeleteType
    ): GPatternInstanceNode {
        const resolvedClassForDel = deleteElement?.instance?.ref?.class?.ref as ClassType | undefined;
        const classHierarchyForDel =
            resolvedClassForDel != undefined
                ? resolveClassChain(resolvedClassForDel, this.reflection).map((c) => c.name)
                : undefined;

        const node = GPatternInstanceNode.builder()
            .id(nodeId)
            .name(instanceName)
            .modifier(PatternModifierKind.DELETE)
            .meta(metadata)
            .build();

        if (classHierarchyForDel != undefined) {
            node.classHierarchy = classHierarchyForDel;
        }

        const modifierCompartment = GPatternModifierTitleCompartment.builder().id(`${nodeId}__modifier-title`).build();

        const modifierLabel = GPatternModifierLabel.builder()
            .id(`${nodeId}__modifier-label`)
            .text(`\u00ab${PatternModifierKind.DELETE}\u00bb`)
            .readonly(true)
            .build();
        modifierCompartment.children.push(modifierLabel);

        const label = GPatternInstanceNameLabel.builder().id(`${nodeId}__name`).text(instanceName).build();
        modifierCompartment.children.push(label);
        node.children.push(modifierCompartment);

        return node;
    }

    /**
     * Creates a deleted instance node (instance declared in a previous match, now deleted).
     * The node is rendered with the DELETE modifier compartment to visually distinguish it.
     *
     * The type name is resolved from the referenced `PatternObjectInstance` when available,
     * so the node shows `name : Type` just like a locally declared instance with DELETE modifier.
     *
     * @param parent The parent match node to add the child to
     * @param instanceName The name of the instance being deleted
     * @param deleteElement The PatternObjectInstanceDelete AST node, used to look up the registry ID
     * @param idRegistry The model ID registry
     */
    private async createDeletedInstanceNode(
        parent: GMatchNode,
        instanceName: string,
        deleteElement: PatternObjectInstanceDeleteType,
        idRegistry: ModelIdRegistry
    ): Promise<void> {
        const nodeId = idRegistry.getId(deleteElement);
        const validatedMetadata = await this.modelState.getValidatedMetadata();
        const metadata = this.getNodeMetadata(validatedMetadata, nodeId);

        const node = this.createDeleteInstanceNode(instanceName, nodeId, metadata, deleteElement);
        parent.children.push(node);
        this.referencedInstancesInCurrentMatch.set(instanceName, nodeId);
    }

    /**
     * Creates the header for a pattern instance node.
     *
     * @param nodeId The node ID
     * @param name The instance name
     * @param typeName The optional type name
     * @returns Array of header elements
     */
    private createPatternInstanceHeader(nodeId: string, name: string, typeName: string | undefined): GModelElement[] {
        const compartment = GCompartment.builder()
            .type(ModelTransformationElementType.COMPARTMENT)
            .id(`${nodeId}__header`)
            .build();

        const labelText = typeName != undefined ? `${name} : ${typeName}` : name;
        const label = GPatternInstanceNameLabel.builder().id(`${nodeId}__name`).text(labelText).build();

        compartment.children.push(label);
        return [compartment];
    }

    /**
     * Creates property assignment labels for a pattern instance.
     *
     * @param nodeId The parent node ID
     * @param properties The property assignments
     * @param idRegistry The model ID registry
     * @returns Array of property elements
     */
    private createPatternPropertyAssignments(
        nodeId: string,
        properties: PatternPropertyAssignmentType[],
        idRegistry: ModelIdRegistry
    ): GModelElement[] {
        if (properties.length === 0) {
            return [];
        }

        const children: GModelElement[] = [];

        const compartment = GCompartment.builder()
            .type(ModelTransformationElementType.COMPARTMENT)
            .id(`${nodeId}__properties`)
            .build();

        for (const prop of properties) {
            if (prop == undefined) {
                continue;
            }

            const propId = idRegistry.getId(prop);
            const rawPropName = prop.name?.$refText ?? prop.name?.ref?.name ?? "?";
            const propName = this.modelState.languageServices.AstSerializer.serializePrimitive(
                { value: rawPropName },
                ID
            );
            const operator = prop.operator ?? "=";
            const valueText = prop.value?.$cstNode?.text ?? "?";
            const propText = `${propName} ${operator} ${valueText}`;

            const label = GPatternPropertyLabel.builder().id(propId).text(propText).build();

            compartment.children.push(label);
        }

        const divider = GHorizontalDivider.builder()
            .type(ModelTransformationElementType.DIVIDER)
            .id(`${nodeId}__divider`)
            .build();

        children.push(divider);
        children.push(compartment);
        return children;
    }

    /**
     * Creates a container wrapping the where-clause and variable compartments.
     * The container also includes horizontal dividers between compartments.
     * Returns undefined if there are no where-clauses or variables.
     *
     * @param nodeId The parent node ID
     * @param elements The pattern elements
     * @param idRegistry The model ID registry
     * @returns A GMatchNodeCompartments container, or undefined if empty
     */
    private createConstraintCompartments(
        nodeId: string,
        elements: unknown[],
        idRegistry: ModelIdRegistry
    ): GMatchNodeCompartments | undefined {
        const whereClauseLabels: GModelElement[] = [];
        for (const element of elements) {
            if (this.reflection.isInstance(element, WhereClause)) {
                const whereId = idRegistry.getId(element);
                const exprText = element.expression?.$cstNode?.text ?? "?";
                const label = GWhereClauseLabel.builder().id(whereId).text(`where ${exprText}`).build();
                whereClauseLabels.push(label);
            }
        }

        const variableLabels: GModelElement[] = [];
        for (const element of elements) {
            if (this.reflection.isInstance(element, PatternVariable)) {
                const varId = idRegistry.getId(element);
                const name = element.name ?? "?";
                const serializedName = this.modelState.languageServices.AstSerializer.serializePrimitive(
                    { value: name },
                    ID
                );
                const typeText = element.type?.$cstNode?.text;
                const valueText = element.value?.$cstNode?.text ?? "?";
                let varText = `var ${serializedName}`;
                if (typeText != undefined) {
                    varText += `: ${typeText}`;
                }
                varText += ` = ${valueText}`;

                const label = GVariableLabel.builder().id(varId).text(varText).build();
                variableLabels.push(label);
            }
        }

        const compartments: GCompartment[] = [];

        if (whereClauseLabels.length > 0) {
            const compartment = GCompartment.builder()
                .type(ModelTransformationElementType.COMPARTMENT)
                .id(`${nodeId}__where-clauses`)
                .build();

            compartment.children.push(...whereClauseLabels);
            compartments.push(compartment);
        }

        if (variableLabels.length > 0) {
            const compartment = GCompartment.builder()
                .type(ModelTransformationElementType.COMPARTMENT)
                .id(`${nodeId}__variables`)
                .build();

            compartment.children.push(...variableLabels);
            compartments.push(compartment);
        }

        if (compartments.length === 0) {
            return undefined;
        }

        const container = GMatchNodeCompartments.builder().id(`${nodeId}__compartments`).build();

        const topDivider = GHorizontalDivider.builder()
            .type(ModelTransformationElementType.DIVIDER)
            .id(`${nodeId}__compartments-top-divider`)
            .build();
        container.children.push(topDivider);

        for (let i = 0; i < compartments.length; i++) {
            if (i > 0) {
                const divider = GHorizontalDivider.builder()
                    .type(ModelTransformationElementType.DIVIDER)
                    .id(`${nodeId}__compartment-divider-${i}`)
                    .build();
                container.children.push(divider);
            }
            container.children.push(compartments[i]);
        }

        return container;
    }

    /**
     * Creates a pattern link edge between instances.
     *
     * @param parent The parent node (match node)
     * @param link The pattern link
     * @param idRegistry The model ID registry
     */
    private async createPatternLinkEdge(
        parent: GMatchNode,
        link: PatternLinkType,
        idRegistry: ModelIdRegistry
    ): Promise<void> {
        const edgeId = idRegistry.getId(link);

        const sourceInstanceRef = link.source?.object?.ref;
        const targetInstanceRef = link.target?.object?.ref;

        if (sourceInstanceRef == undefined || targetInstanceRef == undefined) {
            return;
        }

        const sourceId = this.resolveInstanceNodeId(sourceInstanceRef, idRegistry);
        const targetId = this.resolveInstanceNodeId(targetInstanceRef, idRegistry);

        if (sourceId == undefined || targetId == undefined) {
            return;
        }

        const modifier = this.getPatternModifierKind(link.modifier?.modifier);
        const sourceProperty = link.source?.property?.$refText;
        const targetProperty = link.target?.property?.$refText;

        const validatedMetadata = await this.modelState.getValidatedMetadata();
        const rawEdgeMeta = validatedMetadata.edges[edgeId]?.meta;
        const edgeMeta =
            rawEdgeMeta != undefined && EdgeLayoutMetadataUtil.isValid(rawEdgeMeta)
                ? rawEdgeMeta
                : EdgeLayoutMetadataUtil.create();

        const edgeBuilder = GPatternLinkEdge.builder()
            .id(edgeId)
            .sourceId(sourceId)
            .targetId(targetId)
            .modifier(modifier)
            .meta(edgeMeta);

        if (sourceProperty != undefined) {
            edgeBuilder.sourceProperty(sourceProperty);
        }
        if (targetProperty != undefined) {
            edgeBuilder.targetProperty(targetProperty);
        }

        const sourceClassType = sourceInstanceRef?.class?.ref as ClassType | undefined;
        const targetClassType = targetInstanceRef?.class?.ref as ClassType | undefined;

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

        await this.addPatternLinkLabels(edge, edgeId, sourceProperty, targetProperty);

        if (modifier !== PatternModifierKind.NONE) {
            const modifierNodeId = `${edgeId}__modifier-node`;
            const modifierNodeMetadata = this.getNodeMetadata(validatedMetadata, modifierNodeId);

            const modifierNode = GPatternLinkModifierNode.builder()
                .id(modifierNodeId)
                .modifier(modifier)
                .meta(modifierNodeMetadata)
                .build();

            const modifierLabel = GPatternLinkModifierLabel.builder()
                .id(`${edgeId}__modifier-label`)
                .text(`\u00ab${modifier}\u00bb`)
                .build();

            modifierNode.children.push(modifierLabel);
            edge.children.push(modifierNode);
        }

        parent.children.push(edge);
    }

    /**
     * Resolves the node ID for a pattern instance from its AST node reference.
     *
     * @param instanceRef The pattern object instance AST node
     * @param idRegistry  The model ID registry
     * @returns The resolved node ID or undefined
     */
    private resolveInstanceNodeId(
        instanceRef: PatternObjectInstanceType | undefined,
        idRegistry: ModelIdRegistry
    ): string | undefined {
        if (instanceRef == undefined) return undefined;
        const instanceName = instanceRef.name;
        if (instanceName) {
            const refNodeId = this.referencedInstancesInCurrentMatch.get(instanceName);
            if (refNodeId != undefined) {
                return refNodeId;
            }
        }
        return idRegistry.getId(instanceRef);
    }

    /**
     * Adds property labels to a pattern link edge.
     *
     * @param edge The pattern link edge
     * @param edgeId The edge ID
     * @param sourceProperty The optional source property
     * @param targetProperty The optional target property
     */
    private async addPatternLinkLabels(
        edge: GPatternLinkEdge,
        edgeId: string,
        sourceProperty: string | undefined,
        targetProperty: string | undefined
    ): Promise<void> {
        const validatedMetadata = await this.modelState.getValidatedMetadata();

        if (sourceProperty != undefined) {
            const nodeId = `${edgeId}__source-node`;
            const metadata = this.getNodeMetadata(validatedMetadata, nodeId);

            const endNode = GPatternLinkEndNode.builder().id(nodeId).end("target").meta(metadata).build();

            const label = GPatternLinkEndLabel.builder()
                .id(`${edgeId}__source-label`)
                .text(sourceProperty)
                .readonly(true)
                .build();

            endNode.children.push(label);
            edge.children.push(endNode);
        }

        if (targetProperty != undefined) {
            const nodeId = `${edgeId}__target-node`;
            const metadata = this.getNodeMetadata(validatedMetadata, nodeId);

            const endNode = GPatternLinkEndNode.builder().id(nodeId).end("source").meta(metadata).build();

            const label = GPatternLinkEndLabel.builder()
                .id(`${edgeId}__target-label`)
                .text(targetProperty)
                .readonly(true)
                .build();

            endNode.children.push(label);
            edge.children.push(endNode);
        }
    }

    /**
     * Creates a split node for branching.
     *
     * @param graph The graph to add the node to
     * @param nodeId The node ID
     * @param expression The condition expression text
     */
    private async createSplitNode(graph: GGraphType, nodeId: string, expression: string): Promise<void> {
        const validatedMetadata = await this.modelState.getValidatedMetadata();
        const metadata = this.getNodeMetadata(validatedMetadata, nodeId);

        const node = GSplitNode.builder().id(nodeId).expression(expression).meta(metadata).build();
        graph.children.push(node);
    }

    /**
     * Creates a merge node where branches rejoin.
     *
     * @param graph The graph to add the node to
     * @param nodeId The node ID
     */
    private async createMergeNode(graph: GGraphType, nodeId: string): Promise<void> {
        const validatedMetadata = await this.modelState.getValidatedMetadata();
        const metadata = this.getNodeMetadata(validatedMetadata, nodeId);

        const node = GMergeNode.builder().id(nodeId).meta(metadata).build();
        graph.children.push(node);
    }

    /**
     * Creates a control flow edge between nodes.
     *
     * @param graph The graph to add the edge to
     * @param sourceId The source node ID
     * @param targetId The target node ID
     * @param label The optional edge label
     * @param labelId When provided, use as the label element's ID and make it editable
     */
    private async createControlFlowEdge(
        graph: GGraphType,
        sourceId: string,
        targetId: string,
        label: string | undefined,
        labelId?: string
    ): Promise<void> {
        const edgeId = ModelTransformationIdGenerator.controlFlowEdge(sourceId, targetId, label);
        const validatedMetadata = await this.modelState.getValidatedMetadata();
        const metadata = validatedMetadata.edges[edgeId]?.meta;
        const edgeMeta =
            metadata != undefined && EdgeLayoutMetadataUtil.isValid(metadata)
                ? metadata
                : EdgeLayoutMetadataUtil.create();

        const edge = GControlFlowEdge.builder().id(edgeId).sourceId(sourceId).targetId(targetId).build();

        edge.meta = edgeMeta;

        if (label != undefined) {
            const labelNodeId = `${edgeId}__label-node`;
            const labelMetadata = this.getNodeMetadata(validatedMetadata, labelNodeId);

            const labelNode = GControlFlowLabelNode.builder().id(labelNodeId).end("source").meta(labelMetadata).build();

            const actualLabelId = labelId ?? `${edgeId}__label`;
            const isReadonly = labelId == undefined;
            const labelElement = GControlFlowLabel.builder().id(actualLabelId).text(label).readonly(isReadonly).build();

            labelNode.children.push(labelElement);
            edge.children.push(labelNode);
        }

        graph.children.push(edge);
    }

    private getPatternModifierKind(modifier: string | undefined): PatternModifierKind {
        switch (modifier) {
            case "create":
                return PatternModifierKind.CREATE;
            case "delete":
                return PatternModifierKind.DELETE;
            case "forbid":
                return PatternModifierKind.FORBID;
            case "require":
                return PatternModifierKind.REQUIRE;
            default:
                return PatternModifierKind.NONE;
        }
    }

    /**
     * Gets node metadata from validated metadata, providing defaults if not found.
     *
     * @param validatedMetadata The validated graph metadata
     * @param nodeId The node ID
     * @returns The node layout metadata
     */
    private getNodeMetadata(validatedMetadata: GraphMetadata, nodeId: string): NodeLayoutMetadata {
        const metadata = validatedMetadata.nodes[nodeId]?.meta;
        if (metadata != undefined && NodeLayoutMetadataUtil.isValid(metadata)) {
            return metadata;
        }
        return NodeLayoutMetadataUtil.create(0, 0, 250);
    }
}
