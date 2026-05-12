import type { ValidationAcceptor, ValidationChecks, AstNode, ServiceRegistry, ValidationRegistry } from "langium";
import type { LangiumCoreServices } from "langium";
import { sharedImport } from "@mdeo/language-shared";
import type { ConfigContributionPlugin } from "../plugin/configContributionPlugin.js";
import type { ResolvedConfigContributionPlugins, SectionNamingInfo } from "../plugin/resolvePlugins.js";
import type { ConfigType, BaseConfigSectionType } from "../grammar/configTypes.js";
import { getServicesByLanguageId } from "../features/util.js";

const { MultiMap, Cancellation } = sharedImport("langium");

/**
 * Interface mapping for config AST types used in validation checks.
 */
interface ConfigAstTypes {
    Config: ConfigType;
    BaseConfigSection: BaseConfigSectionType;
}

/**
 * Registers validation checks for the config language.
 * Registers both the config-level structural checks and delegation to plugin validators.
 *
 * @param services The Langium core services
 * @param serviceRegistry The service registry for accessing plugin language services
 * @param plugins The config contribution plugins
 * @param resolvedPlugins The resolved plugin contributions (contains section naming info)
 */
export function registerConfigValidationChecks(
    services: LangiumCoreServices,
    serviceRegistry: ServiceRegistry,
    plugins: ConfigContributionPlugin[],
    resolvedPlugins: ResolvedConfigContributionPlugins
): void {
    const registry = services.validation.ValidationRegistry;
    const validator = new ConfigValidator(serviceRegistry, plugins, resolvedPlugins);

    const checks: ValidationChecks<ConfigAstTypes> = {
        Config: validator.validateConfig.bind(validator),
        BaseConfigSection: validator.validateSection.bind(validator)
    };

    registry.register(checks, validator);
}

/**
 * Validator for the config language.
 *
 * Checks:
 * - Each named section (qualified or simple) appears at most once in a config document.
 * - When a section is present, all sections it depends on (from required plugins) are also present.
 * - Delegates validation of individual section nodes to the plugin language's own validators.
 */
export class ConfigValidator {
    /**
     * Map from section wrapper $type to the SectionNamingInfo for that section.
     */
    private readonly sectionTypeToNamingInfo: Map<string, SectionNamingInfo>;

    /**
     * Constructs a new ConfigValidator.
     *
     * @param serviceRegistry The service registry for accessing plugin-specific language services
     * @param plugins The array of registered config contribution plugins
     * @param resolvedPlugins The resolved plugin contributions providing wrapper interface information
     */
    constructor(
        private readonly serviceRegistry: ServiceRegistry,
        private readonly plugins: ConfigContributionPlugin[],
        private readonly resolvedPlugins: ResolvedConfigContributionPlugins
    ) {
        this.sectionTypeToNamingInfo = new Map();
        for (const [, info] of resolvedPlugins.sections.entries()) {
            this.sectionTypeToNamingInfo.set(info.interface.name, info);
        }
    }

    /**
     * Validates the entire Config node for structural rules:
     * - Each section (by its qualified name) appears at most once.
     * - If a section is present, all sections it depends on are also present.
     *
     * @param config The Config root node to validate
     * @param accept The validation acceptor for reporting diagnostics
     */
    validateConfig(config: ConfigType, accept: ValidationAcceptor): void {
        this.validateSectionUniqueness(config, accept);
        this.validateSectionDependencies(config, accept);
        this.validateSingleExecutableSection(config, accept);
    }

    /**
     * Delegates validation of a single section to the plugin language's validator.
     * Runs all registered validation checks from the plugin's own ValidationRegistry
     * against the section node's content.
     *
     * @param section The section wrapper node to validate
     * @param accept The validation acceptor
     */
    validateSection(section: BaseConfigSectionType, accept: ValidationAcceptor): void {
        const namingInfo = this.sectionTypeToNamingInfo.get(section.$type);
        if (namingInfo == undefined) {
            return;
        }

        const pluginServices = getServicesByLanguageId(this.serviceRegistry, namingInfo.plugin.languageKey);
        if (pluginServices == undefined) {
            return;
        }

        const sectionWithContent = section as AstNode & { content?: AstNode };
        const contentNode = sectionWithContent.content;
        if (contentNode == undefined) {
            return;
        }

        this.runPluginValidationsRecursively(contentNode, pluginServices.validation.ValidationRegistry, accept);
    }

    /**
     * Checks that each section (identified by its wrapper $type) appears at most once
     * in the config document. The display name uses the qualified name from the naming info.
     *
     * @param config The Config root node
     * @param accept The validation acceptor
     */
    private validateSectionUniqueness(config: ConfigType, accept: ValidationAcceptor): void {
        const typeToSections = new MultiMap<string, BaseConfigSectionType>();

        for (const section of config.sections ?? []) {
            typeToSections.add(section.$type, section);
        }

        for (const [type, sections] of typeToSections.entriesGroupedByKey()) {
            if (sections.length <= 1) {
                continue;
            }

            const namingInfo = this.sectionTypeToNamingInfo.get(type);
            const displayName = namingInfo?.qualifiedName ?? type;

            for (const section of sections) {
                accept(
                    "error",
                    `Section '${displayName}' appears more than once. Each section may appear at most once.`,
                    {
                        node: section
                    }
                );
            }
        }
    }

    /**
     * Checks that when a section is present, all sections it transitively depends on are also present.
     * Dependencies are taken from the plugin's `dependencies` array and matched against the
     * `sectionDependencies` list within the plugin.
     *
     * @param config The Config root node
     * @param accept The validation acceptor
     */
    private validateSectionDependencies(config: ConfigType, accept: ValidationAcceptor): void {
        const presentQualifiedNames = this.collectPresentQualifiedNames(config);

        for (const section of config.sections ?? []) {
            const namingInfo = this.sectionTypeToNamingInfo.get(section.$type);
            if (namingInfo == undefined) {
                continue;
            }

            this.checkPluginDependencies(section, namingInfo, presentQualifiedNames, accept);
        }
    }

    /**
     * Collects the set of qualified section names that are present in the config document.
     *
     * @param config The Config root node
     * @returns A Set of qualified section names that are present
     */
    private collectPresentQualifiedNames(config: ConfigType): Set<string> {
        const present = new Set<string>();
        for (const section of config.sections ?? []) {
            const namingInfo = this.sectionTypeToNamingInfo.get(section.$type);
            if (namingInfo != undefined) {
                present.add(namingInfo.qualifiedName);
            }
        }
        return present;
    }

    /**
     * Checks that a section's plugin's sectionDependencies are all present in the config.
     * Only section dependencies listed in the plugin definition are checked here.
     *
     * @param section The section node being validated
     * @param namingInfo The naming info for the section
     * @param presentQualifiedNames Set of qualified names that are present
     * @param accept The validation acceptor
     */
    private checkPluginDependencies(
        section: BaseConfigSectionType,
        namingInfo: SectionNamingInfo,
        presentQualifiedNames: Set<string>,
        accept: ValidationAcceptor
    ): void {
        for (const dep of namingInfo.plugin.sectionDependencies) {
            const depPlugin = this.plugins.find((p) => p.name === dep.pluginName);
            if (depPlugin == undefined) {
                continue;
            }

            const depQualifiedName = `${dep.sectionName}.${dep.pluginName}`;
            if (!presentQualifiedNames.has(depQualifiedName)) {
                accept(
                    "error",
                    `Section '${namingInfo.qualifiedName}' requires section '${depQualifiedName}' (from plugin '${dep.pluginName}'), but it is not present.`,
                    {
                        node: section
                    }
                );
            }
        }
    }

    /**
     * Checks that at most one executable section is present in the config document.
     * Executable sections are those whose contribution plugin section definition
     * has `executable: true`.
     *
     * @param config The Config root node
     * @param accept The validation acceptor
     */
    private validateSingleExecutableSection(config: ConfigType, accept: ValidationAcceptor): void {
        const executableSections: Array<{ section: BaseConfigSectionType; qualifiedName: string }> = [];

        for (const section of config.sections ?? []) {
            const namingInfo = this.sectionTypeToNamingInfo.get(section.$type);
            if (namingInfo == undefined) {
                continue;
            }

            const sectionDef = namingInfo.plugin.sections.find(
                (pluginSection) => pluginSection.name === namingInfo.sectionName
            );
            if (sectionDef?.executable === true) {
                executableSections.push({ section, qualifiedName: namingInfo.qualifiedName });
            }
        }

        if (executableSections.length <= 1) {
            return;
        }

        const presentExecutableNames = executableSections.map((s) => s.qualifiedName).join(", ");
        for (const executable of executableSections) {
            accept("error", `Only one executable section may be present, but found: ${presentExecutableNames}.`, {
                node: executable.section
            });
        }
    }

    /**
     * Runs all registered fast-category validation checks from a plugin's ValidationRegistry
     * against the given node and all its descendants.
     *
     * @param node The root AST node to validate
     * @param registry The plugin's ValidationRegistry
     * @param accept The validation acceptor
     */
    private runPluginValidationsRecursively(
        node: AstNode,
        registry: ValidationRegistry,
        accept: ValidationAcceptor
    ): void {
        const checks = registry.getChecks(node.$type);
        for (const check of checks) {
            void check(node, accept, Cancellation.CancellationToken.None);
        }

        for (const child of this.getChildren(node)) {
            this.runPluginValidationsRecursively(child, registry, accept);
        }
    }

    /**
     * Returns the direct AST children of a node by iterating all array and object properties.
     *
     * @param node The AST node to get children for
     * @returns An iterable of direct child AST nodes
     */
    private getChildren(node: AstNode): AstNode[] {
        const children: AstNode[] = [];
        for (const key of Object.keys(node)) {
            if (key.startsWith("$")) {
                continue;
            }
            const value = (node as unknown as Record<string, unknown>)[key];
            if (Array.isArray(value)) {
                for (const item of value) {
                    if (item != null && typeof item === "object" && "$type" in item) {
                        children.push(item as AstNode);
                    }
                }
            } else if (value != null && typeof value === "object" && "$type" in value) {
                children.push(value as AstNode);
            }
        }
        return children;
    }
}
