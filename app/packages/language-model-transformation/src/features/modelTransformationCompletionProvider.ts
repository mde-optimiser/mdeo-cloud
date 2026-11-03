import { ExpressionCompletionProvider, type ExpressionTypes, type TypeTypes } from "@mdeo/language-expression";
import { computeRelativePathCompletions, acceptRelativePathCompletions, sharedImport } from "@mdeo/language-shared";
import type { CompletionAcceptor, CompletionContext, CompletionProviderOptions, NextFeature } from "langium/lsp";
import type { AstNodeDescription, LangiumDocuments, ReferenceInfo, Stream } from "langium";
import type { AstSerializerAdditionalServices, ExtendedLangiumServices } from "@mdeo/language-common";
import type { ModelTransformationTypirServices } from "../plugin.js";
import {
    CallArgument,
    CallOutput,
    MetamodelFileImport,
    TransformationFileImport,
    type TransformationParameterType
} from "../grammar/modelTransformationTypes.js";

const { AstUtils } = sharedImport("langium");

/**
 * Completion provider for the Model Transformation language.
 *
 * Extends expression completion with relative path completions for the metamodel
 * file path in `using "..."` statements and the transformation path of `import "..." as name`,
 * and offers only the callee's `in` parameters as the
 * arguments of a call and only its `out` parameters after `->`.
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

    /**
     * Restricts the parameters offered in a call to those of the right direction: inputs inside
     * `( … )`, outputs after `->`.
     *
     * @param refInfo The reference being completed
     * @param context The completion context
     * @returns The candidates
     */
    protected override getReferenceCandidates(
        refInfo: ReferenceInfo,
        context: CompletionContext
    ): Stream<AstNodeDescription> {
        const candidates = super.getReferenceCandidates(refInfo, context);
        if (refInfo.property !== "parameter") {
            return candidates;
        }
        const direction = this.reflection.isInstance(refInfo.container, CallArgument)
            ? "in"
            : this.reflection.isInstance(refInfo.container, CallOutput)
              ? "out"
              : undefined;
        if (direction == undefined) {
            return candidates;
        }
        return candidates.filter(
            (candidate) => (candidate.node as TransformationParameterType | undefined)?.direction === direction
        );
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

        if (next.property !== "file") {
            return;
        }
        const extensions = this.reflection.isInstance(node, MetamodelFileImport)
            ? [".mm"]
            : this.reflection.isInstance(node, TransformationFileImport)
              ? [".mt"]
              : undefined;
        if (extensions == undefined) {
            return;
        }

        try {
            const document = AstUtils.getDocument(node);
            const paths = computeRelativePathCompletions(document, this.documents, extensions);
            acceptRelativePathCompletions(context, acceptor, paths);
        } catch {
            // Ignore errors during completion
        }
    }
}
