import type { AstNode, LangiumDocument, ServiceRegistry } from "langium";
import type { HoverProvider, LangiumServices } from "langium/lsp";
import type { Hover, HoverParams } from "vscode-languageserver-protocol";
import { sharedImport } from "@mdeo/language-shared";
import { getServicesByLanguageId } from "@mdeo/language-common";
import type { ConfigContributionPlugin } from "../plugin/configContributionPlugin.js";
import type { ResolvedConfigContributionPlugins } from "../plugin/resolvePlugins.js";

const { MultilineCommentHoverProvider } = sharedImport("langium/lsp");
const { AstUtils, CstUtils } = sharedImport("langium");

/**
 * A delegating hover provider for the config language.
 *
 * When the position is inside a plugin-contributed section, the request is forwarded to the
 * plugin language's own HoverProvider, so that each plugin can explain its constructs (e.g. the
 * declarations of a transformation a section refers to). When the plugin has no hover for the
 * position, the default (documentation comment) hover applies.
 */
export class ConfigDelegatingHoverProvider extends MultilineCommentHoverProvider {
    /**
     * Map from section wrapper AST-type name to the plugin that contributes it.
     */
    private readonly sectionToPlugin = new Map<string, ConfigContributionPlugin>();

    /**
     * The service registry used to look up plugin language services.
     */
    private readonly serviceRegistry: ServiceRegistry;

    /**
     * @param services The config language services
     * @param resolvedPlugins The resolved plugin contributions providing section type information
     */
    constructor(services: LangiumServices, resolvedPlugins: ResolvedConfigContributionPlugins) {
        super(services);
        this.serviceRegistry = services.shared.ServiceRegistry;
        for (const sectionInfo of resolvedPlugins.sections.values()) {
            this.sectionToPlugin.set(sectionInfo.interface.name, sectionInfo.plugin);
        }
    }

    override async getHoverContent(document: LangiumDocument, params: HoverParams): Promise<Hover | undefined> {
        const pluginHover = this.findPluginHoverProvider(document, params);
        if (pluginHover != undefined) {
            const hover = await pluginHover.getHoverContent(document, params);
            if (hover != undefined) {
                return hover;
            }
        }
        return super.getHoverContent(document, params);
    }

    /**
     * Returns the hover provider of the plugin whose section contains the position.
     */
    private findPluginHoverProvider(document: LangiumDocument, params: HoverParams): HoverProvider | undefined {
        const rootCstNode = document.parseResult?.value?.$cstNode;
        if (rootCstNode == undefined) {
            return undefined;
        }
        const leaf = CstUtils.findLeafNodeAtOffset(rootCstNode, document.textDocument.offsetAt(params.position));
        if (leaf == undefined) {
            return undefined;
        }
        const section = AstUtils.getContainerOfType(leaf.astNode, (node): node is AstNode =>
            this.sectionToPlugin.has(node.$type)
        );
        const plugin = section != undefined ? this.sectionToPlugin.get(section.$type) : undefined;
        if (plugin == undefined) {
            return undefined;
        }
        const pluginServices = getServicesByLanguageId(this.serviceRegistry, plugin.languageKey) as
            Partial<LangiumServices> | undefined;
        return pluginServices?.lsp?.HoverProvider;
    }
}
