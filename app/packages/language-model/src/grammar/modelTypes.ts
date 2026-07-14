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
 * Base interface for data imports contributed by plugins (e.g. CSV, and future formats).
 * A contribution plugin wraps its own import block in an interface extending this one,
 * the same way config sections extend `BaseConfigSection`.
 */
export const BaseModelImport = createInterface("BaseModelImport").attrs({});

export type BaseModelImportType = ASTType<typeof BaseModelImport>;

/**
 * Model root interface.
 * Contains metamodel import, any number of plugin-contributed data imports (e.g. CSV),
 * and hand-authored objects and links.
 */
export const Model = createInterface("Model").attrs({
    import: MetamodelFileImport,
    imports: [BaseModelImport],
    objects: [ObjectInstance],
    links: [Link]
});

export type ModelType = ASTType<typeof Model>;
