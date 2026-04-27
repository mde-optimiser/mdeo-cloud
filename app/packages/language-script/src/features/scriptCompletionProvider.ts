import { ExpressionCompletionProvider, type TypeTypes } from "@mdeo/language-expression";
import type { ExpressionTypes } from "@mdeo/language-expression";
import { isMetamodelCompatible } from "@mdeo/language-metamodel";
import {
    FileImportCompletionHelper,
    computeRelativePathCompletions,
    acceptRelativePathCompletions,
    resolveRelativePath,
    getExportetEntitiesFromRelativeFile,
    sharedImport,
    type AstFileImportItem
} from "@mdeo/language-shared";
import type { CompletionAcceptor, CompletionContext, CompletionProviderOptions, NextFeature } from "langium/lsp";
import type { ExtendedLangiumServices, AstSerializerAdditionalServices } from "@mdeo/language-common";
import type { IndexManager, LangiumDocument, LangiumDocuments } from "langium";
import type { ScriptTypirServices } from "../plugin.js";
import {
    Function,
    FunctionFileImport,
    FunctionImport,
    MetamodelFileImport,
    type FunctionImportType,
    type FunctionType,
    type ScriptType
} from "../grammar/scriptTypes.js";

const { AstUtils } = sharedImport("langium");
const { CompletionItemKind } = sharedImport("vscode-languageserver-protocol");

/**
 * Concrete completion helper for Script function imports.
 *
 * Filters target scripts by metamodel compatibility with the current document.
 */
class ScriptFunctionImportHelper extends FileImportCompletionHelper<FunctionType> {
    constructor(services: ExtendedLangiumServices & AstSerializerAdditionalServices) {
        super(
            Function,
            FunctionFileImport.name,
            FunctionImport.name,
            services.AstSerializer,
            services.shared.workspace.IndexManager,
            services.shared.workspace.LangiumDocuments
        );
    }

    protected override shouldIncludeDocument(targetDoc: LangiumDocument, currentDoc: LangiumDocument): boolean {
        const targetScript = targetDoc.parseResult?.value as ScriptType | undefined;
        const scriptMetamodelPath = targetScript?.metamodelImport?.file;

        if (scriptMetamodelPath == undefined) {
            return true;
        }

        const currentScript = currentDoc.parseResult?.value as ScriptType | undefined;
        const currentMetamodelPath = currentScript?.metamodelImport?.file;
        if (currentMetamodelPath == undefined) {
            return false;
        }

        const currentMetamodelDoc = this.langiumDocuments.getDocument(
            resolveRelativePath(currentDoc, currentMetamodelPath)
        );
        if (currentMetamodelDoc == undefined) return false;

        const scriptMetamodelDoc = this.langiumDocuments.getDocument(
            resolveRelativePath(targetDoc, scriptMetamodelPath)
        );
        if (scriptMetamodelDoc == undefined) return false;

        return isMetamodelCompatible(scriptMetamodelDoc, currentMetamodelDoc, this.langiumDocuments);
    }

    protected override buildCompletionItem(entityName: string, relativePath: string) {
        return {
            label: entityName,
            kind: CompletionItemKind.Function,
            labelDetails: { description: relativePath },
            sortText: "1"
        };
    }
}

/**
 * Completion provider for the Script language.
 *
 * Adds completion for functions from compatible but not-yet-imported scripts,
 * including automatic import insertion when a suggestion is selected.
 * Also provides relative path completions for file path string properties.
 */
export class ScriptCompletionProvider extends ExpressionCompletionProvider {
    override readonly completionOptions: CompletionProviderOptions = { triggerCharacters: ['"', "/", "."] };
    private readonly langiumDocuments: LangiumDocuments;
    private readonly importHelper: ScriptFunctionImportHelper;
    private readonly indexManager: IndexManager;

    constructor(
        services: { typir: ScriptTypirServices } & ExtendedLangiumServices & AstSerializerAdditionalServices,
        expressionTypes: ExpressionTypes,
        typeTypes?: TypeTypes
    ) {
        super(services, expressionTypes, typeTypes);
        this.langiumDocuments = services.shared.workspace.LangiumDocuments;
        this.importHelper = new ScriptFunctionImportHelper(services);
        this.indexManager = services.shared.workspace.IndexManager;
    }

    /**
     * Extends the base completion with Script-specific suggestions.
     */
    protected override async completionFor(
        context: CompletionContext,
        next: NextFeature,
        acceptor: CompletionAcceptor
    ): Promise<void> {
        super.completionFor(context, next, acceptor);

        if (next.type === this.expressionTypes.identifierExpressionType.name) {
            await this.completionForUnimportedFunctions(context, acceptor);
        }

        if (next.property === "entity" && this.reflection.isInstance(context.node, FunctionImport)) {
            await this.completionForImportEntity(context, context.node, acceptor);
            return;
        }

        this.completionForRelativePath(context, next, acceptor);
    }

    /**
     * Suggests functions from compatible but not-yet-imported scripts,
     * with auto-import text edits.
     */
    private async completionForUnimportedFunctions(
        context: CompletionContext,
        acceptor: CompletionAcceptor
    ): Promise<void> {
        const node = context.node;
        if (node == undefined) {
            return;
        }

        try {
            const document = AstUtils.getDocument(node);
            const scriptRoot = document.parseResult?.value as ScriptType | undefined;
            if (scriptRoot == undefined) {
                return;
            }

            const alreadyInScope = this.collectAlreadyInScopeNames(scriptRoot);

            await this.importHelper.computeCompletions(
                context,
                acceptor,
                document,
                scriptRoot.imports as ReadonlyArray<AstFileImportItem>,
                () => {
                    if (scriptRoot.imports.length > 0) {
                        return scriptRoot.imports[scriptRoot.imports.length - 1].$cstNode;
                    }
                    return scriptRoot.metamodelImport?.$cstNode;
                },
                (name) => alreadyInScope.has(name)
            );
        } catch {
            // Ignore errors during completion on incomplete documents
        }
    }

    /**
     * Provides completions for the `entity` property inside an import `{ }` block.
     *
     * Langium's default cross-reference completion may return no results when the
     * synthetic node used during completion lacks a `$container` link, causing the
     * scope provider to receive an unresolvable reference.  This method explicitly
     * resolves the target file from the nearest `FunctionFileImport` ancestor and
     * queries the workspace index directly.
     *
     * @param context LSP completion context.
     * @param node The AST node corresponding to the `entity` property being completed.
     * @param acceptor LSP completion acceptor.
     */
    private async completionForImportEntity(
        context: CompletionContext,
        node: FunctionImportType,
        acceptor: CompletionAcceptor
    ): Promise<void> {
        if (node == undefined) {
            return;
        }

        try {
            const document = AstUtils.getDocument(node);

            const fileImport = node.$container;
            if (fileImport == undefined || !this.reflection.isInstance(fileImport, FunctionFileImport)) {
                return;
            }

            const alreadyImported = new Set<string>(
                fileImport.imports.flatMap((imp) => {
                    const name = imp.entity.$refText ?? imp.entity.ref?.name;
                    return name != undefined ? [name] : [];
                })
            );

            const scope = getExportetEntitiesFromRelativeFile(document, fileImport.file, [Function], this.indexManager);

            for (const desc of scope.getAllElements()) {
                const name = desc.name;
                if (!name || alreadyImported.has(name)) {
                    continue;
                }
                acceptor(context, {
                    label: name,
                    kind: CompletionItemKind.Function,
                    sortText: "0"
                });
            }
        } catch {
            // Ignore errors during completion on incomplete documents
        }
    }

    /**
     * Provides relative path completions for file path string properties.
     */
    private completionForRelativePath(
        context: CompletionContext,
        next: NextFeature,
        acceptor: CompletionAcceptor
    ): void {
        const node = context.node;
        if (node == undefined) {
            return;
        }

        let extensions: string[] | undefined;

        if (this.reflection.isInstance(node, MetamodelFileImport) && next.property === "file") {
            extensions = [".mm"];
        } else if (this.reflection.isInstance(node, FunctionFileImport) && next.property === "file") {
            extensions = [".script"];
        }

        if (extensions == undefined) {
            return;
        }

        try {
            const document = AstUtils.getDocument(node);
            const paths = computeRelativePathCompletions(document, this.langiumDocuments, extensions);
            acceptRelativePathCompletions(context, acceptor, paths);
        } catch {
            // Ignore errors during completion
        }
    }

    /**
     * Collects all function names already accessible in the current script:
     * locally defined functions and those already imported from any file.
     */
    private collectAlreadyInScopeNames(scriptRoot: ScriptType): Set<string> {
        const names = new Set<string>();
        for (const func of scriptRoot.functions) {
            names.add(func.name);
        }
        for (const fileImport of scriptRoot.imports) {
            for (const imp of fileImport.imports) {
                const name = imp.name ?? imp.entity.ref?.name ?? imp.entity.$refText;
                if (name) {
                    names.add(name);
                }
            }
        }
        return names;
    }
}
