import type { Operation } from "@eclipse-glsp/protocol";
import type { NewLabelOperation } from "@mdeo/protocol-common";

/**
 * Operation to add a property value assignment to a model object instance.
 */
export interface AddPropertyValueOperation extends NewLabelOperation {
    /**
     * Operation kind discriminator.
     */
    kind: "addPropertyValue";

    /**
     * Identifier of the object node to update.
     */
    objectId: string;
}

/**
 * Namespace helpers for add-property-value operations.
 */
export namespace AddPropertyValueOperation {
    /**
     * Operation kind constant.
     */
    export const KIND = "addPropertyValue";

    /**
     * Identifier carried in `newLabelOperationKind` by a new property value placeholder label,
     * by which the label view recognises a label it has to commit through this operation.
     */
    export const NEW_LABEL_KIND = "property-value-edit";

    /**
     * Payload for creating an add-property-value operation.
     */
    export interface Options {
        /**
         * Identifier of the object node to update.
         */
        objectId: string;

        /**
         * The full edited text (e.g. {@code propName = value}) to insert into the model.
         * The server reads this verbatim and inserts it into the source file.
         */
        labelText?: string;
    }

    /**
     * Creates an add-property-value operation.
     *
     * @param options Operation payload
     * @returns Operation instance
     */
    export function create(options: Options): AddPropertyValueOperation {
        return {
            kind: KIND,
            isOperation: true,
            objectId: options.objectId,
            parentElementId: options.objectId,
            labelText: options.labelText ?? ""
        };
    }

    /**
     * Checks whether an operation is an add-property-value operation.
     *
     * @param operation Operation to check
     * @returns True when operation kind matches
     */
    export function is(operation: Operation): operation is AddPropertyValueOperation {
        return operation.kind === KIND;
    }
}
