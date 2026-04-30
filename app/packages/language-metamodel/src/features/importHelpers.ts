import type {
    AstNodeDescription,
    AstNodeDescriptionProvider,
    IndexManager,
    LangiumDocument,
    LangiumDocuments,
    Scope
} from "langium";
import {
    Class,
    Enum,
    Association,
    type MetaModelType,
    type ClassType,
    type EnumType,
    type AssociationType,
    type FileImportType
} from "../grammar/metamodelTypes.js";
import { sharedImport } from "@mdeo/language-shared";
import { resolveRelativePath } from "@mdeo/language-shared";

const { StreamScope, stream, EMPTY_SCOPE } = sharedImport("langium");

/**
 * Represents the collected entities from a metamodel file and its imports.
 */
export interface MetamodelExports {
    /**
     * All accessible classes (local + imported)
     */
    classes: Set<ClassType>;
    /**
     * All accessible enums (local + imported)
     */
    enums: Set<EnumType>;
    /**
     * All accessible associations (local + imported)
     */
    associations: Set<AssociationType>;
}

/**
 * Collects all imported MetamodelType nodes from a document and its imports recursively.
 * Handles import cycles by tracking visited files.
 *
 * @param document The starting metamodel document
 * @param documents The Langium documents service for resolving imported files
 * @returns Array of all MetamodelType nodes (deduplicated by document URI)
 */
export function collectImportedMetamodels(document: LangiumDocument, documents: LangiumDocuments): MetaModelType[] {
    const visited = new Set<string>();
    const metamodels: MetaModelType[] = [];

    collectMetamodelsRecursively(document, documents, visited, metamodels);
    metamodels.shift();

    return metamodels;
}

/**
 * Recursively collects MetamodelType nodes from a document and its imports.
 * Uses a visited set to prevent infinite loops on circular imports.
 *
 * @param document The current document to process
 * @param documents Langium documents service for file resolution
 * @param visited Set of visited document URIs
 * @param metamodels Array to collect MetamodelType nodes
 */
function collectMetamodelsRecursively(
    document: LangiumDocument,
    documents: LangiumDocuments,
    visited: Set<string>,
    metamodels: MetaModelType[]
): void {
    const uriString = document.uri.toString();
    if (visited.has(uriString)) {
        return;
    }
    visited.add(uriString);

    const metamodel = document.parseResult.value as MetaModelType;
    if (metamodel == undefined) {
        return;
    }

    metamodels.push(metamodel);

    for (const importStatement of metamodel.imports ?? []) {
        const importedDoc = resolveImportedDocument(document, importStatement, documents);
        if (importedDoc != undefined) {
            collectMetamodelsRecursively(importedDoc, documents, visited, metamodels);
        }
    }
}

/**
 * Extracts all classes, enums, and associations from a collection of MetamodelType nodes.
 * Deduplicates entities by their unique key (document path + name).
 *
 * @param metamodels Array of MetamodelType nodes to extract entities from
 * @returns MetamodelExports containing all classes, enums, and associations
 */
export function extractEntitiesFromMetamodels(metamodels: MetaModelType[]): MetamodelExports {
    const classes = new Set<ClassType>();
    const enums = new Set<EnumType>();
    const associations = new Set<AssociationType>();

    for (const metamodel of metamodels) {
        for (const element of metamodel.elements ?? []) {
            if (element.$type === Class.name) {
                const classEntity = element as ClassType;
                classes.add(classEntity);
            } else if (element.$type === Enum.name) {
                const enumEntity = element as EnumType;
                enums.add(enumEntity);
            } else if (element.$type === Association.name) {
                const association = element as AssociationType;
                associations.add(association);
            }
        }
    }

    return { classes, enums, associations };
}

/**
 * Creates AST node descriptions for the exported entities.
 * This is used for scoping and reference resolution.
 *
 * @param exports The metamodel exports containing classes and enums
 * @param descriptionProvider The provider for creating AST node descriptions
 * @returns Array of AST node descriptions
 */
export function createDescriptionsFromExports(
    exports: MetamodelExports,
    descriptionProvider: AstNodeDescriptionProvider
): AstNodeDescription[] {
    const descriptions: AstNodeDescription[] = [];

    for (const classEntity of exports.classes.values()) {
        if (classEntity.name != undefined) {
            descriptions.push(descriptionProvider.createDescription(classEntity, classEntity.name));
        }
    }

    for (const enumEntity of exports.enums.values()) {
        if (enumEntity.name != undefined) {
            descriptions.push(descriptionProvider.createDescription(enumEntity, enumEntity.name));
        }
    }

    return descriptions;
}

/**
 * Collects all exported entities from a metamodel file, including transitively imported entities.
 * Handles import cycles by tracking visited files.
 *
 * @param document The starting metamodel document
 * @param documents The Langium documents service for resolving imported files
 * @returns MetamodelExports containing all accessible classes and enums
 */
export function getExportedEntitiesFromMetamodelFile(
    document: LangiumDocument,
    documents: LangiumDocuments
): MetamodelExports {
    const metamodels = collectImportedMetamodels(document, documents);
    return extractEntitiesFromMetamodels([document.parseResult.value as MetaModelType, ...metamodels]);
}

/**
 * Represents metamodel exports grouped by their source document's absolute path.
 */
export interface MetamodelExportsByPath {
    /**
     * The absolute file path (document.uri.path) for this set of entities.
     */
    absolutePath: string;
    /**
     * Classes defined in this specific file (not from imports).
     */
    classes: ClassType[];
    /**
     * Enums defined in this specific file (not from imports).
     */
    enums: EnumType[];
    /**
     * Associations defined in this specific file (not from imports).
     */
    associations: AssociationType[];
}

/**
 * Collects entities from a metamodel file and all its imports, grouped by source document path.
 * Each entry in the returned array contains the entities from a single document file.
 *
 * @param document The starting metamodel document
 * @param documents The Langium documents service for resolving imported files
 * @returns Array of MetamodelExportsByPath, one per document (main + all imports)
 */
export function getExportedEntitiesByPath(
    document: LangiumDocument,
    documents: LangiumDocuments
): MetamodelExportsByPath[] {
    const result: MetamodelExportsByPath[] = [];
    const visited = new Set<string>();

    collectEntitiesByPathRecursively(document, documents, visited, result);

    return result;
}

/**
 * Recursively collects entities from a document and its imports, tracking source paths.
 *
 * @param document The current document to process
 * @param documents Langium documents service for file resolution
 * @param visited Set of visited document URIs
 * @param result Array to collect exports by path
 */
function collectEntitiesByPathRecursively(
    document: LangiumDocument,
    documents: LangiumDocuments,
    visited: Set<string>,
    result: MetamodelExportsByPath[]
): void {
    const uriString = document.uri.toString();
    if (visited.has(uriString)) {
        return;
    }
    visited.add(uriString);

    const metamodel = document.parseResult?.value as MetaModelType | undefined;
    if (metamodel == undefined) {
        return;
    }

    const classes: ClassType[] = [];
    const enums: EnumType[] = [];
    const associations: AssociationType[] = [];

    for (const element of metamodel.elements ?? []) {
        if (element.$type === Class.name) {
            classes.push(element as ClassType);
        } else if (element.$type === Enum.name) {
            enums.push(element as EnumType);
        } else if (element.$type === Association.name) {
            associations.push(element as AssociationType);
        }
    }

    result.push({
        absolutePath: document.uri.path,
        classes,
        enums,
        associations
    });

    for (const importStatement of metamodel.imports ?? []) {
        const importedDoc = resolveImportedDocument(document, importStatement, documents);
        if (importedDoc != undefined) {
            collectEntitiesByPathRecursively(importedDoc, documents, visited, result);
        }
    }
}

/**
 * Resolves an import statement to its target document.
 *
 * @param currentDocument The document containing the import statement
 * @param importStatement The file import statement to resolve
 * @param documents Langium documents service for file resolution
 * @returns The resolved document or undefined if not found
 */
export function resolveImportedDocument(
    currentDocument: LangiumDocument,
    importStatement: FileImportType,
    documents: LangiumDocuments
): LangiumDocument | undefined {
    const relativePath = importStatement.file;
    if (relativePath == undefined) {
        return undefined;
    }

    const resolvedUri = resolveRelativePath(currentDocument, relativePath);
    return documents.getDocument(resolvedUri);
}

/**
 * Creates a Scope from the collected metamodel exports and descriptions.
 *
 * @param descriptions The AST node descriptions
 * @returns A Scope that can be used for reference resolution
 */
export function createScopeFromDescriptions(descriptions: AstNodeDescription[]): Scope {
    return new StreamScope(stream(descriptions));
}

/**
 * Collects exported entities from a metamodel file and returns them as a Scope.
 * Convenience function that combines entity collection and scope creation.
 *
 * @param document The starting metamodel document
 * @param documents The Langium documents service for resolving imported files
 * @param descriptionProvider The provider for creating AST node descriptions
 * @returns A Scope containing all accessible classes and enums
 */
export function getScopeFromMetamodelFile(
    document: LangiumDocument,
    documents: LangiumDocuments,
    descriptionProvider: AstNodeDescriptionProvider
): Scope {
    const exports = getExportedEntitiesFromMetamodelFile(document, documents);
    const descriptions = createDescriptionsFromExports(exports, descriptionProvider);
    return createScopeFromDescriptions(descriptions);
}

/**
 * Gets exported entities from a metamodel file specified by a relative path.
 * Uses the IndexManager to find entities without requiring direct document access.
 *
 * @param document The current document where the import appears
 * @param relativePath The relative file path to import from
 * @param indexManager The index manager for querying exported entities
 * @returns A Scope containing exported classes and enums from the specified file
 */
export function getExportedEntitiesFromRelativePath(
    document: LangiumDocument,
    relativePath: string | undefined,
    indexManager: IndexManager
): Scope {
    if (relativePath == undefined) {
        return EMPTY_SCOPE;
    }

    const targetUri = resolveRelativePath(document, relativePath).toString();
    const classDescriptions = indexManager.allElements(Class.name, new Set([targetUri])).toArray();
    const enumDescriptions = indexManager.allElements(Enum.name, new Set([targetUri])).toArray();

    return new StreamScope(stream([...classDescriptions, ...enumDescriptions]));
}

/**
 * Checks if metamodel A is compatible with metamodel B.
 *
 * A metamodel A is compatible with B if:
 * - A and B are the same document (same URI), or
 * - B (transitively) imports A
 *
 * This is useful for validating that files using metamodel A can work with
 * entities from files using metamodel B.
 *
 * @param documentA The first metamodel document (the one being checked for compatibility)
 * @param documentB The second metamodel document (the reference metamodel)
 * @param documents The Langium documents service for resolving imported files
 * @returns True if A is compatible with B, false otherwise
 */
export function isMetamodelCompatible(
    documentA: LangiumDocument,
    documentB: LangiumDocument,
    documents: LangiumDocuments
): boolean {
    const uriA = documentA.uri.toString();
    const uriB = documentB.uri.toString();

    if (uriA === uriB) {
        return true;
    }

    return doesMetamodelTransitivelyImport(documentB, uriA, documents, new Set<string>());
}

/**
 * Recursively checks if a metamodel document transitively imports a target URI.
 * Handles import cycles by tracking visited URIs.
 *
 * @param document The metamodel document to check imports from
 * @param targetUri The URI to search for in the import chain
 * @param documents The Langium documents service for resolving imported files
 * @param visited Set of visited URIs to prevent infinite loops
 * @returns True if the document transitively imports the target URI
 */
function doesMetamodelTransitivelyImport(
    document: LangiumDocument,
    targetUri: string,
    documents: LangiumDocuments,
    visited: Set<string>
): boolean {
    const currentUri = document.uri.toString();

    if (visited.has(currentUri)) {
        return false;
    }
    visited.add(currentUri);

    const metamodel = document.parseResult?.value as MetaModelType | undefined;
    if (metamodel == undefined) {
        return false;
    }

    for (const importStatement of metamodel.imports ?? []) {
        const importedDoc = resolveImportedDocument(document, importStatement, documents);
        if (importedDoc == undefined) {
            continue;
        }

        const importedUri = importedDoc.uri.toString();
        if (importedUri === targetUri) {
            return true;
        }

        if (doesMetamodelTransitivelyImport(importedDoc, targetUri, documents, visited)) {
            return true;
        }
    }

    return false;
}

/**
 * Collects all absolute file paths from a metamodel document and its transitive imports.
 * This is useful for constructing the package map for the type system, where each path
 * corresponds to a unique internal package (e.g., "class/path/to/file").
 *
 * @param document The starting metamodel document
 * @param documents The Langium documents service for resolving imported files
 * @returns Array of absolute file paths (from document.uri.path) for the metamodel and all imports
 */
export function getAllMetamodelAbsolutePaths(document: LangiumDocument, documents: LangiumDocuments): string[] {
    const visited = new Set<string>();
    const paths: string[] = [];

    collectPathsRecursively(document, documents, visited, paths);

    return paths;
}

/**
 * Recursively collects absolute file paths from a document and its imports.
 * Uses a visited set to prevent infinite loops on circular imports.
 *
 * @param document The current document to process
 * @param documents Langium documents service for file resolution
 * @param visited Set of visited document URIs
 * @param paths Array to collect absolute paths
 */
function collectPathsRecursively(
    document: LangiumDocument,
    documents: LangiumDocuments,
    visited: Set<string>,
    paths: string[]
): void {
    const uriString = document.uri.toString();
    if (visited.has(uriString)) {
        return;
    }
    visited.add(uriString);

    paths.push(document.uri.path);

    const metamodel = document.parseResult?.value as MetaModelType | undefined;
    if (metamodel == undefined) {
        return;
    }

    for (const importStatement of metamodel.imports ?? []) {
        const importedDoc = resolveImportedDocument(document, importStatement, documents);
        if (importedDoc != undefined) {
            collectPathsRecursively(importedDoc, documents, visited, paths);
        }
    }
}
