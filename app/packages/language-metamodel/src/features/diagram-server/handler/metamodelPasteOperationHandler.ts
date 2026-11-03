import type { PasteOperation } from "@eclipse-glsp/protocol";
import type { Point } from "@eclipse-glsp/protocol";
import type { WorkspaceEdit } from "vscode-languageserver-types";
import type { AstNode } from "langium";
import {
    BasePasteOperationHandler,
    sharedImport,
    computeInsertionMetadata,
    type PasteInsertionResult,
    type InsertedElementMetadata,
    type InsertSpecification,
    type ReferenceResolutionContext,
    type ClipboardEdgeMetadata
} from "@mdeo/language-shared";
import { MetamodelElementType } from "@mdeo/protocol-metamodel";
import {
    Association,
    Class,
    Enum,
    type AssociationType,
    type ClassType,
    type EnumType
} from "../../../grammar/metamodelTypes.js";

import type { PartialMetaModel } from "../../../grammar/metamodelPartialTypes.js";
import { MetamodelViewState, scopeIdToView } from "../metamodelViews.js";
import { MetamodelViewEdits } from "../metamodelViewEdits.js";

const { injectable, inject } = sharedImport("inversify");

/**
 * Metamodel-specific paste operation handler.
 *
 * <p>Inserts deserialized top-level metamodel nodes (classes, enums, associations)
 * at the end of the current document. Associations whose source or target class
 * reference cannot be resolved are silently dropped. Class nodes have invalid
 * class extensions (pointing to absent classes) stripped before insertion.
 */
@injectable()
export class MetamodelPasteOperationHandler extends BasePasteOperationHandler {
    /**
     * Which view the diagram shows. Pasted classes and enums become part of it.
     */
    @inject(MetamodelViewState)
    protected viewState!: MetamodelViewState;

    /**
     * Resolves a cross-reference by searching first among co-pasted nodes and
     * then in the existing source model elements. Only Class nodes are targets of
     * references in the metamodel language.
     */
    protected override resolveReference(
        refText: string,
        { allPastedNodes }: ReferenceResolutionContext
    ): AstNode | undefined {
        const pasted = allPastedNodes.find((n) => n.$type === Class.name && (n as ClassType).name === refText);
        if (pasted) {
            return pasted;
        }
        const elements = (this.modelState.sourceModel as unknown as { elements?: AstNode[] })?.elements ?? [];
        return elements.find((e) => e.$type === Class.name && (e as ClassType).name === refText);
    }

    /**
     * Validates a pasted node after reference resolution:
     * <ul>
     *   <li>{@link Association}: dropped when either end's class reference is unresolved.</li>
     *   <li>{@link Class}: class extensions whose target is unresolved are stripped; the
     *       class itself is always kept.</li>
     *   <li>Everything else ({@link Enum}, etc.) is kept unchanged.</li>
     * </ul>
     */
    protected override validateNode(node: AstNode): AstNode | undefined {
        if (node.$type === Association.name) {
            const assoc = node as AssociationType;
            if (!assoc.source.class.ref || !assoc.target.class.ref) {
                return undefined;
            }
            return node;
        }

        if (node.$type === Class.name) {
            const classNode = node as ClassType;
            if (classNode.extensions) {
                const valid = classNode.extensions.extensions.filter((ext) => ext.class.ref !== undefined);
                if (valid.length !== classNode.extensions.extensions.length) {
                    classNode.extensions.extensions = valid;
                    if (valid.length === 0) {
                        classNode.extensions = undefined;
                    }
                }
            }
            return node;
        }

        return node;
    }

    /**
     * Inserts all pasted nodes at the end of the metamodel document and
     * generates metadata edits to position them near the paste cursor.
     */
    protected override async insertNodes(
        astNodes: AstNode[],
        _operation: PasteOperation,
        offsetPositions: Map<string, Point>,
        offsetEdgeData: ClipboardEdgeMetadata[]
    ): Promise<PasteInsertionResult | undefined> {
        const rootCstNode = this.modelState.sourceModel?.$cstNode;
        if (!rootCstNode) {
            return undefined;
        }

        const document = this.modelState.sourceModel?.$document;
        const isEmpty = document?.textDocument.getText().trim().length === 0;

        const edits: WorkspaceEdit[] = [];
        for (let i = 0; i < astNodes.length; i++) {
            const serialized = await this.serializeNode(astNodes[i] as any);
            const edit = await this.createInsertAfterNodeEdit(rootCstNode, serialized, !isEmpty || i > 0);
            edits.push(edit);
        }

        if (edits.length === 0) {
            return undefined;
        }

        const sourceModel = this.modelState.sourceModel!;
        const view = this.viewState.resolveActiveView(sourceModel as PartialMetaModel);
        if (view != undefined) {
            const members = astNodes.filter((node) => node.$type === Class.name || node.$type === Enum.name);
            const viewEdit = new MetamodelViewEdits(this.modelState).addToView(view.node, members);
            if (viewEdit != undefined) {
                edits.push(viewEdit);
            }
        }
        const insertSpec: InsertSpecification = {
            container: sourceModel,
            property: "elements",
            elements: astNodes
        };

        const insertedElements: InsertedElementMetadata[] = [];
        for (const node of astNodes) {
            if (node.$type === Class.name || node.$type === Enum.name) {
                const name = (node as ClassType | EnumType).name;
                const position = name ? offsetPositions.get(name) : undefined;
                if (position) {
                    const elementType =
                        node.$type === Class.name ? MetamodelElementType.NODE_CLASS : MetamodelElementType.NODE_ENUM;
                    insertedElements.push(this.buildNodeElementMetadata(node, elementType, position));
                }
            } else if (node.$type === Association.name) {
                const assoc = node as AssociationType;
                // Both ends are guaranteed to be resolved by validateNode.
                const edgeMeta = this.findEdgeMetadata(
                    assoc.source.class.ref!.name,
                    assoc.source.name ?? "",
                    assoc.target.class.ref!.name,
                    assoc.target.name ?? "",
                    offsetEdgeData
                );
                if (edgeMeta) {
                    insertedElements.push(
                        this.buildEdgeElementMetadata(
                            node,
                            MetamodelElementType.EDGE_ASSOCIATION,
                            assoc.source.class.ref! as AstNode,
                            assoc.target.class.ref! as AstNode,
                            edgeMeta
                        )
                    );
                }
            }
        }

        return {
            workspaceEdit: this.mergeWorkspaceEdits(edits),
            metadataEdits: computeInsertionMetadata(
                sourceModel,
                this.idProvider,
                [insertSpec],
                insertedElements,
                this.modelState.metadata,
                (id) => (view != undefined ? scopeIdToView(id, view.key) : id)
            )
        };
    }
}
