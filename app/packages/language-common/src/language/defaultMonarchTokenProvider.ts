import type { languages } from "monaco-editor";

/**
 * Default Monarch token provider
 */
export const defaultMonarchTokenProvider: languages.IMonarchLanguage = {
    defaultToken: "operator",
    includeLF: true,
    start: "expression",
    unicode: true,
    tokenizer: {
        expression: [
            // number
            [/[0-9]+(\.[0-9]+)?([eE][+-]?[0-9]+)?/u, { token: "constant.numerical", switchTo: "expression" }],

            // strings
            [/"/u, { token: "string.quote", bracket: "@open", switchTo: "@string" }],

            // operators with dots
            [/\.(\.)+/u, "operator"],

            // properties
            [/\./u, { token: "delimiter.dot", switchTo: "expression.after.dot" }],

            // functions
            [
                /`[^`\n\r]+`(?=\s*[({[])|[\p{ID_Start}][\p{ID_Continue}]*(?=\s*\()/u,
                {
                    cases: {
                        "@keywords": { token: "keyword", switchTo: "expression" },
                        "@default": { token: "entity.name.function", switchTo: "expression" }
                    }
                }
            ],

            // identifiers/variables
            [
                /`[^`\n\r]+`|[\p{ID_Start}][\p{ID_Continue}]*/u,
                {
                    cases: {
                        "@keywords": { token: "keyword", switchTo: "expression" },
                        "@default": { token: "variable", switchTo: "expression" }
                    }
                }
            ],

            // whitespace
            { include: "@whitespace" },

            //newline
            [/\n/u, "white"]
        ],

        "expression.after.dot": [
            [
                /`[^`\n\r]+`|[\p{ID_Start}][\p{ID_Continue}]*/u,
                {
                    cases: {
                        "@keywords": { token: "keyword", switchTo: "expression" },
                        "@default": { token: "variable.property", switchTo: "expression" }
                    }
                }
            ],
            { include: "@expression" }
        ],

        string: [
            [/[^\\$"\n]+/u, "string"],
            [/\\([\\"nt]|u[0-9a-fA-F]{4})/u, "string.escape"],
            [/\\./u, "string.escape.invalid"],
            [/"/u, { token: "string.quote", bracket: "@close", switchTo: "@expression" }]
        ],

        whitespace: [
            [/[^\S\n]+/u, "white"],
            [/\/\*/u, "comment", "@comment"],
            [/\/\/.*/u, "comment"]
        ],

        comment: [
            [/[^/*]+/u, "comment"],
            [/\*\//u, "comment", "@pop"],
            [/[/*]/u, "comment"]
        ]
    }
};
