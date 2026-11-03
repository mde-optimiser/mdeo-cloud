import type { AstReflection, AstSerializerAdditionalServices, ExtendedLangiumServices } from "@mdeo/language-common";
import {
    BaseCompletionProvider,
    computeRelativePathCompletions,
    acceptRelativePathCompletions,
    resolveRelativePath,
    sharedImport
} from "@mdeo/language-shared";
import type { CompletionAcceptor, CompletionContext, CompletionProviderOptions, NextFeature } from "langium/lsp";
import type { LangiumDocuments, MaybePromise } from "langium";
import {
    CrossoversBlock,
    MutationsBlock,
    OperatorArgument,
    UsingPath,
    type CrossoversBlockType,
    type MutationsBlockType,
    type OperatorArgumentType,
    type UsingPathType
} from "../grammar/mdeoTypes.js";
import { ArgumentNameRule } from "../grammar/mdeoRules.js";
import { describeTransformationInputs } from "@mdeo/language-model-transformation";
import { argumentName, findUsedTransformation, renderDeclaration } from "./operatorArguments.js";
import { isPassableInput } from "../validation/operatorArgumentsValidator.js";
import type { Range } from "vscode-languageserver-types";

const { AstUtils, GrammarAST } = sharedImport("langium");
const { CompletionItemKind } = sharedImport("vscode-languageserver-protocol");

/**
 * Completion provider for the MDEO config language.
 *
 * Provides relative path completions for file path string properties such as
 * using paths (model transformation files), and the inputs of the transformation inside the
 * `with { }` block of a using entry.
 */
export class MdeoCompletionProvider extends BaseCompletionProvider {
    override readonly completionOptions: CompletionProviderOptions = { triggerCharacters: ['"', "/", "."] };

    private readonly documents: LangiumDocuments;
    private readonly reflection: AstReflection;

    /**
     * @param services Combined Langium services
     */
    constructor(services: ExtendedLangiumServices & AstSerializerAdditionalServices) {
        super(services);
        this.documents = services.shared.workspace.LangiumDocuments;
        this.reflection = services.shared.AstReflection;
    }

    /**
     * Extends the default completion with relative path suggestions for file path properties.
     *
     * Injects path completions before delegating to the default provider, so that
     * file path fields (e.g., model transformation "using" paths) offer workspace-relative
     * suggestions.
     *
     * @param context The current completion context
     * @param next Describes the grammar feature being completed, including its type and property
     * @param acceptor The acceptor function to register completion items
     * @returns A promise or void when completion is complete
     */
    protected override completionFor(
        context: CompletionContext,
        next: NextFeature,
        acceptor: CompletionAcceptor
    ): MaybePromise<void> {
        if (this.isArgumentName(next)) {
            this.completionForArgumentName(context, acceptor);
            return;
        }
        this.completionForRelativePath(context, next, acceptor);
        return super.completionFor(context, next, acceptor);
    }

    /**
     * Whether the feature being completed is the name of an operator argument. Its keyword
     * alternatives (input names that are also config keywords) are not offered as keywords.
     *
     * @param next The grammar feature being completed
     * @returns True inside the name of an argument
     */
    private isArgumentName(next: NextFeature): boolean {
        return AstUtils.getContainerOfType(next.feature, GrammarAST.isParserRule)?.name === ArgumentNameRule.name;
    }

    /**
     * Offers the inputs of the transformation a `with { }` block belongs to that the config can
     * set and the block does not set yet.
     *
     * @param context The current completion context
     * @param acceptor The acceptor function to register completion items
     */
    private completionForArgumentName(context: CompletionContext, acceptor: CompletionAcceptor): void {
        const node = context.node;
        if (node == undefined) {
            return;
        }
        const usingPath = AstUtils.getContainerOfType(node, (n): n is UsingPathType =>
            this.reflection.isInstance(n, UsingPath)
        );
        if (usingPath == undefined) {
            return;
        }
        const transformation = findUsedTransformation(usingPath, this.documents, this.reflection);
        if (transformation == undefined) {
            return;
        }
        const current = AstUtils.getContainerOfType(node, (n): n is OperatorArgumentType =>
            this.reflection.isInstance(n, OperatorArgument)
        );
        const set = new Set((usingPath.with?.arguments ?? []).filter((a) => a !== current).map((a) => argumentName(a)));
        for (const input of describeTransformationInputs(transformation, this.reflection, this.documents)) {
            if (!isPassableInput(input) || set.has(input.name)) {
                continue;
            }
            acceptor(context, {
                label: input.name,
                kind: CompletionItemKind.Property,
                detail: renderDeclaration(input.parameter, this.reflection),
                documentation: input.optional ? "optional input" : "required input",
                insertText: `${input.name} = `,
                sortText: `0${input.optional ? 1 : 0}${input.name}`
            });
        }
    }

    /**
     * Provides relative path completions for file path string properties in the
     * MDEO config language, such as "using" paths for model transformation files.
     *
     * Paths already referenced by other `UsingPath` entries in the same mutations or
     * crossovers block are not suggested again (an entry of the same file with other
     * arguments is written by hand).
     *
     * @param context The current completion context
     * @param next Describes the grammar feature being completed
     * @param acceptor The acceptor function to register completion items
     * @returns void
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
        let existingPaths: Set<string> | undefined;

        if (this.reflection.isInstance(node, UsingPath) && next.property === "path") {
            extensions = [".mt"];

            const operatorsBlock = AstUtils.getContainerOfType(
                node,
                (n): n is MutationsBlockType | CrossoversBlockType =>
                    this.reflection.isInstance(n, MutationsBlock) || this.reflection.isInstance(n, CrossoversBlock)
            );
            if (operatorsBlock != null) {
                const document = AstUtils.getDocument(node);
                existingPaths = new Set(
                    operatorsBlock.usingPaths
                        .filter((up) => up !== node)
                        .map((up) => resolveRelativePath(document, up.path).toString())
                );
            }
        }

        if (extensions == undefined) {
            return;
        }

        try {
            const document = AstUtils.getDocument(node);
            let paths = computeRelativePathCompletions(document, this.documents, extensions);

            if (existingPaths != undefined && existingPaths.size > 0) {
                paths = paths.filter((path) => !existingPaths!.has(resolveRelativePath(document, path).toString()));
            }

            acceptRelativePathCompletions(context, acceptor, paths);

            this.completionForInsertAllTransformations(context, paths, acceptor);
        } catch {
            // Ignore errors during completion
        }
    }

    /**
     * Adds a special "insert all model transformations" completion item when two or more
     * `.mt` files are missing from the current `MutationsBlock`.
     *
     * When selected the item replaces the current `using "..."` statement (including the
     * `using` keyword) with one `using "..."` line per missing transformation, each sharing
     * the same leading indentation as the original statement.
     *
     * @param context  The current completion context.
     * @param missingPaths Relative paths of `.mt` files not yet referenced in the block.
     * @param acceptor  The LSP completion acceptor.
     */
    private completionForInsertAllTransformations(
        context: CompletionContext,
        missingPaths: string[],
        acceptor: CompletionAcceptor
    ): void {
        if (missingPaths.length < 2) {
            return;
        }

        const existingText = context.textDocument.getText().substring(context.tokenOffset, context.offset);
        let range: Range = { start: context.position, end: context.position };

        let insideString = false;
        if (existingText.length > 0) {
            insideString = existingText.startsWith('"') || existingText.startsWith("'");
            if (insideString) {
                const start = context.textDocument.positionAt(context.tokenOffset + 1);
                const end = context.textDocument.positionAt(context.tokenEndOffset - 1);
                range = { start, end };
            } else {
                const start = context.textDocument.positionAt(context.tokenOffset);
                const end = context.textDocument.positionAt(context.tokenEndOffset);
                range = { start, end };
            }
        }

        const newText = missingPaths.map((path, i) => (i === 0 ? `${path}` : `"\nusing "${path}`)).join("");
        const delimiter = insideString ? "" : '"';

        acceptor(context, {
            label: "(Insert compatible transformations)",
            kind: CompletionItemKind.Snippet,
            detail: `Insert ${missingPaths.length} compatible transformations`,
            textEdit: {
                newText: `${delimiter}${newText}${delimiter}`,
                range
            },
            sortText: "0"
        });
    }
}
