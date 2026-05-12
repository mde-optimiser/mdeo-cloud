import { ConfigContributionPlugin } from "@mdeo/language-config";
import { GrammarSerializer, type SerializedGrammar } from "@mdeo/language-common";
import {
    ProblemSectionContentRule,
    GoalSectionContentRule,
    SingleMultiplicityRule,
    RangeMultiplicityRule,
    MultiplicityRule,
    ConstraintReferenceRule,
    ObjectiveRule,
    RefinementRule,
    FunctionImportRule,
    FunctionFileImportRule
} from "../grammar/optimizationRules.js";
import { GoalSection, ProblemSection } from "../grammar/optimizationTypes.js";

/**
 * The unique name for the optimization contribution plugin.
 */
export const OPTIMIZATION_PLUGIN_NAME = "optimization";

/**
 * The unique plugin ID for the optimization contribution plugin.
 */
export const OPTIMIZATION_PLUGIN_ID = "config-optimization";

/**
 * The language key used to retrieve services for the config-optimization language.
 */
export const CONFIG_OPTIMIZATION_LANGUAGE_KEY = "config-optimization";

/**
 * Creates the serialized grammar for all optimization rules.
 * This grammar contains all parser rules and interfaces used by the plugin.
 *
 * @returns The serialized grammar
 */
function createOptimizationGrammar(): SerializedGrammar {
    const serializer = new GrammarSerializer({
        rules: [
            ProblemSectionContentRule,
            GoalSectionContentRule,
            SingleMultiplicityRule,
            RangeMultiplicityRule,
            MultiplicityRule,
            ConstraintReferenceRule,
            ObjectiveRule,
            RefinementRule,
            FunctionImportRule,
            FunctionFileImportRule
        ],
        additionalTerminals: []
    });
    return serializer.grammar;
}

/**
 * Creates the optimization contribution plugin.
 * This plugin provides the "problem" and "goal" sections for the config language.
 *
 * @returns The ConfigContributionPlugin for optimization
 */
export function createOptimizationContributionPlugin(): ConfigContributionPlugin {
    return {
        id: OPTIMIZATION_PLUGIN_ID,
        type: ConfigContributionPlugin.TYPE,
        name: OPTIMIZATION_PLUGIN_NAME,
        languageKey: CONFIG_OPTIMIZATION_LANGUAGE_KEY,
        grammar: createOptimizationGrammar(),
        sections: [
            {
                name: "problem",
                ruleName: ProblemSectionContentRule.name,
                interfaceName: ProblemSection.name,
                executable: false
            },
            {
                name: "goal",
                ruleName: GoalSectionContentRule.name,
                interfaceName: GoalSection.name,
                executable: false
            }
        ],
        dependencies: ["config-metamodel", "config-script"],
        exportedTypes: [],
        sectionDependencies: []
    };
}
