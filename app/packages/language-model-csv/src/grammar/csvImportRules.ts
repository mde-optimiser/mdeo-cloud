import { createRule, many, optional, or, ref, createExternalTerminalRule } from "@mdeo/language-common";
import { CsvClassImport, CsvColumnMapping, CsvImportBlock, ExternalClass } from "./csvImportTypes.js";

/**
 * Stand-ins for the base language's common terminals, so this grammar's
 * serialized form marks them as external rather than inlining duplicate
 * definitions. The real terminals are supplied via the deserialization
 * context wherever this grammar is merged in.
 */
const ID = createExternalTerminalRule<string>("ID");
const STRING = createExternalTerminalRule<string>("STRING");
const NEWLINE = createExternalTerminalRule<string>("NEWLINE");

export const CsvColumnMappingRule = createRule("CsvColumnMappingRule")
    .returns(CsvColumnMapping)
    .as(({ set }) => [set("csvColumn", STRING), "=", set("property", ID)]);

/**
 * The optional explicit mapping list uses square brackets rather than curly
 * braces, since curly braces already close the enclosing `import CSV { }`
 * block and this grammar's newline-aware brace handling only special-cases
 * "{"/"}"/"("/")" - reusing braces here caused the parser to misread the
 * mapping list's own opening brace as the start of a new class import.
 */
export const CsvClassImportRule = createRule("CsvClassImportRule")
    .returns(CsvClassImport)
    .as(({ set, add }) => [
        set("class", ref(ExternalClass, ID)),
        "from",
        set("file", STRING),
        optional("[", many(or(add("mappings", CsvColumnMappingRule), NEWLINE)), "]")
    ]);

/**
 * The content of a CSV import block (everything between the braces).
 * The `import CSV` keywords themselves are added by the wrapper rule the
 * Model language builds around this contribution, not by this rule.
 */
export const CsvImportContentRule = createRule("CsvImportContentRule")
    .returns(CsvImportBlock)
    .as(({ add }) => ["{", many(or(add("imports", CsvClassImportRule), NEWLINE)), "}"]);
