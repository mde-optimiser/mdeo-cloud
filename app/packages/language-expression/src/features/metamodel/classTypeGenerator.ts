import { FunctionSignature } from "../../typir-extensions/config/type.js";
import type { ClassType, Property, Method } from "../../typir-extensions/config/type.js";
import { getClassContainerPackage, getClassPackage } from "./metamodelClassExtractor.js";
import type { MetamodelClassInfo, MetamodelPropertyInfo, MetamodelRelationInfo } from "./metamodelClassInfo.js";

/**
 * Result of generating class types from metamodel class information.
 */
export interface ClassTypeGenerationResult {
    /**
     * The class types representing metamodel classes (package = "class").
     */
    types: ClassType[];
    /**
     * The virtual container types exposing an all() method for each class (package = "class-container").
     */
    containerTypes: ClassType[];
}

/**
 * Generates ClassType definitions from MetamodelClassInfo for the Typir type system.
 * This function creates a new generator instance, processes the classes, and returns the results.
 * The generator should not be reused.
 *
 * For each class:
 * - A *class type* is created representing the metamodel class (package from classInfo).
 * - A *container type* is created (`isVirtual: true`) with an `all()` method
 *   that returns a Collection of that class type. Allows access like `Person.all()` in the DSL.
 *
 * @param classInfos The intermediate class representations to convert
 * @param absolutePath The absolute path of the metamodel file for generating container package names
 * @returns Object containing both types (class types) and containerTypes (virtual container types)
 */
export function generateClassTypes(classInfos: MetamodelClassInfo[], absolutePath: string): ClassTypeGenerationResult {
    const generator = new ClassTypeGenerator(absolutePath);
    return generator.generateClassTypes(classInfos);
}

/**
 * Internal class for converting MetamodelClassInfo to ClassType for the Typir type system.
 * Should not be instantiated directly; use generateClassTypes() instead.
 */
class ClassTypeGenerator {
    private readonly classPackage: string;
    private readonly classContainerPackage: string;

    constructor(absolutePath: string) {
        this.classPackage = getClassPackage(absolutePath);
        this.classContainerPackage = getClassContainerPackage(absolutePath);
    }

    /**
     * Generates class types and container types for each MetamodelClassInfo.
     *
     * @param classInfos The intermediate class representations to convert
     * @returns Object containing both types (class types) and containerTypes (virtual container types)
     */
    generateClassTypes(classInfos: MetamodelClassInfo[]): ClassTypeGenerationResult {
        const types: ClassType[] = [];
        const containerTypes: ClassType[] = [];

        for (const classInfo of classInfos) {
            const { classType, containerType } = this.generateClassType(classInfo);
            types.push(classType);
            containerTypes.push(containerType);
        }

        return { types, containerTypes };
    }

    /**
     * Generates both the class type and container type for a single class.
     *
     * @param info The class information to convert
     * @returns Object containing the class type and container type
     */
    private generateClassType(info: MetamodelClassInfo): { classType: ClassType; containerType: ClassType } {
        const properties: Record<string, Property> = {};
        const methods: Record<string, Method> = {};

        for (const prop of info.properties) {
            properties[prop.name] = this.createPropertyMember(prop);
        }

        for (const rel of info.relations) {
            properties[rel.property] = this.createRelationMember(rel);
        }

        const superTypes = info.superClasses.map((superClass) => ({
            package: superClass.package,
            type: superClass.name
        }));
        if (superTypes.length === 0) {
            superTypes.push({ package: "builtin", type: "Any" });
        }

        const classType: ClassType = {
            name: info.name,
            package: info.package,
            properties,
            methods,
            superTypes,
            languageNode: info.languageNode
        };

        const containerType = this.createContainerType(info.name);

        return { classType, containerType };
    }

    /**
     * Creates a virtual container type for a class with an all() method.
     * The all() method returns a Collection of the class type.
     *
     * @param className The name of the class
     * @returns The container type with an all() method
     */
    private createContainerType(className: string): ClassType {
        const allMethodSignature: FunctionSignature = {
            returnType: {
                package: "builtin",
                type: "Collection",
                isNullable: false,
                typeArgs: {
                    T: { package: this.classPackage, type: className, isNullable: false }
                }
            },
            parameters: []
        };

        const allMethod: Method = {
            name: "all",
            isProperty: false,
            type: {
                signatures: {
                    [FunctionSignature.DEFAULT_SIGNATURE]: allMethodSignature
                }
            }
        };

        const firstMethodSignature: FunctionSignature = {
            returnType: { package: this.classPackage, type: className, isNullable: false },
            parameters: []
        };

        const firstMethod: Method = {
            name: "first",
            isProperty: false,
            type: {
                signatures: {
                    [FunctionSignature.DEFAULT_SIGNATURE]: firstMethodSignature
                }
            }
        };

        const firstOrNullMethodSignature: FunctionSignature = {
            returnType: { package: this.classPackage, type: className, isNullable: true },
            parameters: []
        };

        const firstOrNullMethod: Method = {
            name: "firstOrNull",
            isProperty: false,
            type: {
                signatures: {
                    [FunctionSignature.DEFAULT_SIGNATURE]: firstOrNullMethodSignature
                }
            }
        };

        return {
            name: className,
            package: this.classContainerPackage,
            properties: {},
            methods: { all: allMethod, first: firstMethod, firstOrNull: firstOrNullMethod },
            isVirtual: true
        };
    }

    /**
     * Creates a Member definition from a property.
     *
     * @param prop The property information
     * @returns The Member definition
     */
    private createPropertyMember(prop: MetamodelPropertyInfo): Property {
        return {
            name: prop.name,
            isProperty: true,
            type: prop.valueType,
            languageNode: prop.languageNode
        };
    }

    /**
     * Creates a Member definition from a relation.
     * Type references are already fully qualified in the relation info.
     *
     * @param rel The relation information
     * @returns The Member definition
     */
    private createRelationMember(rel: MetamodelRelationInfo): Property {
        return {
            name: rel.property,
            isProperty: true,
            type: rel.valueType,
            languageNode: rel.languageNode
        };
    }
}
