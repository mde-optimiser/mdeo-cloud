import type { ExternalReferenceCollector, ExternalReferences } from "@mdeo/language-common";
import type { LangiumDocument, URI } from "langium";
import { resolveRelativePath } from "@mdeo/language-shared";
import { getWrapperInterfaceName } from "@mdeo/language-config";
import { MDEO_PLUGIN_NAME } from "../plugin/mdeoContributionPlugin.js";
import type { SearchSectionType, UsingPathType } from "../grammar/mdeoTypes.js";
import type { MdeoMetamodelResolver } from "./mdeoMetamodelResolver.js";

/**
 * External reference collector for the config-mdeo language.
 * Collects URIs of model transformation files referenced via "using" paths
 * in the mutations and crossovers blocks of the search section, so they can be loaded for
 * scope resolution and validation.
 */
export class MdeoExternalReferenceCollector implements ExternalReferenceCollector {
    private readonly metamodelResolver: MdeoMetamodelResolver;

    /**
     * Constructs a new MdeoExternalReferenceCollector.
     *
     * @param metamodelResolver The metamodel resolver service
     */
    constructor(metamodelResolver: MdeoMetamodelResolver) {
        this.metamodelResolver = metamodelResolver;
    }

    /**
     * Finds external references in the given documents.
     *
     * @param docs The documents to scan for external references
     * @returns The external references found
     */
    findExternalReferences(docs: LangiumDocument[]): ExternalReferences {
        const searchWrapperType = getWrapperInterfaceName("search", MDEO_PLUGIN_NAME);

        const uris: URI[] = [];
        for (const doc of docs) {
            const config = doc.parseResult.value as { sections?: any[] } | undefined;
            if (config == undefined || !Array.isArray(config.sections)) {
                continue;
            }

            for (const section of config.sections) {
                if (section.$type === searchWrapperType) {
                    const searchSection = section as { $type?: string; content?: SearchSectionType };
                    const blocks = [searchSection.content?.mutations?.[0], searchSection.content?.crossovers?.[0]];
                    for (const block of blocks) {
                        for (const usingPath of (block?.usingPaths ?? []) as UsingPathType[]) {
                            if (usingPath.path != undefined) {
                                uris.push(resolveRelativePath(doc, usingPath.path));
                            }
                        }
                    }
                }
            }

            const metamodelUri = this.metamodelResolver.getMetamodelUri(doc);
            if (metamodelUri != undefined && !uris.some((u) => u.toString() === metamodelUri.toString())) {
                uris.push(metamodelUri);
            }
        }

        return {
            local: [],
            external: uris
        };
    }
}
