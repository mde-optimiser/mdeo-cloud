import type { AstNode } from "langium";
import type { ModelIdRegistry } from "./modelIdRegistry.js";

/**
 * Symbol for dependency injection of ModelIdProvider.
 */
export const ModelIdProvider = Symbol("ModelIdProvider");

/**
 * Interface for providing unique IDs for AST nodes.
 * Implementations should generate IDs based on semantic information
 * from the AST node, ensuring consistency across model transformations.
 */
export interface ModelIdProvider {
    /**
     * Gets the name used for ID generation for the given AST node.
     * The name is a pure semantic name, not prefixed with the node type.
     * The registry is provided so that parent names can be looked up.
     *
     * @param node The AST node to get the name for
     * @param registry The model ID registry for looking up cached names of other nodes
     * @returns The name string or undefined if the node type is not supported
     */
    getName(node: AstNode, registry: ModelIdRegistry): string | undefined;

    /**
     * Gets called with the root node to provide additional (root) nodes to analyze and index
     *
     * @param node The root AST Node
     * @returns An array of additional AST nodes
     */
    getAdditional(node: AstNode): AstNode[];
}

/**
 * Base class for ModelIdProvider implementations.
 * Provides a default getAdditional implementation.
 */
export abstract class BaseModelIdProvider implements ModelIdProvider {
    /**
     * Gets the name used for ID generation for the given AST node.
     *
     * @param node The AST node to get the name for
     * @param registry The model ID registry for looking up cached names of other nodes
     * @returns The name string or undefined if not applicable
     */
    abstract getName(node: AstNode, registry: ModelIdRegistry): string | undefined;

    getAdditional(_node: AstNode): AstNode[] {
        return [];
    }

    /**
     * Escapes a string for safe use as part of a diagram element ID.
     * Only characters in [a-zA-Z0-9_-] are kept; every other character is
     * replaced with a single underscore `_`.  This keeps IDs safe for use
     * in CSS selectors, URLs, and similar contexts.
     *
     * Note: this may produce ID collisions when two different raw strings
     * collapse to the same escaped form; that is an accepted trade-off.
     *
     * @param s The raw string to escape
     * @returns The escaped string safe for use in IDs
     */
    static escapeIdPart(s: string): string {
        return s.replace(/[^a-zA-Z0-9_-]/g, "_");
    }
}
