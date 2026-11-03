import { DefaultTypeNames } from "../../type-system/typeSystemConfig.js";
import { classType, typeRef } from "../../typir-extensions/config/typeBuilder.js";

/**
 * The built-in string type exported as `stringType`.
 */
export const stringType = classType(DefaultTypeNames.String)
    .extends(DefaultTypeNames.Any)
    .method("characterAt", (m) =>
        m.signature((s) =>
            s
                .param("index", typeRef("builtin", DefaultTypeNames.Int).build())
                .returns(typeRef("builtin", DefaultTypeNames.String).build())
        )
    )
    .method("concat", (m) =>
        m.signature((s) =>
            s
                .param("str", typeRef("builtin", DefaultTypeNames.String).build())
                .returns(typeRef("builtin", DefaultTypeNames.String).build())
        )
    )
    .method("endsWith", (m) =>
        m.signature((s) =>
            s
                .param("str", typeRef("builtin", DefaultTypeNames.String).build())
                .returns(typeRef("builtin", DefaultTypeNames.String).build())
        )
    )
    .method("firstToLowerCase", (m) =>
        m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.String).build()))
    )
    .method("firstToUpperCase", (m) =>
        m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.String).build()))
    )
    .method("isInteger", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.String).build())))
    .method("isReal", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.String).build())))
    .method("isSubstringOf", (m) =>
        m.signature((s) =>
            s
                .param("str", typeRef("builtin", DefaultTypeNames.String).build())
                .returns(typeRef("builtin", DefaultTypeNames.String).build())
        )
    )
    .method("length", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.Int).build())))
    .method("matches", (m) =>
        m.signature((s) =>
            s
                .param("reg", typeRef("builtin", DefaultTypeNames.String).build())
                .returns(typeRef("builtin", DefaultTypeNames.String).build())
        )
    )
    .method("pad", (m) =>
        m.signature((s) =>
            s
                .param("length", typeRef("builtin", DefaultTypeNames.Int).build())
                .param("padding", typeRef("builtin", DefaultTypeNames.String).build())
                .param("right", typeRef("builtin", DefaultTypeNames.String).build())
                .returns(typeRef("builtin", DefaultTypeNames.String).build())
        )
    )
    .method("replace", (m) =>
        m.signature((s) =>
            s
                .param("reg", typeRef("builtin", DefaultTypeNames.String).build())
                .param("replacement", typeRef("builtin", DefaultTypeNames.String).build())
                .returns(typeRef("builtin", DefaultTypeNames.String).build())
        )
    )
    .method("split", (m) =>
        m.signature((s) =>
            s.param("reg", typeRef("builtin", DefaultTypeNames.String).build()).returns(
                typeRef("builtin", "List")
                    .withTypeArgs({ T: typeRef("builtin", DefaultTypeNames.String).build() })
                    .build()
            )
        )
    )
    .method("startsWith", (m) =>
        m.signature((s) =>
            s
                .param("str", typeRef("builtin", DefaultTypeNames.String).build())
                .returns(typeRef("builtin", DefaultTypeNames.String).build())
        )
    )
    .method("substring", (m) =>
        m
            .signature("1", (s) =>
                s
                    .param("index", typeRef("builtin", DefaultTypeNames.Int).build())
                    .returns(typeRef("builtin", DefaultTypeNames.String).build())
            )
            .signature("2", (s) =>
                s
                    .param("startIndex", typeRef("builtin", DefaultTypeNames.Int).build())
                    .param("endIndex", typeRef("builtin", DefaultTypeNames.Int).build())
                    .returns(typeRef("builtin", DefaultTypeNames.String).build())
            )
    )
    .method("toCharSequence", (m) =>
        m.signature((s) =>
            s.returns(
                typeRef("builtin", "List")
                    .withTypeArgs({ T: typeRef("builtin", DefaultTypeNames.String).build() })
                    .build()
            )
        )
    )
    .method("toLowerCase", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.String).build())))
    .method("toUpperCase", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.String).build())))
    .method("trim", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.String).build())))
    .method("toBoolean", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.Boolean).build())))
    .method("toInt", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.Int).build())))
    .method("toReal", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.Double).build())))
    .method("toDouble", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.Double).build())))
    .method("toFloat", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.Float).build())))
    .build();
