import type { TypirSpecifics, TypirProblem, Type as TypirType, TypeEqualityProblem } from "typir";
import type { CustomClassDetails, CustomClassKind } from "./custom-class-kind.js";
import { CustomValueTypeImplementation, type CustomValueType } from "../custom-value/custom-value-type.js";
import type { ClassTypeRef, Property, Method, ValueType } from "../../config/type.js";
import type { CustomFunctionType } from "../custom-function/custom-function-type.js";
import { sharedImport } from "@mdeo/language-shared";
import { addDependsOnTypeEdge } from "../../service/customTypeGraph.js";

const {
    checkValueForConflict,
    TypeEqualityProblem: TypeEqualityProblemConstant,
    createKindConflict
} = sharedImport("typir");

/**
 * Constructor interface for custom class types.
 * Represents the class itself (static side), not instances.
 */
export interface CustomClassTypeConstructor {
    new (kind: CustomClassKind<TypirSpecifics>, details: CustomClassDetails<TypirSpecifics>): CustomClassType;
}

/**
 * Interface for custom class types.
 * Represents a concrete class type with properties, methods, and type arguments.
 */
export interface CustomClassType extends CustomValueType<CustomClassDetails<TypirSpecifics>> {
    /**
     * The kind that created this type
     */
    readonly kind: CustomClassKind<TypirSpecifics>;
}

/**
 * Custom class type implementation.
 * Represents a concrete class type with properties, methods, and type arguments.
 */
export class CustomClassTypeImplementation
    extends CustomValueTypeImplementation<CustomClassDetails<TypirSpecifics>>
    implements CustomClassType
{
    declare readonly kind: CustomClassKind<TypirSpecifics>;

    /**
     * Cache for the nullable/non-nullable variant of this type
     */
    private nullInvertedTypeCache: CustomClassType | undefined = undefined;

    /**
     * Cached definition of this class type
     */
    private _definition: ClassTypeRef | undefined = undefined;

    /**
     * Creates a new custom class type.
     * Automatically registers subtype relationships and initializes the type.
     *
     * @param kind The kind that created this type
     * @param details The class type details
     */
    constructor(kind: CustomClassKind<TypirSpecifics>, details: CustomClassDetails<TypirSpecifics>) {
        super(
            buildCustomClassIdentifier(details),
            details,
            kind.services,
            details.isNullable,
            details.definition.superTypes ?? []
        );
        this.kind = kind;
        this.registerSubtypesAndConversion();
        this.defineTheInitializationProcessOfThisType({});
    }

    override get isNullable(): boolean {
        return this.details.isNullable;
    }

    override get asNullable(): CustomClassType {
        if (this.isNullable) {
            return this;
        }
        if (this.nullInvertedTypeCache != undefined) {
            return this.nullInvertedTypeCache;
        }
        const nullableType = this.kind.services.factory.CustomClasses.getOrCreate({
            ...this.details,
            isNullable: true
        });
        this.nullInvertedTypeCache = nullableType;
        return nullableType;
    }

    override get asNonNullable(): CustomClassType {
        if (!this.isNullable) {
            return this;
        }
        if (this.nullInvertedTypeCache != undefined) {
            return this.nullInvertedTypeCache;
        }
        const nonNullableType = this.kind.services.factory.CustomClasses.getOrCreate({
            ...this.details,
            isNullable: false
        });
        this.nullInvertedTypeCache = nonNullableType;
        return nonNullableType;
    }

    override get definition(): ClassTypeRef {
        if (this._definition == undefined) {
            const definition = this.details.definition;
            const typeArgs: Record<string, ValueType> = {};
            for (const [argName, argType] of this.details.typeArgs) {
                typeArgs[argName] = argType.definition;
            }
            this._definition = {
                package: definition.package,
                type: definition.name,
                isNullable: this.isNullable,
                typeArgs: typeArgs
            };
        }
        return this._definition;
    }

    override getName(): string {
        return buildCustomClassName(this.details, false);
    }

    override getUserRepresentation(): string {
        return buildCustomClassIdentifier(this.details);
    }

    override analyzeTypeEqualityProblems(otherType: TypirType): TypirProblem[] {
        if (isCustomClassType(otherType)) {
            return checkValueForConflict(this.getIdentifier(), otherType.getIdentifier(), "name");
        } else {
            return [
                <TypeEqualityProblem>{
                    $problem: TypeEqualityProblemConstant,
                    type1: this,
                    type2: otherType,
                    subProblems: [createKindConflict(otherType, this)]
                }
            ];
        }
    }

    override getLocalProperty(memberName: string): Property | undefined {
        if (!Object.hasOwn(this.details.definition.properties, memberName)) {
            return undefined;
        }
        return this.details.definition.properties[memberName];
    }

    override getLocalMethod(memberName: string): Method | undefined {
        if (!Object.hasOwn(this.details.definition.methods, memberName)) {
            return undefined;
        }
        return this.details.definition.methods[memberName];
    }

    override getLocalProperties(): Property[] {
        return Object.values(this.details.definition.properties);
    }

    override getLocalMethods(): Method[] {
        return Object.values(this.details.definition.methods);
    }

    /**
     * Registers dependency edges for all dependent custom types.
     * Must be called after this type has been added to the type graph.
     */
    registerTypeDependencies(): void {
        for (const typeArg of this.details.typeArgs.values()) {
            this.registerTypeDependency(typeArg);
        }

        for (const superClass of this.superClasses) {
            this.registerTypeDependency(superClass);
        }
    }

    /**
     * Registers a single dependency edge.
     *
     * @param dependencyType The dependency type
     */
    private registerTypeDependency(dependencyType: CustomValueType | CustomFunctionType): void {
        addDependsOnTypeEdge(this.services.infrastructure.Graph, this, dependencyType);
    }
}

/**
 * Type guard to check if a value is a CustomClassType.
 *
 * @param type The value to check
 * @returns true if the value is a CustomClassType
 */
export function isCustomClassType(type: unknown): type is CustomClassType {
    return type instanceof CustomClassTypeImplementation;
}

/**
 * Escapes special characters in a string for use in type identifiers.
 * Special characters: . \ < > ?
 * @param str The string to escape
 * @returns The escaped string
 */
export function escapeIdentifierPart(str: string): string {
    return str.replace(/[.\\<>?]/g, "\\$&");
}

/**
 * Unescapes special characters in an identifier part.
 * @param str The escaped string
 * @returns The unescaped string
 */
export function unescapeIdentifierPart(str: string): string {
    return str.replace(/\\([.\\<>?])/g, "$1");
}

/**
 * Builds a unique identifier for a custom class type.
 * Uses escaping to handle special characters in package and name.
 * Format: escapedPackage.escapedName<typeArg1,typeArg2>?
 * @param details The custom class details
 * @returns The unique identifier string
 */
export function buildCustomClassIdentifier(details: CustomClassDetails<any>): string {
    const escapedPackage = escapeIdentifierPart(details.definition.package);
    const escapedName = escapeIdentifierPart(details.definition.name);

    const typeArgsStr = Array.from(details.typeArgs.values())
        .map((typeArg) => typeArg.getIdentifier())
        .join(",");

    const baseName = `${escapedPackage}.${escapedName}`;
    const withTypeArgs = typeArgsStr.length > 0 ? `${baseName}<${typeArgsStr}>` : baseName;
    return details.isNullable ? `${withTypeArgs}?` : withTypeArgs;
}

/**
 * Build a name for a custom class type.
 *
 * @param details The class type details
 * @param isIdentifier Whether to build a full identifier (with package) or just a name
 * @returns The name string
 */
function buildCustomClassName(details: CustomClassDetails<any>, isIdentifier: boolean): string {
    const name = isIdentifier ? details.definition.package + "." + details.definition.name : details.definition.name;
    const typeArgsStr = Array.from(details.typeArgs.values())
        .map((typeArg) => (isIdentifier ? typeArg.getIdentifier() : typeArg.getName()))
        .join(",");
    return typeArgsStr.length > 0
        ? `${name}<${typeArgsStr}>${details.isNullable ? "?" : ""}`
        : `${name}${details.isNullable ? "?" : ""}`;
}
