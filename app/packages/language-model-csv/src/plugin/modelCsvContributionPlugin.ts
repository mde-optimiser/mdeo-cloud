import { type ModelContributionPlugin, MODEL_CONTRIBUTION_PLUGIN_TYPE } from "@mdeo/language-model";
import { CsvClassImportRule, CsvImportBlockRule } from "../grammar/csvImportRules.js";

export const MODEL_CSV_CONTRIBUTION_PLUGIN_ID = "model-csv";

export function createModelCsvContributionPlugin(): ModelContributionPlugin {
    return {
        type: MODEL_CONTRIBUTION_PLUGIN_TYPE,
        id: MODEL_CSV_CONTRIBUTION_PLUGIN_ID,
        name: "CSV Import",
        additionalTerminals: [],
        additionalRules: [CsvClassImportRule, CsvImportBlockRule],
        topLevelRuleNames: [CsvImportBlockRule.name]
    };
}
