import type { GEdge } from "@mdeo/language-shared";
import {
    BaseReconnectEdgeOperationHandler,
    sharedImport,
    DefaultModelIdRegistry,
    ModelIdProvider as ModelIdProviderKey,
    type ModelIdProvider,
    type ModelIdRegistry,
    type ReconnectEndpoints,
    type ReconnectEdgeResult
} from "@mdeo/language-shared";
import type { ReconnectEdgeOperation } from "@mdeo/protocol-common";
import type { AstNode } from "langium";
import type { WorkspaceEdit } from "vscode-languageserver-types";
import {
    PatternLink,
    PatternObjectInstance,
    PatternObjectInstanceReference,
    PatternObjectInstanceDelete,
    type PatternLinkType,
    type PatternObjectInstanceType,
    type PatternObjectInstanceReferenceType,
    type PatternObjectInstanceDeleteType
} from "../../../grammar/modelTransformationTypes.js";
import { ModelTransformationElementType } from "@mdeo/protocol-model-transformation";
import { GPatternLinkEdge } from "../model/patternLinkEdge.js";
import { findContainingCondition, findContainingPattern } from "../modelTransformationPatternUtils.js";

const { injectable, inject } = sharedImport("inversify");
const { GrammarUtils } = sharedImport("langium");

/**
 * Operation handler for reconnecting pattern link edges in a model transformation diagram.
 *
 * When a pattern link edge is reconnected (source or target changes), the handler
 * updates the corresponding {@link PatternLinkEnd.object} reference in the source file
 * and computes the new edge ID. Only reconnections within the same pattern are supported.
 */
@injectable()
export class ModelTransformationReconnectEdgeOperationHandler extends BaseReconnectEdgeOperationHandler {
    @inject(ModelIdProviderKey)
    protected idProvider!: ModelIdProvider;
    override async createReconnectEdit(
        node: AstNode,
        operation: ReconnectEdgeOperation,
        edge: GEdge,
        endpoints: ReconnectEndpoints
    ): Promise<ReconnectEdgeResult | undefined> {
        if (edge.type === ModelTransformationElementType.EDGE_PATTERN_LINK) {
            return await this.handlePatternLinkReconnect(node, endpoints, edge);
        }
        return undefined;
    }

    /**
     * Handles reconnection of a pattern link edge.
     *
     * Validates that the new endpoints are in the same pattern as the link,
     * replaces the object reference(s) in the CST, and returns the new edge ID.
     * Builds a {@link DefaultModelIdRegistry} from the current source model to ensure
     * the computed edge ID matches the server's ID provider output.
     *
     * @param node The AST node for the edge being reconnected
     * @param endpoints The old and new source/target GModel nodes
     * @param edge The GEdge being reconnected
     * @returns The reconnect result or undefined if reconnection is not valid
     */
    private async handlePatternLinkReconnect(
        node: AstNode,
        endpoints: ReconnectEndpoints,
        edge: GEdge
    ): Promise<ReconnectEdgeResult | undefined> {
        if (!this.reflection.isInstance(node, PatternLink)) {
            return undefined;
        }

        const link = node as PatternLinkType;
        const sourceChanged = endpoints.oldSource.id !== endpoints.newSource.id;
        const targetChanged = endpoints.oldTarget.id !== endpoints.newTarget.id;

        if (!sourceChanged && !targetChanged) {
            return undefined;
        }

        const sourceModel = this.modelState.sourceModel;
        if (sourceModel == undefined) {
            return undefined;
        }
        const registry = new DefaultModelIdRegistry(sourceModel, this.idProvider);

        const edits: WorkspaceEdit[] = [];

        let resolvedNewSourceAst: AstNode | undefined;
        if (sourceChanged) {
            resolvedNewSourceAst = this.index.getAstNode(endpoints.newSource);
            if (resolvedNewSourceAst == undefined) {
                return undefined;
            }
            if (!this.inSamePattern(link, resolvedNewSourceAst)) {
                return undefined;
            }
            const edit = await this.createLinkEndEdit(link, "source", resolvedNewSourceAst, registry);
            if (edit == undefined) {
                return undefined;
            }
            edits.push(edit);
        }

        let resolvedNewTargetAst: AstNode | undefined;
        if (targetChanged) {
            resolvedNewTargetAst = this.index.getAstNode(endpoints.newTarget);
            if (resolvedNewTargetAst == undefined) {
                return undefined;
            }
            if (!this.inSamePattern(link, resolvedNewTargetAst)) {
                return undefined;
            }
            const edit = await this.createLinkEndEdit(link, "target", resolvedNewTargetAst, registry);
            if (edit == undefined) {
                return undefined;
            }
            edits.push(edit);
        }

        const patternLinkEdge = edge instanceof GPatternLinkEdge ? edge : undefined;
        const newEdgeId = this.computeNewEdgeId(
            link,
            sourceChanged,
            targetChanged,
            patternLinkEdge,
            resolvedNewSourceAst,
            resolvedNewTargetAst,
            registry
        );

        return {
            newEdgeId,
            workspaceEdit: this.mergeWorkspaceEdits(edits)
        };
    }

    /**
     * Creates a workspace edit that replaces the object reference in a pattern link end.
     * Uses the registry to look up the correct instance name to write into the source.
     *
     * @param link The PatternLink AST node
     * @param endType Whether to update the source or target end
     * @param newNode The new GModel node whose instance name should be used
     * @param registry The model ID registry for resolving instance names
     * @returns The workspace edit or undefined if the CST node cannot be found
     */
    private async createLinkEndEdit(
        link: PatternLinkType,
        endType: "source" | "target",
        newNode: AstNode,
        registry: ModelIdRegistry
    ): Promise<WorkspaceEdit | undefined> {
        const linkEnd = endType === "source" ? link.source : link.target;
        if (linkEnd == undefined) {
            return undefined;
        }

        const objectRefNode = GrammarUtils.findNodeForProperty(linkEnd.$cstNode, "object");
        if (objectRefNode == undefined) {
            return undefined;
        }

        const newInstanceName = this.getLinkedInstanceName(newNode, registry);
        if (newInstanceName == undefined) {
            return undefined;
        }

        return await this.replaceCstNode(objectRefNode, newInstanceName);
    }

    /**
     * Resolves any of the three pattern instance node types to the underlying
     * {@link PatternObjectInstance} it represents.
     *
     * For a {@link PatternObjectInstance} this is the node itself. For
     * {@link PatternObjectInstanceReference} and {@link PatternObjectInstanceDelete}
     * it is the original instance they wrap.
     *
     * @param node An AST node that should be one of the three instance node types
     * @returns The underlying PatternObjectInstance, or undefined if not resolvable
     */
    private resolveLinkedInstance(node: AstNode): PatternObjectInstanceType | undefined {
        if (this.reflection.isInstance(node, PatternObjectInstance)) {
            return node as PatternObjectInstanceType;
        }
        if (this.reflection.isInstance(node, PatternObjectInstanceReference)) {
            return (node as PatternObjectInstanceReferenceType).instance?.ref;
        }
        if (this.reflection.isInstance(node, PatternObjectInstanceDelete)) {
            return (node as PatternObjectInstanceDeleteType).instance?.ref;
        }
        return undefined;
    }

    /**
     * Gets the name used in {@link PatternLinkEnd.object} references for the given node.
     *
     * Resolves to the underlying {@link PatternObjectInstance} and looks up its name in the
     * registry, ensuring the result matches the server IdProvider's name generation.
     *
     * @param node An instance AST node (PatternObjectInstance, Reference, or Delete)
     * @param registry The model ID registry for consistent name lookup
     * @returns The instance name, or undefined if the node cannot be resolved
     */
    private getLinkedInstanceName(node: AstNode, registry: ModelIdRegistry): string | undefined {
        const instance = this.resolveLinkedInstance(node);
        if (instance == undefined) {
            return undefined;
        }
        return registry.getName(instance as AstNode) ?? instance.name;
    }

    /**
     * Checks that the given AST node is visible from the link's container.
     *
     * Cross-pattern reconnection is not supported. A link of an application condition block may
     * additionally reach a node of the enclosing match - that is how a condition is anchored to
     * the pattern it constrains - while a link of the match may not reach into a block, whose
     * names belong to the block's own graph.
     *
     * @param link The PatternLink whose container is the reference
     * @param node The endpoint AST node to check
     * @returns True if the node can be named by the link
     */
    private inSamePattern(link: PatternLinkType, node: AstNode): boolean {
        if (link.$container === node.$container) {
            return true;
        }
        const pattern = findContainingPattern(link, this.reflection);
        if (pattern == undefined || pattern !== findContainingPattern(node, this.reflection)) {
            return false;
        }
        return findContainingCondition(node, this.reflection) == undefined;
    }

    /**
     * Computes the new edge ID after reconnection using the stable ID format
     * {@code PatternLink_<sourceEnd>--<targetEnd>}.
     *
     * Uses the registry to resolve instance names consistently with the server's IdProvider.
     * For each end, the underlying {@link PatternObjectInstance} is resolved and its
     * registry name is used, so ref/delete visual nodes and their source instances
     * produce the same link ID.
     *
     * @param link The original PatternLink AST node
     * @param sourceChanged Whether the source endpoint changed
     * @param targetChanged Whether the target endpoint changed
     * @param edge The original GPatternLinkEdge (may be undefined for safety)
     * @param resolvedNewSourceAst The new source AST node (if source changed)
     * @param resolvedNewTargetAst The new target AST node (if target changed)
     * @param registry The model ID registry for consistent name lookup
     * @returns The new edge ID
     */
    private computeNewEdgeId(
        link: PatternLinkType,
        sourceChanged: boolean,
        targetChanged: boolean,
        edge: GPatternLinkEdge | undefined,
        resolvedNewSourceAst: AstNode | undefined,
        resolvedNewTargetAst: AstNode | undefined,
        registry: ModelIdRegistry
    ): string {
        const sourceProperty = edge?.sourceProperty;
        const targetProperty = edge?.targetProperty;

        let newSourceInstName: string;
        if (sourceChanged && resolvedNewSourceAst != undefined) {
            newSourceInstName = this.getLinkedInstanceName(resolvedNewSourceAst, registry) ?? "unresolved";
        } else {
            const sourceInst = link.source?.object?.ref as PatternObjectInstanceType | undefined;
            newSourceInstName =
                sourceInst != undefined
                    ? (registry.getName(sourceInst as AstNode) ?? sourceInst.name ?? "unresolved")
                    : (link.source?.object?.$refText ?? "unresolved");
        }

        let newTargetInstName: string;
        if (targetChanged && resolvedNewTargetAst != undefined) {
            newTargetInstName = this.getLinkedInstanceName(resolvedNewTargetAst, registry) ?? "unresolved";
        } else {
            const targetInst = link.target?.object?.ref as PatternObjectInstanceType | undefined;
            newTargetInstName =
                targetInst != undefined
                    ? (registry.getName(targetInst as AstNode) ?? targetInst.name ?? "unresolved")
                    : (link.target?.object?.$refText ?? "unresolved");
        }

        const sourceEnd = sourceProperty ? `${newSourceInstName}_${sourceProperty}` : newSourceInstName;
        const targetEnd = targetProperty ? `${newTargetInstName}_${targetProperty}` : newTargetInstName;

        return `${PatternLink.name}_${sourceEnd}--${targetEnd}`;
    }
}
