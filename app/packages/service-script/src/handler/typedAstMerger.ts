import {
    TypedAstMerger as BaseTypedAstMerger,
    type ReturnType,
    type TypedExpression,
    type TypedExtensionCallExpression,
    type TypedExtensionCallArgument,
    type TypedCallableBody
} from "@mdeo/language-expression";
import type { TypedLambdaExpression } from "@mdeo/language-script";

/**
 * Script-specific extension of TypedAstMerger that adds support for
 * script-specific features like functions, lambdas, and extension calls.
 */
export class ScriptTypedAstMerger extends BaseTypedAstMerger {
    /**
     * Remaps a contributed body, whose type indices refer to its contribution's own types array.
     *
     * @param body The body to remap
     * @param typesArray The contribution's types array
     * @returns The body with global type indices
     */
    remapBody(body: TypedCallableBody, typesArray: ReturnType[]): TypedCallableBody {
        return this.remapCallableBody(body, this.indexTypesArray(typesArray));
    }

    /**
     * Handles script-specific expression types (lambda, extensionCall).
     *
     * @param expr The expression to remap
     * @param mapping The index mapping
     * @returns A new expression with remapped type indices
     */
    protected override remapAdditionalExpression(expr: TypedExpression, mapping: Map<number, number>): TypedExpression {
        switch (expr.kind) {
            case "lambda":
                return this.remapLambdaExpression(expr as TypedLambdaExpression, mapping);
            case "extensionCall":
                return this.remapExtensionCallExpression(expr as TypedExtensionCallExpression, mapping);
        }
        return super.remapAdditionalExpression(expr, mapping);
    }

    /**
     * Remaps a lambda expression.
     *
     * @param expr The lambda expression to remap
     * @param mapping The index mapping
     * @returns A new lambda expression with remapped type indices
     */
    private remapLambdaExpression(expr: TypedLambdaExpression, mapping: Map<number, number>): TypedLambdaExpression {
        return {
            kind: "lambda",
            evalType: mapping.get(expr.evalType)!,
            parameters: expr.parameters,
            body: this.remapCallableBody(expr.body, mapping),
            hasBlockBody: expr.hasBlockBody
        };
    }

    /**
     * Remaps an extension call expression.
     *
     * @param expr The extension call expression to remap
     * @param mapping The index mapping
     * @returns A new extension call expression with remapped type indices
     */
    private remapExtensionCallExpression(
        expr: TypedExtensionCallExpression,
        mapping: Map<number, number>
    ): TypedExtensionCallExpression {
        return {
            kind: "extensionCall",
            evalType: mapping.get(expr.evalType)!,
            name: expr.name,
            overload: expr.overload,
            arguments: expr.arguments.map((arg) => this.remapExtensionCallArgument(arg, mapping))
        };
    }

    /**
     * Remaps an extension call argument.
     *
     * @param arg The extension call argument to remap
     * @param mapping The index mapping
     * @returns A new extension call argument with remapped type indices
     */
    private remapExtensionCallArgument(
        arg: TypedExtensionCallArgument,
        mapping: Map<number, number>
    ): TypedExtensionCallArgument {
        return {
            name: arg.name,
            value: this.remapExpression(arg.value, mapping)
        };
    }
}
