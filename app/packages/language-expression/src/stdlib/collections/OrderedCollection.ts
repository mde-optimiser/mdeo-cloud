import { DefaultTypeNames } from "../../type-system/typeSystemConfig.js";
import { classType, typeRef, genericTypeRef, lambdaType } from "../../typir-extensions/config/typeBuilder.js";

/**
 * The built-in generic OrderedCollection type exported as `OrderedCollectionType`.
 */
export const OrderedCollectionType = classType("OrderedCollection")
    .generics("T")
    .extends("ReadonlyOrderedCollection", { T: genericTypeRef("T") })
    .extends("Collection", { T: genericTypeRef("T") })
    .method("removeAt", (m) =>
        m.signature((s) =>
            s.param("index", typeRef("builtin", DefaultTypeNames.Int).build()).returns(genericTypeRef("T"))
        )
    )
    .method("sort", (m) =>
        m
            .signature("natural", (s) =>
                s.returns(
                    typeRef("builtin", "OrderedCollection")
                        .withTypeArgs({ T: genericTypeRef("T") })
                        .build()
                )
            )
            .signature("comparator", (s) =>
                s
                    .param(
                        "comparator",
                        lambdaType()
                            .param("a", genericTypeRef("T"))
                            .param("b", genericTypeRef("T"))
                            .returns(typeRef("builtin", DefaultTypeNames.Int).build())
                    )
                    .returns(
                        typeRef("builtin", "OrderedCollection")
                            .withTypeArgs({ T: genericTypeRef("T") })
                            .build()
                    )
            )
    )
    .method("sortBy", (m) =>
        m.signature((s) =>
            s
                .generics("U")
                .param("iterator", lambdaType().param("it", genericTypeRef("T")).returns(genericTypeRef("U")))
                .returns(
                    typeRef("builtin", "OrderedCollection")
                        .withTypeArgs({ T: genericTypeRef("T") })
                        .build()
                )
        )
    )
    .build();
