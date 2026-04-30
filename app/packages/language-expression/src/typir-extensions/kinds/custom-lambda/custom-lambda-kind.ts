import type { Kind } from "typir";
import type { TypirSpecifics } from "typir";
import {
    buildCustomLambdaIdentifier,
    CustomLambdaTypeImplementation,
    type CustomLambdaType
} from "./custom-lambda-type.js";
import type { ExtendedTypirServices } from "../../service/extendedTypirServices.js";
import type { CustomValueType, CustomValueTypeDetail } from "../custom-value/custom-value-type.js";
import type { CustomVoidType } from "../custom-void/custom-void-type.js";

/**
 * Type details specific to custom lambda types.
 *
 * @template Specifics Language-specific types extending TypirSpecifics
 */
export interface CustomLambdaDetails<Specifics extends TypirSpecifics> extends CustomValueTypeDetail<Specifics> {
    /**
     * The resolved return type of the lambda
     */
    returnType: CustomValueType | CustomVoidType;

    /**
     * The resolved parameter types of the lambda
     */
    parameterTypes: CustomValueType[];

    /**
     * Whether this lambda type is nullable
     */
    isNullable: boolean;
}

/**
 * Factory service interface for creating custom lambda types.
 *
 * @template Specifics Language-specific types extending TypirSpecifics
 */
export interface CustomLambdaFactoryService<Specifics extends TypirSpecifics> {
    /**
     * Get an existing custom lambda type or create a new one.
     * Uses caching to ensure type identity.
     *
     * @param details The lambda type details
     * @returns The custom lambda type instance
     */
    getOrCreate(details: CustomLambdaDetails<Specifics>): CustomLambdaType;
}

/**
 * The kind name for custom lambda types
 */
export const CustomLambdaKindName = "CustomLambda";

/**
 * Kind implementation for custom lambda types.
 * Manages creation and caching of custom lambda type instances.
 *
 * @template Specifics Language-specific types extending TypirSpecifics
 */
export class CustomLambdaKind<Specifics extends TypirSpecifics> implements Kind, CustomLambdaFactoryService<Specifics> {
    readonly $name: string = CustomLambdaKindName;

    /**
     * Creates a new custom lambda kind.
     * Automatically registers itself with the kind registry.
     *
     * @param services Extended Typir services
     */
    constructor(readonly services: ExtendedTypirServices<Specifics>) {
        services.infrastructure.Kinds.register(this);
    }

    /**
     * Get an existing custom lambda type or create a new one.
     * Types are cached by their identifier to ensure type identity.
     *
     * @param details The lambda type details
     * @returns The custom lambda type instance
     */
    getOrCreate(details: CustomLambdaDetails<Specifics>): CustomLambdaType {
        const key = this.buildIdentifier(details);
        const existingType = this.services.infrastructure.Graph.getType(key);
        if (existingType != undefined) {
            return existingType as CustomLambdaType;
        } else {
            const newType = new CustomLambdaTypeImplementation(
                this as unknown as CustomLambdaKind<TypirSpecifics>,
                details
            );
            this.services.infrastructure.Graph.addNode(newType);
            newType.registerTypeDependencies();
            return newType;
        }
    }

    /**
     * Build an identifier for a lambda type.
     *
     * @param details The lambda type details
     * @returns The unique identifier string
     */
    private buildIdentifier(details: CustomLambdaDetails<Specifics>): string {
        return buildCustomLambdaIdentifier(details);
    }
}
