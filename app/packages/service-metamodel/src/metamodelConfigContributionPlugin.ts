import { ConfigContributionPlugin } from "@mdeo/language-config";
import { GrammarSerializer } from "@mdeo/language-common";
import {
    Class,
    Property,
    Enum,
    Association,
    AssociationEnd,
    SingleMultiplicity,
    RangeMultiplicity,
    PrimitiveType,
    EnumTypeReference,
    EnumEntry,
    ClassExtension,
    ClassExtensions,
    FileImport
} from "@mdeo/language-metamodel";

/**
 * The unique plugin ID for the metamodel config contribution plugin.
 */
export const METAMODEL_CONFIG_PLUGIN_ID = "config-metamodel";

/**
 * The language key for the metamodel language.
 */
export const METAMODEL_LANGUAGE_KEY = "metamodel";

/**
 * Creates the metamodel config contribution plugin.
 * This plugin exports metamodel types for use by other config plugins.
 *
 * @returns The ConfigContributionPlugin for metamodel
 */
export function createMetamodelConfigContributionPlugin(): ConfigContributionPlugin {
    const interfaces = [
        Class,
        Property,
        Enum,
        Association,
        AssociationEnd,
        SingleMultiplicity,
        RangeMultiplicity,
        PrimitiveType,
        EnumTypeReference,
        EnumEntry,
        ClassExtension,
        ClassExtensions,
        FileImport
    ];
    const serializer = new GrammarSerializer({
        rules: [],
        additionalTerminals: [],
        interfaces
    });

    return {
        id: METAMODEL_CONFIG_PLUGIN_ID,
        type: ConfigContributionPlugin.TYPE,
        name: "metamodel",
        languageKey: METAMODEL_LANGUAGE_KEY,
        grammar: serializer.grammar,
        sections: [],
        dependencies: [],
        exportedTypes: interfaces.map((i) => i.name),
        sectionDependencies: []
    };
}
