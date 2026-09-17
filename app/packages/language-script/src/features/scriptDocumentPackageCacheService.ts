import type { LangiumDocument } from "langium";
import { DefaultDocumentPackageCacheService } from "@mdeo/language-expression";
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
 * define.
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

    /**
     * Collects the type packages of the records a script declares, and of the records it imports.
     *
     * Imports are matched by the name they refer to in the imported file, which does not need the
     * import to be linked yet.
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

        const documents = this.langiumSharedServices.workspace.LangiumDocuments;
        for (const fileImport of root.imports ?? []) {
            if (fileImport.file == undefined) {
                continue;
            }
            const importedUri = resolveRelativePath(document, fileImport.file);
            const imported = documents.getDocument(importedUri)?.parseResult?.value as ScriptType | undefined;
            const importedRecords = new Set((imported?.records ?? []).map((record) => record.name));
            for (const namedImport of fileImport.imports) {
                const name = namedImport.entity?.$refText;
                if (name != undefined && importedRecords.has(name)) {
                    packages.push(getRecordPackage(importedUri.path, name));
                }
            }
        }
        return packages;
    }
}
