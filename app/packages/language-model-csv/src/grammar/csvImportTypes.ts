import { createInterface, createExternalInterface, Ref, type ASTType } from "@mdeo/language-common";
import type { ClassType } from "@mdeo/language-metamodel";

/**
 * Stand-in for the metamodel's Class interface, used only so this grammar's
 * serialized form marks cross-references to it as external rather than
 * inlining a duplicate "Class" interface definition. The real Class type is
 * supplied via the deserialization context wherever this grammar is merged in.
 */
export const ExternalClass = createExternalInterface<ClassType>("Class");

/**
 * A single CSV class import entry.
 * Maps a metamodel class to a CSV file.
 * Format: ClassName from "path/to/file.csv"
 */
export const CsvClassImport = createInterface("CsvClassImport").attrs({
    class: Ref(() => ExternalClass),
    file: String
});

export type CsvClassImportType = ASTType<typeof CsvClassImport>;

/**
 * The content of a CSV import block (everything between the braces).
 * Format:
 *   {
 *     ClassName from "file.csv"
 *   }
 */
export const CsvImportBlock = createInterface("CsvImportBlock").attrs({
    imports: [CsvClassImport]
});

export type CsvImportBlockType = ASTType<typeof CsvImportBlock>;
