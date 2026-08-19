import { sharedImport } from "@mdeo/language-shared";
import type { LangiumDocument, AstNodeDescription, AstNode, MultiMap } from "langium";
import {
    MatchStatement,
    PatternApplicationCondition,
    PatternObjectInstance,
    PatternVariable
} from "../grammar/modelTransformationTypes.js";
import type { ModelTransformationType } from "../grammar/modelTransformationTypes.js";
import type { AstReflection, ExtendedLangiumServices } from "@mdeo/language-common";
import type { CancellationToken } from "vscode-jsonrpc";

const { DefaultScopeComputation, AstUtils } = sharedImport("langium");

/**
 * The scope computation for the Model Transformation language.
 *
 * Registers PatternObjectInstance nodes as local symbols
 * in the closest non-Pattern/MatchStatement container.
 */
export class ModelTransformationScopeComputation extends DefaultScopeComputation {
    /**
     * AST reflection service for type checking.
     */
    private readonly astReflection: AstReflection;

    constructor(services: ExtendedLangiumServices) {
        super(services);
        this.astReflection = services.shared.AstReflection;
    }

    /**
     * Collects exported symbols from a model transformation document.
     * Exports all PatternVariable and PatternObjectInstance nodes at any nesting depth.
     * This is necessary for findUniqueName to work correctly.
     */
    override collectExportedSymbols(
        document: LangiumDocument,
        cancelToken?: CancellationToken
    ): Promise<AstNodeDescription[]> {
        return this.collectExportedSymbolsForNode(
            document.parseResult.value,
            document,
            (root) => {
                const result: AstNode[] = [];
                for (const node of AstUtils.streamAllContents(root as ModelTransformationType)) {
                    if (
                        this.astReflection.isInstance(node, PatternVariable) ||
                        this.astReflection.isInstance(node, PatternObjectInstance)
                    ) {
                        result.push(node);
                    }
                }
                return result;
            },
            cancelToken
        );
    }

    /**
     * Registers a locally declared symbol.
     *
     * An instance declared inside a condition block belongs to that block's own graph, so it
     * is registered on the block: it is visible to the rest of the block, but not to the
     * enclosing pattern or to any sibling block.
     *
     * @param node The node to register
     * @param document The document being scoped
     * @param symbols The symbol table to add to
     */
    protected override addLocalSymbol(
        node: AstNode,
        document: LangiumDocument,
        symbols: MultiMap<AstNode, AstNodeDescription>
    ): void {
        if (this.astReflection.isInstance(node, PatternObjectInstance)) {
            let container: AstNode | undefined;
            if (this.astReflection.isInstance(node.$container, PatternApplicationCondition)) {
                container = node.$container;
            } else {
                container = node.$container?.$container;
                if (this.astReflection.isInstance(container, MatchStatement)) {
                    container = container.$container;
                }
            }
            if (container != undefined) {
                const name = this.nameProvider.getName(node);
                if (name != undefined) {
                    symbols.add(container, this.descriptions.createDescription(node, name, document));
                }
            }
        }
    }
}
