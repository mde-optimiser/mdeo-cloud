import { createInterface, Optional, Ref, type ASTType } from "@mdeo/language-common";
import { Class } from "@mdeo/language-metamodel";

export const CsvClassImport = createInterface("CsvClassImport").attrs({
    className: Ref(() => Class),
    file: String
});

export type CsvClassImportType = ASTType<typeof CsvClassImport>;

export const CsvImportBlock = createInterface("CsvImportBlock").attrs({
    imports: [CsvClassImport]
});

export type CsvImportBlockType = ASTType<typeof CsvImportBlock>;
