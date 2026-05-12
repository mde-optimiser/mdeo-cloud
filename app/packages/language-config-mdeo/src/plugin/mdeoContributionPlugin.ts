import { ConfigContributionPlugin } from "@mdeo/language-config";
import { OPTIMIZATION_PLUGIN_NAME } from "@mdeo/language-config-optimization";
import { GrammarSerializer, type SerializedGrammar } from "@mdeo/language-common";
import {
    SearchSectionContentRule,
    SolverSectionContentRule,
    RuntimeSectionContentRule,
    MutationsBlockRule,
    UsingPathRule,
    ClassMutationRule,
    EdgeMutationRule,
    MutationStepNumericRule,
    MutationStepFixedRule,
    MutationStepFixedNRule,
    MutationStepIntervalRule,
    MutationStepValueRule,
    MutationBlockRule,
    ArchiveBlockRule,
    AlgorithmParametersBlockRule,
    TerminationBlockRule,
    RuntimeTimeoutBlockRule,
    RuntimeResourcesBlockRule
} from "../grammar/mdeoRules.js";
import { SearchSection, SolverSection, RuntimeSection } from "../grammar/mdeoTypes.js";

/**
 * The unique name for the MDEO contribution plugin.
 */
export const MDEO_PLUGIN_NAME = "mdeo";

/**
 * The unique plugin ID for the MDEO contribution plugin.
 */
export const MDEO_PLUGIN_ID = "config-mdeo";

/**
 * The language key used to retrieve services for the config-mdeo language.
 */
export const CONFIG_MDEO_LANGUAGE_KEY = "config-mdeo";

/**
 * Creates the serialized grammar for all MDEO rules.
 * This grammar contains all parser rules and interfaces used by the plugin.
 *
 * @returns The serialized grammar
 */
function createMdeoGrammar(): SerializedGrammar {
    const serializer = new GrammarSerializer({
        rules: [
            SearchSectionContentRule,
            SolverSectionContentRule,
            RuntimeSectionContentRule,
            MutationsBlockRule,
            UsingPathRule,
            ClassMutationRule,
            EdgeMutationRule,
            MutationStepNumericRule,
            MutationStepFixedRule,
            MutationStepFixedNRule,
            MutationStepIntervalRule,
            MutationStepValueRule,
            MutationBlockRule,
            ArchiveBlockRule,
            AlgorithmParametersBlockRule,
            TerminationBlockRule,
            RuntimeTimeoutBlockRule,
            RuntimeResourcesBlockRule
        ],
        additionalTerminals: []
    });
    return serializer.grammar;
}

/**
 * Creates the MDEO contribution plugin.
 * This plugin provides the "search" and "solver" sections for the config language.
 *
 * @returns The ConfigContributionPlugin for MDEO
 */
export function createMdeoContributionPlugin(): ConfigContributionPlugin {
    return {
        id: MDEO_PLUGIN_ID,
        type: ConfigContributionPlugin.TYPE,
        name: MDEO_PLUGIN_NAME,
        languageKey: CONFIG_MDEO_LANGUAGE_KEY,
        grammar: createMdeoGrammar(),
        sections: [
            {
                name: "search",
                ruleName: SearchSectionContentRule.name,
                interfaceName: SearchSection.name,
                executable: false
            },
            {
                name: "solver",
                ruleName: SolverSectionContentRule.name,
                interfaceName: SolverSection.name,
                executable: true
            },
            {
                name: "runtime",
                ruleName: RuntimeSectionContentRule.name,
                interfaceName: RuntimeSection.name,
                executable: false
            }
        ],
        dependencies: ["config-optimization"],
        exportedTypes: [],
        sectionDependencies: [
            {
                pluginName: OPTIMIZATION_PLUGIN_NAME,
                sectionName: "problem"
            }
        ]
    };
}
