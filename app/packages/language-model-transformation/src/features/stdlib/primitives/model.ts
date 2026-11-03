import { classType, DefaultTypeNames } from "@mdeo/language-expression";

/**
 * The name of the built-in type of model values.
 */
export const MODEL_TYPE = "model";

/**
 * The type of model values (`in donor: model`): another model of the transformation's
 * metamodel, which scope blocks (`in donor { … }`) read. It has no members of its own; the
 * validator restricts where a model value may appear (scope block targets, call arguments,
 * `for … in` over a `List<model>`).
 */
export const modelTransformationModelType = classType(MODEL_TYPE).extends(DefaultTypeNames.Any).build();
