import type { ReturnStatementAccessor, ExpressionTypirServices } from "@mdeo/language-expression";
import type { AstNode } from "langium";
import type { TypirLangiumSpecifics } from "typir-langium";
import { ReturnStatement } from "../../grammar/scriptTypes.js";

/**
 * Provides access to return statements in the Script language for return type analysis.
 */
export class ScriptReturnStatementAccessor<
    Specifics extends TypirLangiumSpecifics = TypirLangiumSpecifics
> implements ReturnStatementAccessor<Specifics> {
    constructor(private readonly typir: ExpressionTypirServices<Specifics>) {}

    isReturnStatement(node: AstNode): boolean {
        return this.typir.langium.LangiumServices.AstReflection.isInstance(node, ReturnStatement);
    }

    getReturnExpression(node: AstNode): AstNode | undefined {
        if (this.typir.langium.LangiumServices.AstReflection.isInstance(node, ReturnStatement)) {
            return node.value;
        }
        return undefined;
    }
}
