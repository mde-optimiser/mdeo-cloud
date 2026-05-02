import type { AstReflection } from "@mdeo/language-common";
import {
    Association,
    Enum,
    EnumTypeReference,
    PrimitiveType,
    type AssociationEndType,
    type AssociationType,
    type ClassType as MetamodelClassType,
    type EnumType as MetamodelEnumType,
    type MetaModelType,
    type MultiplicityType,
    type PropertyType,
    resolveClassChain,
    isMultipleMultiplicity,
    isOptionalMultiplicity
} from "@mdeo/language-metamodel";
import type { ValueType } from "../../typir-extensions/config/type.js";
import type {
    MetamodelClassInfo,
    MetamodelClassRef,
    MetamodelPropertyInfo,
    MetamodelRelationInfo
} from "./metamodelClassInfo.js";
import { DefaultTypeNames } from "../../type-system/typeSystemConfig.js";
import { typeRef } from "../../typir-extensions/config/typeBuilder.js";
import type { MetamodelEnumInfo } from "./metamodelEnumInfo.js";
import { sharedImport } from "@mdeo/language-shared";

const { AstUtils } = sharedImport("langium");

/**
 * Configuration for creating collection types from element types.
 */
export interface CollectionTypeFactory {
    /**
     * Creates a ReadonlyList type from an element type (used for multi-valued properties).
     *
     * @param elementType The type of elements in the list
     * @returns A ValueType representing the ReadonlyList type
     */
    createListType: (elementType: ValueType) => ValueType;
    /**
     * Creates a ReadonlySet type from an element type (used for multi-valued association ends).
     *
     * @param elementType The type of elements in the set
     * @returns A ValueType representing the ReadonlySet type
     */
    createSetType: (elementType: ValueType) => ValueType;
}

/**
 * Default collection type factory for metamodel extraction.
 * Creates List types for properties and Set types for association ends.
 */
export const DefaultCollectionTypeFactory: CollectionTypeFactory = {
    createListType: (elementType) => typeRef("builtin", "List").withTypeArgs({ T: elementType }).build(),
    createSetType: (elementType) => typeRef("builtin", "Set").withTypeArgs({ T: elementType }).build()
};

/**
 * Readonly collection type factory for metamodel extraction.
 * Creates ReadonlyList types for properties and ReadonlySet types for association ends.
 * Use this factory when the extracted metamodel should not allow modifications to collections.
 */
export const ReadonlyCollectionTypeFactory: CollectionTypeFactory = {
    createListType: (elementType) => typeRef("builtin", "ReadonlyList").withTypeArgs({ T: elementType }).build(),
    createSetType: (elementType) => typeRef("builtin", "ReadonlySet").withTypeArgs({ T: elementType }).build()
};

/**
 * Extracts class and enum information from metamodel entities.
 * This function creates a new extractor instance, processes the classes and enums, and returns the results.
 * The extractor should not be reused.
 *
 * @param classes The classes to analyze (directly defined, not imports)
 * @param enums The enums to analyze (directly defined, not imports)
 * @param reflection The AST reflection for type checking
 * @param collectionTypeFactory Factory for creating list types
 * @param absolutePath The absolute path of the metamodel file (e.g., "/path/to/file.mm")
 * @returns Object containing arrays of MetamodelClassInfo and MetamodelEnumInfo
 */
export function extractMetamodelEntities(
    classes: MetamodelClassType[],
    enums: MetamodelEnumType[],
    reflection: AstReflection,
    collectionTypeFactory: CollectionTypeFactory,
    absolutePath: string
): { classes: MetamodelClassInfo[]; enums: MetamodelEnumInfo[] } {
    const extractor = new MetamodelClassExtractor(reflection, collectionTypeFactory, absolutePath);
    const classInfos = extractor.extractClasses(classes);
    const enumPackage = getEnumPackage(absolutePath);
    const enumContainerPackage = getEnumContainerPackage(absolutePath);
    const enumInfos: MetamodelEnumInfo[] = enums.map((enumNode) => ({
        name: enumNode.name,
        package: enumPackage,
        containerPackage: enumContainerPackage,
        entries: enumNode.entries.map((e) => e.name)
    }));
    return { classes: classInfos, enums: enumInfos };
}

/**
 * Generates the package name for a class given its absolute path.
 * @param absolutePath The absolute path of the metamodel file (e.g., "/path/to/file")
 * @returns The package name (e.g., "class/path/to/file")
 */
export function getClassPackage(absolutePath: string): string {
    return `class${absolutePath}`;
}

/**
 * Generates the package name for an enum given its absolute path.
 * @param absolutePath The absolute path of the metamodel file (e.g., "/path/to/file")
 * @returns The package name (e.g., "enum/path/to/file")
 */
export function getEnumPackage(absolutePath: string): string {
    return `enum${absolutePath}`;
}

/**
 * Generates the package name for an enum container given its absolute path.
 * @param absolutePath The absolute path of the metamodel file (e.g., "/path/to/file")
 * @returns The package name (e.g., "enum-container/path/to/file")
 */
export function getEnumContainerPackage(absolutePath: string): string {
    return `enum-container${absolutePath}`;
}

/**
 * Generates the package name for a class container given its absolute path.
 * @param absolutePath The absolute path of the metamodel file (e.g., "/path/to/file")
 * @returns The package name (e.g., "class-container/path/to/file")
 */
export function getClassContainerPackage(absolutePath: string): string {
    return `class-container${absolutePath}`;
}

/**
 * Internal class for extracting class information from metamodel AST nodes.
 * Generates an intermediate representation that can be converted to ClassTypes.
 * Should not be instantiated directly; use extractMetamodelEntities() instead.
 *
 * Package names are generated using the absolute file path (e.g., "class/path/to/file").
 */
class MetamodelClassExtractor {
    private readonly classNameMap = new Map<MetamodelClassType, string>();
    private readonly allClassesSet = new Set<MetamodelClassType>();
    private readonly directClassNodes = new Set<MetamodelClassType>();
    private readonly classPackage: string;
    private readonly classContainerPackage: string;

    /**
     * Creates a new MetamodelClassExtractor.
     *
     * @param reflection The AST reflection for type checking
     * @param collectionTypeFactory Factory for creating list types
     * @param absolutePath The absolute path of the metamodel file
     */
    constructor(
        private readonly reflection: AstReflection,
        private readonly collectionTypeFactory: CollectionTypeFactory,
        absolutePath: string
    ) {
        this.classPackage = getClassPackage(absolutePath);
        this.classContainerPackage = getClassContainerPackage(absolutePath);
    }

    /**
     * Extracts class information from a list of metamodel classes.
     *
     * @param classes The classes to analyze
     * @returns Array of MetamodelClassInfo representing the extracted classes
     */
    extractClasses(classes: MetamodelClassType[]): MetamodelClassInfo[] {
        this.processTopLevelClasses(classes);
        return this.buildClassInfos();
    }

    /**
     * Processes top-level classes to build the class name map.
     * Duplicate names (direct or from the inheritance chain) are silently ignored.
     *
     * @param classes The classes to analyze
     */
    private processTopLevelClasses(classes: MetamodelClassType[]): void {
        const usedNames = new Set<string>();

        for (const classNode of classes) {
            const name = classNode.name;
            if (!usedNames.has(name)) {
                usedNames.add(name);
                this.classNameMap.set(classNode, name);
                this.directClassNodes.add(classNode);
            }
        }

        for (const classNode of classes) {
            const classChain = resolveClassChain(classNode, this.reflection) as MetamodelClassType[];
            for (const chainClass of classChain) {
                this.allClassesSet.add(chainClass);
                if (!this.classNameMap.has(chainClass)) {
                    const chainName = chainClass.name;
                    if (!usedNames.has(chainName)) {
                        usedNames.add(chainName);
                        this.classNameMap.set(chainClass, chainName);
                    }
                }
            }
        }
    }

    /**
     * Builds the complete class information for all extracted classes.
     * Collects associations and generates MetamodelClassInfo for each class.
     *
     * @returns Array of MetamodelClassInfo
     */
    private buildClassInfos(): MetamodelClassInfo[] {
        const associations = this.collectAssociations();
        const result: MetamodelClassInfo[] = [];

        for (const [classNode, simpleName] of this.classNameMap.entries()) {
            if (this.directClassNodes.has(classNode)) {
                const info = this.buildClassInfo(classNode, simpleName, associations);
                result.push(info);
            }
        }

        return result;
    }

    /**
     * Collects all associations from the metamodels that contain the extracted classes.
     *
     * @returns Array of associations found in the relevant metamodels
     */
    private collectAssociations(): AssociationType[] {
        const associations: AssociationType[] = [];
        const metamodelSet = new Set<MetaModelType>();

        for (const cls of this.allClassesSet) {
            const metamodel = this.getMetaModel(cls);
            if (metamodel != undefined) {
                metamodelSet.add(metamodel);
            }
        }

        for (const metamodel of metamodelSet) {
            for (const element of metamodel.elements ?? []) {
                if (this.reflection.isInstance(element, Association)) {
                    associations.push(element);
                }
            }
        }

        return associations;
    }

    /**
     * Finds the containing metamodel for a class by traversing up the AST.
     *
     * @param cls The class to find the metamodel for
     * @returns The containing metamodel or undefined if not found
     */
    private getMetaModel(cls: MetamodelClassType): MetaModelType | undefined {
        let container = cls.$container;
        while (container != undefined) {
            if ("elements" in container && "imports" in container) {
                return container as MetaModelType;
            }
            container = container.$container;
        }
        return undefined;
    }

    /**
     * Builds complete class information including properties, relations, and superclasses.
     *
     * @param classNode The metamodel class node
     * @param name The unique name assigned to this class
     * @param associations All associations from the metamodel
     * @returns Complete MetamodelClassInfo
     */
    private buildClassInfo(
        classNode: MetamodelClassType,
        name: string,
        associations: AssociationType[]
    ): MetamodelClassInfo {
        const superClasses = this.extractSuperClasses(classNode);
        const properties = this.extractProperties(classNode);
        const relations = this.extractRelations(classNode, associations);

        return {
            name: name,
            package: this.classPackage,
            containerPackage: this.classContainerPackage,
            superClasses,
            properties,
            relations
        };
    }

    /**
     * Determines the class package for any given class node by traversing up to the
     * root AST node and reading the Langium document URI.
     * Falls back to the current file's package when the document cannot be resolved.
     *
     * @param classNode The class node whose actual source package should be returned
     * @returns The class package string (e.g. "class/path/to/file.mm")
     */
    private getActualPackageForClass(classNode: MetamodelClassType): string {
        const doc = AstUtils.getDocument(classNode);
        if (doc?.uri?.path != undefined) {
            return getClassPackage(doc.uri.path);
        }
        return this.classPackage;
    }

    /**
     * Extracts superclass references from a class's extension declarations.
     *
     * @param classNode The class to extract superclasses from
     * @returns Array of structured superclass references (package + name)
     */
    private extractSuperClasses(classNode: MetamodelClassType): MetamodelClassRef[] {
        const superClasses: MetamodelClassRef[] = [];
        const extensions = classNode.extensions;
        for (const extension of extensions?.extensions ?? []) {
            const parentClass = extension.class.ref;
            if (parentClass != undefined) {
                const parentName = this.classNameMap.get(parentClass);
                if (parentName != undefined) {
                    const parentPackage = this.getActualPackageForClass(parentClass);
                    superClasses.push({ package: parentPackage, name: parentName });
                }
            }
        }
        return superClasses;
    }

    /**
     * Extracts property information from a class's property declarations.
     *
     * @param classNode The class to extract properties from
     * @returns Array of property information
     */
    private extractProperties(classNode: MetamodelClassType): MetamodelPropertyInfo[] {
        return classNode.properties.map((prop) => ({
            name: prop.name,
            valueType: this.resolvePropertyType(prop)
        }));
    }

    /**
     * Resolves the complete type of a property including multiplicity.
     * Multi-valued properties use ReadonlyList.
     *
     * @param property The property to resolve the type for
     * @returns The complete ValueType including multiplicity handling
     */
    private resolvePropertyType(property: PropertyType): ValueType {
        const baseType = this.resolveBasePropertyType(property);
        return this.applyMultiplicity(baseType, property.multiplicity, this.collectionTypeFactory.createListType);
    }

    /**
     * Resolves the base type of a property without considering multiplicity.
     * Handles primitive types and enum type references.
     *
     * @param property The property to resolve the base type for
     * @returns The base ValueType
     */
    private resolveBasePropertyType(property: PropertyType): ValueType {
        const typeValue = property.type;
        if (this.reflection.isInstance(typeValue, PrimitiveType)) {
            return this.resolvePrimitiveType(typeValue.name);
        }
        if (this.reflection.isInstance(typeValue, EnumTypeReference)) {
            const enumNode = typeValue.enum?.ref;
            if (this.reflection.isInstance(enumNode, Enum)) {
                const doc = AstUtils.getDocument(enumNode);
                const enumPath = doc.uri.path;
                return { package: getEnumPackage(enumPath), type: enumNode.name, isNullable: false };
            }
        }
        return { package: "builtin", type: DefaultTypeNames.String, isNullable: false };
    }

    /**
     * Maps a primitive type name to the corresponding type system type name.
     *
     * @param name The primitive type name from the metamodel
     * @returns The ValueType for this primitive
     */
    private resolvePrimitiveType(name: string): ValueType {
        const typeNameMap: Record<string, string> = {
            int: DefaultTypeNames.Int,
            long: DefaultTypeNames.Long,
            float: DefaultTypeNames.Float,
            double: DefaultTypeNames.Double,
            string: DefaultTypeNames.String,
            boolean: DefaultTypeNames.Boolean
        };
        return { package: "builtin", type: typeNameMap[name] ?? DefaultTypeNames.String, isNullable: false };
    }

    /**
     * Applies multiplicity modifiers to a base type (collection, optional, or required).
     *
     * @param baseType The base type to modify
     * @param multiplicity The multiplicity specification
     * @param createCollectionType Factory function to create the collection type for multiplicity > 1
     * @returns The modified ValueType with multiplicity applied
     */
    private applyMultiplicity(
        baseType: ValueType,
        multiplicity: MultiplicityType | undefined,
        createCollectionType: (elementType: ValueType) => ValueType
    ): ValueType {
        if (multiplicity == undefined) {
            return baseType;
        } else if (isMultipleMultiplicity(multiplicity, this.reflection)) {
            return createCollectionType(baseType);
        } else if (isOptionalMultiplicity(multiplicity, this.reflection)) {
            return { ...baseType, isNullable: true };
        } else {
            return baseType;
        }
    }

    /**
     * Extracts relation information from associations relevant to a class.
     *
     * @param classNode The class to extract relations for
     * @param associations All associations from the metamodel
     * @returns Array of relation information
     */
    private extractRelations(classNode: MetamodelClassType, associations: AssociationType[]): MetamodelRelationInfo[] {
        const relations: MetamodelRelationInfo[] = [];

        for (const assoc of associations) {
            this.processAssociationForClass(classNode, assoc, relations);
        }

        return relations;
    }

    /**
     * Processes a single association to extract relations for a specific class.
     * Adds relation entries for both source and target ends if applicable.
     *
     * @param classNode The class to extract relations for
     * @param assoc The association to process
     * @param relations Output array to add relations to
     */
    private processAssociationForClass(
        classNode: MetamodelClassType,
        assoc: AssociationType,
        relations: MetamodelRelationInfo[]
    ): void {
        const sourceClass = assoc.source?.class?.ref;
        const targetClass = assoc.target?.class?.ref;

        if (sourceClass == undefined || targetClass == undefined) {
            return;
        }

        if (!this.allClassesSet.has(sourceClass) || !this.allClassesSet.has(targetClass)) {
            return;
        }

        if (sourceClass === classNode && assoc.source.name != undefined) {
            const oppositePropertyName = assoc.target.name;
            const relation = this.createRelationInfo(assoc.source, targetClass, oppositePropertyName, true);
            if (relation != undefined) {
                relations.push(relation);
            }
        }

        if (targetClass === classNode && assoc.target.name != undefined) {
            const oppositePropertyName = assoc.source.name;
            const relation = this.createRelationInfo(assoc.target, sourceClass, oppositePropertyName, false);
            if (relation != undefined) {
                relations.push(relation);
            }
        }
    }

    /**
     * Creates relation information from an association end.
     * Uses the opposite class's own document path for type naming.
     *
     * @param end The association end to create a relation from
     * @param oppositeClass The class at the opposite end
     * @param oppositePropertyName The property name at the opposite end, or undefined if unnamed
     * @param isOutgoing Whether this is an outgoing relation
     * @returns The relation information or undefined if invalid
     */
    private createRelationInfo(
        end: AssociationEndType,
        oppositeClass: MetamodelClassType,
        oppositePropertyName: string | undefined,
        isOutgoing: boolean
    ): MetamodelRelationInfo | undefined {
        if (end.name == undefined) {
            return undefined;
        }

        const oppositeSimpleName = this.classNameMap.get(oppositeClass);
        if (oppositeSimpleName == undefined) {
            return undefined;
        }

        const oppositePackage = this.getActualPackageForClass(oppositeClass);
        const oppositeClassName = `${oppositePackage}.${oppositeSimpleName}`;
        const baseType: ValueType = { package: oppositePackage, type: oppositeSimpleName, isNullable: false };
        const valueType = this.applyMultiplicity(baseType, end.multiplicity, this.collectionTypeFactory.createSetType);

        return {
            property: end.name,
            oppositeProperty: oppositePropertyName,
            oppositeClassName,
            isOutgoing,
            valueType
        };
    }
}
