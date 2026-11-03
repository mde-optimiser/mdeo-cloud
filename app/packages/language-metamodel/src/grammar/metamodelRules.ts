import { createRule, or, optional, many, ref, ID, INT, STRING, NEWLINE } from "@mdeo/language-common";
import {
    PrimitiveType,
    SingleMultiplicity,
    RangeMultiplicity,
    Multiplicity,
    Property,
    ClassExtension,
    ClassExtensions,
    Class,
    AssociationEnd,
    Association,
    MetaModel,
    MetamodelPrimitiveTypes,
    MetamodelAssociationOperators,
    Enum,
    EnumEntry,
    EnumTypeReference,
    PropertyTypeValue,
    FileImport,
    ClassOrEnum,
    View,
    ViewEntry
} from "./metamodelTypes.js";

/**
 * Primitive type rule.
 * Matches one of the supported primitive types: int, string, boolean, long, double, float.
 */
export const PrimitiveTypeRule = createRule("PrimitiveTypeRule")
    .returns(PrimitiveType)
    .as(({ set }) => [
        set(
            "name",
            MetamodelPrimitiveTypes.INT,
            MetamodelPrimitiveTypes.STRING,
            MetamodelPrimitiveTypes.BOOLEAN,
            MetamodelPrimitiveTypes.LONG,
            MetamodelPrimitiveTypes.DOUBLE,
            MetamodelPrimitiveTypes.FLOAT
        )
    ]);

/**
 * Single multiplicity rule.
 * Matches either a numeric value or one of the special multiplicity symbols: *, +, ?
 */
export const SingleMultiplicityRule = createRule("SingleMultiplicityRule")
    .returns(SingleMultiplicity)
    .as(({ set }) => [or(set("numericValue", INT), set("value", "*", "+", "?"))]);

/**
 * Range multiplicity rule.
 * Matches a range specification like "1..*" or "0..5".
 */
export const RangeMultiplicityRule = createRule("RangeMultiplicityRule")
    .returns(RangeMultiplicity)
    .as(({ set }) => [set("lower", INT), "..", or(set("upper", "*"), set("upperNumeric", INT))]);

/**
 * Multiplicity rule.
 * Matches multiplicity specifications in brackets, either single or range.
 */
export const MultiplicityRule = createRule("MultiplicityRule")
    .returns(Multiplicity)
    .as(() => ["[", or(RangeMultiplicityRule, SingleMultiplicityRule), "]"]);

/**
 * Enum type reference rule.
 * Matches a reference to an enum type for use in property definitions.
 */
export const EnumTypeReferenceRule = createRule("EnumTypeReferenceRule")
    .returns(EnumTypeReference)
    .as(({ set }) => [set("enum", ref(Enum, ID))]);

/**
 * Property type value rule.
 * Matches either a primitive type or an enum type reference.
 */
export const PropertyTypeValueRule = createRule("PropertyTypeValueRule")
    .returns(PropertyTypeValue)
    .as(() => [or(PrimitiveTypeRule, EnumTypeReferenceRule)]);

/**
 * Property rule.
 * Matches a property definition with name, type (primitive or enum), and optional multiplicity.
 */
export const PropertyRule = createRule("PropertyRule")
    .returns(Property)
    .as(({ set }) => [
        set("name", ID),
        ":",
        set("type", PropertyTypeValueRule),
        optional(set("multiplicity", MultiplicityRule))
    ]);

/**
 * Class extension rule.
 * Matches a reference to a parent class in an extends clause.
 */
export const ClassExtensionRule = createRule("ClassExtensionRule")
    .returns(ClassExtension)
    .as(({ set }) => [set("class", ref(Class, ID))]);

/**
 * Class extensions rule.
 * Matches the extends keyword and list of class extensions.
 */
export const ClassExtensionsRule = createRule("ClassExtensionsRule")
    .returns(ClassExtensions)
    .as(({ add }) => [
        "extends",
        add("extensions", ClassExtensionRule),
        many(",", add("extensions", ClassExtensionRule))
    ]);

/**
 * Class rule.
 * Matches a class definition with optional abstract modifier, name, inheritance, and properties.
 */
export const ClassRule = createRule("ClassRule")
    .returns(Class)
    .as(({ set, add, flag }) => [
        optional(flag("isAbstract", "abstract")),
        "class",
        set("name", ID),
        optional(set("extensions", ClassExtensionsRule)),
        "{",
        many(or(add("properties", PropertyRule), NEWLINE)),
        "}"
    ]);

/**
 * Enum entry rule.
 * Matches a single entry (literal) within an enum definition.
 */
export const EnumEntryRule = createRule("EnumEntryRule")
    .returns(EnumEntry)
    .as(({ set }) => [set("name", ID)]);

/**
 * Enum rule.
 * Matches an enum definition with a name and list of entries.
 */
export const EnumRule = createRule("EnumRule")
    .returns(Enum)
    .as(({ set, add }) => ["enum", set("name", ID), "{", many(or(add("entries", EnumEntryRule), NEWLINE)), "}"]);

/**
 * Association end rule with property.
 * Matches an association end that specifies a property name (e.g., "Class.property[*]").
 */
export const AssociationEndWithPropertyRule = createRule("AssociationEndWithPropertyRule")
    .returns(AssociationEnd)
    .as(({ set }) => [
        set("class", ref(Class, ID)),
        ".",
        set("name", ID),
        optional(set("multiplicity", MultiplicityRule))
    ]);

/**
 * Association end rule without property.
 * Matches an association end that only specifies a class (e.g., "Class").
 * Multiplicity is not allowed without a property name.
 */
export const AssociationEndWithoutPropertyRule = createRule("AssociationEndWithoutPropertyRule")
    .returns(AssociationEnd)
    .as(({ set }) => [set("class", ref(Class, ID))]);

/**
 * Association end rule.
 * Matches an association end, either with or without a property specification.
 */
export const AssociationEndRule = createRule("AssociationEndRule")
    .returns(AssociationEnd)
    .as(() => [or(AssociationEndWithPropertyRule, AssociationEndWithoutPropertyRule)]);

/**
 * Association rule.
 * Matches any association with source end, operator, and target end.
 * Supports 7 operators: -->, <--, <-->, *-->, *--, <--*, --*
 */
export const AssociationRule = createRule("AssociationRule")
    .returns(Association)
    .as(({ set }) => [
        set("source", AssociationEndRule),
        set(
            "operator",
            MetamodelAssociationOperators.NAVIGABLE_TO_TARGET,
            MetamodelAssociationOperators.NAVIGABLE_TO_SOURCE,
            MetamodelAssociationOperators.BIDIRECTIONAL,
            MetamodelAssociationOperators.COMPOSITION_SOURCE_NAVIGABLE_TARGET,
            MetamodelAssociationOperators.COMPOSITION_SOURCE,
            MetamodelAssociationOperators.COMPOSITION_TARGET_NAVIGABLE_SOURCE,
            MetamodelAssociationOperators.COMPOSITION_TARGET
        ),
        set("target", AssociationEndRule)
    ]);

/**
 * File import rule.
 * Matches a simple import statement: import "./relativePath"
 */
export const FileImportRule = createRule("FileImportRule")
    .returns(FileImport)
    .as(({ set }) => ["import", set("file", STRING)]);

/**
 * View entry rule.
 * Matches a reference to a class or enum shown in a view.
 */
export const ViewEntryRule = createRule("ViewEntryRule")
    .returns(ViewEntry)
    .as(({ set }) => [set("element", ref(ClassOrEnum, ID))]);

/**
 * View rule.
 * Matches a view definition with a name and the classes and enums it shows, one per line.
 */
export const ViewRule = createRule("ViewRule")
    .returns(View)
    .as(({ set, add }) => ["view", set("name", ID), "{", many(or(add("entries", ViewEntryRule), NEWLINE)), "}"]);

/**
 * The MetaModel entry rule.
 * Defines the structure: imports, classes, enums, associations, and views.
 */
export const MetaModelRule = createRule("MetaModelRule")
    .returns(MetaModel)
    .as(({ add }) => [
        many(
            or(
                add("imports", FileImportRule),
                add("elements", ClassRule),
                add("elements", EnumRule),
                add("elements", AssociationRule),
                add("views", ViewRule),
                NEWLINE
            )
        )
    ]);
