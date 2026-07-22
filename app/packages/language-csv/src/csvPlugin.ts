import {
    type LangiumLanguagePlugin,
    type LangiumLanguagePluginProvider,
    type LanguageServices,
    type ExternalReferenceAdditionalServices
} from "@mdeo/language-common";
import {
    DefaultAstSerializer,
    SerializerFormatter,
    registerDefaultTokenSerializers,
    DefaultExternalReferenceCollector
} from "@mdeo/language-shared";
import type { ServerContributionPlugin } from "@mdeo/plugin";
import { CsvFileRule, CsvTerminals } from "./grammar/csvRules.js";

function createCsvPlugin(): LangiumLanguagePlugin<ExternalReferenceAdditionalServices> {
    return {
        rootRule: CsvFileRule,
        additionalTerminals: CsvTerminals,
        module: {
            references: {
                ExternalReferenceCollector: () => new DefaultExternalReferenceCollector()
            },
            lsp: {
                Formatter: (services: LanguageServices & ExternalReferenceAdditionalServices) => new SerializerFormatter(services)
            },
            AstSerializer: (services: LanguageServices & ExternalReferenceAdditionalServices) => new DefaultAstSerializer(services)
        },
        postCreate(services: LanguageServices & ExternalReferenceAdditionalServices) {
            registerDefaultTokenSerializers(services);
        }
    };
}

export const csvPluginProvider: LangiumLanguagePluginProvider<ExternalReferenceAdditionalServices> = {
    create(_contributionPlugins: ServerContributionPlugin[], _languageJsUrl?: string): LangiumLanguagePlugin<ExternalReferenceAdditionalServices> {
        return createCsvPlugin();
    }
};
