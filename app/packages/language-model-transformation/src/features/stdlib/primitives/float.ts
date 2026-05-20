import { floatType, classTypeFrom } from "@mdeo/language-expression";
import type { ClassType } from "@mdeo/language-expression";

/**
 * Float type for model transformation.
 */
export const modelTransformationFloatType: ClassType = classTypeFrom(floatType)
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
