import type { LanguageRegistration } from "shiki";

/**
 * TextMate grammars for the five textual MDEO languages.
 *
 * The token classes mirror what the Monarch tokenizers of the language plugins produce in
 * the workbench (see the `textualEditorPlugin` of every service package), so a snippet on
 * the website is highlighted the same way it is in the editor.
 */

/**
 * Description of one MDEO language, from which the TextMate grammar is generated.
 */
interface LanguageDefinition {
    /**
     * Grammar name, also the primary language id in fenced code blocks.
     */
    name: string;
    /**
     * Additional ids that can be used in fenced code blocks and are matched against file
     * extensions of imported snippets.
     */
    aliases: string[];
    /**
     * Control keywords of the language.
     */
    keywords: string[];
    /**
     * Built-in type names, highlighted as types rather than as keywords.
     */
    types?: string[];
    /**
     * Literal-like names such as enum values of configuration options.
     */
    constants?: string[];
    /**
     * Multi-character operators that deserve their own token.
     */
    operators?: string[];
    /**
     * When true, dotted keywords such as `problem.optimization` are highlighted as one token.
     */
    qualifiedKeywords?: boolean;
}

/**
 * Escapes a string so it can be embedded into a regular expression.
 *
 * @param value The raw string
 * @returns The escaped string
 */
function escapeRegex(value: string): string {
    return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/**
 * Builds an alternation of the given literals, longest first so that `<-->` wins over `<--`.
 *
 * @param values The literals to combine
 * @returns The alternation source
 */
function alternation(values: string[]): string {
    return [...values]
        .sort((a, b) => b.length - a.length)
        .map(escapeRegex)
        .join("|");
}

/**
 * Builds a word-boundary alternation for identifier-like literals.
 *
 * @param values The literals to combine
 * @returns The alternation source, anchored on word boundaries
 */
function wordAlternation(values: string[]): string {
    return `\\b(?:${[...values].sort((a, b) => b.length - a.length).join("|")})\\b`;
}

/**
 * Generates the TextMate grammar for one MDEO language.
 *
 * @param definition The language description
 * @returns A Shiki language registration
 */
function createGrammar(definition: LanguageDefinition): LanguageRegistration {
    const scopeName = `source.${definition.name}`;
    const patterns: unknown[] = [{ include: "#comment" }, { include: "#string" }, { include: "#number" }];

    if (definition.qualifiedKeywords) {
        patterns.push({
            match: `${wordAlternation(definition.keywords)}(\\.)([A-Za-z_][A-Za-z0-9_]*)`,
            captures: {
                0: { name: `keyword.control.${definition.name}` },
                1: { name: `punctuation.accessor.${definition.name}` },
                2: { name: `entity.name.namespace.${definition.name}` }
            }
        });
    }

    patterns.push({ match: wordAlternation(definition.keywords), name: `keyword.control.${definition.name}` });

    if (definition.constants?.length) {
        patterns.push({
            match: wordAlternation(definition.constants),
            name: `constant.language.${definition.name}`
        });
    }
    if (definition.types?.length) {
        patterns.push({
            match: wordAlternation(definition.types),
            name: `support.type.primitive.${definition.name}`
        });
    }

    patterns.push(
        // A capitalised identifier is a class, enum or other declared type everywhere in
        // the MDEO languages.
        { match: "\\b[A-Z][A-Za-z0-9_]*\\b", name: `entity.name.type.${definition.name}` },
        // Identifiers directly followed by an opening parenthesis are calls.
        { match: "\\b[a-z_][A-Za-z0-9_]*(?=\\s*\\()", name: `entity.name.function.${definition.name}` },
        // Backtick quoted identifiers escape names that clash with keywords.
        { match: "`[^`\\n\\r]+`", name: `variable.other.${definition.name}` },
        {
            match: "(\\.)\\s*([a-z_][A-Za-z0-9_]*)",
            captures: {
                1: { name: `punctuation.accessor.${definition.name}` },
                2: { name: `variable.other.property.${definition.name}` }
            }
        }
    );

    if (definition.operators?.length) {
        patterns.push({ match: alternation(definition.operators), name: `keyword.operator.${definition.name}` });
    }
    patterns.push({ match: "[=+\\-*/%<>!&|?:^~]+", name: `keyword.operator.${definition.name}` });

    return {
        name: definition.name,
        scopeName,
        aliases: definition.aliases,
        patterns,
        repository: {
            comment: {
                patterns: [
                    { match: "//.*$", name: `comment.line.double-slash.${definition.name}` },
                    { begin: "/\\*", end: "\\*/", name: `comment.block.${definition.name}` }
                ]
            },
            string: {
                name: `string.quoted.double.${definition.name}`,
                begin: '"',
                end: '"',
                patterns: [{ match: '\\\\(?:[\\\\"nt]|u[0-9a-fA-F]{4})', name: `constant.character.escape.${definition.name}` }]
            },
            number: {
                patterns: [
                    { match: "\\b[0-9]+\\.[0-9]+[FfDd]?\\b", name: `constant.numeric.float.${definition.name}` },
                    { match: "\\b[0-9]+[Ll]?\\b", name: `constant.numeric.integer.${definition.name}` }
                ]
            }
        }
    } as unknown as LanguageRegistration;
}

/**
 * The metamodel language (`.mm`).
 */
const metamodel = createGrammar({
    name: "mdeo-metamodel",
    aliases: ["mm"],
    keywords: ["class", "extends", "abstract", "import", "from", "as", "enum"],
    types: ["int", "string", "boolean", "long", "double", "float"],
    operators: ["<-->", "*-->", "<--*", "-->", "<--", "*--", "--*", ".."]
});

/**
 * The model language (`.m` and `.m_gen`).
 */
const model = createGrammar({
    name: "mdeo-model",
    aliases: ["m", "m_gen"],
    keywords: ["using"],
    constants: ["true", "false"],
    operators: ["--"]
});

/**
 * The model transformation language (`.mt` and `.mt_gen`).
 */
const modelTransformation = createGrammar({
    name: "mdeo-model-transformation",
    aliases: ["mt", "mt_gen"],
    keywords: [
        "using",
        "match",
        "if",
        "then",
        "else",
        "while",
        "until",
        "for",
        "do",
        "var",
        "create",
        "delete",
        "forbid",
        "require",
        "where",
        "kill",
        "stop"
    ],
    constants: ["true", "false", "null"],
    operators: ["===", "!==", "==", "!=", "<=", ">=", "&&", "||", "??", "?.", "!!", "=>", "--"]
});

/**
 * The script language (`.fn`).
 */
const script = createGrammar({
    name: "mdeo-script",
    aliases: ["fn"],
    keywords: [
        "import",
        "using",
        "from",
        "as",
        "fun",
        "record",
        "return",
        "if",
        "else",
        "while",
        "for",
        "break",
        "continue",
        "var",
        "val",
        "in",
        "is"
    ],
    types: [
        "int",
        "long",
        "float",
        "double",
        "string",
        "boolean",
        "void",
        "Any",
        "Collection",
        "Iterable",
        "List",
        "Set",
        "Bag",
        "OrderedSet"
    ],
    constants: ["true", "false", "null"],
    operators: ["===", "!==", "as?", "!is", "==", "!=", "<=", ">=", "&&", "||", "??", "?.", "!!", "=>"]
});

/**
 * The config language (`.config`), including the sections contributed by the Config
 * Optimization and Config MDEO plugins.
 */
const config = createGrammar({
    name: "mdeo-config",
    aliases: ["config"],
    qualifiedKeywords: true,
    keywords: [
        // Section keywords contributed by plugins.
        "problem",
        "goal",
        "search",
        "solver",
        "runtime",
        // Keys and operators inside those sections.
        "metamodel",
        "model",
        "import",
        "from",
        "as",
        "constraint",
        "maximize",
        "minimize",
        "refine",
        "mutations",
        "using",
        "create",
        "delete",
        "mutate",
        "add",
        "remove",
        "algorithm",
        "parameters",
        "population",
        "variation",
        "mutation",
        "step",
        "strategy",
        "selection",
        "application",
        "credit",
        "repair",
        "archive",
        "size",
        "bisections",
        "termination",
        "evolutions",
        "time",
        "delta",
        "iterations",
        "batches",
        "timeout",
        "script",
        "transformation",
        "backend",
        "resources",
        "threads",
        "nodes",
        "threadsPerNode",
        "provider"
    ],
    constants: [
        "NSGAII",
        "IBEA",
        "SPEA2",
        "SMSMOEA",
        "VEGA",
        "PESA2",
        "PAES",
        "MDEO",
        "Tinker",
        "random",
        "repetitive",
        "genetic",
        "probabilistic",
        "default",
        "fixed",
        "interval"
    ],
    operators: [".."]
});

/**
 * All MDEO language grammars, ready to be handed to VitePress' markdown options.
 */
export const mdeoLanguages: LanguageRegistration[] = [metamodel, model, modelTransformation, script, config];
