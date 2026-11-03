import { resolveRelativeDocument, sharedImport } from "@mdeo/language-shared";
import type { CstNode, LangiumDocuments, MaybePromise } from "langium";
import type { LangiumServices } from "langium/lsp";
import type { DefinitionParams, LocationLink } from "vscode-languageserver-protocol";
import { TransformationFileImport, type TransformationFileImportType } from "../grammar/modelTransformationTypes.js";

const { DefaultDefinitionProvider } = sharedImport("langium/lsp");
const { AstUtils } = sharedImport("langium");

/**
 * Definition provider of the Model Transformation language.
 *
 * Go-to-definition on the alias or the path of an import (`import "./f.mt" as f`) jumps into the
 * imported file, like it does on the alias of a call (whose reference resolves to the imported
 * file's root).
 */
export class ModelTransformationDefinitionProvider extends DefaultDefinitionProvider {
    private readonly documents: LangiumDocuments;

    constructor(services: LangiumServices) {
        super(services);
        this.documents = services.shared.workspace.LangiumDocuments;
    }

    protected override collectLocationLinks(
        sourceCstNode: CstNode,
        params: DefinitionParams
    ): MaybePromise<LocationLink[] | undefined> {
        const node = sourceCstNode.astNode;
        if (node.$type === TransformationFileImport.name) {
            const fileImport = node as TransformationFileImportType;
            const target =
                fileImport.file != undefined
                    ? resolveRelativeDocument(AstUtils.getDocument(fileImport), fileImport.file, this.documents)
                    : undefined;
            const targetRoot = target?.parseResult?.value?.$cstNode;
            if (target == undefined || targetRoot == undefined) {
                return undefined;
            }
            const start = { start: targetRoot.range.start, end: targetRoot.range.start };
            return [
                {
                    targetUri: target.textDocument.uri,
                    targetRange: targetRoot.range,
                    targetSelectionRange: start,
                    originSelectionRange: sourceCstNode.range
                }
            ];
        }
        return super.collectLocationLinks(sourceCstNode, params);
    }
}
