import type { TypirProblem, TypirSpecifics, Type as TypirType, TypeEqualityProblem } from "typir";
import { sharedImport } from "@mdeo/language-shared";
import { CustomValueTypeImplementation } from "../custom-value/custom-value-type.js";
import type { CustomValueType } from "../custom-value/custom-value-type.js";
import type { CustomLambdaDetails, CustomLambdaKind } from "./custom-lambda-kind.js";
import type { LambdaType, Method, Property } from "../../config/type.js";
import { addDependsOnTypeEdge } from "../../service/customTypeGraph.js";

const {
    checkValueForConflict,
    TypeEqualityProblem: TypeEqualityProblemConstant,
    createKindConflict
} = sharedImport("typir");

/**
 * Interface for custom lambda types.
 * Represents a lambda (anonymous function) type with parameter and return types.
 */
export interface CustomLambdaType extends CustomValueType<CustomLambdaDetails<TypirSpecifics>> {
    /**
     * The kind that created this type
     */
    readonly kind: CustomLambdaKind<TypirSpecifics>;
}

/**
 * Custom lambda type implementation.
 * Represents a lambda (anonymous function) type with parameter and return types.
 */
export class CustomLambdaTypeImplementation
    extends CustomValueTypeImplementation<CustomLambdaDetails<TypirSpecifics>>
    implements CustomLambdaType
{
    declare readonly kind: CustomLambdaKind<TypirSpecifics>;

    /**
     * Cache for the nullable/non-nullable variant of this type
     */
    private nullInvertedTypeCache: CustomLambdaType | undefined = undefined;

    /**
     * Cached definition of this lambda type
     */
    private _definition: LambdaType | undefined = undefined;

    /**
     * Creates a new custom lambda type.
     * Automatically registers subtype relationships and initializes the type.
     *
     * @param kind The kind that created this type
     * @param details The lambda type details
     */
    constructor(kind: CustomLambdaKind<TypirSpecifics>, details: CustomLambdaDetails<TypirSpecifics>) {
        super(
            buildCustomLambdaIdentifier(details),
            details,
            kind.services,
            details.isNullable,
            kind.services.TypeDefinitions.getLambdaSuperTypes()
        );
        this.kind = kind;
        this.registerSubtypesAndConversion();
        this.defineTheInitializationProcessOfThisType({});
    }

    override get isNullable(): boolean {
        return this.details.isNullable;
    }

    override get asNullable(): CustomLambdaType {
        if (this.isNullable) {
            return this;
        }
        if (this.nullInvertedTypeCache != undefined) {
            return this.nullInvertedTypeCache;
        }
        const nullableType = this.kind.services.factory.CustomLambdas.getOrCreate({
            ...this.details,
            isNullable: true
        });
        this.nullInvertedTypeCache = nullableType;
        return nullableType;
    }

    override get asNonNullable(): CustomLambdaType {
        if (!this.isNullable) {
            return this;
        }
        if (this.nullInvertedTypeCache != undefined) {
            return this.nullInvertedTypeCache;
        }
        const nonNullableType = this.kind.services.factory.CustomLambdas.getOrCreate({
            ...this.details,
            isNullable: false
        });
        this.nullInvertedTypeCache = nonNullableType;
        return nonNullableType;
    }

    override get definition(): LambdaType {
        if (this._definition == undefined) {
            this._definition = {
                isNullable: this.isNullable,
                parameters: this.details.parameterTypes.map((paramType, idx) => ({
                    name: `param${idx}`,
                    type: paramType.definition
                })),
                returnType: this.details.returnType.definition
            };
        }
        return this._definition;
    }

    override getName(): string {
        return buildCustomLambdaName(this.details, false);
    }

    override getUserRepresentation(): string {
        return buildCustomLambdaIdentifier(this.details);
    }

    override analyzeTypeEqualityProblems(otherType: TypirType): TypirProblem[] {
        if (isCustomLambdaType(otherType)) {
            return this.analyzeLambdaTypeEquality(otherType);
        } else {
            return this.createKindMismatchProblem(otherType);
        }
    }

    override getLocalProperty(_memberName: string) {
        return undefined;
    }

    override getLocalMethod(_memberName: string) {
        return undefined;
    }

    override getLocalProperties(): Property[] {
        return [];
    }

    override getLocalMethods(): Method[] {
        return [];
    }

    /**
     * Analyze equality between two lambda types.
     *
     * @param otherType The other lambda type to compare with
     * @returns Array of type equality problems
     */
    private analyzeLambdaTypeEquality(otherType: CustomLambdaType): TypirProblem[] {
        const conflicts: TypirProblem[] = [];

        conflicts.push(...this.checkNullabilityConflict(otherType));
        conflicts.push(...this.checkReturnTypeConflict(otherType));
        conflicts.push(...this.checkParameterTypesConflict(otherType));

        return conflicts;
    }

    /**
     * Check if nullability conflicts between two lambda types.
     *
     * @param otherType The other lambda type
     * @returns Array of problems if nullability conflicts
     */
    private checkNullabilityConflict(otherType: CustomLambdaType): TypirProblem[] {
        return checkValueForConflict(this.isNullable, otherType.isNullable, "nullability");
    }

    /**
     * Check if return types conflict between two lambda types.
     *
     * @param otherType The other lambda type
     * @returns Array of problems if return types conflict
     */
    private checkReturnTypeConflict(otherType: CustomLambdaType): TypirProblem[] {
        if (this.details.returnType && otherType.details.returnType) {
            const returnTypeProblem = this.kind.services.Equality.getTypeEqualityProblem(
                this.details.returnType,
                otherType.details.returnType
            );
            return returnTypeProblem ? [returnTypeProblem] : [];
        } else {
            return checkValueForConflict(this.details.returnType, otherType.details.returnType, "return type");
        }
    }

    /**
     * Check if parameter types conflict between two lambda types.
     *
     * @param otherType The other lambda type
     * @returns Array of problems if parameter types conflict
     */
    private checkParameterTypesConflict(otherType: CustomLambdaType): TypirProblem[] {
        if (this.details.parameterTypes.length !== otherType.details.parameterTypes.length) {
            return checkValueForConflict(
                this.details.parameterTypes.length,
                otherType.details.parameterTypes.length,
                "parameter count"
            );
        }

        const conflicts: TypirProblem[] = [];
        for (let i = 0; i < this.details.parameterTypes.length; i++) {
            const paramProblem = this.kind.services.Equality.getTypeEqualityProblem(
                this.details.parameterTypes[i]!,
                otherType.details.parameterTypes[i]!
            );
            if (paramProblem) {
                conflicts.push(paramProblem);
            }
        }
        return conflicts;
    }

    /**
     * Create a kind mismatch problem when comparing with a non-lambda type.
     *
     * @param otherType The other type (not a lambda)
     * @returns Array with a single type equality problem
     */
    private createKindMismatchProblem(otherType: TypirType): TypirProblem[] {
        return [
            <TypeEqualityProblem>{
                $problem: TypeEqualityProblemConstant,
                type1: this,
                type2: otherType,
                subProblems: [createKindConflict(otherType, this)]
            }
        ];
    }

    /**
     * Registers dependency edges for return type, parameter types, and type arguments.
     * Must be called after this type has been added to the type graph.
     */
    registerTypeDependencies(): void {
        if (this.details.returnType instanceof CustomValueTypeImplementation) {
            this.registerTypeDependency(this.details.returnType);
        }

        for (const parameterType of this.details.parameterTypes) {
            this.registerTypeDependency(parameterType);
        }

        for (const typeArg of this.details.typeArgs.values()) {
            this.registerTypeDependency(typeArg);
        }
    }

    /**
     * Registers a single dependency edge.
     *
     * @param dependencyType The dependency type
     */
    private registerTypeDependency(dependencyType: CustomValueType): void {
        addDependsOnTypeEdge(this.services.infrastructure.Graph, this, dependencyType);
    }
}

/**
 * Type guard to check if a value is a CustomLambdaType.
 *
 * @param type The value to check
 * @returns true if the value is a CustomLambdaType
 */
export function isCustomLambdaType(type: unknown): type is CustomLambdaType {
    return type instanceof CustomLambdaTypeImplementation;
}

/**
 * Build a unique identifier for a custom lambda type.
 *
 * @param details The lambda type details
 * @returns The unique identifier string
 */
export function buildCustomLambdaIdentifier(details: CustomLambdaDetails<any>): string {
    return buildCustomLambdaName(details, true);
}

/**
 * Build a name for a custom lambda type.
 *
 * @param details The lambda type details
 * @param isIdentifier Whether to build a full identifier or just a name
 * @returns The name string in the format "(param1, param2) => returnType"
 */
export function buildCustomLambdaName(details: CustomLambdaDetails<any>, isIdentifier: boolean): string {
    const returnType = isIdentifier ? details.returnType.getIdentifier() : details.returnType.getName();
    const parameterTypes = details.parameterTypes
        .map((paramType) => (isIdentifier ? paramType.getIdentifier() : paramType.getName()))
        .join(", ");
    const lambdaType = `(${parameterTypes}) => ${returnType}`;
    return details.isNullable ? `(${lambdaType})?` : lambdaType;
}
