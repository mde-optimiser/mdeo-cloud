import type { TerminalRule, ParserRule } from "@mdeo/language-common";

export const MODEL_CONTRIBUTION_PLUGIN_TYPE = "model-contribution-plugin";

export interface ModelContributionPlugin {
    readonly type: typeof MODEL_CONTRIBUTION_PLUGIN_TYPE;
    readonly id: string;
    readonly name: string;
    readonly additionalTerminals: TerminalRule<any>[];
    readonly additionalRules: ParserRule<any>[];
    readonly topLevelRuleNames: string[];
    readonly keywords: string[];
}

export namespace ModelContributionPlugin {
    export const TYPE = MODEL_CONTRIBUTION_PLUGIN_TYPE;
}
