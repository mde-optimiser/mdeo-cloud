import type { AstNode } from "langium";
import { sharedImport } from "../sharedImport.js";
import type { ModelIdRegistry } from "./modelIdRegistry.js";
import type { GModelElement } from "@eclipse-glsp/server";

const { injectable } = sharedImport("inversify");
const { GModelIndex: BaseGModelIndex } = sharedImport("@eclipse-glsp/server");
const { AstUtils } = sharedImport("langium");

/**
 * Index for efficient lookup of graph model elements.
 * Extends the base GLSP GModelIndex implementation.
 */
@injectable()
export class GModelIndex extends BaseGModelIndex {
    /**
     * Mapping from model element IDs to their corresponding AST nodes.
     */
    protected idToAstNode = new Map<string, AstNode>();

    /**
     * Reverse mapping from AST nodes to their corresponding model element IDs.
     */
    protected astNodeToId = new Map<AstNode, string>();

    /**
     * Indexes the source model root AST nodes.
     *
     * @param root the source model root AST node
     * @param idRegistry the model ID registry to get IDs
     */
    indexSourceModelRoot(root: AstNode, idRegistry: ModelIdRegistry): void {
        this.idToAstNode.clear();
        this.astNodeToId.clear();
        for (const rootNode of idRegistry.getRootNodes(root)) {
            for (const node of AstUtils.streamAst(rootNode)) {
                if (idRegistry.hasId(node)) {
                    const id = idRegistry.getId(node);
                    this.idToAstNode.set(id, node);
                    this.astNodeToId.set(node, id);
                }
            }
        }
    }

    /**
     * Gets the AST node corresponding to the given graph model element.
     *
     * @param node the graph model element
     * @returns the corresponding AST node, or undefined if not found
     */
    getAstNode(node: GModelElement): AstNode | undefined {
        const normalizedId = this.normalizeId(node.id);
        return this.idToAstNode.get(normalizedId);
    }

    /**
     * Gets the element ID corresponding to the given AST node.
     *
     * @param node the AST node
     * @returns the corresponding element ID, or undefined if not found
     */
    getElementId(node: AstNode): string | undefined {
        return this.astNodeToId.get(node);
    }

    /**
     * Normalizes the given ID by removing any fragment identifiers.
     *
     * @param id the ID to normalize
     * @returns the normalized ID
     */
    private normalizeId(id: string): string {
        const hashIndex = id.indexOf("__");
        if (hashIndex >= 0) {
            return id.substring(0, hashIndex);
        }
        return id;
    }
}
