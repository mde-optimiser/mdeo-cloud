import type { PartialAstNode } from "@mdeo/language-common";
import type {
    MetaModelType,
    ClassType,
    ClassExtensionType,
    ClassExtensionsType,
    PropertyType,
    AssociationType,
    AssociationEndType,
    MultiplicityType,
    SingleMultiplicityType,
    RangeMultiplicityType,
    EnumType,
    EnumEntryType,
    EnumTypeReferenceType,
    PropertyTypeValueType,
    PrimitiveTypeType,
    ClassEnumOrAssociationType,
    FileImportType,
    ViewType,
    ViewEntryType
} from "./metamodelTypes.js";

/**
 * Partial metamodel type with optional domain properties.
 */
export type PartialMetaModel = PartialAstNode<MetaModelType>;

/**
 * Partial class type with optional domain properties.
 */
export type PartialClass = PartialAstNode<ClassType>;

/**
 * Partial class extension type with optional domain properties.
 */
export type PartialClassExtension = PartialAstNode<ClassExtensionType>;

/**
 * Partial class extensions type with optional domain properties.
 */
export type PartialClassExtensions = PartialAstNode<ClassExtensionsType>;

/**
 * Partial property type with optional domain properties.
 */
export type PartialProperty = PartialAstNode<PropertyType>;

/**
 * Partial association type with optional domain properties.
 */
export type PartialAssociation = PartialAstNode<AssociationType>;

/**
 * Partial association end type with optional domain properties.
 */
export type PartialAssociationEnd = PartialAstNode<AssociationEndType>;

/**
 * Partial multiplicity type with optional domain properties.
 */
export type PartialMultiplicity = PartialAstNode<MultiplicityType>;

/**
 * Partial single multiplicity type with optional domain properties.
 */
export type PartialSingleMultiplicity = PartialAstNode<SingleMultiplicityType>;

/**
 * Partial range multiplicity type with optional domain properties.
 */
export type PartialRangeMultiplicity = PartialAstNode<RangeMultiplicityType>;

/**
 * Partial enum type with optional domain properties.
 */
export type PartialEnum = PartialAstNode<EnumType>;

/**
 * Partial enum entry type with optional domain properties.
 */
export type PartialEnumEntry = PartialAstNode<EnumEntryType>;

/**
 * Partial enum type reference type with optional domain properties.
 */
export type PartialEnumTypeReference = PartialAstNode<EnumTypeReferenceType>;

/**
 * Partial property type value type with optional domain properties.
 */
export type PartialPropertyTypeValue = PartialAstNode<PropertyTypeValueType>;

/**
 * Partial primitive type type with optional domain properties.
 */
export type PartialPrimitiveType = PartialAstNode<PrimitiveTypeType>;

/**
 * Partial file import type with optional domain properties.
 */
export type PartialFileImport = PartialAstNode<FileImportType>;

/**
 * Partial class, enum, or association type with optional domain properties.
 */
export type PartialClassEnumOrAssociation = PartialAstNode<ClassEnumOrAssociationType>;

/**
 * Partial view type with optional domain properties.
 */
export type PartialView = PartialAstNode<ViewType>;

/**
 * Partial view entry type with optional domain properties.
 */
export type PartialViewEntry = PartialAstNode<ViewEntryType>;
