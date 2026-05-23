import type { AstNode } from "langium";
import type { ValueType } from "../../typir-extensions/config/type.js";

/**
 * Represents property information extracted from a metamodel class.
 * This is an intermediate representation used before generating TypedProperty or ClassType members.
 */
export interface MetamodelPropertyInfo {
    /**
     * The name of the property.
     */
    name: string;

    /**
     * The value type of the property (for Typir type system).
     */
    valueType: ValueType;

    /**
     * Optional reference to the source metamodel AST node that defines this property.
     * Used for creating reference descriptions for LSP rename and find-references.
     */
    languageNode?: AstNode;
}

/**
 * Represents relation (association) information extracted from a metamodel.
 * This is an intermediate representation used before generating TypedRelation or ClassType members.
 */
export interface MetamodelRelationInfo {
    /**
     * The property name that defines this relation on the class.
     */
    property: string;

    /**
     * The property name at the opposite end of the relation, or undefined if unnamed.
     */
    oppositeProperty?: string;

    /**
     * The fully qualified name of the class at the opposite end of the relation.
     */
    oppositeClassName: string;

    /**
     * True if this is an outgoing relation, false if incoming.
     */
    isOutgoing: boolean;

    /**
     * The value type of the relation (for Typir type system).
     */
    valueType: ValueType;

    /**
     * Optional reference to the source metamodel AST node (association end) that defines this relation.
     * Used for creating reference descriptions for LSP rename and find-references.
     */
    languageNode?: AstNode;
}

/**
 * A reference to another metamodel class, split into its package and simple name.
 * Using a structured reference avoids later string-splitting of fully qualified names.
 */
export interface MetamodelClassRef {
    /**
     * The package where the referenced class is defined (e.g. "class/path/to/file.mm").
     */
    package: string;

    /**
     * The simple (unqualified) name of the referenced class (e.g. "Person").
     */
    name: string;
}

/**
 * Represents class information extracted from a metamodel.
 * This is an intermediate representation that can be converted to:
 * - ClassType for the Typir type system
 */
export interface MetamodelClassInfo {
    /**
     * The  name of the class
     */
    name: string;

    /**
     * The package name where the class is defined.
     */
    package: string;

    /**
     * The container package for this class (e.g. `"class-container/path/to/file.mm"`).
     * Derived directly from {@link package} by replacing the `class/` prefix with `class-container`.
     */
    containerPackage: string;

    /**
     * Direct superclasses, each represented as a structured reference with a separate package and name.
     */
    superClasses: MetamodelClassRef[];

    /**
     * Properties defined on this class (not inherited).
     */
    properties: MetamodelPropertyInfo[];

    /**
     * Relations defined on this class (not inherited).
     */
    relations: MetamodelRelationInfo[];

    /**
     * Optional reference to the source metamodel AST node that defines this class.
     * Used for creating reference descriptions for LSP rename and find-references.
     */
    languageNode?: AstNode;
}
