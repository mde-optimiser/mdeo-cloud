import { createInterface, createType, Optional, Ref, type ASTType, type BaseType } from "@mdeo/language-common";
import { AssociationEnd, Class, Enum, EnumEntry, Property } from "@mdeo/language-metamodel";
import type { AstNode } from "langium";

export const SimpleValue = createInterface("SimpleValue").attrs({
    stringValue: Optional(String),
    numberValue: Optional(Number),
    booleanValue: Optional(Boolean)
});

export type SimpleValueType = ASTType<typeof SimpleValue>;

export const EnumValue = createInterface("EnumValue").attrs({
    enumRef: Ref(() => Enum),
    value: Ref(() => EnumEntry)
});

export type EnumValueType = ASTType<typeof EnumValue>;

export const SingleValue: BaseType<AstNode> = createType("SingleValue").types(SimpleValue, EnumValue);

export type SingleValueType = ASTType<typeof SingleValue>;

export const ListValue = createInterface("ListValue").attrs({
    values: [SingleValue]
});

export type ListValueType = ASTType<typeof ListValue>;

export const LiteralValue: BaseType<AstNode> = createType("LiteralValue").types(SimpleValue, EnumValue, ListValue);

export type LiteralValueType = ASTType<typeof LiteralValue>;

export const PropertyAssignment = createInterface("PropertyAssignment").attrs({
    name: Ref(() => Property),
    value: LiteralValue
});

export type PropertyAssignmentType = ASTType<typeof PropertyAssignment>;

export const ObjectInstance = createInterface("ObjectInstance").attrs({
    name: String,
    class: Ref(() => Class),
    properties: [PropertyAssignment]
});

export type ObjectInstanceType = ASTType<typeof ObjectInstance>;

export const LinkEnd = createInterface("LinkEnd").attrs({
    object: Ref(() => ObjectInstance),
    property: Optional(Ref(() => AssociationEnd))
});

export type LinkEndType = ASTType<typeof LinkEnd>;

export const Link = createInterface("Link").attrs({
    source: LinkEnd,
    target: LinkEnd
});

export type LinkType = ASTType<typeof Link>;

export const MetamodelFileImport = createInterface("MetamodelFileImport").attrs({
    file: String
});

export type MetamodelFileImportType = ASTType<typeof MetamodelFileImport>;

/**
 * A single CSV class import entry.
 * Maps a metamodel class to a CSV file.
 * Format: ClassName from "path/to/file.csv"
 */
export const CsvClassImport = createInterface("CsvClassImport").attrs({
    class: Ref(() => Class),
    file: String
});

export type CsvClassImportType = ASTType<typeof CsvClassImport>;

/**
 * A CSV import block at the top of a model file.
 * When present, forbids hand-authored object instances.
 * Format:
 *   import CSV {
 *     ClassName from "file.csv"
 *   }
 */
export const CsvImportBlock = createInterface("CsvImportBlock").attrs({
    imports: [CsvClassImport]
});

export type CsvImportBlockType = ASTType<typeof CsvImportBlock>;

/**
 * Model root interface.
 * Contains metamodel import, optional CSV import block, optional hand-authored objects and links.
 * Either csvImport or objects/links may be present, but not both.
 */
export const Model = createInterface("Model").attrs({
    import: MetamodelFileImport,
    csvImport: Optional(CsvImportBlock),
    objects: [ObjectInstance],
    links: [Link]
});

export type ModelType = ASTType<typeof Model>;
