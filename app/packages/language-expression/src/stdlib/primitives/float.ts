import { DefaultTypeNames } from "../../type-system/typeSystemConfig.js";
import { classType, typeRef } from "../../typir-extensions/config/typeBuilder.js";

/**
 * The built-in float type exported as `floatType`.
 */
export const floatType = classType(DefaultTypeNames.Float)
    .extends(DefaultTypeNames.Any)
    .method("abs", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.Float).build())))
    .method("ceil", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.Int).build())))
    .method("floor", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.Int).build())))
    .method("log", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.Float).build())))
    .method("log10", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.Float).build())))
    .method("max", (m) =>
        m
            .signature(DefaultTypeNames.Int, (s) =>
                s
                    .param("other", typeRef("builtin", DefaultTypeNames.Int).build())
                    .returns(typeRef("builtin", DefaultTypeNames.Float).build())
            )
            .signature(DefaultTypeNames.Long, (s) =>
                s
                    .param("other", typeRef("builtin", DefaultTypeNames.Long).build())
                    .returns(typeRef("builtin", DefaultTypeNames.Float).build())
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
                    .returns(typeRef("builtin", DefaultTypeNames.Float).build())
            )
            .signature(DefaultTypeNames.Long, (s) =>
                s
                    .param("other", typeRef("builtin", DefaultTypeNames.Long).build())
                    .returns(typeRef("builtin", DefaultTypeNames.Float).build())
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
    .method("sqrt", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.Float).build())))
    .method("sin", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.Float).build())))
    .method("cos", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.Float).build())))
    .method("tan", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.Float).build())))
    .method("asin", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.Float).build())))
    .method("acos", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.Float).build())))
    .method("atan", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.Float).build())))
    .method("sinh", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.Float).build())))
    .method("cosh", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.Float).build())))
    .method("tanh", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.Float).build())))
    .build();
