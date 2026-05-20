import { DefaultTypeNames } from "../../type-system/typeSystemConfig.js";
import { classType, typeRef } from "../../typir-extensions/config/typeBuilder.js";

/**
 * The built-in double type exported as `doubleType`.
 */
export const doubleType = classType(DefaultTypeNames.Double)
    .extends(DefaultTypeNames.Any)
    .method("abs", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.Double).build())))
    .method("ceil", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.Long).build())))
    .method("floor", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.Long).build())))
    .method("log", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.Double).build())))
    .method("log10", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.Double).build())))
    .method("max", (m) =>
        m
            .signature(DefaultTypeNames.Int, (s) =>
                s
                    .param("other", typeRef("builtin", DefaultTypeNames.Int).build())
                    .returns(typeRef("builtin", DefaultTypeNames.Double).build())
            )
            .signature(DefaultTypeNames.Long, (s) =>
                s
                    .param("other", typeRef("builtin", DefaultTypeNames.Long).build())
                    .returns(typeRef("builtin", DefaultTypeNames.Double).build())
            )
            .signature(DefaultTypeNames.Float, (s) =>
                s
                    .param("other", typeRef("builtin", DefaultTypeNames.Float).build())
                    .returns(typeRef("builtin", DefaultTypeNames.Double).build())
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
                    .returns(typeRef("builtin", DefaultTypeNames.Double).build())
            )
            .signature(DefaultTypeNames.Long, (s) =>
                s
                    .param("other", typeRef("builtin", DefaultTypeNames.Long).build())
                    .returns(typeRef("builtin", DefaultTypeNames.Double).build())
            )
            .signature(DefaultTypeNames.Float, (s) =>
                s
                    .param("other", typeRef("builtin", DefaultTypeNames.Float).build())
                    .returns(typeRef("builtin", DefaultTypeNames.Double).build())
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
    .method("round", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.Long).build())))
    .method("sqrt", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.Double).build())))
    .method("sin", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.Double).build())))
    .method("cos", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.Double).build())))
    .method("tan", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.Double).build())))
    .method("asin", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.Double).build())))
    .method("acos", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.Double).build())))
    .method("atan", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.Double).build())))
    .method("sinh", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.Double).build())))
    .method("cosh", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.Double).build())))
    .method("tanh", (m) => m.signature((s) => s.returns(typeRef("builtin", DefaultTypeNames.Double).build())))
    .build();
