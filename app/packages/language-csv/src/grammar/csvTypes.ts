import { createInterface, type ASTType } from "@mdeo/language-common";

export const CsvFile = createInterface("CsvFile").attrs({
    content: String
});

export type CsvFileType = ASTType<typeof CsvFile>;
