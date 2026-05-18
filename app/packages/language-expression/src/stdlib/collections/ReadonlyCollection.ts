import { DefaultTypeNames } from "../../type-system/typeSystemConfig.js";
import { classType, typeRef, lambdaType, genericTypeRef, voidType } from "../../typir-extensions/config/typeBuilder.js";

/**
 * The built-in generic ReadonlyCollection type exported as `ReadonlyCollectionType`.
 */
export const ReadonlyCollectionType = classType("ReadonlyCollection")
    .generics("T")
    .extends(DefaultTypeNames.Any)
    .extends(DefaultTypeNames.Iterable, { T: genericTypeRef("T") })
    .method("toBag", (m) =>
        m.signature((s) =>
            s.returns(
                typeRef("builtin", "Bag")
                    .withTypeArgs({ T: genericTypeRef("T") })
                    .build()
            )
        )
    )
    .method("toOrderedSet", (m) =>
        m.signature((s) =>
            s.returns(
                typeRef("builtin", "OrderedSet")
                    .withTypeArgs({ T: genericTypeRef("T") })
                    .build()
            )
        )
    )
    .method("toList", (m) =>
        m.signature((s) =>
            s.returns(
                typeRef("builtin", "List")
                    .withTypeArgs({ T: genericTypeRef("T") })
                    .build()
            )
        )
    )
    .method("toSet", (m) =>
        m.signature((s) =>
            s.returns(
                typeRef("builtin", "Set")
                    .withTypeArgs({ T: genericTypeRef("T") })
                    .build()
            )
        )
    )
    .method("clone", (m) =>
        m.signature((s) =>
            s.returns(
                typeRef("builtin", "Collection")
                    .withTypeArgs({ T: genericTypeRef("T") })
                    .build()
            )
        )
    )
    .method("concat", (m) =>
        m
            .signature("nosep", (s) => s.returns(typeRef("builtin", DefaultTypeNames.String).build()))
            .signature("sep", (s) =>
                s
                    .param("separator", typeRef("builtin", DefaultTypeNames.String).build())
                    .returns(typeRef("builtin", DefaultTypeNames.String).build())
            )
    )
    .method("count", (m) =>
        m
            .signature("noit", (s) =>
                s
                    .param("item", typeRef("builtin", DefaultTypeNames.Any).build())
                    .returns(typeRef("builtin", DefaultTypeNames.Int).build())
            )
            .signature("it", (s) =>
                s
                    .param(
                        "iterator",
                        lambdaType()
                            .param("it", genericTypeRef("T"))
                            .returns(typeRef("builtin", DefaultTypeNames.Boolean).build())
                    )
                    .returns(typeRef("builtin", DefaultTypeNames.Int).build())
            )
    )
    .method("excludes", (m) =>
        m.signature((s) =>
            s
                .param("item", typeRef("builtin", DefaultTypeNames.Any).build())
                .returns(typeRef("builtin", DefaultTypeNames.Boolean).build())
        )
    )
    .method("excludesAll", (m) =>
        m.signature((s) =>
            s
                .param(
                    "col",
                    typeRef("builtin", "Collection")
                        .withTypeArgs({ T: typeRef("builtin", DefaultTypeNames.Any).build() })
                        .build()
                )
                .returns(typeRef("builtin", DefaultTypeNames.Boolean).build())
        )
    )
    .method("excluding", (m) =>
        m.signature((s) =>
            s.param("item", typeRef("builtin", DefaultTypeNames.Any).build()).returns(
                typeRef("builtin", "Collection")
                    .withTypeArgs({ T: genericTypeRef("T") })
                    .build()
            )
        )
    )
    .method("excludingAll", (m) =>
        m.signature((s) =>
            s
                .param(
                    "col",
                    typeRef("builtin", "Collection")
                        .withTypeArgs({ T: typeRef("builtin", DefaultTypeNames.Any).build() })
                        .build()
                )
                .returns(
                    typeRef("builtin", "Collection")
                        .withTypeArgs({ T: genericTypeRef("T") })
                        .build()
                )
        )
    )
    .method("flatten", (m) =>
        m.signature((s) =>
            s.returns(
                typeRef("builtin", "Collection")
                    .withTypeArgs({ T: typeRef("builtin", DefaultTypeNames.Any).build() })
                    .build()
            )
        )
    )
    .method("includes", (m) =>
        m.signature((s) =>
            s
                .param("item", typeRef("builtin", DefaultTypeNames.Any).build())
                .returns(typeRef("builtin", DefaultTypeNames.Boolean).build())
        )
    )
    .method("includesAll", (m) =>
        m.signature((s) =>
            s
                .param(
                    "col",
                    typeRef("builtin", "Collection")
                        .withTypeArgs({ T: typeRef("builtin", DefaultTypeNames.Any).build() })
                        .build()
                )
                .returns(typeRef("builtin", DefaultTypeNames.Boolean).build())
        )
    )
    .method("including", (m) =>
        m.signature((s) =>
            s.param("item", typeRef("builtin", DefaultTypeNames.Any).build()).returns(
                typeRef("builtin", "Collection")
                    .withTypeArgs({ T: genericTypeRef("T") })
                    .build()
            )
        )
    )
    .method("includingAll", (m) =>
        m.signature((s) =>
            s
                .param(
                    "col",
                    typeRef("builtin", "Collection")
                        .withTypeArgs({ T: typeRef("builtin", DefaultTypeNames.Any).build() })
                        .build()
                )
                .returns(
                    typeRef("builtin", "Collection")
                        .withTypeArgs({ T: genericTypeRef("T") })
                        .build()
                )
        )
    )
    .method("isEmpty", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.Boolean).build())))
    .method("notEmpty", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.Boolean).build())))
    .method("random", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.Any).build())))
    .method("size", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.Int).build())))
    .method("sum", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.Double).build())))
    .method("atLeastNMatch", (m) =>
        m.signature((s) =>
            s
                .param(
                    "iterator",
                    lambdaType()
                        .param("it", genericTypeRef("T"))
                        .returns(typeRef("builtin", DefaultTypeNames.Boolean).build())
                )
                .param("n", typeRef("builtin", DefaultTypeNames.Int).build())
                .returns(typeRef("builtin", DefaultTypeNames.Boolean).build())
        )
    )
    .method("atMostNMatch", (m) =>
        m.signature((s) =>
            s
                .param(
                    "iterator",
                    lambdaType()
                        .param("it", genericTypeRef("T"))
                        .returns(typeRef("builtin", DefaultTypeNames.Boolean).build())
                )
                .param("n", typeRef("builtin", DefaultTypeNames.Int).build())
                .returns(typeRef("builtin", DefaultTypeNames.Boolean).build())
        )
    )
    .method("aggregate", (m) =>
        m.signature((s) =>
            s
                .param(
                    "iterator",
                    lambdaType()
                        .param("it", genericTypeRef("T"))
                        .returns(typeRef("builtin", DefaultTypeNames.Any).build())
                )
                .returns(
                    typeRef("builtin", "Map")
                        .withTypeArgs({
                            K: typeRef("builtin", DefaultTypeNames.Any).build(),
                            V: typeRef("builtin", DefaultTypeNames.Any).build()
                        })
                        .build()
                )
        )
    )
    .method("map", (m) =>
        m.signature((s) =>
            s
                .generics("U")
                .param("iterator", lambdaType().param("it", genericTypeRef("T")).returns(genericTypeRef("U")))
                .returns(
                    typeRef("builtin", "List")
                        .withTypeArgs({ T: genericTypeRef("U") })
                        .build()
                )
        )
    )
    .method("exists", (m) =>
        m.signature((s) =>
            s
                .param(
                    "iterator",
                    lambdaType()
                        .param("it", genericTypeRef("T"))
                        .returns(typeRef("builtin", DefaultTypeNames.Boolean).build())
                )
                .returns(typeRef("builtin", DefaultTypeNames.Boolean).build())
        )
    )
    .method("forEach", (m) =>
        m.signature((s) =>
            s.param("iterator", lambdaType().param("it", genericTypeRef("T")).returns(voidType())).returns(voidType())
        )
    )
    .method("associate", (m) =>
        m.signature((s) =>
            s
                .generics("U")
                .param("iterator", lambdaType().param("it", genericTypeRef("T")).returns(genericTypeRef("U")))
                .returns(
                    typeRef("builtin", "ReadonlyMap")
                        .withTypeArgs({
                            K: genericTypeRef("T"),
                            V: genericTypeRef("U")
                        })
                        .build()
                )
        )
    )
    .method("nMatch", (m) =>
        m.signature((s) =>
            s
                .param(
                    "iterator",
                    lambdaType()
                        .param("it", genericTypeRef("T"))
                        .returns(typeRef("builtin", DefaultTypeNames.Boolean).build())
                )
                .param("n", typeRef("builtin", DefaultTypeNames.Int).build())
                .returns(typeRef("builtin", DefaultTypeNames.Boolean).build())
        )
    )
    .method("none", (m) =>
        m.signature((s) =>
            s
                .param(
                    "iterator",
                    lambdaType()
                        .param("it", genericTypeRef("T"))
                        .returns(typeRef("builtin", DefaultTypeNames.Boolean).build())
                )
                .returns(typeRef("builtin", DefaultTypeNames.Boolean).build())
        )
    )
    .method("one", (m) =>
        m.signature((s) =>
            s
                .param(
                    "iterator",
                    lambdaType()
                        .param("it", genericTypeRef("T"))
                        .returns(typeRef("builtin", DefaultTypeNames.Boolean).build())
                )
                .returns(typeRef("builtin", DefaultTypeNames.Boolean).build())
        )
    )
    .method("reject", (m) =>
        m.signature((s) =>
            s
                .param(
                    "iterator",
                    lambdaType()
                        .param("it", genericTypeRef("T"))
                        .returns(typeRef("builtin", DefaultTypeNames.Boolean).build())
                )
                .returns(
                    typeRef("builtin", "Collection")
                        .withTypeArgs({ T: genericTypeRef("T") })
                        .build()
                )
        )
    )
    .method("rejectOne", (m) =>
        m.signature((s) =>
            s
                .param(
                    "iterator",
                    lambdaType()
                        .param("it", genericTypeRef("T"))
                        .returns(typeRef("builtin", DefaultTypeNames.Boolean).build())
                )
                .returns(
                    typeRef("builtin", "Collection")
                        .withTypeArgs({ T: genericTypeRef("T") })
                        .build()
                )
        )
    )
    .method("filter", (m) =>
        m.signature((s) =>
            s
                .param(
                    "iterator",
                    lambdaType()
                        .param("it", genericTypeRef("T"))
                        .returns(typeRef("builtin", DefaultTypeNames.Boolean).build())
                )
                .returns(
                    typeRef("builtin", "Collection")
                        .withTypeArgs({ T: genericTypeRef("T") })
                        .build()
                )
        )
    )
    .method("all", (m) =>
        m.signature((s) =>
            s
                .param(
                    "iterator",
                    lambdaType()
                        .param("it", genericTypeRef("T"))
                        .returns(typeRef("builtin", DefaultTypeNames.Boolean).build())
                )
                .returns(typeRef("builtin", DefaultTypeNames.Boolean).build())
        )
    )
    .method("find", (m) =>
        m.signature((s) =>
            s
                .param(
                    "iterator",
                    lambdaType()
                        .param("it", genericTypeRef("T"))
                        .returns(typeRef("builtin", DefaultTypeNames.Boolean).build())
                )
                .returns(typeRef("builtin", DefaultTypeNames.Any).build())
        )
    )
    .method("sortedBy", (m) =>
        m.signature((s) =>
            s
                .generics("U")
                .param("iterator", lambdaType().param("it", genericTypeRef("T")).returns(genericTypeRef("U")))
                .returns(
                    typeRef("builtin", "ReadonlyOrderedCollection")
                        .withTypeArgs({ T: genericTypeRef("T") })
                        .build()
                )
        )
    )
    .method("flatMap", (m) =>
        m.signature((s) =>
            s
                .generics("U")
                .param(
                    "iterator",
                    lambdaType()
                        .param("it", genericTypeRef("T"))
                        .returns(
                            typeRef("builtin", "Collection")
                                .withTypeArgs({ T: genericTypeRef("U") })
                                .build()
                        )
                )
                .returns(
                    typeRef("builtin", "Collection")
                        .withTypeArgs({ T: genericTypeRef("U") })
                        .build()
                )
        )
    )
    .method("sorted", (m) =>
        m
            .signature("natural", (s) =>
                s.returns(
                    typeRef("builtin", "ReadonlyOrderedCollection")
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
                        typeRef("builtin", "ReadonlyOrderedCollection")
                            .withTypeArgs({ T: genericTypeRef("T") })
                            .build()
                    )
            )
    )
    .method("first", (m) => m.signature((s) => s.returns(genericTypeRef("T"))))
    .method("firstOrNull", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.Any).build())))
    .build();
