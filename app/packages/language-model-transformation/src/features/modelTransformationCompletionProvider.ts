import { ExpressionCompletionProvider, type ExpressionTypes, type TypeTypes } from "@mdeo/language-expression";
import { computeRelativePathCompletions, acceptRelativePathCompletions, sharedImport } from "@mdeo/language-shared";
import type { CompletionAcceptor, CompletionContext, CompletionProviderOptions, NextFeature } from "langium/lsp";
import type { LangiumDocuments } from "langium";
import type { AstSerializerAdditionalServices, ExtendedLangiumServices } from "@mdeo/language-common";
import type { ModelTransformationTypirServices } from "../plugin.js";
import { MetamodelFileImport } from "../grammar/modelTransformationTypes.js";

const { AstUtils } = sharedImport("langium");

/**
 * Completion provider for the Model Transformation language.
 *
 * Extends expression completion with relative path completions for the metamodel
 * file path in `using "..."` statements.
 */
export class ModelTransformationCompletionProvider extends ExpressionCompletionProvider {
    override readonly completionOptions: CompletionProviderOptions = { triggerCharacters: ['"', "/", "."] };

    private readonly documents: LangiumDocuments;

    constructor(
        services: { typir: ModelTransformationTypirServices } & ExtendedLangiumServices &
            AstSerializerAdditionalServices,
        expressionTypes: ExpressionTypes,
        typeTypes: TypeTypes
    ) {
        super(services, expressionTypes, typeTypes);
        this.documents = services.shared.workspace.LangiumDocuments;
    }

    protected override async completionFor(
        context: CompletionContext,
        next: NextFeature,
        acceptor: CompletionAcceptor
    ): Promise<void> {
        await super.completionFor(context, next, acceptor);

        this.completionForRelativePath(context, next, acceptor);
    }

    private completionForRelativePath(
        context: CompletionContext,
        next: NextFeature,
        acceptor: CompletionAcceptor
    ): void {
        const node = context.node;
        if (node == undefined) {
            return;
        }

        if (!this.reflection.isInstance(node, MetamodelFileImport) || next.property !== "file") {
            return;
        }

        try {
            const document = AstUtils.getDocument(node);
            const paths = computeRelativePathCompletions(document, this.documents, [".mm"]);
            acceptRelativePathCompletions(context, acceptor, paths);
        } catch {
            // Ignore errors during completion
        }
    }
}
