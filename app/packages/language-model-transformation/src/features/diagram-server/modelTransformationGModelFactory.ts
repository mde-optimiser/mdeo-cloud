import type { GModelElement, GModelRoot } from "@eclipse-glsp/server";
import type { AstNode } from "langium";
import { declaredClassOf, renderParameter } from "../transformationDeclarations.js";
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
    type PatternVariableReassignmentType,
    PatternObjectInstance,
    PatternLink,
    WhereClause,
    PatternVariable,
    PatternVariableReassignment,
    PatternObjectInstanceReference,
    PatternObjectInstanceDelete,
    type PatternApplicationConditionType,
    type PatternModelScopeType,
    type ObjectInstanceDeclarationType,
    type TransformationParameterType
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
import { GCallLabel, GCallNode } from "./model/callNode.js";
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
import { GDeclarationLabel } from "./model/declarationLabel.js";
import { declarationRowId, declarationsCompartmentId } from "./modelTransformationHeader.js";
import { EndNodeKind, ModelTransformationElementType, PatternModifierKind } from "@mdeo/protocol-model-transformation";
import { ID } from "@mdeo/language-common";
import { ModelTransformationIdGenerator } from "./modelTransformationIdGenerator.js";
import { adaptGeneratedModelTransformationText } from "./generated/generatedModelTransformationAstAdapter.js";
import {
    blockStereotype,
    flattenPatternElements,
    patternModifierKind,
    type FlattenedPatternElement
} from "./modelTransformationPatternUtils.js";
import { stereotypeText, whereClauseLabelText } from "./modelTransformationLabelFormat.js";

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
                const startNode = GStartNode.builder().id(node.id).meta(metadata).build();
                startNode.children.push(...this.createDeclarationRows(node.id, node.inputs, true, idRegistry));
                graph.children.push(startNode);
                break;
            }
            case "end": {
                const cfgEndNode = node as ControlFlowEndNode;
                const metadata = this.getNodeMetadata(validatedMetadata, cfgEndNode.id);
                const kind = cfgEndNode.endKind === "kill" ? EndNodeKind.KILL : EndNodeKind.STOP;
                const endNode = GEndNode.builder().id(cfgEndNode.id).kind(kind).meta(metadata).build();
                endNode.children.push(
                    ...this.createDeclarationRows(cfgEndNode.id, cfgEndNode.outputs, cfgEndNode.primary, idRegistry)
                );
                graph.children.push(endNode);
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
            case "call": {
                const metadata = this.getNodeMetadata(validatedMetadata, node.id);
                const callNode = GCallNode.builder()
                    .id(node.id)
                    .text(node.text)
                    .variant(node.variant)
                    .meta(metadata)
                    .build();
                if (node.labelId != undefined) {
                    callNode.children.push(GCallLabel.builder().id(node.labelId).text(node.text).build());
                }
                graph.children.push(callNode);
                break;
            }
        }
    }

    /**
     * Creates the rows a start node (its inputs) or a successful end node (the outputs) lists:
     * one row per declaration holding its source line, editable and deletable, in a compartment
     * of the node. Without declarations the node keeps its plain symbol and gets no rows.
     *
     * The outputs are listed by every successful end, so the ids of the rows of all but the
     * primary end are derived from the declaration's id (see {@link declarationRowId}); all of
     * them resolve to the declaration, so that edits and deletions find the line behind any row
     * and validation markers land on the start node or the primary end.
     *
     * @param nodeId The id of the start or end node
     * @param declarations The declarations to list
     * @param primary Whether the node holds the declarations' own rows
     * @param idRegistry The model ID registry
     * @returns The compartment holding the rows, or nothing without declarations
     */
    createDeclarationRows(
        nodeId: string,
        declarations: readonly TransformationParameterType[],
        primary: boolean,
        idRegistry: ModelIdRegistry
    ): GModelElement[] {
        if (declarations.length === 0) {
            return [];
        }
        const compartment = GCompartment.builder()
            .type(ModelTransformationElementType.COMPARTMENT)
            .id(declarationsCompartmentId(nodeId))
            .build();
        for (const declaration of declarations) {
            compartment.children.push(
                GDeclarationLabel.builder()
                    .id(declarationRowId(idRegistry.getIdOrUnresolved(declaration), nodeId, primary))
                    .text(this.sourceLine(declaration, renderParameter(declaration)))
                    .build()
            );
        }
        return [compartment];
    }

    /**
     * Returns the source text of a node on one line.
     *
     * @param node The node
     * @param fallback The text when the node has no source text
     * @returns The text
     */
    private sourceLine(node: AstNode, fallback: string): string {
        return (node.$cstNode?.text ?? fallback).replace(/\s+/g, " ").trim();
    }

    /**
     * Returns the stereotype text for an element of an application condition block or of a
     * model scope block, or `undefined` for elements of the match pattern itself.
     *
     * A condition block is always named in the stereotype, an unnamed one by its number, so that
     * the elements of two blocks drawn in the same match node stay distinguishable; a scope block
     * is named by its model (`in donor`).
     *
     * @param condition The condition block an element belongs to, if any.
     * @param scope The model scope block an element belongs to, if any.
     * @returns The stereotype text (without guillemets), or `undefined`.
     */
    private getBlockStereotype(
        condition: PatternApplicationConditionType | undefined,
        scope?: PatternModelScopeType
    ): string | undefined {
        return blockStereotype(condition, scope, this.reflection);
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
        const referenceScopes = new Map<string, PatternModelScopeType>();
        const deletedInstances = new Set<string>();
        const deletedInstanceNodes = new Map<string, PatternObjectInstanceDeleteType>();

        const patternElements = flattenPatternElements(cfgMatchNode.pattern, this.reflection);

        {
            for (const { element, scope } of patternElements) {
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
                            if (scope != undefined) {
                                referenceScopes.set(instanceRef.name, scope);
                            }
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

        for (const { element, condition, scope } of patternElements) {
            if (this.reflection.isInstance(element, PatternObjectInstance)) {
                await this.addPatternInstanceNode(node, element, idRegistry, condition, scope);
            }
        }

        for (const [instanceName, reference] of referencedInstanceNodes) {
            if (!localInstances.has(instanceName) && !deletedInstances.has(instanceName)) {
                await this.createReferencedInstanceNode(
                    node,
                    instanceName,
                    cfgMatchNode.matchName,
                    reference ?? undefined,
                    idRegistry,
                    referenceScopes.get(instanceName)
                );
            }
        }

        for (const [instanceName, deleteElement] of deletedInstanceNodes) {
            if (!localInstances.has(instanceName)) {
                await this.createDeletedInstanceNode(node, instanceName, deleteElement, idRegistry);
            }
        }

        for (const { element, condition, scope } of patternElements) {
            if (this.reflection.isInstance(element, PatternLink)) {
                await this.createPatternLinkEdge(node, element, idRegistry, condition, scope);
            }
        }

        if (cfgMatchNode.pattern?.elements != undefined) {
            const container = this.createConstraintCompartments(cfgMatchNode.id, patternElements, idRegistry);
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
     * @param condition The application condition block the instance belongs to, if any
     * @param scope The model scope block the instance belongs to, if any
     * @returns The constructed GPatternInstanceNode
     */
    createPatternInstanceNode(
        instance: PatternObjectInstanceType,
        nodeId: string,
        metadata: NodeLayoutMetadata,
        idRegistry: ModelIdRegistry,
        condition?: PatternApplicationConditionType,
        scope?: PatternModelScopeType
    ): GPatternInstanceNode {
        const name = instance.name ?? "unnamed";
        const typeName = instance.class?.$refText ?? instance.class?.ref?.name ?? undefined;
        const stereotype = this.getBlockStereotype(condition, scope);
        const modifier =
            condition != undefined
                ? patternModifierKind(condition.kind)
                : patternModifierKind(instance.modifier?.modifier);
        const resolvedClass = instance.class?.ref as ClassType | undefined;
        const classHierarchy =
            resolvedClass != undefined
                ? resolveClassChain(resolvedClass, this.reflection).map((c) => c.name)
                : undefined;

        const node = GPatternInstanceNode.builder()
            .id(nodeId)
            .name(name)
            .modifier(modifier)
            .model(scope?.model?.$refText)
            .meta(metadata)
            .build();

        if (typeName != undefined) {
            node.typeName = typeName;
        }
        if (classHierarchy != undefined) {
            node.classHierarchy = classHierarchy;
        }

        if (modifier !== PatternModifierKind.NONE || stereotype != undefined) {
            const modifierCompartment = GPatternModifierTitleCompartment.builder()
                .id(`${nodeId}__modifier-title`)
                .build();

            const modifierLabel = GPatternModifierLabel.builder()
                .id(`${nodeId}__modifier-label`)
                .text(stereotypeText(stereotype ?? modifier))
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
     * @param condition The application condition block the instance belongs to, if any
     * @param scope The model scope block the instance belongs to, if any
     */
    private async addPatternInstanceNode(
        parent: GMatchNode,
        instance: PatternObjectInstanceType,
        idRegistry: ModelIdRegistry,
        condition?: PatternApplicationConditionType,
        scope?: PatternModelScopeType
    ): Promise<void> {
        const nodeId = idRegistry.getId(instance);
        const validatedMetadata = await this.modelState.getValidatedMetadata();
        const metadata = this.getNodeMetadata(validatedMetadata, nodeId);
        parent.children.push(this.createPatternInstanceNode(instance, nodeId, metadata, idRegistry, condition, scope));
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
     * @param scope The model scope block the reference is declared in, if any; the node is then
     *   tagged with the block's stereotype (`«in donor»`)
     * @returns A GPatternInstanceNode with NONE modifier
     */
    createReferenceInstanceNode(
        instanceName: string,
        nodeId: string,
        metadata: NodeLayoutMetadata,
        idRegistry: ModelIdRegistry,
        reference?: PatternObjectInstanceReferenceType,
        scope?: PatternModelScopeType
    ): GPatternInstanceNode {
        const resolvedClassForRef = declaredClassOf(this.modelState.languageServices, reference?.instance?.ref);
        const classHierarchyForRef =
            resolvedClassForRef != undefined
                ? resolveClassChain(resolvedClassForRef, this.reflection).map((c) => c.name)
                : undefined;

        const node = GPatternInstanceNode.builder()
            .id(nodeId)
            .name(instanceName)
            .modifier(PatternModifierKind.NONE)
            .model(scope?.model?.$refText)
            .meta(metadata)
            .build();

        if (classHierarchyForRef != undefined) {
            node.classHierarchy = classHierarchyForRef;
        }

        const stereotype = this.getBlockStereotype(undefined, scope);
        if (stereotype != undefined) {
            const modifierCompartment = GPatternModifierTitleCompartment.builder()
                .id(`${nodeId}__modifier-title`)
                .build();
            modifierCompartment.children.push(
                GPatternModifierLabel.builder()
                    .id(`${nodeId}__modifier-label`)
                    .text(stereotypeText(stereotype))
                    .build(),
                GPatternInstanceNameLabel.builder().id(`${nodeId}__name`).text(instanceName).build()
            );
            node.children.push(modifierCompartment);
        } else {
            const headerChildren = this.createPatternInstanceHeader(nodeId, instanceName, undefined);
            node.children.push(...headerChildren);
        }

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
     * @param scope         The model scope block the reference is declared in, if any.
     */
    private async createReferencedInstanceNode(
        parent: GMatchNode,
        instanceName: string,
        matchName: string,
        reference: PatternObjectInstanceReferenceType | undefined,
        idRegistry: ModelIdRegistry,
        scope?: PatternModelScopeType
    ): Promise<void> {
        const nodeId =
            reference != undefined
                ? idRegistry.getId(reference)
                : `PatternObjectInstanceReference_${matchName}_ref_${instanceName}`;
        const validatedMetadata = await this.modelState.getValidatedMetadata();
        const metadata = this.getNodeMetadata(validatedMetadata, nodeId);

        const node = this.createReferenceInstanceNode(instanceName, nodeId, metadata, idRegistry, reference, scope);
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
        const resolvedClassForDel = declaredClassOf(this.modelState.languageServices, deleteElement?.instance?.ref);
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
            .text(stereotypeText(PatternModifierKind.DELETE))
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
     * A where clause declared inside a `forbid` / `require` block or a model scope block is
     * rendered like every other member of a block: with the block's stereotype in front of it,
     * so that it is visible which condition or model the constraint belongs to.
     *
     * @param nodeId The parent node ID
     * @param patternElements The pattern elements, each with the condition block it belongs to
     * @param idRegistry The model ID registry
     * @returns A GMatchNodeCompartments container, or undefined if empty
     */
    private createConstraintCompartments(
        nodeId: string,
        patternElements: FlattenedPatternElement[],
        idRegistry: ModelIdRegistry
    ): GMatchNodeCompartments | undefined {
        const whereClauseLabels: GModelElement[] = [];
        for (const { element, condition, scope } of patternElements) {
            if (this.reflection.isInstance(element, WhereClause)) {
                const whereId = idRegistry.getId(element);
                const exprText = element.expression?.$cstNode?.text ?? "?";
                const stereotype = this.getBlockStereotype(condition, scope);
                const label = GWhereClauseLabel.builder()
                    .id(whereId)
                    .text(whereClauseLabelText(exprText, stereotype))
                    .build();
                whereClauseLabels.push(label);
            }
        }

        const variableLabels: GModelElement[] = [];
        for (const { element } of patternElements) {
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
            } else if (this.reflection.isInstance(element, PatternVariableReassignment)) {
                const reassignment = element as PatternVariableReassignmentType;
                const reassignId = idRegistry.getId(reassignment);
                const rawName = reassignment.variable?.$refText ?? reassignment.variable?.ref?.name ?? "?";
                const serializedName = this.modelState.languageServices.AstSerializer.serializePrimitive(
                    { value: rawName },
                    ID
                );
                const valueText = reassignment.value?.$cstNode?.text ?? "?";
                const reassignText = `${serializedName} = ${valueText}`;

                const label = GVariableLabel.builder().id(reassignId).text(reassignText).build();
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
     * @param condition The application condition block the link belongs to, if any
     * @param scope The model scope block the link belongs to, if any
     */
    private async createPatternLinkEdge(
        parent: GMatchNode,
        link: PatternLinkType,
        idRegistry: ModelIdRegistry,
        condition?: PatternApplicationConditionType,
        scope?: PatternModelScopeType
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

        const stereotype = this.getBlockStereotype(condition, scope);
        const modifier =
            condition != undefined ? patternModifierKind(condition.kind) : patternModifierKind(link.modifier?.modifier);
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

        const sourceClassType = declaredClassOf(this.modelState.languageServices, sourceInstanceRef);
        const targetClassType = declaredClassOf(this.modelState.languageServices, targetInstanceRef);

        // Navigable association ends may declare a role name in the metamodel. A pattern link doesn't have to repeat
        // that name explicitly, so when the link's own DSL omits it, fall back to the resolved association
        // end name (when available) so the edge can still show a role label.
        let effectiveSourceProperty = sourceProperty;
        let effectiveTargetProperty = targetProperty;

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
                effectiveSourceProperty = sourceProperty?.trim() || candidate.sourceEnd.name;
                effectiveTargetProperty = targetProperty?.trim() || candidate.targetEnd.name;
            }
        }

        const edge = edgeBuilder.build();

        await this.addPatternLinkLabels(edge, edgeId, effectiveSourceProperty, effectiveTargetProperty);

        if (modifier !== PatternModifierKind.NONE || stereotype != undefined) {
            const modifierNodeId = `${edgeId}__modifier-node`;
            const modifierNodeMetadata = this.getNodeMetadata(validatedMetadata, modifierNodeId);

            const modifierNode = GPatternLinkModifierNode.builder()
                .id(modifierNodeId)
                .modifier(modifier)
                .meta(modifierNodeMetadata)
                .build();

            const modifierLabel = GPatternLinkModifierLabel.builder()
                .id(`${edgeId}__modifier-label`)
                .text(stereotypeText(stereotype ?? modifier))
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
        instanceRef: ObjectInstanceDeclarationType | undefined,
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

    /**
     * Gets node metadata from validated metadata, providing defaults if not found.
     *
     * @param validatedMetadata The validated graph metadata
     * @param nodeId The node ID
     * @returns The node layout metadata
     */
    private getNodeMetadata(validatedMetadata: GraphMetadata, nodeId: string): NodeLayoutMetadata {
        const metadata = validatedMetadata.nodes[nodeId]?.meta;
        if (metadata != undefined && NodeLayoutMetadataUtil.isValidWithOptionalSize(metadata)) {
            return metadata;
        }
        return NodeLayoutMetadataUtil.create(0, 0, 250);
    }
}
