import { createRule, many, STRING, NEWLINE, ID, or, ref } from "@mdeo/language-common";
import { Class } from "@mdeo/language-metamodel";
import { CsvClassImport, CsvImportBlock } from "./csvImportTypes.js";

export const CsvClassImportRule = createRule("CsvClassImportRule")
    .returns(CsvClassImport)
    .as(({ set }) => [
        set("className", ref(Class, ID)),
        "from",
        set("file", STRING)
    ]);

export const CsvImportBlockRule = createRule("CsvImportBlockRule")
    .returns(CsvImportBlock)
    .as(({ add }) => [
        "import",
        "CSV",
        "{",
        many(or(add("imports", CsvClassImportRule), NEWLINE)),
        "}"
    ]);
