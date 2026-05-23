import type { ClassType, Property } from "../../typir-extensions/config/type.js";
import { getEnumContainerPackage, getEnumPackage } from "./metamodelClassExtractor.js";
import type { MetamodelEnumInfo } from "./metamodelEnumInfo.js";

/**
 * Result of generating enum types from metamodel enum information.
 */
export interface EnumTypeGenerationResult {
    /**
     * The value types representing individual enum values (package = "enum").
     */
    types: ClassType[];
    /**
     * The virtual container types exposing enum entries as readonly properties (package = "enum-container").
     */
    containerTypes: ClassType[];
}

/**
 * Generates ClassType definitions from MetamodelEnumInfo for the Typir type system.
 * This function creates a new generator instance, processes the enums, and returns the results.
 *
 * For each enum:
 * - A *value type* is created representing the type of individual enum values (package from enumInfo).
 * - A *container type* is created (`isVirtual: true`) with one readonly
 *   property per enum entry typed as the value type. Allows member-access like `Status.ACTIVE` in the DSL.
 *
 * @param enumInfos The enum information extracted from the metamodel
 * @param absolutePath The absolute path of the metamodel file for generating container package names
 * @returns Object containing both types (value types) and containerTypes (virtual container types)
 */
export function generateEnumTypes(enumInfos: MetamodelEnumInfo[], absolutePath: string): EnumTypeGenerationResult {
    const generator = new EnumTypeGenerator(absolutePath);
    return generator.generateEnumTypes(enumInfos);
}

/**
 * Internal class for generating enum ClassTypes from MetamodelEnumInfo.
 * Should not be instantiated directly; use generateEnumTypes() instead.
 */
class EnumTypeGenerator {
    private readonly enumPackage: string;
    private readonly enumContainerPackage: string;

    constructor(absolutePath: string) {
        this.enumPackage = getEnumPackage(absolutePath);
        this.enumContainerPackage = getEnumContainerPackage(absolutePath);
    }

    /**
     * Generates value and container ClassTypes for each MetamodelEnumInfo.
     *
     * @param enumInfos The enum information extracted from the metamodel
     * @returns Object containing both types (value types) and containerTypes (virtual container types)
     */
    generateEnumTypes(enumInfos: MetamodelEnumInfo[]): EnumTypeGenerationResult {
        const types: ClassType[] = [];
        const containerTypes: ClassType[] = [];

        for (const enumInfo of enumInfos) {
            const { valueType, containerType } = this.generateEnumType(enumInfo);
            types.push(valueType);
            containerTypes.push(containerType);
        }

        return { types, containerTypes };
    }

    /**
     * Generates both the value type and container type for a single enum.
     *
     * @param enumInfo The enum information to process
     * @returns Object containing the value type and container type
     */
    private generateEnumType(enumInfo: MetamodelEnumInfo): { valueType: ClassType; containerType: ClassType } {
        const valueType: ClassType = {
            name: enumInfo.name,
            package: enumInfo.package,
            properties: {},
            methods: {},
            superTypes: [{ package: "builtin", type: "Any" }],
            languageNode: enumInfo.languageNode
        };

        const containerProperties: Record<string, Property> = {};
        for (const entry of enumInfo.entries) {
            containerProperties[entry.name] = {
                name: entry.name,
                isProperty: true,
                readonly: true,
                type: { package: enumInfo.package, type: enumInfo.name, isNullable: false },
                languageNode: entry.languageNode
            };
        }

        const containerType: ClassType = {
            name: enumInfo.name,
            package: this.enumContainerPackage,
            properties: containerProperties,
            methods: {},
            isVirtual: true
        };

        return { valueType, containerType };
    }
}
