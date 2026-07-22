import { createRule, many, or, ref, createExternalTerminalRule } from "@mdeo/language-common";
import { CsvClassImport, CsvImportBlock, ExternalClass } from "./csvImportTypes.js";

/**
 * Stand-ins for the base language's common terminals, so this grammar's
 * serialized form marks them as external rather than inlining duplicate
 * definitions. The real terminals are supplied via the deserialization
 * context wherever this grammar is merged in.
 */
const ID = createExternalTerminalRule<string>("ID");
const STRING = createExternalTerminalRule<string>("STRING");
const NEWLINE = createExternalTerminalRule<string>("NEWLINE");

export const CsvClassImportRule = createRule("CsvClassImportRule")
    .returns(CsvClassImport)
    .as(({ set }) => [set("class", ref(ExternalClass, ID)), "from", set("file", STRING)]);

/**
 * The content of a CSV import block (everything between the braces).
 * The `import CSV` keywords themselves are added by the wrapper rule the
 * Model language builds around this contribution, not by this rule.
 */
export const CsvImportContentRule = createRule("CsvImportContentRule")
    .returns(CsvImportBlock)
    .as(({ add }) => ["{", many(or(add("imports", CsvClassImportRule), NEWLINE)), "}"]);
