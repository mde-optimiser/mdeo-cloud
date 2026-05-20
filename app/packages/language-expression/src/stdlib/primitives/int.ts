import { DefaultTypeNames } from "../../type-system/typeSystemConfig.js";
import { classType, typeRef } from "../../typir-extensions/config/typeBuilder.js";

/**
 * The built-in int type exported as `intType`.
 */
export const intType = classType(DefaultTypeNames.Int)
    .extends(DefaultTypeNames.Any)
    .method("abs", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.Int).build())))
    .method("ceil", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.Int).build())))
    .method("floor", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.Int).build())))
    .method("log", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.Float).build())))
    .method("log10", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.Float).build())))
    .method("max", (m) =>
        m
            .signature(DefaultTypeNames.Int, (s) =>
                s
                    .param("other", typeRef("builtin", DefaultTypeNames.Int).build())
                    .returns(typeRef("builtin", DefaultTypeNames.Int).build())
            )
            .signature(DefaultTypeNames.Long, (s) =>
                s
                    .param("other", typeRef("builtin", DefaultTypeNames.Long).build())
                    .returns(typeRef("builtin", DefaultTypeNames.Long).build())
            )
            .signature(DefaultTypeNames.Float, (s) =>
                s
                    .param("other", typeRef("builtin", DefaultTypeNames.Float).build())
                    .returns(typeRef("builtin", DefaultTypeNames.Float).build())
            )
            .signature(DefaultTypeNames.Double, (s) =>
                s
                    .param("other", typeRef("builtin", DefaultTypeNames.Double).build())
                    .returns(typeRef("builtin", DefaultTypeNames.Double).build())
            )
    )
    .method("min", (m) =>
        m
            .signature(DefaultTypeNames.Int, (s) =>
                s
                    .param("other", typeRef("builtin", DefaultTypeNames.Int).build())
                    .returns(typeRef("builtin", DefaultTypeNames.Int).build())
            )
            .signature(DefaultTypeNames.Long, (s) =>
                s
                    .param("other", typeRef("builtin", DefaultTypeNames.Long).build())
                    .returns(typeRef("builtin", DefaultTypeNames.Long).build())
            )
            .signature(DefaultTypeNames.Float, (s) =>
                s
                    .param("other", typeRef("builtin", DefaultTypeNames.Float).build())
                    .returns(typeRef("builtin", DefaultTypeNames.Float).build())
            )
            .signature(DefaultTypeNames.Double, (s) =>
                s
                    .param("other", typeRef("builtin", DefaultTypeNames.Double).build())
                    .returns(typeRef("builtin", DefaultTypeNames.Double).build())
            )
    )
    .method("pow", (m) =>
        m.signature((s) =>
            s
                .param("exponent", typeRef("builtin", DefaultTypeNames.Double).build())
                .returns(typeRef("builtin", DefaultTypeNames.Double).build())
        )
    )
    .method("round", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.Int).build())))
    .method("iota", (m) =>
        m.signature((s) =>
            s
                .param("end", typeRef("builtin", DefaultTypeNames.Int).build())
                .param("step", typeRef("builtin", DefaultTypeNames.Int).build())
                .returns(
                    typeRef("builtin", "List")
                        .withTypeArgs({ T: typeRef("builtin", DefaultTypeNames.Int).build() })
                        .build()
                )
        )
    )
    .method("mod", (m) =>
        m.signature((s) =>
            s
                .param("divisor", typeRef("builtin", DefaultTypeNames.Int).build())
                .returns(typeRef("builtin", DefaultTypeNames.Int).build())
        )
    )
    .method("to", (m) =>
        m.signature((s) =>
            s.param("other", typeRef("builtin", DefaultTypeNames.Int).build()).returns(
                typeRef("builtin", "List")
                    .withTypeArgs({ T: typeRef("builtin", DefaultTypeNames.Int).build() })
                    .build()
            )
        )
    )
    .method("toBinary", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.String).build())))
    .method("toHex", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.String).build())))
    .build();
