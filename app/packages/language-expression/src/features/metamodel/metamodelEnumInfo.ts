/**
 * Captures enum information extracted from a metamodel.
 */

import type { AstNode } from "langium";

export interface MetamodelEnumInfo {
    /**
     * The simple name of the enum
     */
    name: string;
    /**
     * The package in which the enum is defined (e.g. `"enum/path/to/file.mm"`)
     */
    package: string;
    /**
     * The container package for this enum (e.g. `"enum-container/path/to/file.mm"`).
     * Derived directly from {@link package} by replacing the `enum/` prefix with `enum-container`.
     */
    containerPackage: string;
    /**
     * The enum entries with their names and optional source AST nodes.
     * The `languageNode` is present when the entry was extracted from a metamodel AST node
     * and enables LSP rename / find-references for individual enum literals.
     */
    entries: { name: string; languageNode?: AstNode }[];
    /**
     * Optional reference to the source metamodel AST node that defines this enum.
     * Used for creating reference descriptions for LSP rename and find-references.
     */
    languageNode?: AstNode;
}
