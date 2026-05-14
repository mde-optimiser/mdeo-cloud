import type { CreateEdgeSchema } from "@mdeo/protocol-common";
import { ModelElementType } from "@mdeo/protocol-model";
import {
    sharedImport,
    CreateEdgeSchemaResolver,
    EdgeLayoutMetadataUtil,
    NodeLayoutMetadataUtil,
    type ModelState,
    type GModelIndex,
    type InitialCreateEdgeSchemaRequest,
    type TargetCreateEdgeSchemaRequest
} from "@mdeo/language-shared";
import type { PartialObjectInstance } from "../../grammar/modelPartialTypes.js";
import { ObjectInstance } from "../../grammar/modelTypes.js";
import type { AstNode } from "langium";
import { LinkAssociationResolver } from "./linkAssociationResolver.js";
import { GLinkEdge } from "./model/linkEdge.js";
import { GLinkEndNode } from "./model/linkEndNode.js";
import { GLinkEndLabel } from "./model/linkEndLabel.js";
import type { GModelElementSchema } from "@eclipse-glsp/protocol";

const { injectable, inject } = sharedImport("inversify");
const { ModelState: ModelStateKey, GModelIndex: GModelIndexKey } = sharedImport("@eclipse-glsp/server");

/**
 * Model-language implementation that resolves create-edge schemas using
 * valid associations between source and target object classes.
 */
@injectable()
export class ModelCreateEdgeSchemaResolver extends CreateEdgeSchemaResolver {
    @inject(ModelStateKey)
    protected readonly modelState!: ModelState;

    @inject(GModelIndexKey)
    protected readonly index!: GModelIndex;

    override async getInitialSchema(request: InitialCreateEdgeSchemaRequest): Promise<CreateEdgeSchema | undefined> {
        const sourceObject = this.resolveObjectInstanceByElementId(request.sourceElementId);
        if (sourceObject == undefined) {
            return undefined;
        }

        const sourceClass = sourceObject.class?.ref;
        if (sourceClass == undefined) {
            return undefined;
        }

        const associationResolver = new LinkAssociationResolver(this.modelState.languageServices.shared.AstReflection);
        if (!associationResolver.hasAnyAssociation(sourceClass)) {
            return undefined;
        }

        return this.buildSchema(request.sourceElementId, undefined, {}, undefined, sourceClass.name);
    }

    override async getTargetSchema(request: TargetCreateEdgeSchemaRequest): Promise<CreateEdgeSchema | undefined> {
        const sourceObject = this.resolveObjectInstanceByElementId(request.sourceElementId);
        const targetObject = this.resolveObjectInstanceByElementId(request.targetElementId);
        if (sourceObject == undefined || targetObject == undefined) {
            return undefined;
        }

        const sourceClass = sourceObject.class?.ref;
        const targetClass = targetObject.class?.ref;
        if (sourceClass == undefined || targetClass == undefined) {
            return undefined;
        }

        const associationResolver = new LinkAssociationResolver(this.modelState.languageServices.shared.AstReflection);
        const candidates = associationResolver.findCandidates(sourceClass, targetClass);
        if (candidates.length === 0) {
            return undefined;
        }

        if (candidates.length === 1) {
            const candidate = candidates[0];
            const assocSourceClass = candidate.sourceEnd.class?.ref?.name;
            const assocTargetClass = candidate.targetEnd.class?.ref?.name;
            return this.buildSchema(
                request.sourceElementId,
                request.targetElementId,
                {},
                undefined,
                assocSourceClass,
                assocTargetClass
            );
        }

        const selected = candidates.find(
            (candidate) => associationResolver.choosePreferredLabel(candidate) != undefined
        );

        if (selected == undefined) {
            return undefined;
        }

        const label = associationResolver.choosePreferredLabel(selected);
        if (label == undefined) {
            return undefined;
        }

        const params: Record<string, unknown> = {};
        if (label.end === "source") {
            params.sourceProperty = label.text;
        } else {
            params.targetProperty = label.text;
        }

        const assocSourceClass = selected.sourceEnd.class?.ref?.name;
        const assocTargetClass = selected.targetEnd.class?.ref?.name;
        return this.buildSchema(
            request.sourceElementId,
            request.targetElementId,
            params,
            label,
            assocSourceClass,
            assocTargetClass
        );
    }

    /**
     * Builds a create-edge schema using GLSP model builders.
     *
     * The edge and its child end-nodes are constructed via the same builder API
     * used by the GModel factory, ensuring every node carries its required
     * {@link NodeLayoutMetadata} (position 0, 0) so the client can render the
     * feedback edge without missing metadata errors.
     *
     * @param sourceElementId The source element ID used by the feedback edge
     * @param targetElementId Optional target element ID used when snapped to a target
     * @param params Backend parameters persisted in the schema
     * @param label Optional link-end label descriptor for rendering
     * @returns A schema that can be used for feedback rendering and final creation
     */
    protected buildSchema(
        sourceElementId: string,
        targetElementId: string | undefined,
        params: Record<string, unknown>,
        label: { end: "source" | "target"; text: string } | undefined,
        sourceClass?: string,
        targetClass?: string
    ): CreateEdgeSchema {
        const edgeId = "__create-edge-schema";

        const edgeBuilder = GLinkEdge.builder()
            .id(edgeId)
            .sourceId(sourceElementId)
            .targetId(targetElementId ?? sourceElementId)
            .meta(EdgeLayoutMetadataUtil.create());

        if (sourceClass != undefined) {
            edgeBuilder.sourceClass(sourceClass);
        }
        if (targetClass != undefined) {
            edgeBuilder.targetClass(targetClass);
        }

        const edge = edgeBuilder.build();

        if (label != undefined) {
            const nodeId = `${edgeId}__${label.end}-node`;

            const endNode = GLinkEndNode.builder()
                .id(nodeId)
                .end(label.end === "source" ? "target" : "source")
                .meta(NodeLayoutMetadataUtil.create(0, 0))
                .build();

            const endLabel = GLinkEndLabel.builder()
                .id(`${edgeId}__${label.end}-label`)
                .text(label.text)
                .readonly(true)
                .build();

            endNode.children.push(endLabel);
            edge.children.push(endNode);
        }

        return {
            elementTypeId: ModelElementType.EDGE_LINK,
            template: edge as unknown as GModelElementSchema,
            params
        };
    }

    /**
     * Resolves a model element ID to its ObjectInstance AST node.
     *
     * @param elementId The model element ID
     * @returns The resolved object instance, or undefined when resolution fails
     */
    protected resolveObjectInstanceByElementId(elementId: string): PartialObjectInstance | undefined {
        const modelElement = this.modelState.index.find(elementId);
        if (modelElement == undefined) {
            return undefined;
        }
        const astNode = this.index.getAstNode(modelElement) as AstNode | undefined;
        if (
            astNode == undefined ||
            !this.modelState.languageServices.shared.AstReflection.isInstance(astNode, ObjectInstance)
        ) {
            return undefined;
        }
        return astNode as PartialObjectInstance;
    }
}
