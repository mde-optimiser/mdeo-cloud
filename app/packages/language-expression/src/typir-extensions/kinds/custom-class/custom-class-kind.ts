import type { Kind, TypirSpecifics } from "typir";
import {
    buildCustomClassIdentifier,
    CustomClassTypeImplementation,
    type CustomClassType
} from "./custom-class-type.js";
import type { ClassType } from "../../config/type.js";
import type { ExtendedTypirServices } from "../../service/extendedTypirServices.js";
import type { CustomValueTypeDetail } from "../custom-value/custom-value-type.js";

/**
 * Type details specific to custom class types.
 *
 * @template Specifics Language-specific types extending TypirSpecifics
 */
export interface CustomClassDetails<Specifics extends TypirSpecifics> extends CustomValueTypeDetail<Specifics> {
    /**
     * The class type definition containing properties, methods, and inheritance information
     */
    definition: ClassType;

    /**
     * Whether this class type is nullable
     */
    isNullable: boolean;
}

/**
 * Factory service interface for creating custom class types.
 *
 * @template Specifics Language-specific types extending TypirSpecifics
 */
export interface CustomClassFactoryService<Specifics extends TypirSpecifics> {
    /**
     * Get an existing custom class type or create a new one.
     * Uses caching to ensure type identity.
     *
     * @param details The class type details
     * @returns The custom class type instance
     */
    getOrCreate(details: CustomClassDetails<Specifics>): CustomClassType;
}

/**
 * The kind name for custom class types
 */
export const CustomClassKindName = "CustomClass";

/**
 * Kind implementation for custom class types.
 * Manages creation and caching of custom class type instances.
 *
 * @template Specifics Language-specific types extending TypirSpecifics
 */
export class CustomClassKind<Specifics extends TypirSpecifics> implements Kind, CustomClassFactoryService<Specifics> {
    readonly $name: string = CustomClassKindName;

    /**
     * Creates a new custom class kind.
     * Automatically registers itself with the kind registry.
     *
     * @param services Extended Typir services
     */
    constructor(readonly services: ExtendedTypirServices<Specifics>) {
        services.infrastructure.Kinds.register(this);
    }

    /**
     * Get an existing custom class type or create a new one.
     * Types are cached by their identifier to ensure type identity.
     *
     * @param details The class type details
     * @returns The custom class type instance
     */
    getOrCreate(details: CustomClassDetails<Specifics>): CustomClassType {
        const key = buildCustomClassIdentifier(details);
        const existingType = this.services.infrastructure.Graph.getType(key);
        if (existingType != undefined) {
            return existingType as CustomClassType;
        } else {
            const newType = new CustomClassTypeImplementation(
                this as unknown as CustomClassKind<TypirSpecifics>,
                details
            );
            this.services.infrastructure.Graph.addNode(newType);
            newType.registerTypeDependencies();
            return newType;
        }
    }
}
