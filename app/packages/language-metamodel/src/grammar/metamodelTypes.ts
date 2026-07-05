import { createInterface, createType, Optional, Ref, Union } from "@mdeo/language-common";
import type { ASTType, Interface } from "@mdeo/language-common";
import type { AstNode, Reference } from "langium";

/**
 * Enumeration of primitive types supported in the metamodel.
 */
export enum MetamodelPrimitiveTypes {
    INT = "int",
    STRING = "string",
    BOOLEAN = "boolean",
    LONG = "long",
    DOUBLE = "double",
    FLOAT = "float"
}

/**
 * Enumeration of association operators supported in the metamodel.
 * Supports navigability (arrows) and composition (stars).
 */
export enum MetamodelAssociationOperators {
    /**
     * Navigable from source to target
     */
    NAVIGABLE_TO_TARGET = "-->",
    /**
     * Navigable from target to source
     */
    NAVIGABLE_TO_SOURCE = "<--",
    /**
     * Bidirectional navigability
     */
    BIDIRECTIONAL = "<-->",
    /**
     * Composition at source, navigable to target
     */
    COMPOSITION_SOURCE_NAVIGABLE_TARGET = "*-->",
    /**
     * Composition at source
     */
    COMPOSITION_SOURCE = "*--",
    /**
     * Composition at target, navigable to source
     */
    COMPOSITION_TARGET_NAVIGABLE_SOURCE = "<--*",
    /**
     * Composition at target
     */
    COMPOSITION_TARGET = "--*"
}

/**
 * Primitive type for metamodel properties.
 * Represents basic data types that can be used in metamodel class properties.
 */
export const PrimitiveType = createInterface("PrimitiveType").attrs({
    name: Union(
        MetamodelPrimitiveTypes.INT,
        MetamodelPrimitiveTypes.STRING,
        MetamodelPrimitiveTypes.BOOLEAN,
        MetamodelPrimitiveTypes.LONG,
        MetamodelPrimitiveTypes.DOUBLE,
        MetamodelPrimitiveTypes.FLOAT
    )
});

/**
 * Type representing a PrimitiveType AST node.
 */
export type PrimitiveTypeType = ASTType<typeof PrimitiveType>;

/**
 * Enum entry definition.
 * Represents a single entry (literal) within an enum.
 */
export const EnumEntry = createInterface("EnumEntry").attrs({
    name: String
});

/**
 * Type representing an EnumEntry AST node.
 */
export type EnumEntryType = ASTType<typeof EnumEntry>;

/**
 * Enum definition.
 * Represents an enumeration type with a name and list of entries.
 */
export const Enum = createInterface("Enum").attrs({
    name: String,
    entries: [EnumEntry]
});

/**
 * Type representing an Enum AST node.
 */
export type EnumType = ASTType<typeof Enum>;

/**
 * Single multiplicity specification.
 * Can be a numeric value or one of the special symbols: * (any), + (one or more), ? (optional).
 */
export const SingleMultiplicity = createInterface("SingleMultiplicity").attrs({
    value: Optional(Union("*", "+", "?")),
    numericValue: Optional(Number)
});

/**
 * Type representing a SingleMultiplicity AST node.
 */
export type SingleMultiplicityType = ASTType<typeof SingleMultiplicity>;

/**
 * Range multiplicity specification.
 * Defines a lower bound and upper bound (can be * for unlimited).
 */
export const RangeMultiplicity = createInterface("RangeMultiplicity").attrs({
    lower: Number,
    upper: Optional(Union("*")),
    upperNumeric: Optional(Number)
});

/**
 * Type representing a RangeMultiplicity AST node.
 */
export type RangeMultiplicityType = ASTType<typeof RangeMultiplicity>;

/**
 * Union type for multiplicity specifications.
 * Can be either single or range multiplicity.
 */
export const Multiplicity = createType("Multiplicity").types(SingleMultiplicity, RangeMultiplicity);

/**
 * Type representing a Multiplicity AST node.
 */
export type MultiplicityType = ASTType<typeof Multiplicity>;

/**
 * Enum type reference for property types.
 * Allows referencing an enum type in a property definition.
 */
export const EnumTypeReference = createInterface("EnumTypeReference").attrs({
    enum: Ref(() => Enum)
});

/**
 * Type representing an EnumTypeReference AST node.
 */
export type EnumTypeReferenceType = ASTType<typeof EnumTypeReference>;

/**
 * Union type for property type values.
 * A property type can be either a primitive type or a reference to an enum.
 */
export const PropertyTypeValue = createType("PropertyTypeValue").types(PrimitiveType, EnumTypeReference);

/**
 * Type representing a PropertyTypeValue AST node.
 */
export type PropertyTypeValueType = ASTType<typeof PropertyTypeValue>;

/**
 * Property definition for metamodel classes.
 * Represents an attribute with a name, type (primitive or enum), and optional multiplicity.
 */
export const Property = createInterface("Property").attrs({
    name: String,
    type: PropertyTypeValue,
    multiplicity: Optional(Multiplicity)
});

/**
 * Type representing a Property AST node.
 */
export type PropertyType = ASTType<typeof Property>;

/**
 * Forward declaration for ClassType to break circular reference.
 * This defines the shape of a Class AST node for use in Ref() types.
 */
export interface ClassType extends AstNode {
    name: string;
    isAbstract: boolean;
    extensions: ClassExtensionsType | undefined;
    properties: PropertyType[];
}

/**
 * Forward declaration for ClassExtensionType to break circular reference.
 */
export interface ClassExtensionType extends AstNode {
    class: Reference<ClassType>;
}

/**
 * Forward declaration for ClassExtensionsType to break circular reference.
 */
export interface ClassExtensionsType extends AstNode {
    extensions: ClassExtensionType[];
}

/**
 * Class extension definition.
 * Represents a reference to a parent class in an inheritance relationship.
 */
export const ClassExtension: Interface<ClassExtensionType> = createInterface("ClassExtension").attrs({
    class: Ref(() => Class)
});

/**
 * ClassExtensions definition.
 * Wraps the extends keyword and list of class extensions.
 */
export const ClassExtensions: Interface<ClassExtensionsType> = createInterface("ClassExtensions").attrs({
    extensions: [ClassExtension]
});

/**
 * Class definition.
 * Represents a class in the metamodel with properties and inheritance relationships.
 */
export const Class: Interface<ClassType> = createInterface("Class").attrs({
    name: String,
    isAbstract: Boolean,
    extensions: Optional(ClassExtensions),
    properties: [Property]
});

/**
 * Union type for Class or Enum.
 */
export const ClassOrEnum = createType("ClassOrEnum").types(Class, Enum);

/**
 * Type representing ClassOrEnum AST node.
 */
export type ClassOrEnumType = ASTType<typeof ClassOrEnum>;

/**
 * Simplified file import definition.
 * Represents an import statement that imports an entire file.
 */
export const FileImport = createInterface("FileImport").attrs({
    file: String
});

/**
 * Type representing FileImport AST node.
 */
export type FileImportType = ASTType<typeof FileImport>;

/**
 * Association end definition.
 * Represents one end of an association or composition relationship.
 */
export const AssociationEnd = createInterface("AssociationEnd").attrs({
    class: Ref(() => Class),
    name: Optional(String),
    multiplicity: Optional(Multiplicity)
});

/**
 * Type representing an AssociationEnd AST node.
 */
export type AssociationEndType = ASTType<typeof AssociationEnd>;

/**
 * Association definition.
 * Represents a relationship between two classes.
 * Supports 7 operators: -->, <--, <-->, *-->, *--, <--*, --*
 * Star (*) indicates composition, arrow (>) indicates navigability.
 */
export const Association = createInterface("Association").attrs({
    source: AssociationEnd,
    operator: Union(
        MetamodelAssociationOperators.NAVIGABLE_TO_TARGET,
        MetamodelAssociationOperators.NAVIGABLE_TO_SOURCE,
        MetamodelAssociationOperators.BIDIRECTIONAL,
        MetamodelAssociationOperators.COMPOSITION_SOURCE_NAVIGABLE_TARGET,
        MetamodelAssociationOperators.COMPOSITION_SOURCE,
        MetamodelAssociationOperators.COMPOSITION_TARGET_NAVIGABLE_SOURCE,
        MetamodelAssociationOperators.COMPOSITION_TARGET
    ),
    target: AssociationEnd
});

/**
 * Type representing an Association AST node.
 */
export type AssociationType = ASTType<typeof Association>;

/**
 * Union type for Class, Enum, or Association.
 * Used to represent any top-level element in the metamodel body.
 */
export const ClassEnumOrAssociation = createType("ClassEnumOrAssociation").types(Class, Enum, Association);

/**
 * Type representing ClassEnumOrAssociation AST node.
 */
export type ClassEnumOrAssociationType = ASTType<typeof ClassEnumOrAssociation>;

/**
 * The root MetaModel type.
 * Contains imports, class definitions, enums, and associations.
 */
export const MetaModel = createInterface("MetaModel").attrs({
    imports: [FileImport],
    elements: [ClassEnumOrAssociation]
});

/**
 * Type representing the MetaModel AST node.
 */
export type MetaModelType = ASTType<typeof MetaModel>;
