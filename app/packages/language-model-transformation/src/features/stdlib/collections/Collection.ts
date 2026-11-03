import { ReadonlyCollectionType, classTypeFrom } from "@mdeo/language-expression";
import type { ClassType } from "@mdeo/language-expression";

/**
 * Immutable Collection type for model transformation.
 */
export const ModelTransformationCollectionType: ClassType = classTypeFrom(ReadonlyCollectionType, "Collection")
    .keepMembers(
        "size",
        "isEmpty",
        "notEmpty",
        "sum",
        "first",
        "last",
        "filter",
        "map",
        "exists",
        "all",
        "none",
        "one",
        "find",
        "reject",
        "includes"
    )
    .build();
