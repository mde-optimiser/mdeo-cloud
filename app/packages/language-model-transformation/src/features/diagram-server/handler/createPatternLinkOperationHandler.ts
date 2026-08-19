import type { GNode, ModelState, GModelIndex, CreateEdgeResult } from "@mdeo/language-shared";
import {
    BaseCreateEdgeOperationHandler,
    sharedImport,
    DefaultModelIdRegistry,
    type ModelIdRegistry
} from "@mdeo/language-shared";
import { CreateEdgeOperation } from "@mdeo/protocol-common";
import { LinkAssociationResolver, type LinkAssociationDisambiguation } from "@mdeo/language-model";
import type { AstNode } from "langium";
import type { CompositeCstNode } from "langium";
import {
    PatternObjectInstance,
    PatternObjectInstanceReference,
    PatternObjectInstanceDelete,
    PatternLink,
    PatternLinkEnd,
    PatternModifier,
    type PatternObjectInstanceType,
    type PatternObjectInstanceReferenceType,
    type PatternObjectInstanceDeleteType,
    type PatternLinkType,
    type PatternLinkEndType,
    type PatternModifierType,
    type PatternType,
    PatternApplicationCondition,
    type PatternApplicationConditionType
} from "../../../grammar/modelTransformationTypes.js";
import type { ClassType } from "@mdeo/language-metamodel";
import { ModelTransformationElementType, PatternModifierKind } from "@mdeo/protocol-model-transformation";
import { GPatternInstanceNode } from "../model/patternInstanceNode.js";
import type { WorkspaceEdit } from "vscode-languageserver-types";

const { injectable, inject } = sharedImport("inversify");
const { ModelState: ModelStateKey, GModelIndex: GModelIndexKey } = sharedImport("@eclipse-glsp/server");
const { GrammarUtils } = sharedImport("langium");

/**
 * A container that can hold pattern links: either the match pattern itself or one of its
 * application condition blocks.
 */
type LinkContainer = PatternType | PatternApplicationConditionType;

/**
 * Parameters included in the create-edge schema for pattern links.
 * Used for both disambiguation (multiple association candidates) and
 * modifier determination (persist-persist edge case).
 */
export interface PatternLinkSchemaParams extends LinkAssociationDisambiguation {
    /**
     * The modifier to apply to the new link.
     * Only relevant when both endpoints have NONE (persist) modifier, in which
     * case the schema resolver includes the user-selected context mode here.
     */
    modifier: PatternModifierKind;
}

/**
 * Resolved information about a pattern instance visual node.
 * Abstracts over PatternObjectInstance, PatternObjectInstanceReference, and PatternObjectInstanceDelete.
 */
interface ResolvedPatternInstance {
    /**
     * The semantic name used in PatternLinkEnd references.
     */
    name: string;
    /**
     * The metamodel class of this instance.
     */
    classType: ClassType;
    /**
     * The effective modifier of this instance in the current match.
     */
    modifier: PatternModifierKind;
    /**
     * The Pattern or application condition block that contains this element.
     */
    pattern: LinkContainer;
}

/**
 * Operation handler for creating pattern links between instances in a model transformation diagram.
 *
 * Handles create-edge operations for {@link ModelTransformationElementType.EDGE_PATTERN_LINK} edges.
 * The link modifier is inferred automatically from the source and target node modifiers; for
 * persist-persist pairs the user-supplied context mode (from {@link PatternLinkSchemaParams.modifier})
 * is used instead. The link is inserted into the correct pattern using {@link insertIntoScope}.
 */
@injectable()
export class CreatePatternLinkOperationHandler extends BaseCreateEdgeOperationHandler {
    override readonly operationType = CreateEdgeOperation.KIND;
    override label = "Create pattern link";
    readonly elementTypeIds = [ModelTransformationElementType.EDGE_PATTERN_LINK];

    @inject(ModelStateKey)
    protected readonly localModelState!: ModelState;

    @inject(GModelIndexKey)
    protected readonly localIndex!: GModelIndex;

    /**
     * Creates a new pattern link between two pattern instance nodes.
     *
     * Validates that both source and target are {@link GPatternInstanceNode}s belonging to
     * the same pattern, infers the link modifier, checks for a valid metamodel association,
     * serializes the link, and inserts it into the pattern via {@link insertIntoScope}.
     *
     * Inside a condition block a link has no modifier of its own: the block says whether the
     * graph it belongs to is forbidden or required.
     *
     * @param operation The create-edge operation
     * @param sourceElement The source GNode (must be a GPatternInstanceNode)
     * @param targetElement The target GNode (must be a GPatternInstanceNode)
     * @returns The create-edge result, or undefined if the link cannot be created
     */
    protected override async createEdge(
        operation: CreateEdgeOperation,
        sourceElement: GNode,
        targetElement: GNode
    ): Promise<CreateEdgeResult | undefined> {
        if (operation.elementTypeId !== ModelTransformationElementType.EDGE_PATTERN_LINK) {
            return undefined;
        }

        if (!(sourceElement instanceof GPatternInstanceNode) || !(targetElement instanceof GPatternInstanceNode)) {
            return undefined;
        }

        const sourceAstNode = this.localIndex.getAstNode(sourceElement) as AstNode | undefined;
        const targetAstNode = this.localIndex.getAstNode(targetElement) as AstNode | undefined;
        if (!sourceAstNode || !targetAstNode) {
            return undefined;
        }

        const sourceModel = this.localModelState.sourceModel;
        const registry: ModelIdRegistry | undefined =
            sourceModel != undefined ? new DefaultModelIdRegistry(sourceModel, this.idProvider) : undefined;

        const sourceInfo = this.resolvePatternInstance(sourceElement, registry);
        const targetInfo = this.resolvePatternInstance(targetElement, registry);
        if (!sourceInfo || !targetInfo) {
            return undefined;
        }

        const container = this.resolveLinkContainer(sourceInfo.pattern, targetInfo.pattern);
        if (container == undefined) {
            return undefined;
        }
        const reflection = this.localModelState.languageServices.shared.AstReflection;
        const insideCondition = reflection.isInstance(container, PatternApplicationCondition);

        const params = operation.schema.params as PatternLinkSchemaParams;
        const modifier = insideCondition ? PatternModifierKind.NONE : this.modifierStringToKind(params.modifier);

        const associationResolver = new LinkAssociationResolver(
            this.localModelState.languageServices.shared.AstReflection
        );
        const candidates = associationResolver.findCandidates(sourceInfo.classType, targetInfo.classType);
        if (candidates.length === 0) {
            return undefined;
        }

        const candidate = associationResolver.selectCandidate(candidates, params);
        if (!candidate) {
            return undefined;
        }

        const sourceProperty = params?.sourceProperty;
        const targetProperty = params?.targetProperty;

        const modifierNode: PatternModifierType | undefined =
            modifier !== PatternModifierKind.NONE
                ? { $type: PatternModifier.name, modifier: modifier as string }
                : undefined;

        const linkNode: PatternLinkType = {
            $type: PatternLink.name,
            modifier: modifierNode,
            source: {
                $type: PatternLinkEnd.name,
                object: { $refText: sourceInfo.name },
                property: sourceProperty ? { $refText: sourceProperty } : undefined
            } as PatternLinkEndType,
            target: {
                $type: PatternLinkEnd.name,
                object: { $refText: targetInfo.name },
                property: targetProperty ? { $refText: targetProperty } : undefined
            } as PatternLinkEndType
        };

        const linkText = await this.serializeNode(linkNode);
        const workspaceEdit = this.insertLinkIntoPattern(container, linkText);

        return {
            edgeType: ModelTransformationElementType.EDGE_PATTERN_LINK,
            workspaceEdit,
            insertSpecification: {
                container,
                property: "elements",
                elements: [linkNode]
            },
            insertedElement: {
                element: linkNode,
                edge: {
                    type: ModelTransformationElementType.EDGE_PATTERN_LINK,
                    from: sourceAstNode,
                    to: targetAstNode
                }
            }
        };
    }

    /**
     * Resolves a GPatternInstanceNode to its underlying AST pattern instance information.
     *
     * Works for all three backing AST node types:
     * - {@link PatternObjectInstance}: direct instance in this match
     * - {@link PatternObjectInstanceReference}: a reference to an instance from a prior match
     * - {@link PatternObjectInstanceDelete}: a deletion of an instance from a prior match
     *
     * Uses the provided {@link ModelIdRegistry} to look up instance names consistently
     * with the server's IdProvider, so the generated edge ID is guaranteed to match.
     *
     * @param node The GPatternInstanceNode to resolve
     * @param registry The model ID registry for consistent name resolution (may be undefined)
     * @returns Resolved instance information, or undefined if resolution fails
     */
    private resolvePatternInstance(
        node: GPatternInstanceNode,
        registry: ModelIdRegistry | undefined
    ): ResolvedPatternInstance | undefined {
        const astNode = this.localIndex.getAstNode(node) as AstNode | undefined;
        if (!astNode) {
            return undefined;
        }

        const reflection = this.localModelState.languageServices.shared.AstReflection;

        if (reflection.isInstance(astNode, PatternObjectInstance)) {
            const instance = astNode as PatternObjectInstanceType;
            const name = registry?.getName(instance as AstNode) ?? instance.name;
            const classType = instance.class?.ref as ClassType | undefined;
            const modifier = this.modifierStringToKind(instance.modifier?.modifier);
            const pattern = instance.$container as LinkContainer;
            if (!name || !classType) return undefined;
            return { name, classType, modifier, pattern };
        }

        if (reflection.isInstance(astNode, PatternObjectInstanceReference)) {
            const ref = astNode as PatternObjectInstanceReferenceType;
            const instance = ref.instance?.ref;
            if (!instance) return undefined;
            const name = registry?.getName(instance as AstNode) ?? instance.name;
            const classType = instance.class?.ref as ClassType | undefined;
            const pattern = ref.$container as LinkContainer;
            if (!name || !classType) return undefined;
            return { name, classType, modifier: PatternModifierKind.NONE, pattern };
        }

        if (reflection.isInstance(astNode, PatternObjectInstanceDelete)) {
            const del = astNode as PatternObjectInstanceDeleteType;
            const instance = del.instance?.ref;
            if (!instance) return undefined;
            const name = registry?.getName(instance as AstNode) ?? instance.name;
            const classType = instance.class?.ref as ClassType | undefined;
            const pattern = del.$container as LinkContainer;
            if (!name || !classType) return undefined;
            return { name, classType, modifier: PatternModifierKind.DELETE, pattern };
        }

        return undefined;
    }

    /**
     * Determines the container a new link belongs to, given the containers of its endpoints.
     *
     * A link joins the graph both of its endpoints belong to. When one endpoint is a node of
     * an application condition and the other is a node of the enclosing match, the link is
     * part of the condition: it anchors the condition graph to the match. Endpoints living in
     * two different conditions have no common graph, so no link can be drawn between them.
     *
     * @param sourceContainer The container of the source endpoint
     * @param targetContainer The container of the target endpoint
     * @returns The container that receives the link, or `undefined` when there is none
     */
    private resolveLinkContainer(
        sourceContainer: LinkContainer,
        targetContainer: LinkContainer
    ): LinkContainer | undefined {
        if (sourceContainer === targetContainer) {
            return sourceContainer;
        }
        if (this.isConditionOf(sourceContainer, targetContainer)) {
            return sourceContainer;
        }
        if (this.isConditionOf(targetContainer, sourceContainer)) {
            return targetContainer;
        }
        return undefined;
    }

    /**
     * Checks whether [candidate] is a condition block of [pattern].
     *
     * @param candidate The potential condition block
     * @param pattern The potential enclosing pattern
     * @returns `true` when the candidate is a block declared in that pattern
     */
    private isConditionOf(candidate: LinkContainer, pattern: LinkContainer): boolean {
        const reflection = this.localModelState.languageServices.shared.AstReflection;
        return (
            reflection.isInstance(candidate, PatternApplicationCondition) &&
            (candidate as AstNode).$container === pattern
        );
    }

    /**
     * Inserts a serialized link text into the given container using {@link insertIntoScope}.
     *
     * A blank line is added before the content when the container already holds elements.
     *
     * @param container The target pattern or condition block whose CST scope receives the link
     * @param text The already-serialized link text to insert
     * @returns The workspace edit that inserts the link before the closing `}`
     * @throws {Error} If the container has no CST node or no braces
     */
    private insertLinkIntoPattern(container: LinkContainer, text: string): WorkspaceEdit {
        const containerCst = container.$cstNode as CompositeCstNode | undefined;
        if (!containerCst) {
            throw new Error("Pattern has no CST node; cannot insert link.");
        }
        const openBrace = GrammarUtils.findNodeForKeyword(containerCst, "{");
        const closeBrace = GrammarUtils.findNodeForKeyword(containerCst, "}");
        if (openBrace == undefined || closeBrace == undefined) {
            throw new Error("Pattern has no braces; cannot insert link.");
        }
        const hasContent = (container.elements?.length ?? 0) > 0;
        return this.insertIntoScope(openBrace, closeBrace, hasContent, text);
    }

    /**
     * Converts a modifier string from operation args or schema params to a {@link PatternModifierKind}.
     * Unknown or absent strings default to {@link PatternModifierKind.NONE}.
     *
     * @param modifier The raw modifier string (e.g. `"create"`, `"forbid"`)
     * @returns The corresponding {@link PatternModifierKind}
     */
    private modifierStringToKind(modifier: string | undefined): PatternModifierKind {
        switch (modifier) {
            case "create":
                return PatternModifierKind.CREATE;
            case "delete":
                return PatternModifierKind.DELETE;
            case "require":
                return PatternModifierKind.REQUIRE;
            case "forbid":
                return PatternModifierKind.FORBID;
            default:
                return PatternModifierKind.NONE;
        }
    }
}
