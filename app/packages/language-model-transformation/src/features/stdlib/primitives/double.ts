import { doubleType, classTypeFrom } from "@mdeo/language-expression";
import type { ClassType } from "@mdeo/language-expression";

/**
 * Double type for model transformation.
 */
export const modelTransformationDoubleType: ClassType = classTypeFrom(doubleType)
    .keepMembers(
        "abs",
        "floor",
        "ceil",
        "round",
        "log",
        "log10",
        "pow",
        "max",
        "min",
        "sqrt",
        "sin",
        "cos",
        "tan",
        "asin",
        "acos",
        "atan",
        "sinh",
        "cosh",
        "tanh"
    )
    .build();
