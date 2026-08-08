import { createRule, many, optional, or, ref, group, createExternalTerminalRule } from "@mdeo/language-common";
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
 * A class import, with an optional explicit column mapping block.
 *
 * The mapping block uses plain nested braces, like the nested blocks in the
 * other DSLs. Nothing needs to separate it from `file`: the block can only
 * start with "{", while the enclosing import list continues with an ID or ends
 * with "}", so the parser can always tell which one it is from a single token.
 */
export const CsvClassImportRule = createRule("CsvClassImportRule")
    .returns(CsvClassImport)
    .as(({ set, add }) => [
        set("class", ref(ExternalClass, ID)),
        "from",
        set("file", STRING),
        optional(
            group("{", many(or(add("mappings", CsvColumnMappingRule), NEWLINE)), "}")
        )
    ]);

/**
 * The content of a CSV import block (everything between the braces).
 * The `import CSV` keywords themselves are added by the wrapper rule the
 * Model language builds around this contribution, not by this rule.
 */
export const CsvImportContentRule = createRule("CsvImportContentRule")
    .returns(CsvImportBlock)
    .as(({ add }) => ["{", many(or(add("imports", CsvClassImportRule), NEWLINE)), "}"]);
