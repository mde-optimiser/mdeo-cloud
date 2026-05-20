import { longType, classTypeFrom } from "@mdeo/language-expression";
import type { ClassType } from "@mdeo/language-expression";

/**
 * Long type for model transformation.
 */
export const modelTransformationLongType: ClassType = classTypeFrom(longType)
    .keepMembers("abs", "floor", "ceil", "round", "log", "log10", "pow", "mod", "max", "min")
    .build();
