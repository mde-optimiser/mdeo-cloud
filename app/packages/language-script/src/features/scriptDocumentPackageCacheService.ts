import type { LangiumDocument } from "langium";
import { DefaultDocumentPackageCacheService, type TypeAlias } from "@mdeo/language-expression";
import { resolveRelativePath } from "@mdeo/language-shared";
import type { ExtendedLangiumSharedServices } from "@mdeo/language-common";
import type { ScriptType } from "../grammar/scriptTypes.js";
import type { ResolvedScriptContributionPlugins } from "../plugin/scriptContributionPlugin.js";
import { getRecordPackage, RECORD_PACKAGE_PREFIX } from "./records.js";
import { ContributedClass } from "../plugin/scriptContributionPlugin.js";

/**
 * Script-specific package cache service.
 *
 * Resolves the metamodel import from the `metamodelImport.file` property of the Script root node,
 * and makes visible the records the script declares and imports, and the classes contributions
 * define. A record imported under another name is visible by that name only.
 */
export class ScriptDocumentPackageCacheService extends DefaultDocumentPackageCacheService {
    constructor(
        langiumSharedServices: ExtendedLangiumSharedServices,
        private readonly plugins: ResolvedScriptContributionPlugins
    ) {
        super(langiumSharedServices);
    }

    protected override getMetamodelImportFile(document: LangiumDocument): string | undefined {
        const root = document.parseResult?.value as ScriptType | undefined;
        return root?.metamodelImport?.file;
    }

    protected override computePackageMap(document: LangiumDocument): Map<string, string[]> {
        const map = super.computePackageMap(document);

        const recordPackages = this.getRecordPackages(document);
        if (recordPackages.length > 0) {
            map.set(RECORD_PACKAGE_PREFIX, recordPackages);
        }

        const contributedPackages = [
            ...new Set(this.plugins.classes.map((contributed) => contributed.classType.package))
        ];
        if (contributedPackages.length > 0) {
            map.set(ContributedClass.PACKAGE_PREFIX, contributedPackages);
        }
        return map;
    }

    protected override computeTypeAliases(document: LangiumDocument): Map<string, TypeAlias> {
        const aliases = new Map<string, TypeAlias>();
        for (const imported of this.getImportedRecords(document)) {
            if (imported.alias != undefined) {
                aliases.set(imported.alias, { package: imported.package, name: imported.name });
            }
        }
        return aliases;
    }

    /**
     * Collects the type packages of the records a script declares, and of the records it imports
     * under their own name.
     *
     * @param document The script document
     * @returns The record packages
     */
    private getRecordPackages(document: LangiumDocument): string[] {
        const root = document.parseResult?.value as ScriptType | undefined;
        if (root == undefined) {
            return [];
        }
        const packages = (root.records ?? []).map((record) => getRecordPackage(document.uri.path, record.name));
        for (const imported of this.getImportedRecords(document)) {
            if (imported.alias == undefined) {
                packages.push(imported.package);
            }
        }
        return packages;
    }

    /**
     * Collects the records a script imports.
     *
     * Imports are matched by the name they refer to in the imported file, which does not need the
     * import to be linked yet.
     *
     * @param document The script document
     * @returns Each imported record's package and declared name, and the name it is imported under
     *          when that differs
     */
    private getImportedRecords(document: LangiumDocument): { package: string; name: string; alias?: string }[] {
        const root = document.parseResult?.value as ScriptType | undefined;
        if (root == undefined) {
            return [];
        }
        const imported: { package: string; name: string; alias?: string }[] = [];
        const documents = this.langiumSharedServices.workspace.LangiumDocuments;
        for (const fileImport of root.imports ?? []) {
            if (fileImport.file == undefined) {
                continue;
            }
            const importedUri = resolveRelativePath(document, fileImport.file);
            const importedRoot = documents.getDocument(importedUri)?.parseResult?.value as ScriptType | undefined;
            const importedRecords = new Set((importedRoot?.records ?? []).map((record) => record.name));
            for (const namedImport of fileImport.imports) {
                const name = namedImport.entity?.$refText;
                if (name != undefined && importedRecords.has(name)) {
                    const alias =
                        namedImport.name != undefined && namedImport.name !== name ? namedImport.name : undefined;
                    imported.push({ package: getRecordPackage(importedUri.path, name), name, alias });
                }
            }
        }
        return imported;
    }
}
