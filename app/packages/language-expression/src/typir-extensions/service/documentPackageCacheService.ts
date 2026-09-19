import type { LangiumDocument, WorkspaceCache as WorkspaceCacheType } from "langium";
import { resolveRelativePath, sharedImport } from "@mdeo/language-shared";
import { getAllMetamodelAbsolutePaths } from "@mdeo/language-metamodel";
import { getClassPackage, getEnumPackage } from "../../features/metamodel/metamodelClassExtractor.js";
import type { ExtendedLangiumSharedServices } from "@mdeo/language-common";

const { WorkspaceCache, DocumentState } = sharedImport("langium");

/**
 * Cached per-document package map state, computed once per document lifecycle.
 */
export type DocumentPackageCache = {
    /**
     * Map from user-visible package names to lists of internal packages.
     */
    packageMap: Map<string, string[]>;
    /**
     * Set of all internal packages from all values of packageMap.
     */
    allInternalPackages: Set<string>;
    /**
     * Types the document refers to by a name of its own, such as a record imported under another
     * name, by that name.
     */
    typeAliases: Map<string, TypeAlias>;
};

/**
 * A type a document refers to by a name other than its own.
 */
export type TypeAlias = {
    /**
     * The internal package of the type.
     */
    package: string;
    /**
     * The name the type is declared with.
     */
    name: string;
};

/**
 * Service that computes and caches the package map for each document.
 * The package map maps user-visible package names (e.g., "class", "enum", "builtin")
 * to lists of internal package identifiers.
 */
export interface DocumentPackageCacheService {
    /**
     * Retrieves (or computes and caches) the package map state for the given document.
     *
     * @param document The document for which to compute the package map
     * @returns The cached DocumentPackageCache for that document
     */
    getDocumentPackageCache(document: LangiumDocument): DocumentPackageCache;
}

/**
 * Default implementation of {@link DocumentPackageCacheService} that uses Langium's
 * {@link WorkspaceCache} to cache results per document, invalidating at
 * {@link DocumentState.IndexedReferences}.
 *
 * Subclasses can override {@link getMetamodelImportFile} to provide the metamodel
 * import file path from the document root, enabling class/enum package resolution.
 */
export class DefaultDocumentPackageCacheService implements DocumentPackageCacheService {
    private readonly cache: WorkspaceCacheType<string, DocumentPackageCache>;

    constructor(protected readonly langiumSharedServices: ExtendedLangiumSharedServices) {
        this.cache = new WorkspaceCache(langiumSharedServices as any, DocumentState.IndexedReferences);
    }

    getDocumentPackageCache(document: LangiumDocument): DocumentPackageCache {
        const key = document.uri.toString();
        const existing = this.cache.get(key);
        if (existing != undefined) {
            return existing;
        }
        const packageMap = this.computePackageMap(document);
        const allInternalPackages = new Set<string>();
        for (const internalPackages of packageMap.values()) {
            for (const pkg of internalPackages) {
                allInternalPackages.add(pkg);
            }
        }
        const entry: DocumentPackageCache = {
            packageMap,
            allInternalPackages,
            typeAliases: this.computeTypeAliases(document)
        };
        this.cache.set(key, entry);
        return entry;
    }

    /**
     * Computes the package map for a document.
     * Always includes the "builtin" package, and resolves class/enum packages
     * from the metamodel import if {@link getMetamodelImportFile} returns a path.
     *
     * Can be overridden for fully custom package map computation.
     */
    protected computePackageMap(document: LangiumDocument): Map<string, string[]> {
        const map = new Map<string, string[]>();
        map.set("builtin", ["builtin"]);

        const importFile = this.getMetamodelImportFile(document);
        if (importFile == undefined) {
            return map;
        }

        const langiumDocuments = this.langiumSharedServices.workspace.LangiumDocuments;
        const metamodelUri = resolveRelativePath(document, importFile);
        const metamodelDoc = langiumDocuments.getDocument(metamodelUri);
        if (metamodelDoc == undefined) {
            return map;
        }

        const absolutePaths = getAllMetamodelAbsolutePaths(metamodelDoc, langiumDocuments);
        const classPackages: string[] = [];
        const enumPackages: string[] = [];
        for (const absolutePath of absolutePaths) {
            classPackages.push(getClassPackage(absolutePath));
            enumPackages.push(getEnumPackage(absolutePath));
        }
        if (classPackages.length > 0) {
            map.set("class", classPackages);
        }
        if (enumPackages.length > 0) {
            map.set("enum", enumPackages);
        }
        return map;
    }

    /**
     * Computes the types a document refers to by a name of its own. A type's package that is only
     * reached through an alias should be left out of {@link computePackageMap}, so the declared
     * name does not resolve as well.
     *
     * @param _document The document
     * @returns The aliased types by alias; none by default
     */
    protected computeTypeAliases(_document: LangiumDocument): Map<string, TypeAlias> {
        return new Map();
    }

    /**
     * Returns the metamodel import file path from the document root, or `undefined`
     * if this document has no metamodel import.
     *
     * Override this in language-specific subclasses to provide the correct property access.
     */
    protected getMetamodelImportFile(_document: LangiumDocument): string | undefined {
        return undefined;
    }
}

/**
 * No-op implementation of {@link DocumentPackageCacheService} used as the default
 * when no language-specific package map is provided.
 * Always returns empty maps, effectively disabling type annotation features.
 */
export class NoopDocumentPackageCacheService implements DocumentPackageCacheService {
    getDocumentPackageCache(_document: LangiumDocument): DocumentPackageCache {
        return { packageMap: new Map(), allInternalPackages: new Set(), typeAliases: new Map() };
    }
}
