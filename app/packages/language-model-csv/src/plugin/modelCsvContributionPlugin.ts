import { ModelContributionPlugin } from "@mdeo/language-model";
import { GrammarSerializer, type SerializedGrammar } from "@mdeo/language-common";
import { CsvClassImportRule, CsvImportContentRule } from "../grammar/csvImportRules.js";
import { CsvImportBlock } from "../grammar/csvImportTypes.js";

/**
 * The unique name for the CSV contribution plugin.
 */
export const CSV_PLUGIN_NAME = "csv";

/**
 * The unique plugin ID for the CSV contribution plugin.
 */
export const CSV_PLUGIN_ID = "model-csv";

/**
 * The language key used to retrieve services for the standalone model-csv language.
 */
export const MODEL_CSV_LANGUAGE_KEY = "model-csv";

/**
 * Creates the serialized grammar for the CSV import rules.
 *
 * @returns The serialized grammar
 */
function createCsvGrammar(): SerializedGrammar {
    const serializer = new GrammarSerializer({
        rules: [CsvClassImportRule, CsvImportContentRule],
        additionalTerminals: []
    });
    return serializer.grammar;
}

/**
 * Creates the CSV contribution plugin.
 * This plugin provides the `import CSV { ClassName from "file.csv" }` syntax for the model language.
 *
 * @returns The ModelContributionPlugin for CSV
 */
export function createModelCsvContributionPlugin(): ModelContributionPlugin {
    return {
        id: CSV_PLUGIN_ID,
        type: ModelContributionPlugin.TYPE,
        name: CSV_PLUGIN_NAME,
        languageKey: MODEL_CSV_LANGUAGE_KEY,
        grammar: createCsvGrammar(),
        imports: [
            {
                name: "CSV",
                ruleName: CsvImportContentRule.name,
                interfaceName: CsvImportBlock.name
            }
        ],
        dependencies: [],
        exportedTypes: []
    };
}
