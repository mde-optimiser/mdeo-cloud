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
