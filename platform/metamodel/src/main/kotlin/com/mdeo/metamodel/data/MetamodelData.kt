package com.mdeo.metamodel.data

import kotlinx.serialization.Serializable

/**
 * A resolved multiplicity with lower and upper bounds.
 * upper is -1 to represent an unbounded (∞) upper bound.
 */
@Serializable
data class MultiplicityData(
    val lower: Int,
    val upper: Int
) {
    companion object {
        /**
         * Creates a multiplicity representing a single required value [1..1].
         */
        fun single(): MultiplicityData = MultiplicityData(1, 1)
        
        /**
         * Creates a multiplicity representing an optional value [0..1].
         */
        fun optional(): MultiplicityData = MultiplicityData(0, 1)
        
        /**
         * Creates a multiplicity representing many values [0..*].
         */
        fun many(): MultiplicityData = MultiplicityData(0, -1)
        
        /**
         * Creates a multiplicity representing one or more values [1..*].
         */
        fun oneOrMore(): MultiplicityData = MultiplicityData(1, -1)
    }
    
    /**
     * Returns true if this multiplicity allows multiple values (upper > 1 or unbounded).
     */
    fun isMultiple(): Boolean = upper == -1 || upper > 1
    
    /**
     * Returns true if this multiplicity requires at least one value (lower >= 1).
     */
    fun isRequired(): Boolean = lower >= 1
}

/**
 * Data for a single enum definition in the metamodel.
 */
@Serializable
data class EnumData(
    /**
     * The enum name.
     */
    val name: String,
    /**
     * List of enum entry names.
     */
    val entries: List<String>,
    /**
     * The absolute path of the metamodel file declaring this enum, when that is a file the
     * metamodel imports; `null` for [MetamodelData.path] itself.
     */
    val path: String? = null
)

/**
 * Data for a single property of a metamodel class.
 */
@Serializable
data class PropertyData(
    /**
     * The property name.
     */
    val name: String,
    /**
     * The enum name if this property has an enum type.
     * Mutually exclusive with primitiveType.
     */
    val enumType: String? = null,
    /**
     * The primitive type name if this property has a primitive type.
     * Mutually exclusive with enumType.
     */
    val primitiveType: String? = null,
    /**
     * The multiplicity of the property.
     */
    val multiplicity: MultiplicityData
)

/**
 * Data for a single metamodel class.
 */
@Serializable
data class ClassData(
    /**
     * The class name.
     */
    val name: String,
    /**
     * Whether the class is abstract.
     */
    val isAbstract: Boolean,
    /**
     * Names of parent classes this class extends.
     */
    val extends: List<String> = emptyList(),
    /**
     * The properties defined on this class.
     */
    val properties: List<PropertyData> = emptyList(),
    /**
     * The absolute path of the metamodel file declaring this class, when that is a file the
     * metamodel imports; `null` for [MetamodelData.path] itself.
     */
    val path: String? = null
)

/**
 * Data for one end of an association.
 */
@Serializable
data class AssociationEndData(
    /**
     * The name of the class at this end.
     */
    val className: String,
    /**
     * Optional role name for this end.
     */
    val name: String? = null,
    /**
     * The multiplicity at this end.
     */
    val multiplicity: MultiplicityData
)

/**
 * Data for a metamodel association.
 */
@Serializable
data class AssociationData(
    val source: AssociationEndData,
    val operator: String,
    val target: AssociationEndData
)

/**
 * Complete metamodel data structure containing all class, enum, and association definitions.
 * This mirrors the MetamodelAstData structure from the TypeScript side.
 */
@Serializable
data class MetamodelData(
    /**
     * The absolute file-system path of this metamodel file.
     */
    val path: String = "",
    /**
     * All class definitions in the metamodel.
     */
    val classes: List<ClassData> = emptyList(),
    /**
     * All enum definitions in the metamodel.
     */
    val enums: List<EnumData> = emptyList(),
    /**
     * All association definitions in the metamodel.
     */
    val associations: List<AssociationData> = emptyList(),
    /**
     * Absolute file-system paths of all metamodel files imported by this metamodel.
     */
    val importedMetamodelPaths: List<String> = emptyList()
) {
    /**
     * The absolute path of the metamodel file declaring [classData]. Types of a class are named
     * after that file, as in the languages' type systems.
     */
    fun pathOf(classData: ClassData): String = classData.path ?: path

    /**
     * The absolute path of the metamodel file declaring [enumData].
     */
    fun pathOf(enumData: EnumData): String = enumData.path ?: path

    companion object {
        /**
         * Creates an empty MetamodelData instance.
         */
        fun empty(): MetamodelData = MetamodelData()
    }
}
