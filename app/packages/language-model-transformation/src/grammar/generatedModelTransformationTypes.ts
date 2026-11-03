import { createInterface, type ASTType } from "@mdeo/language-common";

/**
 * Generated model transformation root interface.
 * Contains raw typed-ast JSON content as a single string field.
 */
export const GeneratedModelTransformation = createInterface("GeneratedModelTransformation").attrs({
    content: String
});

/**
 * Generated model transformation AST type.
 */
export type GeneratedModelTransformationType = ASTType<typeof GeneratedModelTransformation>;

/**
 * Stands in for an expression of a generated transformation shown as a regular one: the typed
 * AST is rendered to the expression's text, which the node keeps as its CST text.
 */
export const RenderedExpression = createInterface("IdentifierExpression").attrs({});
