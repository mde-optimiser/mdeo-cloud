import type { Operation } from "@eclipse-glsp/protocol";
import type { NewLabelOperation } from "@mdeo/protocol-common";

/**
 * Operation to add a property to a class.
 *
 * This is a high-complexity context action that typically creates a new
 * property label and may create a property compartment when missing.
 */
export interface AddPropertyOperation extends NewLabelOperation {
    /**
     * The operation kind discriminator.
     */
    kind: "addProperty";

    /**
     * The identifier of the class to update.
     */
    classId: string;

    /**
     * The name of the property to create.
     */
    propertyName: string;
}

/**
 * Namespace helpers for {@link AddPropertyOperation}.
 */
export namespace AddPropertyOperation {
    /**
     * Discriminator constant for this operation type.
     */
    export const KIND = "addProperty";

    /**
     * Identifier carried in `newLabelOperationKind` by a new property placeholder label,
     * by which the label view recognises a label it has to commit through this operation.
     */
    export const NEW_LABEL_KIND = "property-name-edit";

    /**
     * Parameters used to create an add-property operation.
     */
    export interface Options {
        /**
         * The identifier of the class to update.
         */
        classId: string;

        /**
         * The name of the property to create.
         */
        propertyName?: string;
    }

    /**
     * Create an {@link AddPropertyOperation}.
     *
     * @param options The operation payload.
     * @returns A new add-property operation.
     */
    export function create(options: Options): AddPropertyOperation {
        const value = options.propertyName ?? "";
        return {
            kind: KIND,
            isOperation: true,
            classId: options.classId,
            propertyName: value,
            parentElementId: options.classId,
            labelText: value
        };
    }

    /**
     * Check whether an operation is an {@link AddPropertyOperation}.
     *
     * @param operation The operation to inspect.
     * @returns True if the operation is an add-property operation.
     */
    export function is(operation: Operation): operation is AddPropertyOperation {
        return operation.kind === KIND;
    }
}
