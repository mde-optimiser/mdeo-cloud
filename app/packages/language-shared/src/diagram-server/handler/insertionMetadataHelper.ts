import type { AstNode } from "langium";
import type { GraphMetadata, NodeMetadata, EdgeMetadata } from "../metadata.js";
import type { ModelIdProvider } from "../modelIdProvider.js";
import type { InsertSpecification } from "../modelIdInsert.js";
import type { MetadataEdits } from "./operationHandlerCommand.js";
import { computeInsertIds, buildIdChangeMetadataEdits, mergeMetadataEdits } from "../modelIdInsert.js";

/**
 * Describes the metadata for a single inserted element (node or edge).
 * The {@link element} reference must match one of the AstNodes provided
 * in the corresponding {@link InsertSpecification.elements}.
 */
export interface InsertedElementMetadata {
    /**
     * The inserted AstNode (same reference as in the InsertSpecification).
     */
    readonly element: AstNode;
    /**
     * Metadata for a node element.
     */
    readonly node?: { type: string; meta?: object };
    /**
     * Metadata for an edge element. The {@link from} and {@link to} fields are
     * AstNode references; their post-insertion ids are resolved automatically
     * by {@link computeInsertionMetadata}.
     */
    readonly edge?: { type: string; from: AstNode; to: AstNode; meta?: object };
}

/**
 * Computes complete {@link MetadataEdits} for a set of insertions by:
 * 1. Running {@link computeInsertIds} to determine new ids and detect id changes.
 * 2. Building rename edits for any pre-existing nodes/edges whose ids shifted.
 * 3. Assigning metadata to the newly inserted elements using their computed ids.
 *
 * This is the main entry point for handlers that use {@link InsertSpecification}
 * to describe model modifications, replacing manual id guessing.
 *
 * @param rootNode The current model root (before insertion)
 * @param idProvider The model id provider for id generation
 * @param insertions The insert specifications describing what is being added
 * @param insertedElements Metadata descriptors for each newly inserted element
 * @param currentMetadata The current graph metadata (for id change renames)
 * @param mapId Maps the id of an inserted element (and of the endpoints of an inserted edge) to
 *        the id its metadata is stored under, e.g. the id the element has in the diagram that
 *        is currently shown. Ids are used unchanged when omitted.
 * @returns Complete metadata edits, or `undefined` if no metadata changes are needed
 */
export function computeInsertionMetadata(
    rootNode: AstNode,
    idProvider: ModelIdProvider,
    insertions: InsertSpecification[],
    insertedElements: InsertedElementMetadata[],
    currentMetadata: GraphMetadata,
    mapId: (id: string) => string = (id) => id
): MetadataEdits | undefined {
    const { insertedIds, idChanges, resolveId: resolveInsertedId } = computeInsertIds(rootNode, idProvider, insertions);
    const resolveId = (node: AstNode): string | undefined => {
        const resolved = resolveInsertedId(node);
        return resolved != undefined ? mapId(resolved) : undefined;
    };

    const renameEdits = buildIdChangeMetadataEdits(idChanges, currentMetadata);

    const nodeEdits: Record<string, Partial<NodeMetadata>> = {};
    const edgeEdits: Record<string, Partial<EdgeMetadata>> = {};

    for (const desc of insertedElements) {
        const insertedId = insertedIds.get(desc.element);
        if (insertedId == undefined) {
            continue;
        }
        const id = mapId(insertedId);

        if (desc.node != undefined) {
            nodeEdits[id] = {
                type: desc.node.type,
                meta: desc.node.meta
            };
        }

        if (desc.edge != undefined) {
            const fromId = resolveId(desc.edge.from);
            const toId = resolveId(desc.edge.to);
            if (fromId != undefined && toId != undefined) {
                edgeEdits[id] = {
                    type: desc.edge.type,
                    from: fromId,
                    to: toId,
                    meta: desc.edge.meta
                };
            }
        }
    }

    const hasNodeEdits = Object.keys(nodeEdits).length > 0;
    const hasEdgeEdits = Object.keys(edgeEdits).length > 0;

    const insertionEdits: MetadataEdits | undefined =
        hasNodeEdits || hasEdgeEdits
            ? {
                  nodes: hasNodeEdits ? nodeEdits : undefined,
                  edges: hasEdgeEdits ? edgeEdits : undefined
              }
            : undefined;

    return mergeMetadataEdits(renameEdits, insertionEdits);
}
