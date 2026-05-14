import type { CreateEdgeSchema } from "@mdeo/protocol-common";
import {
    CreateEdgeSchemaResolver,
    sharedImport,
    EdgeLayoutMetadataUtil,
    NodeLayoutMetadataUtil
} from "@mdeo/language-shared";
import type { ModelState, GModelIndex } from "@mdeo/language-shared";
import type { InitialCreateEdgeSchemaRequest, TargetCreateEdgeSchemaRequest } from "@mdeo/language-shared";
import type { AstNode } from "langium";
import type { GModelElementSchema } from "@eclipse-glsp/protocol";
import { Class, type ClassType } from "../../grammar/metamodelTypes.js";
import { MetamodelElementType, AssociationEndKind } from "@mdeo/protocol-metamodel";
import { GAssociationEdge } from "./model/associationEdge.js";
import { GAssociationPropertyNode } from "./model/associationPropertyNode.js";
import { GAssociationPropertyLabel } from "./model/associationPropertyLabel.js";
import { GInheritanceEdge } from "./model/inheritanceEdge.js";
import { GClassNode } from "./model/classNode.js";
import { collectAllPropertyNames } from "../../validation/metamodelValidator.js";
import { generateDefaultPropertyName } from "./handler/metamodelHandlerUtils.js";

const { injectable, inject } = sharedImport("inversify");
const { ModelState: ModelStateKey, GModelIndex: GModelIndexKey } = sharedImport("@eclipse-glsp/server");

/**
 * EdgeCreationType values mirrored from editor-metamodel (avoid a cross-package import).
 */
const EdgeCreationType = {
    UNIDIRECTIONAL: "unidirectional",
    BIDIRECTIONAL: "bidirectional",
    COMPOSITION: "composition",
    NAVIGABLE_COMPOSITION: "navigable-composition",
    EXTENDS: "extends"
} as const;

type EdgeCreationTypeValue = (typeof EdgeCreationType)[keyof typeof EdgeCreationType];

/**
 * Context provided by the client create-edge context provider.
 */
interface MetamodelEdgeCreationContext {
    edgeType?: EdgeCreationTypeValue;
}

/**
 * Resolves create-edge schemas for metamodel diagrams.
 *
 * The schema is driven by the client-supplied context (the selected edge type):
 * - Initial schema: checks source is a Class, for BIDIRECTIONAL/COMPOSITION/NAVIGABLE_COMPOSITION
 *   pre-computes the target-side property label (based on the source class name).
 * - Target schema: computes the remaining property label(s) and builds the final schema
 *   with the appropriate association or inheritance edge template.
 */
@injectable()
export class MetamodelCreateEdgeSchemaResolver extends CreateEdgeSchemaResolver {
    @inject(ModelStateKey)
    protected readonly modelState!: ModelState;

    @inject(GModelIndexKey)
    protected readonly index!: GModelIndex;

    /**
     * Resolves the initial create-edge schema for a source element before a target is selected.
     * Validates that the source is a local (non-imported) class and pre-computes the target-side
     * property label for edge types that carry one.
     *
     * @param request The initial schema request containing the source element ID and context
     * @returns The create-edge schema to use for feedback, or undefined if the source is invalid
     */
    override async getInitialSchema(request: InitialCreateEdgeSchemaRequest): Promise<CreateEdgeSchema | undefined> {
        const sourceClass = this.resolveLocalClass(request.sourceElementId);
        if (!sourceClass) {
            return undefined;
        }

        const edgeType = this.extractEdgeType(request.context);

        const needsTargetLabel = this.requiresTargetLabel(edgeType);

        let precomputedTargetLabel: string | undefined;
        if (needsTargetLabel && sourceClass.name) {
            precomputedTargetLabel = this.computePropertyName(sourceClass.name, new Set<string>());
        }

        const params: Record<string, unknown> = { edgeType };
        if (precomputedTargetLabel !== undefined) {
            params.targetLabel = precomputedTargetLabel;
        }

        return this.buildInitialSchema(request.sourceElementId, edgeType, params, precomputedTargetLabel);
    }

    /**
     * Resolves the final create-edge schema once both source and target are known.
     * Computes deduplicated property labels and builds the concrete edge template.
     *
     * @param request The target schema request containing source and target element IDs and context
     * @returns The create-edge schema with finalized property labels, or undefined if either element is invalid
     */
    override async getTargetSchema(request: TargetCreateEdgeSchemaRequest): Promise<CreateEdgeSchema | undefined> {
        const sourceClass = this.resolveLocalClass(request.sourceElementId);
        const targetClassName = this.resolveClassName(request.targetElementId);
        if (!sourceClass || !targetClassName) {
            return undefined;
        }

        const edgeType = this.extractEdgeType(request.context);

        if (edgeType === EdgeCreationType.EXTENDS) {
            return this.buildExtendsSchema(request.sourceElementId, request.targetElementId);
        }

        return this.buildAssociationSchema(
            request.sourceElementId,
            request.targetElementId,
            edgeType,
            sourceClass,
            targetClassName
        );
    }

    /**
     * Resolves an element ID to a local (non-imported) ClassType AST node.
     * Returns undefined if the element is not a class or is an imported class.
     */
    private resolveLocalClass(elementId: string): ClassType | undefined {
        const modelElement = this.modelState.index.find(elementId);
        if (!modelElement) {
            return undefined;
        }
        const astNode = this.index.getAstNode(modelElement) as AstNode | undefined;
        if (!astNode || !this.modelState.languageServices.shared.AstReflection.isInstance(astNode, Class)) {
            return undefined;
        }
        return astNode as ClassType;
    }

    /**
     * Resolves an element ID to the class name, allowing imported classes.
     * Returns undefined if the element is not a class node at all.
     */
    private resolveClassName(elementId: string): string | undefined {
        const modelElement = this.modelState.index.find(elementId);
        if (modelElement instanceof GClassNode) {
            return modelElement.name;
        }
        return undefined;
    }

    /**
     * Resolves an element ID to a ClassType AST node (local classes only),
     * used for collecting property names for deduplication.
     *
     * @param elementId The element ID to resolve
     * @returns The local ClassType AST node, or undefined if not a local class
     */
    private resolveLocalClassForDedup(elementId: string): ClassType | undefined {
        return this.resolveLocalClass(elementId);
    }

    /**
     * Extracts the edge creation type from the client-supplied context.
     * Falls back to {@link EdgeCreationType.UNIDIRECTIONAL} if no type is provided.
     *
     * @param context The client-supplied context object, expected to contain an optional edgeType field
     * @returns The resolved edge creation type value
     */
    private extractEdgeType(context: unknown): EdgeCreationTypeValue {
        const ctx = context as MetamodelEdgeCreationContext | undefined;
        return ctx?.edgeType ?? EdgeCreationType.UNIDIRECTIONAL;
    }

    /**
     * Returns true for edge types that require a label on the target AST end
     * (= property on the target-class, shown near the source graphically).
     * COMPOSITION uses *-- which requires a property on the target end (= source class name).
     *
     * @param edgeType The edge creation type to check
     * @returns True if the edge type requires a label on the target AST end, false otherwise
     */
    private requiresTargetLabel(edgeType: EdgeCreationTypeValue): boolean {
        return (
            edgeType === EdgeCreationType.BIDIRECTIONAL ||
            edgeType === EdgeCreationType.COMPOSITION ||
            edgeType === EdgeCreationType.NAVIGABLE_COMPOSITION
        );
    }

    /**
     * Computes a unique property name based on a base name and a set of
     * already-used names. If the base name is taken, numerically suffixed
     * candidates are tried until a free one is found.
     *
     * The base conversion is delegated to {@link generateDefaultPropertyName} so
     * that both the create-edge flow and the change-association-end flow use the
     * same naming convention.
     *
     * @param baseName The base class name to derive the property name from
     * @param usedNames The set of already-used property names in the target class
     * @returns A unique property name derived from baseName
     */
    private computePropertyName(baseName: string, usedNames: Set<string>): string {
        const base = generateDefaultPropertyName(baseName);
        if (!usedNames.has(base)) {
            return base;
        }
        let suffix = 1;
        while (usedNames.has(`${base}${suffix}`)) {
            suffix++;
        }
        return `${base}${suffix}`;
    }

    /**
     * Collects all property names for a given class (including inherited ones
     * and association ends) into a Set for O(1) lookup.
     *
     * @param classType The ClassType AST node to collect property names for
     * @returns A Set of all property names used by the class and its ancestors
     */
    private collectPropertyNamesSet(classType: ClassType): Set<string> {
        return new Set(collectAllPropertyNames(classType, this.modelState.languageServices.shared.AstReflection));
    }

    /**
     * Builds the initial schema (before target is selected).
     *
     * @param sourceElementId The source element ID
     * @param edgeType The edge creation type
     * @param params The schema params to embed
     * @param precomputedTargetLabel Optional pre-computed label for the target property node
     * @returns The initial create-edge schema with a preview edge template
     */
    private buildInitialSchema(
        sourceElementId: string,
        edgeType: EdgeCreationTypeValue,
        params: Record<string, unknown>,
        precomputedTargetLabel: string | undefined
    ): CreateEdgeSchema {
        const edgeId = "__create-edge-schema";

        if (edgeType === EdgeCreationType.EXTENDS) {
            const edge = GInheritanceEdge.builder()
                .id(edgeId)
                .sourceId(sourceElementId)
                .targetId(sourceElementId)
                .build();
            return {
                elementTypeId: MetamodelElementType.EDGE_INHERITANCE,
                template: edge as unknown as GModelElementSchema,
                params
            };
        }

        const { sourceKind, targetKind } = this.getEndKinds(edgeType);
        const edge = GAssociationEdge.builder()
            .id(edgeId)
            .sourceId(sourceElementId)
            .targetId(sourceElementId)
            .operator(this.getOperator(edgeType))
            .sourceKind(sourceKind)
            .targetKind(targetKind)
            .meta(EdgeLayoutMetadataUtil.create())
            .build();

        if (precomputedTargetLabel !== undefined) {
            const nodeId = `${edgeId}__target-node`;
            const endNode = GAssociationPropertyNode.builder()
                .id(nodeId)
                .end("source")
                .meta(NodeLayoutMetadataUtil.create(0, 0))
                .build();
            const label = GAssociationPropertyLabel.builder()
                .id(`${nodeId}-label`)
                .text(precomputedTargetLabel)
                .build();
            endNode.children.push(label);
            edge.children.push(endNode);
        }

        return {
            elementTypeId: MetamodelElementType.EDGE_ASSOCIATION,
            template: edge as unknown as GModelElementSchema,
            params
        };
    }

    /**
     * Builds the inheritance edge schema for the target step.
     *
     * @param sourceElementId The source element ID
     * @param targetElementId The target element ID
     * @returns The create-edge schema for an inheritance (extends) edge
     */
    private buildExtendsSchema(sourceElementId: string, targetElementId: string): CreateEdgeSchema {
        const edgeId = "__create-edge-schema";
        const edge = GInheritanceEdge.builder()
            .id(edgeId)
            .sourceId(sourceElementId)
            .targetId(targetElementId)
            .meta(EdgeLayoutMetadataUtil.create())
            .build();
        return {
            elementTypeId: MetamodelElementType.EDGE_INHERITANCE,
            template: edge as unknown as GModelElementSchema,
            params: { edgeType: EdgeCreationType.EXTENDS }
        };
    }

    /**
     * Builds the association edge schema for the target step.
     * Computes property labels and deduplicates them against the class hierarchies.
     *
     * @param sourceElementId The source element ID
     * @param targetElementId The target element ID
     * @param edgeType The edge creation type
     * @param sourceClass The resolved source ClassType for property name deduplication
     * @param targetClassName The target class name for label computation
     * @returns The create-edge schema with computed property labels and an edge template
     */
    private buildAssociationSchema(
        sourceElementId: string,
        targetElementId: string,
        edgeType: EdgeCreationTypeValue,
        sourceClass: ClassType,
        targetClassName: string
    ): CreateEdgeSchema {
        const edgeId = "__create-edge-schema";

        const sourceNames = this.collectPropertyNamesSet(sourceClass);
        const targetClassForDedup = this.resolveLocalClassForDedup(targetElementId);
        const targetNames = targetClassForDedup ? this.collectPropertyNamesSet(targetClassForDedup) : new Set<string>();

        let sourceLabel: string | undefined;
        let targetLabel: string | undefined;

        const needsSource = this.requiresSourceLabel(edgeType);
        const needsTarget = this.requiresTargetLabel(edgeType);

        if (needsSource) {
            sourceLabel = this.computePropertyName(targetClassName, sourceNames);
        }
        if (needsTarget && sourceClass.name) {
            if (sourceLabel !== undefined && sourceElementId === targetElementId) {
                targetNames.add(sourceLabel);
            }
            targetLabel = this.computePropertyName(sourceClass.name, targetNames);
        }

        const params: Record<string, unknown> = { edgeType };
        if (sourceLabel !== undefined) {
            params.sourceLabel = sourceLabel;
        }
        if (targetLabel !== undefined) {
            params.targetLabel = targetLabel;
        }

        const { sourceKind, targetKind } = this.getEndKinds(edgeType);
        const edge = GAssociationEdge.builder()
            .id(edgeId)
            .sourceId(sourceElementId)
            .targetId(targetElementId)
            .operator(this.getOperator(edgeType))
            .sourceKind(sourceKind)
            .targetKind(targetKind)
            .meta(EdgeLayoutMetadataUtil.create())
            .build();

        if (sourceLabel !== undefined) {
            const nodeId = `${edgeId}__source-node`;
            const endNode = GAssociationPropertyNode.builder()
                .id(nodeId)
                .end("target")
                .meta(NodeLayoutMetadataUtil.create(0, 0))
                .build();
            const lbl = GAssociationPropertyLabel.builder().id(`${nodeId}-label`).text(sourceLabel).build();
            endNode.children.push(lbl);
            edge.children.push(endNode);
        }

        if (targetLabel !== undefined) {
            const nodeId = `${edgeId}__target-node`;
            const endNode = GAssociationPropertyNode.builder()
                .id(nodeId)
                .end("source")
                .meta(NodeLayoutMetadataUtil.create(0, 0))
                .build();
            const lbl = GAssociationPropertyLabel.builder().id(`${nodeId}-label`).text(targetLabel).build();
            endNode.children.push(lbl);
            edge.children.push(endNode);
        }

        return {
            elementTypeId: MetamodelElementType.EDGE_ASSOCIATION,
            template: edge as unknown as GModelElementSchema,
            params
        };
    }

    /**
     * Returns true for edge types where the SOURCE end has a property.
     * COMPOSITION is excluded because with *-- the property is on the target end only.
     *
     * @param edgeType The edge creation type to check
     * @returns True if the edge type requires a label on the source AST end, false otherwise
     */
    private requiresSourceLabel(edgeType: EdgeCreationTypeValue): boolean {
        return (
            edgeType === EdgeCreationType.UNIDIRECTIONAL ||
            edgeType === EdgeCreationType.BIDIRECTIONAL ||
            edgeType === EdgeCreationType.NAVIGABLE_COMPOSITION
        );
    }

    /**
     * Returns the operator string for a given edge creation type.
     *
     * @param edgeType The edge creation type
     * @returns The string operator (e.g., "-->" for unidirectional)
     */
    private getOperator(edgeType: EdgeCreationTypeValue): string {
        switch (edgeType) {
            case EdgeCreationType.UNIDIRECTIONAL:
                return "-->";
            case EdgeCreationType.BIDIRECTIONAL:
                return "<-->";
            case EdgeCreationType.COMPOSITION:
                return "*--";
            case EdgeCreationType.NAVIGABLE_COMPOSITION:
                return "*-->";
            default:
                return "-->";
        }
    }

    /**
     * Returns the source and target {@link AssociationEndKind} values for a given edge type.
     *
     * @param edgeType The edge creation type
     * @returns An object with sourceKind and targetKind for the association ends
     */
    private getEndKinds(edgeType: EdgeCreationTypeValue): {
        sourceKind: AssociationEndKind;
        targetKind: AssociationEndKind;
    } {
        switch (edgeType) {
            case EdgeCreationType.UNIDIRECTIONAL:
                return { sourceKind: AssociationEndKind.NONE, targetKind: AssociationEndKind.ARROW };
            case EdgeCreationType.BIDIRECTIONAL:
                return { sourceKind: AssociationEndKind.ARROW, targetKind: AssociationEndKind.ARROW };
            case EdgeCreationType.COMPOSITION:
                return { sourceKind: AssociationEndKind.COMPOSITION, targetKind: AssociationEndKind.NONE };
            case EdgeCreationType.NAVIGABLE_COMPOSITION:
                return { sourceKind: AssociationEndKind.COMPOSITION, targetKind: AssociationEndKind.ARROW };
            default:
                return { sourceKind: AssociationEndKind.NONE, targetKind: AssociationEndKind.ARROW };
        }
    }
}
