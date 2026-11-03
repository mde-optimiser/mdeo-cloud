import {
    OrderedCollectionType,
    ReadonlyOrderedCollectionType,
    classTypeFrom,
    genericTypeRef
} from "@mdeo/language-expression";
import type { ClassType } from "@mdeo/language-expression";

const orderedCollection = classTypeFrom(OrderedCollectionType)
    .clearExtends()
    .extends("Collection", { T: genericTypeRef("T") });
orderedCollection.methods.indexOf = ReadonlyOrderedCollectionType.methods.indexOf;
orderedCollection.methods.last = ReadonlyOrderedCollectionType.methods.last;

/**
 * Immutable OrderedCollection type for model transformation, with `indexOf` and `last` of
 * the script language's ordered collections (the engine implements both).
 */
export const ModelTransformationOrderedCollectionType: ClassType = orderedCollection.build();
